// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.pipeline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.analysis.LocationEnricher
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.notify.Notifier
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Small helpers for work that is postponed: location lookups while offline, confirmations during quiet hours. */
object Deferred {
  /** Marker in [de.juliandigitalproducts.notitocal.model.Detection.locationSource]: lookup postponed until online. */
  const val LOCATION_PENDING = "PENDING_OFFLINE"

  fun isOnline(context: Context): Boolean = runSafely {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runSafely true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runSafely false
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }.getOrDefault(true)

  /** Runs [LocationRetryWorker] as soon as a network connection is available. */
  fun scheduleLocationRetry(context: Context) {
    val req = OneTimeWorkRequestBuilder<LocationRetryWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(LOCATION_WORK, ExistingWorkPolicy.KEEP, req)
  }

  /** Minutes from [now] until the configured quiet hours end, or null when outside quiet hours. */
  fun minutesUntilQuietEnd(s: AppSettings, now: LocalTime = LocalTime.now()): Long? {
    if (!s.quietHours) return null
    val m = now.hour * 60 + now.minute
    val inQuiet = if (s.quietStartMinute <= s.quietEndMinute) m in s.quietStartMinute until s.quietEndMinute
    else m >= s.quietStartMinute || m < s.quietEndMinute
    if (!inQuiet) return null
    val diff = (s.quietEndMinute - m + 24 * 60) % (24 * 60)
    return diff.toLong().coerceAtLeast(1)
  }

  /**
   * A confirmation was held back during quiet hours: after they end, one summary notification lists everything that
   * is still waiting. The first hold-back of a quiet period decides the time window (KEEP).
   */
  fun scheduleQuietSummary(context: Context, s: AppSettings) {
    val minutes = minutesUntilQuietEnd(s) ?: return
    val req = OneTimeWorkRequestBuilder<QuietSummaryWorker>()
      .setInitialDelay(minutes, TimeUnit.MINUTES)
      .setInputData(workDataOf(KEY_SINCE to System.currentTimeMillis()))
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(QUIET_WORK, ExistingWorkPolicy.KEEP, req)
  }

  internal const val LOCATION_WORK = "notitocal-location-retry"
  internal const val QUIET_WORK = "notitocal-quiet-summary"
  internal const val KEY_SINCE = "since"
}

/** Resolves locations of events that were captured while the phone was offline. */
class LocationRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val app = applicationContext as NotiToCalApp
    val s = app.settings.current
    val since = System.currentTimeMillis() - Duration.ofDays(7).toMillis()
    val pending = app.log.entriesSince(since).filter {
      it.detection.locationSource == Deferred.LOCATION_PENDING && it.detection.kind == Kind.EVENT
    }
    if (pending.isEmpty()) return Result.success()
    if (!Deferred.isOnline(app)) return Result.retry()
    for (e in pending) {
      val cleared = e.detection.copy(locationSource = null)
      // Turned off in the meantime: only remove the marker.
      val enriched = if (s.locationEnrichmentEnabled) {
        runSafely { LocationEnricher.enrich(app, cleared, e.text, s, e.sender) }.getOrDefault(cleared)
      } else cleared
      app.log.update(e.id, d = enriched)
      if ((e.status == Status.ADDED || e.status == Status.PARTIAL) && enriched.location != e.detection.location && !enriched.location.isNullOrBlank()) {
        // The found address also reaches CalDAV, the server and the other targets that already have the entry.
        runSafely { app.pipeline.syncUpdate(e, enriched) }
      }
    }
    return Result.success()
  }
}

/** After quiet hours: one notification for the suggestions that were held back. */
class QuietSummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val app = applicationContext as NotiToCalApp
    val since = inputData.getLong(Deferred.KEY_SINCE, System.currentTimeMillis() - Duration.ofHours(24).toMillis())
    val waiting = app.log.entriesSince(since).count {
      it.status == Status.PENDING && it.detection.kind in setOf(Kind.EVENT, Kind.TASK)
    }
    if (waiting > 0) Notifier.batchSummary(app, added = 0, asked = waiting)
    return Result.success()
  }
}
