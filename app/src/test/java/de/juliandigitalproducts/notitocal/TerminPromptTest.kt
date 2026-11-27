package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.Prompt
import de.juliandigitalproducts.notitocal.data.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Prompt-Prüfung ohne Modellaufruf: der System-Prompt enthält den Kalender der nächsten 14 Tage (Modelle verrechnen sich
 * bei Wochentagen) und die Datumsregeln für Verschiebungen, Dauer, mehrtägige Termine und Rückblicke.
 */
class TerminPromptTest {
  private val now = LocalDateTime.of(2026, 11, 26, 14, 30)

  @Test fun calendarAndDateRules() {
    val prompt = Prompt.system(AppSettings(), now, "German")
    assertTrue(prompt.contains("Calendar: Thu 2026-11-26 (today), Fri 2026-11-27 (tomorrow), Sat 2026-11-28"))
    // the weekdays the fixtures need: next Tuesday, next Thursday (a week on), two weeks ahead
    listOf("Tue 2026-12-01", "Thu 2026-12-03", "Wed 2026-12-09").forEach { assertTrue(it, prompt.contains(it)) }
    assertTrue(!prompt.contains("2026-12-10")) // 14 days only: today + 13
    assertTrue(prompt.contains(Prompt.DATE_RULES))
    listOf("Rescheduling", "Duration", "Several days", "Looking back", "Recurring", "the same weekday next week").forEach {
      assertTrue(it, Prompt.DATE_RULES.contains(it))
    }
    // multi-day all-day events need a date-only end
    assertTrue(prompt.contains("\"end\":\"YYYY-MM-DDTHH:MM or YYYY-MM-DD or null\""))
  }
}
