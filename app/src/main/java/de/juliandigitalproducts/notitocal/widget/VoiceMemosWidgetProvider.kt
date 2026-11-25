package de.juliandigitalproducts.notitocal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.VoiceNotes
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Large widget 1: Shows Anti-Vocale / Voice Memo entries that contain Appointments & Tasks (filtered).
 * Header: title, "Photo appointment", "Open app" and reload.
 */
class VoiceMemosWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    appWidgetIds.forEach { render(context, appWidgetManager, it, unfiltered = false) }
    super.onUpdate(context, appWidgetManager, appWidgetIds)
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) = WidgetConfigStore.delete(context, appWidgetIds)

  override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(context, intent)
    if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE || intent.action == ACTION_REFRESH) {
      triggerFolderScanAndRefresh(context)
    }
  }

  companion object {
    const val ACTION_REFRESH = "de.juliandigitalproducts.notitocal.widget.REFRESH_MEMOS"
    private const val REQUEST_CAMERA = 10_000
    private const val REQUEST_OPEN = 40_000
    private const val REQUEST_REFRESH = 30_000

    /** Builds one memo widget with its [WidgetConfig]; shared by the filtered and the unfiltered widget. */
    internal fun render(context: Context, mgr: AppWidgetManager, widgetId: Int, unfiltered: Boolean) {
      val config = WidgetConfigStore.load(context, widgetId)
      val colors = WidgetStyle.palettes(context, config.theme)
      val serviceClass = if (unfiltered) UnfilteredVoiceMemosWidgetService::class.java else VoiceMemosWidgetService::class.java
      val serviceIntent = Intent(context, serviceClass).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        data = toUri(Intent.URI_INTENT_SCHEME).toUri()
      }
      // Request codes per widget and kind, so no two widgets share (and overwrite) a PendingIntent.
      val slot = widgetId * 2 + if (unfiltered) 1 else 0

      val views = RemoteViews(context.packageName, R.layout.widget_voice_memos).apply {
        setTextViewText(R.id.widget_title, context.getString(if (unfiltered) R.string.widget_memos_unfiltered_title else R.string.widget_memos_title))
        setRemoteAdapter(R.id.widget_memos_list, serviceIntent)
        setEmptyView(R.id.widget_memos_list, R.id.widget_memos_empty)

        val clickIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        setPendingIntentTemplate(
          R.id.widget_memos_list,
          PendingIntent.getActivity(context, slot, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
        )

        val cameraIntent = Intent(context, QuickCaptureActivity::class.java).apply {
          action = QuickCaptureActivity.ACTION_QUICK_CAMERA
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        setOnClickPendingIntent(R.id.btn_photo_event, PendingIntent.getActivity(context, REQUEST_CAMERA + slot, cameraIntent, IMMUTABLE))

        val openIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        setOnClickPendingIntent(R.id.btn_open_app, PendingIntent.getActivity(context, REQUEST_OPEN + slot, openIntent, IMMUTABLE))

        val providerClass = if (unfiltered) UnfilteredVoiceMemosWidgetProvider::class.java else VoiceMemosWidgetProvider::class.java
        val refreshIntent = Intent(context, providerClass).apply {
          action = if (unfiltered) UnfilteredVoiceMemosWidgetProvider.ACTION_REFRESH_UNFILTERED else ACTION_REFRESH
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
        setOnClickPendingIntent(R.id.btn_refresh, PendingIntent.getBroadcast(context, REQUEST_REFRESH + slot, refreshIntent, IMMUTABLE))

        val actions = if (config.showActions) View.VISIBLE else View.GONE
        setViewVisibility(R.id.btn_photo_event, actions)
        setViewVisibility(R.id.btn_open_app, actions)

        WidgetStyle.background(this, config, colors)
        WidgetStyle.text(this, R.id.widget_title, 14f, config, colors) { it.text }
        WidgetStyle.text(this, R.id.btn_photo_label, 11f, config, colors) { it.text }
        WidgetStyle.text(this, R.id.widget_memos_empty, 12f, config, colors) { it.secondary }
        WidgetStyle.tint(this, R.id.btn_photo_icon, colors) { it.accent }
        WidgetStyle.tint(this, R.id.btn_open_app, colors) { it.text }
        WidgetStyle.tint(this, R.id.btn_refresh, colors) { it.text }
      }
      mgr.updateAppWidget(widgetId, views)
      mgr.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_memos_list)
    }

    private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun triggerFolderScanAndRefresh(context: Context) {
      val app = context.applicationContext as? NotiToCalApp
      if (app != null && app.settings.current.antiVocaleEnabled && app.settings.current.antiVocaleFolderUri.isNotBlank()) {
        app.scope.launch {
          runSafely { VoiceNotes.scan(app, maxAgeMinutes = 24 * 60, limit = 10, quiet = true) }
          updateAll(context)
        }
      }
      updateAll(context)
    }

    fun updateAll(context: Context) {
      val mgr = AppWidgetManager.getInstance(context)
      val filteredIds = mgr.getAppWidgetIds(ComponentName(context, VoiceMemosWidgetProvider::class.java))
      if (filteredIds.isNotEmpty()) {
        mgr.notifyAppWidgetViewDataChanged(filteredIds, R.id.widget_memos_list)
      }
      val unfilteredIds = mgr.getAppWidgetIds(ComponentName(context, UnfilteredVoiceMemosWidgetProvider::class.java))
      if (unfilteredIds.isNotEmpty()) {
        mgr.notifyAppWidgetViewDataChanged(unfilteredIds, R.id.widget_memos_list)
      }
    }
  }
}

/**
 * Large widget 2: Shows ALL Anti-Vocale / Voice Memo messages UNFILTERED
 * (every transcript regardless of whether it contains an appointment/task or plain conversation).
 */
class UnfilteredVoiceMemosWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    appWidgetIds.forEach { VoiceMemosWidgetProvider.render(context, appWidgetManager, it, unfiltered = true) }
    super.onUpdate(context, appWidgetManager, appWidgetIds)
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) = WidgetConfigStore.delete(context, appWidgetIds)

  override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(context, intent)
    if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE || intent.action == ACTION_REFRESH_UNFILTERED) {
      VoiceMemosWidgetProvider.triggerFolderScanAndRefresh(context)
    }
  }

  companion object {
    const val ACTION_REFRESH_UNFILTERED = "de.juliandigitalproducts.notitocal.widget.REFRESH_MEMOS_UNFILTERED"
  }
}
