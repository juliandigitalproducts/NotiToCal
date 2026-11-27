// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.core.net.toUri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Search
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
import de.juliandigitalproducts.notitocal.data.ScreenshotAppFilter
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import android.widget.Toast
import de.juliandigitalproducts.notitocal.capture.TelegramBridgeExporter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.AntiVocaleUtils
import de.juliandigitalproducts.notitocal.capture.AntiVocaleWatch
import de.juliandigitalproducts.notitocal.capture.AntiVocaleWorker
import de.juliandigitalproducts.notitocal.capture.FolderWatch
import de.juliandigitalproducts.notitocal.capture.IcsCalendarSource
import de.juliandigitalproducts.notitocal.capture.ImapClient
import de.juliandigitalproducts.notitocal.capture.NotificationCaptureService
import de.juliandigitalproducts.notitocal.capture.ShareAndClipboardSource
import de.juliandigitalproducts.notitocal.capture.SmsRcsSource
import de.juliandigitalproducts.notitocal.capture.TelegramSource
import de.juliandigitalproducts.notitocal.capture.TelegramUserApiSource
import de.juliandigitalproducts.notitocal.capture.VoiceNotes
import de.juliandigitalproducts.notitocal.capture.WhatsAppSource
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.FilterListMode
import de.juliandigitalproducts.notitocal.data.OcrEngineMode
import de.juliandigitalproducts.notitocal.data.ImapAccount
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.data.TelegramDialogItem
import de.juliandigitalproducts.notitocal.data.TelegramDialogKind
import de.juliandigitalproducts.notitocal.diagnostics.SelfTest
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.widget.QuickCaptureActivity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ------------------------------------------------------------------ 10 Source Tiles Dashboard

private data class SourceTileSpec(
  val key: String,
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val active: Boolean,
  val count: Int = 0,
)

@Composable
fun SourcesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val recent by Ui.app.log.entries.collectAsState()
  // Counts per source over the whole history.
  val entries by androidx.compose.runtime.produceState(initialValue = recent, recent) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Ui.app.log.allEntries() }
  }
  val tick = rememberResumeTick()
  val access = remember(tick) { NotificationCaptureService.isEnabled(context) }
  val mediaGranted = remember(tick) { Ui.granted(context, Ui.imagePermission) }

  val counts = remember(entries) {
    mapOf(
      "whatsapp" to entries.count { it.source == SourceType.WHATSAPP || it.packageName.contains("whatsapp") || it.appLabel.contains("WhatsApp", ignoreCase = true) },
      "telegram_api" to entries.count { it.source == SourceType.TELEGRAM_API },
      "telegram_bot" to entries.count { it.source == SourceType.TELEGRAM },
      "notifications" to entries.count { it.source == SourceType.NOTIFICATION },
      "imap" to entries.count { it.source == SourceType.IMAP },
      "sms_rcs" to entries.count { it.source == SourceType.SMS_RCS || it.packageName in setOf("com.google.android.apps.messaging", "com.samsung.android.messaging") },
      "screenshots" to entries.count { it.source == SourceType.SCREENSHOT },
      "voice_memo" to entries.count { it.source == SourceType.VOICE_MEMO },
      "calendar_ics" to entries.count { it.source == SourceType.CALENDAR_ICS },
      "share_clipboard" to entries.count { it.source in setOf(SourceType.SHARED_TEXT, SourceType.SHARED_IMAGE, SourceType.CLIPBOARD, SourceType.MANUAL) },
    )
  }

  SubScreen(stringResource(R.string.section_sources), onBack) {
    Hint(stringResource(R.string.sources_dashboard_intro))

    SettingGroupCard {
      SettingNav(
        title = stringResource(R.string.diagnose),
        subtitle = stringResource(R.string.diagnose_body),
        icon = Icons.Filled.Science,
      ) { onOpen("settings/diagnose") }
    }

    Spacer(Modifier.height(6.dp))

    val tiles = listOf(
      SourceTileSpec(
        key = "whatsapp",
        title = stringResource(R.string.source_whatsapp_title),
        subtitle = stringResource(R.string.src_wa_sub),
        icon = Icons.AutoMirrored.Filled.Chat,
        active = (s.whatsappEnabled && (s.whatsappPersonalEnabled || s.whatsappBusinessEnabled)) && access,
        count = counts["whatsapp"] ?: 0,
      ),
      SourceTileSpec(
        key = "telegram",
        title = stringResource(R.string.source_telegram),
        subtitle = stringResource(R.string.src_tg_sub),
        icon = Icons.Filled.Security,
        active = ((s.notificationsEnabled && TelegramUserApiSource.TELEGRAM_PACKAGES.any { it in s.monitoredApps }) && access) ||
          s.telegramEnabled || (s.telegramApiEnabled && s.telegramApiConnectedUser.isNotBlank()),
        count = (counts["telegram_api"] ?: 0) + (counts["telegram_bot"] ?: 0) + entries.count { it.packageName in TelegramUserApiSource.TELEGRAM_PACKAGES },
      ),
      SourceTileSpec(
        key = "notifications",
        title = stringResource(R.string.source_notifications),
        subtitle = stringResource(R.string.src_notif_sub, installedCount(s.monitoredApps)),
        icon = Icons.Filled.Notifications,
        active = (s.notificationsEnabled || s.smsRcsEnabled) && access,
        count = (counts["notifications"] ?: 0) + (counts["sms_rcs"] ?: 0),
      ),
      SourceTileSpec(
        key = "imap",
        title = stringResource(R.string.source_imap),
        subtitle = if (s.imapAccounts.isEmpty()) stringResource(R.string.source_imap_sub) else stringResource(R.string.src_imap_count, s.imapAccounts.count { it.enabled }),
        icon = Icons.Filled.Email,
        active = s.imapEnabled && s.imapAccounts.any { it.enabled },
        count = counts["imap"] ?: 0,
      ),
      SourceTileSpec(
        key = "screenshots",
        title = stringResource(R.string.source_screenshots),
        subtitle = stringResource(R.string.source_screenshots_sub),
        icon = Icons.Filled.CameraAlt,
        active = s.screenshotsEnabled && mediaGranted,
        count = counts["screenshots"] ?: 0,
      ),
      SourceTileSpec(
        key = "voice_memo",
        title = stringResource(R.string.source_voice_memo),
        subtitle = stringResource(R.string.source_voice_memo_sub),
        icon = Icons.Filled.Mic,
        active = s.antiVocaleEnabled,
        count = counts["voice_memo"] ?: 0,
      ),
      SourceTileSpec(
        key = "calendar_ics",
        title = stringResource(R.string.source_ics_title),
        subtitle = stringResource(R.string.source_ics_sub),
        icon = Icons.Filled.CalendarMonth,
        active = s.icsEnabled,
        count = counts["calendar_ics"] ?: 0,
      ),
      SourceTileSpec(
        key = "share_clipboard",
        title = stringResource(R.string.source_share_clipboard),
        subtitle = stringResource(R.string.source_share_clipboard_sub),
        icon = Icons.Filled.Share,
        active = s.shareEnabled || s.clipboardEnabled,
        count = counts["share_clipboard"] ?: 0,
      ),
    )

    TileGrid(tiles, Modifier.padding(horizontal = Gaps.Edge), extraWideColumns = 4) { tile, _ ->
      SourceDashboardTile(spec = tile, onClick = { onOpen("settings/sources/${tile.key}") })
    }
  }
}

@Composable
private fun SourceDashboardTile(
  spec: SourceTileSpec,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 3.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = CircleShape,
          color = if (spec.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
          modifier = Modifier.size(38.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              spec.icon,
              contentDescription = null,
              tint = if (spec.active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          }
        }
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (spec.active) Color(0xFF2E7D32).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
          Text(
            text = stringResource(if (spec.active) R.string.status_active_short else R.string.off),
            style = MaterialTheme.typography.labelSmall,
            color = if (spec.active) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }

      Spacer(Modifier.height(10.dp))

      Text(
        spec.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        spec.subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        lineHeight = 15.sp,
      )
      Spacer(Modifier.height(8.dp))
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
      ) {
        Text(
          text = stringResource(R.string.src_processed_count, spec.count),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
      }
    }
  }
}

// ------------------------------------------------------------------ Reusable Interactive Setup Wizard Card

@Composable
private fun CopyableCommandCard(
  command: String,
  label: String? = null,
  clipboard: androidx.compose.ui.platform.ClipboardManager,
  onCopied: (String) -> Unit,
) {
  Card(
    shape = RoundedCornerShape(10.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        if (label != null) {
          Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
      }
      IconButton(onClick = {
        clipboard.setText(AnnotatedString(command))
        onCopied(command)
      }) {
        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_text), modifier = Modifier.size(18.dp))
      }
    }
  }
}

data class InteractiveStep(
  val title: String,
  val description: String,
  val isCompleted: Boolean = false,
  val content: @Composable ColumnScope.() -> Unit,
)

@Composable
fun InteractiveSetupWizardCard(
  wizardTitle: String,
  steps: List<InteractiveStep>,
  modifier: Modifier = Modifier,
) {
  val s by Ui.app.settings.state.collectAsState()
  if (s.hideWizardsInMenus) return

  val allCompleted = steps.isNotEmpty() && steps.all { it.isCompleted }
  var expanded by rememberSaveable(allCompleted) { mutableStateOf(!allCompleted) }
  var currentStep by rememberSaveable { mutableIntStateOf(0) }
  val safeStep = currentStep.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
  var unlockedSteps by rememberSaveable { mutableStateOf(setOf<Int>()) }
  var confirmEditStep by remember { mutableStateOf<Int?>(null) }

  if (allCompleted && !expanded) {
    Surface(
      onClick = { expanded = true },
      shape = RoundedCornerShape(12.dp),
      color = Color(0xFF2E7D32).copy(alpha = 0.12f),
      border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.35f)),
      modifier = modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
          Text(
            stringResource(R.string.wizard_already_configured),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF2E7D32),
            fontWeight = FontWeight.SemiBold,
          )
        }
        Text(
          stringResource(R.string.wizard_open_btn),
          style = MaterialTheme.typography.labelSmall,
          color = Color(0xFF2E7D32),
          fontWeight = FontWeight.Bold,
        )
      }
    }
    return
  }

  Card(
    modifier = modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 8.dp),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(Gaps.Inner),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            wizardTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            stringResource(R.string.wizard_step_counter, safeStep + 1, steps.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TextButton(onClick = { expanded = !expanded }) {
          Text(stringResource(if (expanded) R.string.wizard_hide else R.string.wizard_show))
        }
      }

      if (allCompleted) {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color(0xFF2E7D32).copy(alpha = 0.15f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
            Text(
              stringResource(R.string.wizard_all_steps_done),
              style = MaterialTheme.typography.labelMedium,
              color = Color(0xFF2E7D32),
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }

      if (expanded && steps.isNotEmpty()) {
        LinearProgressIndicator(
          progress = { (safeStep + 1) / steps.size.toFloat() },
          modifier = Modifier.fillMaxWidth(),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          steps.forEachIndexed { idx, st ->
            FilterChip(
              selected = idx == safeStep,
              onClick = { currentStep = idx },
              label = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  if (st.isCompleted) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(13.dp))
                  }
                  Text("${idx + 1}")
                }
              },
            )
          }
        }

        val step = steps[safeStep]
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surface,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                "${safeStep + 1}. ${step.title}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false),
              )
              if (step.isCompleted) {
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.wizard_step_done_badge), style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                  }
                }
              }
            }
            Text(
              step.description,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step.isCompleted && safeStep !in unlockedSteps) {
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF2E7D32).copy(alpha = 0.08f),
                border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(
                  modifier = Modifier.padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                    Text(
                      stringResource(R.string.wizard_step_locked_notice),
                      style = MaterialTheme.typography.bodySmall,
                      color = Color(0xFF1B5E20),
                      fontWeight = FontWeight.Medium,
                      modifier = Modifier.weight(1f),
                    )
                  }
                  OutlinedButton(
                    onClick = { confirmEditStep = safeStep },
                    modifier = Modifier.align(Alignment.End),
                  ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.wizard_step_edit_btn))
                  }
                }
              }
            } else {
              step.content(this)
            }
          }
        }

        if (confirmEditStep != null) {
          AlertDialog(
            onDismissRequest = { confirmEditStep = null },
            title = { Text(stringResource(R.string.wizard_step_already_filled_title)) },
            text = { Text(stringResource(R.string.wizard_step_already_filled_msg)) },
            confirmButton = {
              Button(onClick = {
                confirmEditStep?.let { unlockedSteps = unlockedSteps + it }
                confirmEditStep = null
              }) {
                Text(stringResource(R.string.wizard_step_edit_btn))
              }
            },
            dismissButton = {
              TextButton(onClick = { confirmEditStep = null }) {
                Text(stringResource(R.string.cancel))
              }
            },
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (safeStep > 0) {
            OutlinedButton(onClick = { currentStep = safeStep - 1 }) {
              Text(stringResource(R.string.back))
            }
          } else {
            Spacer(Modifier.width(1.dp))
          }

          if (safeStep < steps.size - 1) {
            Button(onClick = { currentStep = safeStep + 1 }) {
              Text(stringResource(R.string.next))
            }
          } else {
            Button(onClick = { expanded = false }) {
              Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.wizard_hide))
            }
          }
        }
      }
    }
  }
}

// ------------------------------------------------------------------ Dedicated Submenu Screen per Source

