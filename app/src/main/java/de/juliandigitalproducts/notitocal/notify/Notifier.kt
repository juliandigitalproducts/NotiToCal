package de.juliandigitalproducts.notitocal.notify

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.ui.MainActivity
import de.juliandigitalproducts.notitocal.ui.describeWhen
import kotlinx.coroutines.launch

object Notifier {
  const val CH_CONFIRM = "confirm"
  const val CH_ADDED = "added"
  const val CH_STATUS = "status"
  const val EXTRA_ENTRY = "entry_id"
  private const val PROGRESS_ID = 1
  private const val BATCH_ID = 2

  fun createChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannels(listOf(
      NotificationChannel(CH_CONFIRM, context.getString(R.string.channel_confirm), NotificationManager.IMPORTANCE_DEFAULT),
      NotificationChannel(CH_ADDED, context.getString(R.string.channel_added), NotificationManager.IMPORTANCE_LOW),
      NotificationChannel(CH_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
    ))
  }

  /** POST_NOTIFICATIONS exists from Android 13; below that notifications are allowed unless the user blocked them. */
  fun canPost(context: Context): Boolean =
    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
      NotificationManagerCompat.from(context).areNotificationsEnabled()

  @SuppressLint("MissingPermission")
  private fun post(context: Context, id: Int, n: android.app.Notification) {
    if (!canPost(context)) return
    try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
  }

