package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.analysis.Hybrid
import de.juliandigitalproducts.notitocal.analysis.LlmJson
import de.juliandigitalproducts.notitocal.analysis.LocationEnricher
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.analysis.TextNormalizer
import de.juliandigitalproducts.notitocal.capture.Screenshots
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.ScreenshotAppFilter
import de.juliandigitalproducts.notitocal.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class GreetingsAndScreenshotsTest {
  private val now = LocalDateTime.of(2026, 11, 11, 15, 54)

  private val prayer = "🌅🙏 Guten Morgen, liebe Familie!\n\nMöge Gott uns heute und jeden Tag mit guter Gesundheit, " +
    "langem Leben, Wohlstand und unendlichem Glück segnen. 🙏 Möge Er uns beschützen und jeden unserer Schritte zum " +
    "Erfolg führen. 💖✨\n\nAmen! 🙏🌹"

  @Test fun greetingsAreNoDates() {
    assertNull(DateParser.parse("Guten  Morgen ihr Lieben!", now).date)
    assertNull(DateParser.parse("Morgen! Wie geht's?", now).date)
    assertNull(DateParser.parse("Schönen Sonntag euch allen", now).date)
    assertNull(DateParser.parse("Happy Friday!", now).date)
    // A wish for the evening does not move "um 10" to 22:00.
    assertEquals(LocalTime.of(10, 0), DateParser.parse("Schönen Abend noch, wir sehen uns morgen um 10", now).time)
    // Real dates around a greeting still count.
    assertEquals(LocalDate.of(2026, 11, 12), DateParser.parse("Guten Morgen! Treffen wir uns morgen um 10?", now).date)
    assertEquals(LocalDate.of(2026, 11, 15), DateParser.parse("Kommst du am Sonntag?", now).date)
  }

  @Test fun blessingChainIsNoAppointment() {
    assertNull(DateParser.parse(prayer, now).date)
    assertEquals(Kind.NONE, RuleEngine.analyze(prayer, "Infos Portal", now).kind)
  }

  @Test fun byteAlphabetOfSmallModelsIsRepaired() {
    assertEquals("Ümkleidung", TextNormalizer.fixEncoding("Ãľmkleidung"))
    assertEquals("für über", TextNormalizer.fixEncoding("fÃ¼r Ã¼ber"))
    // Real umlauts are left alone.
    assertEquals("Größe Ärger", TextNormalizer.fixEncoding("Größe Ärger"))
    val parsed = LlmJson.parse("""{"kind":"EVENT","title":"Ãľbergabe","date":"2026-11-12","time":"10:00","confidence":0.9}""", "on-device")
    assertEquals("Übergabe", parsed?.title)
  }

  @Test fun refusalKeepsRulesBelowAutoAdd() {
    val raw = """{"kind":"<|endoftext|>I'm sorry, but I can't provide the requested answer."""
    assertTrue(LlmJson.isRefusal(raw))
    assertNull(LlmJson.parse(raw, "on-device"))
    val rules = RuleEngine.analyze("Zahnarzt morgen um 10 Uhr in der Hauptstraße 5", now = now)
    assertEquals(Kind.EVENT, rules.kind)
    val merged = Hybrid.merge(null, rules, "Zahnarzt morgen um 10 Uhr", now, 60, refused = true)
    assertTrue(merged.confidence <= Hybrid.REFUSED_MAX_CONFIDENCE)
    assertFalse(LlmJson.isRefusal("""{"kind":"EVENT","title":"Arzt"}"""))
  }

  @Test fun ownScreenTextIsRecognisedAcrossLineBreaks() {
    assertTrue(RuleEngine.isSelfOrDebugText("Sie wird nie automatisch\neingetragen. Ein Ortsname allein reicht nicht."))
  }

  @Test fun vaguePlacesAreNotSearched() {
    assertTrue(RuleEngine.isVaguePlace("bei uns"))
    assertTrue(RuleEngine.isVaguePlace("zu Hause"))
    assertTrue(RuleEngine.isVaguePlace("at my place"))
    assertFalse(RuleEngine.isVaguePlace("Restaurant Bella Italia"))
    assertTrue(LocationEnricher.extractSearchQueries("bei uns", "Treffen bei uns", "Treffen").isEmpty())
  }

  @Test fun screenshotAppFromFileName() {
    assertEquals("WhatsApp", Screenshots.appOf("Screenshot_20261111_155400_WhatsApp.jpg"))
    assertEquals("NotiToCal", Screenshots.appOf("Screenshot_20261111_155400_NotiToCal.jpg"))
    assertEquals("Samsung Internet", Screenshots.appOf("Screenshot_20261111_155400_Samsung Internet.jpg"))
    assertEquals("com.whatsapp", Screenshots.appOf("Screenshot_2026-11-11-15-54-00-123_com.whatsapp.jpg"))
    assertNull(Screenshots.appOf("Screenshot_20261111-155400.png"))
    assertNull(Screenshots.appOf(null))
  }

  @Test fun screenshotAppFilter() {
    val labels = mapOf("com.whatsapp" to "WhatsApp", "de.juliandigitalproducts.notitocal" to "NotiToCal")
    val label: (String) -> String? = { labels[it] }
    val notif = AppSettings(screenshotAppFilter = ScreenshotAppFilter.NOTIFICATION_APPS, monitoredApps = setOf("com.whatsapp"))
    assertTrue(Screenshots.allowed("WhatsApp", notif, label))
    assertTrue(Screenshots.allowed("com.whatsapp", notif, label))
    assertFalse(Screenshots.allowed("NotiToCal", notif, label))
    // Without an app in the name the screenshot is always analysed.
    assertTrue(Screenshots.allowed(null, notif, label))
    val chosen = AppSettings(screenshotAppFilter = ScreenshotAppFilter.SELECTED, screenshotApps = setOf("de.juliandigitalproducts.notitocal"))
    assertTrue(Screenshots.allowed("NotiToCal", chosen, label))
    assertFalse(Screenshots.allowed("WhatsApp", chosen, label))
    assertTrue(Screenshots.allowed("WhatsApp", AppSettings(), label))
  }

  @Test fun statusBarClockIsDropped() {
    assertEquals("< Strenge\nAutomatisch", Screenshots.dropStatusBar("09:33 T i:\n< Strenge\nAutomatisch"))
    assertEquals("19:30 Konzert im Park", Screenshots.dropStatusBar("19:30 Konzert im Park"))
  }
}
