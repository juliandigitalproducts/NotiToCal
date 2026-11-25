package de.juliandigitalproducts.notitocal.capture

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import de.juliandigitalproducts.notitocal.model.runSafely

/** Reads incoming notifications of the selected apps and watches for new screenshots. */
class NotificationCaptureService : NotificationListenerService() {
  private var scope: CoroutineScope? = null
  private var observer: ContentObserver? = null
  private var observerThread: HandlerThread? = null
  private var lastScreenshotId = -1L

  override fun onListenerConnected() {
    connected = true
    instance = this
    val app = application as NotiToCalApp
    scope?.cancel()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { sc ->
      sc.launch {
        app.settings.state.map { it.screenshotsEnabled }.distinctUntilChanged().collect { enabled ->
          if (enabled) registerScreenshots() else unregisterScreenshots()
        }
      }
      app.scope.launch {
        activeTelegramChats().forEach { (sender, isGroup) ->
          TelegramUserApiSource.recordSeenDialog(app, sender, isGroup)
        }
        ScreenshotFolders.scan(app, maxAgeMinutes = 30, limit = 3)
      }
    }
  }

  override fun onListenerDisconnected() {
    connected = false
    instance = null
    unregisterScreenshots()
    scope?.cancel()
    scope = null
  }

  override fun onDestroy() {
    connected = false
    if (instance === this) instance = null
    unregisterScreenshots()
    scope?.cancel()
    super.onDestroy()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val app = application as NotiToCalApp
    // Cold start: the listener runs on the main thread, do not block it until DataStore is read.
    if (!app.settings.isLoaded) {
      app.scope.launch(Dispatchers.IO) { handlePosted(app, sbn, app.settings.loaded()) }
      return
    }
    handlePosted(app, sbn, app.settings.current)
  }

  private fun handlePosted(app: NotiToCalApp, sbn: StatusBarNotification, s: de.juliandigitalproducts.notitocal.data.AppSettings) {
    if (s.paused || !s.onboardingDone) return
    if (sbn.packageName == packageName) return
    // A voice message only triggers a look into the Anti-Vocale export folder; Anti-Vocale itself transcribes it.
    val voiceWatch = s.antiVocaleActive && sbn.packageName in VoiceNotes.VOICE_APPS
    val notifActive = CapturePolicy.isActive(s, sbn.packageName)
    // Telegram chat titles are learned (titles only, no content) whenever a Telegram mode is set up,
    // so the group/person list can be filled even before the source itself is switched on.
    val telegramLearn = sbn.packageName in TelegramUserApiSource.TELEGRAM_PACKAGES &&
      (s.telegramApiEnabled || s.telegramEnabled || s.telegramApiId.isNotBlank())
    if (!notifActive && !voiceWatch && !telegramLearn) return
    val n = sbn.notification
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
    if (n.category in IGNORED_CATEGORIES) return
    val content = extract(n) ?: return
    if (telegramLearn) TelegramUserApiSource.recordSeenDialog(app, content.sender, content.group)
    if (!notifActive && !voiceWatch) return
    val label = appLabel(this, sbn.packageName)
    if (VoiceNotes.isVoiceMessage(content.text)) {
      if (voiceWatch) VoiceNotes.onVoiceNotification(app, content.sender, label)
      return
    }
    if (sbn.packageName in TelegramUserApiSource.TELEGRAM_PACKAGES) {
      if (!TelegramUserApiSource.shouldProcessTelegramMessage(s, content.sender, content.group)) return
    }
    if (sbn.packageName in WhatsAppSource.PACKAGES) {
      if (!WhatsAppSource.shouldProcess(s, sbn.packageName, content.sender, content.group)) return
    }
    if (sbn.packageName in SmsRcsSource.PACKAGES) {
      if (!SmsRcsSource.shouldProcess(s, content.sender, content.text)) return
    }
    if (!notifActive) return
    val resolvedSource = when {
      sbn.packageName in WhatsAppSource.PACKAGES -> SourceType.WHATSAPP
      sbn.packageName in TelegramUserApiSource.TELEGRAM_PACKAGES && s.telegramApiEnabled -> SourceType.TELEGRAM_API
      sbn.packageName in SmsRcsSource.PACKAGES -> SourceType.SMS_RCS
      else -> SourceType.NOTIFICATION
    }

    if (s.analyzeNotificationImages && content.picture != null) {
      app.scope.launch {
        val ocr = runSafely { MediaProcessor.ocrBitmap(content.picture) }.getOrDefault("").trim()
        val combinedText = when {
          ocr.isNotBlank() && content.text.isNotBlank() -> "${content.text}\n\n[Bild-Text:\n$ocr]"
          ocr.isNotBlank() -> "[Bild:\n$ocr]"
          else -> content.text
        }
        val imageBytes = if (CapturePolicy.sendPictureToAi(s)) runSafely { MediaProcessor.bitmapToJpegBytes(content.picture) }.getOrNull() else null
        val captured = Captured(
          source = resolvedSource,
          packageName = sbn.packageName,
          appLabel = label,
          sender = content.sender,
          text = combinedText,
          imageBytes = imageBytes,
          imageMime = if (imageBytes != null) "image/jpeg" else null,
          isGroup = content.group,
          postedAt = sbn.postTime,
        )
        if (app.batch.analyzeNow(captured)) app.pipeline.process(captured)
        else app.batch.enqueue(captured)
      }
      return
    }

    val captured = Captured(
      source = resolvedSource,
      packageName = sbn.packageName,
      appLabel = label,
      sender = content.sender,
      text = content.text,
      isGroup = content.group,
      postedAt = sbn.postTime,
    )
    // Battery saving: collect and analyse on schedule, unless it cannot wait (today, "in 20 minutes" …).
    // Off the main thread: the urgency check parses dates and enqueueing writes to the database.
    app.scope.launch(Dispatchers.IO) {
      if (app.batch.analyzeNow(captured)) app.pipeline.process(captured)
      else app.batch.enqueue(captured)
    }
  }

