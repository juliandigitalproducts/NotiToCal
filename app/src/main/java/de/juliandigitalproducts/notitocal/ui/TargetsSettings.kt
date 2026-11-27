// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.ProviderType
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.notify.Notifier
import de.juliandigitalproducts.notitocal.output.SourceInfo
import de.juliandigitalproducts.notitocal.output.Targets
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

// ------------------------------------------------------------------ Main Targets Screen (3 Cards + Submenu Tiles)

@Composable
fun TargetsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
  SubScreen(stringResource(R.string.section_targets), onBack) {
    TargetsContent(simple = false, onOpen = onOpen)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetsContent(simple: Boolean, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val all = s.eventTargets + s.taskTargets + s.unfilteredTargets

  fun toggleEvents(t: TargetType, on: Boolean) = Ui.update {
    it.copy(eventTargets = if (on) it.eventTargets + t else it.eventTargets - t)
  }
  fun toggleTasks(t: TargetType, on: Boolean) = Ui.update {
    it.copy(taskTargets = if (on) it.taskTargets + t else it.taskTargets - t)
  }
  fun toggleUnfiltered(t: TargetType, on: Boolean) = Ui.update {
    it.copy(unfilteredTargets = if (on) it.unfilteredTargets + t else it.unfilteredTargets - t)
  }

  @Composable
  fun TargetCategoryCard(
    title: String,
    description: String,
    targets: List<TargetType>,
    selected: Set<TargetType>,
    onToggle: (TargetType, Boolean) -> Unit,
    onOpenSubmenu: () -> Unit,
    modifier: Modifier = Modifier,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
  ) {
    Card(
      modifier = modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(Gaps.Inner),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            title,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          TextButton(onClick = onOpenSubmenu) {
            Text(stringResource(R.string.open_submenu_wizard), maxLines = 1, softWrap = false)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(18.dp))
          }
        }
        Text(
          description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          targets.forEach { t ->
            FilterChip(
              selected = t in selected,
              onClick = { onToggle(t, t !in selected) },
              label = { Text(Notifier.targetLabel(context, t)) },
            )
          }
        }
        extraContent?.invoke(this)
      }
    }
  }

  val eventTargetOptions = TargetType.entries.filter { it.forEvents }
  val taskTargetOptions = TargetType.entries.filter { it.forTasks }
  val unfilteredTargetOptions = TargetType.entries.toList()

  BoxWithConstraints(
    modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp),
  ) {
    val twoColumns = maxWidth >= 600.dp
    if (twoColumns) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          TargetCategoryCard(
            title = stringResource(R.string.targets_card_events_title),
            description = stringResource(R.string.targets_card_events_desc),
            targets = eventTargetOptions,
            selected = s.eventTargets,
            onToggle = ::toggleEvents,
            onOpenSubmenu = { onOpen("settings/targets/events") },
            modifier = Modifier.weight(1f),
          )
          TargetCategoryCard(
            title = stringResource(R.string.targets_card_tasks_title),
            description = stringResource(R.string.targets_card_tasks_desc),
            targets = taskTargetOptions,
            selected = s.taskTargets,
            onToggle = ::toggleTasks,
            onOpenSubmenu = { onOpen("settings/targets/tasks") },
            modifier = Modifier.weight(1f),
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          TargetCategoryCard(
            title = stringResource(R.string.targets_card_unfiltered_title),
            description = stringResource(R.string.targets_card_unfiltered_desc),
            targets = unfilteredTargetOptions,
            selected = s.unfilteredTargets,
            onToggle = ::toggleUnfiltered,
            onOpenSubmenu = { onOpen("settings/targets/unfiltered") },
            modifier = Modifier.weight(1f),
          ) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SettingSlider(
              title = stringResource(R.string.targets_unfiltered_threshold),
              value = s.unfilteredAutoConfidence.toFloat(),
              range = 0f..1f,
              subtitle = stringResource(R.string.targets_unfiltered_threshold_body),
            ) { v -> Ui.update { it.copy(unfilteredAutoConfidence = v.toDouble()) } }
          }
          Spacer(Modifier.weight(1f))
        }
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TargetCategoryCard(
          title = stringResource(R.string.targets_card_events_title),
          description = stringResource(R.string.targets_card_events_desc),
          targets = eventTargetOptions,
          selected = s.eventTargets,
          onToggle = ::toggleEvents,
          onOpenSubmenu = { onOpen("settings/targets/events") },
        )
        TargetCategoryCard(
          title = stringResource(R.string.targets_card_tasks_title),
          description = stringResource(R.string.targets_card_tasks_desc),
          targets = taskTargetOptions,
          selected = s.taskTargets,
          onToggle = ::toggleTasks,
          onOpenSubmenu = { onOpen("settings/targets/tasks") },
        )
        TargetCategoryCard(
          title = stringResource(R.string.targets_card_unfiltered_title),
          description = stringResource(R.string.targets_card_unfiltered_desc),
          targets = unfilteredTargetOptions,
          selected = s.unfilteredTargets,
          onToggle = ::toggleUnfiltered,
          onOpenSubmenu = { onOpen("settings/targets/unfiltered") },
        ) {
          HorizontalDivider(Modifier.padding(vertical = 4.dp))
          SettingSlider(
            title = stringResource(R.string.targets_unfiltered_threshold),
            value = s.unfilteredAutoConfidence.toFloat(),
            range = 0f..1f,
            subtitle = stringResource(R.string.targets_unfiltered_threshold_body),
          ) { v -> Ui.update { it.copy(unfilteredAutoConfidence = v.toDouble()) } }
        }
      }
    }
  }

  // Submenu Grid for Individual Calendar, Task & Interface Apps
  SectionHeader(stringResource(R.string.targets_apps_submenus_header))
  Hint(stringResource(R.string.targets_apps_submenus_hint))

  val appTiles = listOf(
    TargetAppTileSpec(
      key = "device_calendar",
      title = stringResource(R.string.target_device_calendar),
      subtitle = stringResource(R.string.target_sub_calendar_desc),
      icon = Icons.Filled.CalendarMonth,
      active = TargetType.DEVICE_CALENDAR in all || TargetType.CALENDAR_APP in all,
    ),
    TargetAppTileSpec(
      key = "tasks_local",
      title = stringResource(R.string.target_tasks_local_title),
      subtitle = "Tasks.org, OpenTasks (ContentProvider)",
      icon = Icons.Filled.TaskAlt,
      active = TargetType.TASK_PROVIDER in all,
    ),
    TargetAppTileSpec(
      key = "reminders_cloud",
      title = stringResource(R.string.target_reminders_cloud_title),
      subtitle = "Google Tasks, Samsung Reminder",
      icon = Icons.Filled.EventAvailable,
      active = TargetType.GOOGLE_TASKS in all || TargetType.SAMSUNG_REMINDER in all,
    ),
    TargetAppTileSpec(
      key = "todoist",
      title = stringResource(R.string.target_todoist),
      subtitle = stringResource(R.string.todoist_token_hint),
      icon = Icons.Filled.EventAvailable,
      active = TargetType.TODOIST in all,
    ),
    TargetAppTileSpec(
      key = "caldav",
      title = stringResource(R.string.target_caldav),
      subtitle = "Nextcloud, Baïkal, iCloud, Mailbox.org",
      icon = Icons.Filled.CloudSync,
      active = TargetType.CALDAV in all,
    ),
    TargetAppTileSpec(
      key = "notion",
      title = stringResource(R.string.target_notion),
      subtitle = stringResource(R.string.notion_database),
      icon = Icons.Filled.Storage,
      active = TargetType.NOTION in all,
    ),
    TargetAppTileSpec(
      key = "webhook",
      title = stringResource(R.string.target_webhook),
      subtitle = "n8n, Make, Home Assistant, REST",
      icon = Icons.Filled.Http,
      active = TargetType.WEBHOOK in all,
    ),
    TargetAppTileSpec(
      key = "automation",
      title = stringResource(R.string.target_broadcast),
      subtitle = stringResource(R.string.targets_tile_automation_sub),
      icon = Icons.Filled.Share,
      active = TargetType.AUTOMATION_BROADCAST in all || TargetType.SHARE in all,
    ),
    TargetAppTileSpec(
      key = "ai_agent",
      title = stringResource(R.string.target_ai_agent),
      subtitle = stringResource(R.string.targets_tile_ai_sub),
      icon = Icons.Filled.AutoAwesome,
      active = TargetType.AI_AGENT in all || TargetType.SELF_HOSTED in all,
    ),
  )

  TileGrid(appTiles, Modifier.padding(horizontal = Gaps.Edge, vertical = 6.dp), extraWideColumns = 4) { tile, _ ->
    TargetAppSubmenuTile(spec = tile, onClick = { onOpen("settings/targets/${tile.key}") })
  }

  if (!simple) {
    SettingGroupCard(title = stringResource(R.string.routing_title)) {
      SettingNav(
        stringResource(R.string.routing_rules),
        if (s.routingRules.isEmpty()) stringResource(R.string.routing_none) else s.routingRules.joinToString { it.name },
        icon = Icons.Filled.Route,
      ) { onOpen("routing") }
      HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
      SettingSwitch(stringResource(R.string.notify_on_add), s.notifyOnAdd) { v -> Ui.update { it.copy(notifyOnAdd = v) } }
    }
  }
}

