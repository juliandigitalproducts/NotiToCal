package de.juliandigitalproducts.notitocal.pipeline

import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.analysis.TextNormalizer
import de.juliandigitalproducts.notitocal.analysis.rx
import de.juliandigitalproducts.notitocal.data.AnalysisSchedule
import de.juliandigitalproducts.notitocal.data.AppSettings
import java.time.LocalDateTime
import java.time.LocalTime

/** When the collected messages are analysed next, and which messages cannot wait that long. */
object Schedule {

  /** Next analysis time after [now]; null when messages are analysed immediately. */
  fun nextRun(s: AppSettings, now: LocalDateTime): LocalDateTime? = when (s.analysisSchedule) {
    AnalysisSchedule.IMMEDIATE -> null
    AnalysisSchedule.EVERY_15_MIN, AnalysisSchedule.HOURLY, AnalysisSchedule.EVERY_3H ->
      now.plusMinutes(s.analysisSchedule.intervalMinutes.toLong()).withSecond(0).withNano(0)
    AnalysisSchedule.TIMES_DAILY -> nextSlot(s.analysisTimes.ifEmpty { listOf(18 * 60) }, now)
    AnalysisSchedule.DAILY -> nextSlot(listOf(s.analysisTimes.firstOrNull() ?: (18 * 60)), now)
  }

  private fun nextSlot(times: List<Int>, now: LocalDateTime): LocalDateTime {
    val slots = times.map { it.coerceIn(0, 24 * 60 - 1) }.distinct().sorted().map { LocalTime.of(it / 60, it % 60) }
    slots.firstOrNull { now.toLocalDate().atTime(it).isAfter(now) }?.let { return now.toLocalDate().atTime(it) }
    return now.toLocalDate().plusDays(1).atTime(slots.first())
  }

  private val URGENT = rx(
    """\b(gleich|sofort|jetzt|asap|dringend|eilig|urgent|right now|immediately|now|tout de suite|maintenant|ahora|ya|enseguida|""" +
      """subito|adesso|urgente|já|agora|natychmiast|teraz|pilne)\b|\b(in|en|tra|fra|em|za)\s+\d{1,3}\s*(min|minuten|minutes|minutos|minuti|minut)""",
  )

  /**
   * A message is urgent when waiting for the next scheduled analysis would be too late: it mentions "now / in 20 min"
   * or a date/time before the next run (plus one hour of margin).
   */
  fun isUrgent(text: String, now: LocalDateTime, next: LocalDateTime?): Boolean {
    if (next == null) return true
    val t = TextNormalizer.normalize(text)
    if (URGENT.containsMatchIn(t)) return true
    val w = DateParser.parse(t, now)
    val date = w.date ?: if (w.time != null) now.toLocalDate() else return false
    // Without a clock time assume the morning of that day, so "tomorrow" is urgent when the next run is tomorrow noon.
    val target = date.atTime(w.time ?: LocalTime.of(8, 0))
    return !target.isAfter(next.plusHours(1))
  }
}
