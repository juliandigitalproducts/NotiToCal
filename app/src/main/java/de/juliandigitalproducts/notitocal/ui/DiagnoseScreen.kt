// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.AntiVocaleUtils
import de.juliandigitalproducts.notitocal.diagnostics.BugReportGenerator
import de.juliandigitalproducts.notitocal.diagnostics.SelfTest
import de.juliandigitalproducts.notitocal.diagnostics.SelfTest.CheckStatus
import de.juliandigitalproducts.notitocal.diagnostics.SelfTestTip
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.text.style.TextOverflow
import de.juliandigitalproducts.notitocal.model.runSafely
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnoseScreen(
  onBack: () -> Unit,
  onOpen: (String) -> Unit = {},
  initialTab: Int = 0,
) {
  val scope = rememberCoroutineScope()
  var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
  var running by remember { mutableStateOf(false) }
  var items by remember { mutableStateOf(SelfTest.initialChecklist(Ui.app)) }
  var failure by remember { mutableStateOf<String?>(null) }

  SubScreen(stringResource(R.string.diagnose_and_backup), onBack) {
    SingleChoiceSegmentedButtonRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
      SegmentedButton(
        selected = selectedTab == 0,
        onClick = { selectedTab = 0 },
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
        icon = { Icon(Icons.Filled.Science, null, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
      ) {
        Text(
          text = stringResource(R.string.diagnose),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(horizontal = 2.dp),
        )
      }
      SegmentedButton(
        selected = selectedTab == 1,
        onClick = { selectedTab = 1 },
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
        icon = { Icon(Icons.Filled.BugReport, null, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
      ) {
        Text(
          text = stringResource(R.string.bug_report_tab),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(horizontal = 2.dp),
        )
      }
      SegmentedButton(
        selected = selectedTab == 2,
        onClick = { selectedTab = 2 },
        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
        icon = { Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
      ) {
        Text(
          text = stringResource(R.string.diagnose_tab_backup),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(horizontal = 2.dp),
        )
      }
    }

    when (selectedTab) {
      0 -> {
        Hint(stringResource(R.string.diagnose_body))
        Button(
          onClick = {
            running = true
            failure = null
            items = SelfTest.initialChecklist(Ui.app)
            scope.launch {
              val outcome = runSafely {
                SelfTest.runChecklist(Ui.app) { updated ->
                  items = updated
                }
              }
              running = false
              outcome.onFailure { failure = it.message ?: it.javaClass.simpleName }
            }
          },
          enabled = !running,
          modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 12.dp),
        ) {
          if (running) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(10.dp))
          }
          Text(stringResource(R.string.selftest_run))
        }

        failure?.let {
          Text(
            stringResource(R.string.selftest_error, it),
            Modifier.padding(horizontal = Gaps.Inner, vertical = 4.dp),
            color = MaterialTheme.colorScheme.error,
          )
        }

        Card(
          modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 8.dp),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            items.forEachIndexed { index, item ->
              if (index > 0) HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
              DiagnoseChecklistRow(item = item, onOpen = onOpen)
            }
          }
        }

        OutlinedButton(
          onClick = { selectedTab = 1 },
          modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp),
        ) {
          Icon(Icons.Filled.BugReport, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.bug_report_title) + " erstellen")
        }
      }
      1 -> {
        BugReportContent(onOpen = onOpen)
      }
      else -> {
        BackupContent()
      }
    }
  }
}

