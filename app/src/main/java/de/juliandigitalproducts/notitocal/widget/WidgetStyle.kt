// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.widget

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.IdRes
import de.juliandigitalproducts.notitocal.R
import kotlin.math.roundToInt

/** Widget colours of one mode, read from the widget colour resources (no second copy of them). */
internal data class WidgetPalette(val surface: Int, val text: Int, val secondary: Int, val accent: Int, val badge: Int)

/**
 * Applies a [WidgetConfig] to widget RemoteViews.
 * Every colour is set as a day / night pair ([RemoteViews.setColorInt]): the launcher follows the dark mode by itself
 * for [WidgetTheme.SYSTEM], and every update sets all values again, so a reused widget view never keeps an old colour.
 */
internal object WidgetStyle {

  fun palette(context: Context, night: Boolean): WidgetPalette {
    val config = Configuration(context.resources.configuration).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }
    val themed = context.createConfigurationContext(config)
    fun color(@ColorRes id: Int) = themed.getColor(id)
    return WidgetPalette(
      color(R.color.widget_surface), color(R.color.widget_text), color(R.color.widget_text_secondary),
      color(R.color.widget_accent), color(R.color.widget_badge),
    )
  }

  /** The palettes used in day and in night mode for [theme]. */
  data class ThemeColors(val day: WidgetPalette, val night: WidgetPalette)

  fun palettes(context: Context, theme: WidgetTheme): ThemeColors {
    val light = palette(context, night = false)
    val dark = palette(context, night = true)
    return when (theme) {
      WidgetTheme.SYSTEM -> ThemeColors(light, dark)
      WidgetTheme.LIGHT -> ThemeColors(light, light)
      WidgetTheme.DARK -> ThemeColors(dark, dark)
    }
  }

  /** Background layer: opacity and colour. */
  fun background(views: RemoteViews, config: WidgetConfig, colors: ThemeColors) {
    views.setInt(R.id.widget_bg, "setImageAlpha", (config.opacity * 255).roundToInt())
    tint(views, R.id.widget_bg, colors) { it.surface }
  }

  /** Text size ([baseSp] × the zoom) and colour. */
  fun text(views: RemoteViews, @IdRes id: Int, baseSp: Float, config: WidgetConfig, colors: ThemeColors, pick: (WidgetPalette) -> Int) {
    views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, baseSp * config.textScale)
    views.setColorInt(id, "setTextColor", pick(colors.day), pick(colors.night))
  }

  fun tint(views: RemoteViews, @IdRes id: Int, colors: ThemeColors, pick: (WidgetPalette) -> Int) =
    views.setColorInt(id, "setColorFilter", pick(colors.day), pick(colors.night))
}