@Composable
fun SourceDetailScreen(sourceKey: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
  when (sourceKey) {
    "whatsapp" -> WhatsAppSourceSubScreen(onBack, onOpen, initialTab = 0)
    "whatsapp_business" -> WhatsAppSourceSubScreen(onBack, onOpen, initialTab = 1)
    "telegram" -> TelegramCombinedSubScreen(onBack, onOpen, initialTab = 0)
    "telegram_bot" -> TelegramCombinedSubScreen(onBack, onOpen, initialTab = 1)
    "telegram_api" -> TelegramCombinedSubScreen(onBack, onOpen, initialTab = 2)
    "notifications" -> NotificationsSourceSubScreen(onBack, onOpen, initialTab = 0)
    "sms_rcs" -> NotificationsSourceSubScreen(onBack, onOpen, initialTab = 1)
    "imap" -> ImapSourceSubScreen(onBack)
    "screenshots" -> ScreenshotsSourceSubScreen(onBack, onOpen)
    "voice_memo" -> VoiceMemoSourceSubScreen(onBack)
    "calendar_ics" -> CalendarIcsSourceSubScreen(onBack)
    "share_clipboard" -> ShareClipboardSourceSubScreen(onBack, onOpen)
    else -> SourcesScreen(onBack, onOpen)
  }
}

// 1. WhatsApp & WhatsApp Business Submenu
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsAppSourceSubScreen(
  onBack: () -> Unit,
  onOpen: (String) -> Unit,
  initialTab: Int = 0,
) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val tick = rememberResumeTick()
  val access = remember(tick) { NotificationCaptureService.isEnabled(context) }
  var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }

  SubScreen(stringResource(R.string.source_whatsapp_title), onBack) {
    Hint(stringResource(R.string.whatsapp_notification_mode_info))

    if (s.whatsappPersonalEnabled && s.whatsappBusinessEnabled) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
          Spacer(Modifier.width(10.dp))
          Text(
            text = stringResource(R.string.whatsapp_both_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
      }
    }

    val personalActive = s.whatsappPersonalEnabled && (WhatsAppSource.PKG_PERSONAL in s.monitoredApps) && access
    val businessActive = s.whatsappBusinessEnabled && (WhatsAppSource.PKG_BUSINESS in s.monitoredApps) && access

    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
      Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(
          text = stringResource(R.string.whatsapp_config_notice),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    SingleChoiceSegmentedButtonRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
      SegmentedButton(
        selected = selectedTab == 0,
        onClick = { selectedTab = 0 },
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        icon = {
          if (personalActive) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.status_active_short), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
          } else {
            Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(16.dp))
          }
        },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
      ) {
        Text(
          text = stringResource(R.string.whatsapp_personal),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(horizontal = 2.dp),
        )
      }
      SegmentedButton(
        selected = selectedTab == 1,
        onClick = { selectedTab = 1 },
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        icon = {
          if (businessActive) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.status_active_short), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
          } else {
            Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(16.dp))
          }
        },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
      ) {
        Text(
          text = stringResource(R.string.whatsapp_business),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(horizontal = 2.dp),
        )
      }
    }

    when (selectedTab) {
      0 -> {
        // Tab 0: WhatsApp (Privat)
        InteractiveSetupWizardCard(
          wizardTitle = stringResource(R.string.wz_whatsapp_title),
          steps = listOf(
            InteractiveStep(
              title = stringResource(R.string.wa_p_enable),
              description = stringResource(R.string.wa_p_enable_desc),
              isCompleted = s.whatsappPersonalEnabled && (WhatsAppSource.PKG_PERSONAL in s.monitoredApps),
            ) {
              SettingSwitch(stringResource(R.string.whatsapp_personal), s.whatsappPersonalEnabled) { v ->
                Ui.update {
                  val apps = if (v) it.monitoredApps + WhatsAppSource.PKG_PERSONAL else it.monitoredApps - WhatsAppSource.PKG_PERSONAL
                  it.copy(
                    whatsappPersonalEnabled = v,
                    whatsappEnabled = if (v) true else it.whatsappBusinessEnabled,
                    monitoredApps = apps,
                  )
                }
              }
            },
            InteractiveStep(
              title = stringResource(R.string.perm_listener),
              description = stringResource(R.string.perm_listener_body),
              isCompleted = access,
            ) {
              PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_body), access) {
                Ui.openNotificationAccess(context)
              }
            },
            InteractiveStep(
              title = stringResource(R.string.filter_groups_persons_title),
              description = stringResource(R.string.whatsapp_step3_desc),
              isCompleted = true,
            ) {
              FilterModeSelector(
                mode = s.whatsappFilterMode,
                onModeChange = { m -> Ui.update { it.copy(whatsappFilterMode = m) } },
              )
              SettingText(
                label = stringResource(
                  if (s.whatsappFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_excluded_names else R.string.filter_allowed_names,
                ),
                value = s.whatsappFilterContacts,
                placeholder = stringResource(R.string.wa_p_filter_ph),
              ) { v -> Ui.update { it.copy(whatsappFilterContacts = v) } }
            },
            InteractiveStep(
              title = stringResource(R.string.wa_p_check),
              description = stringResource(R.string.wa_p_check_desc),
              isCompleted = s.whatsappPersonalEnabled && access,
            ) {
              SourceTestAction { SelfTest.checkWhatsAppPersonal(Ui.app) }
            },
          ),
        )

        SettingGroupCard(title = stringResource(R.string.wz_whatsapp_title), emphasized = true) {
          SettingSwitch(stringResource(R.string.whatsapp_personal), s.whatsappPersonalEnabled) { v ->
            Ui.update {
              val apps = if (v) it.monitoredApps + WhatsAppSource.PKG_PERSONAL else it.monitoredApps - WhatsAppSource.PKG_PERSONAL
              it.copy(
                whatsappPersonalEnabled = v,
                whatsappEnabled = if (v) true else it.whatsappBusinessEnabled,
                monitoredApps = apps,
              )
            }
          }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SettingSwitch(stringResource(R.string.ignore_groups), s.whatsappIgnoreGroups, stringResource(R.string.ignore_groups_body)) { v ->
            Ui.update { it.copy(whatsappIgnoreGroups = v) }
          }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SettingSlider(
            title = stringResource(R.string.rate_limit_per_min),
            value = s.whatsappMaxMsgsPerMinute.toFloat(),
            range = 1f..30f,
            format = { "${it.roundToInt()} / Min." },
            subtitle = stringResource(R.string.rate_limit_per_min_desc),
          ) { v -> Ui.update { it.copy(whatsappMaxMsgsPerMinute = v.roundToInt()) } }
        }

        SettingGroupCard(title = stringResource(R.string.wa_filter_groups)) {
          Column(Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
            FilterModeSelector(
              mode = s.whatsappFilterMode,
              onModeChange = { m -> Ui.update { it.copy(whatsappFilterMode = m) } },
            )
          }
          SettingText(
            label = stringResource(
              if (s.whatsappFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_excluded_names else R.string.filter_allowed_names,
            ),
            value = s.whatsappFilterContacts,
            placeholder = stringResource(R.string.wa_p_filter_ph2),
            supporting = stringResource(R.string.filter_comma_hint),
          ) { v -> Ui.update { it.copy(whatsappFilterContacts = v) } }
          FilterSuggestionsChips(
            currentFilterText = s.whatsappFilterContacts,
            filterPredicate = { it.source == SourceType.WHATSAPP || it.packageName == WhatsAppSource.PKG_PERSONAL },
            onUpdateFilterText = { v -> Ui.update { it.copy(whatsappFilterContacts = v) } },
          )
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SourceTestAction { SelfTest.checkWhatsAppPersonal(Ui.app) }
        }
      }
      1 -> {
        // Tab 1: WhatsApp Business
        InteractiveSetupWizardCard(
          wizardTitle = stringResource(R.string.wz_wa_business_title),
          steps = listOf(
            InteractiveStep(
              title = stringResource(R.string.wa_b_enable),
              description = stringResource(R.string.wa_b_enable_desc),
              isCompleted = s.whatsappBusinessEnabled && (WhatsAppSource.PKG_BUSINESS in s.monitoredApps),
            ) {
              SettingSwitch(stringResource(R.string.whatsapp_business), s.whatsappBusinessEnabled, stringResource(R.string.whatsapp_business_desc)) { v ->
                Ui.update {
                  val apps = if (v) it.monitoredApps + WhatsAppSource.PKG_BUSINESS else it.monitoredApps - WhatsAppSource.PKG_BUSINESS
                  it.copy(
                    whatsappBusinessEnabled = v,
                    whatsappEnabled = if (v) true else it.whatsappPersonalEnabled,
                    monitoredApps = apps,
                  )
                }
              }
            },
            InteractiveStep(
              title = stringResource(R.string.perm_listener),
              description = stringResource(R.string.perm_listener_body),
              isCompleted = access,
            ) {
              PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_body), access) {
                Ui.openNotificationAccess(context)
              }
            },
            InteractiveStep(
              title = stringResource(R.string.wa_b_filter),
              description = stringResource(R.string.wa_b_filter_desc),
              isCompleted = true,
            ) {
              FilterModeSelector(
                mode = s.whatsappFilterMode,
                onModeChange = { m -> Ui.update { it.copy(whatsappFilterMode = m) } },
              )
              SettingText(
                label = stringResource(
                  if (s.whatsappFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_excluded_names else R.string.filter_allowed_names,
                ),
                value = s.whatsappFilterContacts,
                placeholder = stringResource(R.string.wa_b_filter_ph),
              ) { v -> Ui.update { it.copy(whatsappFilterContacts = v) } }
            },
            InteractiveStep(
              title = stringResource(R.string.wa_b_check),
              description = stringResource(R.string.wa_b_check_desc),
              isCompleted = s.whatsappBusinessEnabled && access,
            ) {
              SourceTestAction { SelfTest.checkWhatsAppBusiness(Ui.app) }
            },
          ),
        )

        SettingGroupCard(title = stringResource(R.string.wz_wa_business_title), emphasized = true) {
          SettingSwitch(stringResource(R.string.whatsapp_business), s.whatsappBusinessEnabled, stringResource(R.string.whatsapp_business_desc)) { v ->
            Ui.update {
              val apps = if (v) it.monitoredApps + WhatsAppSource.PKG_BUSINESS else it.monitoredApps - WhatsAppSource.PKG_BUSINESS
              it.copy(
                whatsappBusinessEnabled = v,
                whatsappEnabled = if (v) true else it.whatsappPersonalEnabled,
                monitoredApps = apps,
              )
            }
          }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SettingSwitch(stringResource(R.string.ignore_groups), s.whatsappIgnoreGroups, stringResource(R.string.ignore_groups_body)) { v ->
            Ui.update { it.copy(whatsappIgnoreGroups = v) }
          }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SettingSlider(
            title = stringResource(R.string.rate_limit_per_min),
            value = s.whatsappMaxMsgsPerMinute.toFloat(),
            range = 1f..30f,
            format = { "${it.roundToInt()} / Min." },
            subtitle = stringResource(R.string.rate_limit_per_min_desc),
          ) { v -> Ui.update { it.copy(whatsappMaxMsgsPerMinute = v.roundToInt()) } }
        }

        SettingGroupCard(title = stringResource(R.string.wa_b_filter_title)) {
          Column(Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
            FilterModeSelector(
              mode = s.whatsappFilterMode,
              onModeChange = { m -> Ui.update { it.copy(whatsappFilterMode = m) } },
            )
          }
          SettingText(
            label = stringResource(
              if (s.whatsappFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_excluded_names else R.string.filter_allowed_names,
            ),
            value = s.whatsappFilterContacts,
            placeholder = stringResource(R.string.wa_b_filter_ph2),
            supporting = stringResource(R.string.filter_comma_hint),
          ) { v -> Ui.update { it.copy(whatsappFilterContacts = v) } }
          FilterSuggestionsChips(
            currentFilterText = s.whatsappFilterContacts,
            filterPredicate = { it.source == SourceType.WHATSAPP || it.packageName == WhatsAppSource.PKG_BUSINESS },
            onUpdateFilterText = { v -> Ui.update { it.copy(whatsappFilterContacts = v) } },
          )
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SourceTestAction { SelfTest.checkWhatsAppBusiness(Ui.app) }
        }
      }
    }
  }
}

