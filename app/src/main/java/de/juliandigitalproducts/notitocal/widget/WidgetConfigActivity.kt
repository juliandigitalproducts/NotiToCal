// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.widget

import de.juliandigitalproducts.notitocal.ui.Gaps
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.ui.CalendarFilter
import de.juliandigitalproducts.notitocal.ui.Hint
import de.juliandigitalproducts.notitocal.ui.SettingGroupCard
import de.juliandigitalproducts.notitocal.ui.SettingSwitch
import de.juliandigitalproducts.notitocal.ui.theme.NotiToCalTheme
import kotlin.math.roundToInt

/**
 * Settings of one home screen widget: opened by the launcher when a widget is placed and again via "Customise widget".
 * Nothing is stored until "Save"; leaving otherwise keeps the previous settings (a new widget is then not placed).
 */
class WidgetConfigActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
      ?: AppWidgetManager.INVALID_APPWIDGET_ID
    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
      finish()
      return
    }
    val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
    setResult(RESULT_CANCELED, result)
    // The activity is exported for the launcher: only widgets of this app can be configured here.
    val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
    if (provider?.packageName != packageName) {
      finish()
      return
    }
    val kind = WidgetKind.of(provider.className)
    val initial = WidgetConfigStore.load(this, widgetId)

    enableEdgeToEdge()
    setContent {
      NotiToCalTheme {
        WidgetConfigScreen(
          kind = kind,
          initial = initial,
          onCancel = { finish() },
          onSave = { config ->
            WidgetConfigStore.save(this, widgetId, config)
            WidgetRenderer.render(this, widgetId, kind)
            setResult(RESULT_OK, result)
            finish()
          },
        )
      }
    }
  }
}

/** Draws a widget again after its settings changed. */
internal object WidgetRenderer {
  fun render(context: Context, widgetId: Int, kind: WidgetKind) {
    val mgr = AppWidgetManager.getInstance(context)
    when (kind) {
      WidgetKind.MEMOS -> VoiceMemosWidgetProvider.render(context, mgr, widgetId, unfiltered = false)
      WidgetKind.MEMOS_UNFILTERED -> VoiceMemosWidgetProvider.render(context, mgr, widgetId, unfiltered = true)
      WidgetKind.QUICK_CAMERA -> QuickCameraWidgetProvider.render(context, mgr, widgetId)
    }
  }
}

@Composable
private fun WidgetConfigScreen(kind: WidgetKind, initial: WidgetConfig, onCancel: () -> Unit, onSave: (WidgetConfig) -> Unit) {
  val app = LocalContext.current.applicationContext as NotiToCalApp
  val settings by app.settings.state.collectAsState()
  var opacity by rememberSaveable { mutableFloatStateOf(initial.opacity) }
  var calendars by rememberSaveable { mutableStateOf(initial.calendars.toList()) }
  var showActions by rememberSaveable { mutableStateOf(initial.showActions) }
  var theme by rememberSaveable { mutableStateOf(initial.theme) }
  var textScale by rememberSaveable { mutableFloatStateOf(initial.textScale) }
  val config = WidgetConfig(opacity, calendars.toSet(), showActions, theme, textScale).sanitized()

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text(
          stringResource(R.string.widget_config_title),
          style = MaterialTheme.typography.headlineSmall,
          modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        )
        WidgetPreview(kind, config)

        SettingGroupCard(title = stringResource(R.string.widget_look), emphasized = true) {
          ConfigSlider(
            title = stringResource(R.string.widget_transparency),
            value = 1f - opacity,
            range = 0f..(1f - WidgetConfig.MIN_OPACITY),
            steps = 15,
          ) { opacity = 1f - it }
          Text(
            stringResource(R.string.widget_theme),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = Gaps.Inner, end = Gaps.Inner, top = 8.dp),
          )
          ThemeSelector(theme) { theme = it }
          ConfigSlider(
            title = stringResource(R.string.widget_text_size),
            value = textScale,
            range = WidgetConfig.MIN_TEXT_SCALE..WidgetConfig.MAX_TEXT_SCALE,
            steps = 14,
          ) { textScale = it }
        }

        if (kind.hasActions) {
          SettingGroupCard(title = stringResource(R.string.widget_actions)) {
            SettingSwitch(stringResource(R.string.widget_actions), showActions, stringResource(R.string.widget_actions_body)) { showActions = it }
          }
        }

        if (kind.hasEntries) {
          SettingGroupCard(title = stringResource(R.string.widget_calendars)) {
            CalendarChoice(settings, calendars.toSet()) { calendars = it.toList() }
          }
        }
      }
      Row(
        Modifier.fillMaxWidth().padding(Gaps.Inner),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
      ) {
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        Button(onClick = { onSave(config) }) { Text(stringResource(R.string.widget_save)) }
      }
    }
  }
}

