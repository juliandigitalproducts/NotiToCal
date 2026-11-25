package de.juliandigitalproducts.notitocal.ui

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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Alignment
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.SettingsBackup
import de.juliandigitalproducts.notitocal.data.BackupCrypto
import kotlinx.coroutines.Dispatchers
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import de.juliandigitalproducts.notitocal.places.PlaceStore
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.BuildConfig
import de.juliandigitalproducts.notitocal.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import de.juliandigitalproducts.notitocal.model.runSafely

// ------------------------------------------------------------------ Privacy & about

private const val WEBSITE = "https://notitocal.com/"
// The privacy policy is docs/privacy-policy.md in the public repository; the controller is named in the imprint.
private const val PRIVACY_POLICY = "https://git.juliandigitalproducts.de/jdaniels/NotiToCal/src/branch/main/docs/privacy-policy.md"
private const val IMPRINT = "https://juliandigitalproducts.de/impressum"

@Composable
fun PrivacyScreen(
  onBack: () -> Unit,
  onRestartWizard: () -> Unit,
  onOpenGuide: () -> Unit = {},
  onOpenLicenses: () -> Unit = {},
  onOpenGoogleNotices: () -> Unit = {},
) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  var confirmClear by remember { mutableStateOf(false) }
  SubScreen(stringResource(R.string.section_privacy), onBack) {
    // How the app works, its limits and how privacy is protected, also with an external AI.
    InfoCard(stringResource(R.string.hiw_screen_title), stringResource(R.string.hiw_teaser), icon = Icons.Filled.Slideshow) {
      Button(onClick = onOpenGuide, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.hiw_open)) }
    }
    SettingGroupCard(title = stringResource(R.string.section_privacy), emphasized = true) {
      SettingSwitch(stringResource(R.string.store_text), s.storeMessageText, stringResource(R.string.store_text_body)) { v -> Ui.update { it.copy(storeMessageText = v) } }
      SettingSwitch(stringResource(R.string.store_screenshot_history), s.storeScreenshotHistory, stringResource(R.string.store_screenshot_history_body)) { v ->
        Ui.update { it.copy(storeScreenshotHistory = v) }
        // Turning it off also removes the screenshots kept so far.
        if (!v) Ui.app.log.sweepScreenshots(deleteAll = true)
      }
      SettingSwitch(stringResource(R.string.send_source), s.sendSourceToTargets, stringResource(R.string.send_source_body)) { v -> Ui.update { it.copy(sendSourceToTargets = v) } }
      SettingSwitch(stringResource(R.string.sender_in_title), s.senderInTitle, stringResource(R.string.sender_in_title_body)) { v -> Ui.update { it.copy(senderInTitle = v) } }
      SettingSwitch(stringResource(R.string.delete_after), s.deleteAfterProcessing, stringResource(R.string.delete_after_body)) { v -> Ui.update { it.copy(deleteAfterProcessing = v) } }
      if (s.deleteAfterProcessing) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gaps.Edge, vertical = 6.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(24.dp))
            Text(
              stringResource(R.string.delete_after_warning_context),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
      }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SettingSlider(stringResource(R.string.keep_log), s.keepLogDays.toFloat(), 1f..365f, format = { resources.getQuantityString(R.plurals.days, it.roundToInt(), it.roundToInt()) }) { v ->
        Ui.update { it.copy(keepLogDays = v.roundToInt()) }
        Ui.app.scope.launch { Ui.app.log.prune(v.roundToInt()) }
      }
      Row(Modifier.padding(horizontal = Gaps.Inner, vertical = 6.dp)) {
        OutlinedButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.clear_log)) }
      }
      Hint(stringResource(R.string.privacy_text))
      Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        TextButton(onClick = { Ui.openUrl(context, PRIVACY_POLICY) }) { Text(stringResource(R.string.privacy_policy)) }
        TextButton(onClick = { Ui.openUrl(context, IMPRINT) }) { Text(stringResource(R.string.imprint)) }
      }
    }

    AliasesSection()

    SettingGroupCard(title = stringResource(R.string.about)) {
      InfoCard("NotiToCal ${BuildConfig.VERSION_NAME}", stringResource(R.string.about_body), icon = Icons.Filled.Info)
      if (BuildConfig.PREVIEW_SIGNED) Hint(stringResource(R.string.preview_signed))
      Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        TextButton(onClick = {}, enabled = false, modifier = Modifier.padding(horizontal = 4.dp)) { Text(stringResource(R.string.source_code)) }
        TextButton(onClick = { Ui.openUrl(context, WEBSITE) }) { Text(stringResource(R.string.website)) }
      }
      TextButton(onClick = onRestartWizard, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { Text(stringResource(R.string.restart_wizard)) }
    }

    SettingGroupCard(title = stringResource(R.string.oss_title)) {
      // Libraries in the APK: generated at build time (AboutLibraries, plus the components bundled inside ML Kit).
      TextButton(onClick = onOpenLicenses, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.licenses_open)) }
      TextButton(onClick = onOpenGoogleNotices, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.licenses_google)) }
      // Not part of the APK: the separate app Anti-Vocale and models downloaded by the user.
      listOf(R.string.oss_antivocale, R.string.oss_models).forEach { id ->
        Hint(stringResource(id))
      }
      Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        TextButton(onClick = {}, enabled = false, modifier = Modifier.padding(horizontal = 4.dp)) { Text(stringResource(R.string.source_code)) }
        TextButton(onClick = { Ui.openUrl(context, WEBSITE) }) { Text(stringResource(R.string.website)) }
      }
    }
  }
  if (confirmClear) ConfirmClearHistoryDialog { confirmClear = false }
}

