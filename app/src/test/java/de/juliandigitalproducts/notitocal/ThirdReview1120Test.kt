// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.analysis.PipelineA
import de.juliandigitalproducts.notitocal.analysis.PipelineB
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.analysis.StyleTemplate
import de.juliandigitalproducts.notitocal.analysis.StyleTemplates
import de.juliandigitalproducts.notitocal.analysis.TemplateKind
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.ProviderType
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.output.DuplicateJudge
import de.juliandigitalproducts.notitocal.output.Privacy
import de.juliandigitalproducts.notitocal.output.SourceInfo
import de.juliandigitalproducts.notitocal.output.Targets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Third review 20.11.2026 (Julian's decisions): every setting does what its label says.
 * - M5: a stored cloud key is used only with the engine "Cloud".
 * - "Send message text to webhook and server" off: no sender anywhere in what webhook and server get – for every way an
 *   entry is made (rules: "(Anna)", sign-off, venue, "bei uns", address only) and for every title/description style.
 * - "Keep message text" off: the message is not stored and not written into the entry.
 * - "Send sender names to the AI" off: titles going into a model request lose the sender.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NotiToCalApp::class)
class ThirdReview1120Test {
  private val now = LocalDateTime.of(2026, 11, 20, 10, 0)
  private val off = AppSettings(storeMessageText = false, sendSourceToTargets = false, shareSenderWithAi = false)
  private val on = AppSettings(storeMessageText = true, sendSourceToTargets = true, shareSenderWithAi = true)
  private val template = """{"t":"{{title}}","l":"{{location}}","d":"{{description}}","s":"{{sender}}","m":"{{text}}","j":{{json}}}"""

  private fun unfold(t: String) = t.replace("\r\n ", "").replace("\\r\\n ", "")

  /** Everything webhook and own server would get for [d]: JSON payload (with ICS) and a template using every field. */
  private fun outgoing(d: Detection, src: SourceInfo, s: AppSettings): String =
    unfold(Targets.payload(d, src, s).toString()) + "\n" + unfold(Targets.fillTemplate(template, d, src, s))

  // ---------------------------------------------------------------- sender: helpers

  @Test fun senderIsRemovedInEveryForm() {
    val names = Privacy.senderNames(Detection(senderName = "Anna Schmidt"), "Anna Schmidt @ Familie")
    assertEquals(listOf("Anna Schmidt", "Anna"), names)
    assertEquals("Treffen im Café", Privacy.redactSender("Treffen im Café (Anna Schmidt)", names))
    assertEquals("Abendessen", Privacy.redactSender("Abendessen mit Anna", names))
    assertEquals("Treffen", Privacy.redactSender("Anna: Treffen", names))
    assertEquals("📌 Kino", Privacy.redactSender("📌 Kino with Anna", names))
    // Only whole words: "Annaberg" is a place, not the sender.
    assertEquals("Ausflug nach Annaberg", Privacy.redactSender("Ausflug nach Annaberg (Anna)", names))
    val onlyName = Privacy.withoutSender(Detection(Kind.EVENT, title = "Anna", location = "Anna"), names)
    assertTrue(onlyName.title.isNotBlank() && !onlyName.title.contains("Anna"))
    assertNull(onlyName.location)
  }

  // ---------------------------------------------------------------- sender + text: every way the rules make an entry

  private data class Way(val name: String, val text: String, val sender: String, val senderName: String, val addressSuggestions: Boolean = false)

  private val ways = listOf(
    Way("generic title + (sender)", "Treffen morgen um 15 Uhr im Café Lotte, passt das?", "Anna Schmidt", "Anna Schmidt"),
    Way("venue meeting", "Treffen wir uns morgen um 19 Uhr beim Italiener?", "Anna Schmidt @ Familie", "Anna Schmidt"),
    Way("sign-off name", "Kommst du morgen um 18 Uhr zum Training in der Halle Nord? Viele Grüße Max Mustermann", "", "Max Mustermann"),
    Way("invitation to the sender's place", "Wir laden euch am Samstag um 19 Uhr zu uns zum Essen ein! Liebe Grüße Jörg", "Jörg", "Jörg"),
    Way("address only", "Bahnhofstraße 42, 10115 Berlin", "Anna Schmidt", "Anna Schmidt", addressSuggestions = true),
  )

