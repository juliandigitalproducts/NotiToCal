// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.data.AppSettings
import java.util.concurrent.TimeUnit

/** Checks the Anti-Vocale export folder on the interval chosen in the settings (WorkManager allows 15 minutes and more). */
class AntiVocaleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val app = applicationContext as NotiToCalApp
    val s = app.settings.current
    if (!s.antiVocaleActive || s.paused) return Result.success()
    // Generous window: the tracker remembers what was read, the window only keeps a newly chosen folder from flooding.
    VoiceNotes.scan(app, maxAgeMinutes = maxOf(s.antiVocaleCheckMinutes * 2, 24 * 60), limit = 10, quiet = true)
    return Result.success()
  }

  companion object {
    private const val WORK = "anti_vocale_check"
    val INTERVALS = listOf(15, 30, 60, 180, 360, 0)

    /** Plans or cancels the periodic check to match [settings]. */
    fun apply(context: Context, settings: AppSettings) {
      val wm = WorkManager.getInstance(context)
      val minutes = settings.antiVocaleCheckMinutes
      if (!settings.antiVocaleActive || minutes <= 0) {
        wm.cancelUniqueWork(WORK)
        return
      }
      val request = PeriodicWorkRequestBuilder<AntiVocaleWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).build()
      wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
  }
}
