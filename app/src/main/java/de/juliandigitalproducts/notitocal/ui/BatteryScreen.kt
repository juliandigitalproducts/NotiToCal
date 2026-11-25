package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.AnalysisSchedule
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun scheduleLabel(context: android.content.Context, a: AnalysisSchedule): String = context.getString(when (a) {
  AnalysisSchedule.IMMEDIATE -> R.string.sched_immediate
  AnalysisSchedule.EVERY_15_MIN -> R.string.sched_15
  AnalysisSchedule.HOURLY -> R.string.sched_hourly
  AnalysisSchedule.EVERY_3H -> R.string.sched_3h
  AnalysisSchedule.TIMES_DAILY -> R.string.sched_times_daily
  AnalysisSchedule.DAILY -> R.string.sched_daily
})

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

/** "Fri 13:00" for the next planned analysis. */
fun formatRun(t: LocalDateTime): String {
  val time = t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
  return if (t.toLocalDate() == LocalDateTime.now().toLocalDate()) time
  else t.format(DateTimeFormatter.ofPattern("EEE")) + " " + time
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BatteryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val queued by Ui.app.log.queued.collectAsState()
  var running by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<String?>(null) }
  // Index of the time slot being edited; -1 adds a new one.
  var editSlot by remember { mutableStateOf<Int?>(null) }

  SubScreen(stringResource(R.string.section_battery), onBack) {
    Hint(stringResource(R.string.battery_intro))

    SettingGroupCard(title = stringResource(R.string.analysis_schedule), emphasized = true) {
      SettingDropdown(stringResource(R.string.analysis_schedule), AnalysisSchedule.entries, s.analysisSchedule, { scheduleLabel(context, it) }) { v ->
        Ui.update { it.copy(analysisSchedule = v) }
      }
      if (s.analysisSchedule == AnalysisSchedule.TIMES_DAILY || s.analysisSchedule == AnalysisSchedule.DAILY) {
        val times = if (s.analysisSchedule == AnalysisSchedule.DAILY) s.analysisTimes.take(1) else s.analysisTimes
        FlowRow(Modifier.padding(horizontal = Gaps.Inner, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          times.forEachIndexed { i, m ->
            InputChip(
              selected = false,
              onClick = { editSlot = i },
              label = { Text(hm(m)) },
              trailingIcon = if (times.size > 1) {
                { Icon(Icons.Filled.Close, stringResource(R.string.remove), Modifier.padding(0.dp)) }
              } else null,
            )
          }
          if (s.analysisSchedule == AnalysisSchedule.TIMES_DAILY && times.size < 8) {
            TextButton(onClick = { editSlot = -1 }) { Icon(Icons.Filled.Add, null); Text(stringResource(R.string.add_time_slot)) }
          }
        }
        if (s.analysisSchedule == AnalysisSchedule.TIMES_DAILY && times.size > 1) Hint(stringResource(R.string.time_slot_hint))
      }
      if (s.analysisSchedule != AnalysisSchedule.IMMEDIATE) {
        HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
        InfoCard(stringResource(R.string.battery_tradeoff_title), stringResource(R.string.battery_tradeoff_body), icon = Icons.Filled.BatteryChargingFull)
        SettingSwitch(stringResource(R.string.urgent_now), s.urgentImmediately, stringResource(R.string.urgent_now_body)) { v -> Ui.update { it.copy(urgentImmediately = v) } }
        SettingSwitch(stringResource(R.string.only_wifi), s.analyzeOnlyWifi, stringResource(R.string.only_wifi_body)) { v -> Ui.update { it.copy(analyzeOnlyWifi = v) } }
        SettingSwitch(stringResource(R.string.only_charging), s.analyzeOnlyCharging, stringResource(R.string.only_charging_body)) { v -> Ui.update { it.copy(analyzeOnlyCharging = v) } }
      }
    }

    if (s.analysisSchedule != AnalysisSchedule.IMMEDIATE) {
      SettingGroupCard(title = stringResource(R.string.queue_title)) {
        Hint(
          pluralStringResource(R.plurals.queue_waiting, queued, queued) +
            (Ui.app.batch.plannedRun()?.takeIf { queued > 0 }?.let { " · " + stringResource(R.string.next_run, formatRun(it)) } ?: ""),
        )
        Row(Modifier.padding(horizontal = Gaps.Inner, vertical = 6.dp)) {
          Button(enabled = queued > 0 && !running, onClick = { running = true }) { Text(stringResource(R.string.analyze_now)) }
        }
      }
    }

    SettingGroupCard(title = stringResource(R.string.source_voice_memo)) {
      if (!s.antiVocaleActive) {
        Hint(stringResource(R.string.voice_no_folder))
        Row(Modifier.padding(horizontal = Gaps.Inner, vertical = 6.dp)) {
          OutlinedButton(onClick = { onOpen("settings/sources") }) { Text(stringResource(R.string.voice_choose_folder)) }
        }
      } else {
        SettingSwitch(
          stringResource(R.string.voice_batched), s.voiceBatched && s.analysisSchedule != AnalysisSchedule.IMMEDIATE,
          stringResource(R.string.voice_batched_body), enabled = s.analysisSchedule != AnalysisSchedule.IMMEDIATE,
        ) { v -> Ui.update { it.copy(voiceBatched = v) } }
      }
    }

    SettingGroupCard(title = stringResource(R.string.notifications_title)) {
      Hint(stringResource(R.string.notifications_intro))
      SettingSwitch(stringResource(R.string.notify_on_add), s.notifyOnAdd) { v -> Ui.update { it.copy(notifyOnAdd = v) } }
      SettingSwitch(stringResource(R.string.notify_confirm), s.notifyConfirm, stringResource(R.string.notify_confirm_body)) { v -> Ui.update { it.copy(notifyConfirm = v) } }
      SettingSwitch(stringResource(R.string.memo_notification), s.notifyMemoText, stringResource(R.string.memo_notification_body)) { v -> Ui.update { it.copy(notifyMemoText = v) } }
      SettingSwitch(stringResource(R.string.notify_errors), s.notifyErrors, stringResource(R.string.notify_errors_body)) { v -> Ui.update { it.copy(notifyErrors = v) } }
    }
  }

  if (running) {
    BatchRunDialog { r ->
      running = false
      result = resources.getString(R.string.analyze_now_done, r.processed, r.added, r.asked) +
        (if (r.failed > 0) "\n" + resources.getQuantityString(R.plurals.analyze_now_failed, r.failed, r.failed) else "")
    }
  }
  result?.let { r ->
    AlertDialog(
      onDismissRequest = { result = null },
      confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(R.string.ok)) } },
      text = { Text(r) },
    )
  }

  editSlot?.let { idx ->
    val current = s.analysisTimes.getOrNull(idx) ?: (12 * 60)
    val state = rememberTimePickerState(current / 60, current % 60, true)
    AlertDialog(
      onDismissRequest = { editSlot = null },
      confirmButton = {
        TextButton(onClick = {
          val m = state.hour * 60 + state.minute
          Ui.update { st ->
            val list = st.analysisTimes.toMutableList()
            if (idx in list.indices) list[idx] = m else list += m
            st.copy(analysisTimes = list.distinct().sorted())
          }
          editSlot = null
        }) { Text(stringResource(R.string.ok)) }
      },
      dismissButton = {
        if (idx >= 0 && s.analysisTimes.size > 1 && s.analysisSchedule == AnalysisSchedule.TIMES_DAILY) TextButton(onClick = {
          Ui.update { st -> st.copy(analysisTimes = st.analysisTimes.filterIndexed { i, _ -> i != idx }) }
          editSlot = null
        }) { Text(stringResource(R.string.remove)) }
      },
      text = { TimePicker(state) },
    )
  }
}
