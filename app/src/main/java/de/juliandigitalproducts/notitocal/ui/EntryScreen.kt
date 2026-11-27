// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.core.graphics.scale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Why
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.notify.Notifier
import de.juliandigitalproducts.notitocal.output.Targets
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val ISO_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(id: Long, onBack: () -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val entries by Ui.app.log.entries.collectAsState()
  val fallback = remember(id) { Ui.app.log.get(id) }
  val entry = entries.firstOrNull { it.id == id } ?: fallback
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var backClicked by remember { mutableStateOf(false) }
  val safeBack = { if (!backClicked) { backClicked = true; onBack() } }

  if (entry == null) {
    Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = { IconButton(onClick = safeBack, enabled = !backClicked) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
      Text(stringResource(R.string.entry_missing), Modifier.padding(pad).padding(Gaps.Inner))
    }
    return
  }

  var d by remember(entry.id) {
    mutableStateOf(entry.detection.let { if (it.kind == Kind.NONE || it.kind == Kind.NOTE) it.copy(kind = Kind.TASK, title = it.title.ifBlank { entry.text.take(60) }) else it })
  }
  var confirmDelete by remember { mutableStateOf(false) }
  var picker by remember { mutableStateOf<String?>(null) }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(if (d.kind == Kind.EVENT) R.string.entry_event else R.string.entry_task)) },
        navigationIcon = { IconButton(onClick = safeBack, enabled = !backClicked) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        actions = {
          IconButton(onClick = { context.startActivity(Targets.shareIntent(context, d, Ui.app.settings.current)) }) {
            Icon(Icons.Filled.Share, stringResource(R.string.target_share))
          }
          IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
        },
      )
    },
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
      val why = explainWhy(context, entry.detection)
      when {
        // "Test text": show what the current settings would do with it as a real notification.
        entry.source == SourceType.MANUAL && entry.detection.realDecision != null && entry.status in setOf(Status.PENDING, Status.IGNORED) ->
          InfoCard(stringResource(R.string.test_title), explainTest(context, entry.detection), icon = Icons.Filled.Science)
        entry.status == Status.ADDED || entry.status == Status.PARTIAL ->
          if (entry.detection.why == Why.AUTO.name && why != null) Hint(why)
        entry.status == Status.FAILED -> InfoCard(stringResource(R.string.real_failed), entry.result.ifBlank { "–" })
        entry.status == Status.IGNORED || entry.detection.kind == Kind.NONE ->
          InfoCard(stringResource(R.string.entry_ignored_title), why ?: stringResource(R.string.entry_ignored_body, entry.detection.reason.ifBlank { "–" }))
        // Discarded by the user (possibly after it had been added automatically): say so, then why it was found.
        entry.status == Status.DISMISSED ->
          InfoCard(stringResource(R.string.entry_dismissed_title), listOfNotNull(stringResource(R.string.entry_dismissed_body), why).joinToString("\n\n"))
        entry.status == Status.PENDING ->
          InfoCard(stringResource(R.string.entry_not_auto_title), why ?: stringResource(R.string.entry_not_auto_body))
      }
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(Gaps.Inner)) {
        listOf(Kind.EVENT, Kind.TASK).forEachIndexed { i, k ->
          SegmentedButton(
            selected = d.kind == k,
            onClick = {
              d = if (k == Kind.TASK) d.copy(kind = k, due = d.due ?: d.start, start = null, end = null)
              else d.copy(kind = k, start = d.start ?: d.due ?: LocalDate.now().plusDays(1).atTime(9, 0).format(ISO_MIN), due = null)
            },
            shape = SegmentedButtonDefaults.itemShape(i, 2),
          ) { Text(stringResource(if (k == Kind.EVENT) R.string.kind_event else R.string.kind_task)) }
        }
      }
      SettingText(stringResource(R.string.field_title), d.title) { d = d.copy(title = it) }

      if (d.kind == Kind.EVENT) {
        val start = d.startDateTime() ?: LocalDate.now().atTime(9, 0)
        val end = d.endDateTime()
        val timed = d.start?.contains('T') == true && !d.allDay
        SettingSwitch(stringResource(R.string.all_day), !timed) { allDay ->
          d = if (allDay) d.copy(allDay = true, start = start.toLocalDate().toString(), end = d.endDateTime()?.toLocalDate()?.toString())
          else d.copy(allDay = false, start = start.toLocalDate().atTime(9, 0).format(ISO_MIN), end = (d.endDateTime() ?: start.plusHours(1)).format(ISO_MIN))
        }
        FlowRow(Modifier.padding(horizontal = Gaps.Inner), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { picker = "start_date" }) {
            Text(start.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
          }
          if (timed) {
            OutlinedButton(onClick = { picker = "start_time" }) { Text(start.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
            Text("–", Modifier.align(Alignment.CenterVertically))
            OutlinedButton(onClick = { picker = "end_time" }) {
              Text((end ?: start.plusHours(1)).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
            }
          }
          OutlinedButton(onClick = { picker = "end_date" }) {
            val ed = end?.toLocalDate() ?: start.toLocalDate()
            Text(
              if (ed != start.toLocalDate()) stringResource(R.string.end_date) + ": " + ed.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
              else stringResource(R.string.multiday_event)
            )
          }
        }
        SettingText(stringResource(R.string.field_location), d.location.orEmpty()) { d = d.copy(location = it.ifBlank { null }) }
      } else {
        val due = d.dueDateTime()
        FlowRow(Modifier.padding(horizontal = Gaps.Inner), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { picker = "due_date" }) {
            Text(due?.toLocalDate()?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: stringResource(R.string.no_due_date))
          }
          if (due != null) {
            OutlinedButton(onClick = { picker = "due_time" }) {
              Text(if (d.due?.contains('T') == true) due.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) else stringResource(R.string.add_time))
            }
            TextButton(onClick = { d = d.copy(due = null) }) { Text(stringResource(R.string.remove)) }
          }
        }
        SettingDropdown(stringResource(R.string.field_priority), listOf(0, 1, 2, 3), d.priority, { p ->
          resources.getString(when (p) { 3 -> R.string.priority_high; 2 -> R.string.priority_medium; 1 -> R.string.priority_low; else -> R.string.priority_none })
        }) { d = d.copy(priority = it) }
      }
      val rules = Ui.app.settings.state.collectAsState().value.routingRules.filter { it.enabled }
      if (rules.isNotEmpty()) {
        val options = listOf<String?>(null) + rules.map { it.id }
        SettingDropdown(stringResource(R.string.routing_rule), options, d.route.takeIf { it in options }, { id ->
          rules.firstOrNull { it.id == id }?.name?.ifBlank { resources.getString(R.string.routing_unnamed) } ?: resources.getString(R.string.routing_default)
        }) { d = d.copy(route = it) }
      }
      SettingText(stringResource(R.string.field_description), d.description, singleLine = false) { d = d.copy(description = it) }

      Row(Modifier.fillMaxWidth().padding(Gaps.Inner), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          modifier = Modifier.weight(1f),
          enabled = d.title.isNotBlank() && (d.kind == Kind.TASK || d.start != null),
          onClick = {
            scope.launch {
              Ui.app.log.update(entry.id, d = d)
              Notifier.cancel(context, entry.id)
              val res = Ui.app.pipeline.deliverEntry(entry, d, fromUi = true)
              val msg = when {
                res.isEmpty() -> resources.getString(R.string.no_target_configured)
                res.all { it.ok } -> resources.getString(R.string.added_to, res.joinToString { Notifier.targetLabel(context, it.target) })
                else -> res.filter { !it.ok }.joinToString("\n") { Notifier.targetLabel(context, it.target) + ": " + it.message }
              }
              snackbar.showSnackbar(msg)
            }
          },
        ) { Text(stringResource(if (entry.status == Status.ADDED) R.string.add_again else R.string.action_add)) }
        OutlinedButton(
          modifier = Modifier.weight(1f),
          onClick = {
            Ui.app.scope.launch { Ui.app.log.update(entry.id, status = Status.DISMISSED) }
            Notifier.cancel(context, entry.id)
            onBack()
          },
        ) { Text(stringResource(R.string.action_dismiss)) }
      }
      var isCheckingAi by remember { mutableStateOf(false) }
      var isAiFinished by remember { mutableStateOf(false) }
      if (isCheckingAi) {
        AiProgressDialog(
          title = stringResource(R.string.recheck_single_ai_btn),
          isFinished = isAiFinished,
          onDismiss = {
            isCheckingAi = false
            isAiFinished = false
          },
          onCancel = {
            isCheckingAi = false
            isAiFinished = false
          },
        )
      }
      Row(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 4.dp)) {
        OutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          enabled = !isCheckingAi && entry.text.isNotBlank(),
          onClick = {
            // Engine "Rules" without an on-device model: no AI to check with – a hint instead of the rules' result.
            if (!de.juliandigitalproducts.notitocal.analysis.Analyzer.canCheckWithAi(Ui.app, Ui.app.settings.current)) {
              scope.launch { snackbar.showSnackbar(resources.getString(R.string.recheck_no_ai)) }
              return@OutlinedButton
            }
            isCheckingAi = true
            isAiFinished = false
            scope.launch {
              try {
                val res = runSafely {
                  Ui.app.analyzer.analyzeForcedAi(
                    Captured(entry.source, entry.packageName, entry.appLabel, entry.sender, entry.text),
                    Ui.app.settings.current,
                  )
                }.getOrNull()
                if (res != null) {
                  val updated = if (res.kind == Kind.NONE || res.kind == Kind.NOTE) {
                    res.copy(kind = d.kind, title = res.title.ifBlank { d.title })
                  } else res
                  d = updated
                  Ui.app.log.update(entry.id, d = updated)
                  snackbar.showSnackbar(resources.getString(R.string.recheck_all_ai_done, 1))
                }
                isAiFinished = true
                kotlinx.coroutines.delay(450)
              } finally {
                isCheckingAi = false
                isAiFinished = false
              }
            }
          },
        ) {
          Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.recheck_single_ai_btn))
        }
      }
      val isTest = entry.appLabel == stringResource(R.string.diagnose_test_label) || entry.appLabel == "Diagnose" || entry.text.contains("Appointment tomorrow at 15:00")
      if (d.kind == Kind.EVENT && !isTest) TextButton(
        onClick = { Targets.calendarIntent(d, Ui.app.settings.current)?.let { context.startActivity(it) } },
        modifier = Modifier.padding(horizontal = 8.dp),
      ) { Text(stringResource(R.string.open_in_calendar_app)) }

      SectionHeader(stringResource(R.string.section_source))
      if (isTest) {
        Card(
          Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 4.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Filled.Science, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
              Text(stringResource(R.string.diagnose_test_banner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Button(
              onClick = { Targets.calendarIntent(d, Ui.app.settings.current)?.let { context.startActivity(it) } },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.self_test_add_to_calendar_btn))
            }
          }
        }
      }
      Card(Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge)) {
        Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(listOf(entry.appLabel, entry.sender).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
              if (entry.packageName.isNotBlank()) {
                Text(entry.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
            if (entry.packageName.isNotBlank()) {
              val launchIntent = remember(entry.packageName) { context.packageManager.getLaunchIntentForPackage(entry.packageName) }
              if (launchIntent != null) {
                OutlinedButton(onClick = { context.startActivity(launchIntent) }) {
                  Icon(Icons.Filled.OpenInNew, null, Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text(stringResource(R.string.open_source_app))
                }
              }
            }
          }
          Text(entry.text.ifBlank { stringResource(R.string.text_not_stored) }, style = MaterialTheme.typography.bodyMedium)

          val screenshotFile = remember(entry.id) {
            java.io.File(context.filesDir, "screenshots/screenshot_${entry.id}.jpg").takeIf { it.exists() }
          }
          if (screenshotFile != null) {
            var showLightbox by remember { mutableStateOf(false) }
            // Decoded off the main thread and subsampled, instead of a full-size bitmap during composition.
            val thumbBitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, screenshotFile) {
              value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runSafely { decodeThumbnail(screenshotFile.absolutePath, 320) }.getOrNull()
              }
            }

            val thumb = thumbBitmap
            if (thumb != null) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                  .clickable { showLightbox = true }
                  .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Box(
                  modifier = Modifier
                    .size(width = 80.dp, height = 80.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                  contentAlignment = Alignment.Center,
                ) {
                  Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = stringResource(R.string.source_screenshot),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                  )
                  Box(
                    modifier = Modifier
                      .fillMaxSize()
                      .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                  ) {
                    Icon(
                      Icons.Filled.ZoomIn,
                      contentDescription = null,
                      tint = Color.White,
                      modifier = Modifier.size(24.dp),
                    )
                  }
                }
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    stringResource(R.string.screenshot_preview_title),
                    style = MaterialTheme.typography.titleSmall,
                  )
                  Text(
                    stringResource(R.string.screenshot_preview_tap),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }

            if (showLightbox) {
              ScreenshotLightboxDialog(
                file = screenshotFile,
                onDismiss = { showLightbox = false },
              )
            }
          }

          HorizontalDivider()

          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              SuggestionChip(
                onClick = {},
                label = { Text(stringResource(R.string.engine_value, entry.detection.engine.ifBlank { stringResource(R.string.engine_rules) })) },
                icon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp)) },
              )
              SuggestionChip(
                onClick = {},
                label = { Text(stringResource(R.string.confidence_value, (entry.detection.confidence * 100).roundToInt())) },
                icon = { Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp)) },
              )
              if (entry.result.isNotBlank()) {
                SuggestionChip(
                  onClick = {},
                  label = { Text(entry.result) },
                  icon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)) },
                )
              }
            }
            if (entry.detection.reason.isNotBlank()) {
              Text(entry.detection.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }

          val raw = entry.detection.rawAnswer?.trim()
          if (Ui.app.settings.current.advancedMode && !raw.isNullOrBlank() && raw != "{\"kind\":\"\"}" && raw != "{}") {
            Text(stringResource(R.string.raw_answer_label) + "\n" + raw, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          StatusBadge(entry.status)
        }
      }
    }
  }

  when (picker) {
    "start_date", "end_date", "due_date" -> {
      val current = when (picker) {
        "start_date" -> d.startDateTime()?.toLocalDate()
        "end_date" -> d.endDateTime()?.toLocalDate() ?: d.startDateTime()?.toLocalDate()
        else -> d.dueDateTime()?.toLocalDate()
      } ?: LocalDate.now()
      val state = rememberDatePickerState(initialSelectedDateMillis = current.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
      DatePickerDialog(
        onDismissRequest = { picker = null },
        confirmButton = {
          TextButton(onClick = {
            state.selectedDateMillis?.let { ms ->
              val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
              d = when (picker) {
                "start_date" -> d.withStartDate(date)
                "end_date" -> {
                  val timed = d.start?.contains('T') == true && !d.allDay
                  val currentEndTime = d.endDateTime()?.toLocalTime() ?: LocalTime.of(18, 0)
                  if (timed) d.copy(end = date.atTime(currentEndTime).format(ISO_MIN))
                  else d.copy(end = date.toString())
                }
                else -> d.copy(due = d.dueDateTime()?.takeIf { d.due?.contains('T') == true }?.let { date.atTime(it.toLocalTime()).format(ISO_MIN) } ?: date.toString())
              }
            }
            picker = null
          }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.cancel)) } },
      ) { DatePicker(state) }
    }
    "start_time", "end_time", "due_time" -> {
      val current: LocalTime = when (picker) {
        "start_time" -> d.startDateTime()?.toLocalTime()
        "end_time" -> d.endDateTime()?.toLocalTime() ?: d.startDateTime()?.toLocalTime()?.plusHours(1)
        else -> d.dueDateTime()?.toLocalTime()?.takeIf { d.due?.contains('T') == true }
      } ?: LocalTime.of(9, 0)
      val state = rememberTimePickerState(current.hour, current.minute, android.text.format.DateFormat.is24HourFormat(context))
      AlertDialog(
        onDismissRequest = { picker = null },
        confirmButton = {
          TextButton(onClick = {
            val t = LocalTime.of(state.hour, state.minute)
            d = when (picker) {
              "start_time" -> d.withStartTime(t)
              "end_time" -> d.startDateTime()?.let { s -> d.copy(end = s.toLocalDate().atTime(t).let { e -> if (e.isAfter(s)) e else e.plusDays(1) }.format(ISO_MIN)) } ?: d
              else -> d.copy(due = ((d.dueDateTime()?.toLocalDate() ?: LocalDate.now()).atTime(t)).format(ISO_MIN))
            }
            picker = null
          }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.cancel)) } },
        text = { TimePicker(state) },
      )
    }
  }

  if (confirmDelete) AlertDialog(
    onDismissRequest = { confirmDelete = false },
    title = { Text(stringResource(R.string.delete_entry)) },
    text = { Text(stringResource(R.string.delete_entry_body)) },
    confirmButton = { TextButton(onClick = { Ui.app.scope.launch { Ui.app.log.delete(entry.id) }; confirmDelete = false; onBack() }) { Text(stringResource(R.string.delete)) } },
    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
  )
}

