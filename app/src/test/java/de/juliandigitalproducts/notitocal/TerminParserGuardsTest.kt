// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Schutztests zur Terminoptimierung (Branch termin-optimierung): jede neue Regel mit ihrem Treffer und den naheliegenden
 * Fehllesungen, die sie nicht auslösen darf. Fester Bezug: Do 26.11.2026 14:30.
 */
class TerminParserGuardsTest {
  private val now = LocalDateTime.of(2026, 11, 26, 14, 30)
  private fun p(t: String, at: LocalDateTime = now) = DateParser.parse(t, at)
  private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

  @Test fun durationSetsEndButIsNoClockTime() {
    assertEquals(90, p("Meeting morgen 14 Uhr, Dauer ca. 90 Minuten").durationMinutes)
    assertEquals(30, p("Call tomorrow at 4pm for 30 min").durationMinutes)
    assertEquals(120, p("Massage Samstag 11 Uhr (2 Std.)").durationMinutes)
    assertEquals(90, p("Kurs morgen 18 Uhr für 1,5 h").durationMinutes)
    assertEquals(60, p("Treffen morgen 10 Uhr für eine Stunde").durationMinutes)
    // "2h" as a duration must not become a 14:00 start
    assertEquals(LocalTime.of(10, 0), p("Workshop morgen 10 Uhr für 2h").time)
    // persons, days, plain minutes in a recipe: no duration
    assertNull(p("Tisch für 2 Personen morgen 19 Uhr").durationMinutes)
    assertNull(p("25 Min backen").durationMinutes)
    assertNull(p("Urlaub für 3 Tage").durationMinutes)
    val e = RuleEngine.analyze("Meeting morgen 14 Uhr, Dauer ca. 90 Minuten", now = now)
    assertEquals("2026-11-27T15:30", e.end)
  }

  @Test fun startHourBeforeClockTime() {
    val r = p("Workshop am 9.12. von 9 bis 12:30 Uhr")
    assertEquals(LocalTime.of(9, 0), r.time)
    assertEquals(LocalTime.of(12, 30), r.endTime)
    // hour ranges with am/pm (found in the holdout check)
    val am = p("Kickoff call next Wed 10-11am")
    assertEquals(LocalTime.of(10, 0), am.time)
    assertEquals(LocalTime.of(11, 0), am.endTime)
    val pm = p("Open house Sat 11-1pm")
    assertEquals(LocalTime.of(11, 0), pm.time)
    assertEquals(LocalTime.of(13, 0), pm.endTime)
    assertEquals(LocalTime.of(14, 0), p("drop by 2-4 pm").time)
    // "2 to 3 h" stays a duration-like phrase, no "to 3 hours" clock time
    assertNull(p("It takes 2 to 3 hours").time)
    // a date range is not read as a clock range
    assertEquals(d(2026, 12, 2), p("Messe vom 2.-4.12.").date)
  }

  @Test fun germanWeekdayAbbreviationsOnlyBeforeTimeOrDate() {
    assertEquals(d(2026, 11, 30), p("Mo 14 Uhr Teammeeting").date)
    assertEquals(d(2026, 11, 27), p("Fr. 18:00 Abschiedsessen").date)
    assertEquals(d(2026, 11, 29), p("So. 10 Uhr Brunch").date)
    // "So, 10 Uhr passt" = "okay, 10 o'clock": no Sunday; "Fr. Müller" is a person
    assertNotEquals(d(2026, 11, 29), p("So, 10 Uhr passt mir").date)
    assertNull(p("Fr. Müller hat angerufen").date)
    assertNull(p("Do it now").date)
  }

  @Test fun recurringPluralsAndTypos() {
    assertEquals(d(2026, 11, 30), p("immer montags 19 Uhr Yoga").date)
    assertEquals(d(2026, 12, 2), p("Termin am Mitwoch um 9").date)
    assertEquals(d(2026, 11, 30), p("Ab nächster Woche Montag Training").date)
  }

  @Test fun pastWeekdayIsALookBack() {
    assertEquals(d(2026, 11, 20), p("letzten Freitag um 9 im Biergarten").date)
    assertEquals(d(2026, 11, 23), p("last Monday at 5").date)
  }

  @Test fun weekdayOfTodayWithPassedTimeMeansNextWeek() {
    assertEquals(d(2026, 12, 3), p("moved to Thursday 2pm").date)
    // still ahead today: stays today
    assertEquals(d(2026, 11, 26), p("Donnerstag 18 Uhr Kino").date)
    // "diesen Donnerstag" is written on purpose
    assertEquals(d(2026, 11, 26), p("diesen Donnerstag 10 Uhr").date)
  }