  private fun openEntry(context: Context, id: Long): PendingIntent =
    PendingIntent.getActivity(
      context, id.toInt(),
      Intent(context, MainActivity::class.java).putExtra(EXTRA_ENTRY, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  private fun action(context: Context, action: String, id: Long): PendingIntent =
    PendingIntent.getBroadcast(
      context, (id * 10 + action.hashCode() % 10).toInt(),
      Intent(context, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ENTRY, id),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  private fun icon(d: Detection) = if (d.kind == Kind.EVENT) R.drawable.ic_event else R.drawable.ic_task

  /** [quiet]: low-priority suggestion (address only) without sound or heads-up. */
  fun confirm(context: Context, id: Long, d: Detection, c: Captured, quiet: Boolean = false) {
    if (!canPost(context)) return
    val whenText = describeWhen(context, d)
    val from = listOf(c.appLabel, c.sender).filter { it.isNotBlank() }.joinToString(" · ")
    val body = listOfNotNull(whenText, d.location, from.takeIf { it.isNotBlank() }?.let { context.getString(R.string.notif_from, it) }).joinToString("\n")
    val n = NotificationCompat.Builder(context, CH_CONFIRM)
      .setSmallIcon(icon(d))
      .setContentTitle((if (d.kind == Kind.EVENT) context.getString(R.string.notif_event_found) else context.getString(R.string.notif_task_found)) + ": " + d.title)
      .setContentText(whenText ?: from)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setContentIntent(openEntry(context, id))
      .setAutoCancel(true)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setSilent(quiet)
      .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
      .addAction(R.drawable.ic_check, context.getString(R.string.action_add), action(context, ActionReceiver.ADD, id))
      .addAction(R.drawable.ic_close, context.getString(R.string.action_dismiss), action(context, ActionReceiver.DISMISS, id))
      .build()
    post(context, id.toInt(), n)
  }

  fun added(context: Context, id: Long, d: Detection, targets: List<TargetType>) {
    if (!canPost(context)) return
    val n = NotificationCompat.Builder(context, CH_ADDED)
      .setSmallIcon(icon(d))
      .setContentTitle(context.getString(if (d.kind == Kind.EVENT) R.string.notif_event_added else R.string.notif_task_added, d.title))
      .setContentText(listOfNotNull(describeWhen(context, d), targets.joinToString { targetLabel(context, it) }).joinToString(" · "))
      .setContentIntent(openEntry(context, id))
      .setAutoCancel(true)
      .addAction(R.drawable.ic_close, context.getString(R.string.action_undo_hint), openEntry(context, id))
      .build()
    post(context, id.toInt(), n)
  }

  fun merged(context: Context, id: Long, d: Detection) {
    if (!canPost(context)) return
    val text = context.getString(R.string.notif_merged)
    val n = NotificationCompat.Builder(context, CH_ADDED)
      .setSmallIcon(icon(d))
      .setContentTitle(d.title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(openEntry(context, id))
      .setAutoCancel(true)
      .build()
    post(context, id.toInt(), n)
  }

  fun duplicateReadonly(context: Context, id: Long, d: Detection, message: String) {
    if (!canPost(context)) return
    val n = NotificationCompat.Builder(context, CH_STATUS)
      .setSmallIcon(R.drawable.ic_event)
      .setContentTitle(d.title)
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(message))
      .setContentIntent(openEntry(context, id))
      .setAutoCancel(true)
      .build()
    post(context, (id.toInt() xor 0x51A7), n)
  }

  fun modelUnavailable(context: Context) {
    if (!canPost(context)) return
    val text = context.getString(R.string.model_shared_missing)
    val n = NotificationCompat.Builder(context, CH_STATUS)
      .setSmallIcon(R.drawable.ic_error)
      .setContentTitle(context.getString(R.string.model_shared_missing_title))
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(openEntry(context, 0))
      .setAutoCancel(true)
      .build()
    post(context, 48, n)
  }

  fun failed(context: Context, id: Long, d: Detection, message: String) {
    if (!canPost(context) || !(context.applicationContext as NotiToCalApp).settings.current.notifyErrors) return
    val n = NotificationCompat.Builder(context, CH_STATUS)
      .setSmallIcon(R.drawable.ic_error)
      .setContentTitle(context.getString(R.string.notif_failed, d.title))
      .setContentText(message)
      .setContentIntent(openEntry(context, id))
      .setAutoCancel(true)
      .build()
    post(context, id.toInt(), n)
  }

  /** For interactive targets (calendar app, share sheet) the user opens the intent from a notification. */
  fun openIntent(context: Context, id: Long, d: Detection, intent: Intent) {
    if (!canPost(context)) return
    val pi = PendingIntent.getActivity(context, (id * 31).toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val n = NotificationCompat.Builder(context, CH_CONFIRM)
      .setSmallIcon(icon(d))
      .setContentTitle(d.title)
      .setContentText(context.getString(R.string.notif_tap_to_open))
      .setContentIntent(pi)
      .setAutoCancel(true)
      .build()
    post(context, (id * 31).toInt(), n)
  }

  fun progress(context: Context, text: String?) {
    if (text == null) { NotificationManagerCompat.from(context).cancel(PROGRESS_ID); return }
    post(context, PROGRESS_ID, NotificationCompat.Builder(context, CH_STATUS)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(context.getString(R.string.app_name))
      .setContentText(text)
      .setProgress(0, 0, true)
      .setOngoing(true)
      .build())
  }

  fun cancel(context: Context, id: Long) = NotificationManagerCompat.from(context).cancel(id.toInt())

  fun targetLabel(context: Context, t: TargetType): String = context.getString(when (t) {
    TargetType.DEVICE_CALENDAR -> R.string.target_device_calendar
    TargetType.CALENDAR_APP -> R.string.target_calendar_app
    TargetType.TASK_PROVIDER -> R.string.target_task_provider
    TargetType.GOOGLE_TASKS -> R.string.target_google_tasks
    TargetType.SAMSUNG_REMINDER -> R.string.target_samsung_reminder
    TargetType.TODOIST -> R.string.target_todoist
    TargetType.CALDAV -> R.string.target_caldav
    TargetType.NOTION -> R.string.target_notion
    TargetType.SELF_HOSTED -> R.string.target_self_hosted
    TargetType.WEBHOOK -> R.string.target_webhook
    TargetType.AUTOMATION_BROADCAST -> R.string.target_broadcast
    TargetType.SHARE -> R.string.target_share
    TargetType.AI_AGENT -> R.string.target_ai_agent
  })

  /** One notification after a collected analysis instead of one per entry. */
  fun batchSummary(context: Context, added: Int, asked: Int) {
    val s = (context.applicationContext as NotiToCalApp).settings.current
    val parts = buildList {
      if (added > 0 && s.notifyOnAdd) add(context.resources.getQuantityString(R.plurals.batch_added, added, added))
      if (asked > 0 && s.notifyConfirm) add(context.resources.getQuantityString(R.plurals.batch_asked, asked, asked))
    }
    if (parts.isEmpty() || !canPost(context)) return
    val n = NotificationCompat.Builder(context, CH_ADDED)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(context.getString(R.string.batch_title))
      .setContentText(parts.joinToString(" · "))
      .setContentIntent(
        PendingIntent.getActivity(
          context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
      )
      .setAutoCancel(true)
      .build()
    post(context, BATCH_ID, n)
  }

  /** Shows what the AI routine answered (agenda, reply draft, research …). */
  fun agentResult(context: Context, id: Long, d: Detection, answer: String) {
    if (!canPost(context)) return
    val share = Intent.createChooser(
      Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, d.title).putExtra(Intent.EXTRA_TEXT, answer), d.title,
    )
    val n = NotificationCompat.Builder(context, CH_ADDED)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(context.getString(R.string.notif_agent_done, d.title))
      .setContentText(answer.take(200))
      .setStyle(NotificationCompat.BigTextStyle().bigText(answer.take(4000)))
      .setContentIntent(openEntry(context, id))
      .setAutoCancel(true)
      .addAction(R.drawable.ic_check, context.getString(R.string.share), PendingIntent.getActivity(
        context, (id * 37).toInt(), share, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      ))
      .build()
    post(context, (id * 37).toInt(), n)
  }

  /** Transcript / summary of a voice memo that was analysed automatically (Anti-Vocale style). */
  fun memo(context: Context, key: Int, title: String, text: String, entryId: Long?) {
    if (!canPost(context)) return
    val b = NotificationCompat.Builder(context, CH_ADDED)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText(text.take(200))
      .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(4000)))
      .setAutoCancel(true)
    if (entryId != null) b.setContentIntent(openEntry(context, entryId))
    post(context, 100_000 + key, b.build())
  }
}

/** Handles "Add" / "Dismiss" from confirmation notifications. */
class ActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getLongExtra(Notifier.EXTRA_ENTRY, -1)
    if (id < 0) return
    Notifier.cancel(context, id)
    when (intent.action) {
      // Delivery talks to calendars and web services and can take longer than a receiver may live (C15):
      // WorkManager keeps it alive and retries. KEEP: a second tap does not add the entry twice.
      ADD -> WorkManager.getInstance(context).enqueueUniqueWork(
        "deliver_entry_$id", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<DeliverEntryWorker>()
          .setInputData(workDataOf(Notifier.EXTRA_ENTRY to id))
          .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
          .build(),
      )
      DISMISS -> {
        val app = context.applicationContext as NotiToCalApp
        val pending = goAsync()
        app.scope.launch {
          try { app.log.update(id, status = Status.DISMISSED) } finally { pending.finish() }
        }
      }
    }
  }

  companion object {
    const val ADD = "de.juliandigitalproducts.notitocal.ADD"
    const val DISMISS = "de.juliandigitalproducts.notitocal.DISMISS"
  }
}

/** Delivers a confirmed entry outside the notification's broadcast receiver (C15). */
class DeliverEntryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val id = inputData.getLong(Notifier.EXTRA_ENTRY, -1)
    val app = applicationContext as NotiToCalApp
    val entry = app.log.get(id) ?: return Result.success()
    if (entry.status == Status.ADDED) return Result.success()
    // A partly delivered entry only goes to the targets that failed (no second copy elsewhere).
    val results = app.pipeline.deliverEntry(entry, fromUi = false)
    // Only network failures are worth another try; a missing permission or setting will not fix itself.
    return if (results.any { !it.ok && it.transient } && runAttemptCount < 2) Result.retry() else Result.success()
  }
}
