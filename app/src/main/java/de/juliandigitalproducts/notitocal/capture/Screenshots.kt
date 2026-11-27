// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.capture

import android.content.Context
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.ScreenshotAppFilter
import de.juliandigitalproducts.notitocal.model.runSafely

/** Which screenshots are analysed, and clean-up of their recognised text. */
object Screenshots {
  // "Screenshot_20261111_155400_WhatsApp.jpg" (Samsung), "Screenshot_2026-11-11-15-54-00-123_com.whatsapp.jpg" (Xiaomi).
  private val APP_SUFFIX = Regex("""^.*?\d[\d_\-.]*[_\-]([^_\d][^_]*?)$""")
  private val STATUS_BAR = Regex("""^\s*\d{1,2}[:.]\d{2}(?:\s+\S{1,2})*\s*$""")

  /**
   * The app named at the end of a screenshot file name, or null when the name carries none (Pixel and others).
   */
  fun appOf(fileName: String?): String? {
    val base = fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.trim().orEmpty()
    if (base.isEmpty()) return null
    return APP_SUFFIX.matchEntire(base)?.groupValues?.get(1)?.trim()?.takeIf { it.any(Char::isLetter) }
  }

  /**
   * Whether a screenshot of [app] (from [appOf]) is analysed. Screenshots without an app in their name always are,
   * because the app cannot be told. [labelOf] gives an installed app's name for a package.
   */
  fun allowed(app: String?, s: AppSettings, labelOf: (String) -> String?): Boolean {
    if (app == null || s.screenshotAppFilter == ScreenshotAppFilter.ALL) return true
    val packages = when (s.screenshotAppFilter) {
      ScreenshotAppFilter.NOTIFICATION_APPS -> s.monitoredApps
      else -> s.screenshotApps
    }
    val wanted = key(app)
    return packages.any { pkg -> key(pkg) == wanted || key(labelOf(pkg).orEmpty()) == wanted }
  }

  fun allowed(context: Context, fileName: String?, s: AppSettings): Boolean =
    allowed(appOf(fileName), s) { pkg -> label(context, pkg) }

  /**
   * Drops the status bar OCR reads as the first line of a screenshot ("09:33 T i:"), so its clock is not taken as
   * the time of an appointment. A first line with real words ("19:30 Konzert") stays.
   */
  fun dropStatusBar(text: String): String {
    val lines = text.lines()
    val first = lines.firstOrNull() ?: return text
    return if (STATUS_BAR.matches(first)) lines.drop(1).joinToString("\n").trim() else text
  }

  /** Letters and digits only, lower case: "Samsung Internet" and "samsung_internet" compare equal. */
  private fun key(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

  private fun label(context: Context, pkg: String): String? = runSafely {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
  }.getOrNull()
}
