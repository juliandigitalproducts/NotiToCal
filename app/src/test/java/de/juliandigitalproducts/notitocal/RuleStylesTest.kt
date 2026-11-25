package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.analysis.StyleTemplates
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.output.Privacy
import de.juliandigitalproducts.notitocal.output.SourceInfo
import de.juliandigitalproducts.notitocal.output.Targets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The 10 styles with the offline rules (third review, Julian: the rules follow the settings, also the style): the
 * same visible result the style asks a model for, deterministic, with the privacy switches on top.
 */
class RuleStylesTest {
  private val now = LocalDateTime.of(2026, 11, 20, 10, 0)
  private val eventText = "Treffen morgen um 15 Uhr im Café Lotte, passt das?"
  private val taskText = "Vergiss nicht morgen die Stromrechnung zu bezahlen"
  private val sender = "Anna Schmidt"
  private val event = RuleEngine.analyze(eventText, sender, now)
  private val task = RuleEngine.analyze(taskText, sender, now)

  private fun styled(d: Detection, id: String, withMessage: Boolean = true) =
    if (d.kind == Kind.TASK) StyleTemplates.applyToRuleEntry(d, StyleTemplates.DEFAULT_EVENT, id, "de", withMessage)
    else StyleTemplates.applyToRuleEntry(d, id, StyleTemplates.DEFAULT_TASK, "de", withMessage)

  /** Expected title and description per style (German words), from the rules' own title, place and notes. */
  private fun expected(id: String, d: Detection, notes: String): Pair<String, String> {
    val t = d.title
    val l = d.location.orEmpty()
    fun lines(vararg l: String) = l.filter { it.isNotBlank() && !it.trim().endsWith(":") && it.trim() != "📍" && it.trim() != "•" }.joinToString("\n")
    return when (id) {
      "event_clean" -> t to notes
      "event_pin" -> "📌 $t" to lines("📍 $l", notes)
      "event_brief" -> "📅 $t" to lines("• $notes")
      "event_formal" -> "Termin: $t" to lines("Betreff: $t", "Ort: $l", "Notizen: $notes")
      "event_focus" -> "🗓️ $t" to lines("Fokuszeit", notes)
      "task_check" -> "☐ $t" to notes
      "task_todo" -> "TODO: $t" to lines("Nächster Schritt: $notes")
      "task_next" -> "⚡ $t" to lines("Als Nächstes: $notes")
      "task_note" -> "📝 $t" to lines("Notiz: $notes")
      "task_plain" -> t to notes
      else -> error(id)
    }
  }

  private val all = StyleTemplates.events.map { it.id to event } + StyleTemplates.tasks.map { it.id to task }

  @Test fun rulesFollowEveryStyle() {
    assertEquals(Kind.EVENT, event.kind)
    assertEquals(Kind.TASK, task.kind)
    assertTrue("rules put the sender into the title: ${event.title}", event.title.contains("Anna"))
    all.forEach { (id, d) ->
      val out = styled(d, id)
      val (title, description) = expected(id, d, d.description)
      assertEquals("$id title", title, out.title)
      assertEquals("$id description", description, out.description)
      assertEquals("$id place", d.location, out.location)
      assertEquals("$id deterministic", out, styled(d, id))
    }
  }

  @Test fun keepMessageOffLeavesOnlyTheStyleLines() {
    all.forEach { (id, d) ->
      val out = styled(d, id, withMessage = false)
      val (title, description) = expected(id, d, "")
      assertEquals("$id title", title, out.title)
      assertEquals("$id description", description, out.description)
      assertFalse("$id message", out.description.contains(d.description))
      // Storage keeps exactly that (it is free of the message already).
      assertEquals(out, Privacy.forStorage(out, d.description, AppSettings(storeMessageText = false)))
    }
    assertEquals("Betreff: ${event.title}\nOrt: ${event.location}", styled(event, "event_formal", withMessage = false).description)
    assertEquals("📍 ${event.location}", styled(event, "event_pin", withMessage = false).description)
  }