@Composable
fun ConfirmClearHistoryDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.clear_log)) },
    text = { Text(stringResource(R.string.clear_log_body)) },
    confirmButton = {
      TextButton(onClick = {
        Ui.app.scope.launch { Ui.app.log.clearHistory() }
        onDismiss()
      }) { Text(stringResource(R.string.delete)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

@Composable
fun BackupScreen(onBack: () -> Unit) {
  SubScreen(stringResource(R.string.backup_and_restore), onBack) {
    BackupContent()
  }
}

@Composable
fun BackupContent() {
  val context = LocalContext.current
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  var message by remember { mutableStateOf<String?>(null) }
  // Include all credentials (API keys, IMAP passwords, Telegram Bot/User-API tokens, CalDAV, Todoist, Notion, Webhooks) by default.
  var includeSecrets by remember { mutableStateOf(true) }
  var exportPassword by remember { mutableStateOf("") }
  var includeAiCsv by remember { mutableStateOf(true) }
  var includePlaces by remember { mutableStateOf(true) }
  val placeCount by produceState(0) { value = withContext(Dispatchers.IO) { runSafely { PlaceStore.get(context).count() }.getOrDefault(0) } }
  val withPlaces = includePlaces && placeCount > 0
  val needsPassword = includeSecrets || withPlaces
  val storedSecretCount = remember(includeSecrets) { Ui.app.secrets.all().size }

  val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        // The whole history, not only the newest entries shown in the app.
        val entries = Ui.app.log.allEntries()
        val csv = de.juliandigitalproducts.notitocal.data.CsvExporter.generateCsv(entries, includeAiCsv)
        val ok = runSafely {
          context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        message = if (ok) resources.getString(R.string.csv_export_success, entries.size) else resources.getString(R.string.backup_import_error)
      }
    }
  }

  val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        val allSecrets = if (includeSecrets) BackupCrypto.sanitize(Ui.app.secrets.all()) else emptyMap()
        val passwordOk = exportPassword.length >= BackupCrypto.MIN_PASSWORD
        val backup = SettingsBackup(
          settings = Ui.app.settings.current,
          secrets = emptyMap(),
          secretsEncrypted = if (includeSecrets && passwordOk) BackupCrypto.encrypt(allSecrets, exportPassword) else null,
          // The location history shows where the user lives and goes: it only ever leaves the app encrypted.
          placesEncrypted = if (includePlaces && passwordOk) BackupRestore.encryptPlaces(context, exportPassword) else null,
          ignoredDuplicates = de.juliandigitalproducts.notitocal.output.CalendarDuplicates.loadIgnoredSignatures(context),
          mergeHistory = de.juliandigitalproducts.notitocal.output.CalendarDuplicates.loadHistoryRecords(context),
        )
        val json = AppJson.encodeToString(SettingsBackup.serializer(), backup)
        val ok = runSafely {
          context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        message = resources.getString(if (ok) R.string.backup_export_success else R.string.backup_import_error)
      }
    }
  }

  val importBackup = rememberBackupImport()

  Column {
    Card(
      Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
      Row(Modifier.padding(Gaps.Inner), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(
          stringResource(R.string.backup_security_warning),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
    }

    SettingGroupCard(title = stringResource(R.string.backup_and_restore)) {
      Hint(stringResource(R.string.backup_full_scope_hint, storedSecretCount))
      Row(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          onClick = { exportLauncher.launch("notitocal-settings-backup.json") },
          enabled = !needsPassword || exportPassword.length >= BackupCrypto.MIN_PASSWORD,
          modifier = Modifier.weight(1f),
        ) {
          Icon(Icons.Filled.FileUpload, null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.export_settings))
        }
        OutlinedButton(
          onClick = importBackup,
          modifier = Modifier.weight(1f),
        ) {
          Icon(Icons.Filled.FileDownload, null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.import_settings))
        }
      }

      SettingSwitch(stringResource(R.string.backup_include_secrets), includeSecrets, stringResource(R.string.backup_include_secrets_body)) { includeSecrets = it }
      if (placeCount > 0) {
        SettingSwitch(stringResource(R.string.backup_include_places), includePlaces, stringResource(R.string.backup_include_places_body, placeCount)) { includePlaces = it }
      }
      if (needsPassword) {
        SettingText(stringResource(R.string.backup_password), exportPassword, secret = true, supporting = pluralStringResource(R.plurals.backup_password_hint, BackupCrypto.MIN_PASSWORD, BackupCrypto.MIN_PASSWORD)) { exportPassword = it }
      }
    }

    SettingGroupCard(title = stringResource(R.string.csv_export_title)) {
      Hint(stringResource(R.string.csv_export_subtitle))
      SettingSwitch(
        stringResource(R.string.csv_export_include_ai),
        includeAiCsv,
        stringResource(R.string.csv_export_include_ai_body),
      ) { includeAiCsv = it }
      Row(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
        Button(
          onClick = { exportCsvLauncher.launch("notitocal-messages-export.csv") },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Filled.FileDownload, null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.csv_export_btn))
        }
      }
    }

    val s by Ui.app.settings.state.collectAsState()
    SettingGroupCard(title = stringResource(R.string.widget_memos_filter_title)) {
      SettingSwitch(
        stringResource(R.string.widget_memos_actionable),
        s.widgetMemosOnlyActionable,
        stringResource(if (s.widgetMemosOnlyActionable) R.string.widget_memos_actionable else R.string.widget_memos_all),
      ) { v -> Ui.update { it.copy(widgetMemosOnlyActionable = v) } }
    }

  }

  message?.let { msg ->
    AlertDialog(
      onDismissRequest = { message = null },
      confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.ok)) } },
      title = { Text(stringResource(R.string.backup_and_restore)) },
      text = { Text(msg) },
    )
  }
}
