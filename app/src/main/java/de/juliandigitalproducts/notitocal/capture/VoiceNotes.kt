package de.juliandigitalproducts.notitocal.capture

import androidx.core.net.toUri
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.analysis.rx
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import de.juliandigitalproducts.notitocal.model.runSafely

/**
 * Voice messages: Anti-Vocale transcribes them on the phone and exports the text to a folder the user
 * granted once (Storage Access Framework). NotiToCal only reads those transcripts and checks them for
 * appointments and tasks – on a voice-message notification, on the interval of [AntiVocaleWorker] or by hand.
 */
object VoiceNotes {
  /** "🎤 Sprachnachricht (0:07)", "Voice message", "🎙 Audio" – the whole text, not a word inside a normal message. */
  private val VOICE_HINT = rx(
    """^\W*(?:🎤|🎙)?\W*(sprachnachricht|sprachmemo|audio|voice message|voice note|message vocal|message audio|mensaje de voz|""" +
      """nota de voz|audio de voz|messaggio vocale|mensagem de voz|wiadomość głosowa|wiadomosc glosowa)?\s*(\(\d{1,2}:\d{2}\))?\s*$""",
  )
  /**
   * Apps whose voice messages get a transcript. Anti-Vocale only exports WhatsApp transcripts; voice messages of
   * other apps (Telegram, Signal …) are recognised by [isVoiceMessage] and skipped, because a scan would assign a
   * foreign WhatsApp transcript to them (transcripts are matched to notifications by order only).
   */
  val VOICE_APPS = setOf("com.whatsapp", "com.whatsapp.w4b")
  private val lock = Mutex()

  fun isVoiceMessage(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 60) return false
    val m = VOICE_HINT.matchEntire(t) ?: return false
    val emoji = t.startsWith("🎤") || t.startsWith("🎙")
    val word = m.groupValues[1].lowercase()
    val duration = m.groupValues[2].isNotEmpty()
    // "Audio" alone is an ordinary short message; it needs the microphone or a duration to count as voice message.
    if (word == "audio") return emoji || duration
    // Needs the microphone emoji or one of the words – not just punctuation.
    return emoji || word.isNotEmpty()
  }

  /**
   * Analyses Anti-Vocale transcripts that are new since the last check, oldest first.
   * [senders] are the senders of the matching notifications, newest first. Returns how many were processed.
   */
  suspend fun scan(
    app: NotiToCalApp,
    senders: List<String> = emptyList(),
    maxAgeMinutes: Int = 10,
    limit: Int = 3,
    quiet: Boolean = false,
    onProgress: (current: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
  ): Int = lock.withLock {
    val s = app.settings.current
    if (!s.antiVocaleEnabled) return 0
    if (s.antiVocaleFolderUri.isBlank()) return 0

    val fresh = AntiVocaleWatch.findNewTranscripts(app, s.antiVocaleFolderUri.toUri(), maxAgeMinutes, limit)
    // Oldest transcript ↔ oldest notification, so each memo keeps its sender.
    val oldestFirstSenders = senders.take(fresh.size).reversed()
    fresh.forEachIndexed { i, entry ->
      onProgress(i + 1, fresh.size, entry.fileName)
      runSafely {
        MediaProcessor.processTextMemo(
          app,
          entry.text,
          sourceApp = "Anti-Vocale",
          sender = oldestFirstSenders.getOrElse(i) { "" },
          auto = true,
          quiet = quiet,
        )
      }
    }
    fresh.size
  }

  /**
   * Called by the notification listener for "🎤 Voice message" notifications. Collected for the next scheduled
   * analysis (battery saving) or – when set to "on arrival" – checked now; Anti-Vocale needs a moment to export.
   */
  fun onVoiceNotification(app: NotiToCalApp, sender: String, sourceApp: String) {
    if (!app.batch.voiceNow()) {
      // Called on the listener's main thread: the queue insert goes to the database, so off the main thread.
      app.scope.launch(Dispatchers.IO) {
        app.batch.enqueue(Captured(SourceType.VOICE_MEMO, appLabel = sourceApp, sender = sender, text = ""))
      }
      return
    }
    app.scope.launch {
      for (attempt in 1..6) {
        delay(attempt * 5_000L)
        if (scan(app, listOf(sender)) > 0) return@launch
      }
    }
  }
}
