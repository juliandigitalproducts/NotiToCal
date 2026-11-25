package de.juliandigitalproducts.notitocal.capture

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.notify.Notifier
import de.juliandigitalproducts.notitocal.ui.MainActivity
import de.juliandigitalproducts.notitocal.ui.theme.NotiToCalTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.juliandigitalproducts.notitocal.model.runSafely

/** Receives shared text and screenshots/images (with their caption). Audio is not accepted: Anti-Vocale transcribes it. */
class ShareActivity : ComponentActivity() {
  private var message by mutableStateOf<String?>(null)
  private var error by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val app = application as NotiToCalApp
    setContent {
      NotiToCalTheme {
        AlertDialog(
          onDismissRequest = { finish() },
          confirmButton = { TextButton(onClick = { finish() }) { Text(stringResource(if (error != null) R.string.close else R.string.run_in_background)) } },
          title = { Text(stringResource(R.string.app_name)) },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              if (error == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(message ?: stringResource(R.string.analyzing))
              } else Text(error!!, Modifier.padding(top = 4.dp))
            }
          },
        )
      }
    }
    if (savedInstanceState == null) handle(app, intent)
  }

  private fun handle(app: NotiToCalApp, intent: Intent) {
    val type = intent.type.orEmpty()
    val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    val referrerPkg = referrer?.host.orEmpty()
    val label = if (referrerPkg.isNotBlank()) NotificationCaptureService.appLabel(this, referrerPkg) else ""

    // Only content:// from other apps: file:// URIs or our own provider could pull app-private files into the analysis.
    if (stream != null && !isForeignContent(stream)) { error = getString(R.string.share_unreadable); return }
    val isIcs = type.equals("text/calendar", ignoreCase = true) || stream?.lastPathSegment?.endsWith(".ics", ignoreCase = true) == true
    // Only images or .ics calendar invitations come with a file; anything else (e.g. audio) has no analysis path.
    if (stream != null && !type.startsWith("image/") && !isIcs) { error = getString(R.string.share_unreadable); return }
    message = getString(
      when {
        type.startsWith("image/") -> R.string.share_reading_image
        else -> R.string.analyzing
      },
    )
    // Copy the shared file first (the read grant ends when this activity finishes) – off the main thread, a large
    // photo or a cloud image would otherwise freeze the screen.
    lifecycleScope.launch {
      val local = withContext(Dispatchers.IO) {
        runSafely { stream?.let { MediaProcessor.copyToCache(this@ShareActivity, it, "share_${System.currentTimeMillis()}") } }.getOrNull()
      }
      if (stream != null && local == null) { error = getString(R.string.share_unreadable); return@launch }
      analyse(app, type, isIcs, local, text, referrerPkg, label)
    }
  }

  private fun analyse(app: NotiToCalApp, type: String, isIcs: Boolean, local: Uri?, text: String, referrerPkg: String, label: String) {
    app.scope.launch {
      val id = runSafely {
        when {
          isIcs && local != null -> {
            val rawIcs = contentResolver.openInputStream(local)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val parsed = IcsCalendarSource.parseIcsToText(rawIcs)
            app.pipeline.process(
              Captured(SourceType.CALENDAR_ICS, packageName = referrerPkg, appLabel = label.ifBlank { getString(R.string.source_ics_title) }, text = parsed),
              userInitiated = true,
            )?.logId
          }
          // An image shared with a message or caption is analysed together with that text.
          type.startsWith("image/") && local != null -> MediaProcessor.processImage(app, local, SourceType.SHARED_IMAGE, userInitiated = true, caption = text)?.logId
          text.isNotBlank() -> app.pipeline.process(
            Captured(SourceType.SHARED_TEXT, packageName = referrerPkg, appLabel = label, text = text), userInitiated = true,
          )?.logId
          else -> null
        }
      }.onFailure { e -> runOnUiThread { error = e.message ?: e.javaClass.simpleName } }.getOrNull()
      // The cache copy was only needed for the analysis (privacy: no shared photos left behind).
      local?.path?.let { runSafely { java.io.File(it).delete() } }
      // Read here, not on the main thread.
      val entry = id?.let { app.log.get(it) }

      runOnUiThread {
        when {
          id != null && !isFinishing -> {
            startActivity(Intent(this@ShareActivity, MainActivity::class.java).putExtra(Notifier.EXTRA_ENTRY, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
          }
          id != null -> entry?.let { e -> Notifier.confirm(app, id, e.detection, Captured(e.source, e.packageName, e.appLabel, e.sender, e.text)) }
          error == null -> error = getString(R.string.share_nothing_found)
        }
      }
    }
  }

  private fun isForeignContent(uri: Uri): Boolean =
    uri.scheme == android.content.ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith(packageName) != true
}
