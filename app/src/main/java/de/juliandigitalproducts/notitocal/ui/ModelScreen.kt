package de.juliandigitalproducts.notitocal.ui

import android.content.Intent
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.analysis.ModelLoadStatus
import de.juliandigitalproducts.notitocal.analysis.ModelManager
import de.juliandigitalproducts.notitocal.analysis.ModelNote
import de.juliandigitalproducts.notitocal.analysis.ModelSpec
import de.juliandigitalproducts.notitocal.analysis.OnDeviceLlm
import de.juliandigitalproducts.notitocal.analysis.SharedModel
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.SecretKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import de.juliandigitalproducts.notitocal.model.runSafely

@Composable
fun ModelScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var tick by remember { mutableIntStateOf(0) }
  var importing by remember { mutableStateOf(false) }
  var sharedError by remember { mutableStateOf(false) }
  var sort by rememberSaveable { mutableIntStateOf(0) }
  val ram = remember { ModelManager.deviceRamGb(context) }
  val installed = remember(tick) { ModelManager.installed(context) }
  val states = remember(tick) { ModelManager.catalog.associateWith { ModelManager.state(context, it) } }

  // Poll DownloadManager while something is downloading.
  LaunchedEffect(Unit) {
    while (true) { delay(1500); tick++ }
  }

  // A finished download becomes the selected model when none is selected yet.
  LaunchedEffect(installed, states) {
    if (Ui.app.settings.current.onDeviceModelFile.isNotBlank() || Ui.app.settings.current.onDeviceModelUri.isNotBlank()) return@LaunchedEffect
    val ready = installed.firstOrNull { f -> states.none { (spec, st) -> spec.fileName == f.name && st?.running == true } } ?: return@LaunchedEffect
    Ui.update { it.copy(onDeviceModelFile = ready.absolutePath, onDeviceTemplate = ModelManager.templateFor(ready.name), engine = AiEngine.ON_DEVICE) }
  }

  val sharedPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    val taken = runSafely { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
    if (!taken || !SharedModel.accessible(context, uri.toString())) {
      sharedError = true
      return@rememberLauncherForActivityResult
    }
    val name = SharedModel.displayName(context, uri)
    OnDeviceLlm.release()
    ModelLoadStatus.sharedLoadError = null
    sharedError = false
    Ui.update {
      it.copy(
        onDeviceModelUri = uri.toString(),
        onDeviceModelName = name,
        onDeviceTemplate = ModelManager.templateFor(name),
        engine = AiEngine.ON_DEVICE,
      )
    }
  }

  val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      importing = true
      scope.launch {
        runSafely { ModelManager.import(context, uri) }.onSuccess { f ->
          Ui.update { it.copy(onDeviceModelFile = f.absolutePath, onDeviceTemplate = ModelManager.templateFor(f.name), engine = AiEngine.ON_DEVICE) }
        }
        importing = false
        tick++
      }
    }
  }

  val sharedOk = s.onDeviceModelUri.isNotBlank() && SharedModel.accessible(context, s.onDeviceModelUri)
  val selectedReady = sharedOk || (s.onDeviceModelFile.isNotBlank() && installed.any { it.absolutePath == s.onDeviceModelFile })
  val selectedLabel = when {
    sharedOk -> s.onDeviceModelName.ifBlank { stringResource(R.string.model_use_shared) }
    s.onDeviceModelFile.isNotBlank() -> s.onDeviceModelFile.substringAfterLast('/')
    else -> ""
  }
  SubScreen(stringResource(R.string.models_title), onBack, bottomBar = {
    if (selectedReady) Surface(tonalElevation = 3.dp) {
      Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(Gaps.Inner), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
          stringResource(R.string.model_selected, selectedLabel),
          Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
        )
        Button(onClick = onBack) { Text(stringResource(R.string.next)) }
      }
    }
  }) {
    Hint(stringResource(R.string.models_intro))

    SettingGroupCard(title = stringResource(R.string.model_use_shared), emphasized = true) {
      Hint(stringResource(R.string.model_shared_body))
      if (sharedOk) Hint(stringResource(R.string.model_shared_selected, s.onDeviceModelName.ifBlank { s.onDeviceModelUri }))
      if (s.onDeviceModelUri.isNotBlank() && !sharedOk) {
        Text(stringResource(R.string.model_shared_missing), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = Gaps.Inner))
      }
      if (sharedError || !ModelLoadStatus.sharedLoadError.isNullOrBlank()) {
        Text(stringResource(R.string.model_shared_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = Gaps.Inner))
      }
      OutlinedButton(onClick = { sharedPicker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
        Text(stringResource(R.string.model_use_shared))
      }
    }

    SettingGroupCard(title = stringResource(R.string.installed_models)) {
      if (installed.isEmpty()) Hint(stringResource(R.string.no_models))
      installed.forEach { f ->
        val selected = s.onDeviceModelFile == f.absolutePath
        val downloading = states.entries.any { (spec, st) -> spec.fileName == f.name && st?.running == true }
        if (!downloading) ListItem(
          colors = cardListItemColors(),
          headlineContent = { Text(f.name) },
          supportingContent = { Text(Formatter.formatShortFileSize(context, f.length())) },
          leadingContent = {
            RadioButton(selected = selected && !sharedOk, onClick = {
              OnDeviceLlm.release()
              if (s.onDeviceModelUri.isNotBlank()) runSafely {
                context.contentResolver.releasePersistableUriPermission(s.onDeviceModelUri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
              Ui.update {
                it.copy(onDeviceModelFile = f.absolutePath, onDeviceModelUri = "", onDeviceModelName = "", onDeviceTemplate = ModelManager.templateFor(f.name), engine = AiEngine.ON_DEVICE)
              }
            })
          },
          trailingContent = {
            IconButton(onClick = {
              if (selected) { OnDeviceLlm.release(); Ui.update { it.copy(onDeviceModelFile = "") } }
              f.delete(); tick++
            }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
          },
        )
      }
      OutlinedButton(enabled = !importing, onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
        Text(stringResource(when {
          importing -> R.string.importing
          s.onDeviceModelUri.isNotBlank() || sharedError -> R.string.model_import_fallback
          else -> R.string.import_model
        }))
      }
      if (importing) BusyRow(stringResource(R.string.busy_importing))
    }

    SettingGroupCard(title = stringResource(R.string.download_models)) {
      SecretField(stringResource(R.string.hf_token), SecretKeys.HF_TOKEN, stringResource(R.string.hf_token_hint))
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
        listOf(R.string.sort_recommended, R.string.sort_fastest, R.string.sort_best).forEachIndexed { i, label ->
          SegmentedButton(selected = sort == i, onClick = { sort = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(label)) }
        }
      }
      Hint(stringResource(R.string.device_ram, ram))
    }
    val sorted = when (sort) {
      1 -> ModelManager.catalog.sortedWith(compareByDescending<ModelSpec> { it.speed }.thenByDescending { it.quality })
      2 -> ModelManager.catalog.sortedWith(compareByDescending<ModelSpec> { it.quality }.thenByDescending { it.speed })
      else -> ModelManager.catalog.sortedWith(compareByDescending<ModelSpec> { it.recommended }.thenByDescending { if (it.ramGb <= ram) it.quality * 2 + it.speed else 0 })
    }
    sorted.forEach { spec ->
      val st = states[spec]
      val file = ModelManager.fileFor(context, spec)
      val done = file.exists() && st?.running != true && file.length() > 1_000_000
      Card(
        Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
      ) {
        Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row {
            Text(spec.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (done) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
          }
          Rating(stringResource(R.string.rating_speed), spec.speed)
          Rating(stringResource(R.string.rating_quality), spec.quality)
          Text(
            listOfNotNull(
              "~${spec.sizeMb} MB",
              stringResource(R.string.needs_ram, spec.ramGb),
              if (spec.recommended) stringResource(R.string.recommended) else null,
              if (spec.needsToken) stringResource(R.string.needs_hf_token) else null,
            ).joinToString(" · ") + spec.notes.joinToString("") { " · " + noteLabel(context, it) },
            style = MaterialTheme.typography.bodySmall,
          )
          if (spec.ramGb > ram) Text(stringResource(R.string.ram_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          when {
            st?.running == true -> {
              if (st.total > 0) LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth())
              else LinearProgressIndicator(Modifier.fillMaxWidth())
              Text(stringResource(R.string.download_background_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Formatter.formatShortFileSize(context, st.done) + " / " + if (st.total > 0) Formatter.formatShortFileSize(context, st.total) else "?", Modifier.weight(1f))
                TextButton(onClick = { ModelManager.cancel(context, spec); tick++ }) { Text(stringResource(R.string.cancel)) }
              }
            }
            done -> if (s.onDeviceModelFile != file.absolutePath || sharedOk) OutlinedButton(onClick = {
              OnDeviceLlm.release()
              if (s.onDeviceModelUri.isNotBlank()) runSafely {
                context.contentResolver.releasePersistableUriPermission(s.onDeviceModelUri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
              Ui.update {
                it.copy(onDeviceModelFile = file.absolutePath, onDeviceModelUri = "", onDeviceModelName = "", onDeviceTemplate = spec.template, engine = AiEngine.ON_DEVICE)
              }
            }) { Text(stringResource(R.string.use_model)) }
            else -> {
              if (st?.failed == true) Text(stringResource(R.string.download_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
              Button(onClick = {
                ModelManager.cancel(context, spec)
                ModelManager.download(context, spec, Ui.app.secrets.get(SecretKeys.HF_TOKEN))
                tick++
              }) { Text(stringResource(R.string.download)) }
            }
          }
        }
      }
    }
    Hint(stringResource(R.string.models_custom_hint))
  }
}

@Composable
private fun Rating(label: String, value: Int) {
  Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.35f))
    Text(
      "●".repeat(value) + "○".repeat(5 - value),
      style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.65f),
    )
  }
}

private fun noteLabel(context: android.content.Context, n: ModelNote) = context.getString(when (n) {
  ModelNote.ENGLISH_ONLY -> R.string.note_english_only
  ModelNote.THINKS_SLOWLY -> R.string.note_thinks
  ModelNote.MULTILINGUAL -> R.string.note_multilingual
  ModelNote.TEST_ONLY -> R.string.note_test_only
})
