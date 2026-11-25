package de.juliandigitalproducts.notitocal

import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.data.LogRepository
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LogRepositoryTest {
  private lateinit var repo: LogRepository
  private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Before fun setUp() {
    context.deleteDatabase("notitocal.db")
    repo = LogRepository(context)
  }

  @After fun tearDown() {
    repo.close()
  }

  private fun add(text: String, title: String = "T") =
    repo.insert(SourceType.NOTIFICATION, "pkg", "App", "Anna", text, Detection(Kind.EVENT, title = title), Status.PENDING, "h-$text")

  @Test fun groupFlagIsStoredAndReadBack() {
    val id = repo.insert(SourceType.WHATSAPP, "pkg", "App", "Familie", "Treffen", Detection(Kind.EVENT, title = "T"), Status.PENDING, "h-grp", isGroup = true)
    assertTrue(repo.entries.value.first { it.id == id }.isGroup)
    repo.close()
    repo = LogRepository(context)
    assertTrue(repo.get(id)!!.isGroup)
    assertFalse(repo.get(add("direkt"))!!.isGroup)
  }

  @Test fun upgradeFromVersion5AddsTheGroupColumn() {
    repo.close()
    context.deleteDatabase("notitocal.db")
    context.openOrCreateDatabase("notitocal.db", android.content.Context.MODE_PRIVATE, null).use { db ->
      db.execSQL(
        "CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, source TEXT NOT NULL, pkg TEXT, " +
          "app TEXT, sender TEXT, text TEXT, detection TEXT NOT NULL, status TEXT NOT NULL, result TEXT, hash TEXT)",
      )
      db.execSQL("INSERT INTO log (created, source, pkg, app, sender, text, detection, status, result, hash) VALUES (1, 'WHATSAPP', 'pkg', 'App', 'Familie', 'alt', '{}', 'PENDING', '', 'h')")
      db.version = 5
    }
    repo = LogRepository(context)
    val old = repo.allEntries().single()
    assertEquals("Familie", old.sender)
    assertFalse(old.isGroup)
  }

  @Test fun getFindsRowsOutsideTheInMemoryList() {
    val first = add("erste Nachricht")
    repeat(LogRepository.IN_MEMORY_LIMIT + 5) { add("Nachricht $it") }
    assertFalse(repo.entries.value.any { it.id == first })
    val found = repo.get(first)
    assertNotNull(found)
    assertEquals("erste Nachricht", found!!.text)
    // The UI list is not blown up as a side effect.
    assertEquals(LogRepository.IN_MEMORY_LIMIT, repo.entries.value.size)
    assertNull(repo.get(999_999))
  }

  @Test fun historyIsPagedAndSearchable() {
    repeat(350) { add("Nachricht $it", title = if (it == 3) "Zahnarzt 50%" else "T") }
    assertEquals(350, repo.count())
    assertEquals(100, repo.queryHistory(limit = 100, offset = 0).size)
    assertEquals(50, repo.queryHistory(limit = 100, offset = 300).size)
    assertEquals(350, repo.allEntries().size)
    assertEquals(1, repo.queryHistory("Zahnarzt").size)
    // % is matched literally, not as wildcard.
    assertEquals(1, repo.queryHistory("50%").size)
  }

  @Test fun deletingEntriesRemovesTheirScreenshots() {
    val id = add("mit Bild")
    val other = add("ohne Bild")
    val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
    val shot = File(dir, "screenshot_$id.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val keep = File(dir, "screenshot_$other.jpg").apply { writeBytes(byteArrayOf(1)) }
    val orphan = File(dir, "screenshot_123456.jpg").apply { writeBytes(byteArrayOf(1)) }
    repo.delete(id)
    waitFor { !shot.exists() }
    assertFalse(shot.exists())
    assertFalse(orphan.exists())
    assertTrue(keep.exists())
    repo.clear()
    waitFor { !keep.exists() }
    assertFalse(keep.exists())
  }

  @Test fun conversationSinceReturnsTheThreadOldestFirst() {
    add("eins"); add("zwei")
    val thread = repo.conversationSince("pkg", "Anna", 0L)
    assertEquals(listOf("eins", "zwei"), thread.map { it.text })
  }

  private fun waitFor(cond: () -> Boolean) {
    val until = System.currentTimeMillis() + 5_000
    while (!cond() && System.currentTimeMillis() < until) Thread.sleep(20)
  }
}