private data class TargetAppTileSpec(
  val key: String,
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val active: Boolean,
)

@Composable
private fun TargetAppSubmenuTile(
  spec: TargetAppTileSpec,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 3.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = CircleShape,
          color = if (spec.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
          modifier = Modifier.size(36.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              spec.icon,
              contentDescription = null,
              tint = if (spec.active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          )
        }
      }
      Spacer(Modifier.height(8.dp))
      Text(
        spec.title,
        style = MaterialTheme.typography.titleSmall,
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
        lineHeight = 14.sp,
      )
    }
  }
}

// ------------------------------------------------------------------ Target Detail Submenu Screen (Categories + Apps)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetDetailScreen(targetKey: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
  when (targetKey) {
    "events" -> TargetCategorySubScreen(
      categoryKey = "events",
      title = stringResource(R.string.targets_card_events_title),
      description = stringResource(R.string.targets_card_events_desc),
      testKind = Kind.EVENT,
      onBack = onBack,
      onOpen = onOpen,
    )
    "tasks" -> TargetCategorySubScreen(
      categoryKey = "tasks",
      title = stringResource(R.string.targets_card_tasks_title),
      description = stringResource(R.string.targets_card_tasks_desc),
      testKind = Kind.TASK,
      onBack = onBack,
      onOpen = onOpen,
    )
    "unfiltered" -> TargetCategorySubScreen(
      categoryKey = "unfiltered",
      title = stringResource(R.string.targets_card_unfiltered_title),
      description = stringResource(R.string.targets_card_unfiltered_desc),
      testKind = Kind.NOTE,
      onBack = onBack,
      onOpen = onOpen,
    )
    "device_calendar" -> TargetConnectorSubScreen(
      title = stringResource(R.string.target_device_calendar),
      types = listOf(TargetType.DEVICE_CALENDAR, TargetType.CALENDAR_APP),
      onBack = onBack,
    ) {
      DeviceCalendarConfigSection()
    }
    "tasks_local", "task_provider" -> TargetConnectorSubScreen(
      title = stringResource(R.string.target_tasks_local_title),
      types = listOf(TargetType.TASK_PROVIDER),
      onBack = onBack,
    ) {
      TaskProviderConfigSection()
    }
    "reminders_cloud" -> TargetConnectorSubScreen(
      title = stringResource(R.string.target_reminders_cloud_title),
      types = listOf(TargetType.GOOGLE_TASKS, TargetType.SAMSUNG_REMINDER),
      onBack = onBack,
    ) {
      CloudRemindersConfigSection()
    }
    "todoist" -> TargetConnectorSubScreen(
      title = stringResource(R.string.target_todoist),
      types = listOf(TargetType.TODOIST),
      onBack = onBack,
    ) {
      TodoistConfigSection()
    }
    "caldav" -> TargetConnectorSubScreen(
      title = stringResource(R.string.target_caldav),
      types = listOf(TargetType.CALDAV),
      onBack = onBack,
    ) {
      CalDavConfigSection()
    }
    "notion" -> TargetConnectorSubScreen(
      title = stringResource(R.string.target_notion),
      types = listOf(TargetType.NOTION),
      onBack = onBack,
    ) {
      NotionConfigSection()
    }
    "webhook" -> TargetConnectorSubScreen(
      title = stringResource(R.string.target_webhook),
      types = listOf(TargetType.WEBHOOK),
      onBack = onBack,
    ) {
      WebhookConfigSection()
    }
    "automation" -> TargetConnectorSubScreen(
      title = stringResource(R.string.target_broadcast),
      types = listOf(TargetType.AUTOMATION_BROADCAST, TargetType.SHARE),
      onBack = onBack,
    ) {
      AutomationAndShareConfigSection()
    }
    "ai_agent" -> TargetConnectorSubScreen(
      title = stringResource(R.string.target_ai_agent),
      types = listOf(TargetType.AI_AGENT, TargetType.SELF_HOSTED),
      onBack = onBack,
    ) {
      AiAgentAndSelfHostedConfigSection()
    }
    else -> TargetsScreen(onBack, onOpen)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetCategorySubScreen(
  categoryKey: String,
  title: String,
  description: String,
  testKind: Kind,
  onBack: () -> Unit,
  onOpen: (String) -> Unit,
) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var testing by remember { mutableStateOf(false) }
  var testMsg by remember { mutableStateOf<String?>(null) }

  val available = when (categoryKey) {
    "events" -> TargetType.entries.filter { it.forEvents }
    "tasks" -> TargetType.entries.filter { it.forTasks }
    else -> TargetType.entries.toList()
  }
  val selected = when (categoryKey) {
    "events" -> s.eventTargets
    "tasks" -> s.taskTargets
    else -> s.unfilteredTargets
  }

  fun onToggle(t: TargetType, on: Boolean) {
    Ui.update { st ->
      when (categoryKey) {
        "events" -> st.copy(eventTargets = if (on) st.eventTargets + t else st.eventTargets - t)
        "tasks" -> st.copy(taskTargets = if (on) st.taskTargets + t else st.taskTargets - t)
        else -> st.copy(unfilteredTargets = if (on) st.unfilteredTargets + t else st.unfilteredTargets - t)
      }
    }
  }

  SubScreen(title, onBack) {
    Hint(description)

    InteractiveSetupWizardCard(
      wizardTitle = stringResource(R.string.wizard_interactive_title),
      steps = listOf(
        InteractiveStep(
          title = stringResource(R.string.target_wizard_step1_title),
          description = description,
        ) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { t ->
              FilterChip(
                selected = t in selected,
                onClick = { onToggle(t, t !in selected) },
                label = { Text(Notifier.targetLabel(context, t)) },
              )
            }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.target_wizard_step2_title),
          description = stringResource(R.string.target_wizard_step2_desc),
        ) {
          if (selected.isEmpty()) {
            Text(
              stringResource(R.string.no_target_configured),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          } else {
            selected.forEach { t ->
              Text(
                "• ${Notifier.targetLabel(context, t)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.target_wizard_step3_title),
          description = stringResource(R.string.targets_unfiltered_threshold_body),
        ) {
          when (categoryKey) {
            "events" -> {
              SettingSlider(
                title = stringResource(R.string.event_min_conf),
                value = s.eventMinConfidence.toFloat(),
                range = 0.2f..0.95f,
              ) { v -> Ui.update { it.copy(eventMinConfidence = v.toDouble()) } }
            }
            "tasks" -> {
              SettingSlider(
                title = stringResource(R.string.task_min_conf),
                value = s.taskMinConfidence.toFloat(),
                range = 0.2f..0.95f,
              ) { v -> Ui.update { it.copy(taskMinConfidence = v.toDouble()) } }
            }
            else -> {
              SettingSlider(
                title = stringResource(R.string.targets_unfiltered_threshold),
                value = s.unfilteredAutoConfidence.toFloat(),
                range = 0f..1f,
                subtitle = stringResource(R.string.targets_unfiltered_threshold_body),
              ) { v -> Ui.update { it.copy(unfilteredAutoConfidence = v.toDouble()) } }
            }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.target_wizard_step4_title),
          description = stringResource(R.string.target_wizard_step4_desc),
        ) {
          Button(
            enabled = !testing,
            onClick = {
              testing = true
              testMsg = null
              scope.launch {
                val d = Detection(
                  kind = if (testKind == Kind.NOTE) Kind.EVENT else testKind,
                  title = resources.getString(R.string.test_entry_title),
                  description = resources.getString(R.string.test_entry_body),
                  start = LocalDate.now().plusDays(1).atTime(12, 0).toString(),
                  due = LocalDate.now().plusDays(1).toString(),
                  confidence = 1.0,
                  engine = "test",
                )
                val st = Ui.app.settings.current
                val targets = selected.filter { !it.interactive }
                val res = targets.map {
                  Targets.deliver(
                    Ui.app,
                    it,
                    d,
                    st,
                    Ui.app.secrets,
                    SourceInfo("NotiToCal", Ui.app.packageName, "Test", d.description, "TEST"),
                    unfiltered = categoryKey == "unfiltered",
                  )
                }
                testMsg = if (res.isEmpty()) {
                  resources.getString(R.string.no_target_configured)
                } else {
                  res.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + Notifier.targetLabel(context, it.target) + ": " + it.message }
                }
                testing = false
              }
            },
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.target_test_send_now))
          }
          testMsg?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall)
          }
        },
      ),
    )

    // Active Targets Selection Card (the main settings of this screen)
    SettingGroupCard(title = title, emphasized = true) {
      Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          available.forEach { t ->
            FilterChip(
              selected = t in selected,
              onClick = { onToggle(t, t !in selected) },
              label = { Text(Notifier.targetLabel(context, t)) },
            )
          }
        }
      }
      if (categoryKey == "unfiltered") {
        HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
        SettingSlider(
          title = stringResource(R.string.targets_unfiltered_threshold),
          value = s.unfilteredAutoConfidence.toFloat(),
          range = 0f..1f,
          subtitle = stringResource(R.string.targets_unfiltered_threshold_body),
        ) { v -> Ui.update { it.copy(unfilteredAutoConfidence = v.toDouble()) } }
      }
    }

    // Inline Configuration Sections for Selected Targets in this Category
    if (TargetType.DEVICE_CALENDAR in selected || TargetType.CALENDAR_APP in selected) {
      DeviceCalendarConfigSection(emphasized = false)
    }
    if (TargetType.TASK_PROVIDER in selected || TargetType.GOOGLE_TASKS in selected || TargetType.SAMSUNG_REMINDER in selected) {
      TaskProviderConfigSection(emphasized = false)
    }
    if (TargetType.TODOIST in selected) {
      TodoistConfigSection(emphasized = false)
    }
    if (TargetType.CALDAV in selected) {
      CalDavConfigSection(emphasized = false)
    }
    if (TargetType.NOTION in selected) {
      NotionConfigSection(emphasized = false)
    }
    if (TargetType.WEBHOOK in selected) {
      WebhookConfigSection(emphasized = false)
    }
    if (TargetType.AUTOMATION_BROADCAST in selected || TargetType.SHARE in selected) {
      AutomationAndShareConfigSection(emphasized = false)
    }
    if (TargetType.AI_AGENT in selected || TargetType.SELF_HOSTED in selected) {
      AiAgentAndSelfHostedConfigSection(emphasized = false)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetConnectorSubScreen(
  title: String,
  types: List<TargetType>,
  onBack: () -> Unit,
  configContent: @Composable ColumnScope.() -> Unit,
) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var testing by remember { mutableStateOf(false) }
  var testMsg by remember { mutableStateOf<String?>(null) }
  val primaryType = types.first()

  SubScreen(title, onBack) {
    InteractiveSetupWizardCard(
      wizardTitle = stringResource(R.string.wizard_interactive_title),
      steps = listOf(
        InteractiveStep(
          title = stringResource(R.string.target_assign_categories),
          description = stringResource(R.string.target_assign_categories_desc),
        ) {
          types.forEach { t ->
            Text(Notifier.targetLabel(context, t), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              if (t.forEvents) {
                FilterChip(
                  selected = t in s.eventTargets,
                  onClick = {
                    val on = t !in s.eventTargets
                    Ui.update { it.copy(eventTargets = if (on) it.eventTargets + t else it.eventTargets - t) }
                  },
                  label = { Text(stringResource(R.string.targets_card_events_title)) },
                )
              }
              if (t.forTasks) {
                FilterChip(
                  selected = t in s.taskTargets,
                  onClick = {
                    val on = t !in s.taskTargets
                    Ui.update { it.copy(taskTargets = if (on) it.taskTargets + t else it.taskTargets - t) }
                  },
                  label = { Text(stringResource(R.string.targets_card_tasks_title)) },
                )
              }
              FilterChip(
                selected = t in s.unfilteredTargets,
                onClick = {
                  val on = t !in s.unfilteredTargets
                  Ui.update { it.copy(unfilteredTargets = if (on) it.unfilteredTargets + t else it.unfilteredTargets - t) }
                },
                label = { Text(stringResource(R.string.targets_card_unfiltered_title)) },
              )
            }
          }
        },
        InteractiveStep(
          title = stringResource(R.string.target_wizard_step2_title),
          description = stringResource(R.string.target_wizard_step2_desc),
        ) {
          Text(
            stringResource(R.string.target_wizard_step2_desc),
            style = MaterialTheme.typography.bodySmall,
          )
        },
        InteractiveStep(
          title = stringResource(R.string.target_wizard_step4_title),
          description = stringResource(R.string.target_wizard_step4_desc),
        ) {
          Button(
            enabled = !testing,
            onClick = {
              testing = true
              testMsg = null
              scope.launch {
                val d = Detection(
                  kind = if (primaryType.forEvents) Kind.EVENT else Kind.TASK,
                  title = resources.getString(R.string.test_entry_title),
                  description = resources.getString(R.string.test_entry_body),
                  start = LocalDate.now().plusDays(1).atTime(12, 0).toString(),
                  due = LocalDate.now().plusDays(1).toString(),
                  confidence = 1.0,
                  engine = "test",
                )
                val res = Targets.deliver(
                  Ui.app,
                  primaryType,
                  d,
                  Ui.app.settings.current,
                  Ui.app.secrets,
                  SourceInfo("NotiToCal", Ui.app.packageName, "Test", d.description, "TEST"),
                )
                testMsg = (if (res.ok) "✓ " else "✗ ") + Notifier.targetLabel(context, res.target) + ": " + res.message
                testing = false
              }
            },
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.target_test_send_now))
          }
          testMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
      ),
    )

    // Category Assignment Card
    SettingGroupCard(title = stringResource(R.string.target_assign_categories)) {
      types.forEachIndexed { idx, t ->
        if (idx > 0) HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))
        Column(Modifier.padding(horizontal = Gaps.Inner, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(Notifier.targetLabel(context, t), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.forEvents) {
              FilterChip(
                selected = t in s.eventTargets,
                onClick = {
                  val on = t !in s.eventTargets
                  Ui.update { it.copy(eventTargets = if (on) it.eventTargets + t else it.eventTargets - t) }
                },
                label = { Text(stringResource(R.string.targets_card_events_title)) },
              )
            }
            if (t.forTasks) {
              FilterChip(
                selected = t in s.taskTargets,
                onClick = {
                  val on = t !in s.taskTargets
                  Ui.update { it.copy(taskTargets = if (on) it.taskTargets + t else it.taskTargets - t) }
                },
                label = { Text(stringResource(R.string.targets_card_tasks_title)) },
              )
            }
            FilterChip(
              selected = t in s.unfilteredTargets,
              onClick = {
                val on = t !in s.unfilteredTargets
                Ui.update { it.copy(unfilteredTargets = if (on) it.unfilteredTargets + t else it.unfilteredTargets - t) }
              },
              label = { Text(stringResource(R.string.targets_card_unfiltered_title)) },
            )
          }
        }
      }
    }

    configContent()
  }
}