  @Test fun rulesEntriesLeaveWithoutSenderAndMessageWhenOff() {
    ways.forEach { w ->
      val d = RuleEngine.analyze(w.text, w.sender, now, 60, w.addressSuggestions)
      assertTrue("${w.name}: an entry", d.kind == Kind.EVENT)
      assertTrue("${w.name}: the rules name the sender in title or place: ${d.title} / ${d.location}", (d.title + d.location).contains(w.senderName.split(' ').first()))
      val src = SourceInfo("Chat", "com.example.chat", w.sender, w.text, "NOTIFICATION")
      val stored = Privacy.forStorage(d, w.text, off)
      assertEquals("${w.name}: stored description", "", stored.description)
      val out = outgoing(stored, src, off)
      w.senderName.split(' ').forEach { part -> assertFalse("${w.name}: '$part' in $out", out.contains(part)) }
      // (An address-only entry is named after the address: there the "message" is the place itself.)
      if (!w.addressSuggestions) assertFalse("${w.name}: message in $out", out.contains(w.text.take(25)))
      assertTrue("${w.name}: a title stays", Privacy.shareable(stored, src, false).title.isNotBlank())
      // Control: with the switches on both go out.
      val full = outgoing(Privacy.forStorage(d, w.text, on), src, on)
      assertTrue("${w.name}: sender with switch on", full.contains(w.senderName.split(' ').first()))
      assertTrue("${w.name}: message with switch on", full.contains(w.text.take(15)))
    }
  }

  // ---------------------------------------------------------------- every title/description style

  private val message = "Hi, Abendessen morgen um 19 Uhr im Café Lotte? Liebe Grüße Anna"

  /** What a model writes in [t] (the style is part of its instruction), with the sender in the title and the message as notes. */
  private fun styled(t: StyleTemplate): Detection {
    val p = StyleTemplates.preview(t, "de", customText = "Abendessen mit Anna", customDescription = message, customLocation = "Café Lotte", customWhen = "Sa 21.11. · 19:00")
    val kind = if (t.kind == TemplateKind.EVENT) Kind.EVENT else Kind.TASK
    val raw = Detection(kind, title = p.title, description = p.description, location = "Café Lotte", engine = "OpenAI", start = "2026-11-21T19:00", due = "2026-11-21")
    return RuleEngine.sanitizeDetection(raw, message, "Anna Schmidt")
  }

  @Test fun everyStyleHonoursTheSwitches() {
    val src = SourceInfo("WhatsApp", "com.whatsapp", "Anna Schmidt", message, "NOTIFICATION")
    (StyleTemplates.events + StyleTemplates.tasks).forEach { t ->
      val d = styled(t)
      assertTrue("${t.id}: style shows the sender in the title", d.title.contains("Anna"))
      // Keep message text off: the message is not in what is stored and delivered, the style's own parts stay.
      val stored = Privacy.forStorage(d, message, off)
      assertFalse("${t.id}: stored '${stored.description}'", stored.description.contains("Abendessen morgen um 19 Uhr"))
      assertEquals("${t.id}: title unchanged", d.title, stored.title)
      if (t.id == "event_formal") assertTrue(stored.description, stored.description.contains("Café Lotte"))
      // Webhook / server with the switch off: no sender, no message – title, place, description, ICS, template.
      val out = outgoing(stored, src, off)
      assertFalse("${t.id}: sender in $out", out.contains("Anna"))
      assertFalse("${t.id}: message in $out", out.contains("Abendessen morgen um 19 Uhr"))
      assertTrue("${t.id}: the entry itself stays: $out", out.contains("Abendessen"))
      // AI requests with "Send sender names to the AI" off.
      assertFalse("${t.id}: sender to the AI", Privacy.forAi(stored, src.sender, off).title.contains("Anna"))
      assertTrue("${t.id}: sender to the AI when allowed", Privacy.forAi(stored, src.sender, on).title.contains("Anna"))
      // Everything on: nothing is taken away.
      val full = outgoing(Privacy.forStorage(d, message, on), src, on)
      assertTrue("${t.id}: full", full.contains("Anna") && full.contains("Abendessen morgen um 19 Uhr"))
    }
  }

  // ---------------------------------------------------------------- M5: the cloud key only with the engine "Cloud"

