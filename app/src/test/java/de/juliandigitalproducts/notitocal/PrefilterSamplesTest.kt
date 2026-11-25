package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.Analyzer
import de.juliandigitalproducts.notitocal.analysis.Decision
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.analysis.DateParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * The invented notifications of [MessageSamples] (the 30 of the first review, time zones, delivery notices) run through
 * the rule pre-filter and the offline rule analysis with the default (balanced) settings. Fixed clock: Friday 20 November 2026, 10:00 (Europe/Berlin local time),
 * never the real date. Writes a result table to build/reports/prefilter-30-samples.md.
 */
class PrefilterSamplesTest {

  private val settings = AppSettings()

  private val samples = MessageSamples.all
  private val now = MessageSamples.NOW

  // Times written with a zone ("17:00 CET") are converted to the phone's zone: pin it, whatever zone the build runs in.
  private val savedZone = TimeZone.getDefault()
  @Before fun pinZone() { TimeZone.setDefault(TimeZone.getTimeZone(MessageSamples.ZONE)) }
  @After fun restoreZone() { TimeZone.setDefault(savedZone) }

  data class Row(val s: MessageSamples.Sample, val relevance: Double?, val passed: Boolean, val kind: Kind, val decision: Decision, val start: String?, val end: String?, val allDay: Boolean, val title: String, val ok: Boolean, val why: String)

  private fun run(s: MessageSamples.Sample): Row {
    val relevance = Analyzer.prefilterRelevance(s.text, settings, now)
    val passed = relevance == null || relevance >= settings.prefilterThreshold
    val d = RuleEngine.analyze(s.text, "", now, settings.defaultEventMinutes, settings.addressSuggestions)
    val o = Analyzer.decide(d, s.text, settings, now)
    val isEvent = o.detection.kind == Kind.EVENT && o.decision != Decision.IGNORE
    val det = o.detection
    val problems = buildList {
      if (s.event) {
        if (!passed) add("pre-filter")
        if (!isEvent) add("no event (${o.why})")
        else {
          if (det.start != s.start) add("start")
          if (s.end != null && det.end != s.end) add("end")
          if (det.allDay != s.allDay) add("allDay")
          if (s.title != null && !det.title.contains(s.title, ignoreCase = true)) add("title")
        }
      } else {
        if (isEvent) add("false event")
        if (s.preBlock && passed) add("pre-filter let noise through")
      }
    }
    return Row(s, relevance, passed, det.kind, o.decision, det.start ?: det.due, det.end, det.allDay, det.title, problems.isEmpty(), problems.joinToString(", "))
  }

  @Test
  fun thirtySamples() {
    val rows = samples.map(::run)
    val md = buildString {
      appendLine("| # | Quelle | Nachricht | Erwartet | Vorfilter | Ergebnis Regeln | Start | Ende | Titel | OK |")
      appendLine("|---|---|---|---|---|---|---|---|---|---|")
      rows.forEach { r ->
        val exp = if (r.s.event) "Termin ${r.s.start}${r.s.end?.let { " – $it" } ?: ""}${if (r.s.allDay) " (ganztägig)" else ""}" else "kein Termin"
        val pre = r.relevance?.let { "%.2f %s".format(it, if (r.passed) "durch" else "blockiert") } ?: "n/a"
        val ok = when {
          r.ok -> "✅"
          r.s.aiPath -> "⚠️ KI-Fall: ${r.why}"
          else -> "❌ ${r.why}"
        }
        appendLine("| ${r.s.id} | ${r.s.source} | ${r.s.text.replace("|", "\\|")} | $exp${if (r.s.aiPath) " (KI-Fall)" else ""} | $pre | ${r.kind}/${r.decision} | ${r.start ?: ""} | ${r.end ?: ""} | ${r.title.replace("|", "\\|")} | $ok |")
      }
      appendLine()
      val preOk = rows.count { r -> if (r.s.event) r.passed else if (r.s.preBlock) !r.passed else true }
      appendLine("Vorfilter korrekt: $preOk/${rows.size} · Gesamt (Vorfilter + Regeln) korrekt: ${rows.count { it.ok }}/${rows.size}")
    }
    File("build/reports").mkdirs()
    File("build/reports/prefilter-30-samples.md").writeText(md)
    println(md)
    val failed = rows.filter { !it.ok && !it.s.aiPath }
    assertTrue("Failed samples:\n" + failed.joinToString("\n") { "#${it.s.id} ${it.why}: ${it.s.text}" }, failed.isEmpty())
  }

  /** Guards for the parser changes made with these samples: no new false readings around them. */
  @Test
  fun parserGuards() {
    fun p(t: String) = DateParser.parse(t, now)
    // weekday + day number only when the day really is that weekday this or next month
    assertEquals(java.time.LocalDate.of(2026, 11, 27), p("Quedamos el viernes 27").date)
    assertEquals(java.time.LocalDate.of(2026, 11, 21), p("Treffen am Samstag 5 Leute").date)
    assertEquals(java.time.LocalDate.of(2026, 11, 22), p("Sonntag 3 von uns").date)
    assertEquals(java.time.LocalTime.of(18, 0), p("Freitag 18 Uhr Kino").time)
    // "um 7:45" is a morning time in German; a bare "at 7:45" / "um 5" keeps the afternoon reading
    assertEquals(java.time.LocalTime.of(7, 45), p("morgen um 7:45").time)
    assertEquals(java.time.LocalTime.of(19, 45), p("see you tomorrow at 7:45").time)
    assertEquals(java.time.LocalTime.of(17, 0), p("morgen um 5").time)
    // hour ranges with one marker
    val r = p("Training Montag 9-11 Uhr")
    assertEquals(java.time.LocalTime.of(9, 0), r.time)
    assertEquals(java.time.LocalTime.of(11, 0), r.endTime)
    // cancellations with words in between, but not their negation
    assertTrue(RuleEngine.scores("Der Kurs fällt heute leider aus").cancelled)
    assertTrue(RuleEngine.scores("Das Training findet morgen nicht statt").cancelled)
    assertTrue(!RuleEngine.scores("Das Treffen fällt nicht aus, wir sehen uns um 18 Uhr").cancelled)
    // a look back with a future date stays relevant
    assertTrue(RuleEngine.relevance("Gestern hast du gefragt: treffen wir uns morgen um 15 Uhr?", now) >= 0.2)
  }
}
