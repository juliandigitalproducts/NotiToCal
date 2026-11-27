// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.widget

import android.content.Context
import androidx.core.content.edit
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.ui.CalendarFilter
import kotlinx.serialization.Serializable

@Serializable
enum class WidgetTheme { SYSTEM, LIGHT, DARK }

/** The kinds of home screen widget; they offer different options. */
enum class WidgetKind(val hasEntries: Boolean, val hasActions: Boolean) {
  MEMOS(hasEntries = true, hasActions = true),
  MEMOS_UNFILTERED(hasEntries = true, hasActions = true),
  QUICK_CAMERA(hasEntries = false, hasActions = false);

  companion object {
    fun of(providerClassName: String?): WidgetKind = when (providerClassName) {
      UnfilteredVoiceMemosWidgetProvider::class.java.name -> MEMOS_UNFILTERED
      QuickCameraWidgetProvider::class.java.name -> QUICK_CAMERA
      else -> MEMOS
    }
  }
}

/** Look and content of one placed widget. */
@Serializable
data class WidgetConfig(
  /** Background opacity, 0.2 … 1. */
  val opacity: Float = DEFAULT_OPACITY,
  /** Calendar keys as in [CalendarFilter] (a routing rule id or [CalendarFilter.DEFAULT]); empty = all calendars. */
  val calendars: Set<String> = emptySet(),
  /** Shows the "Photo appointment" and "Open app" buttons. */
  val showActions: Boolean = true,
  val theme: WidgetTheme = WidgetTheme.SYSTEM,
  /** Text zoom, 0.5 … 2. */
  val textScale: Float = 1f,
) {
  /** Values from an old or edited store are pulled back into range. */
  fun sanitized() = copy(opacity = opacity.coerceIn(MIN_OPACITY, 1f), textScale = textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE))

  /** Whether [e] belongs to one of the chosen calendars (with the current routing rules). */
  fun shows(e: LogEntry, s: AppSettings): Boolean {
    // A calendar whose rule was deleted or disabled meanwhile no longer counts; none left = all calendars.
    val keys = calendars.mapNotNull { CalendarFilter.valid(it, s) }
    return keys.isEmpty() || keys.any { CalendarFilter.matches(e, it, s) }
  }

  companion object {
    const val DEFAULT_OPACITY = 0.94f
    const val MIN_OPACITY = 0.2f
    const val MIN_TEXT_SCALE = 0.5f
    const val MAX_TEXT_SCALE = 2f
  }
}

/** One stored [WidgetConfig] per widget id. */
object WidgetConfigStore {
  private const val PREFS = "widget_config"
  private fun key(id: Int) = "widget_$id"

  fun load(context: Context, id: Int): WidgetConfig {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(id), null) ?: return WidgetConfig()
    return runSafely { AppJson.decodeFromString<WidgetConfig>(raw) }.getOrDefault(WidgetConfig()).sanitized()
  }

  fun save(context: Context, id: Int, config: WidgetConfig) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(key(id), AppJson.encodeToString(config.sanitized())) }

  fun delete(context: Context, ids: IntArray) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { ids.forEach { remove(key(it)) } }
}
