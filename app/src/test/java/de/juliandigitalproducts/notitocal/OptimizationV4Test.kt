// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.analysis.LlmJson
import de.juliandigitalproducts.notitocal.analysis.Prompt
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class OptimizationV4Test {

  @Test
  fun testMonthFallbackAndProvisionalDayInDateParser() {
    // Current date is in November 2026
    val now = LocalDateTime.of(2026, 11, 10, 10, 0)
    val text = "treffen um 14 Uhr im Januar am Brunnen"

    val parsed = DateParser.parse(text, now)
    assertNotNull(parsed.date)
    // Year rollover: Nov 2026 -> next January is 2027
    assertEquals(2027, parsed.date!!.year)
    assertEquals(1, parsed.date!!.monthValue)
    // Day fallback: 1st of month
    assertEquals(1, parsed.date!!.dayOfMonth)
    // Provisional day marked
    assertTrue(parsed.isProvisionalDay)
    assertEquals(LocalTime.of(14, 0), parsed.time)
  }

  @Test
  fun testMonthFallbackAndProvisionalDayInRuleEngine() {
    val now = LocalDateTime.of(2026, 11, 10, 10, 0)
    val text = "treffen um 14 Uhr im Januar am Brunnen"

    val det = RuleEngine.analyze(text, "Anna", now, 60)
    assertEquals(Kind.EVENT, det.kind)
    assertEquals("2027-01-01T14:00", det.start)
    assertTrue(det.isProvisionalDay)
    assertEquals("Brunnen", det.location)
    assertTrue(det.title.contains("Brunnen"))
  }

  @Test
  fun testFollowUpSmartPatchDateUpdateWithProvisionalContextDate() {
    val now = LocalDateTime.of(2026, 11, 10, 10, 0)
    val followUpText = "wir treffen uns am Brunnen am 14ten"
    val provisionalDate = LocalDate.of(2027, 1, 1)

    val parsed = DateParser.parse(followUpText, now, provisionalContextDate = provisionalDate)
    assertNotNull(parsed.date)
    // Must adopt the year and month of the provisional event
    assertEquals(2027, parsed.date!!.year)
    assertEquals(1, parsed.date!!.monthValue)
    // Concrete day updated to 14
    assertEquals(14, parsed.date!!.dayOfMonth)
    assertFalse(parsed.isProvisionalDay)
  }

  @Test
  fun testLlmJsonParsingUpdateExistingAndProvisionalDay() {
    val rawJson = """
      {
        "kind": "EVENT",
        "title": "Treffen Brunnen",
        "description": "Treffen am Brunnen um 14 Uhr",
        "start": "2027-01-14T14:00",
        "end": "2027-01-14T15:00",
        "location": "Brunnen",
        "confidence": 0.95,
        "reason": "Update bestehender Termin",
        "is_provisional_day": false,
        "action_type": "UPDATE_EXISTING",
        "target_event_id": "42"
      }
    """.trimIndent()

    val det = LlmJson.parse(rawJson, "cloud")
    assertNotNull(det)
    assertEquals(Kind.EVENT, det!!.kind)
    assertEquals("Treffen Brunnen", det.title)
    assertEquals("2027-01-14T14:00", det.start)
    assertFalse(det.isProvisionalDay)
    assertEquals("UPDATE_EXISTING", det.actionType)
    assertEquals("42", det.targetEventId)
    assertTrue(det.isUpdateExisting())
  }

  @Test
  fun testPromptFormattingWithRecentChatAndExistingEvents() {
    val s = AppSettings()
    val captured = Captured(
      source = SourceType.NOTIFICATION,
      packageName = "org.telegram.messenger",
      appLabel = "Telegram",
      sender = "Anna",
      text = "wir treffen uns am Brunnen am 14ten",
    )
    val recentChat = listOf("treffen um 14 Uhr im Januar am Brunnen", "geht klar")
    val existingEvents = listOf(
      Prompt.ExistingEventContext(
        id = "99",
        title = "Treffen Brunnen",
        start = "2027-01-01T14:00",
        location = "Brunnen",
        isProvisionalDay = true,
      ),
    )

    val prompt = Prompt.user(captured, s, recentChat, existingEvents)
    assertTrue(prompt.contains("Previous chat context"))
    assertTrue(prompt.contains("treffen um 14 Uhr im Januar am Brunnen"))
    assertTrue(prompt.contains("existingEvents:"))
    assertTrue(prompt.contains("\"id\":\"99\""))
    assertTrue(prompt.contains("\"title\":\"Treffen Brunnen\""))
    assertTrue(prompt.contains("\"is_provisional_day\":true"))
  }

  @Test
  fun testAppSettingsStoreScreenshotHistorySerialization() {
    val defaultSettings = AppSettings()
    assertTrue(defaultSettings.storeScreenshotHistory)

    val toggled = defaultSettings.copy(storeScreenshotHistory = false)
    assertFalse(toggled.storeScreenshotHistory)

    val json = AppJson.encodeToString(AppSettings.serializer(), toggled)
    val decoded = AppJson.decodeFromString(AppSettings.serializer(), json)
    assertFalse(decoded.storeScreenshotHistory)
  }
}