// 2. Telegram User-API (Invisible Client-API + Explicit Group & Person Filter + Flood Protection)
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TelegramUserApiContent(onOpen: (String) -> Unit = {}) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var loginCode by rememberSaveable { mutableStateOf("") }
  var authStatus by remember { mutableStateOf<String?>(null) }
  var authOk by remember { mutableStateOf(true) }
  var busy by remember { mutableStateOf(false) }
  var isSyncing by remember { mutableStateOf(false) }
  var syncNotice by remember { mutableStateOf<String?>(null) }
  var showAddDialog by remember { mutableStateOf(false) }
  var bridgeTestResult by remember { mutableStateOf<TelegramUserApiSource.BridgeCheckResult?>(null) }
  var testingBridge by remember { mutableStateOf(false) }
  var showBridgeHelpModal by remember { mutableStateOf(false) }

  fun triggerSync() {
    isSyncing = true
    syncNotice = null
    scope.launch {
      try {
        syncNotice = TelegramUserApiSource.syncDialogs(Ui.app).summary(Ui.app)
      } catch (e: Exception) {
        syncNotice = Ui.app.getString(R.string.error_detail, e.message.orEmpty())
      } finally {
        isSyncing = false
      }
    }
  }

  androidx.compose.runtime.LaunchedEffect(Unit) {
    runSafely { TelegramUserApiSource.syncDialogs(Ui.app) }
  }

  var bridgeLang by rememberSaveable { mutableStateOf(TelegramBridgeExporter.detectDefaultLanguageCode()) }
  var bridgeWeg by rememberSaveable { mutableIntStateOf(0) } // 0 = Termux, 1 = Server
  val clipboard = LocalClipboardManager.current
  var copiedFeedback by remember { mutableStateOf<String?>(null) }

  InteractiveSetupWizardCard(
    wizardTitle = stringResource(R.string.telegram_api_wizard_title),
    steps = listOf(
      InteractiveStep(
        title = stringResource(R.string.tg_step_path),
        description = stringResource(R.string.tg_step_path_desc),
        isCompleted = s.telegramApiBridgeUrl.isNotBlank(),
      ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          SegmentedButton(
            selected = bridgeWeg == 0,
            onClick = {
              bridgeWeg = 0
              if (s.telegramApiBridgeUrl.isBlank()) {
                Ui.update { it.copy(telegramApiBridgeUrl = "http://127.0.0.1:8085") }
              }
            },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
          ) {
            Text(stringResource(R.string.telegram_path_termux), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
          }
          SegmentedButton(
            selected = bridgeWeg == 1,
            onClick = { bridgeWeg = 1 },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
          ) {
            Text(stringResource(R.string.telegram_path_server), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            stringResource(R.string.telegram_bridge_script_language),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            TelegramBridgeExporter.SUPPORTED_LANGUAGES.forEach { lang ->
              FilterChip(
                selected = bridgeLang == lang.code,
                onClick = { bridgeLang = lang.code },
                label = { Text(lang.code.uppercase()) },
              )
            }
          }
        }

        if (bridgeWeg == 0) {
          Text(
            stringResource(R.string.telegram_path_termux_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = { Ui.openUrl(context, "https://f-droid.org/packages/com.termux/") },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.AutoMirrored.Filled.Launch, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.telegram_termux_download_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
              onClick = {
                val msg = TelegramBridgeExporter.saveScriptToDownloads(context, langCode = bridgeLang).second
                copiedFeedback = msg
                if (s.telegramApiBridgeUrl.isBlank()) {
                  Ui.update { it.copy(telegramApiBridgeUrl = "http://127.0.0.1:8085") }
                }
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.FileDownload, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("${stringResource(R.string.telegram_download_script_btn)} (${bridgeLang.uppercase()})", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }

          Text(stringResource(R.string.tg_termux_cmds), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)

          CopyableCommandCard(
            command = "pkg update && pkg install python && pip install telethon",
            label = stringResource(R.string.tg_cmd_install),
            clipboard = clipboard,
            onCopied = { copiedFeedback = Ui.app.getString(R.string.tg_cmd_copied) },
          )
          CopyableCommandCard(
            command = "termux-setup-storage",
            label = stringResource(R.string.tg_cmd_storage),
            clipboard = clipboard,
            onCopied = { copiedFeedback = Ui.app.getString(R.string.tg_cmd_copied) },
          )
          CopyableCommandCard(
            command = "python ~/storage/downloads/notitocal_telegram_bridge.py",
            label = stringResource(R.string.tg_cmd_start),
            clipboard = clipboard,
            onCopied = { copiedFeedback = Ui.app.getString(R.string.tg_cmd_copied) },
          )
        } else {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
              Text(
                stringResource(R.string.telegram_server_wifi_vpn_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = { TelegramBridgeExporter.shareScript(context, langCode = bridgeLang) },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.telegram_share_script_btn), maxLines = 1)
            }
            OutlinedButton(
              onClick = {
                val msg = TelegramBridgeExporter.saveAllLanguagesToDownloads(context).second
                copiedFeedback = msg
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.FileDownload, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.telegram_download_all_languages_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }

          Text(stringResource(R.string.tg_pc_cmds), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)

          CopyableCommandCard(
            command = "pip install telethon",
            label = stringResource(R.string.tg_cmd_pc_install),
            clipboard = clipboard,
            onCopied = { copiedFeedback = Ui.app.getString(R.string.tg_cmd_copied) },
          )
          CopyableCommandCard(
            command = "python3 notitocal_telegram_bridge.py --lan",
            label = stringResource(R.string.tg_cmd_pc_lan),
            clipboard = clipboard,
            onCopied = { copiedFeedback = Ui.app.getString(R.string.tg_cmd_copied) },
          )
          CopyableCommandCard(
            command = "adb reverse tcp:8085 tcp:8085 && python3 notitocal_telegram_bridge.py",
            label = stringResource(R.string.tg_cmd_pc_usb),
            clipboard = clipboard,
            onCopied = { copiedFeedback = Ui.app.getString(R.string.tg_cmd_copied) },
          )
        }

        copiedFeedback?.let { msg ->
          Text("✓ $msg", color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodySmall)
        }
      },
      InteractiveStep(
        title = stringResource(R.string.tg_step_creds),
        description = stringResource(R.string.tg_step_creds_desc),
        isCompleted = s.telegramApiId.isNotBlank() && s.telegramPhone.isNotBlank() && Ui.app.secrets.get(SecretKeys.TELEGRAM_API_HASH).isNotBlank(),
      ) {
        OutlinedButton(onClick = { Ui.openUrl(context, "https://my.telegram.org/apps") }) {
          Icon(Icons.AutoMirrored.Filled.Launch, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.telegram_bridge_open_apps_btn))
        }
        SettingText(
          label = stringResource(R.string.telegram_api_id),
          value = s.telegramApiId,
          number = true,
          placeholder = "12345678",
        ) { v -> Ui.update { it.copy(telegramApiId = v.trim()) } }
        SecretField(
          label = stringResource(R.string.telegram_api_hash),
          key = SecretKeys.TELEGRAM_API_HASH,
          supporting = stringResource(R.string.telegram_api_hash_hint),
        )
        SettingText(
          label = stringResource(R.string.telegram_api_phone),
          value = s.telegramPhone,
          placeholder = "+491701234567",
        ) { v -> Ui.update { it.copy(telegramPhone = v.trim()) } }
        SecretField(
          label = stringResource(R.string.telegram_2fa_password),
          key = SecretKeys.TELEGRAM_2FA_PASSWORD,
          supporting = stringResource(R.string.telegram_2fa_password_hint),
        )
      },
      InteractiveStep(
        title = stringResource(R.string.tg_step_connect),
        description = stringResource(R.string.tg_step_connect_desc),
        isCompleted = bridgeTestResult?.ok == true || s.telegramApiConnectedUser.isNotBlank(),
      ) {
        SettingText(
          label = stringResource(R.string.telegram_api_bridge_url),
          value = s.telegramApiBridgeUrl,
          placeholder = if (bridgeWeg == 0) "http://127.0.0.1:8085" else "http://192.168.178.50:8085",
          supporting = stringResource(R.string.telegram_api_bridge_hint),
        ) { v -> Ui.update { it.copy(telegramApiBridgeUrl = v.trim()) } }
        SecretField(
          label = stringResource(R.string.telegram_bridge_token),
          key = SecretKeys.TELEGRAM_BRIDGE_TOKEN,
          supporting = stringResource(R.string.telegram_bridge_token_hint),
        )
        Text(
          stringResource(R.string.tg_token_wrap_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
          onClick = {
            testingBridge = true
            scope.launch {
              bridgeTestResult = TelegramUserApiSource.testBridgeConnection(
                Ui.app,
                s.telegramApiBridgeUrl,
                Ui.app.secrets.get(SecretKeys.TELEGRAM_BRIDGE_TOKEN),
              )
              testingBridge = false
            }
          },
          enabled = !testingBridge,
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (testingBridge) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.checking_progress))
          } else {
            Icon(Icons.Filled.NetworkCheck, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.telegram_test_bridge_btn))
          }
        }
        bridgeTestResult?.let { bRes ->
          Card(
            colors = CardDefaults.cardColors(
              containerColor = if (bRes.ok) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = if (bRes.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (bRes.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
              )
              Column {
                Text(
                  text = if (bRes.ok) stringResource(R.string.telegram_bridge_reachable, bRes.latencyMs) else stringResource(R.string.telegram_bridge_unreachable),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = if (bRes.ok) Color(0xFF1B5E20) else MaterialTheme.colorScheme.error,
                )
                Text(
                  text = bRes.detail,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }
      },
      InteractiveStep(
        title = stringResource(R.string.tg_step_login),
        description = stringResource(R.string.tg_step_login_desc),
        isCompleted = s.telegramApiConnectedUser.isNotBlank(),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            enabled = !busy,
            onClick = {
              busy = true
              scope.launch {
                val res = TelegramUserApiSource.requestLoginCode(
                  context = Ui.app,
                  apiId = s.telegramApiId,
                  apiHash = Ui.app.secrets.get(SecretKeys.TELEGRAM_API_HASH),
                  phone = s.telegramPhone,
                  bridgeUrl = s.telegramApiBridgeUrl,
                  bridgeToken = Ui.app.secrets.get(SecretKeys.TELEGRAM_BRIDGE_TOKEN),
                )
                authOk = res.ok
                authStatus = res.detail
                busy = false
              }
            },
          ) {
            Text(stringResource(R.string.telegram_api_request_code))
          }
        }
        SettingText(
          label = stringResource(R.string.telegram_api_code),
          value = loginCode,
          number = true,
          placeholder = "12345",
        ) { loginCode = it }
        Button(
          enabled = !busy,
          onClick = {
            busy = true
            scope.launch {
              val res = TelegramUserApiSource.completeLogin(
                app = Ui.app,
                apiId = s.telegramApiId,
                apiHash = Ui.app.secrets.get(SecretKeys.TELEGRAM_API_HASH),
                phone = s.telegramPhone,
                code = loginCode,
                bridgeUrl = s.telegramApiBridgeUrl,
                bridgeToken = Ui.app.secrets.get(SecretKeys.TELEGRAM_BRIDGE_TOKEN),
                password2fa = Ui.app.secrets.get(SecretKeys.TELEGRAM_2FA_PASSWORD),
              )
              authOk = res.ok
              authStatus = res.detail
              if (res.ok) {
                runSafely { TelegramUserApiSource.syncDialogs(Ui.app) }
              }
              busy = false
            }
          },
        ) {
          Text(stringResource(R.string.telegram_api_connect_btn))
        }
        authStatus?.let { msg ->
          Text(
            text = (if (authOk) "✓ " else "✗ ") + msg,
            style = MaterialTheme.typography.bodySmall,
            color = if (authOk) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
          )
        }
      },
      InteractiveStep(
        title = stringResource(R.string.tg_step_filter),
        description = stringResource(R.string.tg_step_filter_desc),
        isCompleted = s.telegramDialogs.isNotEmpty(),
      ) {
        FilterModeSelector(
          mode = s.telegramFilterMode,
          onModeChange = { m -> Ui.update { it.copy(telegramFilterMode = m) } },
        )
        SettingSwitch(
          stringResource(R.string.telegram_rate_limit_switch),
          s.telegramRateLimitEnabled,
          stringResource(R.string.telegram_rate_limit_desc),
        ) { v -> Ui.update { it.copy(telegramRateLimitEnabled = v) } }
        if (s.telegramRateLimitEnabled) {
          SettingSlider(
            title = stringResource(R.string.rate_limit_per_min),
            value = s.telegramMaxMsgsPerMinute.toFloat(),
            range = 1f..30f,
            format = { "${it.roundToInt()} / Min." },
            subtitle = stringResource(R.string.rate_limit_per_min_desc),
          ) { v -> Ui.update { it.copy(telegramMaxMsgsPerMinute = v.roundToInt()) } }
        }
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = { triggerSync() },
            enabled = !isSyncing,
            modifier = Modifier.fillMaxWidth(),
          ) {
            if (isSyncing) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
              )
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.loading_dialogs_progress))
            } else {
              Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.telegram_sync_dialogs))
            }
          }
          OutlinedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.telegram_add_dialog))
          }
          syncNotice?.let { notice ->
            Text(
              text = notice,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        TelegramDialogChecklist(s)
      },
      InteractiveStep(
        title = stringResource(R.string.tg_step_enable),
        description = stringResource(R.string.tg_step_enable_desc),
        isCompleted = s.telegramApiEnabled && s.telegramApiConnectedUser.isNotBlank(),
      ) {
        SettingSwitch(
          stringResource(R.string.telegram_api_enabled),
          s.telegramApiEnabled,
          stringResource(R.string.telegram_api_enabled_body),
        ) { v -> Ui.update { it.copy(telegramApiEnabled = v) } }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SourceTestAction { SelfTest.checkTelegramUserApi(Ui.app) }
      },
    ),
  )

    // Connection & Credentials Card
    SettingGroupCard(title = stringResource(R.string.source_telegram_api_title), emphasized = true) {
      SettingSwitch(
        stringResource(R.string.telegram_api_enabled),
        s.telegramApiEnabled,
        stringResource(R.string.telegram_api_enabled_body),
      ) { v -> Ui.update { it.copy(telegramApiEnabled = v) } }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SettingText(
        label = stringResource(R.string.telegram_api_id),
        value = s.telegramApiId,
        number = true,
        placeholder = "12345678",
      ) { v -> Ui.update { it.copy(telegramApiId = v.trim()) } }
      SecretField(
        label = stringResource(R.string.telegram_api_hash),
        key = SecretKeys.TELEGRAM_API_HASH,
        supporting = stringResource(R.string.telegram_api_hash_hint),
      )
      SettingText(
        label = stringResource(R.string.telegram_api_phone),
        value = s.telegramPhone,
        placeholder = "+491701234567",
      ) { v -> Ui.update { it.copy(telegramPhone = v.trim()) } }
      SecretField(
        label = stringResource(R.string.telegram_2fa_password),
        key = SecretKeys.TELEGRAM_2FA_PASSWORD,
        supporting = stringResource(R.string.telegram_2fa_password_hint),
      )
      SettingText(
        label = stringResource(R.string.telegram_api_bridge_url),
        value = s.telegramApiBridgeUrl,
        placeholder = "http://127.0.0.1:8085",
        supporting = stringResource(R.string.telegram_api_bridge_hint),
      ) { v -> Ui.update { it.copy(telegramApiBridgeUrl = v.trim()) } }
      SecretField(
        label = stringResource(R.string.telegram_bridge_token),
        key = SecretKeys.TELEGRAM_BRIDGE_TOKEN,
        supporting = stringResource(R.string.telegram_bridge_token_hint),
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = {
            testingBridge = true
            scope.launch {
              bridgeTestResult = TelegramUserApiSource.testBridgeConnection(
                Ui.app,
                s.telegramApiBridgeUrl,
                Ui.app.secrets.get(SecretKeys.TELEGRAM_BRIDGE_TOKEN),
              )
              testingBridge = false
            }
          },
          enabled = !testingBridge,
          modifier = Modifier.weight(1f),
        ) {
          if (testingBridge) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.checking_progress))
          } else {
            Icon(Icons.Filled.NetworkCheck, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.telegram_test_bridge_btn))
          }
        }
        OutlinedButton(
          onClick = { showBridgeHelpModal = true },
          modifier = Modifier.weight(1f),
        ) {
          Icon(Icons.Filled.Terminal, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.telegram_bridge_script_btn))
        }
      }
      bridgeTestResult?.let { bRes ->
        Box(modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 4.dp)) {
          Card(
            colors = CardDefaults.cardColors(
              containerColor = if (bRes.ok) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = if (bRes.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (bRes.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
              )
              Column {
                Text(
                  text = if (bRes.ok) stringResource(R.string.telegram_bridge_reachable, bRes.latencyMs) else stringResource(R.string.telegram_bridge_unreachable),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = if (bRes.ok) Color(0xFF1B5E20) else MaterialTheme.colorScheme.error,
                )
                Text(
                  text = bRes.detail,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }
      }
      if (s.telegramApiConnectedUser.isNotBlank()) {
        Text(
          text = "✓ ${stringResource(R.string.telegram_api_connected_as, s.telegramApiConnectedUser)}",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFF2E7D32),
          modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 4.dp),
        )
      }
    }

    // Flood Protection & Group/Person Selection Card
    SettingGroupCard(title = stringResource(R.string.filter_groups_persons_title)) {
      SettingSwitch(
        stringResource(R.string.telegram_rate_limit_switch),
        s.telegramRateLimitEnabled,
        stringResource(R.string.telegram_rate_limit_desc),
      ) { v -> Ui.update { it.copy(telegramRateLimitEnabled = v) } }
      if (s.telegramRateLimitEnabled) {
        SettingSlider(
          title = stringResource(R.string.rate_limit_per_min),
          value = s.telegramMaxMsgsPerMinute.toFloat(),
          range = 1f..30f,
          format = { "${it.roundToInt()} / Min." },
          subtitle = stringResource(R.string.rate_limit_per_min_desc),
        ) { v -> Ui.update { it.copy(telegramMaxMsgsPerMinute = v.roundToInt()) } }
      }

      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner, vertical = 6.dp))

      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          stringResource(R.string.filter_mode_label),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        FilterModeSelector(
          mode = s.telegramFilterMode,
          onModeChange = { m -> Ui.update { it.copy(telegramFilterMode = m) } },
        )
        Text(
          text = stringResource(
            if (s.telegramFilterMode == FilterListMode.ALL_EXCEPT) {
              R.string.telegram_filter_all_except_help
            } else {
              R.string.telegram_filter_only_selected_help
            },
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = { triggerSync() },
            enabled = !isSyncing,
            modifier = Modifier.fillMaxWidth(),
          ) {
            if (isSyncing) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
              )
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.loading_dialogs_progress))
            } else {
              Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.telegram_sync_dialogs))
            }
          }
          OutlinedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.telegram_add_dialog))
          }
          syncNotice?.let { notice ->
            Text(
              text = notice,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        TimeWindowSelector(
          selectedHours = s.telegramTimeWindowHours,
          title = stringResource(R.string.tg_time_window),
          subtitle = stringResource(R.string.tg_time_window_desc),
          onSelect = { h -> Ui.update { it.copy(telegramTimeWindowHours = h) } },
        )

        TelegramDialogChecklist(s)
      }

      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SourceTestAction { SelfTest.checkTelegramUserApi(Ui.app) }
    }

  if (showAddDialog) {
    AddTelegramDialogModal(
      onDismiss = { showAddDialog = false },
      onAdd = { item ->
        Ui.update { st -> st.copy(telegramDialogs = st.telegramDialogs + item) }
        showAddDialog = false
      },
    )
  }

  if (showBridgeHelpModal) {
    TelegramBridgeHelpModal(onDismiss = { showBridgeHelpModal = false })
  }
}

