// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Material You: colours follow the wallpaper (dynamic color). */
@Composable
fun NotiToCalTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val context = LocalContext.current
  // minSdk is 31, so dynamic color is always available.
  val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
  MaterialTheme(colorScheme = scheme, content = content)
}