@Composable
private fun ConfigSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text("${(value * 100).roundToInt()} %", style = MaterialTheme.typography.labelLarge)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
  }
}

@Composable
private fun ThemeSelector(selected: WidgetTheme, onSelect: (WidgetTheme) -> Unit) {
  val options = listOf(
    WidgetTheme.SYSTEM to R.string.widget_theme_system,
    WidgetTheme.LIGHT to R.string.widget_theme_light,
    WidgetTheme.DARK to R.string.widget_theme_dark,
  )
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
    options.forEachIndexed { i, (theme, label) ->
      SegmentedButton(selected = selected == theme, onClick = { onSelect(theme) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
        Text(stringResource(label))
      }
    }
  }
}

/** Multiple choice of the calendars from the routing rules; nothing chosen = all calendars. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarChoice(s: AppSettings, selected: Set<String>, onChange: (Set<String>) -> Unit) {
  val rules = s.routingRules.filter { it.enabled }
  if (rules.isEmpty()) {
    Hint(stringResource(R.string.widget_calendars_no_rules))
    return
  }
  val unnamed = stringResource(R.string.filter_unnamed_rule)
  val options = rules.map { it.id to it.name.ifBlank { unnamed } } + (CalendarFilter.DEFAULT to stringResource(R.string.filter_default_calendar))
  Hint(stringResource(R.string.widget_calendars_hint))
  FlowRow(Modifier.padding(horizontal = Gaps.Inner, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FilterChip(selected = selected.isEmpty(), onClick = { onChange(emptySet()) }, label = { Text(stringResource(R.string.filter_all_calendars)) })
    options.forEach { (key, name) ->
      FilterChip(
        selected = key in selected,
        onClick = { onChange(if (key in selected) selected - key else selected + key) },
        label = { Text(name) },
      )
    }
  }
}

/** A small mock of the widget with the current choices (colours from the same widget colour resources). */
@Composable
private fun WidgetPreview(kind: WidgetKind, config: WidgetConfig) {
  val night = when (config.theme) {
    WidgetTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    WidgetTheme.LIGHT -> false
    WidgetTheme.DARK -> true
  }
  val context = LocalContext.current
  val palette = remember(night) { WidgetStyle.palette(context, night) }
  val text = Color(palette.text)
  val accent = Color(palette.accent)
  Box(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = Gaps.Inner, vertical = 8.dp)
      .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(24.dp))
      .padding(20.dp),
  ) {
    Column(
      Modifier
        .fillMaxWidth()
        .background(Color(palette.surface).copy(alpha = config.opacity), RoundedCornerShape(20.dp))
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      val scale = config.textScale
      if (kind == WidgetKind.QUICK_CAMERA) {
        Text(stringResource(R.string.widget_photo_label), color = text, fontSize = (11 * scale).sp, fontWeight = FontWeight.Bold)
        return@Column
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(if (kind == WidgetKind.MEMOS_UNFILTERED) R.string.widget_memos_unfiltered_title else R.string.widget_memos_title),
          color = text, fontSize = (14 * scale).sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1,
        )
        if (config.showActions) {
          Text(
            stringResource(R.string.widget_photo_event) + " · " + stringResource(R.string.widget_open_app),
            color = accent, fontSize = (11 * scale).sp, maxLines = 1,
          )
        }
      }
      Column(
        Modifier.fillMaxWidth().background(accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(8.dp),
      ) {
        Text("09:00", color = accent, fontSize = (11 * scale).sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.widget_sample_text), color = text, fontSize = (12 * scale).sp)
      }
    }
  }
}