/**
 * Group / person list for the Telegram filter. The whole row is tappable; the label next to the
 * checkbox tells what a check means in the current mode ("ausgeschlossen" vs. "ausgewertet").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TelegramDialogChecklist(s: AppSettings) {
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var kindFilter by rememberSaveable { mutableStateOf<TelegramDialogKind?>(null) }
  var quickAddTitle by rememberSaveable { mutableStateOf("") }
  var quickAddKind by rememberSaveable { mutableStateOf(TelegramDialogKind.GROUP) }
  var showImportModal by remember { mutableStateOf(false) }
  var quickNotice by remember { mutableStateOf<String?>(null) }
  val allExcept = s.telegramFilterMode == FilterListMode.ALL_EXCEPT
  val checkedLabel = if (allExcept) stringResource(R.string.tg_excluded) else stringResource(R.string.tg_analysed)
  val uncheckedLabel = if (allExcept) stringResource(R.string.tg_analysed) else stringResource(R.string.tg_ignored)

  fun setSelected(id: String, checked: Boolean) {
    Ui.update { st ->
      st.copy(telegramDialogs = st.telegramDialogs.map { if (it.id == id) it.copy(selected = checked) else it })
    }
  }

  fun addQuickDialog() {
    val clean = quickAddTitle.trim()
    if (clean.isBlank()) return
    val newItem = TelegramDialogItem(
      id = "tg_${clean.lowercase().hashCode()}",
      title = clean,
      username = if (clean.startsWith("@")) clean else "",
      kind = quickAddKind,
      selected = true,
    )
    Ui.update { st ->
      if (st.telegramDialogs.any { it.title.equals(clean, ignoreCase = true) }) {
        st.copy(telegramDialogs = st.telegramDialogs.map {
          if (it.title.equals(clean, ignoreCase = true)) it.copy(selected = true) else it
        })
      } else {
        st.copy(telegramDialogs = st.telegramDialogs + newItem)
      }
    }
    quickAddTitle = ""
  }

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val selectedCount = s.telegramDialogs.count { it.selected }
    Text(
      text = if (s.telegramDialogs.isEmpty()) stringResource(R.string.tg_groups_people) else
        stringResource(R.string.tg_groups_people_count, s.telegramDialogs.size, selectedCount, checkedLabel),
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(
        onClick = { showImportModal = true },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Filled.FileDownload, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.telegram_import_allowlist))
      }
    }

    // Inline quick-add row so groups/persons can always be added with 1 tap
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      OutlinedTextField(
        value = quickAddTitle,
        onValueChange = { quickAddTitle = it },
        singleLine = true,
        label = { Text(if (quickAddKind == TelegramDialogKind.GROUP) stringResource(R.string.tg_add_group) else stringResource(R.string.tg_add_person)) },
        placeholder = { Text(if (quickAddKind == TelegramDialogKind.GROUP) stringResource(R.string.tg_group_ph) else stringResource(R.string.tg_person_ph)) },
        modifier = Modifier.weight(1f),
      )
      FilterChip(
        selected = quickAddKind == TelegramDialogKind.GROUP,
        onClick = {
          quickAddKind = if (quickAddKind == TelegramDialogKind.GROUP) TelegramDialogKind.PERSON else TelegramDialogKind.GROUP
        },
        label = { Text(if (quickAddKind == TelegramDialogKind.GROUP) "Gruppe" else "Person") },
      )
      IconButton(
        enabled = quickAddTitle.isNotBlank(),
        onClick = { addQuickDialog() },
      ) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.telegram_add_dialog))
      }
    }
    if (s.telegramDialogs.isNotEmpty()) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
          onClick = { Ui.update { st -> st.copy(telegramDialogs = st.telegramDialogs.map { it.copy(selected = true) }) } },
          modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.select_all)) }
        TextButton(
          onClick = { Ui.update { st -> st.copy(telegramDialogs = st.telegramDialogs.map { it.copy(selected = false) }) } },
          modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.deselect_all)) }
        TextButton(
          onClick = { Ui.update { st -> st.copy(telegramDialogs = emptyList()) } },
          modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.clear_list)) }
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = kindFilter == null, onClick = { kindFilter = null }, label = { Text(stringResource(R.string.filter_all)) })
        FilterChip(
          selected = kindFilter == TelegramDialogKind.GROUP,
          onClick = { kindFilter = TelegramDialogKind.GROUP },
          label = { Text(stringResource(R.string.telegram_kind_group)) },
        )
        FilterChip(
          selected = kindFilter == TelegramDialogKind.PERSON,
          onClick = { kindFilter = TelegramDialogKind.PERSON },
          label = { Text(stringResource(R.string.telegram_kind_person)) },
        )
      }
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        singleLine = true,
        label = { Text(stringResource(R.string.search_groups_persons)) },
        placeholder = { Text(stringResource(R.string.tg_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        modifier = Modifier.fillMaxWidth(),
      )
    }

    TelegramDialogSuggestionsChips(
      existingDialogs = s.telegramDialogs,
      onAddDialog = { newDialog ->
        Ui.update { it.copy(telegramDialogs = it.telegramDialogs + newDialog) }
      },
    )

    val filtered = s.telegramDialogs.filter { d ->
      (kindFilter == null || d.kind == kindFilter || (kindFilter == TelegramDialogKind.GROUP && d.kind == TelegramDialogKind.CHANNEL)) &&
        (searchQuery.isBlank() || d.title.contains(searchQuery, ignoreCase = true) || d.username.contains(searchQuery, ignoreCase = true))
    }.sortedWith(compareBy<TelegramDialogItem> { it.kind == TelegramDialogKind.PERSON }.thenBy { it.title.lowercase() })

    if (filtered.isEmpty()) {
      Text(
        stringResource(R.string.telegram_no_dialogs_yet),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      filtered.forEach { dialog ->
        HorizontalDivider()
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { setSelected(dialog.id, !dialog.selected) }
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            imageVector = if (dialog.kind == TelegramDialogKind.PERSON) Icons.Filled.Person else Icons.Filled.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(dialog.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kindLabel = when (dialog.kind) {
              TelegramDialogKind.PERSON -> stringResource(R.string.telegram_kind_person)
              TelegramDialogKind.CHANNEL -> "Kanal"
              TelegramDialogKind.GROUP -> stringResource(R.string.telegram_kind_group)
            }
            val sub = listOfNotNull(
              kindLabel,
              dialog.username.takeIf { it.isNotBlank() }?.let { if (it.startsWith("@")) it else "@$it" },
              if (dialog.selected) checkedLabel else uncheckedLabel,
            ).joinToString(" · ")
            Text(
              sub,
              style = MaterialTheme.typography.bodySmall,
              color = if (dialog.selected && allExcept) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Checkbox(checked = dialog.selected, onCheckedChange = { setSelected(dialog.id, it) })
          IconButton(onClick = { Ui.update { st -> st.copy(telegramDialogs = st.telegramDialogs.filterNot { it.id == dialog.id }) } }) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
          }
        }
      }
    }
  }

  if (showImportModal) {
    ImportAllowlistModal(onDismiss = { showImportModal = false })
  }
}

@Composable
private fun FilterModeSelector(
  mode: FilterListMode,
  onModeChange: (FilterListMode) -> Unit,
) {
  SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    SegmentedButton(
      selected = mode == FilterListMode.ALL_EXCEPT,
      onClick = { onModeChange(FilterListMode.ALL_EXCEPT) },
      shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
    ) {
      Text(stringResource(R.string.filter_mode_all_except))
    }
    SegmentedButton(
      selected = mode == FilterListMode.ONLY_SELECTED,
      onClick = { onModeChange(FilterListMode.ONLY_SELECTED) },
      shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
    ) {
      Text(stringResource(R.string.filter_mode_only_selected))
    }
  }
}

private data class ContactSuggestionChip(
  val filterValue: String,
  val displayLabel: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSuggestionsChips(
  currentFilterText: String,
  filterPredicate: ((de.juliandigitalproducts.notitocal.data.LogEntry) -> Boolean)? = null,
  onUpdateFilterText: (String) -> Unit,
) {
  val entries by Ui.app.log.entries.collectAsState()
  val now = remember { System.currentTimeMillis() }
  val cutoff = remember(now) { now - (90L * 24 * 60 * 60 * 1000L) }
  val suggestions = remember(entries, cutoff) {
    val birthdayNames = mutableSetOf<String>()
    val birthdayRegex = Regex(
      """\b(?:geburtstag\s+von|hat(?:\s+(?:heute|morgen|übermorgen|bald|am\s+\d{1,2}\.?(?:\s+[a-zäöü]+)?))?\s+geburtstag|geburtstag(?:\s+hat|\s*:)?)\s+([A-ZÄÖÜ][a-zäöüß]+)|([A-ZÄÖÜ][a-zäöüß]+)\s+hat(?:\s+(?:heute|morgen|übermorgen|bald|am\s+\d{1,2}\.?(?:\s+[a-zäöü]+)?))?\s+geburtstag\b""",
      RegexOption.IGNORE_CASE
    )

    fun cleanSender(raw: String): String {
      var s = raw.trim()
      s = s.replace(Regex("""\s*\((?:\d+\s*(?:neue\s+)?(?:nachrichten?|messages?|msgs?|ungelesen|new)|[\+\d]+)\)""", RegexOption.IGNORE_CASE), "")
      s = s.replace(Regex("""\s*:\s*\d+\s*(?:neue\s+)?(?:nachrichten?|messages?)\s*$""", RegexOption.IGNORE_CASE), "")
      return s.trim()
    }

    entries.asSequence()
      .filter { it.created >= cutoff }
      .forEach { e ->
        birthdayRegex.findAll(e.text).forEach { m ->
          val n1 = m.groupValues[1].trim()
          val n2 = m.groupValues[2].trim()
          if (n1.isNotBlank()) birthdayNames.add(n1.lowercase())
          if (n2.isNotBlank()) birthdayNames.add(n2.lowercase())
        }
      }

    val chipsMap = mutableMapOf<ContactSuggestionChip, Int>()
    entries.asSequence()
      .filter { it.created >= cutoff }
      .filter { filterPredicate?.invoke(it) ?: true }
      .map { cleanSender(it.sender) }
      .filter { it.length >= 2 && !it.startsWith("+") }
      .forEach { raw ->
        if (raw.contains(" @ ") || raw.contains(" › ")) {
          val sep = if (raw.contains(" @ ")) " @ " else " › "
          val person = raw.substringBefore(sep).trim()
          val group = raw.substringAfter(sep).trim()
          if (group.isNotBlank() && person.isNotBlank()) {
            val linked = ContactSuggestionChip(filterValue = group, displayLabel = "$group › $person")
            chipsMap[linked] = (chipsMap[linked] ?: 0) + 3
            if (!birthdayNames.contains(person.lowercase())) {
              val indPerson = ContactSuggestionChip(filterValue = person, displayLabel = person)
              chipsMap[indPerson] = (chipsMap[indPerson] ?: 0) + 1
            }
            val grpOnly = ContactSuggestionChip(filterValue = group, displayLabel = group)
            chipsMap[grpOnly] = (chipsMap[grpOnly] ?: 0) + 1
          } else {
            val single = raw.ifBlank { group.ifBlank { person } }
            if (single.isNotBlank() && !birthdayNames.contains(single.lowercase())) {
              val c = ContactSuggestionChip(filterValue = single, displayLabel = single)
              chipsMap[c] = (chipsMap[c] ?: 0) + 1
            }
          }
        } else {
          if (!birthdayNames.contains(raw.lowercase())) {
            val c = ContactSuggestionChip(filterValue = raw, displayLabel = raw)
            chipsMap[c] = (chipsMap[c] ?: 0) + 1
          }
        }
      }

    chipsMap.entries
      .sortedByDescending { it.value }
      .take(14)
      .map { it.key }
  }

  if (suggestions.isNotEmpty()) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp)) {
      Text(
        text = stringResource(R.string.filter_suggestions_title),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(4.dp))
      val existingItems = remember(currentFilterText) {
        currentFilterText.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        suggestions.forEach { chip ->
          val isSelected = chip.filterValue.lowercase() in existingItems
          FilterChip(
            selected = isSelected,
            onClick = {
              val currentList = currentFilterText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
              if (isSelected) {
                currentList.removeAll { it.equals(chip.filterValue, ignoreCase = true) }
              } else {
                currentList.add(chip.filterValue)
              }
              onUpdateFilterText(currentList.joinToString(", "))
            },
            label = { Text(chip.displayLabel, style = MaterialTheme.typography.labelSmall) },
            leadingIcon = if (isSelected) {
              { Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp)) }
            } else null,
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TelegramDialogSuggestionsChips(
  existingDialogs: List<TelegramDialogItem>,
  onAddDialog: (TelegramDialogItem) -> Unit,
) {
  val entries by Ui.app.log.entries.collectAsState()
  val now = remember { System.currentTimeMillis() }
  val cutoff = remember(now) { now - (90L * 24 * 60 * 60 * 1000L) }
  val suggestions = remember(entries, cutoff) {
    entries.asSequence()
      .filter { it.created >= cutoff }
      .filter { it.source == SourceType.TELEGRAM || it.source == SourceType.TELEGRAM_API || it.packageName in TelegramUserApiSource.TELEGRAM_PACKAGES }
      .map { it.sender.trim() }
      .filter { it.length >= 2 && !it.startsWith("+") }
      .groupingBy { it }
      .eachCount()
      .entries
      .sortedByDescending { it.value }
      .take(10)
      .map { it.key }
  }

  val existingTitles = remember(existingDialogs) {
    existingDialogs.map { it.title.trim().lowercase() }.toSet()
  }

  val available = suggestions.filter { it.lowercase() !in existingTitles }

  if (available.isNotEmpty()) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp)) {
      Text(
        text = stringResource(R.string.filter_suggestions_title),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(4.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        available.forEach { name ->
          FilterChip(
            selected = false,
            onClick = {
              onAddDialog(TelegramDialogItem(title = name, kind = TelegramDialogKind.GROUP, selected = true))
            },
            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
            leadingIcon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(14.dp)) },
          )
        }
      }
    }
  }
}

@Composable
private fun AddTelegramDialogModal(
  onDismiss: () -> Unit,
  onAdd: (TelegramDialogItem) -> Unit,
) {
  var title by remember { mutableStateOf("") }
  var username by remember { mutableStateOf("") }
  var kind by remember { mutableStateOf(TelegramDialogKind.GROUP) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.telegram_add_dialog)) },
    confirmButton = {
      Button(
        enabled = title.isNotBlank() || username.isNotBlank(),
        onClick = {
          onAdd(
            TelegramDialogItem(
              title = title.trim().ifBlank { username.trim() },
              username = username.trim(),
              kind = kind,
              selected = true,
            ),
          )
        },
      ) { Text(stringResource(R.string.alias_save)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          SegmentedButton(
            selected = kind == TelegramDialogKind.GROUP,
            onClick = { kind = TelegramDialogKind.GROUP },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          ) { Text(stringResource(R.string.telegram_kind_group)) }
          SegmentedButton(
            selected = kind == TelegramDialogKind.PERSON,
            onClick = { kind = TelegramDialogKind.PERSON },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          ) { Text(stringResource(R.string.telegram_kind_person)) }
        }
        SettingText(
          label = stringResource(R.string.field_title),
          value = title,
          placeholder = if (kind == TelegramDialogKind.GROUP) stringResource(R.string.tg_group_ph2) else stringResource(R.string.tg_person_ph),
        ) { title = it }
        SettingText(
          label = "@Username / Chat-ID (optional)",
          value = username,
          placeholder = "@username",
        ) { username = it }
      }
    },
  )
}

@Composable
private fun ImportAllowlistModal(onDismiss: () -> Unit) {
  var jsonText by remember { mutableStateOf("") }
  var errorMsg by remember { mutableStateOf<String?>(null) }
  val sampleJson = """{
  "-1001234567890": "Projekt-Team",
  "-1009876543210": "Familienchat",
  "123456789": "Max Mustermann"
}"""

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.telegram_import_allowlist)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          stringResource(R.string.tg_import_hint),
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
          onClick = { jsonText = sampleJson },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Filled.ContentPaste, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.tg_import_example))
        }
        OutlinedTextField(
          value = jsonText,
          onValueChange = {
            jsonText = it
            errorMsg = null
          },
          label = { Text("allowlist.json / Text") },
          placeholder = { Text(sampleJson) },
          modifier = Modifier.fillMaxWidth().height(180.dp),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        errorMsg?.let { err ->
          Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val imported = TelegramUserApiSource.importFromAllowlist(jsonText)
          if (imported.isEmpty()) {
            errorMsg = Ui.app.getString(R.string.tg_import_none)
          } else {
            Ui.update { st ->
              val existingIds = st.telegramDialogs.map { it.id }.toSet()
              val existingTitles = st.telegramDialogs.map { it.title.lowercase().trim() }.toSet()
              val toAdd = imported.filterNot { it.id in existingIds || it.title.lowercase().trim() in existingTitles }
              st.copy(telegramDialogs = st.telegramDialogs + toAdd)
            }
            onDismiss()
          }
        },
      ) {
        Text(stringResource(R.string.import_btn))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TelegramBridgeHelpModal(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf<String?>(null) }
  var selectedLang by rememberSaveable { mutableStateOf(TelegramBridgeExporter.detectDefaultLanguageCode()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Terminal, null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.telegram_bridge_help_title))
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          stringResource(R.string.telegram_bridge_help_intro),
          style = MaterialTheme.typography.bodyMedium,
        )

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            stringResource(R.string.telegram_bridge_script_language),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            TelegramBridgeExporter.SUPPORTED_LANGUAGES.forEach { lang ->
              FilterChip(
                selected = selectedLang == lang.code,
                onClick = { selectedLang = lang.code },
                label = { Text(lang.code.uppercase()) },
              )
            }
          }
        }

        Text(stringResource(R.string.telegram_download_script_btn), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = {
              val msg = TelegramBridgeExporter.saveScriptToDownloads(context, langCode = selectedLang).second
              copied = msg
            },
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Filled.FileDownload, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("${stringResource(R.string.telegram_bridge_save_to_downloads)} (${selectedLang.uppercase()})", maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          OutlinedButton(
            onClick = { TelegramBridgeExporter.shareScript(context, langCode = selectedLang) },
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.telegram_share_script_btn), maxLines = 1)
          }
        }

        OutlinedButton(
          onClick = {
            val msg = TelegramBridgeExporter.saveAllLanguagesToDownloads(context).second
            copied = msg
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Filled.FileDownload, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.telegram_download_all_languages_btn))
        }

        OutlinedButton(
          onClick = {
            TelegramBridgeExporter.copyScriptToClipboard(context, langCode = selectedLang)
            copied = resources.getString(R.string.telegram_bridge_code_copied_feedback)
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.telegram_bridge_copy_all_code))
        }

        Text(stringResource(R.string.telegram_bridge_step1_title), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          modifier = Modifier.fillMaxWidth().clickable {
            clipboard.setText(AnnotatedString("pkg update && pkg install python && pip install telethon"))
            copied = resources.getString(R.string.telegram_bridge_pip_copied_feedback)
          },
        ) {
          Text("pkg update && pkg install python && pip install telethon", modifier = Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.telegram_bridge_step2_title), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          modifier = Modifier.fillMaxWidth().clickable {
            clipboard.setText(AnnotatedString("python ~/storage/downloads/notitocal_telegram_bridge.py"))
            copied = resources.getString(R.string.telegram_bridge_start_copied_feedback)
          },
        ) {
          Text("python ~/storage/downloads/notitocal_telegram_bridge.py", modifier = Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.telegram_bridge_step3_title), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Text(
          stringResource(R.string.telegram_bridge_step3_desc),
          style = MaterialTheme.typography.bodySmall,
        )
        copied?.let { msg ->
          Text("✓ $msg", color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(onClick = {
        val msg = TelegramBridgeExporter.saveScriptToDownloads(context, langCode = selectedLang).second
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        onDismiss()
      }) {
        Icon(Icons.Filled.FileDownload, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.telegram_bridge_save_and_done))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.close))
      }
    },
  )
}

// 3. Telegram-Bot & Combined Submenu
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TelegramCombinedSubScreen(
  onBack: () -> Unit,
  onOpen: (String) -> Unit = {},
  initialTab: Int = 0,
) {
  var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
  val s by Ui.app.settings.state.collectAsState()

  val notifActive = s.notificationsEnabled && TelegramUserApiSource.TELEGRAM_PACKAGES.any { it in s.monitoredApps }
  val botActive = s.telegramEnabled
  val apiActive = s.telegramApiEnabled && s.telegramApiConnectedUser.isNotBlank()
  val activeCount = listOf(notifActive, botActive, apiActive).count { it }

  SubScreen(stringResource(R.string.source_telegram), onBack) {
    if (activeCount > 1) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
          Spacer(Modifier.width(10.dp))
          Text(
            text = stringResource(R.string.telegram_multi_hint, activeCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }
    }

    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
      Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(
          text = stringResource(R.string.telegram_config_notice),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    SingleChoiceSegmentedButtonRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
      SegmentedButton(
        selected = selectedTab == 0,
        onClick = { selectedTab = 0 },
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
        icon = {
          if (notifActive) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.status_active_short), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
          } else {
            Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
          }
        },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
      ) {
        Text(stringResource(R.string.source_mode_notification), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
      }
      SegmentedButton(
        selected = selectedTab == 1,
        onClick = { selectedTab = 1 },
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
        icon = {
          if (botActive) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.status_active_short), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
          } else {
            Icon(Icons.Filled.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
          }
        },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
      ) {
        Text("Bot", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
      }
      SegmentedButton(
        selected = selectedTab == 2,
        onClick = { selectedTab = 2 },
        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
        icon = {
          if (apiActive) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.status_active_short), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
          } else {
            Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(16.dp))
          }
        },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
      ) {
        Text("API", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
      }
    }

    when (selectedTab) {
      0 -> TelegramNotificationsContent(onOpen = onOpen)
      1 -> TelegramBotContent(onOpen = onOpen)
      2 -> TelegramUserApiContent(onOpen = onOpen)
    }
  }
}

@Composable
private fun TelegramNotificationsContent(onOpen: (String) -> Unit = {}) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val tick = rememberResumeTick()
  val access = remember(tick) { NotificationCaptureService.isEnabled(context) }
  val isTgMonitored = TelegramUserApiSource.TELEGRAM_PACKAGES.any { it in s.monitoredApps }

  InteractiveSetupWizardCard(
    wizardTitle = stringResource(R.string.tg_notif_options_title),
    steps = listOf(
      InteractiveStep(
        title = stringResource(R.string.tg_notif_step_capture),
        description = stringResource(R.string.tg_notif_step_capture_body),
        isCompleted = s.notificationsEnabled && isTgMonitored,
      ) {
        SettingSwitch(stringResource(R.string.tg_notif_options_title), s.notificationsEnabled && isTgMonitored) { v ->
          Ui.update {
            val apps = if (v) it.monitoredApps + TelegramUserApiSource.TELEGRAM_PACKAGES else it.monitoredApps - TelegramUserApiSource.TELEGRAM_PACKAGES
            it.copy(
              notificationsEnabled = if (v) true else it.notificationsEnabled,
              monitoredApps = apps,
            )
          }
        }
      },
      InteractiveStep(
        title = stringResource(R.string.perm_listener),
        description = stringResource(R.string.perm_listener_body),
        isCompleted = access,
      ) {
        PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_body), access) {
          Ui.openNotificationAccess(context)
        }
      },
      InteractiveStep(
        title = stringResource(R.string.filter_groups_persons_title),
        description = stringResource(R.string.tg_notif_step_filter_body),
        isCompleted = true,
      ) {
        FilterModeSelector(
          mode = s.telegramFilterMode,
          onModeChange = { m -> Ui.update { it.copy(telegramFilterMode = m) } },
        )
      },
    ),
  )

  SettingGroupCard(title = stringResource(R.string.tg_notif_options_title), emphasized = true) {
    SettingSwitch(
      title = stringResource(R.string.tg_notif_active),
      checked = isTgMonitored,
      subtitle = stringResource(R.string.tg_notif_active_body),
    ) { v ->
      Ui.update {
        val apps = if (v) it.monitoredApps + TelegramUserApiSource.TELEGRAM_PACKAGES else it.monitoredApps - TelegramUserApiSource.TELEGRAM_PACKAGES
        it.copy(monitoredApps = apps)
      }
    }

    SettingSwitch(
      title = stringResource(R.string.telegram_rate_limit_switch),
      checked = s.telegramRateLimitEnabled,
      subtitle = stringResource(R.string.telegram_rate_limit_desc),
    ) { v -> Ui.update { it.copy(telegramRateLimitEnabled = v) } }

    if (s.telegramRateLimitEnabled) {
      SettingSlider(
        title = stringResource(R.string.tg_max_per_minute),
        value = s.telegramMaxMsgsPerMinute.toFloat(),
        range = 5f..120f,
        steps = 22,
        format = { "${it.toInt()} / Min" },
      ) { v -> Ui.update { it.copy(telegramMaxMsgsPerMinute = v.toInt()) } }
    }
  }

  SettingGroupCard(title = stringResource(R.string.filter_groups_persons_title)) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(stringResource(R.string.filter_mode_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      FilterModeSelector(
        mode = s.telegramFilterMode,
        onModeChange = { m -> Ui.update { it.copy(telegramFilterMode = m) } },
      )
      TelegramDialogChecklist(s)
    }
  }
}

@Composable
private fun TelegramUserApiSubScreen(onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
  TelegramCombinedSubScreen(onBack = onBack, onOpen = onOpen, initialTab = 2)
}

@Composable
private fun TelegramBotSubScreen(onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
  TelegramCombinedSubScreen(onBack = onBack, onOpen = onOpen, initialTab = 1)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TelegramBotContent(onOpen: (String) -> Unit = {}) {
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var isSyncing by remember { mutableStateOf(false) }
  var syncNotice by remember { mutableStateOf<String?>(null) }
  var showAddDialog by remember { mutableStateOf(false) }
  var bridgeTestResult by remember { mutableStateOf<TelegramUserApiSource.BridgeCheckResult?>(null) }
  var testingBridge by remember { mutableStateOf(false) }
  var showBridgeHelpModal by remember { mutableStateOf(false) }

  androidx.compose.runtime.LaunchedEffect(Unit) {
    runSafely { TelegramUserApiSource.syncDialogs(Ui.app) }
  }

  Hint(stringResource(R.string.telegram_mode_switcher_info))
  FlowRow(
    modifier = Modifier.padding(horizontal = Gaps.Inner),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    OutlinedButton(onClick = { onOpen("apps") }) {
      Text(stringResource(R.string.monitored_apps))
    }
  }
    InteractiveSetupWizardCard(
      wizardTitle = stringResource(R.string.wizard_interactive_title),
      steps = listOf(
        InteractiveStep(
          title = stringResource(R.string.tg_bot_step_create),
          description = stringResource(R.string.telegram_guide),
          isCompleted = Ui.app.secrets.get(SecretKeys.TELEGRAM_BOT).isNotBlank(),
        ) {
          SettingSwitch(stringResource(R.string.telegram_enabled), s.telegramEnabled) { v ->
            Ui.update { it.copy(telegramEnabled = v) }
          }
          SecretField(
            label = stringResource(R.string.telegram_token),
            key = SecretKeys.TELEGRAM_BOT,
            supporting = stringResource(R.string.telegram_token_hint),
          )
        },
        InteractiveStep(
          title = stringResource(R.string.telegram_chat_id),
          description = stringResource(R.string.telegram_chat_id_hint),
          isCompleted = s.telegramEnabled && Ui.app.secrets.get(SecretKeys.TELEGRAM_BOT).isNotBlank(),
        ) {
          SettingText(
            label = stringResource(R.string.telegram_chat_id),
            value = s.telegramChatId,
            placeholder = "123456789",
          ) { v -> Ui.update { it.copy(telegramChatId = v.trim()) } }
          SourceTestAction {
            val step = SelfTest.checkTelegram(Ui.app)
            if (step.passed && s.telegramChatId.isBlank()) {
              val token = Ui.app.secrets.get(SecretKeys.TELEGRAM_BOT)
              val disc = TelegramSource.testConnection(token, s.telegramChatId).discoveredChatId
              if (!disc.isNullOrBlank()) Ui.update { it.copy(telegramChatId = disc) }
            }
            step
          }
        },
      ),
    )

    SettingGroupCard(title = stringResource(R.string.source_telegram), emphasized = true) {
      Hint(stringResource(R.string.telegram_intro))
      SettingSwitch(
        stringResource(R.string.telegram_enabled),
        s.telegramEnabled,
        stringResource(R.string.telegram_enabled_body),
      ) { v -> Ui.update { it.copy(telegramEnabled = v) } }
      SecretField(
        label = stringResource(R.string.telegram_token),
        key = SecretKeys.TELEGRAM_BOT,
        supporting = stringResource(R.string.telegram_token_hint),
      )
      SettingText(
        label = stringResource(R.string.telegram_chat_id),
        value = s.telegramChatId,
        placeholder = "123456789",
        supporting = stringResource(R.string.telegram_chat_id_hint),
      ) { v -> Ui.update { it.copy(telegramChatId = v.trim()) } }
      Hint(stringResource(R.string.telegram_guide))
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SourceTestAction {
        val step = SelfTest.checkTelegram(Ui.app)
        if (step.passed && s.telegramChatId.isBlank()) {
          val token = Ui.app.secrets.get(SecretKeys.TELEGRAM_BOT)
          val disc = TelegramSource.testConnection(token, s.telegramChatId).discoveredChatId
          if (!disc.isNullOrBlank()) Ui.update { it.copy(telegramChatId = disc) }
        }
        runSafely { TelegramUserApiSource.syncDialogs(Ui.app) }
        step
      }
    }

    // Shared Group & Person Selection Card in Telegram Bot submenu as well
    SettingGroupCard(title = stringResource(R.string.filter_groups_persons_title)) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          stringResource(R.string.filter_mode_label),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        FilterModeSelector(
          mode = s.telegramFilterMode,
          onModeChange = { m -> Ui.update { it.copy(telegramFilterMode = m) } },
        )
        Text(
          text = stringResource(
            if (s.telegramFilterMode == FilterListMode.ALL_EXCEPT) {
              R.string.telegram_filter_all_except_help
            } else {
              R.string.telegram_filter_only_selected_help
            },
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
          onClick = {
            isSyncing = true
            syncNotice = null
            scope.launch {
              try {
                syncNotice = TelegramUserApiSource.syncDialogs(Ui.app).summary(Ui.app)
              } catch (e: Exception) {
                syncNotice = Ui.app.getString(R.string.error_detail, e.message.orEmpty())
              } finally {
                isSyncing = false
              }
            }
          },
          enabled = !isSyncing,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (isSyncing) stringResource(R.string.loading_dialogs_progress) else stringResource(R.string.telegram_sync_dialogs))
        }
        OutlinedButton(
          onClick = { showAddDialog = true },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.telegram_add_dialog))
        }
        syncNotice?.let { notice ->
          Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        TelegramDialogChecklist(s)
      }
    }

  if (showAddDialog) {
    AddTelegramDialogModal(
      onDismiss = { showAddDialog = false },
      onAdd = { item ->
        Ui.update { st -> st.copy(telegramDialogs = st.telegramDialogs + item) }
        showAddDialog = false
      },
    )
  }

  if (showBridgeHelpModal) {
    TelegramBridgeHelpModal(onDismiss = { showBridgeHelpModal = false })
  }
}

// 4. General Messenger & System Notifications Submenu (including SMS & RCS)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsSourceSubScreen(
  onBack: () -> Unit,
  onOpen: (String) -> Unit,
  initialTab: Int = 0,
) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val tick = rememberResumeTick()
  val access = remember(tick) { NotificationCaptureService.isEnabled(context) }
  var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }

  SubScreen(
    title = if (selectedTab == 0) stringResource(R.string.source_notifications) else stringResource(R.string.source_sms_rcs_title),
    onBack = onBack,
  ) {
    SingleChoiceSegmentedButtonRow(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
      SegmentedButton(
        selected = selectedTab == 0,
        onClick = { selectedTab = 0 },
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        icon = { Icon(Icons.Filled.Notifications, null, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Text(
          text = stringResource(R.string.source_notifications),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.labelMedium,
        )
      }
      SegmentedButton(
        selected = selectedTab == 1,
        onClick = { selectedTab = 1 },
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        icon = { Icon(Icons.Filled.Sms, null, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
      ) {
        Text(
          text = stringResource(R.string.source_sms_rcs_title),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }

    when (selectedTab) {
      0 -> {
        InteractiveSetupWizardCard(
          wizardTitle = stringResource(R.string.wizard_interactive_title),
          steps = listOf(
            InteractiveStep(
              title = stringResource(R.string.perm_listener),
              description = stringResource(R.string.perm_listener_body),
              isCompleted = access,
            ) {
              PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_body), access) {
                Ui.openNotificationAccess(context)
              }
            },
            InteractiveStep(
              title = stringResource(R.string.monitored_apps),
              description = stringResource(R.string.wizard_apps_body),
              isCompleted = s.monitoredApps.isNotEmpty(),
            ) {
              Button(onClick = { onOpen("apps") }) {
                Text(stringResource(R.string.monitored_apps))
              }
            },
            InteractiveStep(
              title = stringResource(R.string.source_test_button),
              description = stringResource(R.string.diagnose_body),
              isCompleted = s.notificationsEnabled && access,
            ) {
              SourceTestAction { SelfTest.checkNotifications(Ui.app) }
            },
          ),
        )

        SettingGroupCard(title = stringResource(R.string.source_notifications), emphasized = true) {
          SettingSwitch(
            stringResource(R.string.source_notifications),
            s.notificationsEnabled,
          ) { v -> Ui.update { it.copy(notificationsEnabled = v) } }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_body), access) {
            Ui.openNotificationAccess(context)
          }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SettingNav(
            stringResource(R.string.monitored_apps),
            installedCount(s.monitoredApps).let { n -> pluralStringResource(R.plurals.summary_apps, n, n) },
          ) { onOpen("apps") }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SettingSwitch(
            stringResource(R.string.ignore_groups),
            s.ignoreGroupChats,
            stringResource(R.string.ignore_groups_body),
          ) { v -> Ui.update { it.copy(ignoreGroupChats = v) } }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SourceTestAction { SelfTest.checkNotifications(Ui.app) }
        }
      }
      1 -> {
        InteractiveSetupWizardCard(
          wizardTitle = stringResource(R.string.wz_sms_title),
          steps = listOf(
            InteractiveStep(
              title = stringResource(R.string.source_sms_rcs_title),
              description = stringResource(R.string.source_sms_rcs_sub),
              isCompleted = s.smsRcsEnabled && access,
            ) {
              SettingSwitch(stringResource(R.string.source_sms_rcs_title), s.smsRcsEnabled) { v ->
                Ui.update { st ->
                  val apps = if (v) st.monitoredApps + SmsRcsSource.PACKAGES else st.monitoredApps - SmsRcsSource.PACKAGES
                  st.copy(smsRcsEnabled = v, monitoredApps = apps)
                }
              }
              PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_body), access) {
                Ui.openNotificationAccess(context)
              }
            },
            InteractiveStep(
              title = stringResource(R.string.sms_ignore_2fa),
              description = stringResource(R.string.sms_ignore_2fa_desc),
              isCompleted = true,
            ) {
              SettingSwitch(stringResource(R.string.sms_ignore_2fa), s.smsIgnore2fa, stringResource(R.string.sms_ignore_2fa_desc)) { v ->
                Ui.update { it.copy(smsIgnore2fa = v) }
              }
            },
            InteractiveStep(
              title = stringResource(R.string.filter_groups_persons_title),
              description = stringResource(R.string.filter_comma_hint),
              isCompleted = true,
            ) {
              FilterModeSelector(
                mode = s.smsFilterMode,
                onModeChange = { m -> Ui.update { it.copy(smsFilterMode = m) } },
              )
              SettingText(
                label = stringResource(
                  if (s.smsFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_excluded_names else R.string.filter_allowed_names,
                ),
                value = s.smsFilterSenders,
                placeholder = stringResource(R.string.sms_filter_ph),
              ) { v -> Ui.update { it.copy(smsFilterSenders = v) } }
              SourceTestAction { SelfTest.checkSmsRcs(Ui.app) }
            },
          ),
        )

        SettingGroupCard(title = stringResource(R.string.source_sms_rcs_title), emphasized = true) {
          SettingSwitch(stringResource(R.string.source_sms_rcs_title), s.smsRcsEnabled, stringResource(R.string.source_sms_rcs_sub)) { v ->
            Ui.update { st ->
              val apps = if (v) st.monitoredApps + SmsRcsSource.PACKAGES else st.monitoredApps - SmsRcsSource.PACKAGES
              st.copy(smsRcsEnabled = v, monitoredApps = apps)
            }
          }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SettingSwitch(stringResource(R.string.sms_ignore_2fa), s.smsIgnore2fa, stringResource(R.string.sms_ignore_2fa_desc)) { v ->
            Ui.update { it.copy(smsIgnore2fa = v) }
          }
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          Column(Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
            FilterModeSelector(
              mode = s.smsFilterMode,
              onModeChange = { m -> Ui.update { it.copy(smsFilterMode = m) } },
            )
          }
          SettingText(
            label = stringResource(
              if (s.smsFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_excluded_names else R.string.filter_allowed_names,
            ),
            value = s.smsFilterSenders,
            placeholder = stringResource(R.string.sms_filter_ph2),
            supporting = stringResource(R.string.filter_comma_hint),
          ) { v -> Ui.update { it.copy(smsFilterSenders = v) } }
          FilterSuggestionsChips(
            currentFilterText = s.smsFilterSenders,
            filterPredicate = { it.source == SourceType.SMS_RCS || it.packageName in setOf("com.google.android.apps.messaging", "com.samsung.android.messaging") },
            onUpdateFilterText = { v -> Ui.update { it.copy(smsFilterSenders = v) } },
          )
          HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
          SourceTestAction { SelfTest.checkSmsRcs(Ui.app) }
        }
      }
    }
  }
}

// 5. IMAP E-Mail (Read-Only, Multi-Account) Submenu
@Composable
private fun ImapSourceSubScreen(onBack: () -> Unit) {
  val s by Ui.app.settings.state.collectAsState()
  var editingImap by remember { mutableStateOf<ImapAccount?>(null) }

  SubScreen(stringResource(R.string.source_imap), onBack) {
    InteractiveSetupWizardCard(
      wizardTitle = stringResource(R.string.wizard_interactive_title),
      steps = listOf(
        InteractiveStep(
          title = stringResource(R.string.imap_enabled),
          description = stringResource(R.string.imap_intro),
          isCompleted = s.imapEnabled,
        ) {
          SettingSwitch(stringResource(R.string.imap_enabled), s.imapEnabled) { v ->
            Ui.update { it.copy(imapEnabled = v) }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.imap_add_account),
          description = stringResource(R.string.app_password_hint),
          isCompleted = s.imapAccounts.isNotEmpty(),
        ) {
          Button(onClick = { editingImap = ImapAccount() }) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.imap_add_account))
          }
        },
        InteractiveStep(
          title = stringResource(R.string.source_test_button),
          description = stringResource(R.string.imap_enabled_body),
          isCompleted = s.imapAccounts.any { it.enabled },
        ) {
          SourceTestAction { SelfTest.checkImap(Ui.app) }
        },
      ),
    )

    SettingGroupCard(title = stringResource(R.string.source_imap), emphasized = true) {
      Hint(stringResource(R.string.imap_intro))
      SettingSwitch(
        stringResource(R.string.imap_enabled),
        s.imapEnabled,
        stringResource(R.string.imap_enabled_body),
      ) { v -> Ui.update { it.copy(imapEnabled = v) } }
      TimeWindowSelector(
        selectedHours = s.imapTimeWindowHours,
        title = stringResource(R.string.imap_time_window),
        subtitle = stringResource(R.string.imap_time_window_desc),
        onSelect = { h -> Ui.update { it.copy(imapTimeWindowHours = h) } },
      )

      s.imapAccounts.forEach { acc ->
        HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
        ImapAccountRow(
          account = acc,
          onToggle = { on ->
            Ui.update { st ->
              st.copy(imapAccounts = st.imapAccounts.map { if (it.id == acc.id) it.copy(enabled = on) else it })
            }
          },
          onEdit = { editingImap = acc },
          onDelete = {
            Ui.app.secrets.put(SecretKeys.imap(acc.id), "")
            Ui.update { st -> st.copy(imapAccounts = st.imapAccounts.filterNot { it.id == acc.id }) }
          },
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = { editingImap = ImapAccount() },
          modifier = Modifier.weight(1f),
        ) {
          Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.imap_add_account))
        }
      }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SourceTestAction { SelfTest.checkImap(Ui.app) }
    }
  }

  editingImap?.let { acc ->
    ImapAccountDialog(
      initial = acc,
      onDismiss = { editingImap = null },
      onSave = { saved, password ->
        if (password != null) Ui.app.secrets.put(SecretKeys.imap(saved.id), password)
        Ui.update { st ->
          val exists = st.imapAccounts.any { it.id == saved.id }
          val nextList = if (exists) st.imapAccounts.map { if (it.id == saved.id) saved else it } else st.imapAccounts + saved
          st.copy(imapAccounts = nextList, imapEnabled = true)
        }
        editingImap = null
      },
    )
  }
}

// 6. SMS & RCS Submenu (forwarder to combined Notifications & SMS/RCS screen)
@Composable
private fun SmsRcsSourceSubScreen(onBack: () -> Unit) {
  NotificationsSourceSubScreen(onBack = onBack, onOpen = {}, initialTab = 1)
}

// 7. Screenshots & FotoTermin Submenu
@Composable
private fun ScreenshotsSourceSubScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val tick = rememberResumeTick()
  var refresh by remember { mutableIntStateOf(0) }
  val mediaGranted = remember(tick, refresh) { Ui.granted(context, Ui.imagePermission) }
  val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    refresh++
    if (ok) Ui.update { it.copy(screenshotsEnabled = true) }
  }

  SubScreen(stringResource(R.string.source_screenshots), onBack) {
    InteractiveSetupWizardCard(
      wizardTitle = stringResource(R.string.wizard_interactive_title),
      steps = listOf(
        InteractiveStep(
          title = stringResource(R.string.screenshots_enabled),
          description = stringResource(R.string.screenshots_body),
          isCompleted = s.screenshotsEnabled && mediaGranted,
        ) {
          SettingSwitch(
            stringResource(R.string.screenshots_enabled),
            s.screenshotsEnabled && mediaGranted,
          ) { v ->
            if (v && !mediaGranted) mediaLauncher.launch(Ui.imagePermission) else Ui.update { it.copy(screenshotsEnabled = v) }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.widget_photo_event),
          description = stringResource(R.string.source_screenshots_sub),
          isCompleted = true,
        ) {
          Button(
            onClick = {
              runSafely {
                context.startActivity(
                  Intent(context, QuickCaptureActivity::class.java).apply {
                    action = QuickCaptureActivity.ACTION_QUICK_CAMERA
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                  },
                )
              }
            },
          ) {
            Icon(Icons.Filled.CameraAlt, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.widget_photo_event))
          }
        },
        InteractiveStep(
          title = stringResource(R.string.source_test_button),
          description = stringResource(R.string.selftest_ocr),
          isCompleted = s.screenshotsEnabled && mediaGranted,
        ) {
          SourceTestAction { SelfTest.checkScreenshots(Ui.app) }
        },
      ),
    )

    SettingGroupCard(title = stringResource(R.string.source_screenshots), emphasized = true) {
      SettingSwitch(
        stringResource(R.string.screenshots_enabled),
        s.screenshotsEnabled && mediaGranted,
        stringResource(R.string.screenshots_body),
      ) { v ->
        if (v && !mediaGranted) mediaLauncher.launch(Ui.imagePermission) else Ui.update { it.copy(screenshotsEnabled = v) }
      }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SettingSwitch(
        stringResource(R.string.analyze_notification_images),
        s.analyzeNotificationImages,
        stringResource(R.string.analyze_notification_images_desc),
      ) { v ->
        Ui.update { it.copy(analyzeNotificationImages = v) }
      }
      if (s.analyzeNotificationImages) {
        SettingSwitch(
          stringResource(R.string.notification_images_to_ai),
          s.notificationImagesToAi,
          stringResource(R.string.notification_images_to_ai_desc),
        ) { v ->
          Ui.update { it.copy(notificationImagesToAi = v) }
        }
      }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      Column(Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
        Text(
          stringResource(R.string.ocr_quick_switch_title),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          OcrEngineMode.entries.forEachIndexed { idx, mode ->
            SegmentedButton(
              selected = s.ocrEngineMode == mode,
              onClick = { Ui.update { it.copy(ocrEngineMode = mode) } },
              shape = SegmentedButtonDefaults.itemShape(index = idx, count = OcrEngineMode.entries.size),
            ) {
              Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SettingSwitch(
        stringResource(R.string.screenshot_vision),
        s.screenshotVision,
        stringResource(R.string.screenshot_vision_body),
        enabled = s.engine == AiEngine.CLOUD || s.engine == AiEngine.SELF_HOSTED,
      ) { v ->
        Ui.update { it.copy(screenshotVision = v) }
      }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SettingNav(
        stringResource(R.string.monitored_folders),
        stringResource(R.string.monitored_folders_body),
      ) { onOpen("settings/folders") }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SourceTestAction { SelfTest.checkScreenshots(Ui.app) }
    }

    ScreenshotAppFilterCard(s, onOpen)
  }
}

/** Which apps' screenshots are analysed: all, the ones watched for notifications, or a chosen list. */
@Composable
private fun ScreenshotAppFilterCard(s: AppSettings, onOpen: (String) -> Unit) {
  SettingGroupCard(title = stringResource(R.string.shot_apps_title)) {
    listOf(
      ScreenshotAppFilter.ALL to R.string.shot_apps_all,
      ScreenshotAppFilter.NOTIFICATION_APPS to R.string.shot_apps_notif,
      ScreenshotAppFilter.SELECTED to R.string.shot_apps_selected,
    ).forEach { (mode, label) ->
      Row(
        Modifier.fillMaxWidth()
          .selectable(selected = s.screenshotAppFilter == mode, role = Role.RadioButton) { Ui.update { it.copy(screenshotAppFilter = mode) } }
          .padding(horizontal = Gaps.Inner, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(selected = s.screenshotAppFilter == mode, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
      }
    }
    if (s.screenshotAppFilter == ScreenshotAppFilter.SELECTED) {
      SettingNav(stringResource(R.string.shot_apps_pick), stringResource(R.string.shot_apps_count, s.screenshotApps.size)) {
        onOpen("settings/screenshot_apps")
      }
    }
    Hint(stringResource(R.string.shot_apps_hint))
  }
}

// 8. Voice Memos & Anti-Vocale Submenu
@Composable
private fun VoiceMemoSourceSubScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()

  SubScreen(stringResource(R.string.source_voice_memo), onBack) {
    InteractiveSetupWizardCard(
      wizardTitle = stringResource(R.string.wizard_interactive_title),
      steps = listOf(
        InteractiveStep(
          title = stringResource(R.string.anti_vocale_open_app),
          description = stringResource(R.string.anti_vocale_intro),
          isCompleted = true,
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { AntiVocaleUtils.openApp(context) }) {
              Text(stringResource(R.string.anti_vocale_open_app))
            }
            OutlinedButton(onClick = { AntiVocaleUtils.openPlayStore(context) }) {
              Text(stringResource(R.string.anti_vocale_play_store))
            }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.anti_vocale_pick_folder),
          description = stringResource(R.string.anti_vocale_guide_steps),
          isCompleted = s.antiVocaleEnabled,
        ) {
          SettingSwitch(
            stringResource(R.string.anti_vocale_switch),
            s.antiVocaleEnabled,
          ) { v -> Ui.update { it.copy(antiVocaleEnabled = v) } }
        },
        InteractiveStep(
          title = stringResource(R.string.source_test_button),
          description = stringResource(R.string.anti_vocale_check_connection),
          isCompleted = s.antiVocaleEnabled,
        ) {
          SourceTestAction { SelfTest.checkVoiceMemos(Ui.app) }
        },
      ),
    )

    SettingGroupCard(title = stringResource(R.string.source_voice_memo), emphasized = true) {
      Hint(stringResource(R.string.anti_vocale_intro))
      SettingSwitch(
        stringResource(R.string.anti_vocale_switch),
        s.antiVocaleEnabled,
        stringResource(R.string.anti_vocale_switch_desc),
      ) { v ->
        Ui.update { it.copy(antiVocaleEnabled = v) }
      }
      if (s.antiVocaleEnabled) {
        AntiVocaleIntegrationCard()
      }
      SettingSwitch(
        stringResource(R.string.memo_summary),
        s.memoSummary,
        stringResource(R.string.memo_summary_body),
      ) { v -> Ui.update { it.copy(memoSummary = v) } }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SourceTestAction { SelfTest.checkVoiceMemos(Ui.app) }
    }
  }
}

