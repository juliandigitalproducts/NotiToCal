package de.juliandigitalproducts.notitocal.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.core.content.FileProvider
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.capture.NotificationCaptureService
import de.juliandigitalproducts.notitocal.capture.TelegramUserApiSource
import de.juliandigitalproducts.notitocal.data.FilterListMode
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.ui.Ui
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates structured, privacy-sanitized bug and diagnostic reports.
 * All credentials, tokens, session keys and passwords are strictly redacted.
 */
object BugReportGenerator {

  data class BugReport(
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val summary: String,
    val file: File? = null,
  )

  suspend fun generate(context: Context, app: NotiToCalApp): BugReport {
    val s = app.settings.current
    val now = System.currentTimeMillis()
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.GERMAN)
    val dateStr = df.format(Date(now))

    // 1. App Version & Build
    val packageInfo = runSafely { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val versionName = packageInfo?.versionName ?: "unknown"
    val versionCode = packageInfo?.longVersionCode ?: -1L

    // 2. Device & OS
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isBatteryOptimized = !(pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false)
    val notifServiceEnabled = NotificationCaptureService.isEnabled(context)
    val notifServiceConnected = NotificationCaptureService.connected
    val imagePermissionGranted = Ui.granted(context, Ui.imagePermission)

    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
    val availRamMb = (memInfo.availMem) / (1024 * 1024)
    val totalRamMb = (memInfo.totalMem) / (1024 * 1024)
    val lowRam = memInfo.lowMemory

    val dataDir = Environment.getDataDirectory()
    val freeStorageMb = dataDir.freeSpace / (1024 * 1024)
    val totalStorageMb = dataDir.totalSpace / (1024 * 1024)

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val actNet = cm?.activeNetwork
    val caps = cm?.getNetworkCapabilities(actNet)
    val netType = when {
      caps == null -> "Keine Verbindung"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN (Wi-Fi)"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobilfunk"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
      else -> "Aktiv"
    }

    // 3. Telegram API & Bridge Diagnostics
    val hasApiId = s.telegramApiId.isNotBlank()
    val maskedApiId = if (hasApiId) s.telegramApiId.take(3) + "****" else "nicht konfiguriert"
    val hasApiHash = app.secrets.get(SecretKeys.TELEGRAM_API_HASH).isNotBlank()
    val has2fa = app.secrets.get(SecretKeys.TELEGRAM_2FA_PASSWORD).isNotBlank()
    val hasSession = app.secrets.get(SecretKeys.TELEGRAM_API_SESSION).isNotBlank()
    val maskedPhone = if (s.telegramPhone.length > 5) s.telegramPhone.take(4) + "****" + s.telegramPhone.takeLast(2) else if (s.telegramPhone.isNotBlank()) "***" else "nicht konfiguriert"
    val bridgeToken = app.secrets.get(SecretKeys.TELEGRAM_BRIDGE_TOKEN)
    val bridgeCheck = if (s.telegramApiBridgeUrl.isNotBlank()) {
      TelegramUserApiSource.testBridgeConnection(context, s.telegramApiBridgeUrl, bridgeToken)
    } else null

    // 4. Database & KPI metrics
    val entries = app.log.allEntries()
    val totalLogged = entries.size
    val totalEvents = entries.count { it.detection.kind == Kind.EVENT && (it.status == Status.ADDED || it.status == Status.PARTIAL) }
    val totalTasks = entries.count { it.detection.kind == Kind.TASK && (it.status == Status.ADDED || it.status == Status.PARTIAL) }
    val recentErrors = entries.filter { it.status == Status.FAILED || it.result.contains("Fehler", ignoreCase = true) }.takeLast(15)

    // 5. Assemble Structured Text Report
    val reportText = buildString {
      appendLine("=================================================================")
      appendLine("              NotiToCal – Fehler- & Diagnosebericht             ")
      appendLine("=================================================================")
      appendLine("Erstellt am:        $dateStr")
      appendLine("App-Version:        v$versionName (Build $versionCode)")
      appendLine("Paketname:          ${context.packageName}")
      appendLine("Onboarding fertig:  ${if (s.onboardingDone) "Ja" else "Nein"}")
      appendLine("Pausiert:           ${if (s.paused) "JA (Erfassung pausiert!)" else "Nein"}")
      appendLine()

      appendLine("------------------- GERÄT & BETRIEBSSYSTEM ---------------------")
      appendLine("Hersteller / Modell: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
      appendLine("Android-Version:     Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
      appendLine("Fingerprint:         ${Build.FINGERPRINT.take(60)}...")
      appendLine("Unterstützte ABIs:   ${Build.SUPPORTED_ABIS.joinToString(", ")}")
      appendLine("RAM (Arbeitsspeicher): $availRamMb MB frei von $totalRamMb MB (Drosselung: ${if (lowRam) "JA" else "Nein"})")
      appendLine("Interner Speicher:   $freeStorageMb MB frei von $totalStorageMb MB")
      appendLine("Netzwerkverbindung:  $netType")
      appendLine("Diagnose-Protokoll:  ${if (s.diagnosticLoggingEnabled) "Aktiviert" else "Deaktiviert durch Benutzer"}")
      appendLine()

      appendLine("----------------- BERECHTIGUNGEN & HINTERGRUND -----------------")
      appendLine("Benachrichtigungs-Dienst aktiv (Secure Settings): ${if (notifServiceEnabled) "✓ JA" else "✗ NEIN (Zugriff fehlt!)"}")
      appendLine("Benachrichtigungs-Dienst verbunden (Laufzeit):   ${if (notifServiceConnected) "✓ JA" else "✗ NEIN"}")
      appendLine("Akku-Optimierungsausnahme gewährt:              ${if (!isBatteryOptimized) "✓ JA (Hintergrund aktiv)" else "✗ NEIN (App wird vom System gedrosselt!)"}")
      appendLine("Bilder- / Medien-Berechtigung gewährt:          ${if (imagePermissionGranted) "✓ JA" else "✗ NEIN"}")
      appendLine()

      appendLine("----------------- TELEGRAM BENUTZER-API STATUS -----------------")
      appendLine("Telegram User-API aktiviert:   ${if (s.telegramApiEnabled) "JA" else "Nein"}")
      appendLine("Telegram-API-ID:               $maskedApiId")
      appendLine("Telegram-API-Hash hinterlegt:  ${if (hasApiHash) "✓ Ja (im Keystore geschützt)" else "✗ Nein"}")
      appendLine("Telefonnummer:                 $maskedPhone")
      appendLine("2FA-Passwort hinterlegt:       ${if (has2fa) "✓ Ja (im Keystore)" else "Nein (nicht hinterlegt)"}")
      appendLine("Sitzungs-Token vorhanden:      ${if (hasSession) "✓ Ja" else "Nein"}")
      appendLine("Verbundener Benutzername:      ${s.telegramApiConnectedUser.ifBlank { "–" }}")
      appendLine("Filter-Modus:                  ${if (s.telegramFilterMode == FilterListMode.ALL_EXCEPT) "Alle außer (Blacklist)" else "Nur folgende (Whitelist)"}")
      appendLine("Gruppen & Personen in Liste:   ${s.telegramDialogs.size} (${s.telegramDialogs.count { it.selected }} angehakt)")
      appendLine("Flood-Schutz / Drosselung:     ${if (s.telegramRateLimitEnabled) "Aktiv (max. ${s.telegramMaxMsgsPerMinute}/Min)" else "Deaktiviert"}")
      appendLine("Zeitfenster für Abruf:         ${s.telegramTimeWindowHours} Stunden")
      appendLine()
      appendLine("Bridge-Konfiguration:")
      appendLine("  Bridge-URL:                  ${s.telegramApiBridgeUrl.ifBlank { "Keine (Lokaler Android-Modus)" }}")
      if (bridgeCheck != null) {
        appendLine("  Bridge-Erreichbarkeit:       ${if (bridgeCheck.ok) "✓ ERREICHBAR (${bridgeCheck.latencyMs} ms)" else "✗ NICHT ERREICHBAR"}")
        appendLine("  Bridge-Diagnosedetails:      ${bridgeCheck.detail}")
      } else {
        appendLine("  Hinweis:                     Ohne Bridge-URL werden Chats aus Benachrichtigungen,")
        appendLine("                               dem Verlauf oder vordefinierten Listen (allowlist.json) bezogen.")
      }
      appendLine()
      appendLine("Aktuelle Telegram Gruppen & Personen (${s.telegramDialogs.size}):")
      if (s.telegramDialogs.isEmpty()) {
        appendLine("  (Keine Einträge in der Liste vorhanden)")
      } else {
        // Names of people and groups are personal data: the report only shows type and selection, masked.
        s.telegramDialogs.forEachIndexed { i, d ->
          val flag = if (d.selected) "[x]" else "[ ]"
          val masked = d.title.take(1).ifBlank { "?" } + "***"
          appendLine("  $flag #${i + 1} ${d.kind}: $masked (ID: ***${d.id.toString().takeLast(3)})")
        }
      }
      appendLine()

      appendLine("------------------ TELEGRAM BOT STATUS -------------------------")
      val hasBotToken = app.secrets.get(SecretKeys.TELEGRAM_BOT).isNotBlank()
      appendLine("Telegram Bot aktiviert:        ${if (s.telegramEnabled) "JA" else "Nein"}")
      appendLine("Bot-Token hinterlegt:          ${if (hasBotToken) "✓ Ja (im Keystore geschützt)" else "Nein"}")
      appendLine("Konfigurierte Chat-ID:         ${s.telegramChatId.ifBlank { "Automatisch / Alle erlaubten" }}")
      appendLine()

      appendLine("------------------ ANDERE QUELLEN-MODULE -----------------------")
      appendLine("WhatsApp (Persönlich / Business): ${s.whatsappEnabled} (Personal: ${s.whatsappPersonalEnabled}, Business: ${s.whatsappBusinessEnabled})")
      appendLine("Messenger-Benachrichtigungen:    ${s.notificationsEnabled} (${s.monitoredApps.size} Apps ausgewählt)")
      appendLine("E-Mail (IMAP):                   ${s.imapEnabled} (${s.imapAccounts.size} Konten)")
      appendLine("SMS & RCS:                       ${s.smsRcsEnabled} (2FA-Schutz: ${s.smsIgnore2fa})")
      appendLine("Screenshots (OCR):               ${s.screenshotsEnabled}")
      appendLine("Anti-Vocale (Sprachmemos):       ${s.antiVocaleEnabled}")
      appendLine("ICS-Dateien (.ics):              ${s.icsEnabled}")
      appendLine("Teilen / Zwischenablage:         Share: ${s.shareEnabled}, Clipboard: ${s.clipboardEnabled}")
      appendLine()

      appendLine("----------------- ZIELE & KI-KONFIGURATION ---------------------")
      appendLine("Termin-Ziele (Events):           ${s.eventTargets.size} konfiguriert")
      appendLine("Aufgaben-Ziele (Tasks):          ${s.taskTargets.size} konfiguriert")
      appendLine("Ungefilterte Weiterleitung:      ${s.unfilteredTargets.size} konfiguriert")
      appendLine("KI-Engine:                       ${s.engine.name}")
      appendLine("Ausgewähltes Modell:             ${s.providerModel.ifBlank { s.onDeviceModelName.ifBlank { "Standard / Regelbasiert" } }}")
      appendLine()

      appendLine("----------------- OCR & BILDVERARBEITUNG -----------------------")
      appendLine("Standard-OCR-Modus:              ${s.ocrEngineMode.label}")
      appendLine("Bilder aus Benachrichtigungen:   ${if (s.analyzeNotificationImages) "Aktiviert" else "Deaktiviert"}")
      appendLine("Vision-LLM für Bilder:           ${if (s.screenshotVision) "Aktiviert" else "Deaktiviert"}")
      appendLine()

      appendLine("----------------- DUPLIKAT-ERKENNUNG ---------------------------")
      appendLine("Automatisch entscheiden:         ${if (s.duplicateAutoDecide) "Aktiviert" else "Deaktiviert"}")
      appendLine("Gesetzliche Feiertage ignorieren: ${if (s.duplicateIgnoreHolidays) "JA" else "Nein"}")
      appendLine("Kalenderübergreifend:            ${if (s.duplicateCrossCalendar) "JA" else "Nein"}")
      appendLine("Zeitfenster-Preset:              ${s.duplicateRangePreset}")
      appendLine("KI für unsichere Fälle:          ${if (s.duplicateUseAiForUnsure) "Aktiv (Schwelle: ${(s.duplicateAiThreshold * 100).toInt()}%)" else "Deaktiviert"}")
      appendLine()

      appendLine("----------------- DATENBANK & VERLAUFS-METRIKEN ----------------")
      appendLine("Verarbeitete Einträge gesamt:    $totalLogged")
      appendLine("Erstellte Kalendertermine:       $totalEvents")
      appendLine("Erstellte Aufgaben:              $totalTasks")
      appendLine()

      appendLine("---------------- LETZTE FEHLER / WARNUNGEN ---------------------")
      if (recentErrors.isEmpty()) {
        appendLine("Keine protokollierten Fehler im Nachrichtenverlauf.")
      } else {
        recentErrors.takeLast(10).forEach { err ->
          val errTime = df.format(Date(err.created))
          appendLine("• $errTime [${err.source}] ${err.appLabel}: ${err.result} (Status: ${err.status})")
        }
      }
      appendLine()

      appendLine("---------------- DIAGNOSE-LOGS (LETZTE EREIGNISSE) -------------")
      if (s.diagnosticLoggingEnabled) {
        appendLine(DiagnosticLogger.recentFormatted())
      } else {
        appendLine("(Diagnose-Protokollierung wurde in den Einstellungen deaktiviert)")
      }
      appendLine("=================================================================")
    }

    val summary = buildString {
      append("NotiToCal v$versionName (Build $versionCode) auf Android ${Build.VERSION.RELEASE} · ")
      append(if (notifServiceConnected) "Listener verbunden · " else "Listener getrennt · ")
      append("TG: ${s.telegramDialogs.size} Chats (${s.telegramDialogs.count { it.selected }} aktiv) · ")
      if (bridgeCheck != null) {
        append(if (bridgeCheck.ok) "Bridge OK" else "Bridge getrennt")
      } else {
        append("Lokaler Modus")
      }
    }

    val reportFile = runSafely {
      val cacheDir = File(context.cacheDir, "reports").apply { mkdirs() }
      File(cacheDir, "notitocal-fehlerbericht-${System.currentTimeMillis()}.txt").apply {
        writeText(reportText)
      }
    }.getOrNull()

    return BugReport(
      timestamp = now,
      text = reportText,
      summary = summary,
      file = reportFile,
    )
  }

  fun shareReport(context: Context, report: BugReport) {
    val file = report.file
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, "NotiToCal Fehler- und Diagnosebericht")
      putExtra(Intent.EXTRA_TEXT, report.text)
      if (file != null && file.exists()) {
        val uri = runSafely {
          FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri != null) {
          putExtra(Intent.EXTRA_STREAM, uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      }
    }
    context.startActivity(Intent.createChooser(intent, "Fehlerbericht teilen"))
  }
}
