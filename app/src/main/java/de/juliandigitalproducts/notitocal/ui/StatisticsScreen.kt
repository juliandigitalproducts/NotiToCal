// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import kotlin.math.roundToInt

private enum class TypeFilter(@StringRes val label: Int, val kind: Kind?) {
  ALL(R.string.stat_type_all, null),
  EVENTS(R.string.stat_events, Kind.EVENT),
  TASKS(R.string.stat_tasks, Kind.TASK),
  OTHER(R.string.stat_other, Kind.NONE),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatisticsScreen(onBack: () -> Unit) {
  // Statistics cover the whole stored history (reloaded when new entries arrive), not only the in-memory list.
  val recent by Ui.app.log.entries.collectAsState()
  val entries by androidx.compose.runtime.produceState(initialValue = recent, recent) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Ui.app.log.allEntries() }
  }

  var timeFilter by rememberSaveable { mutableStateOf(StatPeriod.DAYS_7) }
  var periodOffset by rememberSaveable { mutableIntStateOf(0) }
  var chartType by rememberSaveable { mutableStateOf(ChartType.BAR) }
  var selectedTypes by remember { mutableStateOf(setOf(TypeFilter.ALL)) }

  val window = remember(timeFilter, periodOffset) { StatsWindow.of(timeFilter, periodOffset, LocalDate.now(), ZoneId.systemDefault()) }
  val goBack = { if (StatsWindow.canGoBack(timeFilter)) periodOffset-- }
  val goForward = { if (StatsWindow.canGoForward(timeFilter, periodOffset)) periodOffset++ }

  val filteredEntries = remember(entries, window, selectedTypes) {
    entries.filter { entry ->
      val inTime = entry.created in window
      val inType = when {
        selectedTypes.contains(TypeFilter.ALL) || selectedTypes.isEmpty() -> true
        else -> selectedTypes.any { filter ->
          when (filter.kind) {
            Kind.EVENT -> entry.detection.kind == Kind.EVENT
            Kind.TASK -> entry.detection.kind == Kind.TASK
            Kind.NOTE -> entry.detection.kind == Kind.NOTE
            Kind.NONE -> entry.detection.kind != Kind.EVENT && entry.detection.kind != Kind.TASK
            null -> true
          }
        }
      }
      inTime && inType
    }
  }

  // KPIs
  val totalProcessed = filteredEntries.size
  val createdEvents = filteredEntries.count { it.detection.kind == Kind.EVENT && (it.status == Status.ADDED || it.status == Status.PARTIAL) }
  val createdTasks = filteredEntries.count { it.detection.kind == Kind.TASK && (it.status == Status.ADDED || it.status == Status.PARTIAL) }
  val totalCreated = createdEvents + createdTasks

  // 30 seconds saved per created appointment / task; counts up to the new value within 2 seconds.
  val totalSecondsSaved = totalCreated * 30
  val shownSeconds = remember { Animatable(0f) }
  LaunchedEffect(totalSecondsSaved) {
    shownSeconds.animateTo(totalSecondsSaved.toFloat(), tween(COUNT_UP_MS, easing = FastOutSlowInEasing))
  }
  val formattedTimeSaved = formatSavedTime(shownSeconds.value.roundToInt())

  // AI vs. Rules ratio
  val aiProcessedCount = filteredEntries.count { it.detection.engine.isNotBlank() && it.detection.engine != "rules" }
  val rulesProcessedCount = totalProcessed - aiProcessedCount
  val aiPercent = if (totalProcessed > 0) (aiProcessedCount * 100f / totalProcessed).roundToInt() else 0
  val rulesPercent = if (totalProcessed > 0) 100 - aiPercent else 0

  // Trend & Prognose
  val activeDaysCount = if (timeFilter == StatPeriod.ALL) 30 else timeFilter.days
  val avgPerDay = totalCreated.toFloat() / activeDaysCount
  val projectedNext7Days = (avgPerDay * 7).roundToInt()
  val projectedSecondsSaved = projectedNext7Days * 30

  SubScreen(
    title = stringResource(R.string.statistics_title),
    onBack = onBack,
  ) {
    // SubScreen already scrolls vertically; a second verticalScroll here crashes Compose
    // ("Vertically scrollable component was measured with an infinity maximum height").
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 24.dp)
        .periodSwipe(onBack = goBack, onForward = goForward),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      ChartTypeToggle(chartType, onSelect = { chartType = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner))

      // 1. Time Filter Selector
      Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
      ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(R.string.stat_period),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            StatPeriod.entries.forEach { tf ->
              FilterChip(
                selected = timeFilter == tf,
                onClick = { timeFilter = tf; periodOffset = 0 },
                label = { Text(stringResource(tf.label)) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
              )
            }
          }

          PeriodNavigator(
            label = periodLabel(timeFilter, periodOffset, window),
            canGoBack = StatsWindow.canGoBack(timeFilter),
            canGoForward = StatsWindow.canGoForward(timeFilter, periodOffset),
            onBack = goBack,
            onForward = goForward,
          )

          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

          Text(
            stringResource(R.string.stat_category_filter),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            TypeFilter.entries.forEach { filter ->
              val isSelected = selectedTypes.contains(filter)
              FilterChip(
                selected = isSelected,
                onClick = {
                  selectedTypes = if (filter == TypeFilter.ALL) {
                    setOf(TypeFilter.ALL)
                  } else {
                    val next = selectedTypes.toMutableSet()
                    next.remove(TypeFilter.ALL)
                    if (isSelected) next.remove(filter) else next.add(filter)
                    if (next.isEmpty()) setOf(TypeFilter.ALL) else next
                  }
                },
                label = { Text(stringResource(filter.label)) },
              )
            }
          }
        }
      }

      // 2. Big Highlight Card: Eingesparte Zeit (30 Sek. pro Termin / Task)
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
      ) {
        Row(
          modifier = Modifier.padding(18.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(52.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                Icons.Filled.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp),
              )
            }
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.stat_time_saved),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Text(
              formattedTimeSaved,
              style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
              stringResource(R.string.stat_time_saved_basis),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
          }
        }
      }

      // 3. Grid of Main Metrics (Processed, Events, Tasks)
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        MetricTile(
          title = stringResource(R.string.stat_messages),
          value = "$totalProcessed",
          sub = stringResource(R.string.stat_processed),
          icon = Icons.Filled.Notifications,
          containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
          modifier = Modifier.weight(1f),
        )
        MetricTile(
          title = stringResource(R.string.stat_events),
          value = "$createdEvents",
          sub = stringResource(R.string.stat_created),
          icon = Icons.Filled.CalendarMonth,
          containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
          modifier = Modifier.weight(1f),
        )
        MetricTile(
          title = stringResource(R.string.stat_tasks),
          value = "$createdTasks",
          sub = stringResource(R.string.stat_created),
          icon = Icons.Filled.TaskAlt,
          containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
          modifier = Modifier.weight(1f),
        )
      }

      // 4. KI vs. Algorithmus Ratio Card
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      ) {
        Column(modifier = Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
              Text(stringResource(R.string.stat_ratio_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.stat_total, totalProcessed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }

          // Two-color ratio bar
          Surface(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().height(14.dp),
            color = Color(0xFF9E9E9E).copy(alpha = 0.3f),
          ) {
            Row(modifier = Modifier.fillMaxSize()) {
              if (aiPercent > 0) {
                Box(
                  modifier = Modifier
                    .fillMaxHeight()
                    .weight(aiPercent.toFloat().coerceAtLeast(0.1f))
                    .background(Color(0xFF6750A4)),
                )
              }
              if (rulesPercent > 0) {
                Box(
                  modifier = Modifier
                    .fillMaxHeight()
                    .weight(rulesPercent.toFloat().coerceAtLeast(0.1f))
                    .background(Color(0xFF00796B)),
                )
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Box(modifier = Modifier.size(10.dp).background(Color(0xFF6750A4), CircleShape))
              Text(
                stringResource(R.string.stat_ai_share, aiProcessedCount, aiPercent),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
              )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Box(modifier = Modifier.size(10.dp).background(Color(0xFF00796B), CircleShape))
              Text(
                stringResource(R.string.stat_rules_share, rulesProcessedCount, rulesPercent),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
              )
            }
          }
        }
      }

      // 5. Daily Bar Chart (Activity Visualization)
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      ) {
        Column(modifier = Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(stringResource(R.string.stat_activity), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(periodLabel(timeFilter, periodOffset, window), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
          }

          val counts = remember(filteredEntries, window) {
            window.buckets.map { b ->
              val inBucket = filteredEntries.filter { it.created >= b.start && it.created < b.end }
              BucketCount(inBucket.count { it.detection.kind == Kind.EVENT }, inBucket.count { it.detection.kind == Kind.TASK })
            }
          }
          ActivityChart(counts, bucketLabels(timeFilter, window), chartType, animationKey = window)
        }
      }

      // 6. Distribution by Source (WhatsApp, Telegram, IMAP, etc.)
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      ) {
        Column(modifier = Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.stat_sources), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          SourceBreakdown(filteredEntries)
        }
      }

      // 7. Trend & Prognose Card
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)),
      ) {
        Row(
          modifier = Modifier.padding(Gaps.Inner),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(32.dp))
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.stat_trend), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
              stringResource(R.string.stat_avg_per_day, "%.1f".format(LocalConfiguration.current.locales[0], avgPerDay)),
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(
              stringResource(R.string.stat_forecast, projectedNext7Days, formatSavedTime(projectedSecondsSaved)),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MetricTile(
  title: String,
  value: String,
  sub: String,
  icon: ImageVector,
  containerColor: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = containerColor,
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
      }
      Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SourceBreakdown(entries: List<LogEntry>) {
  val total = entries.size.coerceAtLeast(1)
  val sourceGroups = listOf(
    "WhatsApp" to entries.count { it.source == SourceType.WHATSAPP || it.packageName.contains("whatsapp") || it.appLabel.contains("WhatsApp", ignoreCase = true) },
    "Telegram" to entries.count { it.source == SourceType.TELEGRAM || it.source == SourceType.TELEGRAM_API },
    stringResource(R.string.stat_src_email) to entries.count { it.source == SourceType.IMAP },
    stringResource(R.string.stat_src_notifications) to entries.count { it.source == SourceType.NOTIFICATION },
    stringResource(R.string.stat_src_screens) to entries.count { it.source == SourceType.SCREENSHOT || it.source == SourceType.SHARED_IMAGE },
    stringResource(R.string.stat_src_voice) to entries.count { it.source == SourceType.VOICE_MEMO },
  ).filter { it.second > 0 }

  if (sourceGroups.isEmpty()) {
    Text(stringResource(R.string.stat_no_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  } else {
    sourceGroups.forEach { (name, count) ->
      val fraction = count.toFloat() / total
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
          Text("$count (${(fraction * 100).roundToInt()} %)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
          progress = { fraction },
          modifier = Modifier.fillMaxWidth().height(6.dp),
          strokeCap = StrokeCap.Round,
        )
      }
    }
  }
}

@Composable
private fun formatSavedTime(seconds: Int): String {
  return when {
    seconds < 60 -> stringResource(R.string.stat_time_s, seconds)
    seconds < 3600 -> {
      val mins = seconds / 60
      val secs = seconds % 60
      if (secs == 0) stringResource(R.string.stat_time_m, mins) else stringResource(R.string.stat_time_ms, mins, secs)
    }
    else -> {
      val hours = seconds / 3600
      val mins = (seconds % 3600) / 60
      if (mins == 0) stringResource(R.string.stat_time_h, hours) else stringResource(R.string.stat_time_hm, hours, mins)
    }
  }
}

private const val COUNT_UP_MS = 2000
private const val SWIPE_THRESHOLD_DP = 64

/** Horizontal swipe through the periods: to the right = earlier, to the left = later. */
@Composable
private fun Modifier.periodSwipe(onBack: () -> Unit, onForward: () -> Unit): Modifier {
  val back by rememberUpdatedState(onBack)
  val forward by rememberUpdatedState(onForward)
  return pointerInput(Unit) { detectPeriodSwipe({ back() }, { forward() }) }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPeriodSwipe(onBack: () -> Unit, onForward: () -> Unit) {
  val threshold = SWIPE_THRESHOLD_DP.dp.toPx()
  var dragged = 0f
  detectHorizontalDragGestures(
    onDragStart = { dragged = 0f },
    onDragEnd = {
      when {
        dragged > threshold -> onBack()
        dragged < -threshold -> onForward()
      }
    },
    onHorizontalDrag = { change, dx ->
      change.consume()
      dragged += dx
    },
  )
}

@Composable
private fun ChartTypeToggle(selected: ChartType, onSelect: (ChartType) -> Unit, modifier: Modifier = Modifier) {
  val options = listOf(
    Triple(ChartType.BAR, Icons.Filled.BarChart, R.string.stat_chart_bar),
    Triple(ChartType.LINE, Icons.AutoMirrored.Filled.ShowChart, R.string.stat_chart_line),
  )
  SingleChoiceSegmentedButtonRow(modifier) {
    options.forEachIndexed { i, (type, icon, label) ->
      SegmentedButton(
        selected = selected == type,
        onClick = { onSelect(type) },
        shape = SegmentedButtonDefaults.itemShape(i, options.size),
        icon = { Icon(icon, null, Modifier.size(SegmentedButtonDefaults.IconSize)) },
      ) { Text(stringResource(label)) }
    }
  }
}

@Composable
private fun PeriodNavigator(label: String, canGoBack: Boolean, canGoForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack, enabled = canGoBack) {
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.stat_prev_period))
    }
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    IconButton(onClick = onForward, enabled = canGoForward) {
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.stat_next_period))
    }
  }
}

@Composable
private fun formatter(skeleton: String): DateTimeFormatter {
  val locale = LocalConfiguration.current.locales[0]
  return remember(locale, skeleton) {
    DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
  }
}

private fun DateTimeFormatter.millis(ms: Long): String = format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
private fun periodLabel(period: StatPeriod, offset: Int, window: StatWindow): String {
  val day = formatter("EEEdMMM")
  val range = formatter("dMMM")
  return when {
    period == StatPeriod.ALL -> stringResource(R.string.stat_all_time)
    period == StatPeriod.TODAY && offset == 0 -> stringResource(R.string.stat_today)
    period == StatPeriod.TODAY -> day.millis(window.start)
    else -> range.millis(window.start) + " – " + range.millis(window.end - 1)
  }
}

@Composable
private fun bucketLabels(period: StatPeriod, window: StatWindow): List<String> {
  val f = formatter(
    when (period) {
      StatPeriod.TODAY -> "H"
      StatPeriod.ALL -> "MMM"
      else -> "dM"
    },
  )
  return remember(window, f) { window.buckets.map { f.millis(it.start) } }
}
