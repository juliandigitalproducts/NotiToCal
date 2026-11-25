package de.juliandigitalproducts.notitocal.capture

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import de.juliandigitalproducts.notitocal.model.runSafely

object AntiVocaleWatch {
  private const val PREFS = "anti_vocale_tracker"

  data class TranscriptItem(
    val uri: Uri,
    val fileName: String,
    val text: String,
    val modified: Long,
  )

  /**
   * Checks whether the user-selected folder is accessible and counts available text files.
   * Returns -1 if an error occurred / permission is missing, or the number of text files.
   */
  fun checkFolder(context: Context, tree: Uri): Int {
    return runSafely {
      val docs = FolderWatch.list(context, tree, FolderWatch.Kind.TEXT)
      docs.size
    }.getOrElse { -1 }
  }

  /**
   * Scans the Anti-Vocale export folder for new transcripts, oldest first, at most [limit].
   * Supports BOTH scenarios:
   * 1. Multi-file mode: Anti-Vocale creates a new .txt (or .srt/.vtt) file per voice message.
   * 2. Single-file mode: Anti-Vocale appends to a single export file over time; only the appended part is returned.
   *
   * Only the returned files are marked as read, so a larger backlog is picked up by the next call.
   * Files seen for the first time that are older than [maxAgeMinutes] are recorded without being returned,
   * so choosing a folder with an existing archive does not create a flood of old entries.
   */
  fun findNewTranscripts(context: Context, tree: Uri, maxAgeMinutes: Int = 60, limit: Int = Int.MAX_VALUE): List<TranscriptItem> {
    val files = runSafely { FolderWatch.list(context, tree, FolderWatch.Kind.TEXT) }.getOrDefault(emptyList())
    if (files.isEmpty()) return emptyList()

    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val since = System.currentTimeMillis() - maxAgeMinutes * 60_000L
    val results = mutableListOf<TranscriptItem>()

    for (file in files.sortedBy { it.modified }) {
      if (results.size >= limit) break
      val lastModKey = "mod_${file.id}"
      val lastLenKey = "len_${file.id}"
      val lastHashKey = "hash_${file.id}"

      val prevMod = prefs.getLong(lastModKey, 0L)
      val prevLen = prefs.getInt(lastLenKey, 0)
      val prevHash = prefs.getString(lastHashKey, null)

      // Already processed and not modified since.
      if (prevMod > 0L && file.modified <= prevMod) continue

      val raw = runSafely {
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
      }.getOrNull().orEmpty()

      fun remember() = prefs.edit {
        putLong(lastModKey, file.modified)
        putInt(lastLenKey, raw.length)
        putString(lastHashKey, raw.hashCode().toString())
      }

      if (raw.isBlank()) continue
      if (prevMod == 0L && file.modified < since) {
        remember()
        continue
      }
      val newContent = newPart(raw, prevLen, prevHash)
      if (newContent == null) {
        remember()
        continue
      }

      val clean = AntiVocaleParser.cleanTranscript(newContent)
      val fileName = file.uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.ifBlank { null } ?: "Anti-Vocale"
      if (clean.isNotBlank()) results.add(TranscriptItem(file.uri, fileName, clean, file.modified))
      remember()
    }

    return results
  }

  /**
   * The part of [raw] that was not analysed yet, null if nothing changed. Appended to an existing file: the old content
   * is unchanged at the start (same hash as last time), so only the new suffix counts. Anything else (new file,
   * rewritten file) counts as a whole.
   */
  fun newPart(raw: String, prevLen: Int, prevHash: String?): String? {
    if (prevHash != null && prevHash == raw.hashCode().toString()) return null
    val appended = prevLen in 1 until raw.length && raw.substring(0, prevLen).hashCode().toString() == prevHash
    return if (appended) raw.substring(prevLen) else raw
  }

  fun resetBaseline(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
  }
}
