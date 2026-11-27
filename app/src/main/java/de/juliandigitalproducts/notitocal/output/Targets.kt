// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.output

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.provider.CalendarContract
import android.util.Base64
import androidx.core.content.ContextCompat
import de.juliandigitalproducts.notitocal.analysis.SelfHosted
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.data.SecretStore
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.net.Http
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import de.juliandigitalproducts.notitocal.model.runSafely

data class DeliveryResult(
  val target: TargetType,
  val ok: Boolean,
  val message: String,
  val intent: Intent? = null,
  val merged: Boolean = false,
  val skippedReadonly: Boolean = false,
  /** Id of the created item at the service (Todoist task, Notion page), stored for later updates. */
  val remoteId: String? = null,
  /** The failure may go away by itself (network, timeout, server busy) – worth another try. */
  val transient: Boolean = false,
)

/** Source information sent along with a detection to webhooks and the self-hosted server. */
data class SourceInfo(val app: String, val packageName: String, val sender: String, val text: String, val source: String)

/**
 * Resolved start/end of an event or the due time of a task. For all-day items [end] is exclusive (midnight after the
 * last day), as calendars and iCalendar expect it.
 */
internal data class Span(val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean)

internal fun Detection.span(defaultMinutes: Int): Span? {
  val s = startDateTime() ?: dueDateTime() ?: return null
  val timed = (start ?: due)?.contains('T') == true && !allDay
  return if (!timed) {
    // "end" of a detection is the last day (inclusive, "3rd to 5th" -> 5th); no end = one day (C7).
    val lastDay = endDateTime()?.toLocalDate()?.takeIf { !it.isBefore(s.toLocalDate()) } ?: s.toLocalDate()
    Span(s.toLocalDate().atStartOfDay(), lastDay.plusDays(1).atStartOfDay(), true)
  } else {
    Span(s, endDateTime()?.takeIf { it.isAfter(s) } ?: s.plusMinutes(defaultMinutes.toLong()), false)
  }
}

private fun LocalDateTime.millis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun LocalDateTime.utcMidnightMillis(): Long = toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

object Targets {

  // ---------- Device calendar (any account synced on the phone: Google, Samsung, Exchange, DAVx5 …) ----------

