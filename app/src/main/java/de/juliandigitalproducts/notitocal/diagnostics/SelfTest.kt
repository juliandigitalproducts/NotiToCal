package de.juliandigitalproducts.notitocal.diagnostics

import android.net.Uri
import androidx.core.net.toUri
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.AntiVocaleWatch
import de.juliandigitalproducts.notitocal.capture.IcsCalendarSource
import de.juliandigitalproducts.notitocal.capture.ImapClient
import de.juliandigitalproducts.notitocal.capture.MediaProcessor
import de.juliandigitalproducts.notitocal.capture.NotificationCaptureService
import de.juliandigitalproducts.notitocal.capture.ShareAndClipboardSource
import de.juliandigitalproducts.notitocal.capture.SmsRcsSource
import de.juliandigitalproducts.notitocal.capture.TelegramSource
import de.juliandigitalproducts.notitocal.capture.TelegramUserApiSource
import de.juliandigitalproducts.notitocal.capture.WhatsAppSource
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.runSafely
import java.io.File

/** On-device check for all 6 sources, OCR, AI engine and targets. */
object SelfTest {
  private const val IMAGE = "selftest/appointment.png"

  enum class CheckStatus { IDLE, RUNNING, PASSED, FAILED }

  data class Step(
    val passed: Boolean,
    val recognized: String = "",
    val analysis: String = "",
    val tips: List<SelfTestTip> = emptyList(),
    val error: String = "",
  )

  data class CheckItem(
    val id: String,
    val title: String,
    val status: CheckStatus = CheckStatus.IDLE,
    val detail: String = "",
    val step: Step? = null,
  )

  data class Report(
    val notifications: Step,
    val ocr: Step,
    val screenshot: Step,
    val antiVocale: Step,
    val imap: Step,
    val telegram: Step,
    val shareClipboard: Step,
    val aiTargets: Step,
  )

  fun initialChecklist(app: NotiToCalApp): List<CheckItem> = listOf(
    CheckItem("notifications", app.getString(R.string.source_notifications)),
    CheckItem("whatsapp_personal", app.getString(R.string.whatsapp_personal)),
    CheckItem("whatsapp_business", app.getString(R.string.whatsapp_business)),
    CheckItem("telegram_api", app.getString(R.string.source_telegram_api_title)),
    CheckItem("telegram", app.getString(R.string.source_telegram)),
    CheckItem("imap", app.getString(R.string.source_imap)),
    CheckItem("sms_rcs", app.getString(R.string.source_sms_rcs_title)),
    CheckItem("ocr", app.getString(R.string.selftest_ocr)),
    CheckItem("screenshots", app.getString(R.string.source_screenshots)),
    CheckItem("voice_memo", app.getString(R.string.source_voice_memo)),
    CheckItem("calendar_ics", app.getString(R.string.source_ics_title)),
    CheckItem("share_clipboard", app.getString(R.string.source_share_clipboard)),
    CheckItem("ai_targets", app.getString(R.string.selftest_ai_targets)),
  )

  suspend fun runChecklist(
    app: NotiToCalApp,
    onUpdate: (List<CheckItem>) -> Unit,
  ): Report {
    val items = initialChecklist(app).toMutableList()
    suspend fun execute(index: Int, block: suspend () -> Step): Step {
      items[index] = items[index].copy(status = CheckStatus.RUNNING, detail = app.getString(R.string.analyzing))
      onUpdate(items.toList())
      val step = runSafely { block() }.getOrElse { e ->
        Step(passed = false, error = e.message ?: e.javaClass.simpleName)
      }
      val summary = when {
        step.error.isNotBlank() -> step.error
        step.analysis.isNotBlank() -> step.analysis
        step.passed -> app.getString(R.string.selftest_passed)
        else -> app.getString(R.string.selftest_failed)
      }
      items[index] = items[index].copy(
        status = if (step.passed) CheckStatus.PASSED else CheckStatus.FAILED,
        detail = summary,
        step = step,
      )
      onUpdate(items.toList())
      return step
    }

    val notifStep = execute(0) { checkNotifications(app) }
    execute(1) { checkWhatsAppPersonal(app) }
    execute(2) { checkWhatsAppBusiness(app) }
    execute(3) { checkTelegramUserApi(app) }
    val telegramStep = execute(4) { checkTelegram(app) }
    val imapStep = execute(5) { checkImap(app) }
    execute(6) { checkSmsRcs(app) }
    val ocrStep = execute(7) { checkOcr(app) }
    val screenshotStep = execute(8) { checkScreenshots(app) }
    val voiceStep = execute(9) { checkVoiceMemos(app) }
    execute(10) { checkCalendarIcs(app) }
    val shareStep = execute(11) { checkShareAndClipboard(app) }
    val aiTargetsStep = execute(12) { checkAiAndTargets(app) }

    return Report(
      notifications = notifStep,
      ocr = ocrStep,
      screenshot = screenshotStep,
      antiVocale = voiceStep,
      imap = imapStep,
      telegram = telegramStep,
      shareClipboard = shareStep,
      aiTargets = aiTargetsStep,
    )
  }

