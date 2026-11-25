package de.juliandigitalproducts.notitocal.ui

import androidx.annotation.StringRes
import de.juliandigitalproducts.notitocal.R
import java.time.LocalDate
import java.time.ZoneId

/** Time ranges of the statistics. [days] is the length of one swipe step (0 = everything, no swiping). */
internal enum class StatPeriod(@StringRes val label: Int, val days: Int) {
  TODAY(R.string.stat_today, 1),
  DAYS_7(R.string.stat_7d, 7),
  DAYS_30(R.string.stat_30d, 30),
  ALL(R.string.stat_all_time, 0),
}

internal enum class ChartType { BAR, LINE }

/** One column / point of the activity chart: [start] inclusive, [end] exclusive (epoch millis). */
internal data class StatBucket(val start: Long, val end: Long)

/** The shown window ([start]..[end) for the numbers) and how the chart splits it. */
internal data class StatWindow(val start: Long, val end: Long, val buckets: List<StatBucket>) {
  operator fun contains(millis: Long) = millis in start until end
}

internal object StatsWindow {
  private const val HOURS_PER_BUCKET_TODAY = 3L
  private const val MONTHS_FOR_ALL = 12L

  /**
   * The window [offset] steps before the current one (0 = current, -1 = the period before …).
   * Today → one day in 3-hour columns, 7 / 30 days → daily columns ending today, everything → the last 12 months.
   */
  fun of(period: StatPeriod, offset: Int, today: LocalDate, zone: ZoneId): StatWindow {
    fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    return when (period) {
      StatPeriod.TODAY -> {
        val day = today.plusDays(offset.toLong())
        val start = day.atStartOfDay(zone)
        val buckets = (0 until 24 / HOURS_PER_BUCKET_TODAY).map { i ->
          StatBucket(
            start.plusHours(i * HOURS_PER_BUCKET_TODAY).toInstant().toEpochMilli(),
            start.plusHours((i + 1) * HOURS_PER_BUCKET_TODAY).toInstant().toEpochMilli(),
          )
        }
        StatWindow(ms(day), ms(day.plusDays(1)), buckets)
      }
      StatPeriod.DAYS_7, StatPeriod.DAYS_30 -> {
        val endDay = today.plusDays(1L + offset.toLong() * period.days)
        val firstDay = endDay.minusDays(period.days.toLong())
        val buckets = (0 until period.days).map { i -> StatBucket(ms(firstDay.plusDays(i.toLong())), ms(firstDay.plusDays(i + 1L))) }
        StatWindow(ms(firstDay), ms(endDay), buckets)
      }
      StatPeriod.ALL -> {
        val firstMonth = today.withDayOfMonth(1).minusMonths(MONTHS_FOR_ALL - 1)
        val buckets = (0 until MONTHS_FOR_ALL).map { i -> StatBucket(ms(firstMonth.plusMonths(i)), ms(firstMonth.plusMonths(i + 1))) }
        StatWindow(Long.MIN_VALUE, Long.MAX_VALUE, buckets)
      }
    }
  }

  /** Swiping goes back in time without limit, forward only up to the current period. */
  fun canGoForward(period: StatPeriod, offset: Int) = period != StatPeriod.ALL && offset < 0
  fun canGoBack(period: StatPeriod) = period != StatPeriod.ALL
}
