package de.juliandigitalproducts.notitocal.widget

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.MediaProcessor
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.notify.Notifier
import de.juliandigitalproducts.notitocal.ui.MainActivity
import de.juliandigitalproducts.notitocal.ui.theme.NotiToCalTheme
import kotlinx.coroutines.launch
import java.io.File
import de.juliandigitalproducts.notitocal.model.runSafely

class QuickCaptureActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      NotiToCalTheme {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
          contentAlignment = Alignment.Center,
        ) {
          run {
            CameraCaptureHandler(
              onDismiss = { finish() },
              onDone = { logId ->
                if (logId != null) {
                  val intent = Intent(this@QuickCaptureActivity, MainActivity::class.java).apply {
                    putExtra(Notifier.EXTRA_ENTRY, logId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                  }
                  startActivity(intent)
                }
                finish()
              },
            )
          }
        }
      }
    }
  }

  @Composable
  private fun CameraCaptureHandler(onDismiss: () -> Unit, onDone: (Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    // Saveable: while the camera app is open the system may end this process; the result then arrives in a new
    // activity, which must still know the photo file (and must not open the camera a second time).
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var cameraRequested by rememberSaveable { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
      if (success && photoUri != null) {
        isProcessing = true
        scope.launch {
          val app = application as NotiToCalApp
          val result = runSafely {
            MediaProcessor.processImage(app, photoUri!!, SourceType.SHARED_IMAGE, userInitiated = true)
          }.getOrNull()
          // The text is in the log now; the photo itself is not kept (C23).
          photoPath?.let { File(it).delete() }
          VoiceMemosWidgetProvider.updateAll(this@QuickCaptureActivity)
          onDone(result?.logId)
        }
      } else {
        onDismiss()
      }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        val file = File(cacheDir, "photos/photo_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(this@QuickCaptureActivity, "$packageName.fileprovider", file)
        photoPath = file.absolutePath
        photoUri = uri
        takePictureLauncher.launch(uri)
      } else {
        Toast.makeText(this@QuickCaptureActivity, R.string.perm_camera_required, Toast.LENGTH_SHORT).show()
        onDismiss()
      }
    }

    LaunchedEffect(Unit) {
      if (!cameraRequested) {
        cameraRequested = true
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
      }
    }

    if (isProcessing) {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Row(
          modifier = Modifier.padding(24.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(28.dp))
          Text(stringResource(R.string.share_reading_image), style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
  }

  companion object {
    const val ACTION_QUICK_CAMERA = "de.juliandigitalproducts.notitocal.action.QUICK_CAMERA"
  }
}