  fun checkNotifications(app: NotiToCalApp): Step {
    val s = app.settings.current
    val enabled = NotificationCaptureService.isEnabled(app)
    if (!enabled) {
      return Step(passed = false, error = app.getString(R.string.perm_listener))
    }
    val count = s.monitoredApps.size
    val detail = app.resources.getQuantityString(R.plurals.summary_apps, count, count)
    return Step(passed = s.notificationsEnabled, analysis = detail, error = if (!s.notificationsEnabled) app.getString(R.string.off) else "")
  }

  suspend fun checkOcr(app: NotiToCalApp): Step {
    val uri = assetUri(app, IMAGE)
    val text = runSafely { MediaProcessor.ocr(app, uri) }.getOrElse {
      return Step(passed = false, error = it.message ?: it.javaClass.simpleName)
    }.trim()
    val vision = app.settings.current.visionForImages()
    val emptyTips = ocrEmptyTips(text.isBlank(), vision)
    if (emptyTips.isNotEmpty()) return Step(passed = false, tips = emptyTips, error = app.getString(R.string.ocr_empty_no_vision))
    val analysis = if (text.isNotBlank()) app.resources.getQuantityString(R.plurals.ocr_characters_found, text.length, text.length) else ""
    return Step(passed = text.isNotBlank(), recognized = text, analysis = analysis)
  }

  suspend fun checkScreenshots(app: NotiToCalApp): Step {
    val uri = assetUri(app, IMAGE)
    val text = runSafely { MediaProcessor.ocr(app, uri) }.getOrElse {
      return Step(passed = false, error = it.message ?: it.javaClass.simpleName)
    }.trim()
    val vision = app.settings.current.visionForImages()
    val emptyTips = ocrEmptyTips(text.isBlank(), vision)
    if (emptyTips.isNotEmpty()) return Step(passed = false, tips = emptyTips, error = app.getString(R.string.ocr_empty_no_vision))
    val bytes = if (vision) runSafely { MediaProcessor.jpegBytes(app, uri) }.getOrNull() else null
    val captured = Captured(
      source = SourceType.SCREENSHOT,
      appLabel = app.getString(R.string.diagnose_test_label),
      text = text.ifBlank { app.getString(R.string.image_without_text) },
      imageBytes = bytes,
      imageMime = if (bytes != null) "image/jpeg" else null,
      isSelfTest = true,
    )
    val result = runSafely { app.pipeline.process(captured, userInitiated = true) }.getOrElse {
      return Step(passed = false, recognized = text, error = it.message ?: it.javaClass.simpleName)
    }
    val detection = result?.detection
    val analysis = when (detection?.kind) {
      null -> ""
      Kind.NONE, Kind.NOTE -> detection.kind.name
      else -> detection.kind.name + ": " + detection.title
    }
    return Step(passed = text.isNotBlank() || result != null, recognized = text, analysis = analysis)
  }

  /** Voice messages: NotiToCal only reads Anti-Vocale's transcripts, so the check is whether that folder is readable. */
  fun checkVoiceMemos(app: NotiToCalApp): Step {
    val settings = app.settings.current
    if (!settings.antiVocaleEnabled) {
      return Step(passed = true, analysis = app.getString(R.string.off))
    }
    val tips = antiVocaleTips(settings.antiVocaleEnabled, settings.antiVocaleFolderUri.isNotBlank())
    if (tips.isNotEmpty()) return Step(passed = false, tips = tips, error = app.getString(R.string.tip_anti_vocale_folder))
    val files = AntiVocaleWatch.checkFolder(app, settings.antiVocaleFolderUri.toUri())
    if (files < 0) return Step(passed = false, tips = listOf(SelfTestTip.ANTI_VOCALE_FOLDER), error = app.getString(R.string.anti_vocale_check_failed))
    return Step(passed = true, analysis = app.resources.getQuantityString(R.plurals.anti_vocale_check_ok, files, files))
  }