  @Test fun rescheduleReadsTheNewDay() {
    assertEquals(d(2026, 12, 2), p("Zahnarzttermin verschieben: statt Dienstag jetzt Mittwoch um 15 Uhr").date)
    assertEquals(d(2026, 11, 28), p("Kino ist verschoben vom Freitag auf Samstag, 20 Uhr").date)
    assertEquals(d(2026, 12, 2), p("The call was moved from Tuesday to Wednesday 10am").date)
    // without a rescheduling word "from Tuesday to Wednesday" stays a range
    val range = p("Conference from Tuesday to Wednesday")
    assertEquals(d(2026, 12, 1), range.date)
    assertEquals(d(2026, 12, 2), range.endDate)
    // a date range in a rescheduling message is not cut
    val urlaub = p("Urlaub verschoben, jetzt vom 21.12. bis 3.1.")
    assertEquals(d(2026, 12, 21), urlaub.date)
    assertEquals(d(2027, 1, 3), urlaub.endDate)
  }

  @Test fun abHourOnlyWithDate() {
    assertEquals(LocalTime.of(15, 0), p("morgen ab 3 im Park").time)
    assertNull(p("Eintritt ab 18 Jahren").time)
    assertNull(p("morgen ab 18 Jahren frei").time)
    assertNull(p("Versand ab 3 Stück").time)
  }

  @Test fun atSignHourAndPlace() {
    assertEquals(LocalTime.of(11, 0), p("brunch Sat @ 11").time)
    assertEquals("Studio Bloom", RuleEngine.extractLocation("yoga tonight 6:30pm @ Studio Bloom"))
    assertNull(p("mail me at name@11mail.example").time)
  }

  @Test fun holidays() {
    assertEquals(d(2026, 12, 31), p("Silvester feiern wir bei Jonas").date)
    assertEquals(d(2026, 12, 24), p("Heiligabend bei Oma").date)
    assertEquals(d(2027, 1, 1), p("Neujahrstag Brunch").date)
    assertEquals(d(2026, 12, 25), p("Christmas Day lunch").date)
  }

  @Test fun monthDayRange() {
    val r = p("Company retreat Dec 10-12, Lake Constance")
    assertEquals(d(2026, 12, 10), r.date)
    assertEquals(d(2026, 12, 12), r.endDate)
    // "Dec 3, 4:15pm" is no range
    assertNull(p("conference Dec 3rd, 4:15pm").endDate)
  }

  @Test fun yearOfDatesWithoutYear() {
    // recent past stays in the past (ignored as past later), older dates mean the coming year
    assertEquals(d(2026, 11, 14), p("Fotos vom 14.11.").date)
    assertEquals(d(2026, 10, 15), p("Rechnung vom 15.10.").date)
    assertEquals(d(2027, 9, 1), p("am 1.9.").date)
    assertEquals(d(2027, 1, 3), p("am 3.1.").date)
    // in early January the December just gone is the past, not next December
    assertEquals(d(2026, 12, 28), p("am 28.12.", LocalDateTime.of(2027, 1, 3, 10, 0)).date)
    // with a year: as written
    assertEquals(d(2025, 11, 14), p("am 14.11.2025").date)
  }

  @Test fun titlesAndPlaces() {
    val bank = RuleEngine.analyze(
      "Sehr geehrter Herr Beispiel,\nhiermit bestätigen wir Ihren Beratungstermin am Mittwoch, 02.12.2026, 16:00 Uhr.\nMit freundlichen Grüßen\nIhre Musterbank\nTel. 0711 000000",
      now = now,
    )
    assertTrue(bank.title, bank.title.startsWith("Beratungstermin"))
    assertEquals("Musterbank", bank.senderName)
    assertEquals("Haupteingang Stadtpark", RuleEngine.extractLocation("Treffpunkt: Haupteingang Stadtpark, Sonntag 10 Uhr, Laufgruppe"))
    assertEquals("Café Lotte, Hauptstraße 5", RuleEngine.extractLocation("Ort: Café Lotte, Hauptstraße 5"))
    val messe = RuleEngine.analyze("Messe in Köln vom 2. bis 4. Dezember", now = now)
    assertEquals("Messe in Köln", messe.title)
    val bday = RuleEngine.analyze("Don't forget: Lena's birthday is on Saturday", now = now)
    assertEquals(Kind.EVENT, bday.kind)
    assertEquals("Lena's birthday", bday.title)
    assertTrue(bday.allDay)
  }
}
