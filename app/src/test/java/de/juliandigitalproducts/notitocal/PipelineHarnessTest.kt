// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.data.ActionMode
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.LocationRecognitionMode
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.model.Why
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.File
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Harness: every message of [MessageSamples] through the whole local pipeline – filters (preCheck), Analyzer with
 * its rule pre-filter, model call, rule engine, decide, duplicate check and the target writers – with a fixed clock
 * (Fri 20.11.2026 10:00 Europe/Berlin) and fake sinks on 127.0.0.1 instead of real services:
 * - own server (`api.php`): `complete` is the model (answers 400, so the rules take over as with a failing model;
 *   every call is counted), `item` stores the entry for the ICS feed,
 * - CalDAV collection (PUT `<uid>.ics`),
 * - webhook.
 * Run: `./gradlew :app:pipelineHarness` (report: app/build/reports/pipeline-harness.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NotiToCalApp::class)
class PipelineHarnessTest {
  private val app get() = ApplicationProvider.getApplicationContext<NotiToCalApp>()
  private val savedZone = TimeZone.getDefault()
  private lateinit var sinks: FakeSinks

  data class Req(val method: String, val path: String, val query: String, val body: String)

  /**
   * Records every request; the model endpoint fails on purpose. A tiny HTTP/1.1 server on a plain socket (the unit
   * tests compile against android.jar, which has no com.sun.net.httpserver).
   */
  class FakeSinks {
    val requests = CopyOnWriteArrayList<Req>()
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
      thread(isDaemon = true, name = "fake-sinks") {
        while (!socket.isClosed) {
          val client = runCatching { socket.accept() }.getOrNull() ?: break
          runCatching { client.use { handle(it) } }
        }
      }
    }

    private fun handle(client: Socket) {
      val input = BufferedInputStream(client.getInputStream())
      fun line(): String {
        val b = StringBuilder()
        while (true) {
          val c = input.read()
          if (c == -1 || c == '\n'.code) break
          if (c != '\r'.code) b.append(c.toChar())
        }
        return b.toString()
      }
      val (method, target) = line().split(' ').let { it[0] to it[1] }
      val headers = generateSequence { line().takeIf { it.isNotEmpty() } }
        .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
      val bytes = ByteArrayOutputStream()
      if (headers["transfer-encoding"]?.contains("chunked") == true) {
        while (true) {
          val size = line().substringBefore(';').trim().toInt(16)
          if (size == 0) { line(); break }
          bytes.write(input.readNBytes(size)); line()
        }
      } else {
        bytes.write(input.readNBytes(headers["content-length"]?.toInt() ?: 0))
      }
      val req = Req(method, target.substringBefore('?'), target.substringAfter('?', ""), bytes.toString(Charsets.UTF_8.name()))
      requests += req
      val (code, answer) = when {
        req.query == "action=complete" -> 400 to """{"error":"no model in the harness"}"""
        req.query == "action=item" -> 200 to """{"ok":true,"id":${requests.count { it.query == "action=item" }}}"""
        req.method == "PUT" -> 201 to ""
        else -> 200 to "ok"
      }
      val body = answer.toByteArray()
      val out = client.getOutputStream()
      out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
      out.write(body)
      out.flush()
    }

