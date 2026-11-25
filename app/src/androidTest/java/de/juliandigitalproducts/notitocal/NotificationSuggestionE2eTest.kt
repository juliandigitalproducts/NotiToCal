package de.juliandigitalproducts.notitocal

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import de.juliandigitalproducts.notitocal.capture.NotificationCaptureService
import de.juliandigitalproducts.notitocal.data.ActionMode
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.LocationRecognitionMode
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Status
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * E2E on a real device or emulator: notifications are posted by the shell (`cmd notification post`, package
 * com.android.shell), the real NotificationListener picks them up, and the suggestion has to appear in the
 * notification shade (checked with UI Automator).
 *
 * Run (emulator or device connected, e.g. Pixel_3a_API_35 arm64 on the Mac):
 *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.juliandigitalproducts.notitocal.NotificationSuggestionE2eTest
 * Note: the test switches the app's settings to the offline rules and adds com.android.shell to the app list.
 */
@RunWith(AndroidJUnit4::class)
class NotificationSuggestionE2eTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val app get() = instrumentation.targetContext.applicationContext as NotiToCalApp
  private val device = UiDevice.getInstance(instrumentation)
  private val listener get() = "${app.packageName}/${NotificationCaptureService::class.java.name}"

  /** Runs a command line through `sh` (stdin), so arguments may contain spaces and quotes. */
  private fun shell(command: String): String {
    val (out, input) = instrumentation.uiAutomation.executeShellCommandRw("sh")
    FileOutputStream(input.fileDescriptor).use { it.write("$command\nexit\n".toByteArray()) }
    input.close()
    return ParcelFileDescriptor.AutoCloseInputStream(out).use { FileInputStream(it.fd).readBytes().toString(Charsets.UTF_8) }
  }

  private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

  private fun post(tag: String, title: String, text: String) {
    shell("cmd notification post -t ${quote(title)} ${quote(tag)} ${quote(text)}")
  }

  private fun <T> waitFor(timeoutMs: Long, what: String, probe: () -> T?): T {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      probe()?.let { return it }
      Thread.sleep(250)
    }
    throw AssertionError("timed out waiting for $what")
  }

  private fun entryWithText(part: String): LogEntry? =
    app.log.allEntries().firstOrNull { it.packageName == SHELL && it.text.contains(part) }

  @Before fun setUp() {
    runBlocking {
      shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
      shell("cmd notification allow_listener $listener")
      app.log.clear()
      app.settings.update {
        it.copy(
          onboardingDone = true,
          paused = false,
          engine = AiEngine.RULES,
          locationRecognitionMode = LocationRecognitionMode.FAST,
          locationEnrichmentEnabled = false,
          notificationsEnabled = true,
          monitoredApps = it.monitoredApps + SHELL,
          ignoreGroupChats = false,
          eventMode = ActionMode.ASK,
          notifyConfirm = true,
          quietHours = false,
          storeMessageText = true,
          dedupeMinutes = 0,
          eventTargets = setOf(TargetType.AUTOMATION_BROADCAST),
        )
      }
      waitFor(20_000, "notification listener") { NotificationCaptureService.connected.takeIf { it } }
    }
  }

  @After fun tearDown() {
    device.pressHome()
  }

  @Test fun chatMessageBecomesASuggestionInTheShade() {
    post("ntc_e2e_event", "Anna", "Treffen wir uns morgen um 15 Uhr im Café Lotte?")
    val entry = waitFor(30_000, "the suggestion") { entryWithText("Café Lotte")?.takeIf { it.detection.kind == Kind.EVENT } }
    assertEquals(Status.PENDING, entry.status)
    assertEquals("${LocalDate.now().plusDays(1)}T15:00", entry.detection.start)

    device.openNotification()
    val found = app.getString(R.string.notif_event_found)
    assertNotNull("suggestion notification", device.wait(Until.findObject(By.textStartsWith(found)), 15_000))
    device.pressBack()
  }

  @Test fun noiseGivesNoSuggestion() {
    post("ntc_e2e_noise", "Gruppe", "haha ja genau, das war lustig gestern 😂")
    // A real appointment afterwards: once it is in, the noise before it has been looked at, too.
    post("ntc_e2e_after", "Praxis", "Ihr Termin beim Zahnarzt ist übermorgen um 9:30 Uhr bestätigt.")
    waitFor(30_000, "the appointment") { entryWithText("Zahnarzt") }
    val noise = waitFor(10_000, "the noise entry") { entryWithText("haha ja genau") }
    assertTrue(noise.detection.kind == Kind.NONE || noise.status == Status.IGNORED)
    assertTrue(app.log.allEntries().none { it.text.contains("haha") && it.status == Status.PENDING })
  }

  private companion object {
    const val SHELL = "com.android.shell"
  }
}
