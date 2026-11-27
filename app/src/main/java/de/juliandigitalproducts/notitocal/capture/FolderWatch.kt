// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import de.juliandigitalproducts.notitocal.model.runSafely

/**
 * Lists files in a folder the user granted with the Storage Access Framework.
 * Shared by voice notes and screenshot folders. Nothing here deletes user files.
 */
object FolderWatch {
  enum class Kind { IMAGE, TEXT }

  data class Doc(val uri: Uri, val id: String, val modified: Long, val name: String = "")

  private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
  private val TEXT_EXT = setOf("txt", "srt", "vtt")

  fun persist(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }

  /** Drops the app's access. Does not delete anything in the folder. */
  fun release(context: Context, uri: String) {
    runSafely { context.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
  }

  fun label(uri: String): String =
    runSafely { DocumentsContract.getTreeDocumentId(uri.toUri()).substringAfter(':').ifBlank { "/" } }.getOrDefault(uri)

  fun list(context: Context, tree: Uri, kind: Kind): List<Doc> {
    val out = mutableListOf<Doc>()
    fun walk(docId: String, depth: Int) {
      val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
      context.contentResolver.query(
        children,
        arrayOf(
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
          DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        ),
        null, null, null,
      )?.use { c ->
        while (c.moveToNext()) {
          val id = c.getString(0) ?: continue
          val name = c.getString(1).orEmpty()
          val mime = c.getString(2).orEmpty()
          if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            // WhatsApp keeps one sub folder per week; voice notes the user sent go to "Sent" (if present).
            if (depth < 2 && !name.startsWith(".")) walk(id, depth + 1)
          } else if (matches(name, mime, kind)) {
            out += Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, c.getLong(3), name)
          }
        }
      }
    }
    walk(DocumentsContract.getTreeDocumentId(tree), 0)
    return out.sortedByDescending { it.modified }
  }

  fun baseline(context: Context, prefsName: String, folder: String, kind: Kind) {
    val ids = runSafely { list(context, folder.toUri(), kind).map { it.id } }.getOrDefault(emptyList())
    saveDone(context, prefsName, done(context, prefsName) + ids)
  }

  /** Files newer than [maxAgeMinutes] that have not been handled yet. */
  fun fresh(context: Context, prefsName: String, folder: String, kind: Kind, maxAgeMinutes: Int, limit: Int): List<Doc> {
    val handled = done(context, prefsName)
    val since = System.currentTimeMillis() - maxAgeMinutes * 60_000L
    return runSafely { list(context, folder.toUri(), kind) }.getOrDefault(emptyList())
      .filter { it.id !in handled && it.modified >= since }
      .take(limit)
  }

  fun mark(context: Context, prefsName: String, ids: Collection<String>) {
    if (ids.isEmpty()) return
    saveDone(context, prefsName, done(context, prefsName) + ids)
  }

  private fun matches(name: String, mime: String, kind: Kind): Boolean = when (kind) {
    Kind.IMAGE -> name.substringAfterLast('.').lowercase() in IMAGE_EXT || mime.startsWith("image/")
    Kind.TEXT -> name.substringAfterLast('.').lowercase() in TEXT_EXT || mime.startsWith("text/")
  }

  private fun done(context: Context, prefsName: String): MutableSet<String> =
    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getStringSet("done", emptySet())!!.toMutableSet()

  private fun saveDone(context: Context, prefsName: String, ids: Set<String>) =
    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit { putStringSet("done", ids.toList().takeLast(800).toSet()) }
}
