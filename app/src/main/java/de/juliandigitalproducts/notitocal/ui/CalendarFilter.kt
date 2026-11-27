// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.output.Targets
import de.juliandigitalproducts.notitocal.pipeline.Router

/**
 * Filter by calendar, following the routing rules: every enabled rule is one calendar, plus the default calendar for
 * entries no rule matched. Null key = all calendars.
 */
object CalendarFilter {
  const val DEFAULT = "__default__"

  fun matches(e: LogEntry, key: String?, s: AppSettings): Boolean {
    if (key == null) return true
    val rule = Router.rule(s, e.detection)
    return if (key == DEFAULT) rule == null else rule?.id == key
  }

  /** The selected key, or null when its rule was deleted or disabled meanwhile. */
  fun valid(key: String?, s: AppSettings): String? =
    key?.takeIf { it == DEFAULT || s.routingRules.any { r -> r.id == it && r.enabled } }
}

/** Chips "All calendars · <rule> … · Default calendar". Hidden while no routing rule exists. */
@Composable
fun CalendarFilterRow(s: AppSettings, selected: String?, onSelect: (String?) -> Unit) {
  val rules = s.routingRules.filter { it.enabled }
  if (rules.isEmpty()) return
  val context = LocalContext.current
  val calendarNames = remember(rules) {
    if (rules.any { it.calendarId >= 0 }) Targets.calendars(context).associate { it.id to it.name } else emptyMap()
  }
  val options: List<Pair<String?, String>> = buildList {
    add(null to stringResource(R.string.filter_all_calendars))
    rules.forEach { r ->
      val calendar = calendarNames[r.calendarId]
      val name = r.name.ifBlank { stringResource(R.string.filter_unnamed_rule) }
      add(r.id to if (calendar != null && !calendar.equals(name, ignoreCase = true)) "$name · $calendar" else name)
    }
    add(CalendarFilter.DEFAULT to stringResource(R.string.filter_default_calendar))
  }
  LazyRow(
    modifier = Modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = Gaps.Inner, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(options, key = { it.first ?: "__all__" }) { (key, label) ->
      FilterChip(
        selected = selected == key,
        onClick = { onSelect(key) },
        label = { Text(label) },
        leadingIcon = if (key == null) ({ Icon(Icons.Filled.CalendarMonth, contentDescription = null) }) else null,
      )
    }
  }
}