// ------------------------------------------------------------------ Modular Target Configuration Cards

@Composable
private fun DeviceCalendarConfigSection(emphasized: Boolean = true) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  var refresh by remember { mutableIntStateOf(0) }
  val calGranted = remember(refresh) { Ui.granted(context, Manifest.permission.WRITE_CALENDAR) }
  val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

  SettingGroupCard(title = stringResource(R.string.target_device_calendar), emphasized = emphasized) {
    if (!calGranted) {
      PermissionRow(stringResource(R.string.perm_calendar), stringResource(R.string.perm_calendar_body), false) {
        calLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
      }
    } else {
      val cals = remember(refresh) { Targets.calendars(context) }
      if (cals.isEmpty()) {
        Hint(stringResource(R.string.no_calendars))
      } else {
        SettingDropdown(
          stringResource(R.string.calendar),
          cals.map { it.id },
          cals.firstOrNull { it.id == s.calendarId }?.id ?: cals.first().id,
          { id -> cals.first { it.id == id }.let { "${it.name} (${it.account})" } },
        ) { v -> Ui.update { it.copy(calendarId = v) } }
      }
    }
    Hint(stringResource(R.string.calendar_app_hint))
  }
}

@Composable
private fun TaskProviderConfigSection(emphasized: Boolean = true) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  var refresh by remember { mutableIntStateOf(0) }
  val taskGranted = remember(refresh, s.tasksAuthority) { Ui.granted(context, Targets.taskPermission(s.tasksAuthority)) }
  val taskLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

  SettingGroupCard(title = stringResource(R.string.target_task_provider), emphasized = emphasized) {
    SettingDropdown(
      stringResource(R.string.task_app),
      Targets.TASK_AUTHORITIES.map { it.first },
      s.tasksAuthority,
      { a -> Targets.TASK_AUTHORITIES.first { it.first == a }.second },
    ) { v ->
      Ui.update { it.copy(tasksAuthority = v, tasksListId = -1) }
    }
    if (!taskGranted) {
      PermissionRow(stringResource(R.string.perm_tasks), stringResource(R.string.perm_tasks_body), false) {
        taskLauncher.launch(arrayOf(Targets.taskReadPermission(s.tasksAuthority), Targets.taskPermission(s.tasksAuthority)))
      }
    } else {
      val lists = remember(refresh, s.tasksAuthority) { Targets.taskLists(context, s.tasksAuthority) }
      if (lists.isEmpty()) {
        Hint(stringResource(R.string.no_task_lists))
      } else {
        SettingDropdown(
          stringResource(R.string.task_list),
          lists.map { it.id },
          lists.firstOrNull { it.id == s.tasksListId }?.id ?: lists.first().id,
          { id -> lists.first { it.id == id }.let { "${it.name} (${it.account})" } },
        ) { v -> Ui.update { it.copy(tasksListId = v) } }
      }
    }
    Hint(stringResource(R.string.task_provider_hint))
  }
}

