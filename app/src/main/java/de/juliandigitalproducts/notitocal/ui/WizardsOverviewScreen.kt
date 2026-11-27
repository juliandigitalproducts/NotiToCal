// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import de.juliandigitalproducts.notitocal.capture.NotificationCaptureService

data class WizardItemSpec(
  val route: String,
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val isCompleted: Boolean,
  val tag: String,
)

@Composable
fun WizardsOverviewScreen(
  onBack: () -> Unit,
  onOpen: (String) -> Unit,
) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val tick = rememberResumeTick()
  val access = remember(tick) { NotificationCaptureService.isEnabled(context) }
  val mediaGranted = remember(tick) { Ui.granted(context, Ui.imagePermission) }

  val generalWizards = listOf(
    WizardItemSpec(
      route = "wizard",
      title = stringResource(R.string.wz_onboarding_title),
      subtitle = stringResource(R.string.wz_onboarding_subtitle),
      icon = Icons.Filled.AutoAwesome,
      isCompleted = s.onboardingDone,
      tag = stringResource(R.string.wz_tag_general),
    ),
  )

  val sourceWizards = listOf(
    WizardItemSpec(
      route = "settings/sources/whatsapp",
      title = stringResource(R.string.wz_whatsapp_title),
      subtitle = stringResource(R.string.wz_whatsapp_subtitle),
      icon = Icons.AutoMirrored.Filled.Chat,
      isCompleted = s.whatsappPersonalEnabled && access,
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/whatsapp_business",
      title = stringResource(R.string.wz_wa_business_title),
      subtitle = stringResource(R.string.wz_wa_business_subtitle),
      icon = Icons.AutoMirrored.Filled.Chat,
      isCompleted = s.whatsappBusinessEnabled && access,
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/telegram_api",
      title = stringResource(R.string.wz_tg_api_title),
      subtitle = stringResource(R.string.wz_tg_api_subtitle),
      icon = Icons.Filled.Security,
      isCompleted = s.telegramApiEnabled,
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/telegram_bot",
      title = stringResource(R.string.wz_tg_bot_title),
      subtitle = stringResource(R.string.wz_tg_bot_subtitle),
      icon = Icons.Filled.SmartToy,
      isCompleted = s.telegramEnabled,
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/notifications",
      title = stringResource(R.string.wz_notifications_title),
      subtitle = stringResource(R.string.wz_notifications_subtitle),
      icon = Icons.Filled.Notifications,
      isCompleted = s.notificationsEnabled && access,
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/imap",
      title = stringResource(R.string.wz_imap_title),
      subtitle = stringResource(R.string.wz_imap_subtitle),
      icon = Icons.Filled.Email,
      isCompleted = s.imapEnabled && s.imapAccounts.any { it.enabled },
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/sms_rcs",
      title = stringResource(R.string.wz_sms_title),
      subtitle = stringResource(R.string.wz_sms_subtitle),
      icon = Icons.Filled.Sms,
      isCompleted = s.smsRcsEnabled && access,
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/screenshots",
      title = stringResource(R.string.wz_screenshots_title),
      subtitle = stringResource(R.string.wz_screenshots_subtitle),
      icon = Icons.Filled.CameraAlt,
      isCompleted = s.screenshotsEnabled && mediaGranted,
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/voice_memo",
      title = stringResource(R.string.wz_voice_title),
      subtitle = stringResource(R.string.wz_voice_subtitle),
      icon = Icons.Filled.Mic,
      isCompleted = s.antiVocaleEnabled,
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/calendar_ics",
      title = stringResource(R.string.wz_ics_title),
      subtitle = stringResource(R.string.wz_ics_subtitle),
      icon = Icons.Filled.CalendarMonth,
      isCompleted = s.icsEnabled,
      tag = stringResource(R.string.wz_tag_source),
    ),
    WizardItemSpec(
      route = "settings/sources/share_clipboard",
      title = stringResource(R.string.wz_share_title),
      subtitle = stringResource(R.string.wz_share_subtitle),
      icon = Icons.Filled.Share,
      isCompleted = s.shareEnabled || s.clipboardEnabled,
      tag = stringResource(R.string.wz_tag_source),
    ),
  )

  val targetWizards = listOf(
    WizardItemSpec(
      route = "settings/targets/events",
      title = stringResource(R.string.wz_t_events_title),
      subtitle = stringResource(R.string.wz_t_events_subtitle),
      icon = Icons.Filled.CalendarMonth,
      isCompleted = s.eventTargets.isNotEmpty(),
      tag = stringResource(R.string.wz_tag_target),
    ),
    WizardItemSpec(
      route = "settings/targets/tasks",
      title = stringResource(R.string.wz_t_tasks_title),
      subtitle = stringResource(R.string.wz_t_tasks_subtitle),
      icon = Icons.Filled.TaskAlt,
      isCompleted = s.taskTargets.isNotEmpty(),
      tag = stringResource(R.string.wz_tag_target),
    ),
    WizardItemSpec(
      route = "settings/targets/unfiltered",
      title = stringResource(R.string.wz_t_unfiltered_title),
      subtitle = stringResource(R.string.wz_t_unfiltered_subtitle),
      icon = Icons.Filled.Cloud,
      isCompleted = s.unfilteredTargets.isNotEmpty(),
      tag = stringResource(R.string.wz_tag_target),
    ),
  )

  val aiAndDiagWizards = listOf(
    WizardItemSpec(
      route = "settings/engine",
      title = stringResource(R.string.wz_engine_title),
      subtitle = stringResource(R.string.wz_engine_subtitle),
      icon = Icons.Filled.AutoAwesome,
      isCompleted = s.engine != de.juliandigitalproducts.notitocal.data.AiEngine.RULES,
      tag = stringResource(R.string.wz_tag_ai),
    ),
    WizardItemSpec(
      route = "settings/diagnose",
      title = stringResource(R.string.wz_diagnose_title),
      subtitle = stringResource(R.string.wz_diagnose_subtitle),
      icon = Icons.Filled.Science,
      isCompleted = access && s.onboardingDone,
      tag = stringResource(R.string.wz_tag_diagnose),
    ),
  )

  val allWizards = generalWizards + sourceWizards + targetWizards + aiAndDiagWizards
  val completedCount = allWizards.count { it.isCompleted }

  SubScreen(
    title = stringResource(R.string.wizards_overview_title),
    onBack = onBack,
  ) {
    // SubScreen already scrolls vertically; a nested verticalScroll crashes Compose.
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Summary Card
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(R.string.wz_header_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
          Text(
            stringResource(R.string.wz_header_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.primary,
            ) {
              Text(
                stringResource(R.string.wz_progress, completedCount, allWizards.size),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
              )
            }
          }
        }
      }

      // Switch: Alle Assistenten in Menüs ausblenden
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              stringResource(R.string.wz_hide_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              stringResource(R.string.wz_hide_body),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = s.hideWizardsInMenus,
            onCheckedChange = { v -> Ui.update { it.copy(hideWizardsInMenus = v) } },
          )
        }
      }

      // Group 1: Allgemeine Einrichtung
      SectionHeader(stringResource(R.string.wz_sec_general))
      generalWizards.forEach { spec ->
        WizardCard(spec = spec, onClick = { onOpen(spec.route) })
      }

      // Group 2: Quellen-Assistenten
      SectionHeader(stringResource(R.string.wz_sec_sources))
      sourceWizards.forEach { spec ->
        WizardCard(spec = spec, onClick = { onOpen(spec.route) })
      }

      // Group 3: Ziel-Assistenten
      SectionHeader(stringResource(R.string.wz_sec_targets))
      targetWizards.forEach { spec ->
        WizardCard(spec = spec, onClick = { onOpen(spec.route) })
      }

      // Group 4: KI & Diagnose
      SectionHeader(stringResource(R.string.wz_sec_ai))
      aiAndDiagWizards.forEach { spec ->
        WizardCard(spec = spec, onClick = { onOpen(spec.route) })
      }
    }
  }
}

@Composable
private fun WizardCard(
  spec: WizardItemSpec,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 3.dp),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = if (spec.isCompleted) Color(0xFF2E7D32).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(44.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            spec.icon,
            contentDescription = null,
            tint = if (spec.isCompleted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
          )
        }
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            spec.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (spec.isCompleted) {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = Color(0xFF2E7D32).copy(alpha = 0.15f),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
              ) {
                Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(12.dp))
                Text(
                  stringResource(R.string.wz_active),
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF2E7D32),
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }
        }
        Text(
          spec.subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(20.dp),
      )
    }
  }
}
