// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.BackupCrypto
import de.juliandigitalproducts.notitocal.data.SettingsBackup
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.places.PlaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restores a [SettingsBackup]: settings, secrets and the duplicate history. */
object BackupRestore {
  /** Key of the location history inside its encrypted map. */
  const val PLACES = "places"

  /** The encrypted location history for a backup, or null when there is none. */
  fun encryptPlaces(context: Context, password: String): de.juliandigitalproducts.notitocal.data.EncryptedSecrets? {
    val records = runSafely { PlaceStore.get(context).exportAll() }.getOrDefault(emptyList())
    return if (records.isEmpty()) null else BackupCrypto.encrypt(mapOf(PLACES to PlaceStore.encode(records)), password)
  }

  /** Whether the backup holds anything that needs the password. */
  fun needsPassword(backup: SettingsBackup) = backup.secretsEncrypted != null || backup.placesEncrypted != null

  /** Decrypts secrets and location history; null when the password is wrong (or the file damaged). */
  fun decrypt(backup: SettingsBackup, password: String): Pair<Map<String, String>, String?>? = runSafely {
    val secrets = backup.secretsEncrypted?.let { BackupCrypto.decrypt(it, password) }.orEmpty()
    val places = backup.placesEncrypted?.let { BackupCrypto.decrypt(it, password)[PLACES] }
    secrets to places
  }.getOrNull()

  fun apply(context: Context, backup: SettingsBackup, secrets: Map<String, String>, places: String? = null) {
    places?.let { json -> runSafely { PlaceStore.get(context).importAll(PlaceStore.decode(json)) } }
    // The onboarding state stays as it is: importing in the setup wizard must not end the wizard by itself.
    Ui.update { backup.settings.copy(onboardingDone = it.onboardingDone) }
    val combinedSecrets = backup.secrets + secrets
    if (combinedSecrets.isNotEmpty()) Ui.app.secrets.restoreAll(BackupCrypto.sanitize(combinedSecrets))
    de.juliandigitalproducts.notitocal.output.CalendarDuplicates.restoreHistoryAndIgnored(
      context = context,
      records = backup.mergeHistory,
      ignored = backup.ignoredDuplicates,
    )
  }

  /** Reads at most 4 MB: a huge or endless file must not fill the memory before the size check. */
  fun read(context: Context, uri: android.net.Uri): SettingsBackup? = runSafely {
    val text = context.contentResolver.openInputStream(uri)?.use {
      de.juliandigitalproducts.notitocal.net.Http.readLimited(it, 4_000_000)
    } ?: error("empty")
    AppJson.decodeFromString(SettingsBackup.serializer(), text).also { require(it.app == "NotiToCal") { "not a NotiToCal backup" } }
  }.getOrNull()
}

/**
 * Picks a backup file and imports it, asking for the password of encrypted secrets. Returns the action that opens the
 * file picker; [onResult] gets whether the import worked (after the user closed the result message).
 */
@Composable
fun rememberBackupImport(onResult: (Boolean) -> Unit = {}): () -> Unit {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val latestOnResult by rememberUpdatedState(onResult)
  var pending by remember { mutableStateOf<SettingsBackup?>(null) }
  var password by remember { mutableStateOf("") }
  // Whether it worked, and the message to show.
  var result by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
  val success = true to R.string.backup_import_success
  val failed = false to R.string.backup_import_error

  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) scope.launch(Dispatchers.IO) {
      val backup = BackupRestore.read(context, uri)
      when {
        backup == null -> result = failed
        BackupRestore.needsPassword(backup) -> { password = ""; pending = backup }
        else -> { BackupRestore.apply(context, backup, backup.secrets); result = success }
      }
    }
  }

  pending?.let { backup ->
    AlertDialog(
      onDismissRequest = { pending = null },
      title = { Text(stringResource(R.string.backup_password)) },
      text = {
        Column {
          Text(stringResource(R.string.backup_password_import))
          SettingText(stringResource(R.string.backup_password), password, secret = true) { password = it }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          val entered = password
          pending = null
          // PBKDF2 with 210 000 rounds takes a moment: not on the main thread.
          scope.launch(Dispatchers.Default) {
            val opened = BackupRestore.decrypt(backup, entered)
            if (opened == null) result = false to R.string.backup_password_wrong
            else { BackupRestore.apply(context, backup, opened.first, opened.second); result = success }
          }
        }) { Text(stringResource(R.string.import_settings)) }
      },
      dismissButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) }
          TextButton(onClick = {
            // Settings without the secrets.
            pending = null
            BackupRestore.apply(context, backup, emptyMap())
            result = success
          }) { Text(stringResource(R.string.backup_import_without_secrets)) }
        }
      },
    )
  }

  result?.let { (ok, message) ->
    AlertDialog(
      onDismissRequest = { result = null; latestOnResult(ok) },
      confirmButton = { TextButton(onClick = { result = null; latestOnResult(ok) }) { Text(stringResource(R.string.ok)) } },
      title = { Text(stringResource(R.string.backup_and_restore)) },
      text = { Text(stringResource(message)) },
    )
  }

  return { launcher.launch(arrayOf("application/json", "text/*", "*/*")) }
}
