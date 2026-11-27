// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.data.ActionMode
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.LocationRecognitionMode
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.output.Privacy
import de.juliandigitalproducts.notitocal.output.SourceInfo
import de.juliandigitalproducts.notitocal.output.Targets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Second review 20.11.2026: every test here fails on the branch state before it (2b3e533). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NotiToCalApp::class)
class SecondReview1120Test {
  private val now = LocalDateTime.of(2026, 11, 20, 10, 0)
  private val berlin = ZoneId.of("Europe/Berlin")
  private fun p(t: String, at: LocalDateTime = now) = DateParser.parse(t, at, zone = berlin)

  // ---- hour ranges ----

  @Test fun hourRangeWithSeparateHIsADurationNotATime() {
    val r = p("Fahrzeit 2-3 h, Abfahrt morgen 9:30")
    assertEquals(LocalTime.of(9, 30), r.time)
    assertNull(r.endTime)
  }

  @Test fun zwischenUndIsARange() {
    val r = p("Zustellung morgen zwischen 14 und 16 Uhr")
    assertEquals(LocalDate.of(2026, 11, 21), r.date)
    assertEquals(LocalTime.of(14, 0), r.time)
    assertEquals(LocalTime.of(16, 0), r.endTime)
    val clock = p("Lieferung morgen zwischen 14:00 und 16:30")
    assertEquals(LocalTime.of(14, 0), clock.time)
    assertEquals(LocalTime.of(16, 30), clock.endTime)
    val marked = p("zwischen 9 Uhr und 11 Uhr im Büro")
    assertEquals(LocalTime.of(9, 0), marked.time)
    assertEquals(LocalTime.of(11, 0), marked.endTime)
    val en = p("delivery tomorrow between 2pm and 4pm")
    assertEquals(LocalTime.of(14, 0), en.time)
    assertEquals(LocalTime.of(16, 0), en.endTime)
  }

  @Test fun undWithoutZwischenIsNoRange() {
    // Two separate times, and a number of people between "zwischen" and "und".
    val two = p("Treffen um 14 Uhr und um 18 Uhr Essen")
    assertEquals(LocalTime.of(14, 0), two.time)
    assertNull(two.endTime)
    val people = p("Wir sind zwischen 3 und 5 Leute, Treffen morgen um 18 Uhr")
    assertEquals(LocalTime.of(18, 0), people.time)
    assertNull(people.endTime)
  }

  @Test fun rangeTitleLosesTheWholeRange() {
    val d = RuleEngine.analyze("Paket kommt morgen zwischen 14 und 16 Uhr", "", now, 60, false)
    assertEquals("2026-11-21T14:00", d.start)
    assertEquals("2026-11-21T16:00", d.end)
    assertFalse(d.title, d.title.contains("16") || d.title.contains("und") || d.title.lowercase().endsWith("zwischen"))
  }

  // ---- "um 7:45" ----

  @Test fun umBeforeAClockTimeOnlyAsAWord() {
    // The window before the time must not cut "Datum" to "um".
    assertEquals(p("Datum 7:45 Treffen").time, p("Datum  7:45 Treffen").time)
    assertEquals(LocalTime.of(7, 45), p("morgen um  7:45").time)
  }

  // ---- time zones ----

  @Test fun timeWithZoneIsConvertedToThePhoneZone() {
    val est = p("Webinar am 01.12.2026 um 17:00 EST")
    assertEquals(LocalDate.of(2026, 12, 1), est.date)
    assertEquals(LocalTime.of(23, 0), est.time)
    // the day changes, too
    val pst = p("Call on 2026-12-01 at 11pm PST")
    assertEquals(LocalDate.of(2026, 12, 2), pst.date)
    assertEquals(LocalTime.of(8, 0), pst.time)
    // fixed offsets
    assertEquals(LocalTime.of(17, 0), p("Release am 03.12.2026 um 18:00 UTC+2").time)
    assertEquals(LocalTime.of(19, 0), p("Release am 03.12.2026 um 18:00 GMT").time)
    // a range moves as a whole
    val range = p("Sync 02.12.2026 14:00-15:00 EST")
    assertEquals(LocalTime.of(20, 0), range.time)
    assertEquals(LocalTime.of(21, 0), range.endTime)
  }

