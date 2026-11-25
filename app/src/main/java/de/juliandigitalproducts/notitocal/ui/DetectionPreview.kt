package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** "Donnerstag, 12. November 2026 · 19:00–20:00 Uhr" for an appointment (or the due date of a task). */
fun whenLabel(d: Detection, locale: Locale = Locale.getDefault()): String? {
  val start = if (d.kind == Kind.TASK) d.dueDateTime() else d.startDateTime()
  start ?: return null
  val day = start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
  val timed = (if (d.kind == Kind.TASK) d.due else d.start)?.contains('T') == true && !d.allDay
  if (!timed) return day
  val time = DateTimeFormatter.ofPattern("HH:mm", locale)
  val end: LocalDateTime? = if (d.kind == Kind.EVENT) d.endDateTime() else null
  val span = if (end != null && end.isAfter(start)) "${start.format(time)}–${end.format(time)}" else start.format(time)
  return "$day · $span" + if (locale.language == "de") " Uhr" else ""
}

/** A found appointment or task shown the way it lands in the calendar: title, time, place, notes. */
@Composable
fun DetectionPreviewCard(d: Detection, modifier: Modifier = Modifier) {
  val isTask = d.kind == Kind.TASK
  Card(
    modifier = modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
  ) {
    Row(Modifier.padding(Gaps.Inner), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      // The coloured bar of a calendar entry.
      Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(3.dp),
        modifier = Modifier.size(width = 5.dp, height = 56.dp),
      ) {}
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                if (isTask) Icons.Filled.CheckCircle else Icons.Filled.CalendarMonth, null,
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
          Text(
            stringResource(if (isTask) R.string.kind_task else R.string.kind_event),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.weight(1f))
          if (d.confidence > 0) {
            Text(
              stringResource(R.string.preview_confidence, (d.confidence * 100).roundToInt()),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Text(
          d.title.ifBlank { stringResource(R.string.nothing_found) },
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        PreviewLine(Icons.Filled.Schedule, whenLabel(d) ?: "–")
        PreviewLine(Icons.Filled.Place, d.location?.takeIf { it.isNotBlank() } ?: stringResource(R.string.preview_no_place))
        d.description.takeIf { it.isNotBlank() && it != d.title }?.let { PreviewLine(Icons.AutoMirrored.Filled.Notes, it, maxLines = 3) }
      }
    }
  }
}

@Composable
private fun PreviewLine(icon: ImageVector, text: String, maxLines: Int = 2) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
    Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
  }
}
