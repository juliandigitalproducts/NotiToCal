package de.juliandigitalproducts.notitocal

import android.app.Application
import de.juliandigitalproducts.notitocal.analysis.Analyzer
import de.juliandigitalproducts.notitocal.analysis.SharedModel
import de.juliandigitalproducts.notitocal.capture.AntiVocaleWorker
import de.juliandigitalproducts.notitocal.data.LogRepository
import de.juliandigitalproducts.notitocal.data.SecretStore
import de.juliandigitalproducts.notitocal.data.SettingsRepository
import de.juliandigitalproducts.notitocal.notify.Notifier
import de.juliandigitalproducts.notitocal.pipeline.Batch
import de.juliandigitalproducts.notitocal.pipeline.Pipeline
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotiToCalApp : Application() {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  lateinit var settings: SettingsRepository
  lateinit var secrets: SecretStore
  lateinit var log: LogRepository
  lateinit var analyzer: Analyzer
  lateinit var pipeline: Pipeline
  lateinit var batch: Batch

  override fun onCreate() {
    super.onCreate()
    instance = this
    settings = SettingsRepository(this, scope)
    scope.launch(Dispatchers.IO) { sweepCache() }
    secrets = SecretStore(this)
    log = LogRepository(this)
    analyzer = Analyzer(this, secrets)
    pipeline = Pipeline(this)
    batch = Batch(this)
    Notifier.createChannels(this)
    // "Diagnostic log" follows the saved setting, also after a restart (before, it was on again until toggled).
    // Started first and on its own, so it does not wait for the log housekeeping below (or stop if that fails).
    scope.launch {
      settings.state
        .map { it.diagnosticLoggingEnabled }
        .distinctUntilChanged()
        .collect { on ->
          de.juliandigitalproducts.notitocal.diagnostics.DiagnosticLogger.enabled = on
          if (!on) de.juliandigitalproducts.notitocal.diagnostics.DiagnosticLogger.clear()
        }
    }
    scope.launch {
      log.prune(settings.current.keepLogDays)
      log.reload()
      log.refreshQueued()
      val modelUri = settings.current.onDeviceModelUri
      if (modelUri.isNotBlank() && !SharedModel.accessible(this@NotiToCalApp, modelUri)) {
        Notifier.modelUnavailable(this@NotiToCalApp)
      }
      // Re-plan the Anti-Vocale folder check whenever its settings change (and once at start).
      launch {
        settings.state
          .map { listOf(it.antiVocaleActive, it.antiVocaleCheckMinutes) }
          .distinctUntilChanged()
          .collect { AntiVocaleWorker.apply(this@NotiToCalApp, settings.current) }
      }
      // Re-plan the location samples whenever they are switched or their interval changes.
      launch {
        settings.state
          .map { listOf(it.locationSampling, it.locationSampleMinutes) }
          .distinctUntilChanged()
          .collect { de.juliandigitalproducts.notitocal.places.LocationSampler.apply(this@NotiToCalApp, settings.current) }
      }
      // Re-plan the collected analysis whenever its settings change (and once at start).
      settings.state
        .map { listOf(it.analysisSchedule, it.analysisTimes, it.analyzeOnlyWifi, it.analyzeOnlyCharging, it.engine) }
        .distinctUntilChanged()
        .collectIndexed { i, _ ->
          // At process start keep an existing plan (otherwise an interval would restart with every app start).
          batch.schedule(replace = i > 0)
        }
    }
  }

  companion object {
    lateinit var instance: NotiToCalApp
      private set
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    // In the background or under memory pressure the on-device model is the largest thing to give back (C28).
    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) de.juliandigitalproducts.notitocal.analysis.OnDeviceLlm.releaseIfIdle()
  }

  /** Photos, recordings and shared copies left behind by a crash or a cancelled capture (C23). */
  private fun sweepCache() {
    val limit = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    // "voice": recordings left by versions before 1.5.0 (they could still record memos).
    listOf("photos", "voice", "shared").forEach { dir ->
      java.io.File(cacheDir, dir).listFiles()?.filter { it.isFile && it.lastModified() < limit }?.forEach { it.delete() }
    }
  }
}