  suspend fun checkImap(app: NotiToCalApp): Step {
    val s = app.settings.current
    val active = s.imapAccounts.filter { it.enabled }
    if (!s.imapEnabled && s.imapAccounts.isEmpty()) {
      return Step(passed = true, analysis = app.getString(R.string.off))
    }
    if (active.isEmpty()) {
      return Step(passed = false, error = app.getString(R.string.imap_none_configured))
    }
    val results = active.map { acc ->
      val name = acc.label.ifBlank { acc.host.ifBlank { acc.username } }
      val res = ImapClient.testAccount(acc, app.secrets.get(SecretKeys.imap(acc.id)))
      Triple(name, res.ok, res.detail)
    }
    val allOk = results.all { it.second }
    val summary = results.joinToString(" · ") { (name, ok, detail) -> "${if (ok) "✓" else "✗"} $name: $detail" }
    return Step(
      passed = allOk,
      analysis = if (allOk) summary else "",
      error = if (!allOk) summary else "",
    )
  }

  suspend fun checkTelegram(app: NotiToCalApp): Step {
    val s = app.settings.current
    val token = app.secrets.get(SecretKeys.TELEGRAM_BOT)
    if (!s.telegramEnabled && token.isBlank()) {
      return Step(passed = true, analysis = app.getString(R.string.off))
    }
    val res = TelegramSource.testConnection(token, s.telegramChatId)
    if (res.discoveredDialogs.isNotEmpty()) {
      TelegramUserApiSource.mergeIntoSettings(app, res.discoveredDialogs)
    }
    return Step(
      passed = res.ok,
      analysis = if (res.ok) res.detail else "",
      error = if (!res.ok) res.detail else "",
    )
  }

  fun checkWhatsAppPersonal(app: NotiToCalApp): Step {
    val res = WhatsAppSource.testPersonal(app, app.settings.current)
    return Step(
      passed = res.ok,
      analysis = if (res.ok) res.detail else "",
      error = if (!res.ok) res.detail else "",
    )
  }

  fun checkWhatsAppBusiness(app: NotiToCalApp): Step {
    val res = WhatsAppSource.testBusiness(app, app.settings.current)
    return Step(
      passed = res.ok,
      analysis = if (res.ok) res.detail else "",
      error = if (!res.ok) res.detail else "",
    )
  }

  fun checkTelegramUserApi(app: NotiToCalApp): Step {
    val res = TelegramUserApiSource.testConnection(app)
    return Step(
      passed = res.ok,
      analysis = if (res.ok) res.detail else "",
      error = if (!res.ok) res.detail else "",
    )
  }

  fun checkSmsRcs(app: NotiToCalApp): Step {
    val res = SmsRcsSource.testSource(app, app.settings.current)
    return Step(
      passed = res.ok,
      analysis = if (res.ok) res.detail else "",
      error = if (!res.ok) res.detail else "",
    )
  }

  fun checkCalendarIcs(app: NotiToCalApp): Step {
    val res = IcsCalendarSource.testSource(app)
    return Step(
      passed = res.ok,
      analysis = if (res.ok) res.detail else "",
      error = if (!res.ok) res.detail else "",
    )
  }

  fun checkShareAndClipboard(app: NotiToCalApp): Step {
    val res = ShareAndClipboardSource.testSource(app)
    return Step(
      passed = res.ok,
      analysis = if (res.ok) res.detail else "",
      error = if (!res.ok) res.detail else "",
    )
  }

  fun checkAiAndTargets(app: NotiToCalApp): Step {
    val s = app.settings.current
    val targets = s.eventTargets + s.taskTargets + s.unfilteredTargets
    if (targets.isEmpty()) {
      return Step(passed = false, error = app.getString(R.string.no_target_configured))
    }
    val engineOk = when (s.engine) {
      AiEngine.RULES -> true
      AiEngine.ON_DEVICE -> s.onDeviceModelUri.isNotBlank() || s.onDeviceModelFile.isNotBlank()
      AiEngine.CLOUD -> !s.provider.needsKey || app.secrets.get(SecretKeys.llm(s.provider)).isNotBlank()
      AiEngine.SELF_HOSTED -> s.selfHostedUrl.isNotBlank()
    }
    if (!engineOk) {
      return Step(passed = false, error = "${s.engine.name}: ${app.getString(R.string.api_key)}")
    }
    return Step(passed = true, analysis = "${s.engine.name} · ${targets.size} Target(s)")
  }

  private fun assetUri(app: NotiToCalApp, asset: String): Uri {
    val file = File(app.cacheDir, "selftest-" + asset.substringAfterLast('/'))
    app.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
    return Uri.fromFile(file)
  }
}
