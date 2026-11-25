package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.ui.StatPeriod
import de.juliandigitalproducts.notitocal.ui.StatsWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsWindowTest {
  private val zone = ZoneId.of("Europe/Berlin")
  private val today = LocalDate.of(2026, 11, 11)
  private fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

  @Test fun sevenDaysEndWithToday() {
    val w = StatsWindow.of(StatPeriod.DAYS_7, 0, today, zone)
    assertEquals(ms(LocalDate.of(2026, 11, 5)), w.start)
    assertEquals(ms(LocalDate.of(2026, 11, 12)), w.end)
    assertEquals(7, w.buckets.size)
  }

  @Test fun swipingBackShowsThePreviousWeek() {
    val w = StatsWindow.of(StatPeriod.DAYS_7, -1, today, zone)
    assertEquals(ms(LocalDate.of(2026, 10, 29)), w.start)
    assertEquals(ms(LocalDate.of(2026, 11, 5)), w.end)
  }

  @Test fun todayIsSplitIntoThreeHourColumnsAcrossDst() {
    val dstDay = LocalDate.of(2026, 10, 25) // clocks go back: 25 hours
    val w = StatsWindow.of(StatPeriod.TODAY, 0, dstDay, zone)
    assertEquals(8, w.buckets.size)
    assertEquals(w.start, w.buckets.first().start)
    assertEquals(ms(dstDay.plusDays(1)), w.end)
  }

  @Test fun allCoversEverythingWithTwelveMonths() {
    val w = StatsWindow.of(StatPeriod.ALL, -3, today, zone)
    assertEquals(12, w.buckets.size)
    assertTrue(0L in w)
    assertFalse(StatsWindow.canGoBack(StatPeriod.ALL))
  }

  @Test fun noSwipingIntoTheFuture() {
    assertFalse(StatsWindow.canGoForward(StatPeriod.DAYS_30, 0))
    assertTrue(StatsWindow.canGoForward(StatPeriod.DAYS_30, -1))
  }
}
