// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.ConversationAggregator
import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.analysis.LocationMemory
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.LocationRecognitionMode
import de.juliandigitalproducts.notitocal.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class LocationAndConversationTest {

  @Before
  fun setUp() {
    LocationMemory.clear()
    ConversationAggregator.clearAll()
  }

  @Test
  fun testDefaultRetentionDaysIs90() {
    val s = AppSettings()
    assertEquals(90, s.keepLogDays)
    assertEquals(LocationRecognitionMode.MEDIUM, s.locationRecognitionMode)
  }

  @Test
  fun testRelativeOrdinalDateCurrentMonthWhenBeforeDay() {
    // Current day is 9th November: target day is 14th -> must be November 14th of current year
    val now = LocalDateTime.of(2026, 11, 9, 10, 0)
    val parsed = DateParser.parse("lass mal am 14ten machen", now)
    assertNotNull("Date must be parsed", parsed.date)
    assertEquals(LocalDate.of(2026, 11, 14), parsed.date)
  }

  @Test
  fun testRelativeOrdinalDateNextMonthWhenAfterDay() {
    // Current day is 15th November: target day is 14th -> must be December 14th of current year
    val now = LocalDateTime.of(2026, 11, 15, 10, 0)
    val parsed = DateParser.parse("lass mal am 14ten machen", now)
    assertNotNull("Date must be parsed", parsed.date)
    assertEquals(LocalDate.of(2026, 12, 14), parsed.date)
  }

  @Test
  fun testRelativeOrdinalDateCurrentMonthOnSameDay() {
    // Current day is 14th November: target day is 14th (day <= 14) -> must be November 14th (today)
    val now = LocalDateTime.of(2026, 11, 14, 10, 0)
    val parsed = DateParser.parse("treffen am 14.", now)
    assertNotNull("Date must be parsed", parsed.date)
    assertEquals(LocalDate.of(2026, 11, 14), parsed.date)
  }

  @Test
  fun testSingleMessageAddressAndClockTime() {
    val now = LocalDateTime.of(2026, 11, 9, 10, 0)
    val text = "14 Uhr treffen: Musterstraße 123"
    val whenParsed = DateParser.parse(text, now)

    assertNotNull(whenParsed.time)
    assertEquals(LocalTime.of(14, 0), whenParsed.time)

    val streetMatch = ConversationAggregator.STREET_PATTERN.find(text)
    assertNotNull(streetMatch)
    assertEquals("Musterstraße", streetMatch!!.groupValues[1].trim())
    assertEquals("123", streetMatch.groupValues[2].trim())
  }

  @Test
  fun testMultiMessageAggregationWithNegationAndConfirmation() {
    val now = LocalDateTime.of(2026, 11, 9, 12, 0)
    val s = AppSettings(homeCity = "Berlin", homePostalCode = "10115")

    val messages = listOf(
      ConversationAggregator.SessionMessage("Person 1", "lass mal am 14ten machen", 1000L),
      ConversationAggregator.SessionMessage("Person 2", "an der Musterstraße 3?", 2000L),
      ConversationAggregator.SessionMessage("Person 3", "nein die 123", 3000L),
      ConversationAggregator.SessionMessage("Person 1", "ok machen wir so", 4000L),
    )

    val slotState = ConversationAggregator.evaluateThread(messages, now, s, "Freunde Gruppe")

    // 1. Date: 14th of current month (Nov 14)
    assertEquals(LocalDate.of(2026, 11, 14), slotState.date)

    // 2. Street: Musterstraße
    assertEquals("Musterstraße", slotState.street)

    // 3. Negation override: 3 replaced by 123
    assertEquals("123", slotState.houseNumber)

    // 4. Affirmation confirmation: status CONFIRMED
    assertEquals(ConversationAggregator.ThreadStatus.CONFIRMED, slotState.status)
    assertTrue("Confidence should be >= 0.90 on confirmed thread", slotState.confidence >= 0.90)
  }

  @Test
  fun testLocationMemoryCandidateScoringAndDecay() {
    val s = AppSettings(homeCity = "Hamburg", homePostalCode = "20095")
    val now = System.currentTimeMillis()

    // Record group location in Munich
    LocationMemory.recordLocation("Projekt Gruppe", "München", "80331", now)
    // Record global location in Köln
    LocationMemory.recordLocation("Anderer Chat", "Köln", "50667", now)

    val candidates = LocationMemory.getCandidateCities("Projekt Gruppe", s, now)
    assertTrue("Candidates should contain at least 3 entries", candidates.size >= 3)

    // Munich has group weight (3.0) + global weight (1.5) = 4.5 -> highest
    assertEquals("München", candidates[0].city)
    assertTrue(candidates[0].score > 3.0)

    // Hamburg is home location with weight 2.0
    val homeCandidate = candidates.firstOrNull { it.city == "Hamburg" }
    assertNotNull(homeCandidate)
    assertEquals(2.0, homeCandidate!!.score, 0.01)
  }

  @Test
  fun testSessionBufferSlidingWindow() {
    val now = LocalDateTime.of(2026, 11, 9, 12, 0)
    val s = AppSettings()
    val baseTime = 1_000_000L

    // Message 1 inside window
    ConversationAggregator.addMessageAndEvaluate(
      SourceType.WHATSAPP, "com.whatsapp", "Gruppe 1",
      "Treffen am 14ten", timestamp = baseTime,
      windowMillis = 60_000L, nowDateTime = now, s = s
    )

    // Message 2 arriving 10 minutes later (outside 60s window)
    val state = ConversationAggregator.addMessageAndEvaluate(
      SourceType.WHATSAPP, "com.whatsapp", "Gruppe 1",
      "an der Musterstraße 123", timestamp = baseTime + 600_000L,
      windowMillis = 60_000L, nowDateTime = now, s = s
    )

    // Previous message pruned
    assertEquals(1, state.messageCount)
    assertEquals("Musterstraße", state.street)
    assertEquals("123", state.houseNumber)
  }
}
