// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.ActionMode
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.Strictness
import kotlin.math.roundToInt

// ------------------------------------------------------------------ Strictness

@Composable
fun StrictnessScreen(onBack: () -> Unit) {
  val s by Ui.app.settings.state.collectAsState()
  SubScreen(stringResource(R.string.section_strictness), onBack) {
    StrictnessSimple()
    if (s.advancedMode) StrictnessAdvanced() else Hint(stringResource(R.string.advanced_hint))
  }
}

@Composable
fun StrictnessSimple() {
  val s by Ui.app.settings.state.collectAsState()
  val presets = listOf(Strictness.RELAXED, Strictness.BALANCED, Strictness.STRICT)

  SettingGroupCard(title = stringResource(R.string.section_strictness), emphasized = true) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
      presets.forEachIndexed { i, p ->
        SegmentedButton(selected = s.strictness == p, onClick = { Ui.update { it.withPreset(p) } }, shape = SegmentedButtonDefaults.itemShape(i, presets.size)) {
          Text(strictnessLabel(p))
        }
      }
    }
    Hint(stringResource(when (s.strictness) {
      Strictness.RELAXED -> R.string.strict_relaxed_body
      Strictness.BALANCED -> R.string.strict_balanced_body
      Strictness.STRICT -> R.string.strict_strict_body
      Strictness.CUSTOM -> R.string.strict_custom_body
    }))
    HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
    SettingSwitch(stringResource(R.string.auto_add), s.eventMode == ActionMode.AUTO || s.taskMode == ActionMode.AUTO, stringResource(R.string.auto_add_body)) { v ->
      val m = if (v) ActionMode.AUTO else ActionMode.ASK
      Ui.update {
        it.copy(
          eventMode = if (it.eventMode == ActionMode.OFF) ActionMode.OFF else m, taskMode = if (it.taskMode == ActionMode.OFF) ActionMode.OFF else m,
          autoAddUnsure = it.autoAddUnsure && v,
        )
      }
    }
    val autoOn = s.eventMode == ActionMode.AUTO || s.taskMode == ActionMode.AUTO
    SettingSwitch(stringResource(R.string.auto_add_unsure), s.autoAddUnsure && autoOn, stringResource(R.string.auto_add_unsure_body), enabled = autoOn) { v ->
      Ui.update { it.copy(autoAddUnsure = v) }
    }
    HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
    SettingSwitch(stringResource(R.string.detect_events), s.eventMode != ActionMode.OFF) { v ->
      Ui.update { it.copy(eventMode = if (!v) ActionMode.OFF else if (it.taskMode == ActionMode.AUTO) ActionMode.AUTO else ActionMode.ASK) }
    }
    SettingSwitch(stringResource(R.string.detect_tasks), s.taskMode != ActionMode.OFF) { v ->
      Ui.update { it.copy(taskMode = if (!v) ActionMode.OFF else if (it.eventMode == ActionMode.AUTO) ActionMode.AUTO else ActionMode.ASK) }
    }
    HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
    SettingSwitch(stringResource(R.string.merge_duplicates), s.mergeDuplicateEvents, stringResource(R.string.merge_duplicates_body)) { v ->
      Ui.update { it.copy(mergeDuplicateEvents = v) }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrictnessAdvanced() {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  var pickQuiet by remember { mutableStateOf<Boolean?>(null) }
  fun custom(t: (AppSettings) -> AppSettings) = Ui.update { t(it).copy(strictness = Strictness.CUSTOM) }
  val modeLabel = { m: ActionMode -> resources.getString(when (m) { ActionMode.AUTO -> R.string.mode_auto; ActionMode.ASK -> R.string.mode_ask; ActionMode.OFF -> R.string.off }) }

  SettingGroupCard(title = stringResource(R.string.thresholds)) {
    SettingSlider(stringResource(R.string.event_min_conf), s.eventMinConfidence.toFloat(), 0.1f..0.99f, subtitle = stringResource(R.string.event_min_conf_body)) { v -> custom { it.copy(eventMinConfidence = v.toDouble()) } }
    SettingSlider(stringResource(R.string.task_min_conf), s.taskMinConfidence.toFloat(), 0.1f..0.99f, subtitle = stringResource(R.string.task_min_conf_body)) { v -> custom { it.copy(taskMinConfidence = v.toDouble()) } }
    SettingSlider(stringResource(R.string.auto_conf), s.autoAddConfidence.toFloat(), 0.3f..1f, subtitle = stringResource(R.string.auto_conf_body)) { v -> custom { it.copy(autoAddConfidence = v.toDouble()) } }
    HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
    SettingDropdown(stringResource(R.string.event_mode), ActionMode.entries, s.eventMode, modeLabel) { v -> custom { it.copy(eventMode = v) } }
    SettingDropdown(stringResource(R.string.task_mode), ActionMode.entries, s.taskMode, modeLabel) { v -> custom { it.copy(taskMode = v) } }
  }

  SettingGroupCard(title = stringResource(R.string.rules)) {
    SettingSwitch(stringResource(R.string.require_time), s.requireTimeForEvents, stringResource(R.string.require_time_body)) { v -> custom { it.copy(requireTimeForEvents = v) } }
    SettingSwitch(stringResource(R.string.undated_as_tasks), s.undatedEventsAsTasks, stringResource(R.string.undated_as_tasks_body)) { v -> custom { it.copy(undatedEventsAsTasks = v) } }
    SettingSwitch(stringResource(R.string.address_suggestions), s.addressSuggestions, stringResource(R.string.address_suggestions_body)) { v -> Ui.update { it.copy(addressSuggestions = v) } }
    SettingSwitch(stringResource(R.string.ignore_past), s.ignorePast) { v -> custom { it.copy(ignorePast = v) } }
    SettingSwitch(stringResource(R.string.day_first), s.dayFirst, stringResource(R.string.day_first_body)) { v -> custom { it.copy(dayFirst = v) } }
    HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
    SettingSlider(stringResource(R.string.max_days_ahead), s.maxDaysAhead.toFloat(), 7f..730f, format = { "${it.roundToInt()}" }) { v -> custom { it.copy(maxDaysAhead = v.roundToInt()) } }
    SettingSlider(stringResource(R.string.min_text_length), s.minTextLength.toFloat(), 0f..60f, format = { "${it.roundToInt()}" }) { v -> custom { it.copy(minTextLength = v.roundToInt()) } }
    SettingSlider(stringResource(R.string.dedupe_minutes), s.dedupeMinutes.toFloat(), 0f..1440f, format = { "${it.roundToInt()} min" }, subtitle = stringResource(R.string.dedupe_body)) { v -> custom { it.copy(dedupeMinutes = v.roundToInt()) } }
    if (s.mergeDuplicateEvents) {
      SettingSlider(
        stringResource(R.string.duplicate_threshold),
        s.duplicateThreshold.toFloat(),
        0.40f..0.95f,
        subtitle = stringResource(R.string.duplicate_threshold_body),
      ) { v -> custom { it.copy(duplicateThreshold = v.toDouble()) } }
    }
  }

  SettingGroupCard(title = stringResource(R.string.keywords)) {
    SettingText(stringResource(R.string.boost_keywords), s.boostKeywords, singleLine = false, supporting = stringResource(R.string.boost_keywords_body)) { v -> custom { it.copy(boostKeywords = v) } }
    SettingText(stringResource(R.string.block_keywords), s.blockKeywords, singleLine = false, supporting = stringResource(R.string.block_keywords_body)) { v -> custom { it.copy(blockKeywords = v) } }
    SettingText(stringResource(R.string.blocked_senders), s.blockedSenders, singleLine = false, supporting = stringResource(R.string.blocked_senders_body)) { v -> custom { it.copy(blockedSenders = v) } }
  }

  SettingGroupCard(title = stringResource(R.string.calendar_defaults)) {
    SettingSlider(stringResource(R.string.default_duration), s.defaultEventMinutes.toFloat(), 15f..240f, steps = 14, format = { "${it.roundToInt()} min" }) { v -> Ui.update { it.copy(defaultEventMinutes = v.roundToInt()) } }
    SettingSlider(stringResource(R.string.reminder), s.reminderMinutes.toFloat(), -1f..1440f, format = { if (it < 0) resources.getString(R.string.off) else "${it.roundToInt()} min" }) { v -> Ui.update { it.copy(reminderMinutes = v.roundToInt()) } }
  }

  SettingGroupCard(title = stringResource(R.string.quiet_hours)) {
    SettingSwitch(stringResource(R.string.quiet_hours), s.quietHours, stringResource(R.string.quiet_hours_body)) { v -> Ui.update { it.copy(quietHours = v) } }
    if (s.quietHours) Row(Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { pickQuiet = true }) { Text("%02d:%02d".format(s.quietStartMinute / 60, s.quietStartMinute % 60)) }
      Text("–", Modifier.padding(top = 10.dp))
      OutlinedButton(onClick = { pickQuiet = false }) { Text("%02d:%02d".format(s.quietEndMinute / 60, s.quietEndMinute % 60)) }
    }
  }

  pickQuiet?.let { isStart ->
    val m = if (isStart) s.quietStartMinute else s.quietEndMinute
    val state = rememberTimePickerState(m / 60, m % 60, true)
    AlertDialog(
      onDismissRequest = { pickQuiet = null },
      confirmButton = {
        TextButton(onClick = {
          val v = state.hour * 60 + state.minute
          Ui.update { if (isStart) it.copy(quietStartMinute = v) else it.copy(quietEndMinute = v) }
          pickQuiet = null
        }) { Text(stringResource(R.string.ok)) }
      },
      text = { TimePicker(state) },
    )
  }
}
