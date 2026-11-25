package de.juliandigitalproducts.notitocal

import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.LocationRecognitionMode
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.model.Why
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** End-to-end through the real pipeline (offline rules, automation broadcast as target – no network, no calendar). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NotiToCalApp::class)
class PipelineFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<NotiToCalApp>()
  private val year = java.time.LocalDate.now().year + 1

  @Before fun setUp() {
    runBlocking {
      app.log.clear()
      app.settings.update {
        it.copy(
          onboardingDone = true,
          engine = AiEngine.RULES,
          locationRecognitionMode = LocationRecognitionMode.FAST,
          locationEnrichmentEnabled = false,
          eventTargets = setOf(TargetType.AUTOMATION_BROADCAST),
          taskTargets = setOf(TargetType.AUTOMATION_BROADCAST),
          autoAddConfidence = 0.0,
          eventMode = de.juliandigitalproducts.notitocal.data.ActionMode.AUTO,
          taskMode = de.juliandigitalproducts.notitocal.data.ActionMode.AUTO,
          minTextLength = 1,
          dedupeMinutes = 0,
        )
      }
    }
  }

  private fun send(text: String, sender: String, pkg: String) = runBlocking {
    app.pipeline.process(Captured(SourceType.NOTIFICATION, packageName = pkg, appLabel = "Chat", sender = sender, text = text))
  }

  private fun events() = app.log.allEntries().filter { it.detection.kind == Kind.EVENT }

  @Test fun endOfEventUpdatesTheEventInsteadOfAddingOne() {
    val first = send("Die Veranstaltung ist am 20.03.$year um 14 Uhr im Bürgerhaus", "Anna", "com.example.a")
    assertNotNull(first)
    assertEquals(1, events().size)
    send("Ende der Veranstaltung am 20.03.$year um 15 Uhr", "Anna", "com.example.a")
    val all = events()
    assertEquals(1, all.size)
    assertTrue(all.single().detection.end.orEmpty().endsWith("T15:00"))
    assertTrue(all.single().detection.start.orEmpty().endsWith("T14:00"))
  }

  @Test fun sameTitleAtAnotherTimeIsKeptAndAskedWithoutAi() {
    send("Kunde am 21.03.$year um 14 Uhr in Solingen", "Chef", "com.example.b")
    send("Kunde treffen am 21.03.$year um 15 Uhr in Solingen", "Chef", "com.example.b")
    val all = events().sortedBy { it.id }
    assertEquals(2, all.size)
    assertEquals(Why.POSSIBLE_DUPLICATE.name, all[1].detection.why)
    assertEquals(Status.PENDING, all[1].status)
  }

  @Test fun movedAppointmentChangesTheTime() {
    send("Kunde am 22.03.$year um 14 Uhr in Solingen", "Chef", "com.example.c")
    send("Der Kunde in Solingen am 22.03.$year wird auf 15 Uhr verschoben", "Chef", "com.example.c")
    val all = events()
    assertEquals(1, all.size)
    assertTrue(all.single().detection.start.orEmpty().endsWith("T15:00"))
  }

  @Test fun analyseNowReportsProgressPerMessage() = runBlocking {
    app.log.enqueue(Captured(SourceType.NOTIFICATION, packageName = "com.example.e", appLabel = "Chat", sender = "A", text = "Treffen am 24.03.$year um 10 Uhr"))
    app.log.enqueue(Captured(SourceType.NOTIFICATION, packageName = "com.example.e", appLabel = "Chat", sender = "B", text = "Essen am 25.03.$year um 19 Uhr"))
    val steps = mutableListOf<Pair<Int, Int>>()
    val summary = app.batch.runNow { done, total -> steps += done to total }
    assertEquals(2, summary.processed)
    assertTrue(steps.all { it.second == 3 })
    assertEquals(listOf(0, 1, 2, 3), steps.map { it.first })
  }

  @Test fun resortGivesAPendingEntryTheNewRule() = runBlocking {
    app.settings.update { it.copy(eventMode = de.juliandigitalproducts.notitocal.data.ActionMode.ASK) }
    send("Kunde am 26.03.$year um 11 Uhr in Köln", "Chef", "com.example.f")
    val rule = de.juliandigitalproducts.notitocal.data.RoutingRule(id = "work", name = "Arbeit", keywords = "Kunde")
    app.settings.update { it.copy(routingRules = listOf(rule)) }
    val resort = de.juliandigitalproducts.notitocal.pipeline.Resort(app)
    val (changes, checked) = resort.plan(java.time.LocalDate.of(year, 3, 1), java.time.LocalDate.of(year, 3, 31))
    assertEquals(1, checked)
    val summary = resort.run(changes, checked)
    assertEquals(1, summary.changed)
    assertEquals("work", events().single().detection.route)
  }

  @Test fun deliveredEntriesGetAStableUid() {
    send("Zahnarzt am 23.03.$year um 9 Uhr", "Praxis", "com.example.d")
    val e = events().single()
    assertEquals(Status.ADDED, e.status)
    assertNotNull(e.detection.syncUid)
  }
}
