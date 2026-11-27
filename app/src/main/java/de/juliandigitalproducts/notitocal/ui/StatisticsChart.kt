// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import kotlin.math.ceil

/** Events and tasks counted in one chart column. */
internal data class BucketCount(val events: Int, val tasks: Int) {
  val total get() = events + tasks
}

private const val REVEAL_MS = 700
private const val MAX_LABELS = 8
private const val MIN_SCALE = 4

/**
 * Activity chart as stacked bars or as two lines (events, tasks). It grows in whenever [animationKey] or the chart
 * type changes (opening the screen, another period); the animation lives in a LaunchedEffect, so it stops with the screen.
 */
@Composable
internal fun ActivityChart(counts: List<BucketCount>, labels: List<String>, type: ChartType, animationKey: Any) {
  val reveal = remember { Animatable(0f) }
  LaunchedEffect(animationKey, type) {
    reveal.snapTo(0f)
    reveal.animateTo(1f, tween(REVEAL_MS, easing = FastOutSlowInEasing))
  }
  val eventColor = MaterialTheme.colorScheme.primary
  val taskColor = MaterialTheme.colorScheme.tertiary
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  val eventsLabel = stringResource(R.string.stat_events)
  val tasksLabel = stringResource(R.string.stat_tasks)
  val description = "$eventsLabel: ${counts.sumOf { it.events }} · $tasksLabel: ${counts.sumOf { it.tasks }}"
  val scale = (counts.maxOfOrNull { it.total } ?: 0).coerceAtLeast(MIN_SCALE).toFloat()

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description }) {
      if (counts.isEmpty()) return@Canvas
      drawGrid(gridColor)
      val progress = reveal.value
      when (type) {
        ChartType.BAR -> drawBars(counts, scale, progress, eventColor, taskColor)
        ChartType.LINE -> clipRect(right = size.width * progress) {
          drawSeries(counts.map { it.events }, scale, eventColor)
          drawSeries(counts.map { it.tasks }, scale, taskColor)
        }
      }
    }
    val step = ceil(labels.size / MAX_LABELS.toFloat()).toInt().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth()) {
      labels.forEachIndexed { i, label ->
        Text(
          if (i % step == 0) label else "",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          maxLines = 1,
          softWrap = false,
          modifier = Modifier.weight(1f),
        )
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
      LegendDot(eventColor, eventsLabel)
      Spacer(Modifier.width(16.dp))
      LegendDot(taskColor, tasksLabel)
    }
  }
}

@Composable
private fun LegendDot(color: Color, label: String) {
  Box(Modifier.size(8.dp).background(color, CircleShape))
  Spacer(Modifier.width(4.dp))
  Text(label, style = MaterialTheme.typography.labelSmall)
}

private fun DrawScope.drawGrid(color: Color) {
  listOf(0f, 0.5f, 1f).forEach { f ->
    val y = size.height * f
    drawLine(color.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
  }
}

private fun DrawScope.drawBars(counts: List<BucketCount>, scale: Float, progress: Float, eventColor: Color, taskColor: Color) {
  val slot = size.width / counts.size
  val barWidth = (slot * 0.6f).coerceAtMost(18.dp.toPx())
  val radius = CornerRadius(3.dp.toPx())
  counts.forEachIndexed { i, c ->
    val x = slot * i + (slot - barWidth) / 2
    val eventHeight = size.height * c.events / scale * progress
    val taskHeight = size.height * c.tasks / scale * progress
    if (eventHeight > 0f) drawRoundRect(eventColor, Offset(x, size.height - eventHeight), Size(barWidth, eventHeight), radius)
    if (taskHeight > 0f) drawRoundRect(taskColor, Offset(x, size.height - eventHeight - taskHeight), Size(barWidth, taskHeight), radius)
  }
}

private fun DrawScope.drawSeries(values: List<Int>, scale: Float, color: Color) {
  val slot = size.width / values.size
  val points = values.mapIndexed { i, v -> Offset(slot * i + slot / 2, size.height - size.height * v / scale) }
  val path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
  }
  drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
  points.forEach { drawCircle(color, radius = 3.dp.toPx(), center = it) }
}
