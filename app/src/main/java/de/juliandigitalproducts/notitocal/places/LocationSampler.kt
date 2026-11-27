// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.runSafely
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Records where the user is, like the Google Maps timeline but rarely: once per chosen interval (default hourly),
 * with the battery-saving network/fused position. A recent position another app already asked for is reused, so
 * usually no new location request is made at all. Everything stays in [PlaceStore] on the device.
 */
class LocationSampler(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val app = applicationContext as NotiToCalApp
    val s = app.settings.current
    if (!s.locationSampling || s.paused || !canSample(app)) return Result.success()
    val location = position(app, s.locationSampleMinutes) ?: return Result.success()
    record(app, location.latitude, location.longitude, System.currentTimeMillis(), s.locationSampleMinutes)
    return Result.success()
  }

  companion object {
    private const val WORK = "location_sampling"
    private const val PREFS = "location_sampling"
    val INTERVALS = listOf(30, 60, 120, 240)

    /** Plans or cancels the sampling to match [settings]. */
    fun apply(context: Context, settings: AppSettings) {
      val wm = WorkManager.getInstance(context)
      if (!settings.locationSampling) {
        wm.cancelUniqueWork(WORK)
        return
      }
      val request = PeriodicWorkRequestBuilder<LocationSampler>(settings.locationSampleMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
        .build()
      wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Sampling in the background needs a location permission and "Allow all the time". */
    fun canSample(context: Context): Boolean =
      granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) && granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String) =
      ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Stores a sample. A sample at the same place as the previous one extends that stay; a different place starts a
     * new visit there.
     */
    fun record(context: Context, lat: Double, lon: Double, at: Long, minutes: Int) {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val previous = prefs.getLong("last_place", -1L)
      val store = PlaceStore.get(context)
      val id = store.sample(lat, lon, at, minutes, previous)
      prefs.edit { putLong("last_place", id) }
    }

    /** A position at most half an interval old, from any app; otherwise one low-power request (30 s at most). */
    @SuppressLint("MissingPermission")
    private suspend fun position(context: Context, intervalMinutes: Int): Location? {
      val lm = context.getSystemService(LocationManager::class.java) ?: return null
      val maxAge = intervalMinutes * 30_000L
      val cached = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runSafely { lm.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time <= maxAge }
        .minByOrNull { it.accuracy }
      if (cached != null) return cached
      val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { runSafely { lm.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return null
      val request = LocationRequest.Builder(0L).setQuality(LocationRequest.QUALITY_LOW_POWER).setDurationMillis(30_000L).build()
      return withTimeoutOrNull(35_000L) {
        suspendCancellableCoroutine { cont ->
          val signal = CancellationSignal()
          cont.invokeOnCancellation { signal.cancel() }
          runSafely {
            lm.getCurrentLocation(provider, request, signal, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
          }.onFailure { if (cont.isActive) cont.resume(null) }
        }
      }
    }
  }
}
