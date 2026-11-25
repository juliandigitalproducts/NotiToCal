package de.juliandigitalproducts.notitocal

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.analysis.Analyzer
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.ProviderType
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.output.Privacy
import de.juliandigitalproducts.notitocal.output.Targets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The open points of the third review fixed after Julian's t56u (20.11.2026). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NotiToCalApp::class)
class OpenPoints1120Test {
  private val app get() = ApplicationProvider.getApplicationContext<NotiToCalApp>()

  /** Calendar provider that knows one event (id 7) and records what is written to it. */
  class FakeCalendar : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor =
      MatrixCursor(arrayOf(CalendarContract.Events._ID)).apply { if (uri.lastPathSegment == "7") addRow(arrayOf<Any>(7L)) }
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?): Int {
      updates += values!!
      return 1
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?) = 0
    override fun getType(uri: Uri): String? = null

    companion object { val updates = mutableListOf<ContentValues>() }
  }

  // ------------------------------------------------------------- (a) device calendar update without the sender

  @Test fun deviceCalendarUpdateTakesTheSenderOutOfOldTitles() {
    Robolectric.setupContentProvider(FakeCalendar::class.java, CalendarContract.AUTHORITY)
    shadowOf(app as android.app.Application).grantPermissions(Manifest.permission.WRITE_CALENDAR)
    FakeCalendar.updates.clear()
    // Made while "Sender in title" was on: the name is in the stored title, no sender_name recorded.
    val old = Detection(Kind.EVENT, title = "Treffen im Café (Anna Schmidt)", start = "2026-11-21T15:00", engine = "rules")
    val r = Targets.updateDeviceCalendar(app, old, AppSettings(senderInTitle = false), eventId = 7L, sender = "Anna Schmidt @ Familie")
    assertTrue(r.message, r.ok)
    assertEquals("Treffen im Café", FakeCalendar.updates.single().getAsString(CalendarContract.Events.TITLE))
    // On: unchanged.
    Targets.updateDeviceCalendar(app, old, AppSettings(), eventId = 7L, sender = "Anna Schmidt")
    assertEquals(old.title, FakeCalendar.updates.last().getAsString(CalendarContract.Events.TITLE))
  }

  // ------------------------------------------------------------- (6) "Check manually with AI" without a model

  @Test fun checkWithAiNeedsAModel() = runBlocking {
    val rules = AppSettings(engine = AiEngine.RULES, onDeviceModelFile = "", onDeviceModelUri = "")
    assertFalse(Analyzer.canCheckWithAi(app, rules))
    try {
      app.analyzer.analyzeForcedAi(Captured(SourceType.NOTIFICATION, "com.example", "Chat", "Anna", "Treffen morgen um 15 Uhr"), rules)
      fail("must not return the rules' result as an AI check")
    } catch (_: Analyzer.NoAiForCheck) {
    }
    val model = File.createTempFile("model", ".task").apply { deleteOnExit() }
    assertTrue(Analyzer.canCheckWithAi(app, rules.copy(onDeviceModelFile = model.path)))
    assertTrue(Analyzer.canCheckWithAi(app, AppSettings(engine = AiEngine.CLOUD, provider = ProviderType.CUSTOM)))
    assertTrue(Analyzer.canCheckWithAi(app, AppSettings(engine = AiEngine.SELF_HOSTED)))
    // The hint exists in every language and names the button.
    assertTrue(app.getString(R.string.recheck_no_ai).contains(app.getString(R.string.recheck_single_ai_btn)))
  }

  // ------------------------------------------------------------- (8) partial quotes of the message, "Keep" off

  private val message = "Hey, treffen wir uns morgen um 15 Uhr im Café Lotte? Bring bitte die Unterlagen für das Projekt mit."
  private fun cleaned(description: String) =
    Privacy.forStorage(Detection(Kind.EVENT, title = "Treffen", description = description, engine = "OpenAI"), message, AppSettings(storeMessageText = false)).description

  @Test fun partialQuotesOfTheMessageGo() {
    // A quoted passage, a sentence copying 5+ words, a label with a quote: all go.
    assertEquals("Treffen mit Anna im Café Lotte.", cleaned("Treffen mit Anna im Café Lotte. Sie schreibt: „Bring bitte die Unterlagen für das Projekt mit“."))
    assertEquals("Unterlagen mitnehmen.", cleaned("Unterlagen mitnehmen. Bring bitte die Unterlagen für das Projekt mit!"))
    assertEquals("📍 Café Lotte", cleaned("📍 Café Lotte\nNotizen: \"treffen wir uns morgen um 15 Uhr\""))
    assertEquals("", cleaned("Nachricht: treffen wir uns morgen um 15 Uhr im Café Lotte"))
    // Paraphrases, short quotes and the style's own lines stay.
    assertEquals("Projektunterlagen mitbringen.\nOrt: Café Lotte", cleaned("Projektunterlagen mitbringen.\nOrt: Café Lotte"))
    assertEquals("Ort „Café Lotte“", cleaned("Ort „Café Lotte“"))
    // "Keep" on: nothing changes.
    val full = "Sie schreibt: „Bring bitte die Unterlagen für das Projekt mit“."
    assertEquals(full, Privacy.forStorage(Detection(Kind.EVENT, title = "T", description = full, engine = "OpenAI"), message, AppSettings()).description)
  }
}