  @Test fun centralEuropeanAbbreviationsMeanTheLocalTimeAllYear() {
    assertEquals(LocalTime.of(17, 0), p("Webinar am 01.12.2026 um 17:00 CET").time)
    assertEquals(LocalTime.of(10, 0), p("Termin am 05.12.2026 um 10 Uhr MEZ").time)
    // "CET" written in summer: still the time in Berlin, not one hour later
    assertEquals(LocalTime.of(17, 0), p("Webinar am 01.07.2027 um 17:00 CET").time)
  }

  @Test fun zoneWordsAndAmbiguousAbbreviationsAreLeftAlone() {
    assertEquals(LocalTime.of(18, 0), p("Treffen morgen 18:00 west entrance").time)
    assertEquals(LocalTime.of(18, 0), p("Treffen morgen 18:00 CST").time)
    assertEquals(LocalTime.of(18, 0), p("Treffen morgen 18:00 IST").time)
  }

  @Test fun zoneIsNotPartOfTheTitle() {
    val d = RuleEngine.analyze("Webinar Kubernetes basics on 2026-12-01 at 17:00 EST", "", now, 60, false)
    assertFalse(d.title, d.title.contains("EST"))
  }

  // ---- privacy: webhook / own server ----

  private val message = "Treffen wir uns morgen um 15 Uhr im Café Lotte? Geheimer Satz."
  private val src = SourceInfo("Chat", "com.example.chat", "Anna", message, "NOTIFICATION")

  @Test fun rulesDescriptionIsTheMessageAndStaysHomeWhenSharingIsOff() {
    val d = RuleEngine.analyze(message, "Anna", now, 60, false)
    assertTrue("rules put the message into the description", d.description.contains("Geheimer Satz"))
    val off = Targets.payload(d, src, AppSettings(sendSourceToTargets = false)).toString()
    assertFalse(off, off.contains("Geheimer Satz"))
    val on = Targets.payload(d, src, AppSettings(sendSourceToTargets = true)).toString()
    assertTrue(on.contains("Geheimer Satz"))
    val template = "{\"d\":\"{{description}}\",\"j\":{{json}}}"
    assertFalse(Targets.fillTemplate(template, d, src, AppSettings(sendSourceToTargets = false)).contains("Geheimer Satz"))
    assertTrue(Targets.fillTemplate(template, d, src, AppSettings(sendSourceToTargets = true)).contains("Geheimer Satz"))
  }

  @Test fun modelDescriptionIsKeptUnlessItIsTheMessage() {
    val own = Detection(Kind.EVENT, title = "Treffen", start = "2026-11-21T15:00", description = "Kaffee mit Anna", engine = "OpenAI")
    assertEquals("Kaffee mit Anna", Privacy.descriptionWithoutMessage(own, src.text))
    val copied = own.copy(description = "Treffen wir uns morgen um 15 Uhr im  Café Lotte?\nGeheimer Satz.")
    assertEquals("", Privacy.descriptionWithoutMessage(copied, src.text))
    assertEquals(copied.description, Privacy.shareable(copied, src, withSource = true).description)
  }

  // ---- pinned clock of the pipeline ----

  private val app get() = ApplicationProvider.getApplicationContext<NotiToCalApp>()

  @After fun resetClock() { app.pipeline.clock = Clock.systemDefaultZone() }

  @Test fun pipelineDecidesWithItsClock() = runBlocking {
    // A message from 1 March 2025 about the next day: with the real clock that day is long past and would be ignored.
    val sent = LocalDateTime.of(2025, 3, 1, 10, 0).atZone(ZoneId.systemDefault())
    app.pipeline.clock = Clock.fixed(sent.toInstant(), sent.zone)
    app.log.clear()
    app.settings.update {
      AppSettings().copy(
        onboardingDone = true, engine = AiEngine.RULES, locationRecognitionMode = LocationRecognitionMode.FAST,
        locationEnrichmentEnabled = false, eventTargets = setOf(TargetType.AUTOMATION_BROADCAST), autoAddConfidence = 0.0,
        eventMode = ActionMode.AUTO, dedupeMinutes = 0,
      )
    }
    app.pipeline.process(
      Captured(SourceType.NOTIFICATION, "com.example.clock", "Chat", "Praxis", "Zahnarzt morgen um 9 Uhr", postedAt = sent.toInstant().toEpochMilli()),
    )
    val e = app.log.allEntries().single { it.detection.kind == Kind.EVENT }
    assertEquals("2025-03-02T09:00", e.detection.start)
    assertEquals(Status.ADDED, e.status)
  }
}
