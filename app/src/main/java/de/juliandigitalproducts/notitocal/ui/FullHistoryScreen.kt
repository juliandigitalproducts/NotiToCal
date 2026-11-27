// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.runtime.mutableIntStateOf
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Status
import kotlinx.coroutines.launch

private enum class HistoryFilterCategory {
  ALL, EVENTS, TASKS, PENDING, IGNORED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullHistoryScreen(
  onBack: () -> Unit,
  onOpen: (String) -> Unit,
) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val entries by Ui.app.log.entries.collectAsState()
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var filterCategory by rememberSaveable { mutableStateOf(HistoryFilterCategory.ALL) }
  var calendarKey by rememberSaveable { mutableStateOf<String?>(null) }
  val calendarFilter = CalendarFilter.valid(calendarKey, s)
  var confirmClear by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // The complete history comes page by page from the database, not only the newest entries kept in memory.
  val pageSize = 200
  var loaded by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
  var endReached by remember { mutableStateOf(false) }
  var loadingMore by remember { mutableStateOf(false) }
  var total by remember { mutableIntStateOf(0) }
  LaunchedEffect(searchQuery, entries) {
    if (searchQuery.isNotEmpty()) kotlinx.coroutines.delay(250)
    val (first, count) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      Ui.app.log.queryHistory(searchQuery, pageSize, 0) to Ui.app.log.count()
    }
    loaded = first
    endReached = first.size < pageSize
    total = count
  }
  fun loadMore() {
    if (loadingMore || endReached) return
    loadingMore = true
    scope.launch {
      val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        Ui.app.log.queryHistory(searchQuery, pageSize, loaded.size)
      }
      loaded = (loaded + next).distinctBy { it.id }
      endReached = next.size < pageSize
      loadingMore = false
    }
  }

  val filteredEntries = remember(loaded, searchQuery, filterCategory, calendarFilter, s.routingRules) {
    val q = searchQuery.trim().lowercase()
    loaded.filter { e ->
      val matchesCat = when (filterCategory) {
        HistoryFilterCategory.ALL -> true
        HistoryFilterCategory.EVENTS -> e.detection.kind == Kind.EVENT
        HistoryFilterCategory.TASKS -> e.detection.kind == Kind.TASK
        HistoryFilterCategory.PENDING -> e.status == Status.PENDING
        HistoryFilterCategory.IGNORED -> e.status == Status.IGNORED
      }
      if (!matchesCat) return@filter false
      if (!CalendarFilter.matches(e, calendarFilter, s)) return@filter false

      if (q.isEmpty()) return@filter true

      e.sender.lowercase().contains(q) ||
        e.text.lowercase().contains(q) ||
        e.detection.title.lowercase().contains(q) ||
        e.detection.location.orEmpty().lowercase().contains(q) ||
        e.appLabel.lowercase().contains(q)
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.full_history_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
          }
        },
        actions = {
          if (entries.isNotEmpty()) {
            IconButton(onClick = { confirmClear = true }) {
              Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.clear_log))
            }
          }
        },
      )
    },
  ) { pad ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = pad.calculateTopPadding()),
    ) {
      // Search Bar
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text(stringResource(R.string.full_history_search_placeholder), style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Filled.Clear, contentDescription = null)
            }
          }
        },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = Gaps.Inner, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
      )

      // Category Filter Chips
      LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Gaps.Inner, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(HistoryFilterCategory.entries) { cat ->
          val label = when (cat) {
            HistoryFilterCategory.ALL -> stringResource(R.string.filter_all)
            HistoryFilterCategory.EVENTS -> stringResource(R.string.filter_events)
            HistoryFilterCategory.TASKS -> stringResource(R.string.filter_tasks)
            HistoryFilterCategory.PENDING -> stringResource(R.string.filter_pending)
            HistoryFilterCategory.IGNORED -> stringResource(R.string.filter_ignored)
          }
          FilterChip(
            selected = filterCategory == cat,
            onClick = { filterCategory = cat },
            label = { Text(label) },
          )
        }
      }
      CalendarFilterRow(s, calendarFilter) { calendarKey = it }

      // Retention & Count Summary Banner
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = Gaps.Edge, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Info, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
              stringResource(R.string.full_history_retention_info, s.keepLogDays),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
          ) {
            Text(
              text = "${filteredEntries.size} / $total",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
          }
        }
      }

      Spacer(Modifier.height(6.dp))

      // List of Messages
      if (filteredEntries.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              Icons.Filled.History,
              contentDescription = null,
              modifier = Modifier.size(56.dp),
              tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
              stringResource(R.string.full_history_empty),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (searchQuery.isNotEmpty() || filterCategory != HistoryFilterCategory.ALL || calendarFilter != null) {
              TextButton(onClick = { searchQuery = ""; filterCategory = HistoryFilterCategory.ALL; calendarKey = null }) {
                Text(stringResource(R.string.filter_all))
              }
            }
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = Gaps.Edge, end = Gaps.Edge, bottom = 32.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(filteredEntries, key = { it.id }) { entry ->
            FullHistoryItemCard(entry = entry, onClick = { onOpen("entry/${entry.id}") })
          }
          if (!endReached) {
            item(key = "load_more") {
              // Reaching the end of the list loads the next page.
              LaunchedEffect(loaded.size) { loadMore() }
              Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
              }
            }
          }
        }
      }
    }
  }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text(stringResource(R.string.clear_log)) },
      text = { Text(stringResource(R.string.clear_log_body)) },
      confirmButton = {
        TextButton(
          onClick = {
            confirmClear = false
            scope.launch { Ui.app.log.clear() }
          },
        ) {
          Text(stringResource(R.string.delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmClear = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
}

@Composable
private fun FullHistoryItemCard(
  entry: LogEntry,
  onClick: () -> Unit,
) {
  val context = LocalContext.current
  val (statusColor, statusIcon, statusTextRes) = when (entry.status) {
    Status.ADDED -> Triple(Color(0xFF2E7D32), Icons.Filled.CheckCircle, R.string.status_added)
    Status.PENDING -> Triple(Color(0xFFEF6C00), Icons.Filled.HourglassEmpty, R.string.status_pending)
    Status.PARTIAL -> Triple(Color(0xFF00897B), Icons.Filled.CheckCircle, R.string.status_partial)
    Status.FAILED -> Triple(MaterialTheme.colorScheme.error, Icons.Filled.Warning, R.string.status_failed)
    Status.IGNORED, Status.DISMISSED -> Triple(MaterialTheme.colorScheme.outline, Icons.AutoMirrored.Filled.Notes, R.string.status_ignored)
  }

  // Format sender: clean counters and cleanly format "Group › Person"
  val formattedSender = remember(entry.sender) {
    var raw = entry.sender.trim()
    raw = raw.replace(Regex("""\s*\((?:\d+\s*(?:neue\s+)?(?:nachrichten?|messages?|msgs?|ungelesen|new)|[\+\d]+)\)""", RegexOption.IGNORE_CASE), "")
    raw = raw.replace(Regex("""\s*:\s*\d+\s*(?:neue\s+)?(?:nachrichten?|messages?)\s*$""", RegexOption.IGNORE_CASE), "")
    if (raw.contains(" @ ")) {
      val person = raw.substringBefore(" @ ").trim()
      val group = raw.substringAfter(" @ ").trim()
      if (group.isNotBlank() && person.isNotBlank()) "$group › $person" else raw
    } else {
      raw
    }
  }

  val relativeTime = remember(entry.created) {
    DateUtils.getRelativeTimeSpanString(
      entry.created,
      System.currentTimeMillis(),
      DateUtils.MINUTE_IN_MILLIS,
      DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      // Header: App / Sender & Status Badge
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f).padding(end = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(24.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              val icon: ImageVector = when (entry.detection.kind) {
                Kind.EVENT -> Icons.Filled.Event
                Kind.TASK -> Icons.Filled.TaskAlt
                else -> Icons.AutoMirrored.Filled.Notes
              }
              Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
          }
          Column {
            Text(
              text = formattedSender.ifBlank { entry.appLabel.ifBlank { entry.source.name } },
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (entry.appLabel.isNotBlank() && formattedSender.isNotBlank()) {
              Text(
                text = "${entry.appLabel} · $relativeTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        // Status Badge
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = statusColor.copy(alpha = 0.15f),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(statusIcon, null, modifier = Modifier.size(12.dp), tint = statusColor)
            Text(
              text = stringResource(statusTextRes),
              style = MaterialTheme.typography.labelSmall,
              color = statusColor,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }

      // Title & Location
      if (entry.detection.title.isNotBlank()) {
        Text(
          text = entry.detection.title,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      entry.detection.location?.takeIf { it.isNotBlank() }?.let { loc ->
        Text(
          text = "📍 $loc",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      // Original Message snippet
      if (entry.text.isNotBlank()) {
        Text(
          text = entry.text,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
