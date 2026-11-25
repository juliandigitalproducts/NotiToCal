package de.juliandigitalproducts.notitocal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import de.juliandigitalproducts.notitocal.R

class QuickCameraWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    appWidgetIds.forEach { render(context, appWidgetManager, it) }
    super.onUpdate(context, appWidgetManager, appWidgetIds)
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) = WidgetConfigStore.delete(context, appWidgetIds)

  companion object {
    internal fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
      val config = WidgetConfigStore.load(context, widgetId)
      val colors = WidgetStyle.palettes(context, config.theme)
      val intent = Intent(context, QuickCaptureActivity::class.java).apply {
        action = QuickCaptureActivity.ACTION_QUICK_CAMERA
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
      val pending = PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val views = RemoteViews(context.packageName, R.layout.widget_quick_camera).apply {
        setOnClickPendingIntent(R.id.widget_camera_btn, pending)
        WidgetStyle.background(this, config, colors)
        WidgetStyle.text(this, R.id.widget_photo_label, 11f, config, colors) { it.text }
      }
      mgr.updateAppWidget(widgetId, views)
    }
  }
}
