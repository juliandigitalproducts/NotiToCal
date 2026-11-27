// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.analysis.ModelManager
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.BackupCrypto
import de.juliandigitalproducts.notitocal.data.HashIndex
import de.juliandigitalproducts.notitocal.data.HistoryPolicy
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.data.LogRepository
import de.juliandigitalproducts.notitocal.data.RoutingRule
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.data.hashRecord
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.net.Http
import de.juliandigitalproducts.notitocal.output.DuplicateJudge
import de.juliandigitalproducts.notitocal.output.DuplicateJudge.Verdict
import de.juliandigitalproducts.notitocal.output.Targets
import de.juliandigitalproducts.notitocal.ui.CalendarFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL

/** Review 2026-11-11 (1.5.17): duplicates with clock times, updates to every target, hardening. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReviewFixes1517Test {
  private fun ev(title: String, start: String, location: String? = null) = Detection(Kind.EVENT, title = title, start = start, location = location)

  // ---------------------------------------------------------------- DuplicateJudge

  @Test fun endOfEventBelongsToTheEvent() {
    val old = ev("Veranstaltung", "2026-11-20T14:00")
    val new = ev("Ende der Veranstaltung", "2026-11-20T15:00")
    assertEquals(Verdict.END_TIME, DuplicateJudge.judge(new, "Ende der Veranstaltung 15 Uhr", "Anna", old, "Veranstaltung 14 Uhr", "Ben"))
  }

  @Test fun sameCustomerAtAnotherTimeIsUnsureNotADuplicate() {
    val old = ev("Kunde", "2026-11-20T14:00", "Solingen")
    val new = ev("Kunde treffen", "2026-11-20T15:00", "Solingen")
    assertEquals(
      Verdict.UNCERTAIN,
      DuplicateJudge.judge(new, "Kunde treffen 15 Uhr Solingen", "", old, "Kunde 14 Uhr Solingen", ""),
    )
  }

  @Test fun movedAppointmentIsRecognised() {
    val old = ev("Kunde", "2026-11-20T14:00", "Solingen")
    val new = ev("Kunde", "2026-11-20T15:00", "Solingen")
    assertEquals(
      Verdict.MOVED,
      DuplicateJudge.judge(new, "Der Termin mit dem Kunden in Solingen wird auf 15 Uhr verschoben", "", old, "Kunde 14 Uhr Solingen", ""),
    )
  }

  @Test fun sameTimeSameTitleIsTheSame() {
    val old = ev("Zahnarzt", "2026-11-20T09:00")
    assertEquals(Verdict.SAME, DuplicateJudge.judge(ev("Zahnarzt", "2026-11-20T09:05"), "Zahnarzt 9 Uhr", "", old, "Zahnarzt um 9", ""))
  }

  @Test fun unrelatedAppointmentsAreDifferent() {
    val old = ev("Zahnarzt", "2026-11-20T09:00")
    assertEquals(Verdict.DIFFERENT, DuplicateJudge.judge(ev("Fußball", "2026-11-20T18:00"), "Fußball 18 Uhr", "", old, "Zahnarzt 9", ""))
    assertEquals(Verdict.DIFFERENT, DuplicateJudge.judge(ev("Zahnarzt", "2026-11-21T09:00"), "Zahnarzt morgen", "", old, "Zahnarzt", ""))
  }

  @Test fun byeIsNoEndTime() {
    // "bis dann" is a greeting, not "bis 15 Uhr".
    val old = ev("Kunde", "2026-11-20T14:00", "Solingen")
    val new = ev("Kunde", "2026-11-20T15:00", "Solingen")
    assertEquals(Verdict.UNCERTAIN, DuplicateJudge.judge(new, "Kunde 15 Uhr Solingen, bis dann", "", old, "Kunde 14 Uhr Solingen", ""))
    assertTrue(DuplicateJudge.hasEndMarker("geht bis 15 Uhr"))
    assertFalse(DuplicateJudge.hasEndMarker("bis dann"))
  }

  @Test fun aiAnswerIsParsed() {
    assertEquals(true, DuplicateJudge.parse("{\"same\": true}"))
    assertEquals(false, DuplicateJudge.parse("<think>hm</think>{\"same\":false}"))
    assertNull(DuplicateJudge.parse("no idea"))
    assertNull(DuplicateJudge.parse(null))
  }

  // ---------------------------------------------------------------- fingerprint index with the clock time

  @Test fun fingerprintsWithDifferentTimesAreNoPlainDuplicate() {
    val now = System.currentTimeMillis()
    val index = HashIndex()
    index.upsert(hashRecord(1, now, "h1", Status.ADDED, ev("Meeting", "2026-11-20T09:00"), "Meeting morgen um 9 Uhr im Büro"))
    assertEquals("09:00", index.records().single().time)
    assertNull(index.findDuplicate("Meeting", "Meeting morgen um 15 Uhr im Büro", "2026-11-20", 60, now, "15:00"))
    assertEquals(1L, index.findDuplicate("Meeting", "anders formuliert, aber gleich", "2026-11-20", 60, now, "09:00")?.logId)
    // The very same text stays a duplicate.
    assertEquals(1L, index.findDuplicate("x", "Meeting morgen um 9 Uhr im Büro", "2026-11-20", 60, now, "15:00")?.logId)
    // Without a clock time the old behaviour stays.
    assertEquals(1L, index.findDuplicate("Meeting", "", "2026-11-20", 60, now)?.logId)
  }

  @Test fun timeOfIsEmptyForAllDay() {
    assertEquals("", HistoryPolicy.timeOf(Detection(Kind.EVENT, title = "x", start = "2026-11-20", allDay = true)))
    assertEquals("14:30", HistoryPolicy.timeOf(ev("x", "2026-11-20T14:30")))
  }

  // ---------------------------------------------------------------- LogRepository.classify

  @Test fun repositoryKeepsTwoMeetingsAndClassifiesTheEnd() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    context.deleteDatabase("notitocal.db")
    val repo = LogRepository(context)
    try {
      repo.insert(SourceType.NOTIFICATION, "pkg", "App", "Anna", "Meeting 9 Uhr", ev("Meeting", "2026-11-20T09:00"), Status.ADDED, "a")
      assertNull(repo.findDuplicate(ev("Meeting", "2026-11-20T15:00"), "Meeting 15 Uhr", 12 * 60, "Anna"))
      repo.insert(SourceType.NOTIFICATION, "pkg", "App", "Ben", "Veranstaltung 14 Uhr", ev("Veranstaltung", "2026-11-21T14:00"), Status.ADDED, "b")
      val end = repo.classify(ev("Ende der Veranstaltung", "2026-11-21T15:00"), "Ende der Veranstaltung 15 Uhr", 12 * 60, "Ben")
      assertEquals(Verdict.END_TIME, end?.second)
      assertEquals("Veranstaltung", end?.first?.detection?.title)
    } finally {
      repo.close()
    }
  }

  // ---------------------------------------------------------------- delivery

  @Test fun succeededTargetsAreReadFromTheSummary() {
    val summary = "✓ DEVICE_CALENDAR: added\n✗ CALDAV: HTTP 500\n✓ WEBHOOK: webhook ok\n↻ TODOIST: not updated"
    assertEquals(setOf(TargetType.DEVICE_CALENDAR, TargetType.WEBHOOK), Targets.succeededTargets(summary))
  }

  @Test fun onlyNetworkTroubleIsRetried() {
    assertTrue(Targets.isTransient(IOException("timeout")))
    assertTrue(Targets.isTransient(de.juliandigitalproducts.notitocal.net.HttpException(503, "")))
    assertTrue(Targets.isTransient(de.juliandigitalproducts.notitocal.net.HttpException(429, "")))
    assertFalse(Targets.isTransient(de.juliandigitalproducts.notitocal.net.HttpException(401, "")))
    assertFalse(Targets.isTransient(de.juliandigitalproducts.notitocal.net.InsecureUrlException("example.com")))
    assertFalse(Targets.isTransient(SecurityException("calendar permission")))
  }

  @Test fun serviceIdsAreTakenFromTheAnswer() {
    assertEquals("6X7rM8997g3RQmvh", Targets.responseId("{\"id\":\"6X7rM8997g3RQmvh\",\"content\":\"x\"}"))
    assertNull(Targets.responseId("not json"))
  }

  @Test fun payloadCarriesUidAndAction() {
    val d = ev("Kunde", "2026-11-20T15:00").copy(syncUid = "abc-123-def")
    val src = de.juliandigitalproducts.notitocal.output.SourceInfo("App", "pkg", "", "", "NOTIFICATION")
    val p = Targets.payload(d, src, AppSettings(), action = Targets.ACTION_UPDATE).toString()
    assertTrue(p.contains("\"uid\":\"abc-123-def\""))
    assertTrue(p.contains("\"action\":\"update\""))
    assertTrue(Targets.buildIcs(d, AppSettings()).contains("UID:abc-123-def"))
  }

  // ---------------------------------------------------------------- hardening

  @Test fun credentialsStayOnTheirHost() {
    val headers = mapOf("Authorization" to "Bearer x", "x-api-key" to "k", "Notion-Version" to "2022-06-28")
    assertEquals(headers, Http.headersForRedirect(headers, URL("https://api.example.com/a"), URL("https://api.example.com/b")))
    assertEquals(mapOf("Notion-Version" to "2022-06-28"), Http.headersForRedirect(headers, URL("https://api.example.com/a"), URL("https://evil.example.net/")))
  }

  @Test fun largeAnswersAreCut() {
    assertEquals("hello", Http.readLimited(ByteArrayInputStream("hello".toByteArray()), 10))
    val thrown = runCatching { Http.readLimited(ByteArrayInputStream(ByteArray(100)), 10) }.exceptionOrNull()
    assertTrue(thrown is IOException)
  }

  @Test fun backupWithAbsurdIterationCountIsRefused() {
    val enc = BackupCrypto.encrypt(mapOf("k" to "v"), "password123")
    assertEquals(mapOf("k" to "v"), BackupCrypto.decrypt(enc, "password123"))
    val manipulated = enc.copy(iterations = Int.MAX_VALUE)
    assertTrue(runCatching { BackupCrypto.decrypt(manipulated, "password123") }.exceptionOrNull() is IllegalArgumentException)
  }

  @Test fun importedModelNamesCannotLeaveTheFolder() {
    assertEquals("evil.task", ModelManager.safeFileName("../../evil.task"))
    assertEquals("a_b.task", ModelManager.safeFileName("a b.task"))
    assertEquals("imported.task", ModelManager.safeFileName(".."))
  }

  // ---------------------------------------------------------------- calendar filter

  @Test fun calendarFilterFollowsTheRoutingRules() {
    val work = RoutingRule(id = "work", name = "Arbeit", calendarId = 3)
    val s = AppSettings(routingRules = listOf(work, RoutingRule(id = "old", name = "Alt", enabled = false)))
    fun entry(route: String?) = LogEntry(1, 0, SourceType.NOTIFICATION, "", "", "", "", Detection(Kind.EVENT, title = "x", route = route), Status.ADDED, "")
    assertTrue(CalendarFilter.matches(entry("work"), "work", s))
    assertFalse(CalendarFilter.matches(entry(null), "work", s))
    assertTrue(CalendarFilter.matches(entry(null), CalendarFilter.DEFAULT, s))
    // A disabled rule no longer routes: its entries count as default calendar.
    assertTrue(CalendarFilter.matches(entry("old"), CalendarFilter.DEFAULT, s))
    assertTrue(CalendarFilter.matches(entry("work"), null, s))
    assertNull(CalendarFilter.valid("old", s))
    assertEquals("work", CalendarFilter.valid("work", s))
  }
}