  private data class Content(val sender: String, val text: String, val group: Boolean, val picture: Bitmap? = null)

  private fun extract(n: Notification): Content? {
    val ex = n.extras
    val pic: Bitmap? = runSafely {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ex.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
      } else {
        @Suppress("DEPRECATION")
        ex.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
      }
    }.getOrNull()

    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
    if (style != null && style.messages.isNotEmpty()) {
      val msg = style.messages.last()
      val text = msg.text?.toString()?.trim().orEmpty()
      val person = cleanSenderTitle(msg.person?.name?.toString().orEmpty())
      val chat = cleanSenderTitle(style.conversationTitle?.toString().orEmpty())
      val sender = when {
        chat.isNotBlank() && person.isNotBlank() && chat != person -> "$person @ $chat"
        person.isNotBlank() -> person
        else -> chat
      }
      if (text.isEmpty() && pic == null) return null
      val resolvedText = text.ifBlank { if (pic != null) "[Bild empfangen]" else "" }
      return Content(sender, resolvedText, style.isGroupConversation, picture = pic)
    }

    val title = cleanSenderTitle(ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
    val convTitle = cleanSenderTitle(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty())
    val isGrp = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || convTitle.isNotBlank()
    val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    val text = (big ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()).orEmpty().trim()
    if (text.isEmpty() && pic == null) return null
    val resolvedSender = when {
      convTitle.isNotBlank() && title.isNotBlank() && convTitle != title -> "$title @ $convTitle"
      convTitle.isNotBlank() -> convTitle
      else -> title
    }
    val resolvedText = text.ifBlank { if (pic != null) "[Bild empfangen]" else "" }
    return Content(resolvedSender, resolvedText, isGrp, picture = pic)
  }

  /**
   * Removes unread counters from a chat title ("Anna (3)", "Anna (+2)", "Anna (5 neue Nachrichten)"). A number with
   * five or more digits is a phone number of an unsaved contact ("Max (+491701234567)") and is kept.
   */
  fun cleanSenderTitle(raw: String): String {
    var s = raw.trim()
    s = s.replace(COUNTER_IN_PARENS, "")
    s = s.replace(COUNTER_SUFFIX, "")
    return s.trim()
  }

  // ---------------- Screenshots ----------------

