// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.NotificationCaptureService
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.runSafely

/** The setup steps in order; the settings steps show the advanced switch in the header. */
private enum class WizardStep(val settings: Boolean = false) {
  IMPORT, WELCOME, PERMISSIONS, APPS(true), MEDIA(true), PLACES(true), AI(true), TARGETS(true), STRICTNESS(true), DONE
}

@Composable
fun WizardScreen(onDone: () -> Unit, onOpen: (String) -> Unit) {
  var index by rememberSaveable { mutableIntStateOf(0) }
  val steps = WizardStep.entries
  val step = steps[index]
  val s by Ui.app.settings.state.collectAsState()
  val finish = { Ui.update { it.copy(onboardingDone = true) }; onDone() }
  val progress by animateFloatAsState((index + 1) / steps.size.toFloat(), label = "wizard_progress")

  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(R.string.wizard_step_of, index + 1, steps.size),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.weight(1f),
        )
        // The advanced switch is available on every settings step and shows all parameters right in the wizard.
        if (step.settings) {
          Text(stringResource(R.string.advanced_mode), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
          Switch(checked = s.advancedMode, onCheckedChange = { v -> Ui.update { it.copy(advancedMode = v) } })
        }
      }
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
      )
    }
    Box(Modifier.weight(1f)) {
      AnimatedContent(
        targetState = index,
        transitionSpec = {
          val forward = targetState > initialState
          (slideInHorizontally { w -> if (forward) w else -w } + fadeIn())
            .togetherWith(slideOutHorizontally { w -> if (forward) -w else w } + fadeOut())
        },
        label = "wizard_step_transition",
      ) { current ->
        // Every step has its own scroll position: a shared one kept the height of the previous (short) step, so a
        // longer list could not be scrolled until another step had been shown.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
          when (steps[current]) {
            WizardStep.IMPORT -> ImportStep(onFinish = finish, onContinue = { index++ })
            WizardStep.WELCOME -> WelcomeStep()
            WizardStep.PERMISSIONS -> PermissionStep()
            WizardStep.APPS -> AppsStep(onOpen)
            WizardStep.MEDIA -> { StepTitle(Icons.Filled.Mic, R.string.wizard_media_title, R.string.wizard_media_body); MediaSourcesContent(wizard = true) }
            WizardStep.PLACES -> PlacesStep()
            WizardStep.AI -> { StepTitle(Icons.Filled.AutoAwesome, R.string.wizard_ai_title, R.string.wizard_ai_body); EngineContent(wizard = true, onOpen = onOpen) }
            WizardStep.TARGETS -> { StepTitle(Icons.Filled.CalendarMonth, R.string.wizard_targets_title, R.string.wizard_targets_body); TargetsContent(simple = !s.advancedMode, onOpen = onOpen) }
            WizardStep.STRICTNESS -> {
              StepTitle(Icons.Filled.Tune, R.string.wizard_strictness_title, R.string.wizard_strictness_body)
              StrictnessSimple()
              if (s.advancedMode) StrictnessAdvanced()
            }
            WizardStep.DONE -> DoneStep()
          }
        }
      }
    }
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (index > 0) OutlinedButton(onClick = { index-- }) { Text(stringResource(R.string.back)) } else Spacer(Modifier.size(1.dp))
        if (step == WizardStep.DONE) {
          Button(onClick = finish) {
            Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wizard_finish))
          }
        } else {
          Button(onClick = { index++ }) {
            Text(stringResource(when (step) {
              WizardStep.IMPORT -> R.string.wizard_import_new
              WizardStep.WELCOME -> R.string.wizard_start
              else -> R.string.next
            }))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
          }
        }
      }
    }
  }
}

/** A step's heading: icon in a tonal circle, title and a short explanation. */
@Composable
private fun StepTitle(icon: ImageVector, title: Int, body: Int) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
      Box(contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
      }
    }
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** First question: start fresh, or take over the configuration of an earlier installation. */
@Composable
private fun ImportStep(onFinish: () -> Unit, onContinue: () -> Unit) {
  var imported by rememberSaveable { mutableStateOf(false) }
  val importBackup = rememberBackupImport { ok -> if (ok) imported = true }
  StepTitle(Icons.Filled.SettingsBackupRestore, R.string.wizard_import_title, R.string.wizard_import_body)
  ChoiceCard(stringResource(R.string.wizard_import_new), stringResource(R.string.wizard_import_new_body), selected = !imported, icon = Icons.Filled.AutoAwesome) {
    onContinue()
  }
  ChoiceCard(stringResource(R.string.wizard_import_existing), stringResource(R.string.wizard_import_existing_body), selected = imported, icon = Icons.Filled.FileDownload) {
    importBackup()
  }
  if (imported) {
    InfoCard(stringResource(R.string.wizard_import_done_title), stringResource(R.string.wizard_import_done_body), icon = Icons.Filled.CheckCircle) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        Button(onClick = onFinish) { Text(stringResource(R.string.wizard_finish)) }
        OutlinedButton(onClick = onContinue) { Text(stringResource(R.string.wizard_import_review)) }
      }
    }
  }
  Hint(stringResource(R.string.wizard_import_hint), Modifier.padding(horizontal = 8.dp))
}

/** Places: home town, the location history (import / samples) and the automatic place lookup. */
@Composable
private fun PlacesStep() {
  val s by Ui.app.settings.state.collectAsState()
  StepTitle(Icons.Filled.Place, R.string.wizard_places_title, R.string.wizard_places_body)
  SettingGroupCard(emphasized = true) {
    SettingSwitch(stringResource(R.string.loc_auto_title), s.locationEnrichmentEnabled, stringResource(R.string.loc_auto_body)) { v ->
      Ui.update { it.copy(locationEnrichmentEnabled = v) }
    }
  }
  HomeLocationCard()
  LocationHistoryCards()
}

