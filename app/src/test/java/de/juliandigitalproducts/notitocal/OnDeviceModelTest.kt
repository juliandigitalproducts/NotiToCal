package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.Hybrid
import de.juliandigitalproducts.notitocal.analysis.LlmJson
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime

/**
 * Real answers of Qwen 2.5 0.5B / 1.5B Instruct (via Ollama, same weights as the MediaPipe .task files) to the
 * compact on-device prompt, run through the app's parser and the rules merge.
 */
class OnDeviceModelTest {
  private val now = LocalDateTime.of(2026, 10, 31, 10, 0)

  private data class Case(val model: String, val message: String, val answer: String, val kind: Kind, val start: String?, val due: String?)

  private val cases = listOf(
    Case("qwen2.5:0.5b-instruct (final prompt)", "Hi! Treffen wir uns morgen um 19 Uhr beim Italiener in der Hauptstraße? Und vergiss nicht, die Tickets mitzubringen.", "{\"kind\":\"EVENT\",\"title\":\"Italiener in der Hauptstraße\",\"description\":\"Italiener in der Hauptstraße\",\"date\":\"2026-11-02\",\"time\":\"19:00\",\"location\":\"Italiener in der Hauptstraße\",\"confidence\":0.9}", Kind.EVENT, "2026-11-01T19:00", null),
    Case("qwen2.5:0.5b-instruct (final prompt)", "Vergiss nicht, morgen Milch zu kaufen!", "{\"kind\":\"TASK\",\"title\":\"Milch zu kaufen\",\"description\":\"Milch zu kaufen\",\"date\":\"2026-11-02\",\"time\":\"00:00\",\"location\":\"Kaufhaus\",\"confidence\":1}", Kind.TASK, null, "2026-11-01"),
    Case("qwen2.5:0.5b-instruct (final prompt)", "Zahnarzttermin am 12.11. um 9:30", "{\"kind\":\"EVENT\",\"title\":\"Zahnarzttermin\",\"description\":\"Zahnarzttermin um 9:30\",\"date\":\"2026-11-12\",\"time\":\"9:30\",\"location\":\"Nicht festgelegt\",\"confidence\":1}", Kind.EVENT, "2026-11-12T09:30", null),
    Case("qwen2.5:0.5b-instruct (final prompt)", "Haha ja voll lustig 😂", "{\"kind\":\"NONE\",\"title\":\"\",\"description\":\"\",\"date\":null,\"time\":null,\"location\":null,\"confidence\":0.9}", Kind.NONE, null, null),
    Case("qwen2.5:0.5b-instruct (final prompt)", "Kannst du bis Freitag den Bericht schicken?", "{\"kind\":\"TASK\",\"title\":\"Bericht senden\",\"description\":\"Bericht senden\",\"date\":\"2026-11-07\",\"time\":\"00:00\",\"location\":\"nicht angegeben\",\"confidence\":1}", Kind.TASK, null, "2026-11-06"),
    Case("qwen2.5:0.5b-instruct (final prompt)", "Rendez-vous chez le dentiste mardi à 10h", "{\"kind\":\"EVENT\",\"title\":\"Rendez-vous chez le dentiste\",\"description\":\"Rendez-vous chez le dentiste\",\"date\":\"2026-11-02\",\"time\":\"10:00\",\"location\":\"chez le dentiste\",\"confidence\":1}", Kind.EVENT, "2026-11-03T10:00", null),
    Case("qwen2.5:0.5b-instruct (final prompt)", "Das Meeting morgen um 10 fällt aus.", "{\"kind\":\"EVENT\",\"title\":\"Mein Meeting morgen um 10 fällt aus\",\"description\":\"Mein Meeting morgen um 10 fällt aus\",\"date\":\"2026-11-01\",\"time\":\"10:00\",\"location\":null,\"confidence\":1}", Kind.NONE, null, null),
    Case("qwen2.5:0.5b-instruct (final prompt)", "Nur heute: 30% Rabatt auf alles!", "{\"kind\":\"EVENT\",\"title\":\"Rabatt auf alles\",\"description\":\"Rabatt auf alles\",\"date\":\"2026-11-01\",\"time\":\"00:00\",\"location\":\"N/A\",\"confidence\":1}", Kind.NONE, null, null),
    Case("qwen2.5:1.5b-instruct (final prompt)", "Hi! Treffen wir uns morgen um 19 Uhr beim Italiener in der Hauptstraße? Und vergiss nicht, die Tickets mitzubringen.", "{\"kind\":\"EVENT\",\"title\":\"Treffen am Italiener\",\"description\":\"Treffen am Italiener\",\"date\":\"2026-11-01\",\"time\":\"19:00\",\"location\":\"Hauptstraße\"}", Kind.EVENT, "2026-11-01T19:00", null),
    Case("qwen2.5:1.5b-instruct (final prompt)", "Vergiss nicht, morgen Milch zu kaufen!", "{\"kind\":\"TASK\",\"title\":\"Milk\",\"description\":\"Milk\",\"date\":\"2026-11-02\",\"time\":null,\"location\":null,\"confidence\":0.85}", Kind.TASK, null, "2026-11-01"),
    Case("qwen2.5:1.5b-instruct (final prompt)", "Zahnarzttermin am 12.11. um 9:30", "{\"kind\":\"EVENT\",\"title\":\"Zahnarzttermin\",\"description\":\"Zahnarzttermin\",\"date\":\"2026-11-12\",\"time\":\"09:30\",\"location\":\"Zahnarzt\"}", Kind.EVENT, "2026-11-12T09:30", null),
    Case("qwen2.5:1.5b-instruct (final prompt)", "Haha ja voll lustig 😂", "{\"kind\":\"NONE\",\"title\":\"\",\"description\":\"\",\"date\":null,\"time\":null,\"location\":null,\"confidence\":0.9}", Kind.NONE, null, null),
    Case("qwen2.5:1.5b-instruct (final prompt)", "Kannst du bis Freitag den Bericht schicken?", "{\"kind\":\"TASK\",\"title\":\"Bericht schicken\",\"description\":\"Bericht schicken\",\"date\":\"2026-11-05\",\"time\":null,\"location\":null,\"confidence\":0.85}", Kind.TASK, null, "2026-11-06"),
    Case("qwen2.5:1.5b-instruct (final prompt)", "Rendez-vous chez le dentiste mardi à 10h", "{\"kind\":\"EVENT\",\"title\":\"Rendez-vous chez le dentiste\",\"description\":\"Rendez-vous chez le dentiste\",\"date\":\"2026-11-05\",\"time\":\"10:00\",\"location\":\"chez le dentiste\",\"confidence\":0.9}", Kind.EVENT, "2026-11-03T10:00", null),
    Case("qwen2.5:1.5b-instruct (final prompt)", "Das Meeting morgen um 10 fällt aus.", "{\"kind\":\"EVENT\",\"title\":\"Meeting morgen um 10 Uhr\",\"description\":\"Meeting morgen um 10 Uhr\",\"date\":\"2026-11-02\",\"time\":\"10:00\",\"location\":\"nicht genannt\",\"confidence\":0.85}", Kind.NONE, null, null),
    Case("qwen2.5:1.5b-instruct (final prompt)", "Nur heute: 30% Rabatt auf alles!", "{\"kind\":\"EVENT\",\"title\":\"Rabatt auf alles\",\"description\":\"Rabatt auf alles\",\"date\":\"2026-10-31\",\"time\":null,\"location\":\"Online\",\"confidence\":0.95}", Kind.NONE, null, null),
    Case("qwen2.5:0.5b-instruct", "Hi! Treffen wir uns morgen um 19 Uhr beim Italiener in der Hauptstraße? Und vergiss nicht, die Tickets mitzubringen.", "{\"kind\":\"EVENT\",\"title\":\"Mittagessen bei Italiener in der Hauptstraße\",\"description\":\"Mittagessen bei Italiener in der Hauptstraße\",\"date\":\"2026-11-01\",\"time\":\"19:00\",\"location\":\"Italiener in der Hauptstraße\",\"confidence\":0.9}", Kind.EVENT, "2026-11-01T19:00", null),
    Case("qwen2.5:0.5b-instruct", "Vergiss nicht, morgen Milch zu kaufen!", "{\"kind\":\"TASK\",\"title\":\"Milch zu kaufen\",\"description\":\"Milch zu kaufen\",\"date\":\"2026-11-02\",\"time\":\"00:00\",\"location\":\"Kaufhaus\",\"confidence\":1}", Kind.TASK, null, "2026-11-01"),
    Case("qwen2.5:0.5b-instruct", "Zahnarzttermin am 12.11. um 9:30", "{\"kind\":\"EVENT\",\"title\":\"Zahnarzttermin\",\"description\":\"Zahnarzttermin um 9:30\",\"date\":\"2026-11-12\",\"time\":\"9:30\",\"location\":\"\",\"confidence\":1}", Kind.EVENT, "2026-11-12T09:30", null),
    Case("qwen2.5:0.5b-instruct", "Haha ja voll lustig 😂", "{\"kind\":\"NONE\",\"title\":\"Haha ja voll lustig\",\"description\":\"Haha ja voll lustig\",\"date\":null,\"time\":null,\"location\":null,\"confidence\":1}", Kind.NONE, null, null),
    Case("qwen2.5:0.5b-instruct", "Kannst du bis Freitag den Bericht schicken?", "{\"kind\":\"TASK\",\"title\":\"Bericht senden\",\"description\":\"Bericht senden\",\"date\":\"2026-11-07\",\"time\":\"00:00\",\"location\":\"nicht angegeben\",\"confidence\":1}", Kind.TASK, null, "2026-11-06"),
    Case("qwen2.5:0.5b-instruct", "Rendez-vous chez le dentiste mardi à 10h", "{\"kind\":\"EVENT\",\"title\":\"Rendez-vous chez le dentiste\",\"description\":\"Rendez-vous chez le dentiste\",\"date\":\"2026-11-03\",\"time\":\"10:00\",\"location\":\"le dentiste\",\"confidence\":1}", Kind.EVENT, "2026-11-03T10:00", null),
    Case("qwen2.5:0.5b-instruct", "Das Meeting morgen um 10 fällt aus.", "{\"kind\":\"EVENT\",\"title\":\"Mittagessen morgen um 10 Uhr\",\"description\":\"Mittagessen morgen um 10 Uhr\",\"date\":\"2026-11-02\",\"time\":\"10:00\",\"location\":\"Mein Büro\",\"confidence\":0.9}", Kind.NONE, null, null),
    Case("qwen2.5:0.5b-instruct", "Nur heute: 30% Rabatt auf alles!", "{\"kind\":\"EVENT\",\"title\":\"Rabatt auf alles\",\"description\":\"Rabatt auf alles\",\"date\":\"2026-10-31\",\"time\":\"00:00\",\"location\":\"N/A\",\"confidence\":1}", Kind.NONE, null, null),
    Case("qwen2.5:1.5b-instruct", "Hi! Treffen wir uns morgen um 19 Uhr beim Italiener in der Hauptstraße? Und vergiss nicht, die Tickets mitzubringen.", "{\"kind\":\"EVENT\",\"title\":\"Mittagsessen bei Italiener\",\"description\":\"Mittagsessen bei Italiener\",\"date\":\"2026-11-02\",\"time\":\"19:00\",\"location\":\"Hauptstraße\"}", Kind.EVENT, "2026-11-01T19:00", null),
    Case("qwen2.5:1.5b-instruct", "Vergiss nicht, morgen Milch zu kaufen!", "{\"kind\":\"TASK\",\"title\":\"Milk\",\"description\":\"Milk\",\"date\":\"2026-11-02\",\"time\":null,\"location\":null,\"confidence\":0.85}", Kind.TASK, null, "2026-11-01"),
    Case("qwen2.5:1.5b-instruct", "Zahnarzttermin am 12.11. um 9:30", "{\"kind\":\"EVENT\",\"title\":\"Zahnarzttermin\",\"description\":\"Zahnarzttermin am 12.11. um 9:30\",\"date\":\"2026-11-12\",\"time\":\"09:30\",\"location\":\"Zahnarzt\"},{\"kind\":\"TASK\",\"title\":\"Zahnarzttermin\",\"description\":\"Zahnarzttermin am 12.11. um 9:30\",\"date\":\"2026-11-12\",\"time\":\"09:30\",\"location\":\"Zahnarzt\"}", Kind.EVENT, "2026-11-12T09:30", null),
    Case("qwen2.5:1.5b-instruct", "Haha ja voll lustig 😂", "{\"kind\":\"NONE\",\"title\":\"\",\"description\":\"\",\"date\":null,\"time\":null,\"location\":null,\"confidence\":0.9}", Kind.NONE, null, null),
    Case("qwen2.5:1.5b-instruct", "Kannst du bis Freitag den Bericht schicken?", "{\"kind\":\"TASK\",\"title\":\"Bericht schicken\",\"description\":\"Bericht schicken\",\"date\":\"2026-11-05\",\"time\":null,\"location\":null,\"confidence\":0.85}", Kind.TASK, null, "2026-11-06"),
    Case("qwen2.5:1.5b-instruct", "Rendez-vous chez le dentiste mardi à 10h", "{\"kind\":\"EVENT\",\"title\":\"Dental Appointment\",\"description\":\"Dental Appointment\",\"date\":\"2026-11-04\",\"time\":\"10:00\",\"location\":\"chez le dentiste\"}", Kind.EVENT, "2026-11-03T10:00", null),
    Case("qwen2.5:1.5b-instruct", "Das Meeting morgen um 10 fällt aus.", "{\"kind\":\"EVENT\",\"title\":\"Meeting morgen um 10\",\"description\":\"Meeting morgen um 10\",\"date\":\"2026-11-02\",\"time\":\"10:00\",\"location\":\"nicht genannt\",\"confidence\":0.85}", Kind.NONE, null, null),
    Case("qwen2.5:1.5b-instruct", "Nur heute: 30% Rabatt auf alles!", "{\"kind\":\"EVENT\",\"title\":\"30% Rabatt auf alles!\",\"description\":\"30% Rabatt auf alles!\",\"date\":\"2026-10-31\",\"time\":null,\"location\":\"Online\",\"confidence\":0.95}", Kind.NONE, null, null),
  )