  private fun registerScreenshots() {
    if (observer != null) return
    val perm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) return
    val thread = HandlerThread("screenshots").also { it.start() }
    observerThread = thread
    lastScreenshotId = latestScreenshot()?.first ?: -1
    observer = object : ContentObserver(Handler(thread.looper)) {
      override fun onChange(selfChange: Boolean, uri: Uri?) = check()
    }.also {
      contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it)
    }
  }

  private fun unregisterScreenshots() {
    observer?.let { contentResolver.unregisterContentObserver(it) }
    observer = null
    observerThread?.quitSafely()
    observerThread = null
  }

  private fun check() {
    val (id, added, name) = latestScreenshot() ?: return
    if (id == lastScreenshotId) return
    // Only screenshots taken right now (not restored backups or edits of old files).
    if (System.currentTimeMillis() / 1000 - added > 60) return
    lastScreenshotId = id
    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    val app = application as NotiToCalApp
    app.scope.launch {
      MediaProcessor.processScreenshot(app, uri, name)
      ScreenshotFolders.scan(app, maxAgeMinutes = 30, limit = 3)
    }
  }

  private fun latestScreenshot(): Triple<Long, Long, String?>? = runSafely {
    contentResolver.query(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DISPLAY_NAME),
      "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?) AND ${MediaStore.Images.Media.IS_PENDING} = 0",
      arrayOf("%Screenshot%", "Screenshot%"),
      "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getString(2)) else null }
  }.getOrNull()

  companion object {
    private val COUNTER_IN_PARENS = Regex(
      """\s*\((?:\d+\s*(?:neue\s+)?(?:nachrichten?|messages?|msgs?|ungelesen|new)|\+?\d{1,4})\)""",
      RegexOption.IGNORE_CASE,
    )
    private val COUNTER_SUFFIX = Regex("""\s*:\s*\d+\s*(?:neue\s+)?(?:nachrichten?|messages?)\s*$""", RegexOption.IGNORE_CASE)

    @Volatile var connected = false
      private set

    @Volatile private var instance: NotificationCaptureService? = null

    /**
     * Chats (sender string, isGroup) of the Telegram notifications currently in the shade or snoozed.
     * Used by the Telegram group/person sync; empty when the listener is not connected.
     */
    fun activeTelegramChats(): List<Pair<String, Boolean>> {
      val svc = instance ?: return emptyList()
      val active = runSafely { svc.activeNotifications?.toList() }.getOrNull().orEmpty()
      val snoozed = runSafely { svc.snoozedNotifications?.toList() }.getOrNull().orEmpty()
      val rankingMap = runSafely { svc.currentRanking }.getOrNull()
      val results = mutableListOf<Pair<String, Boolean>>()
      for (sbn in (active + snoozed)) {
        if (sbn.packageName !in TelegramUserApiSource.TELEGRAM_PACKAGES) continue
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
        val extracted = runSafely { svc.extract(sbn.notification) }.getOrNull()
        if (extracted != null && extracted.sender.isNotBlank()) {
          results += extracted.sender to extracted.group
        }
        if (rankingMap != null) {
          val ranking = Ranking()
          if (runSafely { rankingMap.getRanking(sbn.key, ranking) }.getOrDefault(false)) {
            val shortcutTitle = runSafely { ranking.conversationShortcutInfo?.shortLabel?.toString()?.trim() }.getOrNull().orEmpty()
            if (shortcutTitle.isNotBlank()) {
              results += shortcutTitle to (extracted?.group ?: false)
            }
          }
        }
      }
      return results.distinctBy { it.first.lowercase() }
    }

    private val IGNORED_CATEGORIES = setOf(
      Notification.CATEGORY_CALL, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE,
      Notification.CATEGORY_SYSTEM, Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_LOCATION_SHARING,
      Notification.CATEGORY_ALARM, Notification.CATEGORY_MISSED_CALL,
    )

    fun isEnabled(context: Context): Boolean {
      val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
      val me = ComponentName(context, NotificationCaptureService::class.java)
      return flat.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    fun appLabel(context: Context, pkg: String): String = runSafely {
      val pm = context.packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
  }
}