    fun stop() = socket.close()
    fun modelCalls() = requests.count { it.query == "action=complete" }
    fun deliveries() = requests.filter { it.query != "action=complete" }
  }

  private val postedAt = MessageSamples.NOW.atZone(MessageSamples.ZONE).toInstant().toEpochMilli()

  @Before fun setUp() {
    runBlocking {
      TimeZone.setDefault(TimeZone.getTimeZone(MessageSamples.ZONE))
      sinks = FakeSinks()
      app.pipeline.clock = Clock.fixed(MessageSamples.NOW.atZone(MessageSamples.ZONE).toInstant(), MessageSamples.ZONE)
      app.log.clear()
      // From the defaults, so settings left by other test classes cannot change the result.
      app.settings.update {
        AppSettings().copy(
          onboardingDone = true,
          engine = AiEngine.SELF_HOSTED,
          selfHostedUrl = sinks.base + "/server/",
          fallbackToRules = true,
          locationRecognitionMode = LocationRecognitionMode.THOROUGH,
          locationEnrichmentEnabled = false,
          eventTargets = setOf(TargetType.CALDAV, TargetType.SELF_HOSTED, TargetType.WEBHOOK),
          taskTargets = setOf(TargetType.WEBHOOK),
          caldavEventUrl = sinks.base + "/caldav/events/",
          caldavUser = "harness",
          webhookUrl = sinks.base + "/hook",
          webhookBodyTemplate = "",
          eventMode = ActionMode.AUTO,
          taskMode = ActionMode.ASK,
          autoAddConfidence = 0.0,
          sendSourceToTargets = false,
          quietHours = false,
          unfilteredTargets = emptySet(),
        )
      }
    }
  }

  @After fun tearDown() {
    sinks.stop()
    app.pipeline.clock = Clock.systemDefaultZone()
    TimeZone.setDefault(savedZone)
  }

  private fun send(s: MessageSamples.Sample, pkg: String = "harness.app${s.id}", sender: String = "Absender${s.id}") = runBlocking {
    app.pipeline.process(Captured(SourceType.NOTIFICATION, pkg, s.source, sender, s.text, postedAt = postedAt))
  }

  private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject
  private fun icsUtc(local: String): String =
    LocalDateTime.parse(local).atZone(MessageSamples.ZONE).withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))

  data class Row(val s: MessageSamples.Sample, val modelCalls: Int, val decision: String, val start: String?, val end: String?, val title: String, val targets: String, val problems: List<String>)

  private fun check(s: MessageSamples.Sample): Row {
    val callsBefore = sinks.modelCalls()
    val sentBefore = sinks.deliveries().size
    val result = send(s)
    val calls = sinks.modelCalls() - callsBefore
    val delivered = sinks.deliveries().drop(sentBefore)
    val d = result?.detection
    val problems = mutableListOf<String>()
    val eventDelivered = d?.kind == Kind.EVENT && result.decision.name == "AUTO"
    if (s.event) {
      if (calls == 0) problems += "pre-filter blocked it (no model call)"
      if (!eventDelivered) problems += "no event (${d?.why})"
      else {
        if (d.start != s.start) problems += "start ${d.start}"
        if (s.end != null && d.end != s.end) problems += "end ${d.end}"
        if (d.allDay != s.allDay) problems += "allDay"
        if (s.title != null && !d.title.contains(s.title, ignoreCase = true)) problems += "title '${d.title}'"
        val caldav = delivered.filter { it.method == "PUT" && it.path.startsWith("/caldav/events/") }
        val item = delivered.filter { it.query == "action=item" }
        val hook = delivered.filter { it.path == "/hook" }
        if (caldav.size != 1 || item.size != 1 || hook.size != 1) problems += "targets caldav=${caldav.size} server=${item.size} webhook=${hook.size}"
        caldav.firstOrNull()?.let { put ->
          val dtstart = if (s.allDay) "DTSTART;VALUE=DATE:${s.start!!.replace("-", "")}" else "DTSTART:${icsUtc(s.start!!)}"
          if (!put.body.contains(dtstart)) problems += "CalDAV without $dtstart"
        }
        for (r in item + hook) {
          val o = json(r.body)
          if (o["start"]?.jsonPrimitive?.content != s.start) problems += "${r.path} start"
          if (o["timezone"]?.jsonPrimitive?.content != MessageSamples.ZONE.id) problems += "${r.path} timezone"
        }
      }
    } else {
      if (eventDelivered) problems += "false event"
      if (s.preBlock && calls > 0) problems += "pre-filter let noise through to the model"
      if (s.preBlock && d != null && d.kind != Kind.NONE) problems += "kind ${d.kind}"
      if (s.preBlock && delivered.isNotEmpty()) problems += "delivered to ${delivered.map { it.path }}"
    }
    // "Send message text to webhook and server" is off: neither the message nor the sender leave with the entry.
    for (r in delivered.filter { it.query == "action=item" || it.path == "/hook" }) {
      if (r.body.contains(s.text)) problems += "${r.path} contains the message text"
      val src = json(r.body)["source"]?.jsonObject
      if (src?.containsKey("text") == true || src?.containsKey("sender") == true) problems += "${r.path} source.text/sender"
      // The rules name the sender in the title ("Treffen (Anna)"): with the switch off not even there (third review).
      val ics = json(r.body)["ics"]?.jsonPrimitive?.content.orEmpty().replace("\r\n ", "")
      if (r.body.contains("Absender${s.id}") || ics.contains("Absender${s.id}")) problems += "${r.path} contains the sender"
    }
    return Row(
      s, calls, result?.decision?.name ?: "filtered", d?.start ?: d?.due, d?.end, d?.title.orEmpty(),
      delivered.joinToString(" ") { r ->
        when {
          r.query == "action=item" -> "Server"
          r.method == "PUT" -> "CalDAV"
          r.path == "/hook" -> "Webhook"
          else -> r.path
        }
      },
      if (s.aiPath) emptyList() else problems,
    )
  }

  @Test fun allSamplesThroughTheWholePipeline() {
    val rows = MessageSamples.all.map(::check)
    val md = buildString {
      appendLine("| # | Quelle | Nachricht | Erwartet | Modell-Aufrufe | Entscheidung | Start | Ende | Titel | Ziele | OK |")
      appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
      rows.forEach { r ->
        val exp = if (r.s.event) "Termin ${r.s.start}${r.s.end?.let { " – $it" } ?: ""}" else "kein Termin"
        val ok = when {
          r.s.aiPath -> "⚠️ KI-Fall (nicht geprüft)"
          r.problems.isEmpty() -> "✅"
          else -> "❌ " + r.problems.joinToString(", ")
        }
        appendLine("| ${r.s.id} | ${r.s.source} | ${r.s.text.replace("|", "\\|")} | $exp | ${r.modelCalls} | ${r.decision} | ${r.start ?: ""} | ${r.end ?: ""} | ${r.title.replace("|", "\\|")} | ${r.targets} | $ok |")
      }
      appendLine()
      appendLine("Korrekt: ${rows.count { it.problems.isEmpty() && !it.s.aiPath }}/${rows.count { !it.s.aiPath }} (ohne KI-Fälle) · feste Uhr ${MessageSamples.NOW} ${MessageSamples.ZONE}")
    }
    File("build/reports").mkdirs()
    File("build/reports/pipeline-harness.md").writeText(md)
    println(md)
    val failed = rows.filter { it.problems.isNotEmpty() }
    assertTrue("Failed samples:\n" + failed.joinToString("\n") { "#${it.s.id} ${it.problems}: ${it.s.text}" }, failed.isEmpty())
  }

  @Test fun duplicateCheckStopsTheSameAppointment() {
    val first = MessageSamples.base.first { it.id == 1 }
    send(first)
    val afterFirst = sinks.deliveries().size
    assertEquals(3, afterFirst)
    // Same text from another app (SMS + WhatsApp): filtered before the analysis.
    assertNull(send(first, pkg = "harness.other", sender = "Absender1"))
    // Same appointment in other words from the same person: analysed, but recognised as a duplicate.
    val again = send(first.copy(text = "Also morgen um 15 Uhr Treffen im Café Lotte, ich freu mich!"))!!
    assertEquals(Why.DUPLICATE.name, again.detection.why)
    assertEquals(afterFirst, sinks.deliveries().size)
    assertEquals(1, app.log.allEntries().count { it.detection.kind == Kind.EVENT && it.status == Status.ADDED })
  }

  @Test fun messageTextOnlyLeavesWithTheSwitchOn() = runBlocking {
    val s = MessageSamples.base.first { it.id == 1 }
    app.settings.update { it.copy(sendSourceToTargets = true) }
    send(s)
    val bodies = sinks.deliveries().filter { it.query == "action=item" || it.path == "/hook" }.map { it.body }
    assertEquals(2, bodies.size)
    assertTrue(bodies.all { it.contains(s.text) && json(it)["source"]!!.jsonObject["sender"]!!.jsonPrimitive.content == "Absender1" })
  }

  @Test fun webhookTemplateWithTextFieldsStaysEmptyWhenOff() = runBlocking {
    val s = MessageSamples.base.first { it.id == 2 }
    app.settings.update { it.copy(webhookBodyTemplate = """{"t":"{{title}}","d":"{{description}}","m":"{{text}}","s":"{{sender}}","j":{{json}}}""") }
    send(s)
    val hook = sinks.deliveries().single { it.path == "/hook" }
    val o = json(hook.body)
    assertFalse(hook.body.contains(s.text))
    assertEquals("", o["m"]!!.jsonPrimitive.content)
    assertEquals("", o["s"]!!.jsonPrimitive.content)
    assertEquals("", o["d"]!!.jsonPrimitive.content)
    assertTrue(o["t"]!!.jsonPrimitive.content.contains("Termin", ignoreCase = true))
    assertEquals(LocalDate.of(2026, 11, 25).toString(), o["j"]!!.jsonObject["start"]!!.jsonPrimitive.content.substring(0, 10))
  }

  /** "Keep message text" off (third review): neither the log nor the calendar entry (CalDAV) keep the message. */
  @Test fun messageTextIsNotKeptWhenOff() = runBlocking {
    val s = MessageSamples.base.first { it.id == 1 }
    val quote = "treffen wir uns morgen um 15 Uhr"
    app.settings.update { it.copy(storeMessageText = false) }
    val r = send(s)!!
    val entry = app.log.get(r.logId)!!
    assertEquals("", entry.text)
    assertEquals("", entry.detection.description)
    val put = sinks.deliveries().single { it.method == "PUT" }
    assertFalse(put.body, put.body.replace("\r\n ", "").contains(quote))
    assertTrue(sinks.deliveries().none { it.body.contains(quote) })
    // A message shared into the app is not kept either; a text typed in to try the app is.
    val shared = app.pipeline.process(Captured(SourceType.SHARED_TEXT, "harness.share", "Share", "", "Zahnarzt am 25.11.2026 um 08:30 Uhr"), userInitiated = true)!!
    assertEquals("", app.log.get(shared.logId)!!.text)
    val typed = app.pipeline.process(Captured(SourceType.MANUAL, "", "Test", "", "Friseur am 24.11.2026 um 15:30 Uhr"), userInitiated = true)!!
    assertEquals("Friseur am 24.11.2026 um 15:30 Uhr", app.log.get(typed.logId)!!.text)
  }

  @Test fun messageTextIsKeptWhenOn() = runBlocking {
    val s = MessageSamples.base.first { it.id == 1 }
    app.settings.update { it.copy(storeMessageText = true) }
    val r = send(s)!!
    assertEquals(s.text, app.log.get(r.logId)!!.text)
    val put = sinks.deliveries().single { it.method == "PUT" }
    assertTrue(put.body, put.body.replace("\r\n ", "").contains("treffen wir uns morgen um 15 Uhr"))
  }

  /** The chosen style with the offline rules (here the failing model hands over to them), privacy switches on top. */
  @Test fun rulesEntriesFollowTheChosenStyle() = runBlocking {
    val s = MessageSamples.base.first { it.id == 1 }
    app.settings.update { it.copy(eventTemplateId = "event_formal") }
    val d = send(s)!!.detection
    assertTrue(d.title, d.title.startsWith("Appointment: ") && d.title.contains("Treffen"))
    assertTrue(d.description, d.description.startsWith("Subject: ") && d.description.contains("Notes: ") && d.description.contains(s.text))
    val put = sinks.deliveries().single { it.method == "PUT" }.body.replace("\r\n ", "")
    assertTrue(put, put.contains("SUMMARY:Appointment: Treffen"))
    // Webhook and server with the switch off: the style stays, message and sender go.
    for (r in sinks.deliveries().filter { it.query == "action=item" || it.path == "/hook" }) {
      val o = json(r.body)
      assertTrue(r.body, o["title"]!!.jsonPrimitive.content.startsWith("Appointment: "))
      assertTrue(r.body, o["description"]!!.jsonPrimitive.content.startsWith("Subject: "))
      assertFalse(r.body, r.body.contains(s.text) || r.body.contains("Absender1"))
    }
  }

  @Test fun rulesStyleWithoutKeptMessage() = runBlocking {
    val s = MessageSamples.base.first { it.id == 1 }
    app.settings.update { it.copy(eventTemplateId = "event_pin", storeMessageText = false) }
    val r = send(s)!!
    val d = app.log.get(r.logId)!!.detection
    assertTrue(d.title, d.title.startsWith("📌 "))
    assertTrue(d.description, d.description.startsWith("📍 ") && !d.description.contains("treffen wir uns"))
    val put = sinks.deliveries().single { it.method == "PUT" }.body.replace("\r\n ", "")
    assertTrue(put, put.contains("SUMMARY:📌 "))
    assertFalse(put, put.contains("treffen wir uns morgen um 15 Uhr"))
  }

  private fun broadcasts() = org.robolectric.Shadows.shadowOf(app as android.app.Application).broadcastIntents
    .filter { it.action == de.juliandigitalproducts.notitocal.output.Targets.ACTION_DETECTED }

  private fun extras(i: android.content.Intent): Map<String, String> =
    i.extras!!.keySet().associateWith { k -> @Suppress("DEPRECATION") i.extras!!.get(k).toString() }

  /** t55u: "Sender in title" off – no target gets the name in the title, whatever style; the rest follows its own switch. */
  @Test fun senderInTitleOffForEveryTarget() = runBlocking {
    val s = MessageSamples.base.first { it.id == 1 }
    app.settings.update {
      it.copy(senderInTitle = false, sendSourceToTargets = true, eventTemplateId = "event_formal", eventTargets = it.eventTargets + TargetType.AUTOMATION_BROADCAST)
    }
    val d = send(s)!!.detection
    assertTrue(d.title, d.title.startsWith("Appointment: Treffen") && !d.title.contains("Absender1"))
    assertFalse(d.description, d.description.lines().first().contains("Absender1"))
    val put = sinks.deliveries().single { it.method == "PUT" }.body.replace("\r\n ", "")
    assertTrue(put, put.contains("SUMMARY:Appointment: Treffen") && !put.lines().first { it.startsWith("SUMMARY") }.contains("Absender1"))
    for (r in sinks.deliveries().filter { it.query == "action=item" || it.path == "/hook" }) {
      val o = json(r.body)
      assertFalse(r.body, o["title"]!!.jsonPrimitive.content.contains("Absender1"))
      assertEquals(r.body, "Absender1", o["source"]!!.jsonObject["sender"]!!.jsonPrimitive.content) // "send message text" is on
    }
    val b = broadcasts().map { extras(it) }
    assertTrue(b.isNotEmpty())
    b.forEach { e -> assertFalse(e.toString(), e.getValue("title").contains("Absender1")); assertEquals("Absender1", e["sender"]) }
  }

  /** t55u: the automation broadcast has its own switch for sender and message (on by default, as before). */
  @Test fun broadcastFollowsItsOwnSwitch() = runBlocking {
    val s = MessageSamples.base.first { it.id == 1 }
    app.settings.update { it.copy(eventTargets = setOf(TargetType.AUTOMATION_BROADCAST), sendSourceToTargets = false) }
    assertTrue(AppSettings().broadcastIncludesSource)
    send(s)
    val on = broadcasts().map { extras(it) }
    assertTrue(on.isNotEmpty())
    on.forEach { e -> assertEquals("Absender1", e["sender"]); assertEquals(s.text, e["text"]) }
    app.log.clear()
    org.robolectric.Shadows.shadowOf(app as android.app.Application).clearBroadcastIntents()
    app.settings.update { it.copy(broadcastIncludesSource = false) }
    send(MessageSamples.base.first { it.id == 9 }, sender = "Absender1")
    val off = broadcasts().map { extras(it) }
    assertTrue(off.isNotEmpty())
    off.forEach { e ->
      assertFalse(e.toString(), "sender" in e || "text" in e)
      assertFalse(e.toString(), e.values.any { it.contains("Absender1") || it.contains("Jour fixe wurde verschoben auf") })
      assertTrue(e.toString(), e.getValue("title").isNotBlank())
    }
  }

  /**
   * t56u, point 3: "Keep message text" off + "Send message text" on – an entry added right away and one confirmed
   * later both reach webhook and server without the message (it is not kept), the sender goes along in both.
   */
  @Test fun sendMessageTextNeedsKeepForImmediateAndConfirmedEntries() = runBlocking {
    app.settings.update { it.copy(storeMessageText = false, sendSourceToTargets = true) }
    val first = MessageSamples.base.first { it.id == 1 }
    send(first)
    val immediate = sinks.deliveries().filter { it.query == "action=item" || it.path == "/hook" }
    assertTrue(immediate.isNotEmpty())
    app.settings.update { it.copy(eventMode = ActionMode.ASK) }
    val second = MessageSamples.base.first { it.id == 9 }
    val r = send(second)!!
    val before = sinks.deliveries().size
    app.pipeline.deliverEntry(app.log.get(r.logId)!!, fromUi = true)
    val confirmed = sinks.deliveries().drop(before).filter { it.query == "action=item" || it.path == "/hook" }
    assertTrue(confirmed.isNotEmpty())
    for ((req, sample) in immediate.map { it to first } + confirmed.map { it to second }) {
      val src = json(req.body)["source"]!!.jsonObject
      assertEquals(req.body, "Absender${sample.id}", src["sender"]!!.jsonPrimitive.content)
      assertEquals(req.body, "", src["text"]!!.jsonPrimitive.content)
      assertFalse(req.body, req.body.contains(sample.text))
    }
  }
}
