// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.widget

import androidx.core.net.toUri
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.AntiVocaleParser
import de.juliandigitalproducts.notitocal.capture.FolderWatch
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.model.runSafely
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service for Widget 1 (Filtered): shows Anti-Vocale / Voice Memo entries with Appointments & Tasks.
 */
class VoiceMemosWidgetService : RemoteViewsService() {
  override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
    return VoiceMemosFactory(applicationContext, unfiltered = false, widgetId = intent.widgetId())
  }
}

/**
 * Service for Widget 2 (Unfiltered): shows ALL Anti-Vocale / Voice Memo messages unfiltered.
 */
class UnfilteredVoiceMemosWidgetService : RemoteViewsService() {
  override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
    return VoiceMemosFactory(applicationContext, unfiltered = true, widgetId = intent.widgetId())
  }
}

private fun Intent.widgetId() = getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

private class VoiceMemosFactory(
  private val context: Context,
  private val unfiltered: Boolean,
  private val widgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {
  private val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
  private var items: List<LogEntry> = emptyList()
  private var config = WidgetConfig()
  /** Set in [onDataSetChanged], which the launcher always calls before [getViewAt]. */
  private lateinit var colors: WidgetStyle.ThemeColors

  override fun onCreate() {}

  override fun onDataSetChanged() {
    val app = context.applicationContext as? NotiToCalApp
    val s = app?.settings?.current ?: AppSettings()
    config = WidgetConfigStore.load(context, widgetId)
    colors = WidgetStyle.palettes(context, config.theme)
    val all = app?.log?.entries?.value.orEmpty().filter { config.shows(it, s) }

    val loggedMemos = all.filter {
      it.source == SourceType.VOICE_MEMO ||
        it.appLabel.contains("Anti-Vocale", ignoreCase = true) ||
        it.appLabel.contains("Sprach", ignoreCase = true) ||
        it.appLabel.contains("Voice", ignoreCase = true)
    }

    val combined = if (unfiltered) {
      // Also include any raw transcripts directly from the Anti-Vocale export folder that aren't in the log yet
      val folderExtras = readUnloggedFolderMemos(context, s, loggedMemos).filter { config.shows(it, s) }
      (loggedMemos + folderExtras).sortedByDescending { it.created }.take(25)
    } else {
      loggedMemos
        .filter { it.detection.kind in setOf(Kind.EVENT, Kind.TASK) }
        .sortedByDescending { it.created }
        .take(15)
    }

    items = combined
  }

  private fun readUnloggedFolderMemos(
    context: Context,
    s: AppSettings,
    existing: List<LogEntry>,
  ): List<LogEntry> {
    if (!s.antiVocaleEnabled || s.antiVocaleFolderUri.isBlank()) return emptyList()
    val tree = runSafely { s.antiVocaleFolderUri.toUri() }.getOrNull() ?: return emptyList()
    val files = runSafely { FolderWatch.list(context, tree, FolderWatch.Kind.TEXT) }.getOrDefault(emptyList())
    if (files.isEmpty()) return emptyList()
    val seenTexts = existing.map { it.text.trim().lowercase() }.toHashSet()
    val extras = mutableListOf<LogEntry>()
    for (file in files.sortedByDescending { it.modified }.take(20)) {
      val raw = runSafely {
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
      }.getOrNull().orEmpty()
      val clean = AntiVocaleParser.cleanTranscript(raw).trim()
      if (clean.isBlank() || clean.lowercase() in seenTexts) continue
      seenTexts += clean.lowercase()
      extras += LogEntry(
        id = -(file.id.hashCode().toLong().let { if (it == 0L) 1L else kotlin.math.abs(it) }),
        created = file.modified,
        source = SourceType.VOICE_MEMO,
        packageName = "",
        appLabel = "Anti-Vocale",
        sender = file.uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty(),
        text = clean,
        detection = Detection(kind = Kind.NONE, title = "Anti-Vocale"),
        status = Status.IGNORED,
        result = "",
      )
    }
    return extras
  }

  override fun onDestroy() {
    items = emptyList()
  }

  override fun getCount(): Int = items.size

  override fun getViewAt(position: Int): RemoteViews? {
    if (position !in items.indices) return null
    val item = items[position]
    val d = item.detection

    val rv = RemoteViews(context.packageName, R.layout.widget_voice_memo_item)
    WidgetStyle.text(rv, R.id.memo_time, 11f, config, colors) { it.accent }
    WidgetStyle.text(rv, R.id.memo_badge, 11f, config, colors) { it.badge }
    WidgetStyle.text(rv, R.id.memo_text, 12f, config, colors) { it.text }
    val timeLabel = timeFormat.format(Date(item.created)) +
      (item.sender.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    rv.setTextViewText(R.id.memo_time, timeLabel)
    val memoText = item.text.ifBlank { d.title.ifBlank { "(${context.getString(R.string.widget_memo_fallback)})" } }.take(1000)
    rv.setTextViewText(R.id.memo_text, memoText)

    val badge = when (d.kind) {
      Kind.EVENT -> "📅 " + d.title.ifBlank { context.getString(R.string.kind_event) }
      Kind.TASK -> "☑️ " + d.title.ifBlank { context.getString(R.string.kind_task) }
      else -> if (unfiltered) "🎙 " + context.getString(R.string.widget_memo_unfiltered_badge) else null
    }

    if (badge != null) {
      rv.setTextViewText(R.id.memo_badge, badge)
      rv.setViewVisibility(R.id.memo_badge, View.VISIBLE)
    } else {
      rv.setViewVisibility(R.id.memo_badge, View.GONE)
    }

    val fillInIntent = Intent().apply {
      if (item.id > 0) {
        putExtra("entry_id", item.id)
        putExtra("open_route", "entry/${item.id}")
      }
    }
    rv.setOnClickFillInIntent(R.id.memo_item_container, fillInIntent)

    return rv
  }

  override fun getLoadingView(): RemoteViews? = null
  override fun getViewTypeCount(): Int = 1
  override fun getItemId(position: Int): Long = items.getOrNull(position)?.id ?: position.toLong()
  override fun hasStableIds(): Boolean = true
}
