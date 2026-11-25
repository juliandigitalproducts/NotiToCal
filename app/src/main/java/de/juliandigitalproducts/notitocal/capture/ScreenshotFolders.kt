package de.juliandigitalproducts.notitocal.capture

import android.net.Uri
import android.provider.DocumentsContract
import de.juliandigitalproducts.notitocal.NotiToCalApp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import de.juliandigitalproducts.notitocal.model.runSafely

/** New images in the screenshot folders the user picked. Uses the same folder walk as voice notes. */
object ScreenshotFolders {
  private const val PREFS = "screenshot_folders"
  private val lock = Mutex()

  fun initialUri(): Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Pictures/Screenshots")

  fun baseline(app: NotiToCalApp, folder: String) {
    FolderWatch.baseline(app, PREFS, folder, FolderWatch.Kind.IMAGE)
  }

  suspend fun scan(
    app: NotiToCalApp,
    maxAgeMinutes: Int = 12 * 60,
    limit: Int = 5,
    onProgress: (current: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
  ): Int = lock.withLock {
    val s = app.settings.current
    if (!s.screenshotsEnabled || s.paused || s.screenshotFolderUris.isEmpty()) return 0
    var left = limit
    var count = 0
    for (folder in s.screenshotFolderUris) {
      if (left <= 0) break
      val fresh = FolderWatch.fresh(app, PREFS, folder, FolderWatch.Kind.IMAGE, maxAgeMinutes, left)
      fresh.forEachIndexed { i, f ->
        val label = f.uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.ifBlank { null } ?: "Screenshot ${count + 1}"
        onProgress(count + 1, fresh.size, label)
        FolderWatch.mark(app, PREFS, listOf(f.id))
        runSafely { MediaProcessor.processScreenshot(app, f.uri, f.name) }
        count++
        left--
      }
    }
    count
  }
}
