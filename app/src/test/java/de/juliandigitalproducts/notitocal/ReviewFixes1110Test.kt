// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.ConversationAggregator
import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.capture.NotificationCaptureService
import de.juliandigitalproducts.notitocal.capture.VoiceNotes
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.screenshotId
import de.juliandigitalproducts.notitocal.output.Targets
import de.juliandigitalproducts.notitocal.pipeline.Deferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Fixes from the code review of 10 Nov 2026. */
class ReviewFixes1110Test {
  // Tuesday, 10 November 2026, 10:00
  private val now = LocalDateTime.of(2026, 11, 10, 10, 0)

  // ---------------- Colloquial quarters ----------------

  @Test fun viertelNach() {
    assertEquals(LocalTime.of(16, 15), DateParser.parse("Treffen morgen viertel nach 4", now).time)
  }

  @Test fun viertelVor() {
    assertEquals(LocalTime.of(16, 45), DateParser.parse("Kommst du morgen um viertel vor 5?", now).time)
  }

  @Test fun dreiviertel() {
    assertEquals(LocalTime.of(16, 45), DateParser.parse("Morgen dreiviertel 5 beim Bäcker", now).time)
  }

  @Test fun regionalViertel() {
    assertEquals(LocalTime.of(16, 15), DateParser.parse("Morgen viertel 5 am Eingang", now).time)
  }

  @Test fun quarterPastAndTo() {
    assertEquals(LocalTime.of(16, 15), DateParser.parse("Meet tomorrow at quarter past 4", now).time)
    assertEquals(LocalTime.of(16, 45), DateParser.parse("Meet tomorrow quarter to 5", now).time)
  }

  @Test fun halbWithUhrIsNotFullHour() {
    assertEquals(LocalTime.of(15, 30), DateParser.parse("Morgen halb 4 Uhr", now).time)
  }

  @Test fun stadtviertelIsNoTime() {
    assertNull(DateParser.parse("Im Stadtviertel 5 gibt es ein neues Café", now).time)
  }

  // ---------------- Early times are not moved to the evening ----------------

  @Test fun earlyClockTimeWithUhrStays() {
    assertEquals(LocalTime.of(6, 45), DateParser.parse("Abflug morgen 6:45 Uhr", now).time)
  }

  @Test fun leadingZeroStays() {
    assertEquals(LocalTime.of(6, 45), DateParser.parse("Abflug morgen 06:45", now).time)
  }

  @Test fun morningCueStays() {
    assertEquals(LocalTime.of(7, 0), DateParser.parse("Morgen früh um 7 am Bahnhof", now).time)
  }

  @Test fun bareHourIsStillAfternoon() {
    assertEquals(LocalTime.of(17, 0), DateParser.parse("Treffen morgen um 5", now).time)
  }

  @Test fun eveningStillWins() {
    assertEquals(LocalTime.of(18, 45), DateParser.parse("Morgen 6:45 Uhr abends", now).time)
  }

  // ---------------- Next week + weekday, version numbers ----------------

  @Test fun nextWeekTuesday() {
    // Today is Tuesday 10 Nov; "nächste Woche Dienstag" is 17 Nov, not today.
    assertEquals(LocalDate.of(2026, 11, 17), DateParser.parse("Nächste Woche Dienstag um 15 Uhr", now).date)
    // Thursday next week: 19 Nov, not this Thursday 12 Nov.
    assertEquals(LocalDate.of(2026, 11, 19), DateParser.parse("nächste Woche Donnerstag", now).date)
  }

  @Test fun versionNumberIsNoDate() {
    assertNull(DateParser.parse("Update auf Version 1.5.15 ist da", now).date)
    assertNull(DateParser.parse("Build 2.1.5.15 fertig", now).date)
  }

  @Test fun shortYearDateStillWorks() {
    assertEquals(LocalDate.of(2026, 12, 3), DateParser.parse("Termin am 3.12.26", now).date)
  }

  // ---------------- Sender titles ----------------

  @Test fun phoneNumberInParenthesesIsKept() {
    val svc = NotificationCaptureService()
    assertEquals("Max (+491701234567)", svc.cleanSenderTitle("Max (+491701234567)"))
    assertEquals("Anna (01701234567)", svc.cleanSenderTitle("Anna (01701234567)"))
    assertEquals("Sportfreunde", svc.cleanSenderTitle("Sportfreunde (+3)"))
    assertEquals("Anna", svc.cleanSenderTitle("Anna (12)"))
    assertEquals("Familie", svc.cleanSenderTitle("Familie (11 Nachrichten)"))
  }

