// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.data

import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Status
import java.security.MessageDigest

/**
 * Rules for deleting processed history and for the content-free duplicate index.
 * The index stores hashes and a day, never the message text, title or sender.
 */
object HistoryPolicy {
  /** Final states removed when [AppSettings.deleteAfterProcessing] is on. Pending confirmations stay. */
  val deletableAfterProcessing = setOf(Status.ADDED, Status.IGNORED)

  /** Same states [LogRepository.findDuplicate] treats as "already captured". */
  val duplicateStatuses = setOf(Status.PENDING, Status.ADDED, Status.PARTIAL)
  val duplicateKinds = setOf(Kind.EVENT, Kind.TASK)

  fun shouldDropAfterProcessing(enabled: Boolean, status: Status): Boolean =
    enabled && status in deletableAfterProcessing

  /** Cutoff used by log pruning. Null keeps every row (keepDays <= 0). */
  fun retentionCutoff(keepDays: Int, nowMillis: Long): Long? =
    if (keepDays <= 0) null else nowMillis - keepDays * 86_400_000L

  fun since(withinMinutes: Int, nowMillis: Long): Long? =
    if (withinMinutes <= 0) null else nowMillis - withinMinutes * 60_000L

  fun normalizeKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

  fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

  /** Hash of the normalised key, or empty when it is too short to count as a duplicate. */
  fun keyedHash(raw: String, minLength: Int): String {
    val key = normalizeKey(raw)
    return if (key.length >= minLength) sha1(key) else ""
  }

  fun dayOf(d: Detection): String =
    (d.startDateTime() ?: d.dueDateTime())?.toLocalDate()?.toString().orEmpty()

  /** Clock time "HH:mm" of an explicitly timed detection, empty for all-day or undated ones. */
  fun timeOf(d: Detection): String =
    if (!d.hasExplicitTime()) "" else (d.startDateTime() ?: d.dueDateTime())?.toLocalTime()?.toString()?.take(5).orEmpty()

  /** Same normalisation the pipeline uses before it stores a message hash. */
  fun contentHash(text: String): String =
    sha1(text.trim().lowercase().replace(Regex("\\s+"), " "))
}

/** One analysed message, without its text. [hash] is the pipeline content hash. */
data class HashRecord(
  val logId: Long,
  val created: Long,
  val hash: String,
  val status: Status,
  val kind: Kind,
  val titleHash: String,
  val textHash: String,
  val day: String,
  /** Start time "HH:mm", empty when unknown. Two clearly different times are not a plain duplicate. */
  val time: String = "",
)

fun hashRecord(
  logId: Long,
  created: Long,
  hash: String,
  status: Status,
  detection: Detection,
  fingerprintText: String,
) = HashRecord(
  logId = logId,
  created = created,
  hash = hash,
  status = status,
  kind = detection.kind,
  titleHash = HistoryPolicy.keyedHash(detection.title, 4),
  textHash = HistoryPolicy.keyedHash(fingerprintText, 12),
  day = HistoryPolicy.dayOf(detection),
  time = HistoryPolicy.timeOf(detection),
)

/** In-memory view of the hash store. [LogRepository] loads rows into this and uses the same rules. */
class HashIndex(records: List<HashRecord> = emptyList()) {
  private val items = records.toMutableList()

  fun records(): List<HashRecord> = items.toList()

  fun upsert(record: HashRecord) {
    items.removeAll { it.logId == record.logId }
    items += record
  }

  fun remove(logId: Long) {
    items.removeAll { it.logId == logId }
  }

  /** Drops fingerprints older than the log retention. Rows exactly at the cutoff stay. */
  fun prune(keepDays: Int, nowMillis: Long) {
    val cutoff = HistoryPolicy.retentionCutoff(keepDays, nowMillis) ?: return
    items.removeAll { it.created < cutoff }
  }

  fun seenRecently(hash: String, withinMinutes: Int, nowMillis: Long, text: String = ""): Boolean {
    val since = HistoryPolicy.since(withinMinutes, nowMillis) ?: return false
    if (hash.isBlank() && text.isBlank()) return false
    val tKey = if (text.isNotBlank()) HistoryPolicy.keyedHash(text, 12) else ""
    return items.any {
      it.created >= since && (
        (hash.isNotBlank() && it.hash == hash) ||
        (tKey.isNotEmpty() && it.textHash == tKey)
      )
    }
  }

  /**
   * Same title or same message on the same day. A title-only match with a different start time is NOT returned here:
   * "Kunde 14 Uhr" and "Kunde 15 Uhr" may be two appointments or one that was moved – [DuplicateJudge] decides that.
   */
  fun findDuplicate(title: String, text: String, day: String, withinMinutes: Int, nowMillis: Long, time: String = ""): HashRecord? {
    val since = HistoryPolicy.since(withinMinutes, nowMillis) ?: return null
    val titleHash = HistoryPolicy.keyedHash(title, 4)
    val textHash = HistoryPolicy.keyedHash(text, 12)
    if (titleHash.isEmpty() && textHash.isEmpty()) return null
    return items.firstOrNull { e ->
      val sameText = textHash.isNotEmpty() && e.textHash == textHash
      val sameTitle = titleHash.isNotEmpty() && e.titleHash == titleHash
      val timesDiffer = time.isNotEmpty() && e.time.isNotEmpty() && time != e.time
      e.created >= since &&
        e.status in HistoryPolicy.duplicateStatuses &&
        e.kind in HistoryPolicy.duplicateKinds &&
        (sameText || (sameTitle && !timesDiffer)) &&
        e.day == day
    }
  }
}
