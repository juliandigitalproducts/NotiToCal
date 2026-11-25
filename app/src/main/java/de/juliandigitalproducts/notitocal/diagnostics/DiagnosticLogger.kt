package de.juliandigitalproducts.notitocal.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory ring buffer recording the most recent diagnostic events (errors, warnings, sync events).
 * Used when generating comprehensive bug reports for troubleshooting without persisting sensitive data.
 */
object DiagnosticLogger {
  private const val MAX_EVENTS = 120

  enum class Level { INFO, WARN, ERROR }

  data class Event(
    val timestamp: Long = System.currentTimeMillis(),
    val level: Level = Level.INFO,
    val tag: String,
    val message: String,
    val errorDetails: String? = null,
  ) {
    fun format(): String {
      val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.GERMAN)
      val timeStr = df.format(Date(timestamp))
      val lvl = when (level) {
        Level.INFO -> "[INFO]"
        Level.WARN -> "[WARN]"
        Level.ERROR -> "[ERROR]"
      }
      return buildString {
        append("$timeStr $lvl [$tag] $message")
        if (!errorDetails.isNullOrBlank()) {
          append("\n    -> Details: $errorDetails")
        }
      }
    }
  }

  private val buffer = ConcurrentLinkedDeque<Event>()

  @Volatile
  var enabled: Boolean = true

  fun log(tag: String, message: String) {
    if (!enabled) return
    add(Event(level = Level.INFO, tag = tag, message = sanitize(message)))
  }

  fun warn(tag: String, message: String, throwable: Throwable? = null) {
    if (!enabled) return
    add(
      Event(
        level = Level.WARN,
        tag = tag,
        message = sanitize(message),
        errorDetails = throwable?.let { sanitize("${it.javaClass.simpleName}: ${it.message}") },
      ),
    )
  }

  fun error(tag: String, message: String, throwable: Throwable? = null) {
    if (!enabled) return
    add(
      Event(
        level = Level.ERROR,
        tag = tag,
        message = sanitize(message),
        errorDetails = throwable?.let { sanitize("${it.javaClass.simpleName}: ${it.message}\n${it.stackTraceToString().take(400)}") },
      ),
    )
  }

  fun recentEvents(): List<Event> = buffer.toList()

  fun recentFormatted(): String {
    val list = recentEvents()
    if (list.isEmpty()) return "Keine Diagnose-Ereignisse im Speicher."
    return list.joinToString("\n") { it.format() }
  }

  fun clear() {
    buffer.clear()
  }

  private fun add(event: Event) {
    buffer.addLast(event)
    while (buffer.size > MAX_EVENTS) {
      buffer.pollFirst()
    }
  }

  /**
   * Strips secrets, tokens, hashes and phone numbers from log text before storing it in memory. Applied to the
   * message and to exception details, which can carry URLs (e.g. a Telegram bot URL with its token).
   */
  internal fun sanitize(input: String): String {
    return input
      .replace(Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]{8,}"""), "$1[REDACTED_TOKEN]")
      .replace(Regex("""(?i)([?&](?:token|key|api_key|access_token)=)[^&\s]+"""), "$1[REDACTED]")
      .replace(Regex("""(?<![\w.:])\+?\d[\d ()/-]{6,}(\d{2})(?![\w.:])""")) { m ->
        // Phone numbers have at least 9 digits; dates (2026-11-08) and durations stay readable.
        if (m.value.count(Char::isDigit) >= 9 && !Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(m.value)) "***" + m.groupValues[1] else m.value
      }
      .replace(Regex("""(?i)(bot[0-9]{8,12}:)[A-Za-z0-9_-]{20,}"""), "$1[REDACTED_BOT_TOKEN]")
      .replace(Regex("""(?i)(api[_-]?hash[\s:=]+)[0-9a-fA-F]{16,64}"""), "$1[REDACTED_HASH]")
      .replace(Regex("""(?i)(password[\s:=]+)[^\s,;]+"""), "$1[REDACTED_PASSWORD]")
      .replace(Regex("""(?i)(session[\s:=]+)[^\s,;]+"""), "$1[REDACTED_SESSION]")
  }
}