@Composable
private fun CloudRemindersConfigSection(emphasized: Boolean = true) {
  val context = LocalContext.current
  val pm = context.packageManager
  val googleTasksInstalled = remember {
    try { pm.getPackageInfo("com.google.android.apps.tasks", 0); true } catch (_: Exception) { false }
  }
  val samsungReminderInstalled = remember {
    try { pm.getPackageInfo("com.samsung.android.app.reminder", 0); true } catch (_: Exception) { false }
  }

  SettingGroupCard(title = stringResource(R.string.target_reminders_cloud_title), emphasized = emphasized) {
    // Google Tasks
    ListItem(
      colors = cardListItemColors(),
      headlineContent = { Text(stringResource(R.string.target_google_tasks), fontWeight = FontWeight.SemiBold) },
      supportingContent = {
        Text(
          stringResource(if (googleTasksInstalled) R.string.google_tasks_installed else R.string.google_tasks_missing),
          style = MaterialTheme.typography.bodySmall,
          color = if (googleTasksInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      leadingContent = {
        Icon(Icons.Filled.CheckCircle, null, tint = if (googleTasksInstalled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline)
      },
      trailingContent = if (googleTasksInstalled) {
        {
          TextButton(onClick = {
            val intent = pm.getLaunchIntentForPackage("com.google.android.apps.tasks")
            intent?.let { context.startActivity(it) }
          }) {
            Text(stringResource(R.string.wizard_open_btn))
          }
        }
      } else null,
    )

    HorizontalDivider(Modifier.padding(horizontal = Gaps.Inner))

    // Samsung Reminder
    ListItem(
      colors = cardListItemColors(),
      headlineContent = { Text(stringResource(R.string.target_samsung_reminder), fontWeight = FontWeight.SemiBold) },
      supportingContent = {
        Text(
          stringResource(if (samsungReminderInstalled) R.string.samsung_reminder_installed else R.string.samsung_reminder_missing),
          style = MaterialTheme.typography.bodySmall,
          color = if (samsungReminderInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      leadingContent = {
        Icon(Icons.Filled.CheckCircle, null, tint = if (samsungReminderInstalled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline)
      },
      trailingContent = if (samsungReminderInstalled) {
        {
          TextButton(onClick = {
            val intent = pm.getLaunchIntentForPackage("com.samsung.android.app.reminder")
            intent?.let { context.startActivity(it) }
          }) {
            Text(stringResource(R.string.wizard_open_btn))
          }
        }
      } else null,
    )

    Hint(stringResource(R.string.target_reminders_cloud_desc))
  }
}

@Composable
private fun TodoistConfigSection(emphasized: Boolean = true) {
  val s by Ui.app.settings.state.collectAsState()
  SettingGroupCard(title = stringResource(R.string.target_todoist), emphasized = emphasized) {
    SecretField(stringResource(R.string.todoist_token), SecretKeys.TODOIST, stringResource(R.string.todoist_token_hint))
    SettingText(stringResource(R.string.todoist_project), s.todoistProjectId) { v -> Ui.update { it.copy(todoistProjectId = v.trim()) } }
  }
}

@Composable
private fun CalDavConfigSection(emphasized: Boolean = true) {
  val s by Ui.app.settings.state.collectAsState()
  SettingGroupCard(title = stringResource(R.string.target_caldav), emphasized = emphasized) {
    SettingText(
      stringResource(R.string.caldav_event_url),
      s.caldavEventUrl,
      placeholder = "https://cloud.example.com/remote.php/dav/calendars/user/personal/",
    ) { v -> Ui.update { it.copy(caldavEventUrl = v.trim()) } }
    SettingText(
      stringResource(R.string.caldav_task_url),
      s.caldavTaskUrl,
      supporting = stringResource(R.string.caldav_task_url_hint),
    ) { v -> Ui.update { it.copy(caldavTaskUrl = v.trim()) } }
    SettingText(stringResource(R.string.username), s.caldavUser) { v -> Ui.update { it.copy(caldavUser = v.trim()) } }
    SecretField(stringResource(R.string.password), SecretKeys.CALDAV, stringResource(R.string.app_password_hint))
  }
}

@Composable
private fun NotionConfigSection(emphasized: Boolean = true) {
  val s by Ui.app.settings.state.collectAsState()
  SettingGroupCard(title = stringResource(R.string.target_notion), emphasized = emphasized) {
    SecretField(stringResource(R.string.notion_token), SecretKeys.NOTION)
    SettingText(stringResource(R.string.notion_database), s.notionDatabaseId) { v -> Ui.update { it.copy(notionDatabaseId = v.trim()) } }
    SettingText(stringResource(R.string.notion_title_prop), s.notionTitleProperty) { v -> Ui.update { it.copy(notionTitleProperty = v) } }
    SettingText(stringResource(R.string.notion_date_prop), s.notionDateProperty) { v -> Ui.update { it.copy(notionDateProperty = v) } }
    SettingText(stringResource(R.string.notion_type_prop), s.notionTypeProperty, supporting = stringResource(R.string.optional)) { v ->
      Ui.update { it.copy(notionTypeProperty = v) }
    }
  }
}

@Composable
private fun WebhookConfigSection(emphasized: Boolean = true) {
  val s by Ui.app.settings.state.collectAsState()
  SettingGroupCard(title = stringResource(R.string.target_webhook), emphasized = emphasized) {
    SettingText(
      stringResource(R.string.webhook_url),
      s.webhookUrl,
      placeholder = "https://n8n.example.com/webhook/notitocal",
    ) { v -> Ui.update { it.copy(webhookUrl = v.trim()) } }
    SecretField(stringResource(R.string.webhook_auth), SecretKeys.WEBHOOK_AUTH, stringResource(R.string.webhook_auth_hint))
    SettingText(
      stringResource(R.string.webhook_template),
      s.webhookBodyTemplate,
      singleLine = false,
      placeholder = "{\"text\": \"{{title}} – {{when}}\"}",
      supporting = stringResource(R.string.webhook_template_hint),
    ) { v -> Ui.update { it.copy(webhookBodyTemplate = v) } }
  }
}

@Composable
private fun AutomationAndShareConfigSection(emphasized: Boolean = true) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()

  SettingGroupCard(title = stringResource(R.string.target_broadcast), emphasized = emphasized) {
    Hint(stringResource(R.string.broadcast_hint, Targets.ACTION_DETECTED))
    val receivers = remember { listOf(AppInfo("", resources.getString(R.string.broadcast_known_apps))) + AppSelector.installedApps(context) }
    SettingDropdown(
      stringResource(R.string.broadcast_app),
      receivers.map { it.packageName },
      s.broadcastPackage.takeIf { p -> receivers.any { it.packageName == p } } ?: "",
      { p -> receivers.firstOrNull { it.packageName == p }?.label ?: p },
    ) { v -> Ui.update { it.copy(broadcastPackage = v) } }
    SettingSwitch(stringResource(R.string.broadcast_source), s.broadcastIncludesSource, stringResource(R.string.broadcast_source_body)) { v ->
      Ui.update { it.copy(broadcastIncludesSource = v) }
    }
  }

  SettingGroupCard(title = stringResource(R.string.target_share)) {
    val apps = remember { listOf(AppInfo("", resources.getString(R.string.ask_every_time))) + AppSelector.shareTargets(context) }
    SettingDropdown(
      stringResource(R.string.share_app),
      apps.map { it.packageName },
      s.sharePackage.takeIf { p -> apps.any { it.packageName == p } } ?: "",
      { p -> apps.first { it.packageName == p }.label },
    ) { v -> Ui.update { it.copy(sharePackage = v) } }
    Hint(stringResource(R.string.share_hint))
  }
}

@Composable
private fun AiAgentAndSelfHostedConfigSection(emphasized: Boolean = true) {
  val s by Ui.app.settings.state.collectAsState()

  SettingGroupCard(title = stringResource(R.string.target_ai_agent), emphasized = emphasized) {
    Hint(stringResource(R.string.agent_hint))
    SettingDropdown(stringResource(R.string.provider), ProviderType.entries, s.agentProvider, { it.label }) { p ->
      Ui.update { it.copy(agentProvider = p, agentBaseUrl = "", agentModel = "") }
    }
    SettingText(stringResource(R.string.base_url), s.agentBaseUrl, placeholder = s.agentProvider.defaultBaseUrl) { v ->
      Ui.update { it.copy(agentBaseUrl = v.trim()) }
    }
    SettingText(stringResource(R.string.model), s.agentModel, placeholder = s.effectiveAgentModel) { v ->
      Ui.update { it.copy(agentModel = v.trim()) }
    }
    if (s.agentProvider.needsKey || s.agentProvider == ProviderType.CUSTOM) {
      androidx.compose.runtime.key(s.agentProvider) {
        SecretField(stringResource(R.string.api_key), SecretKeys.llm(s.agentProvider), stringResource(R.string.api_key_hint))
      }
    }
    SettingText(
      stringResource(R.string.agent_routine),
      s.agentInstructions,
      singleLine = false,
      placeholder = stringResource(R.string.agent_routine_placeholder),
      supporting = stringResource(R.string.agent_routine_hint),
    ) { v -> Ui.update { it.copy(agentInstructions = v) } }
    SettingSlider(
      stringResource(R.string.max_tokens),
      s.agentMaxTokens.toFloat(),
      300f..8000f,
      format = { it.roundToInt().toString() },
    ) { v -> Ui.update { it.copy(agentMaxTokens = v.roundToInt()) } }
    Hint(stringResource(R.string.agent_privacy))
  }

  SettingGroupCard(title = stringResource(R.string.target_self_hosted)) {
    SettingText(stringResource(R.string.server_url), s.selfHostedUrl, placeholder = "https://example.com/notitocal/") { v ->
      Ui.update { it.copy(selfHostedUrl = v.trim()) }
    }
    SecretField(stringResource(R.string.server_token), SecretKeys.SELF_HOSTED)
    Hint(stringResource(R.string.server_db_hint))
  }
}
