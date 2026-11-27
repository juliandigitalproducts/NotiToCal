// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.Analyzer
import de.juliandigitalproducts.notitocal.analysis.Decision
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.util.TimeZone

/**
 * Messung der Terminerkennung: die erfundenen Nachrichten aus `src/test/resources/termin/faelle.json` laufen durch den
 * echten Offline-Detektor ([RuleEngine.analyze] + [Analyzer.decide], Standard-Einstellungen) mit festem "jetzt"
 * (Do 26.11.2026 14:30 Europe/Berlin). Keine Netzwerk- oder KI-Aufrufe.
 *
 * Der Test berichtet die Trefferquote gesamt und je Kategorie (Konsole und build/reports/termin-erkennung.md). Rot wird
 * er nur, wenn die Quote unter die Mindestschwellen fällt – einzelne Fehlschläge sind erlaubt, falsche Termine nicht.
 */
class TerminErkennungTest {

  companion object {
    /**
     * Mindestquote gesamt (vollständig korrekt). Vorher 51/78 (65 %), nach der Optimierung 78/78; 0.95 lässt höchstens
     * drei Abweichungen zu, damit künftige Regeländerungen nicht an einem Grenzfall hängen, eine echte Regression aber auffällt.
     */
    const val MIN_GESAMT = 0.95
    /** Negativbeispiele (kein Termin) müssen alle stimmen: ein falscher Termin im Kalender ist der teuerste Fehler. */
    const val MIN_NEGATIV = 1.0
  }

  private val settings = AppSettings()
  private val savedZone = TimeZone.getDefault()

