package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.LlmJson
import de.juliandigitalproducts.notitocal.analysis.StyleTemplates
import de.juliandigitalproducts.notitocal.capture.AntiVocaleWatch
import de.juliandigitalproducts.notitocal.capture.MediaProcessor
import de.juliandigitalproducts.notitocal.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screenshot + caption, style previews, Anti-Vocale transcripts and model answers (C33). */
class CaptureTest {
  @Test fun screenshotAndCaptionAreOneInput() {
    assertEquals("Termin bestätigt\n\nZahnarzt Di 10:00", MediaProcessor.combineImageText("Termin bestätigt", "Zahnarzt Di 10:00"))
    assertEquals("Nur Bild", MediaProcessor.combineImageText("  ", "Nur Bild"))
    assertEquals("Nur Text", MediaProcessor.combineImageText("Nur Text", ""))
    // A caption that is already part of the image text is not repeated.
    assertEquals("Hallo Anna, morgen 10 Uhr?", MediaProcessor.combineImageText("morgen 10 Uhr", "Hallo Anna, morgen 10 Uhr?"))
  }

  @Test fun stylePreviewInUserLanguage() {
    val de = StyleTemplates.preview(StyleTemplates.event("event_formal"), "de")
    assertTrue(de.title + " / " + de.description, (de.title + de.description).contains("Budgetbesprechung"))
    assertFalse(de.description, de.description.contains("{w_"))
    val en = StyleTemplates.preview(StyleTemplates.event("event_formal"), "en")
    assertFalse(en.title, en.title.contains("Budgetbesprechung"))
    // Unknown language falls back to English.
    assertEquals(en, StyleTemplates.preview(StyleTemplates.event("event_formal"), "ja"))
  }

  @Test fun antiVocaleAppendedTranscripts() {
    val first = "Anna: Treffen morgen um 10?\n"
    val both = first + "Ben: Bitte Rechnung bis Freitag zahlen\n"
    assertEquals(first, AntiVocaleWatch.newPart(first, 0, null))
    // Unchanged file: nothing new.
    assertNull(AntiVocaleWatch.newPart(first, first.length, first.hashCode().toString()))
    // Appended: only the new message.
    assertEquals("Ben: Bitte Rechnung bis Freitag zahlen\n", AntiVocaleWatch.newPart(both, first.length, first.hashCode().toString()))
    // Rewritten file: everything.
    val other = "Carla: Abendessen Samstag 19 Uhr, Pizzeria Roma"
    assertEquals(other, AntiVocaleWatch.newPart(other, first.length, first.hashCode().toString()))
  }

  @Test fun cloudAnswersWithFencesAndStrings() {
    val d = LlmJson.parse("```json\n{\"kind\":\"event\",\"title\":\"Zahnarzt\",\"start\":\"2026-11-04T10:00\",\"confidence\":\"85\"}\n```", "cloud")!!
    assertEquals(Kind.EVENT, d.kind)
    assertEquals(0.85, d.confidence, 0.001)
    assertFalse(d.allDay)
    val t = LlmJson.parse("{\"kind\":\"TASK\",\"title\":\"Pay\",\"start\":\"2026-11-06\"}", "cloud")!!
    assertEquals("2026-11-06", t.due)
    assertNull(t.start)
    assertNull(LlmJson.parse("null", "cloud"))
  }
}