// 9. Calendar Invitations & ICS Files Submenu
@Composable
private fun CalendarIcsSourceSubScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var scanMsg by remember { mutableStateOf<String?>(null) }
  val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runSafely { FolderWatch.persist(context, uri) }
      Ui.update { it.copy(icsFolderUri = uri.toString(), icsEnabled = true) }
    }
  }

  SubScreen(stringResource(R.string.source_ics_title), onBack) {
    InteractiveSetupWizardCard(
      wizardTitle = stringResource(R.string.wizard_interactive_title),
      steps = listOf(
        InteractiveStep(
          title = stringResource(R.string.source_ics_title),
          description = stringResource(R.string.source_ics_desc),
          isCompleted = s.icsEnabled,
        ) {
          SettingSwitch(stringResource(R.string.source_ics_title), s.icsEnabled) { v ->
            Ui.update { it.copy(icsEnabled = v) }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.ics_pick_folder),
          description = stringResource(R.string.ics_folder_hint),
          isCompleted = s.icsFolderUri.isNotBlank(),
        ) {
          Button(onClick = { folderPicker.launch(null) }) {
            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ics_pick_folder))
          }
          if (s.icsFolderUri.isNotBlank()) {
            Text(
              FolderWatch.label(s.icsFolderUri),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        },
        InteractiveStep(
          title = stringResource(R.string.source_test_button),
          description = stringResource(R.string.source_ics_sub),
          isCompleted = s.icsEnabled && s.icsFolderUri.isNotBlank(),
        ) {
          SourceTestAction { SelfTest.checkCalendarIcs(Ui.app) }
        },
      ),
    )

    SettingGroupCard(title = stringResource(R.string.source_ics_title), emphasized = true) {
      Hint(stringResource(R.string.source_ics_desc))
      SettingSwitch(stringResource(R.string.source_ics_title), s.icsEnabled, stringResource(R.string.source_ics_sub)) { v ->
        Ui.update { it.copy(icsEnabled = v) }
      }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (s.icsFolderUri.isBlank()) stringResource(R.string.ics_pick_folder) else FolderWatch.label(s.icsFolderUri))
        }
        if (s.icsFolderUri.isNotBlank()) {
          Button(onClick = {
            scope.launch {
              val n = IcsCalendarSource.scanFolder(Ui.app)
              scanMsg = "$n .ics Datei(en) importiert"
            }
          }) {
            Text(stringResource(R.string.voice_scan_now))
          }
        }
      }
      scanMsg?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 4.dp))
      }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SourceTestAction { SelfTest.checkCalendarIcs(Ui.app) }
    }
  }
}