  @Test fun realModelAnswers() {
    cases.forEach { c ->
      val d = Hybrid.merge(LlmJson.parse(c.answer, "on-device"), RuleEngine.analyze(c.message, "", now), c.message, now, 60)
      val label = "${c.model}: ${c.message} -> $d"
      assertEquals(label, c.kind, d.kind)
      if (c.kind == Kind.EVENT) assertEquals(label, c.start, d.start)
      if (c.kind == Kind.TASK) assertEquals(label, c.due, d.due)
      if (c.kind == Kind.NONE) return@forEach
      // Places the model invented ("Kaufhaus", "N/A", "nicht angegeben") are dropped.
      d.location?.let { loc -> assertFalse(label, loc in setOf("Kaufhaus", "N/A", "nicht angegeben", "nicht genannt", "Online", "Mein Büro")) }
      assertFalse(label, d.title.contains("um 10"))
    }
  }

  @Test fun parserTakesFirstObjectOnly() {
    val raw = "{\"kind\":\"EVENT\",\"title\":\"A\",\"date\":\"2026-11-12\",\"time\":\"9:30\"},{\"kind\":\"TASK\"}"
    val d = LlmJson.parse(raw, "t")!!
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("2026-11-12T09:30", d.start)
  }

  @Test fun placeholderKindIsNotAnEvent() {
    assertEquals(Kind.NONE, LlmJson.parse("{\"kind\":\"EVENT|TASK|NOTE|NONE\",\"title\":\"x\"}", "t")!!.kind)
  }
}
