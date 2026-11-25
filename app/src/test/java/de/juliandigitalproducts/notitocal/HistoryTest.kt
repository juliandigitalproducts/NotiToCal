package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.data.HashIndex
import de.juliandigitalproducts.notitocal.data.HistoryPolicy
import de.juliandigitalproducts.notitocal.data.hashRecord
import de.juliandigitalproducts.notitocal.diagnostics.SelfTestTip
import de.juliandigitalproducts.notitocal.diagnostics.antiVocaleTips
import de.juliandigitalproducts.notitocal.diagnostics.ocrEmptyTips
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
  private val now = 1_700_000_000_000L
  private val text = "Termin morgen um 15 Uhr im Cafe Central bitte"
  private val detection = Detection(Kind.EVENT, title = "Cafe Central", start = "2026-11-01T15:00")

  private fun remembered(status: Status = Status.ADDED, kind: Kind = Kind.EVENT, logId: Long = 7L, created: Long = now) =
    hashRecord(logId, created, HistoryPolicy.contentHash(text), status, detection.copy(kind = kind), text)

  @Test fun immediateDeletionDropsOnlyAddedAndIgnored() {
    val dropped = Status.entries.filter { HistoryPolicy.shouldDropAfterProcessing(true, it) }.toSet()
    assertEquals(setOf(Status.ADDED, Status.IGNORED), dropped)
    assertFalse(HistoryPolicy.shouldDropAfterProcessing(false, Status.ADDED))
    assertFalse(HistoryPolicy.shouldDropAfterProcessing(true, Status.PENDING))
    assertFalse(HistoryPolicy.shouldDropAfterProcessing(true, Status.PARTIAL))
    assertFalse(HistoryPolicy.shouldDropAfterProcessing(true, Status.FAILED))
    assertFalse(HistoryPolicy.shouldDropAfterProcessing(true, Status.DISMISSED))
  }

  @Test fun hashStoreKeepsNoMessageText() {
    val rec = remembered()
    assertFalse(rec.hash.contains("Cafe"))
    assertFalse(rec.titleHash.contains("Cafe"))
    assertFalse(rec.textHash.contains("Central"))
    assertEquals(40, rec.hash.length)
    assertEquals(40, rec.titleHash.length)
    assertEquals(40, rec.textHash.length)
    assertEquals("2026-11-01", rec.day)
    assertEquals(HistoryPolicy.contentHash("  termin   MORGEN um 15 Uhr im Cafe Central bitte "), rec.hash)
  }

  @Test fun duplicateDetectionSurvivesAfterTheLogRowIsGone() {
    val index = HashIndex()
    index.upsert(remembered())
    val later = now + 60_000L
    assertTrue(index.seenRecently(HistoryPolicy.contentHash(text), 120, later))
    val dup = index.findDuplicate("Cafe Central", "a completely different wording that is long enough", "2026-11-01", 12 * 60, later)
    assertEquals(7L, dup?.logId)
    assertNull(index.findDuplicate("Cafe Central", text, "2026-11-02", 12 * 60, later))
    assertNull(index.findDuplicate("Cafe Central", text, "2026-11-01", 0, later))
    assertFalse(index.seenRecently(HistoryPolicy.contentHash(text), 0, later))
  }

  @Test fun ignoredAndNotesDoNotCountAsDuplicates() {
    val index = HashIndex(listOf(remembered(status = Status.IGNORED), remembered(status = Status.PENDING, kind = Kind.NOTE, logId = 8)))
    assertNull(index.findDuplicate("Cafe Central", text, "2026-11-01", 12 * 60, now))
    assertTrue(index.seenRecently(HistoryPolicy.contentHash(text), 120, now))
  }

  @Test fun pendingStillCountsAndExplicitRemovalForgetsTheHash() {
    val index = HashIndex()
    index.upsert(remembered(status = Status.PENDING))
    assertEquals(7L, index.findDuplicate(detection.title, text, "2026-11-01", 30, now)?.logId)
    index.remove(7L)
    assertFalse(index.seenRecently(HistoryPolicy.contentHash(text), 120, now))
    assertNull(index.findDuplicate(detection.title, text, "2026-11-01", 30, now))
  }

  @Test fun shortKeysDoNotMatch() {
    val index = HashIndex()
    index.upsert(hashRecord(1, now, "abc", Status.ADDED, Detection(Kind.TASK, title = "Ab", due = "2026-11-01"), "short"))
    assertNull(index.findDuplicate("Ab", "short", "2026-11-01", 60, now))
  }

  @Test fun pruneUsesTheSameCutoffAsTheLog() {
    val cutoff = HistoryPolicy.retentionCutoff(30, now)
    assertEquals(now - 30 * 86_400_000L, cutoff)
    val index = HashIndex()
    index.upsert(remembered(logId = 1, created = cutoff!! - 1))
    index.upsert(remembered(logId = 2, created = cutoff))
    index.prune(0, now)
    assertEquals(listOf(1L, 2L), index.records().map { it.logId })
    index.prune(30, now)
    assertEquals(listOf(2L), index.records().map { it.logId })
    assertNull(HistoryPolicy.retentionCutoff(0, now))
  }
}

class SelfTestHintsTest {
  @Test fun antiVocaleTipsNameTheMissingPiece() {
    assertEquals(listOf(SelfTestTip.ANTI_VOCALE_FOLDER), antiVocaleTips(enabled = true, hasFolder = false))
    assertTrue(antiVocaleTips(enabled = true, hasFolder = true).isEmpty())
    assertEquals(listOf(SelfTestTip.ANTI_VOCALE_OFF), antiVocaleTips(enabled = false, hasFolder = true))
  }

  @Test fun emptyOcrWithoutVisionIsItsOwnHint() {
    assertEquals(listOf(SelfTestTip.OCR_EMPTY_NO_VISION), ocrEmptyTips(textBlank = true, visionOn = false))
    assertTrue(ocrEmptyTips(textBlank = true, visionOn = true).isEmpty())
    assertTrue(ocrEmptyTips(textBlank = false, visionOn = false).isEmpty())
  }
}