  data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int, val primary: Boolean)

  fun calendars(context: Context): List<CalendarInfo> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val list = mutableListOf<CalendarInfo>()
    context.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      arrayOf(
        CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.IS_PRIMARY,
      ),
      "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
      arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
    )?.use { c ->
      while (c.moveToNext()) list += CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4) == 1)
    }
    return list.sortedByDescending { it.primary }
  }

  private fun deviceCalendar(context: Context, d: Detection, s: AppSettings): DeliveryResult {
    val t = TargetType.DEVICE_CALENDAR
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED)
      return DeliveryResult(t, false, "calendar permission missing")
    val calId = s.calendarId.takeIf { id -> id >= 0 && calendars(context).any { it.id == id } } ?: calendars(context).firstOrNull()?.id
      ?: return DeliveryResult(t, false, "no writable calendar")
    val span = d.span(s.defaultEventMinutes) ?: return DeliveryResult(t, false, "no date")
    val title = if (d.kind == Kind.TASK) "☐ ${d.title.removePrefix("☐").removePrefix("☑").trim()}" else d.title
    if (s.mergeDuplicateEvents && d.kind == Kind.EVENT) {
      val startMillis = if (span.allDay) span.start.utcMidnightMillis() else span.start.millis()
      val outcome = CalendarDuplicates.evaluate(
        context,
        DuplicateMatcher.Proposed(title, d.description, d.location.orEmpty(), startMillis),
        s.duplicateThreshold,
      )
      when (outcome.action) {
        DuplicateMatcher.Action.SKIP_READONLY ->
          return DeliveryResult(t, false, context.getString(de.juliandigitalproducts.notitocal.R.string.notif_duplicate_readonly), skippedReadonly = true)
        DuplicateMatcher.Action.MERGE -> {
          val eventId = outcome.candidate?.eventId ?: return DeliveryResult(t, false, "insert failed")
          val values = ContentValues()
          outcome.locationUpdate?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
          outcome.descriptionUpdate?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
          if (values.size() > 0) {
            val updated = runSafely {
              context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values, null, null)
            }.getOrDefault(0)
            if (updated <= 0) return DeliveryResult(t, false, "calendar update failed")
          }
          val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
          return DeliveryResult(t, true, context.getString(de.juliandigitalproducts.notitocal.R.string.notif_merged), Intent(Intent.ACTION_VIEW, eventUri), merged = true)
        }
        DuplicateMatcher.Action.CREATE -> Unit
      }
    }
    val values = ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, calId)
      put(CalendarContract.Events.TITLE, title)
      put(CalendarContract.Events.DESCRIPTION, d.description)
      d.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
      if (span.allDay) {
        put(CalendarContract.Events.ALL_DAY, 1)
        put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        // span.end is already exclusive; adding another day here made one-day events two days long (C7).
        put(CalendarContract.Events.DTSTART, span.start.utcMidnightMillis())
        put(CalendarContract.Events.DTEND, span.end.utcMidnightMillis())
      } else {
        put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        put(CalendarContract.Events.DTSTART, span.start.millis())
        put(CalendarContract.Events.DTEND, span.end.millis())
      }
      put(CalendarContract.Events.HAS_ALARM, if (s.reminderMinutes >= 0) 1 else 0)
    }
    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return DeliveryResult(t, false, "insert failed")
    val eventId = uri.lastPathSegment?.toLongOrNull()
    if (eventId != null && s.reminderMinutes >= 0) {
      context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
        put(CalendarContract.Reminders.EVENT_ID, eventId)
        put(CalendarContract.Reminders.MINUTES, s.reminderMinutes)
        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
      })
    }
    return DeliveryResult(t, true, "event #$eventId", Intent(Intent.ACTION_VIEW, uri))
  }

  /**
   * Updates the calendar event a log entry created. [eventId] (stored when the event was inserted) is used when
   * known; older entries fall back to a narrow search: same title, the configured calendar, not deleted, not
   * recurring and starting within two days of [oldStartMillis]. Never touches an arbitrary event with the same title.
   */
  fun updateDeviceCalendar(
    context: Context,
    entry: Detection,
    s: AppSettings,
    oldTitle: String = entry.title,
    eventId: Long? = null,
    oldStartMillis: Long? = null,
    sender: String = "",
  ): DeliveryResult {
    // "Sender in title" off: also an entry made while it was on loses the name in the calendar (found by its old title).
    val d = Privacy.titleForTargets(entry, sender, s)
    val t = TargetType.DEVICE_CALENDAR
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED)
      return DeliveryResult(t, false, "calendar permission missing")
    val span = d.span(s.defaultEventMinutes) ?: return DeliveryResult(t, false, "no date")

    val foundEventId: Long? = eventId?.takeIf { id -> eventExists(context, id) } ?: findOwnEvent(context, s, oldTitle, oldStartMillis)
    if (foundEventId == null) {
      return deviceCalendar(context, d, s)
    }

    val eventId = foundEventId
    val values = ContentValues().apply {
      put(CalendarContract.Events.TITLE, d.title)
      put(CalendarContract.Events.DESCRIPTION, d.description)
      d.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
      if (span.allDay) {
        put(CalendarContract.Events.ALL_DAY, 1)
        put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        put(CalendarContract.Events.DTSTART, span.start.utcMidnightMillis())
        put(CalendarContract.Events.DTEND, span.end.utcMidnightMillis())
      } else {
        put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        put(CalendarContract.Events.DTSTART, span.start.millis())
        put(CalendarContract.Events.DTEND, span.end.millis())
      }
    }

    val rows = runSafely {
      context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values, null, null)
    }.getOrDefault(0)

    val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    return if (rows > 0) {
      DeliveryResult(t, true, "calendar updated", Intent(Intent.ACTION_VIEW, eventUri), merged = true)
    } else {
      deviceCalendar(context, d, s)
    }
  }

  /**
   * Moves an event this app created into the calendar of [s] (after a routing rule changed): it is created there
   * and the old one deleted. A deleted or unknown event is not created again.
   */
  fun moveDeviceCalendarEvent(context: Context, d: Detection, s: AppSettings, eventId: Long?): DeliveryResult {
    val t = TargetType.DEVICE_CALENDAR
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED)
      return DeliveryResult(t, false, "calendar permission missing")
    val oldId = eventId?.takeIf { eventExists(context, it) } ?: return DeliveryResult(t, false, "event not found")
    val writable = calendars(context).map { it.id }
    val targetCal = s.calendarId.takeIf { it in writable } ?: writable.firstOrNull() ?: return DeliveryResult(t, false, "no writable calendar")
    val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, oldId)
    if (calendarOf(context, oldId) == targetCal) return DeliveryResult(t, true, "already in this calendar", Intent(Intent.ACTION_VIEW, eventUri))
    // No merging into a look-alike there: the event itself is moving.
    val created = deviceCalendar(context, d, s.copy(calendarId = targetCal, mergeDuplicateEvents = false))
    if (!created.ok) return created
    runSafely { context.contentResolver.delete(eventUri, null, null) }
    return created.copy(message = "moved to another calendar")
  }

  private fun calendarOf(context: Context, id: Long): Long? = runSafely {
    context.contentResolver.query(
      ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
      arrayOf(CalendarContract.Events.CALENDAR_ID), null, null, null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
  }.getOrNull()

  private fun eventExists(context: Context, id: Long): Boolean = runSafely {
    context.contentResolver.query(
      ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
      arrayOf(CalendarContract.Events._ID), "${CalendarContract.Events.DELETED} = 0", null, null,
    )?.use { it.moveToFirst() } ?: false
  }.getOrDefault(false)

  private fun findOwnEvent(context: Context, s: AppSettings, title: String, startMillis: Long?): Long? {
    if (title.isBlank() || startMillis == null) return null
    val window = 2L * 24 * 60 * 60 * 1000
    val calIds = calendars(context).map { it.id }
    val calId = s.calendarId.takeIf { it in calIds } ?: calIds.firstOrNull() ?: return null
    return runSafely {
      context.contentResolver.query(
        CalendarContract.Events.CONTENT_URI,
        arrayOf(CalendarContract.Events._ID),
        "${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0 " +
          "AND ${CalendarContract.Events.RRULE} IS NULL AND ${CalendarContract.Events.DTSTART} BETWEEN ? AND ?",
        arrayOf(title, calId.toString(), (startMillis - window).toString(), (startMillis + window).toString()),
        "${CalendarContract.Events.DTSTART} ASC",
      )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()
  }

  /** Event id from a successful device-calendar delivery (its view intent points at the event). */
  fun calendarEventId(r: DeliveryResult): Long? =
    if (r.ok && r.target == TargetType.DEVICE_CALENDAR) r.intent?.data?.lastPathSegment?.toLongOrNull() else null

  /** Opens the calendar app's "new event" screen, pre-filled (user confirms there). */
  fun calendarIntent(d: Detection, s: AppSettings): Intent? {
    val span = d.span(s.defaultEventMinutes) ?: return null
    return Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
      putExtra(CalendarContract.Events.TITLE, d.title)
      putExtra(CalendarContract.Events.DESCRIPTION, d.description)
      d.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
      putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, span.allDay)
      putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, span.start.millis())
      putExtra(CalendarContract.EXTRA_EVENT_END_TIME, span.end.millis())
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  /** Formats task details for Google Tasks and opens it via ACTION_SEND or INSERT. */
  fun googleTasksIntent(context: Context, d: Detection): Intent {
    val title = d.title.ifBlank { d.description.take(50) }
    val details = buildString {
      if (d.description.isNotBlank() && d.description != title) append(d.description).append("\n\n")
      // In the user's language, not hard-coded German (C21).
      if (d.due != null) append(context.getString(de.juliandigitalproducts.notitocal.R.string.due_at, d.due)).append("\n")
      if (d.location != null) append(context.getString(de.juliandigitalproducts.notitocal.R.string.field_location)).append(": ").append(d.location).append("\n")
    }.trim()
    val fullText = if (details.isNotBlank()) "$title\n\n$details" else title
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      setPackage("com.google.android.apps.tasks")
      putExtra(Intent.EXTRA_TITLE, title)
      putExtra(Intent.EXTRA_SUBJECT, title)
      putExtra(Intent.EXTRA_TEXT, fullText)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return if (context.packageManager.resolveActivity(intent, 0) != null) intent
    else Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, fullText)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  /** Formats reminder for Samsung Reminder app and opens it. */
  fun samsungReminderIntent(context: Context, d: Detection, s: AppSettings): Intent {
    val span = d.span(s.defaultEventMinutes)
    val title = d.title.ifBlank { d.description.take(50) }
    val intent = Intent("com.samsung.android.app.reminder.ACTION_INSERT").apply {
      setPackage("com.samsung.android.app.reminder")
      putExtra("title", title)
      if (d.description.isNotBlank()) putExtra("note", d.description)
      if (d.location != null) putExtra("location", d.location)
      if (span != null) {
        putExtra("start_time", span.start.millis())
        putExtra("reminder_time", span.start.millis() - (s.reminderMinutes.coerceAtLeast(0) * 60_000L))
        putExtra("all_day", span.allDay)
      }
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return if (context.packageManager.resolveActivity(intent, 0) != null) intent
    else Intent(Intent.ACTION_INSERT).apply {
      setPackage("com.samsung.android.app.reminder")
      putExtra(Intent.EXTRA_TITLE, title)
      putExtra(Intent.EXTRA_TEXT, d.description)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  // ---------- Task apps via the OpenTasks provider contract (Tasks.org, OpenTasks) ----------

  data class TaskListInfo(val id: Long, val name: String, val account: String)

  val TASK_AUTHORITIES = listOf("org.tasks.opentasks" to "Tasks.org", "org.dmfs.tasks" to "OpenTasks")

  fun taskPermission(authority: String) =
    if (authority == "org.dmfs.tasks") "org.dmfs.permission.WRITE_TASKS" else "org.tasks.permission.WRITE_TASKS"

  fun taskReadPermission(authority: String) =
    if (authority == "org.dmfs.tasks") "org.dmfs.permission.READ_TASKS" else "org.tasks.permission.READ_TASKS"

  fun taskLists(context: Context, authority: String): List<TaskListInfo> = runSafely {
    val list = mutableListOf<TaskListInfo>()
    context.contentResolver.query("content://$authority/tasklists".toUri(), arrayOf("_id", "list_name", "account_name"), null, null, null)?.use { c ->
      while (c.moveToNext()) list += TaskListInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
    }
    list
  }.getOrDefault(emptyList())

  private fun taskProvider(context: Context, d: Detection, s: AppSettings): DeliveryResult {
    val t = TargetType.TASK_PROVIDER
    if (ContextCompat.checkSelfPermission(context, taskPermission(s.tasksAuthority)) != PackageManager.PERMISSION_GRANTED)
      return DeliveryResult(t, false, "task app permission missing")
    val listId = s.tasksListId.takeIf { it >= 0 } ?: taskLists(context, s.tasksAuthority).firstOrNull()?.id
      ?: return DeliveryResult(t, false, "no task list (is the task app installed and synced?)")
    val values = ContentValues().apply {
      put("list_id", listId)
      put("title", d.title)
      put("description", d.description)
      d.location?.let { put("location", it) }
      put("priority", when (d.priority) { 3 -> 1; 2 -> 5; 1 -> 9; else -> 0 })
      val span = d.span(s.defaultEventMinutes)
      if (span != null) {
        if (span.allDay) {
          put("is_allday", 1); put("tz", "UTC"); put("due", span.start.utcMidnightMillis())
        } else {
          put("is_allday", 0); put("tz", ZoneId.systemDefault().id); put("due", span.start.millis())
        }
      }
    }
    val uri = context.contentResolver.insert("content://${s.tasksAuthority}/tasks".toUri(), values)
      ?: return DeliveryResult(t, false, "insert failed")
    return DeliveryResult(t, true, "task ${uri.lastPathSegment}")
  }

  // ---------- Todoist ----------

  private fun todoistBody(d: Detection, s: AppSettings, create: Boolean): JsonObject {
    val span = d.span(s.defaultEventMinutes)
    return buildJsonObject {
      put("content", d.title)
      if (d.description.isNotBlank()) put("description", d.description.take(15000))
      put("priority", (d.priority + 1).coerceIn(1, 4))
      if (create && s.todoistProjectId.isNotBlank()) put("project_id", s.todoistProjectId)
      if (span != null) {
        if (span.allDay) put("due_date", span.start.toLocalDate().toString())
        else put("due_datetime", span.start.atZone(ZoneId.systemDefault()).toInstant().toString())
      }
      if (create && d.kind == Kind.EVENT) putJsonArray("labels") { add(kotlinx.serialization.json.JsonPrimitive("event")) }
    }
  }

  /** "id" of a JSON object answer, or null. */
  internal fun responseId(raw: String): String? = runSafely {
    (AppJson.parseToJsonElement(raw).jsonObject["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
  }.getOrNull()?.takeIf { it.isNotBlank() }

  private suspend fun todoist(d: Detection, s: AppSettings, token: String): DeliveryResult {
    val t = TargetType.TODOIST
    if (token.isBlank()) return DeliveryResult(t, false, "no Todoist token")
    val res = Http.postJson("https://api.todoist.com/api/v1/tasks", todoistBody(d, s, create = true).toString(), mapOf("Authorization" to "Bearer $token"))
    return DeliveryResult(t, true, "Todoist", remoteId = responseId(res))
  }

  // ---------- CalDAV (Nextcloud, iCloud, Radicale, Baïkal, mailbox.org …) ----------

  // A lone CR is a line break as well: message text must never start a new property line in the iCalendar data.
  private fun icsEscape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

  /** RFC 5545 line folding: at most 75 octets per line, never splitting a UTF-8 character or a surrogate pair. */
  internal fun fold(line: String): String {
    if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
    val out = StringBuilder()
    var lineBytes = 0
    var i = 0
    while (i < line.length) {
      val cp = line.codePointAt(i)
      val chars = Character.charCount(cp)
      val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
      // Continuation lines start with one space, which counts towards their 75 octets.
      if (lineBytes + bytes > 75) {
        out.append("\r\n ")
        lineBytes = 1
      }
      out.appendCodePoint(cp)
      lineBytes += bytes
      i += chars
    }
    return out.toString()
  }

  private val ICS_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
  private val ICS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
  private fun LocalDateTime.icsUtc() = atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).format(ICS_UTC)

  fun buildIcs(d: Detection, s: AppSettings, uid: String = d.syncUid ?: UUID.randomUUID().toString()): String {
    val span = d.span(s.defaultEventMinutes)
    val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//NotiToCal//Android//EN", "CALSCALE:GREGORIAN")
    val now = LocalDateTime.now().icsUtc()
    if (d.kind == Kind.TASK) {
      lines += listOf("BEGIN:VTODO", "UID:$uid", "DTSTAMP:$now", "SUMMARY:${icsEscape(d.title)}", "STATUS:NEEDS-ACTION")
      if (span != null) lines += if (span.allDay) "DUE;VALUE=DATE:${span.start.format(ICS_DATE)}" else "DUE:${span.start.icsUtc()}"
      if (d.priority > 0) lines += "PRIORITY:${when (d.priority) { 3 -> 1; 2 -> 5; else -> 9 }}"
      if (d.description.isNotBlank()) lines += "DESCRIPTION:${icsEscape(d.description)}"
      lines += "END:VTODO"
    } else {
      lines += listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:$now", "SUMMARY:${icsEscape(d.title)}")
      if (span != null) {
        if (span.allDay) {
          lines += "DTSTART;VALUE=DATE:${span.start.format(ICS_DATE)}"
          lines += "DTEND;VALUE=DATE:${span.end.format(ICS_DATE)}"
        } else {
          lines += "DTSTART:${span.start.icsUtc()}"
          lines += "DTEND:${span.end.icsUtc()}"
        }
      }
      d.location?.let { lines += "LOCATION:${icsEscape(it)}" }
      if (d.description.isNotBlank()) lines += "DESCRIPTION:${icsEscape(d.description)}"
      if (s.reminderMinutes >= 0) lines += listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:${icsEscape(d.title)}", "TRIGGER:-PT${s.reminderMinutes}M", "END:VALARM")
      lines += "END:VEVENT"
    }
    lines += "END:VCALENDAR"
    return lines.joinToString("\r\n", postfix = "\r\n") { fold(it) }
  }

  /** [update]: overwrite the item stored under the entry's UID instead of creating a new one. */
  private suspend fun caldav(d: Detection, s: AppSettings, password: String, update: Boolean = false): DeliveryResult {
    val t = TargetType.CALDAV
    val collection = (if (d.kind == Kind.TASK) s.caldavTaskUrl.ifBlank { s.caldavEventUrl } else s.caldavEventUrl).trim()
    if (collection.isBlank()) return DeliveryResult(t, false, "no CalDAV URL")
    if (update && d.syncUid == null) return DeliveryResult(t, false, "no UID")
    val uid = d.syncUid ?: UUID.randomUUID().toString()
    val url = collection.trimEnd('/') + "/$uid.ics"
    val auth = "Basic " + Base64.encodeToString("${s.caldavUser}:$password".toByteArray(), Base64.NO_WRAP)
    val headers = if (update) mapOf("Authorization" to auth) else mapOf("Authorization" to auth, "If-None-Match" to "*")
    Http.request("PUT", url, headers, buildIcs(d, s, uid).toByteArray(), "text/calendar; charset=utf-8")
    return DeliveryResult(t, true, if (update) "CalDAV updated" else "CalDAV")
  }

  // ---------- Notion database ----------

  private suspend fun notion(d: Detection, s: AppSettings, token: String): DeliveryResult {
    val t = TargetType.NOTION
    if (token.isBlank() || s.notionDatabaseId.isBlank()) return DeliveryResult(t, false, "Notion not configured")
    val body = buildJsonObject {
      putJsonObject("parent") { put("database_id", s.notionDatabaseId.trim()) }
      put("properties", notionProperties(d, s))
      if (d.description.isNotBlank()) putJsonArray("children") {
        add(buildJsonObject {
          put("object", "block"); put("type", "paragraph")
          putJsonObject("paragraph") {
            putJsonArray("rich_text") { add(buildJsonObject { put("type", "text"); putJsonObject("text") { put("content", d.description.take(1900)) } }) }
          }
        })
      }
    }
    val res = Http.postJson("https://api.notion.com/v1/pages", body.toString(), mapOf("Authorization" to "Bearer $token", "Notion-Version" to "2022-06-28"))
    return DeliveryResult(t, true, "Notion", remoteId = responseId(res))
  }

  private fun notionProperties(d: Detection, s: AppSettings): JsonObject {
    val span = d.span(s.defaultEventMinutes)
    return buildJsonObject {
        putJsonObject(s.notionTitleProperty.ifBlank { "Name" }) {
          putJsonArray("title") { add(buildJsonObject { putJsonObject("text") { put("content", d.title) } }) }
        }
        if (span != null && s.notionDateProperty.isNotBlank()) putJsonObject(s.notionDateProperty) {
          putJsonObject("date") {
            if (span.allDay) put("start", span.start.toLocalDate().toString())
            else {
              put("start", span.start.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString())
              if (d.kind == Kind.EVENT) put("end", span.end.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString())
            }
          }
        }
        if (s.notionTypeProperty.isNotBlank()) putJsonObject(s.notionTypeProperty) {
          putJsonObject("select") { put("name", if (d.kind == Kind.EVENT) "Event" else "Task") }
        }
    }
  }

  // ---------- Webhook / self-hosted server / automation apps ----------

  fun payload(
    d: Detection, src: SourceInfo, s: AppSettings, withSource: Boolean = s.sendSourceToTargets, action: String = ACTION_CREATE,
  ): JsonObject = buildJsonObject {
    // Switch off: no message and no sender, also not inside title, description, place or ICS (see Privacy).
    val shared = Privacy.shareable(d, src, withSource)
    put("action", action)
    d.syncUid?.let { put("uid", it) }
    put("kind", d.kind.name)
    put("title", shared.title)
    put("description", shared.description)
    d.start?.let { put("start", it) }
    d.end?.let { put("end", it) }
    put("all_day", d.allDay)
    d.due?.let { put("due", it) }
    shared.location?.let { put("location", it) }
    put("priority", d.priority)
    put("confidence", d.confidence)
    put("engine", d.engine)
    put("timezone", ZoneId.systemDefault().id)
    putJsonObject("source") {
      put("type", src.source)
      put("app", src.app)
      put("package", src.packageName)
      if (withSource) put("sender", src.sender)
      if (withSource) put("text", src.text)
    }
    put("ics", buildIcs(shared, s))
  }

  const val ACTION_DETECTED = "de.juliandigitalproducts.notitocal.DETECTED"
  /** "action" field of webhook / server / broadcast payloads. */
  const val ACTION_CREATE = "create"
  const val ACTION_UPDATE = "update"

  /** Automation apps that get the broadcast when no app is chosen in the settings. */
  val AUTOMATION_APPS = listOf("net.dinglisch.android.taskerm", "com.arlosoft.macrodroid", "com.llamalab.automate")

  /**
   * Sent only to the chosen automation app, or to the known ones (C8). Before, the broadcast was implicit and every
   * installed app could read appointment contents.
   */
  private fun broadcast(context: Context, entry: Detection, src: SourceInfo, s: AppSettings, action: String = ACTION_CREATE): DeliveryResult {
    // Own switch (t55u): off = neither the sender nor the message, also not inside title, description or place.
    val withSource = s.broadcastIncludesSource
    val d = Privacy.shareable(entry, src, withSource)
    val intent = Intent(ACTION_DETECTED).apply {
      putExtra("action", action)
      d.syncUid?.let { putExtra("uid", it) }
      putExtra("kind", d.kind.name)
      putExtra("title", d.title)
      putExtra("description", d.description)
      putExtra("start", d.start)
      putExtra("end", d.end)
      putExtra("all_day", d.allDay)
      putExtra("due", d.due)
      putExtra("location", d.location)
      putExtra("priority", d.priority)
      putExtra("confidence", d.confidence)
      putExtra("source_app", src.app)
      putExtra("source_package", src.packageName)
      if (withSource) {
        putExtra("sender", src.sender)
        putExtra("text", src.text)
      }
      putExtra("start_millis", d.startDateTime()?.millis() ?: d.dueDateTime()?.millis() ?: -1L)
    }
    val packages = s.broadcastPackage.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: AUTOMATION_APPS
    packages.forEach { context.sendBroadcast(Intent(intent).setPackage(it)) }
    return DeliveryResult(TargetType.AUTOMATION_BROADCAST, true, "broadcast")
  }

  fun shareText(context: Context, d: Detection, s: AppSettings): String = buildString {
    append(if (d.kind == Kind.EVENT) "📅 " else "☑️ ")
    append(d.title)
    d.span(s.defaultEventMinutes)?.let { sp ->
      append("\n🕒 ")
      val fmtDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
      val fmtTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
      append(sp.start.format(fmtDate))
      if (!sp.allDay) append(" ").append(sp.start.format(fmtTime)).append(if (d.kind == Kind.EVENT) "–" + sp.end.format(fmtTime) else "")
    }
    d.location?.let { append("\n📍 ").append(it) }
    if (d.description.isNotBlank() && d.description != d.title) append("\n\n").append(d.description)
  }

  fun shareIntent(context: Context, d: Detection, s: AppSettings): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, d.title)
      putExtra(Intent.EXTRA_TITLE, d.title)
      putExtra(Intent.EXTRA_TEXT, shareText(context, d, s))
      if (s.sharePackage.isNotBlank()) setPackage(s.sharePackage)
    }
    val out = if (s.sharePackage.isNotBlank() && send.resolveActivity(context.packageManager) != null) send else Intent.createChooser(send, d.title)
    return out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  // ---------- AI routine (Claude, Grok, ChatGPT, Gemini, a local model …) ----------

  private fun jsonString(v: String) = kotlinx.serialization.json.JsonPrimitive(v).toString().removeSurrounding("\"")

  /** Fills {{title}}, {{kind}}, {{when}}, {{start}}, {{end}}, {{due}}, {{location}}, {{description}}, {{sender}}, {{app}}, {{text}}, {{json}}. JSON-escaped. */
  fun fillTemplate(template: String, d: Detection, src: SourceInfo, s: AppSettings, action: String = ACTION_CREATE): String {
    // "Send message text to webhook and server" off: {{sender}} and {{text}} stay empty, like in {{json}}, and
    // {{title}}, {{location}} and {{description}} carry neither the message nor the sender.
    val withSource = s.sendSourceToTargets
    val shared = Privacy.shareable(d, src, withSource)
    val values = mapOf(
      "action" to action, "uid" to d.syncUid.orEmpty(),
      "title" to shared.title, "kind" to d.kind.name, "start" to d.start.orEmpty(), "end" to d.end.orEmpty(), "due" to d.due.orEmpty(),
      "location" to shared.location.orEmpty(), "description" to shared.description, "sender" to (if (withSource) src.sender else ""), "app" to src.app,
      "text" to (if (withSource) src.text else ""), "when" to (d.start ?: d.due).orEmpty(),
    )
    var out = template.replace("{{json}}", payload(d, src, s, action = action).toString())
    values.forEach { (k, v) -> out = out.replace("{{$k}}", jsonString(v)) }
    return out
  }

  private suspend fun aiAgent(context: Context, d: Detection, s: AppSettings, secrets: SecretStore, src: SourceInfo): DeliveryResult {
    val t = TargetType.AI_AGENT
    if (s.agentInstructions.isBlank()) return DeliveryResult(t, false, "no routine defined")
    val cfg = s.forAgent()
    val key = secrets.get(SecretKeys.llm(cfg.provider))
    if (cfg.provider.needsKey && key.isBlank()) return DeliveryResult(t, false, "no API key for ${cfg.provider.label}")
    val system = """
You are the user's personal assistant. NotiToCal found the ${if (d.kind == Kind.EVENT) "appointment" else "task"} below in a message on the user's phone.
Carry out the user's routine for it and reply with the result only (plain text, no markdown tables), in ${de.juliandigitalproducts.notitocal.analysis.Prompt.userLanguage(context)} unless the routine asks for another language.
Routine:
${s.agentInstructions.trim()}
""".trim()
    val user = buildString {
      // "Send sender names to the AI" off: the routine's model gets the entry without the sender (also not in the title).
      val forModel = Privacy.forAi(d, src.sender, s)
      append("Item (JSON):\n").append(payload(forModel, src.copy(sender = if (s.shareSenderWithAi) src.sender else ""), s, withSource = true).let { o -> kotlinx.serialization.json.JsonObject(o - "ics") }.toString())
      append("\nCurrent local time: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
    }
    val shown = de.juliandigitalproducts.notitocal.analysis.Pseudonymizer.around(s, system, user) { sys, u ->
      de.juliandigitalproducts.notitocal.analysis.CloudLlmClient(cfg, key).complete(sys, u, json = false).trim()
    }.orEmpty()
    return DeliveryResult(t, true, shown.take(4000))
  }

  /** Targets that can change an item they received earlier (the device calendar is updated by [updateDeviceCalendar]). */
  val UPDATABLE = setOf(
    TargetType.CALDAV, TargetType.TODOIST, TargetType.NOTION, TargetType.SELF_HOSTED, TargetType.WEBHOOK, TargetType.AUTOMATION_BROADCAST,
  )

  /**
   * Sends a changed entry to a target that already got it. Null when the target cannot update (interactive targets,
   * the AI routine, task apps) – the user then sees in the entry that only the other targets have the new state.
   */
  suspend fun update(context: Context, target: TargetType, entry: Detection, s: AppSettings, secrets: SecretStore, src: SourceInfo): DeliveryResult? =
    try {
      val d = Privacy.titleForTargets(entry, src.sender, s)
      when (target) {
        TargetType.CALDAV -> caldav(d, s, secrets.get(SecretKeys.CALDAV), update = true)
        TargetType.TODOIST -> {
          val id = d.remoteIds[target.name]
          val token = secrets.get(SecretKeys.TODOIST)
          if (id == null || token.isBlank()) DeliveryResult(target, false, "no Todoist task id") else {
            Http.postJson("https://api.todoist.com/api/v1/tasks/$id", todoistBody(d, s, create = false).toString(), mapOf("Authorization" to "Bearer $token"))
            DeliveryResult(target, true, "Todoist updated")
          }
        }
        TargetType.NOTION -> {
          val id = d.remoteIds[target.name]
          val token = secrets.get(SecretKeys.NOTION)
          if (id == null || token.isBlank()) DeliveryResult(target, false, "no Notion page id") else {
            val body = buildJsonObject { put("properties", notionProperties(d, s)) }
            Http.request(
              "PATCH", "https://api.notion.com/v1/pages/$id", mapOf("Authorization" to "Bearer $token", "Notion-Version" to "2022-06-28"),
              body.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8",
            )
            DeliveryResult(target, true, "Notion updated")
          }
        }
        TargetType.SELF_HOSTED -> if (s.selfHostedUrl.isBlank() || d.syncUid == null) null else {
          Http.postJson(
            SelfHosted.url(s.selfHostedUrl, "item"), payload(d, src, s, action = ACTION_UPDATE).toString(),
            SelfHosted.auth(secrets.get(SecretKeys.SELF_HOSTED)),
          )
          DeliveryResult(target, true, "server updated")
        }
        TargetType.WEBHOOK -> if (s.webhookUrl.isBlank()) null else {
          val auth = secrets.get(SecretKeys.WEBHOOK_AUTH)
          val body = if (s.webhookBodyTemplate.isBlank()) payload(d, src, s, action = ACTION_UPDATE).toString() else fillTemplate(s.webhookBodyTemplate, d, src, s, action = ACTION_UPDATE)
          Http.postJson(s.webhookUrl.trim(), body, if (auth.isBlank()) emptyMap() else mapOf("Authorization" to auth))
          DeliveryResult(target, true, "webhook updated")
        }
        TargetType.AUTOMATION_BROADCAST -> broadcast(context, d, src, s, action = ACTION_UPDATE)
        else -> null
      }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      throw e
    } catch (e: Exception) {
      DeliveryResult(target, false, e.message?.take(200) ?: e.javaClass.simpleName)
    }

  /** Delivers to one target. Interactive targets only return an intent for the caller to start. */
  suspend fun deliver(
    context: Context,
    target: TargetType,
    d: Detection,
    s: AppSettings,
    secrets: SecretStore,
    src: SourceInfo,
    unfiltered: Boolean = false,
  ): DeliveryResult =
    try {
      val prepared = if (unfiltered) {
        val fallbackTitle = d.title.ifBlank { src.sender.takeIf { s.senderInTitle }.orEmpty().ifBlank { src.app }.ifBlank { src.text.lineSequence().firstOrNull()?.take(60).orEmpty() } }.ifBlank { "NotiToCal" }
        val fallbackDesc = if (src.text.isNotBlank() && !d.description.contains(src.text)) {
          if (d.description.isBlank()) src.text else "${d.description}\n\n${src.text}"
        } else d.description
        val fallbackStart = d.start ?: d.due ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        d.copy(
          kind = if (d.kind == Kind.NONE || d.kind == Kind.NOTE) Kind.TASK else d.kind,
          title = fallbackTitle,
          description = fallbackDesc,
          start = d.start ?: fallbackStart,
          due = d.due ?: fallbackStart.substringBefore('T'),
        )
      } else d
      // "Sender in title" off: no target gets the name in the title (also entries confirmed or sent again later).
      val effective = Privacy.titleForTargets(prepared, src.sender, s)
      val withSrc = s.sendSourceToTargets || unfiltered
      when (target) {
        TargetType.DEVICE_CALENDAR -> deviceCalendar(context, effective, s)
        TargetType.CALENDAR_APP -> calendarIntent(effective, s)?.let { DeliveryResult(target, true, "calendar app", it) }
          ?: DeliveryResult(target, false, "no date")
        TargetType.TASK_PROVIDER -> taskProvider(context, effective, s)
        TargetType.GOOGLE_TASKS -> DeliveryResult(target, true, "Google Tasks", googleTasksIntent(context, effective))
        TargetType.SAMSUNG_REMINDER -> DeliveryResult(target, true, "Samsung Reminder", samsungReminderIntent(context, effective, s))
        TargetType.TODOIST -> todoist(effective, s, secrets.get(SecretKeys.TODOIST))
        TargetType.CALDAV -> caldav(effective, s, secrets.get(SecretKeys.CALDAV))
        TargetType.NOTION -> notion(effective, s, secrets.get(SecretKeys.NOTION))
        TargetType.SELF_HOSTED -> {
          if (s.selfHostedUrl.isBlank()) DeliveryResult(target, false, "no server URL") else {
            Http.postJson(SelfHosted.url(s.selfHostedUrl, "item"), payload(effective, src, s, withSource = withSrc).toString(), SelfHosted.auth(secrets.get(SecretKeys.SELF_HOSTED)))
            DeliveryResult(target, true, "server")
          }
        }
        TargetType.WEBHOOK -> {
          if (s.webhookUrl.isBlank()) DeliveryResult(target, false, "no webhook URL") else {
            val auth = secrets.get(SecretKeys.WEBHOOK_AUTH)
            val body = if (s.webhookBodyTemplate.isBlank()) payload(effective, src, s, withSource = withSrc).toString() else fillTemplate(s.webhookBodyTemplate, effective, src, s.copy(sendSourceToTargets = withSrc))
            val res = Http.postJson(s.webhookUrl.trim(), body, if (auth.isBlank()) emptyMap() else mapOf("Authorization" to auth))
            DeliveryResult(target, true, "webhook " + res.take(300))
          }
        }
        TargetType.AI_AGENT -> aiAgent(context, effective, s, secrets, src)
        TargetType.AUTOMATION_BROADCAST -> broadcast(context, effective, src, s)
        TargetType.SHARE -> DeliveryResult(target, true, "share", shareIntent(context, effective, s))
      }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      throw e
    } catch (e: Exception) {
      DeliveryResult(target, false, e.message?.take(200) ?: e.javaClass.simpleName, transient = isTransient(e))
    }

  /** Network trouble, timeouts, 408/429 and server errors; not a wrong setting, a missing permission or a 4xx answer. */
  fun isTransient(e: Throwable): Boolean = when (e) {
    is de.juliandigitalproducts.notitocal.net.InsecureUrlException -> false
    is de.juliandigitalproducts.notitocal.net.HttpException -> e.code == 408 || e.code == 429 || e.code >= 500
    is java.io.IOException -> true
    else -> false
  }

  /** Targets marked "✓" in a stored delivery summary (see Pipeline.deliver). */
  fun succeededTargets(summary: String): Set<TargetType> =
    summary.lineSequence().filter { it.startsWith("✓ ") }
      .mapNotNull { line -> TargetType.entries.firstOrNull { line.removePrefix("✓ ").startsWith(it.name + ":") } }
      .toSet()
}
