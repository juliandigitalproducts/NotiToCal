// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** What a piece of content turned out to be. */
@Serializable
enum class Kind { EVENT, TASK, NOTE, NONE }

/** Where the analysed content came from. */
@Serializable
enum class SourceType {
  NOTIFICATION, SCREENSHOT, VOICE_MEMO, SHARED_TEXT, SHARED_IMAGE, MANUAL,
  IMAP, TELEGRAM, CLIPBOARD, TELEGRAM_API, WHATSAPP, SMS_RCS, CALENDAR_ICS,
}

/** Raw input for the pipeline. */
data class Captured(
  val source: SourceType,
  val packageName: String = "",
  val appLabel: String = "",
  val sender: String = "",
  val text: String,
  val isGroup: Boolean = false,
  val imageBytes: ByteArray? = null,
  val imageMime: String? = null,
  val postedAt: Long = System.currentTimeMillis(),
  val isSelfTest: Boolean = false,
)

/**
 * Structured result of an analysis. Dates are ISO-8601 local values ("2026-11-01T15:00" or "2026-11-01")
 * so they can be produced by any LLM and stored as text.
 */
@Serializable
data class Detection(
  val kind: Kind = Kind.NONE,
  val title: String = "",
  val description: String = "",
  val start: String? = null,
  val end: String? = null,
  @SerialName("all_day") val allDay: Boolean = false,
  val due: String? = null,
  val location: String? = null,
  val priority: Int = 0,
  val confidence: Double = 0.0,
  val reason: String = "",
  val engine: String = "",
  /** Id of the [de.juliandigitalproducts.notitocal.data.RoutingRule] used for delivery (null = defaults). */
  val route: String? = null,
  /** Category name proposed by the AI (matched to a routing rule by name). */
  val category: String? = null,
  /** Why it was (not) added automatically: a [Why] name plus the value / limit involved, shown in the user's language. */
  val why: String? = null,
  @SerialName("why_value") val whyValue: Double? = null,
  @SerialName("why_limit") val whyLimit: Double? = null,
  /** What the current settings would do with it as a real notification (AUTO / ASK / IGNORE), also for tests and shares. */
  @SerialName("real_decision") val realDecision: String? = null,
  /** The model's raw answer (shortened), shown in the AI test to see what went wrong. */
  @SerialName("raw_answer") val rawAnswer: String? = null,
  @SerialName("algorithm_version") val algorithmVersion: Int = CURRENT_ALGORITHM_VERSION,
  @SerialName("is_end_estimated") val isEndEstimated: Boolean = false,
  @SerialName("estimation_reason") val estimationReason: String? = null,
  @SerialName("location_source") val locationSource: String? = null,
  @SerialName("is_provisional_day") val isProvisionalDay: Boolean = false,
  @SerialName("action_type") val actionType: String = "CREATE_NEW",
  @SerialName("target_event_id") val targetEventId: String? = null,
  /** Id of the device calendar event this entry created (CalendarContract.Events._ID), so updates hit exactly it. */
  @SerialName("calendar_event_id") val calendarEventId: Long? = null,
  /** Stable id sent to CalDAV (UID), the server, webhooks and automations, so a later change updates the same item. */
  @SerialName("sync_uid") val syncUid: String? = null,
  /** Ids the external services returned (TargetType name → id, e.g. the Todoist task or the Notion page). */
  @SerialName("remote_ids") val remoteIds: Map<String, String> = emptyMap(),
  /**
   * The sender as the analysis saw it (sign-off name or notification sender). Kept so that a title like
   * "Treffen (Anna)" can be sent without the name where the sender must not go, also when the message text is gone.
   */
  @SerialName("sender_name") val senderName: String? = null,
  /** The description was made without the message ("Keep message text" off), so it carries none of it. */
  @SerialName("message_free_description") val messageFreeDescription: Boolean = false,
) {
  fun startDateTime(): LocalDateTime? = parseDateTime(start)
  fun endDateTime(): LocalDateTime? = parseDateTime(end)
  fun dueDateTime(): LocalDateTime? = parseDateTime(due)
  fun hasExplicitTime(): Boolean = start?.contains('T') == true || due?.contains('T') == true
  fun hasDate(): Boolean = start != null || due != null
  fun isUpdateExisting(): Boolean = actionType.equals("UPDATE_EXISTING", ignoreCase = true)

  companion object {
    const val CURRENT_ALGORITHM_VERSION = 2
    fun parseDateTime(value: String?): LocalDateTime? {
      if (value.isNullOrBlank() || value == "null") return null
      val trimmed = value.trim()

      // Convert UTC 'Z' or timezone offset to local system timezone using OffsetDateTime / Instant
      if (trimmed.endsWith("Z", ignoreCase = true)) {
        val converted = runSafely {
          Instant.parse(trimmed).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }.getOrNull()
        if (converted != null) return converted
      }
      val tIndex = trimmed.indexOf('T')
      if (tIndex > 0 && (trimmed.indexOf('+', tIndex) > 0 || trimmed.indexOf('-', tIndex) > 0)) {
        val converted = runSafely {
          OffsetDateTime.parse(trimmed).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        }.getOrNull()
        if (converted != null) return converted
      }

      val v = trimmed.removeSuffix("Z").substringBefore('+').let {
        // Strip a "-02:00" style offset after the time part if fallback parsing is needed.
        val t = it.indexOf('T')
        if (t > 0 && it.lastIndexOf('-') > t) it.substring(0, it.lastIndexOf('-')) else it
      }
      return runSafely {
        if (v.contains('T')) {
          val (d, t) = v.split('T', limit = 2)
          LocalDateTime.of(LocalDate.parse(d), LocalTime.parse(if (t.length == 2) "$t:00" else t))
        } else {
          LocalDate.parse(v).atStartOfDay()
        }
      }.getOrNull()
    }
  }
}

/** Reasons for the decision, explained to the user in [de.juliandigitalproducts.notitocal.ui.explainWhy]. */
enum class Why {
  AUTO, ASK_MODE, ASK_CONFIDENCE, BELOW_MIN, BLOCKED_KEYWORD, NO_DATE, NO_TIME, PAST, DEADLINE_PASSED, TOO_FAR, KIND_OFF,
  NOTHING, NOTE, PREFILTER, AI_ERROR, DUPLICATE, SEEN, TOO_SHORT, BLOCKED_SENDER, GROUP, PAUSED,
  /** Optional address-only suggestion (setting "addressSuggestions"), always to be confirmed. */
  LOCATION_ONLY,
  /** Similar entry at another time on the same day, and no AI could tell whether it was moved: to be confirmed. */
  POSSIBLE_DUPLICATE,
}

/** Status of a logged detection. */
enum class Status { PENDING, ADDED, PARTIAL, FAILED, DISMISSED, IGNORED }