// 10. Share Menu & Clipboard Import Submenu
@Composable
private fun ShareClipboardSourceSubScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var clipResult by remember { mutableStateOf<String?>(null) }

  SubScreen(stringResource(R.string.source_share_clipboard), onBack) {
    InteractiveSetupWizardCard(
      wizardTitle = stringResource(R.string.wizard_interactive_title),
      steps = listOf(
        InteractiveStep(
          title = stringResource(R.string.share_source_enabled),
          description = stringResource(R.string.wizard_share_tip_body),
          isCompleted = s.shareEnabled,
        ) {
          SettingSwitch(stringResource(R.string.share_source_enabled), s.shareEnabled) { v ->
            Ui.update { it.copy(shareEnabled = v) }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.clipboard_enabled),
          description = stringResource(R.string.clipboard_enabled_body),
          isCompleted = s.clipboardEnabled,
        ) {
          SettingSwitch(stringResource(R.string.clipboard_enabled), s.clipboardEnabled) { v ->
            Ui.update { it.copy(clipboardEnabled = v) }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.source_test_button),
          description = stringResource(R.string.clipboard_analyze_now),
          isCompleted = s.shareEnabled || s.clipboardEnabled,
        ) {
          SourceTestAction { SelfTest.checkShareAndClipboard(Ui.app) }
        },
      ),
    )

    SettingGroupCard(title = stringResource(R.string.source_share_clipboard), emphasized = true) {
      Hint(stringResource(R.string.wizard_share_tip_body))
      SettingSwitch(
        stringResource(R.string.share_source_enabled),
        s.shareEnabled,
        stringResource(R.string.share_source_enabled_body),
      ) { v -> Ui.update { it.copy(shareEnabled = v) } }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SettingSwitch(
        stringResource(R.string.clipboard_enabled),
        s.clipboardEnabled,
        stringResource(R.string.clipboard_enabled_body),
      ) { v -> Ui.update { it.copy(clipboardEnabled = v) } }

      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = {
            val text = ShareAndClipboardSource.readClipboard(context)
            if (text.isNullOrBlank()) {
              clipResult = resources.getString(R.string.clipboard_empty)
            } else {
              scope.launch {
                val res = runSafely {
                  Ui.app.pipeline.process(
                    Captured(
                      source = SourceType.CLIPBOARD,
                      appLabel = resources.getString(R.string.source_share_clipboard),
                      text = text,
                    ),
                    userInitiated = true,
                  )
                }.getOrNull()
                clipResult = if (res != null) {
                  onOpen("entry/${res.logId}")
                  null
                } else {
                  resources.getString(R.string.share_nothing_found)
                }
              }
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Filled.ContentPaste, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.clipboard_analyze_now))
        }
      }
      clipResult?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 2.dp),
        )
      }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SourceTestAction { SelfTest.checkShareAndClipboard(Ui.app) }
    }
  }
}

