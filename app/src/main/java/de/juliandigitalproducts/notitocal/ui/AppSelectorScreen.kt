// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.SmsRcsSource
import de.juliandigitalproducts.notitocal.capture.TelegramUserApiSource
import de.juliandigitalproducts.notitocal.capture.WhatsAppSource
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.runSafely

data class AppInfo(val packageName: String, val label: String)

object AppSelector {
  /** Launcher apps; [includeSelf] adds NotiToCal itself (its own screenshots can be filtered too). */
  fun installedApps(context: Context, includeSelf: Boolean = false): List<AppInfo> {
    val pm = context.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
      .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
      .filter { includeSelf || it.packageName != context.packageName }
      .distinctBy { it.packageName }
      .sortedBy { it.label.lowercase() }
  }

  /** Apps that can receive shared text (for the "Share to app" target, e.g. Samsung Notes, Keep, TickTick). */
  fun shareTargets(context: Context): List<AppInfo> {
    val pm = context.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain"), 0)
      .map { AppInfo(it.activityInfo.packageName, it.activityInfo.applicationInfo.loadLabel(pm).toString()) }
      .filter { it.packageName != context.packageName }
      .distinctBy { it.packageName }
      .sortedBy { it.label.lowercase() }
  }
}

@Composable
fun installedCount(packages: Set<String>): Int {
  val context = LocalContext.current
  return remember(packages) { Ui.installedMonitoredCount(context, packages) }
}

@Composable
fun AppIcon(pkg: String) {
  val context = LocalContext.current
  val bmp = remember(pkg) { runSafely { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
  if (bmp != null) Image(bmp, null, Modifier.size(40.dp))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppSelectorScreen(onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val apps = remember { AppSelector.installedApps(context) }
  var query by rememberSaveable { mutableStateOf("") }
  // Selected and recommended apps first, then the rest alphabetically.
  val shown = apps
    .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
    .sortedWith(compareByDescending<AppInfo> { it.packageName in s.monitoredApps }.thenByDescending { it.packageName in AppSettings.DEFAULT_APPS })
  ListScreen(stringResource(R.string.monitored_apps), onBack) {
      OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text(stringResource(R.string.search)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp),
      )
      Hint(installedCount(s.monitoredApps).let { n -> pluralStringResource(R.plurals.apps_selected, n, n) })
      LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(shown, key = { it.packageName }) { a ->
          val isWhatsAppPersonal = a.packageName == WhatsAppSource.PKG_PERSONAL
          val isWhatsAppBusiness = a.packageName == WhatsAppSource.PKG_BUSINESS
          val isWhatsApp = isWhatsAppPersonal || isWhatsAppBusiness
          val isTelegram = a.packageName in TelegramUserApiSource.TELEGRAM_PACKAGES
          val isSmsRcs = a.packageName in SmsRcsSource.PACKAGES
          val on = when {
            isWhatsAppPersonal -> s.whatsappEnabled && s.whatsappPersonalEnabled && a.packageName in s.monitoredApps
            isWhatsAppBusiness -> s.whatsappEnabled && s.whatsappBusinessEnabled && a.packageName in s.monitoredApps
            isSmsRcs -> s.smsRcsEnabled && a.packageName in s.monitoredApps
            else -> a.packageName in s.monitoredApps
          }
          ListItem(
            headlineContent = { Text(a.label) },
            supportingContent = {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.packageName, style = MaterialTheme.typography.bodySmall)
                if (isWhatsApp) {
                  Text(
                    stringResource(R.string.whatsapp_special_menu_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                  )
                  AssistChip(
                    onClick = { onOpen("settings/sources/whatsapp") },
                    label = { Text(stringResource(R.string.open_whatsapp_special_menu)) },
                  )
                }
                if (isTelegram) {
                  Text(
                    stringResource(R.string.telegram_special_menu_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                  )
                  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                      onClick = { onOpen("settings/sources/telegram_api") },
                      label = { Text(stringResource(R.string.open_telegram_api_menu)) },
                    )
                    AssistChip(
                      onClick = { onOpen("settings/sources/telegram_bot") },
                      label = { Text(stringResource(R.string.open_telegram_bot_menu)) },
                    )
                  }
                }
                if (isSmsRcs) {
                  Text(
                    stringResource(R.string.apps_sms_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                  )
                  AssistChip(
                    onClick = { onOpen("settings/sources/sms_rcs") },
                    label = { Text(stringResource(R.string.apps_sms_open)) },
                  )
                }
              }
            },
            leadingContent = { AppIcon(a.packageName) },
            trailingContent = {
              Switch(checked = on, onCheckedChange = { v ->
                Ui.update { st ->
                  val newApps = if (v) st.monitoredApps + a.packageName else st.monitoredApps - a.packageName
                  when {
                    isWhatsAppPersonal -> st.copy(
                      monitoredApps = newApps,
                      whatsappPersonalEnabled = v,
                      whatsappEnabled = if (v) true else st.whatsappBusinessEnabled,
                    )
                    isWhatsAppBusiness -> st.copy(
                      monitoredApps = newApps,
                      whatsappBusinessEnabled = v,
                      whatsappEnabled = if (v) true else st.whatsappPersonalEnabled,
                    )
                    isSmsRcs -> {
                      val anySms = newApps.any { it in SmsRcsSource.PACKAGES }
                      st.copy(
                        monitoredApps = newApps,
                        smsRcsEnabled = anySms,
                      )
                    }
                    else -> st.copy(monitoredApps = newApps)
                  }
                }
              })
            },
          )
        }
      }
    }
}

/** Apps whose screenshots are analysed with [de.juliandigitalproducts.notitocal.data.ScreenshotAppFilter.SELECTED]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotAppsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val apps = remember { AppSelector.installedApps(context, includeSelf = true) }
  var query by rememberSaveable { mutableStateOf("") }
  val shown = apps
    .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
    .sortedByDescending { it.packageName in s.screenshotApps }

  ListScreen(stringResource(R.string.shot_apps_pick), onBack) {
    OutlinedTextField(
      value = query, onValueChange = { query = it }, singleLine = true,
      leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text(stringResource(R.string.search)) },
      modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp),
    )
    Hint(stringResource(R.string.shot_apps_hint))
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
      items(shown, key = { it.packageName }) { a ->
        val on = a.packageName in s.screenshotApps
        ListItem(
          headlineContent = { Text(a.label) },
          supportingContent = { Text(a.packageName, style = MaterialTheme.typography.bodySmall) },
          leadingContent = { AppIcon(a.packageName) },
          trailingContent = {
            Switch(checked = on, onCheckedChange = { v ->
              Ui.update { st -> st.copy(screenshotApps = if (v) st.screenshotApps + a.packageName else st.screenshotApps - a.packageName) }
            })
          },
        )
      }
    }
  }
}

/** A screen with a top bar and its own lazy list (a [SubScreen] scrolls as a whole and cannot hold one). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  var backClicked by remember { mutableStateOf(false) }
  Scaffold(topBar = {
    TopAppBar(
      title = { Text(title) },
      navigationIcon = {
        IconButton(
          onClick = {
            if (!backClicked) {
              backClicked = true
              onBack()
            }
          },
          enabled = !backClicked,
        ) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
        }
      },
    )
  }) { pad ->
    Column(Modifier.fillMaxSize().padding(pad), content = content)
  }
}