@Composable
private fun BugReportContent(onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var report by remember { mutableStateOf<BugReportGenerator.BugReport?>(null) }
  var generating by remember { mutableStateOf(false) }
  var copiedNotice by remember { mutableStateOf(false) }

  fun triggerGenerate() {
    generating = true
    copiedNotice = false
    scope.launch {
      report = BugReportGenerator.generate(context, Ui.app)
      generating = false
    }
  }

  androidx.compose.runtime.LaunchedEffect(Unit) {
    if (report == null) triggerGenerate()
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = Gaps.Inner),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
      SettingSwitch(
        title = stringResource(R.string.diagnostic_logging_active),
        checked = s.diagnosticLoggingEnabled,
        subtitle = stringResource(R.string.diagnostic_logging_active_desc),
      ) { v ->
        Ui.update { it.copy(diagnosticLoggingEnabled = v) }
      }
    }

    Hint(stringResource(R.string.bug_report_desc))

    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
      Column(modifier = Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            stringResource(R.string.bug_report_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
          if (generating) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
        report?.let { rep ->
          Text(
            rep.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = { triggerGenerate() },
            enabled = !generating,
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.bug_report_generate))
          }
          OutlinedButton(
            onClick = {
              report?.let { rep ->
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("NotiToCal Bug Report", rep.text))
                copiedNotice = true
              }
            },
            enabled = report != null && !generating,
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.bug_report_copy))
          }
        }
        report?.let { rep ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = { BugReportGenerator.shareReport(context, rep) },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.bug_report_share))
            }
          }
        }
        if (copiedNotice) {
          Text(
            stringResource(R.string.bug_report_copied),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2E7D32),
          )
        }
      }
    }

    report?.let { rep ->
      Text(
        "Vorschau des erstellten Berichts:",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
      )
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = rep.text,
          modifier = Modifier.padding(12.dp),
          style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 14.sp,
          ),
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

@Composable
private fun DiagnoseChecklistRow(item: SelfTest.CheckItem, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      when (item.status) {
        CheckStatus.IDLE -> Icon(
          Icons.Filled.RadioButtonUnchecked,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.outline,
          modifier = Modifier.size(22.dp),
        )
        CheckStatus.RUNNING -> CircularProgressIndicator(
          modifier = Modifier.size(22.dp),
          strokeWidth = 2.5.dp,
        )
        CheckStatus.PASSED -> Icon(
          Icons.Filled.CheckCircle,
          contentDescription = stringResource(R.string.selftest_passed),
          tint = Color(0xFF2E7D32),
          modifier = Modifier.size(22.dp),
        )
        CheckStatus.FAILED -> Icon(
          Icons.Filled.Cancel,
          contentDescription = stringResource(R.string.selftest_failed),
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(22.dp),
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        val headline = if (item.detail.isNotBlank()) {
          "${item.title} - ${item.detail}"
        } else {
          item.title
        }
        Text(
          headline,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = when (item.status) {
            CheckStatus.FAILED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
          },
        )
      }
    }

    item.step?.let { step ->
      if (step.recognized.isNotBlank()) {
        Text(
          stringResource(R.string.selftest_recognized, step.recognized),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 34.dp),
        )
      }
      step.tips.forEach { tip ->
        Text(
          tipText(tip),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(start = 34.dp),
        )
      }
      if (step.tips.contains(SelfTestTip.ANTI_VOCALE_FOLDER) || step.tips.contains(SelfTestTip.ANTI_VOCALE_OFF)) {
        Column(Modifier.padding(start = 34.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Button(onClick = { onOpen("settings/sources") }) {
            Text(stringResource(R.string.selftest_open_antivocale_settings))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { AntiVocaleUtils.openApp(context) }) {
              Text(stringResource(R.string.anti_vocale_open_app))
            }
            TextButton(onClick = { AntiVocaleUtils.openPlayStore(context) }) {
              Text(stringResource(R.string.anti_vocale_play_store))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun tipText(tip: SelfTestTip): String = stringResource(when (tip) {
  SelfTestTip.ANTI_VOCALE_FOLDER -> R.string.tip_anti_vocale_folder
  SelfTestTip.ANTI_VOCALE_OFF -> R.string.tip_anti_vocale_off
  SelfTestTip.OCR_EMPTY_NO_VISION -> R.string.ocr_empty_no_vision
})
