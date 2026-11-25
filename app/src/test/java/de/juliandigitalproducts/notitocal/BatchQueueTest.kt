package de.juliandigitalproducts.notitocal

import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.LocationRecognitionMode
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NotiToCalApp::class)
class BatchQueueTest {
  private val app get() = ApplicationProvider.getApplicationContext<NotiToCalApp>()

  @Before fun setUp() {
    runBlocking {
      runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor()).build()) }
      app.log.clear()
      app.settings.update {
        it.copy(
          onboardingDone = true, engine = AiEngine.RULES, locationRecognitionMode = LocationRecognitionMode.FAST,
          locationEnrichmentEnabled = false, eventTargets = setOf(TargetType.AUTOMATION_BROADCAST),
          taskTargets = setOf(TargetType.AUTOMATION_BROADCAST),
        )
      }
    }
  }

  @Test fun queuedMessageWithPlaceIsAnalysedWithDefaultSettings() = runBlocking {
    app.settings.update { de.juliandigitalproducts.notitocal.data.AppSettings().copy(onboardingDone = true, eventTargets = setOf(TargetType.AUTOMATION_BROADCAST), taskTargets = setOf(TargetType.AUTOMATION_BROADCAST)) }
    app.log.enqueue(Captured(SourceType.SMS_RCS, packageName = "com.google.android.apps.messaging", appLabel = "Messages",
      sender = "01701234567", text = "Hi Alex! Shall we meet on the 12th at 7 pm at the Italian place on Main Street?", postedAt = System.currentTimeMillis()))
    val r = runCatching { app.pipeline.process(app.log.queuedItems().single().captured, quiet = true) }
    assertEquals(null, r.exceptionOrNull())
    assertEquals(de.juliandigitalproducts.notitocal.analysis.Decision.ASK, r.getOrNull()?.decision)
  }

  @Test fun queuedMessageIsAnalysedByTheScheduledRun() = runBlocking {
    // Always a Thursday in the future: a fixed date turns into the past and the entry is (rightly) ignored.
    val day = java.time.LocalDate.now().plusDays(2).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.THURSDAY))
    val date = day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ENGLISH))
    val text = "Dentist appointment on $date at 10:30, Dr Martin, Hauptstrasse 5"
    app.log.enqueue(Captured(SourceType.NOTIFICATION, packageName = "com.google.android.apps.messaging", appLabel = "Messages",
      sender = "01705555", text = text, postedAt = System.currentTimeMillis()))
    val summary = app.batch.runNow()
    assertEquals(1, summary.processed)
    assertEquals(1, summary.added + summary.asked)
  }
}