@Composable
private fun WelcomeStep() {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
      Box(contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.NotificationsActive, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
      }
    }
    Text(stringResource(R.string.wizard_welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text(stringResource(R.string.wizard_welcome_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)

    // Visual Flow: How it works
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          stringResource(R.string.wizard_how_it_works_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FlowStageItem(
            icon = Icons.Filled.Notifications,
            label = stringResource(R.string.wizard_step_capture),
            desc = stringResource(R.string.wizard_step_capture_desc),
            modifier = Modifier.weight(1f),
          )
          Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
          FlowStageItem(
            icon = Icons.Filled.AutoAwesome,
            label = stringResource(R.string.wizard_step_context),
            desc = stringResource(R.string.wizard_step_context_desc),
            modifier = Modifier.weight(1f),
          )
          Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
          FlowStageItem(
            icon = Icons.Filled.CalendarMonth,
            label = stringResource(R.string.wizard_step_deliver),
            desc = stringResource(R.string.wizard_step_deliver_desc),
            modifier = Modifier.weight(1f),
          )
        }
      }
    }

    // Expectations Card: Was geht / Was geht nicht
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
      Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          stringResource(R.string.wizard_expectations_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp).padding(top = 2.dp))
          Text(stringResource(R.string.wizard_can_do_1), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp).padding(top = 2.dp))
          Text(stringResource(R.string.wizard_can_do_2), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp).padding(top = 2.dp))
          Text(stringResource(R.string.wizard_can_do_3), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(top = 2.dp))
          Text(stringResource(R.string.wizard_cannot_do_1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(top = 2.dp))
          Text(stringResource(R.string.wizard_cannot_do_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }

  InfoCard(stringResource(R.string.wizard_privacy_title), stringResource(R.string.wizard_privacy_body), icon = Icons.Filled.Lock)
  val s by Ui.app.settings.state.collectAsState()
  SettingSwitch(stringResource(R.string.advanced_mode), s.advancedMode, stringResource(R.string.wizard_advanced_body)) { v -> Ui.update { it.copy(advancedMode = v) } }
}

@Composable
private fun FlowStageItem(icon: ImageVector, label: String, desc: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.padding(horizontal = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
    }
    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3)
  }
}

@Composable
private fun PermissionStep() {
  val context = LocalContext.current
  val tick = rememberResumeTick()
  var refresh by remember { mutableIntStateOf(0) }
  val access = remember(tick, refresh) { NotificationCaptureService.isEnabled(context) }
  val notif = remember(tick, refresh) { Ui.notificationsGranted(context) }
  val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

  StepTitle(Icons.Filled.Notifications, R.string.wizard_perm_title, R.string.wizard_perm_body)
  SettingGroupCard(emphasized = true) {
    PermissionRow(stringResource(R.string.perm_listener), stringResource(R.string.perm_listener_body), access) { Ui.openNotificationAccess(context) }
    PermissionRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_body), notif) {
      if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      else runSafely { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
    }
  }
  Hint(stringResource(R.string.perm_restricted_hint), Modifier.padding(horizontal = 8.dp))
}

@Composable
fun PermissionRow(title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
  ListItem(
    colors = cardListItemColors(),
    headlineContent = { Text(title) },
    supportingContent = { Text(body) },
    leadingContent = {
      Icon(if (granted) Icons.Filled.CheckCircle else Icons.Filled.Lock, null, tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    },
    trailingContent = { if (!granted) OutlinedButton(onClick = onGrant) { Text(stringResource(R.string.grant)) } },
  )
}

@Composable
private fun AppsStep(onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val installed = remember { AppSelector.installedApps(context) }
  val suggested = installed.filter { it.packageName in AppSettings.DEFAULT_APPS }
  StepTitle(Icons.Filled.NotificationsActive, R.string.wizard_apps_title, R.string.wizard_apps_body)
  SettingGroupCard(emphasized = true) {
    suggested.forEach { a ->
      val checked = a.packageName in s.monitoredApps
      ListItem(
        colors = cardListItemColors(),
        modifier = Modifier.clickable {
          Ui.update { st -> st.copy(monitoredApps = if (checked) st.monitoredApps - a.packageName else st.monitoredApps + a.packageName) }
        },
        headlineContent = { Text(a.label) },
        supportingContent = { Text(a.packageName, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { AppIcon(a.packageName) },
        trailingContent = {
          Checkbox(checked = checked, onCheckedChange = { on ->
            Ui.update { st -> st.copy(monitoredApps = if (on) st.monitoredApps + a.packageName else st.monitoredApps - a.packageName) }
          })
        },
      )
    }
    if (suggested.isEmpty()) Hint(stringResource(R.string.wizard_apps_none))
    // Secondary action: an outlined button stays visible on the highlighted card.
    OutlinedButton(onClick = { onOpen("apps") }, modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
      Icon(Icons.Filled.Apps, null, Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(stringResource(R.string.all_apps))
    }
  }
}

@Composable
private fun DoneStep() {
  val s by Ui.app.settings.state.collectAsState()
  Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Spacer(Modifier.height(24.dp))
    Icon(Icons.Filled.CheckCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.wizard_done_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text(stringResource(R.string.wizard_done_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
  }
  InfoCard(stringResource(R.string.wizard_summary), buildString {
    append(installedCount(s.monitoredApps).let { n -> pluralStringResource(R.plurals.summary_apps, n, n) }).append('\n')
    append(engineLabel(s.engine, s.effectiveModel)).append('\n')
    append(stringResource(R.string.summary_strictness, strictnessLabel(s.strictness)))
  })
  InfoCard(stringResource(R.string.wizard_share_tip_title), stringResource(R.string.wizard_share_tip_body))
}