@Composable
fun SourceTestAction(runCheck: suspend () -> SelfTest.Step) {
  val scope = rememberCoroutineScope()
  var running by remember { mutableStateOf(false) }
  var result by remember { mutableStateOf<SelfTest.Step?>(null) }

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        enabled = !running,
        onClick = {
          running = true
          result = null
          scope.launch {
            result = runSafely { runCheck() }.getOrElse { e ->
              SelfTest.Step(passed = false, error = e.message ?: e.javaClass.simpleName)
            }
            running = false
          }
        },
      ) {
        if (running) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(R.string.source_test_button))
      }

      result?.let { step ->
        Icon(
          if (step.passed) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
          contentDescription = null,
          tint = if (step.passed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = step.error.ifBlank { step.analysis.ifBlank { stringResource(if (step.passed) R.string.selftest_passed else R.string.selftest_failed) } },
          style = MaterialTheme.typography.bodySmall,
          color = if (step.passed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun ImapAccountRow(
  account: ImapAccount,
  onToggle: (Boolean) -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var testing by remember { mutableStateOf(false) }
  var testStatus by remember { mutableStateOf<ImapClient.CheckResult?>(null) }

  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          account.label.ifBlank { account.username.ifBlank { account.host } },
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          "${account.host}:${account.port} · ${account.folder} (read-only)",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(
        enabled = !testing,
        onClick = {
          testing = true
          testStatus = null
          scope.launch {
            testStatus = ImapClient.testAccount(account, Ui.app.secrets.get(SecretKeys.imap(account.id)))
            testing = false
          }
        },
      ) {
        if (testing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Filled.Science, contentDescription = stringResource(R.string.source_test_button))
      }
      IconButton(onClick = onEdit) {
        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
      }
      IconButton(onClick = onDelete) {
        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
      }
      Switch(checked = account.enabled, onCheckedChange = onToggle)
    }
    testStatus?.let { st ->
      Text(
        text = (if (st.ok) "✓ " else "✗ ") + st.detail,
        style = MaterialTheme.typography.bodySmall,
        color = if (st.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
      )
    }
  }
}

@Composable
private fun ImapAccountDialog(
  initial: ImapAccount,
  onDismiss: () -> Unit,
  onSave: (ImapAccount, String?) -> Unit,
) {
  var label by remember(initial.id) { mutableStateOf(initial.label) }
  var host by remember(initial.id) { mutableStateOf(initial.host) }
  var portText by remember(initial.id) { mutableStateOf(initial.port.toString()) }
  var ssl by remember(initial.id) { mutableStateOf(initial.ssl) }
  var username by remember(initial.id) { mutableStateOf(initial.username) }
  var folder by remember(initial.id) { mutableStateOf(initial.folder) }
  var password by remember(initial.id) { mutableStateOf(Ui.app.secrets.get(SecretKeys.imap(initial.id))) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.imap_account_title)) },
    confirmButton = {
      Button(
        enabled = host.isNotBlank() && username.isNotBlank(),
        onClick = {
          onSave(
            initial.copy(
              label = label.trim(),
              host = host.trim(),
              port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 993,
              ssl = ssl,
              username = username.trim(),
              folder = folder.trim().ifBlank { "INBOX" },
            ),
            password,
          )
        },
      ) { Text(stringResource(R.string.alias_save)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        SettingText(stringResource(R.string.field_title), label, placeholder = stringResource(R.string.imap_label_ph)) { label = it }
        SettingText(stringResource(R.string.imap_host), host, placeholder = "imap.example.com") { host = it }
        SettingText(stringResource(R.string.imap_port), portText, number = true) { portText = it }
        SettingSwitch(stringResource(R.string.imap_ssl), ssl) { ssl = it }
        SettingText(stringResource(R.string.username), username, placeholder = "user@example.com") { username = it }
        SettingText(stringResource(R.string.password), password, secret = true, supporting = stringResource(R.string.app_password_hint)) { password = it }
        SettingText(stringResource(R.string.imap_folder), folder, placeholder = "INBOX") { folder = it }
      }
    },
  )
}

/** Anti-Vocale local transcription integration card with setup instructions, links, and folder picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AntiVocaleIntegrationCard() {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var checkMsg by remember { mutableStateOf<String?>(null) }
  var checking by remember { mutableStateOf(false) }
  var checkFailed by remember { mutableStateOf(false) }
  var scanning by remember { mutableStateOf(false) }
  var scanProgress by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }

  val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runSafely { FolderWatch.persist(context, uri) }
      Ui.update { it.copy(antiVocaleFolderUri = uri.toString(), antiVocaleAutoImport = true) }
      checkMsg = null
    }
  }

  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary)
        Text(
          stringResource(R.string.anti_vocale_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
      }

      Text(
        stringResource(R.string.anti_vocale_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { AntiVocaleUtils.openApp(context) }) {
          Icon(Icons.AutoMirrored.Filled.Launch, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.anti_vocale_open_app))
        }
        OutlinedButton(onClick = { AntiVocaleUtils.openPlayStore(context) }) {
          Text(stringResource(R.string.anti_vocale_play_store))
        }
        TextButton(onClick = { AntiVocaleUtils.openGitHub(context) }) {
          Text(stringResource(R.string.anti_vocale_github))
        }
      }

      HorizontalDivider()

      Text(
        stringResource(R.string.anti_vocale_guide_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        stringResource(R.string.anti_vocale_guide_steps),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )

      if (s.antiVocaleFolderUri.isBlank()) {
        Button(
          onClick = { folderPicker.launch(null) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Filled.FolderOpen, null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.anti_vocale_pick_folder))
        }
      } else {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Text(
              FolderWatch.label(s.antiVocaleFolderUri),
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { folderPicker.launch(null) }) {
              Icon(Icons.Filled.Edit, stringResource(R.string.edit))
            }
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            enabled = !checking,
            onClick = {
              checking = true
              checkMsg = null
              scope.launch {
                val count = AntiVocaleWatch.checkFolder(context, s.antiVocaleFolderUri.toUri())
                checking = false
                checkFailed = count < 0
                checkMsg = if (count >= 0) {
                  resources.getQuantityString(R.plurals.anti_vocale_check_ok, count, count)
                } else {
                  resources.getString(R.string.anti_vocale_check_failed)
                }
              }
            },
          ) {
            Text(stringResource(R.string.anti_vocale_check_connection))
          }

          TextButton(onClick = {
            val old = s.antiVocaleFolderUri
            Ui.update { it.copy(antiVocaleFolderUri = "", antiVocaleAutoImport = false) }
            FolderWatch.release(context, old)
            checkMsg = null
          }) {
            Text(stringResource(R.string.remove))
          }
        }

        checkMsg?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (checkFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          )
        }

        SettingSwitch(
          stringResource(R.string.anti_vocale_auto_import),
          s.antiVocaleAutoImport,
          stringResource(R.string.anti_vocale_auto_import_desc),
        ) { v -> Ui.update { it.copy(antiVocaleAutoImport = v) } }
        if (s.antiVocaleAutoImport) {
          SettingDropdown(
            stringResource(R.string.anti_vocale_interval),
            AntiVocaleWorker.INTERVALS,
            s.antiVocaleCheckMinutes.takeIf { it in AntiVocaleWorker.INTERVALS } ?: 30,
            display = { m -> intervalLabel(context, m) },
          ) { m -> Ui.update { it.copy(antiVocaleCheckMinutes = m) } }
          Hint(stringResource(R.string.anti_vocale_interval_body))
          if (!NotificationCaptureService.isEnabled(context)) Hint(stringResource(R.string.voice_needs_listener))
        }
        SettingSwitch(stringResource(R.string.memo_notification), s.notifyMemoText, stringResource(R.string.memo_notification_body)) { v ->
          Ui.update { it.copy(notifyMemoText = v) }
        }
        OutlinedButton(enabled = !scanning, onClick = {
          scanning = true
          scanProgress = null
          scope.launch {
            val n = VoiceNotes.scan(Ui.app, maxAgeMinutes = 24 * 60, limit = 10) { curr, tot, name -> scanProgress = Triple(curr, tot, name) }
            checkMsg = resources.getQuantityString(R.plurals.memos_found, n, n)
            scanning = false
            scanProgress = null
          }
        }) { Text(stringResource(R.string.voice_scan_now)) }
      }
    }
  }
  if (scanning) {
    val p = scanProgress
    ProgressDialog(
      message = stringResource(R.string.busy_scanning),
      hint = p?.let { (curr, tot, name) -> resources.getString(R.string.scanning_file_progress, curr, tot, name) } ?: stringResource(R.string.busy_hint_wait),
      progress = p?.let { (curr, tot, _) -> if (tot > 0) curr / tot.toFloat() else null },
    )
  }
}

private fun intervalLabel(context: android.content.Context, minutes: Int): String = when {
  minutes <= 0 -> context.getString(R.string.anti_vocale_interval_off)
  minutes < 60 -> context.resources.getQuantityString(R.plurals.anti_vocale_interval_minutes, minutes, minutes)
  else -> context.getString(R.string.anti_vocale_interval_hours, minutes / 60)
}

@Composable
fun MediaSourcesContent(wizard: Boolean, onOpen: (String) -> Unit = {}) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val tick = rememberResumeTick()
  var refresh by remember { mutableIntStateOf(0) }
  val mediaGranted = remember(tick, refresh) { Ui.granted(context, Ui.imagePermission) }
  val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    refresh++
    if (ok) Ui.update { it.copy(screenshotsEnabled = true) }
  }
  SettingGroupCard(title = stringResource(R.string.source_screenshots)) {
    SettingSwitch(stringResource(R.string.screenshots_enabled), s.screenshotsEnabled && mediaGranted, stringResource(R.string.screenshots_body)) { v ->
      if (v && !mediaGranted) mediaLauncher.launch(Ui.imagePermission) else Ui.update { it.copy(screenshotsEnabled = v) }
    }
    SettingSwitch(stringResource(R.string.screenshot_vision), s.screenshotVision, stringResource(R.string.screenshot_vision_body), enabled = s.engine == AiEngine.CLOUD || s.engine == AiEngine.SELF_HOSTED) { v ->
      Ui.update { it.copy(screenshotVision = v) }
    }
  }

  SettingGroupCard(title = stringResource(R.string.source_voice_memo)) {
    Hint(stringResource(R.string.anti_vocale_intro))
    SettingSwitch(
      stringResource(R.string.anti_vocale_switch),
      s.antiVocaleEnabled,
      stringResource(R.string.anti_vocale_switch_desc),
    ) { v ->
      Ui.update { it.copy(antiVocaleEnabled = v) }
    }
    if (s.antiVocaleEnabled) {
      AntiVocaleIntegrationCard()
    }
    SettingSwitch(stringResource(R.string.memo_summary), s.memoSummary, stringResource(R.string.memo_summary_body)) { v -> Ui.update { it.copy(memoSummary = v) } }
  }

  if (!wizard) SettingNav(stringResource(R.string.monitored_folders), stringResource(R.string.monitored_folders_body)) { onOpen("settings/folders") }
}