  @Test fun privacySwitchesApplyOnTopOfEveryStyle() {
    val off = AppSettings(sendSourceToTargets = false, shareSenderWithAi = false)
    val template = """{"t":"{{title}}","d":"{{description}}","l":"{{location}}","j":{{json}}}"""
    all.forEach { (id, d) ->
      val text = if (d.kind == Kind.TASK) taskText else eventText
      val src = SourceInfo("WhatsApp", "com.whatsapp", sender, text, "NOTIFICATION")
      val out = styled(d, id) // "Keep message text" on: the message is the notes
      val sent = (Targets.payload(out, src, off).toString() + Targets.fillTemplate(template, out, src, off)).replace("\\r\\n ", "")
      assertFalse("$id sender in $sent", sent.contains("Anna"))
      assertFalse("$id message in $sent", sent.contains(text.take(20)))
      val shared = Privacy.shareable(out, src, withSource = false)
      // The style stays visible: prefix of the title and the style's own lines.
      val prefix = expected(id, d, "").first.substringBefore(d.title)
      assertTrue("$id prefix '$prefix' in '${shared.title}'", shared.title.startsWith(prefix))
      if (id == "event_pin") assertEquals("📍 ${d.location}", shared.description)
      if (id == "event_formal") assertTrue(shared.description, shared.description.startsWith("Betreff: ") && shared.description.contains("Ort: ${d.location}"))
      assertFalse("$id AI title", Privacy.forAi(out, sender, off).title.contains("Anna"))
      // Switches on: everything goes.
      val on = AppSettings(sendSourceToTargets = true)
      assertTrue("$id on", Targets.payload(out, src, on).toString().contains("Anna"))
    }
  }

  @Test fun modelEntriesAreNotStyledTwice() {
    val model = Detection(Kind.EVENT, title = "📌 Treffen", description = "📍 Café", engine = "OpenAI")
    assertEquals(model, StyleTemplates.applyToRuleEntry(model, "event_pin", "task_check", "de", withMessage = true))
  }

  /** t55u: "Sender in title" off, for every style with the rules and for titles a model wrote. */
  @Test fun senderInTitleOffForEveryStyle() {
    val off = AppSettings(senderInTitle = false)
    all.forEach { (id, d) ->
      val out = StyleTemplates.applyToRuleEntry(
        Privacy.titleForTargets(d, sender, off),
        if (d.kind == Kind.EVENT) id else StyleTemplates.DEFAULT_EVENT, if (d.kind == Kind.TASK) id else StyleTemplates.DEFAULT_TASK, "de", true,
      )
      assertFalse("$id ${out.title}", out.title.contains("Anna"))
      assertTrue("$id ${out.title}", out.title.startsWith(expected(id, d, "").first.substringBefore(d.title)))
      assertTrue("$id ${out.title}", out.title.length > 4)
      // Again at delivery: nothing left to take out.
      assertEquals(out, Privacy.titleForTargets(out, sender, off))
    }
    fun ai(title: String) = Privacy.titleForTargets(Detection(Kind.EVENT, title = title, engine = "OpenAI"), sender, off).title
    assertEquals("Abendessen", ai("Abendessen mit Anna"))
    assertEquals("📌 Abendessen", ai("📌 Abendessen (Anna Schmidt)"))
    // A title that was only the name (behind a style prefix) gets the neutral word in the app language.
    listOf("Termin: Anna" to "Termin: ", "📌 Anna" to "📌 ").forEach { (t, prefix) ->
      val out = ai(t)
      assertTrue(out, out.startsWith(prefix) && out.length > prefix.length + 2 && !out.contains("Anna"))
    }
    // On (default): unchanged.
    assertEquals("Abendessen mit Anna", Privacy.titleForTargets(Detection(Kind.EVENT, title = "Abendessen mit Anna"), sender, AppSettings()).title)
  }
}