  @Before fun pinZone() { TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin")) }
  @After fun restoreZone() { TimeZone.setDefault(savedZone) }

  data class Erwartung(
    val kind: Kind, val start: String?, val end: String?, val allDay: Boolean,
    val titel: List<String>, val ort: String?, val ortGenau: String?,
  )
  data class Fall(val id: String, val kategorie: String, val sprache: String, val quelle: String, val text: String, val erwartet: Erwartung)
  data class Ergebnis(
    val fall: Fall, val erkannt: Boolean, val ok: Boolean, val probleme: List<String>, val vorfilter: Double,
    val kind: Kind, val decision: Decision, val start: String?, val end: String?, val allDay: Boolean, val titel: String, val ort: String?,
  )

  private fun laden(): Pair<LocalDateTime, List<Fall>> {
    val raw = javaClass.classLoader!!.getResource("termin/faelle.json")!!.readText()
    val root = Json.parseToJsonElement(raw).jsonObject
    val jetzt = LocalDateTime.parse(root["jetzt"]!!.jsonPrimitive.content)
    fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.takeIf { it.isString }?.content
    val faelle = root["faelle"]!!.jsonArray.map { e ->
      val o = e.jsonObject
      val x = o["erwartet"]!!.jsonObject
      Fall(
        o.s("id")!!, o.s("kategorie")!!, o.s("sprache")!!, o.s("quelle")!!, o.s("text")!!,
        Erwartung(
          Kind.valueOf(x.s("kind")!!), x.s("start"), x.s("end"), x["allDay"]?.jsonPrimitive?.booleanOrNull ?: false,
          x["titel"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(), x.s("ort"), x.s("ortGenau"),
        ),
      )
    }
    return jetzt to faelle
  }

  private fun pruefe(f: Fall, jetzt: LocalDateTime): Ergebnis {
    val d = RuleEngine.analyze(f.text, "", jetzt, settings.defaultEventMinutes, settings.addressSuggestions)
    val o = Analyzer.decide(d, f.text, settings, jetzt)
    val det = o.detection
    val vorfilter = RuleEngine.relevance(f.text, jetzt)
    val istTermin = det.kind == Kind.EVENT && o.decision != Decision.IGNORE
    val e = f.erwartet
    val probleme = mutableListOf<String>()
    if (e.kind == Kind.EVENT) {
      if (!istTermin) probleme += "kein Termin (${det.kind}/${o.why})"
      else {
        if (det.start != e.start) probleme += "Start ${det.start} statt ${e.start}"
        if (e.end != null && det.end != e.end) probleme += "Ende ${det.end} statt ${e.end}"
        if (det.allDay != e.allDay) probleme += "ganztägig ${det.allDay}"
        if (e.titel.isNotEmpty() && e.titel.none { det.title.contains(it, ignoreCase = true) }) probleme += "Titel '${det.title}'"
        if (e.ort != null && det.location?.contains(e.ort, ignoreCase = true) != true) probleme += "Ort '${det.location}'"
        if (e.ortGenau != null && det.location != e.ortGenau) probleme += "Ort '${det.location}'"
      }
    } else if (istTermin) {
      probleme += "falscher Termin ${det.start} '${det.title}'"
    }
    val erkannt = istTermin == (e.kind == Kind.EVENT)
    return Ergebnis(f, erkannt, probleme.isEmpty(), probleme, vorfilter, det.kind, o.decision, det.start, det.end, det.allDay, det.title, det.location)
  }

  private fun quote(rs: List<Ergebnis>, p: (Ergebnis) -> Boolean) =
    "${rs.count(p)}/${rs.size} (${"%.0f".format(100.0 * rs.count(p) / rs.size.coerceAtLeast(1))} %)"

  @Test
  fun trefferquote() {
    val (jetzt, faelle) = laden()
    val rs = faelle.map { pruefe(it, jetzt) }
    val md = buildString {
      appendLine("# Terminerkennung – Messung (jetzt = $jetzt Europe/Berlin, Regeln/Offline-Detektor)")
      appendLine()
      appendLine("Gesamt vollständig korrekt: ${quote(rs) { it.ok }} · Termin ja/nein richtig: ${quote(rs) { it.erkannt }}")
      appendLine()
      appendLine("| Kategorie | korrekt | Termin ja/nein richtig |")
      appendLine("|---|---|---|")
      rs.groupBy { it.fall.kategorie }.forEach { (k, g) -> appendLine("| $k | ${quote(g) { it.ok }} | ${quote(g) { it.erkannt }} |") }
      rs.groupBy { it.fall.sprache }.forEach { (k, g) -> appendLine("| Sprache $k | ${quote(g) { it.ok }} | ${quote(g) { it.erkannt }} |") }
      val pos = rs.filter { it.fall.erwartet.kind == Kind.EVENT }
      appendLine()
      appendLine("Vorfilter (Schwelle ${settings.prefilterThreshold}) lässt Termine durch: ${quote(pos) { it.vorfilter >= settings.prefilterThreshold }}")
      appendLine()
      appendLine("| id | Nachricht | Ergebnis | Start | Ende | Titel | Ort | OK |")
      appendLine("|---|---|---|---|---|---|---|---|")
      rs.forEach { r ->
        val cell = { s: String? -> s.orEmpty().replace("|", "\\|").replace("\n", " ") }
        appendLine(
          "| ${r.fall.id} | ${cell(r.fall.text)} | ${r.kind}/${r.decision} | ${cell(r.start)} | ${cell(r.end)} | ${cell(r.titel)} | ${cell(r.ort)} | " +
            (if (r.ok) "✅" else "❌ " + cell(r.probleme.joinToString("; "))) + " |",
        )
      }
    }
    File("build/reports").mkdirs()
    File("build/reports/termin-erkennung.md").writeText(md)
    println(md)

    val gesamt = rs.count { it.ok }.toDouble() / rs.size
    val neg = rs.filter { it.fall.erwartet.kind == Kind.NONE }
    val negQuote = neg.count { it.ok }.toDouble() / neg.size
    val fehler = rs.filter { !it.ok }.joinToString("\n") { "${it.fall.id}: ${it.probleme.joinToString("; ")}" }
    // Fehlertexte enthalten Nachrichten mit "%": nicht durch format() schicken.
    assertTrue("Gesamtquote " + "%.2f".format(gesamt) + " < $MIN_GESAMT\n$fehler", gesamt >= MIN_GESAMT)
    assertTrue("Negativquote " + "%.2f".format(negQuote) + " < $MIN_NEGATIV\n$fehler", negQuote >= MIN_NEGATIV)
  }
}