  // ---------------- Voice messages ----------------

  @Test fun audioAloneIsNoVoiceMessage() {
    assertFalse(VoiceNotes.isVoiceMessage("Audio"))
    assertTrue(VoiceNotes.isVoiceMessage("🎤 Audio"))
    assertTrue(VoiceNotes.isVoiceMessage("Audio (0:12)"))
    assertTrue(VoiceNotes.isVoiceMessage("Sprachnachricht"))
  }

  // ---------------- Conversation aggregation ----------------

  private fun msgs(vararg texts: String) = texts.mapIndexed { i, t -> ConversationAggregator.SessionMessage("Gruppe", t, 1_000L * (i + 1)) }

  @Test fun okWithDateAloneIsNotPromotable() {
    val st = ConversationAggregator.evaluateThread(msgs("Gut, morgen bin ich nicht da"), now, AppSettings())
    assertFalse(st.promotable)
  }

  @Test fun cancellationBeatsConfirmation() {
    val st = ConversationAggregator.evaluateThread(msgs("Kino morgen um 20 Uhr?", "Ok, aber ich kann nicht"), now, AppSettings())
    assertEquals(ConversationAggregator.ThreadStatus.CANCELLED, st.status)
    assertFalse(st.promotable)
  }

  @Test fun partsFromSeveralMessagesArePromotable() {
    val st = ConversationAggregator.evaluateThread(msgs("Kino am Samstag?", "um 20 Uhr", "ok machen wir so"), now, AppSettings())
    assertTrue(st.promotable)
    assertEquals(LocalTime.of(20, 0), st.time)
    assertEquals("Kino (20–23 Uhr)", st.title)
  }

  @Test fun relativeDateUsesEachMessagesOwnTime() {
    // "morgen" written at 23:50, evaluated when the next message arrives at 00:10: still the next day of 23:50.
    val written = LocalDateTime.of(2026, 11, 10, 23, 50)
    val later = written.plusMinutes(20)
    val messages = listOf(
      ConversationAggregator.SessionMessage("A", "Treffen morgen um 18 Uhr?", 0L),
      ConversationAggregator.SessionMessage("B", "ok", 20 * 60_000L),
    )
    val st = ConversationAggregator.evaluateThread(messages, later, AppSettings())
    assertEquals(LocalDate.of(2026, 11, 11), st.date)
  }

  @Test fun threadIsRestoredFromHistory() {
    ConversationAggregator.clearAll()
    val base = 10_000_000L
    val st = ConversationAggregator.addMessageAndEvaluate(
      source = de.juliandigitalproducts.notitocal.model.SourceType.WHATSAPP,
      packageName = "com.whatsapp", sender = "Gruppe", text = "ok machen wir so", timestamp = base,
      nowDateTime = now, s = AppSettings(),
      history = { listOf(ConversationAggregator.SessionMessage("Gruppe", "Kino morgen um 20 Uhr", base - 60_000L)) },
    )
    assertEquals(2, st.messageCount)
    assertTrue(st.promotable)
  }

  // ---------------- ICS, screenshots, quiet hours ----------------

  @Test fun icsFoldingCountsOctetsAndKeepsCharacters() {
    val line = "SUMMARY:" + "ä".repeat(60) + "😀".repeat(10)
    val folded = Targets.fold(line)
    folded.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
    assertEquals(line, folded.replace("\r\n ", ""))
  }

  @Test fun screenshotFileNames() {
    assertEquals(42L, screenshotId("screenshot_42.jpg"))
    assertNull(screenshotId("other.jpg"))
    assertNull(screenshotId("screenshot_x.jpg"))
  }

  @Test fun quietHoursEnd() {
    val s = AppSettings(quietHours = true, quietStartMinute = 22 * 60, quietEndMinute = 7 * 60)
    assertEquals(60L, Deferred.minutesUntilQuietEnd(s, LocalTime.of(6, 0)))
    assertEquals(8 * 60L + 30, Deferred.minutesUntilQuietEnd(s, LocalTime.of(22, 30)))
    assertNull(Deferred.minutesUntilQuietEnd(s, LocalTime.of(12, 0)))
  }
}
