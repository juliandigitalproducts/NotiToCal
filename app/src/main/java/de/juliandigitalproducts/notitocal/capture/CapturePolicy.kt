// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.capture

import de.juliandigitalproducts.notitocal.data.AppSettings

/**
 * Decides whether a notification of [pkg] may be analysed. The app list (monitoredApps) is always the
 * outer gate: an app the user removed from the list is never read, whatever a source switch says.
 * WhatsApp, SMS/RCS and Telegram (User-API local mode) additionally need their own source switch.
 */
object CapturePolicy {
  fun isActive(s: AppSettings, pkg: String): Boolean {
    if (pkg !in s.monitoredApps) return false
    return when (pkg) {
      WhatsAppSource.PKG_PERSONAL -> s.whatsappEnabled && s.whatsappPersonalEnabled
      WhatsAppSource.PKG_BUSINESS -> s.whatsappEnabled && s.whatsappBusinessEnabled
      in SmsRcsSource.PACKAGES -> s.smsRcsEnabled
      in TelegramUserApiSource.TELEGRAM_PACKAGES -> s.notificationsEnabled || s.telegramApiEnabled
      else -> s.notificationsEnabled
    }
  }

  /** Pictures from notifications go to an AI model only with the separate opt-in, never just because OCR is on. */
  fun sendPictureToAi(s: AppSettings): Boolean = s.notificationImagesToAi && s.visionForImages()
}