private fun Detection.withStartDate(date: LocalDate): Detection {
  val s = startDateTime()
  val timed = start?.contains('T') == true && !allDay
  if (!timed || s == null) return copy(start = date.toString(), end = null)
  val duration = endDateTime()?.let { java.time.Duration.between(s, it) } ?: java.time.Duration.ofHours(1)
  val ns = date.atTime(s.toLocalTime())
  return copy(start = ns.format(ISO_MIN), end = ns.plus(duration).format(ISO_MIN))
}

private fun Detection.withStartTime(t: LocalTime): Detection {
  val s = startDateTime() ?: LocalDateTime.now()
  val duration = endDateTime()?.let { java.time.Duration.between(s, it) } ?: java.time.Duration.ofHours(1)
  val ns = s.toLocalDate().atTime(t)
  return copy(start = ns.format(ISO_MIN), end = ns.plus(duration).format(ISO_MIN), allDay = false)
}

@Composable
fun ScreenshotLightboxDialog(
  file: java.io.File,
  onDismiss: () -> Unit,
) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offsetX by remember { mutableFloatStateOf(0f) }
  var offsetY by remember { mutableFloatStateOf(0f) }

  // Screen-sized (max. 2048 px), decoded off the main thread.
  val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, file) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runSafely { decodeThumbnail(file.absolutePath, 2048) }.getOrNull()
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.94f)),
    ) {
      val shown = bitmap
      if (shown != null) {
        Image(
          bitmap = shown.asImageBitmap(),
          contentDescription = stringResource(R.string.source_screenshot),
          modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
              detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                if (scale > 1f) {
                  offsetX += pan.x
                  offsetY += pan.y
                } else {
                  offsetX = 0f
                  offsetY = 0f
                }
              }
            }
            .graphicsLayer(
              scaleX = scale,
              scaleY = scale,
              translationX = offsetX,
              translationY = offsetY,
            ),
          contentScale = ContentScale.Fit,
        )
      }

      IconButton(
        onClick = onDismiss,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(Gaps.Inner)
          .background(Color.Black.copy(alpha = 0.6f), CircleShape),
      ) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
      }

      if (scale > 1.05f) {
        FilledTonalButton(
          onClick = {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
          },
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(24.dp),
        ) {
          Text(stringResource(R.string.reset_zoom))
        }
      }
    }
  }
}

/** Decodes an image file with inSampleSize so that it is not loaded in full size, then scales it to [maxDim]. */
internal fun decodeThumbnail(path: String, maxDim: Int): android.graphics.Bitmap? {
  val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
  android.graphics.BitmapFactory.decodeFile(path, bounds)
  val max = maxOf(bounds.outWidth, bounds.outHeight)
  if (max <= 0) return null
  var sample = 1
  while (max / (sample * 2) >= maxDim) sample *= 2
  val decoded = android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    ?: return null
  val m = maxOf(decoded.width, decoded.height)
  if (m <= maxDim) return decoded
  val scale = maxDim.toFloat() / m
  val scaled = decoded.scale((decoded.width * scale).toInt(), (decoded.height * scale).toInt())
  if (scaled !== decoded) decoded.recycle()
  return scaled
}