  private val app get() = ApplicationProvider.getApplicationContext<NotiToCalApp>()
  private lateinit var cloud: PipelineHarnessTest.FakeSinks

  @Before fun startCloud() {
    cloud = PipelineHarnessTest.FakeSinks()
  }

  @After fun stopCloud() { cloud.stop() }

  /**
   * A cloud provider that is ready to use (an OpenAI-compatible one needs no key, so it always counted as "has a key")
   * and points at the fake cloud; only [engine] changes.
   */
  private fun withKey(engine: AiEngine) =
    AppSettings(engine = engine, provider = ProviderType.CUSTOM, providerBaseUrl = cloud.base + "/v1", providerModel = "m", rulePrefilter = false)

  private fun cloudCalls(engine: AiEngine, call: suspend (AppSettings) -> Unit): Int = runBlocking {
    val before = cloud.requests.size
    runCatching { call(withKey(engine)) }
    cloud.requests.size - before
  }

  @Test fun storedCloudKeyIsOnlyUsedWithTheCloudEngine() {
    val pipelineA: suspend (AppSettings) -> Unit = { s ->
      PipelineA.process(listOf(PipelineA.PipelineMessage("1", "Treffen morgen um 15 Uhr")), settings = s, secrets = app.secrets, context = null)
    }
    val pipelineB: suspend (AppSettings) -> Unit = { s ->
      val ev = { id: String -> PipelineB.CandidateEvent(id, "Treffen", 1_000_000L, 4_600_000L) }
      PipelineB.process(listOf(ev("1"), ev("2")), settings = s, secrets = app.secrets, context = null)
    }
    val duplicateAi: suspend (AppSettings) -> Unit = { s ->
      val a = Detection(Kind.EVENT, title = "Treffen", start = "2026-11-21T15:00")
      DuplicateJudge.askAi(app, s, app.secrets, a, a.copy(start = "2026-11-21T17:00"), "Doch erst um 17 Uhr")
    }
    val recheck: suspend (AppSettings) -> Unit = { s ->
      app.analyzer.analyzeForcedAi(Captured(SourceType.NOTIFICATION, "com.example", "Chat", "Anna", "Treffen morgen um 15 Uhr"), s, now)
    }
    mapOf("pipeline A" to pipelineA, "pipeline B" to pipelineB, "duplicate AI" to duplicateAi, "check with AI" to recheck).forEach { (name, call) ->
      for (engine in listOf(AiEngine.RULES, AiEngine.ON_DEVICE)) {
        assertEquals("$name with $engine must not call the cloud", 0, cloudCalls(engine, call))
      }
      assertTrue("$name with CLOUD uses the key", cloudCalls(AiEngine.CLOUD, call) > 0)
    }
  }

  @Test fun cloudAllowedFollowsTheEngine() {
    assertTrue(AppSettings(engine = AiEngine.CLOUD).cloudAllowed)
    AiEngine.entries.filter { it != AiEngine.CLOUD }.forEach { assertFalse(it.name, AppSettings(engine = it).cloudAllowed) }
  }

  // ---------------------------------------------------------------- diagnostic log follows its saved setting

  @Test fun diagnosticLogFollowsTheSetting() = runBlocking {
    val logger = de.juliandigitalproducts.notitocal.diagnostics.DiagnosticLogger
    suspend fun settle(expected: Boolean) {
      // The app collects the setting on the main thread, which runBlocking holds: let its queue run.
      repeat(100) {
        if (logger.enabled == expected) return
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        kotlinx.coroutines.delay(20)
      }
    }
    // The logger is shared by all tests: first "on" through this app's setting, so the switch to "off" is a real change.
    app.settings.update { it.copy(diagnosticLoggingEnabled = true) }
    settle(true)
    app.settings.update { it.copy(diagnosticLoggingEnabled = false) }
    settle(false)
    logger.log("Test", "must not be kept")
    assertTrue(
      "enabled=${logger.enabled} current=${app.settings.current.diagnosticLoggingEnabled} state=${app.settings.state.value.diagnosticLoggingEnabled}",
      logger.recentEvents().none { it.message.contains("must not be kept") },
    )
    app.settings.update { it.copy(diagnosticLoggingEnabled = true) }
    settle(true)
    logger.log("Test", "kept")
    assertTrue(logger.recentEvents().any { it.message == "kept" })
  }
}
