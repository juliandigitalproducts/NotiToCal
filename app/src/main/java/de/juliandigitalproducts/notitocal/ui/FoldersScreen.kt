package de.juliandigitalproducts.notitocal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.FolderWatch
import de.juliandigitalproducts.notitocal.capture.ScreenshotFolders
import kotlinx.coroutines.launch
import de.juliandigitalproducts.notitocal.model.runSafely

/** Screenshot folders. Voice messages are read from Anti-Vocale's export folder (Sources → Voice messages). */

@Composable
fun FoldersScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var confirm by remember { mutableStateOf<String?>(null) }
  var scanNote by remember { mutableStateOf<String?>(null) }
  var scanning by remember { mutableStateOf(false) }
  var scanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
  var scanLabel by remember { mutableStateOf<String?>(null) }
  val foundLabel = stringResource(R.string.folders_found)

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    if (runSafely { FolderWatch.persist(context, uri) }.isFailure) return@rememberLauncherForActivityResult
    Ui.update { current ->
      current.copy(screenshotFolderUris = (current.screenshotFolderUris + uri.toString()).distinct(), screenshotsEnabled = true)
    }
    Ui.app.scope.launch { ScreenshotFolders.baseline(Ui.app, uri.toString()) }
  }

  SubScreen(stringResource(R.string.monitored_folders), onBack) {
    Hint(stringResource(R.string.monitored_folders_body))
    Hint(stringResource(R.string.folders_remove_body))

    SettingGroupCard(title = stringResource(R.string.folders_screenshots), emphasized = true) {
      SettingSwitch(stringResource(R.string.screenshots_enabled), s.screenshotsEnabled, stringResource(R.string.screenshots_body)) { v ->
        Ui.update { it.copy(screenshotsEnabled = v) }
      }
      FolderList(s.screenshotFolderUris, onRemove = { confirm = it })
      Row(Modifier.padding(horizontal = Gaps.Inner), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
          picker.launch(ScreenshotFolders.initialUri())
        }) { Text(stringResource(R.string.folders_add)) }
        if (s.screenshotFolderUris.isNotEmpty()) OutlinedButton(enabled = !scanning, onClick = {
          scanning = true
          scanProgress = null
          scanLabel = null
          scope.launch {
            val n = ScreenshotFolders.scan(Ui.app, maxAgeMinutes = 24 * 60) { curr, tot, name ->
              scanProgress = curr to tot
              scanLabel = name
            }
            scanNote = foundLabel.replace("%1\$d", n.toString())
            scanning = false
            scanProgress = null
            scanLabel = null
          }
        }) { Text(stringResource(R.string.folders_check)) }
      }
      Spacer(Modifier.height(8.dp))
    }
  }

  confirm?.let { uri ->
    AlertDialog(
      onDismissRequest = { confirm = null },
      title = { Text(stringResource(R.string.folders_remove)) },
      text = { Text(stringResource(R.string.folders_remove_body)) },
      confirmButton = {
        TextButton(onClick = {
          Ui.update { current -> current.copy(screenshotFolderUris = current.screenshotFolderUris.filter { it != uri }) }
          // The Anti-Vocale folder may be the same tree; keep its access in that case.
          if (uri != s.antiVocaleFolderUri) FolderWatch.release(context, uri)
          confirm = null
        }) { Text(stringResource(R.string.folders_remove)) }
      },
      dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
  if (scanning) {
    val progressRatio = scanProgress?.let { (curr, tot) -> if (tot > 0) curr / tot.toFloat() else null }
    val hint = scanProgress?.let { (curr, tot) ->
      resources.getString(R.string.scanning_file_progress, curr, tot, scanLabel.orEmpty())
    } ?: stringResource(R.string.busy_hint_wait)
    ProgressDialog(
      message = stringResource(R.string.busy_scanning),
      hint = hint,
      progress = progressRatio,
    )
  }
  scanNote?.let { note ->
    AlertDialog(
      onDismissRequest = { scanNote = null },
      confirmButton = { TextButton(onClick = { scanNote = null }) { Text(stringResource(R.string.ok)) } },
      text = { Text(note) },
    )
  }
}

@Composable
private fun FolderList(uris: List<String>, onRemove: (String) -> Unit) {
  if (uris.isEmpty()) {
    Hint(stringResource(R.string.folders_empty))
    return
  }
  uris.forEach { uri ->
    ListItem(
      headlineContent = { Text(FolderWatch.label(uri), style = MaterialTheme.typography.bodyLarge) },
      supportingContent = { Text(uri, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
      trailingContent = {
        IconButton(onClick = { onRemove(uri) }) { Icon(Icons.Filled.Delete, stringResource(R.string.folders_remove)) }
      },
      colors = cardListItemColors(),
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
