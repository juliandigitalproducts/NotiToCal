package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.output.DuplicateMatcher
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.Action
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.Candidate
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.Proposed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateMatcherTest {
  private val start = 1_700_000_000_000L

  private fun candidate(
    title: String,
    description: String = "",
    location: String = "",
    deltaMillis: Long = 0,
    access: Int = 700,
    id: Long = 11,
  ) = Candidate(id, 3, title, description, location, start + deltaMillis, access)

  @Test fun sameTitleAndTimeMerges() {
    val outcome = DuplicateMatcher.evaluate(Proposed("Team sync", "Bring the notes", "Room 4", start), listOf(candidate("Team sync", location = "Room 4")))
    assertEquals(Action.MERGE, outcome.action)
    assertTrue(outcome.score >= DuplicateMatcher.THRESHOLD)
    assertEquals("Bring the notes", outcome.descriptionUpdate)
    assertNull(outcome.locationUpdate)
  }

  @Test fun punctuationAndEmojiStillMatch() {
    val outcome = DuplicateMatcher.evaluate(
      Proposed("team sync!!", "Agenda", "", start + 10 * 60_000),
      listOf(candidate("Team sync 🎉", description = "", location = "")),
    )
    assertEquals(Action.MERGE, outcome.action)
    assertEquals(DuplicateMatcher.normalize("Team sync 🎉"), DuplicateMatcher.normalize("team sync!!"))
  }

  @Test fun farApartOrDifferentTitleCreates() {
    val far = DuplicateMatcher.evaluate(
      Proposed("Team sync", "", "", start),
      listOf(candidate("Team sync", deltaMillis = 3 * 60 * 60_000)),
    )
    assertEquals(Action.CREATE, far.action)
    val other = DuplicateMatcher.evaluate(Proposed("Dentist", "", "", start), listOf(candidate("Dinner")))
    assertEquals(Action.CREATE, other.action)
  }

  @Test fun readOnlyCalendarSkipsWithoutWrites() {
    val outcome = DuplicateMatcher.evaluate(
      Proposed("Team sync", "New details", "Home", start),
      listOf(candidate("Team sync", description = "Keep me", location = "Office", access = 200)),
    )
    assertEquals(Action.SKIP_READONLY, outcome.action)
    assertNull(outcome.locationUpdate)
    assertNull(outcome.descriptionUpdate)
    assertTrue(outcome.candidate!!.accessLevel < DuplicateMatcher.ACCESS_CONTRIBUTOR)
  }

  @Test fun contributorCanMerge() {
    val outcome = DuplicateMatcher.evaluate(
      Proposed("Team sync", "Notes", "", start),
      listOf(candidate("Team sync", access = DuplicateMatcher.ACCESS_CONTRIBUTOR)),
    )
    assertEquals(Action.MERGE, outcome.action)
  }

  @Test fun existingLocationAndDescriptionAreNotReplaced() {
    val original = "Line one.\nKeep the code 42.\nLast line."
    val outcome = DuplicateMatcher.evaluate(
      Proposed("Standup", "New notes that must not replace the old text", "Home office", start),
      listOf(candidate("Standup", description = original, location = "Office")),
    )
    assertEquals(Action.MERGE, outcome.action)
    assertNull(outcome.locationUpdate)
    val updated = outcome.descriptionUpdate!!
    assertTrue(updated.startsWith(original))
    assertEquals(original, updated.substring(0, original.length))
    assertTrue(updated.contains("Keep the code 42."))
    assertTrue(updated.contains(DuplicateMatcher.MARKER))
    assertTrue(updated.contains("New notes that must not replace the old text"))
    assertTrue(updated.indexOf("Home office") > original.length)
  }

  @Test fun emptyLocationIsFilledAndEmptyDescriptionIsSet() {
    val outcome = DuplicateMatcher.evaluate(
      Proposed("Standup", "Only the new text", "Room 2", start),
      listOf(candidate("Standup", description = "   ", location = "")),
    )
    assertEquals("Room 2", outcome.locationUpdate)
    assertEquals("Only the new text", outcome.descriptionUpdate)
    assertTrue(!outcome.descriptionUpdate!!.contains(DuplicateMatcher.MARKER))
  }

  @Test fun highestScoreWins() {
    val weak = candidate("Team", id = 1, deltaMillis = 90 * 60_000)
    val strong = candidate("Team sync", id = 2, deltaMillis = 0)
    val outcome = DuplicateMatcher.evaluate(Proposed("Team sync", "x", "", start), listOf(weak, strong))
    assertEquals(2L, outcome.candidate?.eventId)
  }

  @Test fun repeatedMergeDoesNotDuplicateNotiToCalMarkerBlock() {
    val existingDesc = "Original text\n\n—— NotiToCal ——\nMittagessen\nOriginal text"
    val outcome = DuplicateMatcher.evaluate(
      Proposed("Mittagessen", "Original text", "Max Mustermann", start),
      listOf(candidate("Mittagessenbesuch", description = existingDesc, location = "Max Mustermann")),
    )
    assertEquals(Action.MERGE, outcome.action)
    assertNull("Should not append duplicate block when description is already present", outcome.descriptionUpdate)
  }

  @Test fun intelligentClusterMatchesMittagessenLeverkusenLevAndTimedMittagessenbesuch() {
    val zone = java.time.ZoneId.of("Europe/Berlin")
    val day = java.time.LocalDate.of(2026, 11, 14)
    val allDayStartUtc = day.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    val timed1400 = day.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
    val timed1500 = day.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()

    // 1. Tagestermin: „Mittagessen Leverkusen“
    val ev1 = DuplicateMatcher.CalendarEventItem(
      eventId = 101L,
      calendarId = 1L,
      calendarName = "Privat",
      title = "Mittagessen Leverkusen",
      startMillis = allDayStartUtc,
      endMillis = allDayStartUtc + 24 * 3600_000L,
      allDay = true,
    )
    // 2. Weiterer Termin: „Mittagsessen Lev“
    val ev2 = DuplicateMatcher.CalendarEventItem(
      eventId = 102L,
      calendarId = 2L,
      calendarName = "Familie",
      title = "Mittagsessen Lev",
      description = "Kuchen mitbringen",
      startMillis = allDayStartUtc,
      endMillis = allDayStartUtc + 24 * 3600_000L,
      allDay = true,
    )
    // 3. Konkreter Termin: „Mittagessenbesuch“, 14:00–15:00 Uhr
    val ev3 = DuplicateMatcher.CalendarEventItem(
      eventId = 103L,
      calendarId = 1L,
      calendarName = "Privat",
      title = "Mittagessenbesuch",
      description = "Einladung bei Max",
      startMillis = timed1400,
      endMillis = timed1500,
      allDay = false,
      reminderMinutes = listOf(30),
    )

    val clusters = DuplicateMatcher.findClusters(
      events = listOf(ev1, ev2, ev3),
      crossCalendar = true,
      zone = zone,
    )
    assertEquals("All 3 events must be grouped into 1 duplicate cluster", 1, clusters.size)
    val cluster = clusters.first()
    assertEquals(3, cluster.events.size)

    // Timed event ev3 has the highest data quality score and is chosen as default primary
    assertEquals(103L, cluster.defaultPrimaryId)

    // Proposal keeps concrete 14:00-15:00 time, adopts full location "Leverkusen", and combines descriptions
    val prop = cluster.proposal
    assertEquals(false, prop.allDay)
    assertEquals(timed1400, prop.startMillis)
    assertEquals(timed1500, prop.endMillis)
    assertEquals("Leverkusen", prop.location)
    assertTrue(prop.description.contains("Einladung bei Max"))
    assertTrue(prop.description.contains("Kuchen mitbringen"))
  }

  @Test fun crossCalendarFlagAndIgnoredSignaturesRespected() {
    val zone = java.time.ZoneId.of("Europe/Berlin")
    val day = java.time.LocalDate.of(2026, 11, 20)
    val t = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    val a = DuplicateMatcher.CalendarEventItem(eventId = 1L, calendarId = 10L, title = "Zahnarzt Kontrolle", startMillis = t)
    val b = DuplicateMatcher.CalendarEventItem(eventId = 2L, calendarId = 20L, title = "Zahnarzttermin", startMillis = t)

    // Cross-calendar disabled -> no match across calendars 10 and 20
    assertTrue(DuplicateMatcher.findClusters(listOf(a, b), crossCalendar = false, zone = zone).isEmpty())

    // Cross-calendar enabled -> matched
    val found = DuplicateMatcher.findClusters(listOf(a, b), crossCalendar = true, zone = zone)
    assertEquals(1, found.size)

    // When marked as "Not a duplicate" (ignored signature), skipped on future scans
    val sig = found.first().id
    assertTrue(DuplicateMatcher.findClusters(listOf(a, b), crossCalendar = true, ignoredSignatures = setOf(sig), zone = zone).isEmpty())
  }
}
