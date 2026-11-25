package de.juliandigitalproducts.notitocal.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.places.LocationSampler
import de.juliandigitalproducts.notitocal.places.PlaceStore
import de.juliandigitalproducts.notitocal.places.TimelineImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAPS_PACKAGE = "com.google.android.apps.maps"
private const val MAPS_TIMELINE = "https://www.google.com/maps/timeline"

/**
 * The on-device location history: what it is good for (and what not), importing a Google Maps timeline export and
 * the optional periodic location samples. Shown under "Places & addresses" and in the setup wizard.
 */
@Composable
fun LocationHistoryCards() {
  val context = LocalContext.current
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  val s by Ui.app.settings.state.collectAsState()
  var changes by remember { mutableIntStateOf(0) }
  val places by produceState(0, changes) { value = withContext(Dispatchers.IO) { runSafely { PlaceStore.get(context).count() }.getOrDefault(0) } }
  var importing by remember { mutableStateOf(false) }
  var imported by remember { mutableIntStateOf(0) }
  var importDone by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<String?>(null) }
  var confirmClear by remember { mutableStateOf(false) }
  val tick = rememberResumeTick()
  val canSample = remember(tick, changes) { LocationSampler.canSample(context) }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    importing = true
    importDone = false
    imported = 0
    scope.launch(Dispatchers.IO) {
      val r = runSafely {
        context.contentResolver.openInputStream(uri)?.use { TimelineImport.run(it, PlaceStore.get(context)) { n -> imported = n } }
      }.getOrNull()
      result = if (r == null || r.visits == 0) resources.getString(R.string.loc_import_failed)
      else resources.getString(R.string.loc_import_done, r.visits, r.newPlaces)
      changes++
      importDone = true
    }
  }
  val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    changes++
    if (ok) Ui.update { it.copy(locationSampling = true) }
  }
  val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
    // Android asks for "Allow all the time" separately, after the normal location permission.
    if (perms.values.any { it }) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
  }

  SettingGroupCard(title = stringResource(R.string.loc_hist_title), emphasized = true) {
    Hint(stringResource(R.string.loc_hist_body))
    CardContent {
      ProCon(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, listOf(R.string.loc_hist_pro_1, R.string.loc_hist_pro_2, R.string.loc_hist_pro_3))
      Spacer(Modifier.size(6.dp))
      ProCon(Icons.Filled.Info, MaterialTheme.colorScheme.onSurfaceVariant, listOf(R.string.loc_hist_con_1, R.string.loc_hist_con_2, R.string.loc_hist_con_3))
    }
    HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner, vertical = 4.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner), verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.loc_hist_count, places), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
      if (places > 0) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.loc_hist_clear)) }
    }
  }

  SettingGroupCard(title = stringResource(R.string.loc_import_title)) {
    Hint(stringResource(R.string.loc_import_steps))
    // Both at once: who already has an export imports it right away, the others open Maps first.
    Row(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilledTonalButton(onClick = {
        runSafely { context.startActivity(Intent(Intent.ACTION_VIEW, MAPS_TIMELINE.toUri()).setPackage(MAPS_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
          .onFailure { Ui.openUrl(context, MAPS_TIMELINE) }
      }, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.Map, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.loc_import_open_maps), maxLines = 1)
      }
      Button(onClick = { picker.launch(arrayOf("application/json", "application/zip", "*/*")) }, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.loc_import_btn), maxLines = 1)
      }
    }
  }

  SettingGroupCard(title = stringResource(R.string.loc_sample_title)) {
    SettingSwitch(stringResource(R.string.loc_sample_switch), s.locationSampling && canSample, stringResource(R.string.loc_sample_body)) { on ->
      when {
        !on -> Ui.update { it.copy(locationSampling = false) }
        canSample -> Ui.update { it.copy(locationSampling = true) }
        else -> foreground.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
      }
    }
    CardContent {
      Text(stringResource(R.string.loc_sample_interval), style = MaterialTheme.typography.labelMedium)
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        LocationSampler.INTERVALS.forEachIndexed { i, minutes ->
          SegmentedButton(
            selected = s.locationSampleMinutes == minutes,
            onClick = { Ui.update { it.copy(locationSampleMinutes = minutes) } },
            shape = SegmentedButtonDefaults.itemShape(i, LocationSampler.INTERVALS.size),
          ) { Text(intervalLabel(minutes), style = MaterialTheme.typography.labelSmall) }
        }
      }
      if (s.locationSampling && !canSample) {
        Text(stringResource(R.string.loc_sample_needs_always), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text(stringResource(R.string.grant)) }
      }
    }
  }

  if (importing) AiProgressDialog(
    title = stringResource(R.string.loc_import_title),
    statusOverride = stringResource(R.string.loc_import_progress, imported),
    isFinished = importDone,
    onDismiss = { importing = false },
  )
  if (!importing) result?.let { msg ->
    AlertDialog(
      onDismissRequest = { result = null },
      confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(R.string.ok)) } },
      title = { Text(stringResource(R.string.loc_import_title)) },
      text = { Text(msg) },
    )
  }
  if (confirmClear) AlertDialog(
    onDismissRequest = { confirmClear = false },
    title = { Text(stringResource(R.string.loc_hist_clear)) },
    text = { Text(stringResource(R.string.loc_hist_clear_body)) },
    confirmButton = {
      TextButton(onClick = {
        confirmClear = false
        scope.launch(Dispatchers.IO) { runSafely { PlaceStore.get(context).clear() }; changes++ }
      }) { Text(stringResource(R.string.delete)) }
    },
    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
  )
}

@Composable
private fun intervalLabel(minutes: Int): String =
  if (minutes < 60) stringResource(R.string.interval_minutes, minutes) else stringResource(R.string.interval_hours, minutes / 60)

@Composable
private fun ProCon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, lines: List<Int>) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    lines.forEach { line ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = tint)
        Text(stringResource(line), style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}
