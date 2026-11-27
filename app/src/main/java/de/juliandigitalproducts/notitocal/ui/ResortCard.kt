// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.pipeline.Resort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One run of "apply the rules to existing entries": range picked → preview → running → result. */
private sealed interface ResortStep {
  data object PickRange : ResortStep
  data class Preview(val from: LocalDate, val to: LocalDate, val changes: List<Resort.Change>, val checked: Int) : ResortStep
  data class Running(val preview: Preview) : ResortStep
  data class Done(val message: String) : ResortStep
}

/** Card with the button to sort existing events and tasks in a chosen date range by the current rules again. */
@Composable
fun ResortCard() {
  var step by remember { mutableStateOf<ResortStep?>(null) }
  SettingGroupCard(title = stringResource(R.string.resort_title)) {
    Hint(stringResource(R.string.resort_body))
    Row(Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
      OutlinedButton(onClick = { step = ResortStep.PickRange }) {
        Icon(Icons.AutoMirrored.Filled.Sort, null)
        Text(stringResource(R.string.resort_pick_range), Modifier.padding(start = 8.dp))
      }
    }
  }
  step?.let { current -> ResortFlow(current, onStep = { step = it }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResortFlow(step: ResortStep, onStep: (ResortStep?) -> Unit) {
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  val resort = remember { Resort(Ui.app) }
  when (step) {
    ResortStep.PickRange -> {
      val today = LocalDate.now()
      val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = today.minusDays(30).utcMillis(),
        initialSelectedEndDateMillis = today.plusDays(90).utcMillis(),
      )
      DatePickerDialog(
        onDismissRequest = { onStep(null) },
        confirmButton = {
          TextButton(
            enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
            onClick = {
              val from = state.selectedStartDateMillis!!.utcDate()
              val to = state.selectedEndDateMillis!!.utcDate()
              scope.launch {
                val (changes, checked) = withContext(Dispatchers.IO) { resort.plan(from, to) }
                onStep(ResortStep.Preview(from, to, changes, checked))
              }
            },
          ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { onStep(null) }) { Text(stringResource(R.string.cancel)) } },
      ) { DateRangePicker(state, Modifier.weight(1f)) }
    }
    is ResortStep.Preview -> {
      val range = stringResource(R.string.resort_range, step.from.short(), step.to.short())
      AlertDialog(
        onDismissRequest = { onStep(null) },
        title = { Text(stringResource(R.string.resort_title)) },
        text = {
          Text(
            if (step.changes.isEmpty()) range + "\n\n" + stringResource(R.string.resort_nothing)
            else range + "\n\n" + stringResource(R.string.resort_preview, step.checked, step.changes.size) + "\n\n" + stringResource(R.string.resort_external_note),
          )
        },
        confirmButton = {
          if (step.changes.isEmpty()) TextButton(onClick = { onStep(null) }) { Text(stringResource(R.string.ok)) }
          else TextButton(onClick = { onStep(ResortStep.Running(step)) }) { Text(stringResource(R.string.resort_apply)) }
        },
        dismissButton = if (step.changes.isEmpty()) null else ({ TextButton(onClick = { onStep(null) }) { Text(stringResource(R.string.cancel)) } }),
      )
    }
    is ResortStep.Running -> {
      var progress by remember { mutableFloatStateOf(0f) }
      var result by remember { mutableStateOf<Resort.Summary?>(null) }
      LaunchedEffect(step) {
        result = withContext(Dispatchers.IO) {
          resort.run(step.preview.changes, step.preview.checked) { done, total -> progress = if (total > 0) done.toFloat() / total else 1f }
        }
      }
      AiProgressDialog(
        title = stringResource(R.string.resort_title),
        statusOverride = stringResource(R.string.resort_apply),
        progress = progress,
        isFinished = result != null,
        onDismiss = {
          result?.let { r -> onStep(ResortStep.Done(resources.getString(R.string.resort_done, r.changed, r.moved, r.notMoved))) }
        },
      )
    }
    is ResortStep.Done -> AlertDialog(
      onDismissRequest = { onStep(null) },
      title = { Text(stringResource(R.string.resort_title)) },
      text = { Text(step.message) },
      confirmButton = { TextButton(onClick = { onStep(null) }) { Text(stringResource(R.string.ok)) } },
    )
  }
}

private fun LocalDate.utcMillis(): Long = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
private fun LocalDate.short(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
