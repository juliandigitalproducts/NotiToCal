package de.juliandigitalproducts.notitocal.pipeline

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.analysis.Decision
import de.juliandigitalproducts.notitocal.capture.ScreenshotFolders
import de.juliandigitalproducts.notitocal.capture.VoiceNotes
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AnalysisSchedule
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import de.juliandigitalproducts.notitocal.model.runSafely

/**
 * Battery saving: messages are captured instantly into a local queue and analysed together on a schedule
 * ([Schedule]). Urgent messages (today, "in 20 minutes" …) skip the queue.
 */
class Batch(private val app: NotiToCalApp) {
  private val lock = Mutex()

  /** [processed]: messages analysed in this run; [failed]: messages that failed and stay queued for the next run. */
  data class Summary(val processed: Int, val added: Int, val asked: Int, val memos: Int, val failed: Int = 0)

  /** True when this message should be analysed right away instead of waiting for the next scheduled run. */
  fun analyzeNow(c: Captured): Boolean {
    val s = app.settings.current
    if (s.analysisSchedule == AnalysisSchedule.IMMEDIATE) return true
    if (!s.urgentImmediately) return false
    val now = LocalDateTime.now()
    return Schedule.isUrgent(c.text, now, Schedule.nextRun(s, now))
  }

  fun enqueue(c: Captured) {
    app.log.enqueue(c)
    schedule(replace = false)
  }

  /** Voice messages are collected as a marker (sender, app); the file is looked up in the folder at analysis time. */
  fun voiceNow(): Boolean {
    val s = app.settings.current
    return !s.voiceBatched || s.analysisSchedule == AnalysisSchedule.IMMEDIATE
  }

  /**
   * Analyses everything in the queue. One summary notification instead of one per entry.
   * [onProgress] reports (finished steps, all steps): every queued message is a step, the voice memos and the
   * polled sources are one step each.
   */
  suspend fun runNow(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Summary = withContext(Dispatchers.IO) { runLocked(onProgress) }

  // "Analyse now" starts from the UI (main thread); place lookups, CalDAV and the database must not run there.
  private suspend fun runLocked(onProgress: (done: Int, total: Int) -> Unit): Summary = lock.withLock {
    val items = app.log.queuedItems()
    var added = 0
    var asked = 0
    var failed = 0
    val voices = items.filter { it.captured.source == SourceType.VOICE_MEMO }
    val messages = items - voices.toSet()
    val total = messages.size + (if (voices.isNotEmpty()) 1 else 0) + 1
    for ((index, q) in messages.withIndex()) {
      onProgress(index, total)
      val res = runSafely { app.pipeline.process(q.captured, quiet = true) }
      // A failed message stays queued for the next run (C16), unless it keeps failing for a day.
      if (res.isFailure && Duration.between(Instant.ofEpochMilli(q.captured.postedAt), Instant.now()).toHours() < 24) {
        failed++
        continue
      }
      app.log.dequeue(q.id)
      when (res.getOrNull()?.decision) {
        Decision.AUTO -> if (res.getOrNull()?.logId?.let { app.log.statusOf(it) } in setOf(Status.ADDED, Status.PARTIAL)) added++
        Decision.ASK -> asked++
        else -> Unit
      }
    }
    var memos = 0
    if (voices.isNotEmpty()) {
      onProgress(messages.size, total)
      val oldest = voices.minOf { it.captured.postedAt }
      val minutes = Duration.between(Instant.ofEpochMilli(oldest), Instant.now()).toMinutes().toInt() + 30
      // Newest file first ↔ newest notification first, so each memo keeps its sender.
      val senders = voices.sortedByDescending { it.captured.postedAt }.map { it.captured.sender }
      memos = runSafely {
        VoiceNotes.scan(app, senders, maxAgeMinutes = minutes, limit = voices.size, quiet = true)
      }.getOrDefault(0)
      voices.forEach { app.log.dequeue(it.id) }
    }
    onProgress(total - 1, total)
    runSafely { ScreenshotFolders.scan(app, maxAgeMinutes = 12 * 60, limit = 5) }
    runSafely { de.juliandigitalproducts.notitocal.capture.ImapClient.pollEnabledAccounts(app, maxPerAccount = 5) }
    runSafely { de.juliandigitalproducts.notitocal.capture.TelegramSource.pollUpdates(app) }
    runSafely { de.juliandigitalproducts.notitocal.capture.TelegramUserApiSource.pollMessages(app) }
    runSafely { de.juliandigitalproducts.notitocal.capture.IcsCalendarSource.scanFolder(app) }
    onProgress(total, total)
    if (added > 0 || asked > 0) Notifier.batchSummary(app, added, asked)
    app.log.dropFinalEntries(app.settings.current.deleteAfterProcessing)
    // "Keep history for N days" also while the app keeps running for weeks (before: only at the start of the process).
    app.log.prune(app.settings.current.keepLogDays)
    Summary(items.size - failed, added, asked, memos, failed)
  }

  /**
   * Plans the next run with WorkManager: [replace] after settings changes, otherwise an existing plan is kept.
   * Nothing is planned while the queue is empty – enqueueing a message plans the run, so an idle phone never wakes up.
   */
  fun schedule(replace: Boolean, fromWorker: Boolean = false) {
    val s = app.settings.current
    val wm = WorkManager.getInstance(app)
    val next = Schedule.nextRun(s, LocalDateTime.now())
    if (next == null || app.log.queued.value == 0) {
      if (!fromWorker) wm.cancelUniqueWork(WORK)
      return
    }
    val delay = Duration.between(LocalDateTime.now(), next).toMillis().coerceAtLeast(0)
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(
        when {
          s.analyzeOnlyWifi -> NetworkType.UNMETERED
          s.engine == AiEngine.CLOUD || s.engine == AiEngine.SELF_HOSTED -> NetworkType.CONNECTED
          else -> NetworkType.NOT_REQUIRED
        },
      )
      .setRequiresCharging(s.analyzeOnlyCharging)
      .build()
    val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
      .setInitialDelay(delay, TimeUnit.MILLISECONDS)
      .setConstraints(constraints)
      .build()
    // From inside the running worker REPLACE would cancel itself; APPEND_OR_REPLACE starts after it.
    val policy = when {
      fromWorker -> ExistingWorkPolicy.APPEND_OR_REPLACE
      replace -> ExistingWorkPolicy.REPLACE
      else -> ExistingWorkPolicy.KEEP
    }
    wm.enqueueUniqueWork(WORK, policy, request)
    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(NEXT_RUN, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
  }

  /** Planned time of the next analysis (for the UI), or null. */
  fun plannedRun(): LocalDateTime? {
    if (app.settings.current.analysisSchedule == AnalysisSchedule.IMMEDIATE) return null
    val ms = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(NEXT_RUN, 0)
    return if (ms > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()) else null
  }

  companion object {
    const val WORK = "collected_analysis"
    private const val PREFS = "batch"
    private const val NEXT_RUN = "next_run"
  }
}

class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val app = applicationContext as NotiToCalApp
    app.batch.runNow()
    // Messages that arrived during the run get the next slot.
    app.batch.schedule(replace = false, fromWorker = true)
    return Result.success()
  }
}
