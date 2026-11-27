// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind

/** One visual and textual style for an event or a task. The instruction is added to the analysis prompt. */
data class StyleTemplate(
  val id: String,
  val kind: TemplateKind,
  val emoji: String,
  val accent: Long,
  val titleFormat: String,
  val descriptionFormat: String,
  val instruction: String,
  val sampleTitle: String,
  val sampleDescription: String,
  val sampleLocation: String?,
  val sampleWhen: String,
)

enum class TemplateKind { EVENT, TASK }

data class TemplatePreview(
  val title: String,
  val description: String,
  val location: String?,
  val whenLabel: String,
  val emoji: String,
  val accent: Long,
)

object StyleTemplates {
  const val DEFAULT_EVENT = "event_clean"
  const val DEFAULT_TASK = "task_check"

  val events: List<StyleTemplate> = listOf(
    template(
      "event_clean", TemplateKind.EVENT, "", 0xFF5C6BC0, "{title}", "{description}",
      "Style id event_clean: plain title without emoji or prefix, one short sentence as the description.",
      "Dentist", "Check-up with Dr Keller.", "Main Street 12", "Tue 12 May · 09:30",
    ),
    template(
      "event_pin", TemplateKind.EVENT, "📌", 0xFF00897B, "📌 {title}", "📍 {location}\n{description}",
      "Style id event_pin: start the title with 📌 and put the place on the first description line.",
      "Team sync", "Weekly planning.", "Room 4", "Mon 11 May · 10:00",
    ),
    template(
      "event_brief", TemplateKind.EVENT, "📅", 0xFFEF6C00, "📅 {title}", "• {description}",
      "Style id event_brief: start the title with 📅 and write the description as a single bullet.",
      "School play", "Arrive ten minutes early.", "Hall", "Fri 15 May · 18:00",
    ),
    template(
      "event_formal", TemplateKind.EVENT, "", 0xFF455A64, "{w_appointment}: {title}",
      "{w_subject}: {title}\n{w_where}: {location}\n{w_notes}: {description}",
      "Style id event_formal: prefix the title with the word for \"Appointment\" in the output language and a colon, and structure the description as Subject, Where and Notes (these labels in the output language).",
      "Budget review", "Bring the Q2 sheet.", "Office", "Wed 13 May · 14:00",
    ),
    template(
      "event_focus", TemplateKind.EVENT, "🗓️", 0xFF8E24AA, "🗓️ {title}", "{w_focus}\n{description}",
      "Style id event_focus: start the title with 🗓️ and begin the description with the words \"Focus block\" in the output language.",
      "Deep work", "No meetings in this block.", null, "Thu 14 May · 08:00",
    ),
  )

  val tasks: List<StyleTemplate> = listOf(
    template(
      "task_check", TemplateKind.TASK, "☐", 0xFF2E7D32, "☐ {title}", "{description}",
      "Style id task_check: start the task title with ☐ and keep the description to one sentence.",
      "Pay the electricity bill", "Due before Friday.", null, "Due Fri 15 May",
    ),
    template(
      "task_todo", TemplateKind.TASK, "", 0xFF1565C0, "TODO: {title}", "{w_next_step}: {description}",
      "Style id task_todo: prefix the title with TODO: and start the description with \"Next step:\" in the output language.",
      "Send the report", "Attach the signed PDF.", null, "Due Thu 14 May",
    ),
    template(
      "task_next", TemplateKind.TASK, "⚡", 0xFFC62828, "⚡ {title}", "{w_do_next}: {description}",
      "Style id task_next: start the title with ⚡ and start the description with \"Do next:\" in the output language.",
      "Call the school", "Ask about the trip.", null, "Due today",
    ),
    template(
      "task_note", TemplateKind.TASK, "📝", 0xFFF9A825, "📝 {title}", "{w_note}: {description}",
      "Style id task_note: start the title with 📝 and start the description with \"Note:\" in the output language.",
      "Buy milk", "Oat milk, two cartons.", null, "Due tomorrow",
    ),
    template(
      "task_plain", TemplateKind.TASK, "", 0xFF546E7A, "{title}", "{description}",
      "Style id task_plain: short title with no emoji and a one-line description.",
      "Renew the passport", "Check the expiry date first.", null, "Due 1 Jun",
    ),
  )

  fun event(id: String): StyleTemplate = events.firstOrNull { it.id == id } ?: events.first()
  fun task(id: String): StyleTemplate = tasks.firstOrNull { it.id == id } ?: tasks.first()

  fun promptBlock(eventId: String, taskId: String): String {
    val event = event(eventId)
    val task = task(taskId)
    return """
Style instruction:
- EVENT: ${event.instruction}
- TASK: ${task.instruction}
""".trim()
  }

  /**
   * Example of [template] in [language] (ISO code, e.g. "de"). The English samples in the template are the
   * fallback for languages without a translation in [StyleSamples].
   */
  fun preview(
    template: StyleTemplate,
    language: String = "en",
    customText: String = "",
    customDescription: String? = null,
    customLocation: String? = null,
    customWhen: String? = null,
  ): TemplatePreview {
    val sample = StyleSamples.sample(language, template.id)
    val title = customText.trim().ifBlank { sample?.title ?: template.sampleTitle }
    val description = customDescription?.trim()?.ifBlank { null }
      ?: sample?.description
      ?: template.sampleDescription
    val location = customLocation?.trim()?.ifBlank { null }
      ?: if (template.sampleLocation == null) null else sample?.location ?: template.sampleLocation
    val whenLabel = customWhen?.trim()?.ifBlank { null }
      ?: sample?.whenLabel
      ?: template.sampleWhen
    fun fill(pattern: String) = render(template, pattern, language, title, description, location, whenLabel)
    return TemplatePreview(
      title = fill(template.titleFormat),
      description = fill(template.descriptionFormat),
      location = location,
      whenLabel = whenLabel,
      emoji = template.emoji,
      accent = template.accent,
    )
  }

  /**
   * Fills [pattern] of [template]. Lines whose placeholder stayed empty (a "📍" without a place, "Notes:" without
   * notes) are left out, so an entry without a description gets no empty labels.
   */
  internal fun render(
    template: StyleTemplate, pattern: String, language: String, title: String, description: String, location: String?, whenLabel: String = "",
  ): String {
    var out = pattern
      .replace("{emoji}", template.emoji)
      .replace("{title}", title)
      .replace("{description}", description)
      .replace("{location}", location.orEmpty())
      .replace("{when}", whenLabel)
    StyleSamples.words(language).forEach { (key, word) -> out = out.replace("{$key}", word) }
    return out.lines()
      .filter { line ->
        val tr = line.trim()
        tr != "📍" && tr != "•" && !tr.endsWith(":") && tr.isNotEmpty()
      }
      .joinToString("\n")
      .trim()
  }

  /** [title] is nothing but a style's prefix ("TODO:", "Termin:"), e.g. after the sender was taken out of it. */
  fun isOnlyPrefix(title: String): Boolean {
    val t = title.trim()
    return (events + tasks).any { tpl ->
      val prefix = tpl.titleFormat.substringBefore("{title}")
      prefix.isNotBlank() && StyleSamples.supportedLanguages.any { lang ->
        var p = prefix
        StyleSamples.words(lang).forEach { (key, word) -> p = p.replace("{$key}", word) }
        p.trim().trimEnd(':') == t.trimEnd(':')
      }
    }
  }

  /** Entries the offline rules made (also an assembled conversation): they get the style here, a model gets it in its prompt. */
  fun ruleMade(d: Detection): Boolean =
    d.engine == "rules" || d.engine == de.juliandigitalproducts.notitocal.pipeline.Pipeline.SESSION_ENGINE

  /**
   * The chosen event/task style applied to an entry of the offline rules, with the same visible result the style asks a
   * model for (title prefix, description layout, place line). [withMessage] false: the message is left out of the
   * description (the rules use it as notes), only the style's own lines stay – "Keep message text" off.
   * Deterministic and applied once, when the entry is made.
   */
  fun applyToRuleEntry(d: Detection, eventId: String, taskId: String, language: String, withMessage: Boolean): Detection {
    if (!ruleMade(d)) return d
    val template = when (d.kind) {
      Kind.EVENT -> event(eventId)
      Kind.TASK -> task(taskId)
      else -> return d
    }
    val notes = if (withMessage) d.description else ""
    fun fill(pattern: String) = render(template, pattern, language, d.title, notes, d.location)
    return d.copy(
      title = fill(template.titleFormat).ifBlank { d.title },
      description = fill(template.descriptionFormat),
      messageFreeDescription = !withMessage,
    )
  }

  private fun template(
    id: String,
    kind: TemplateKind,
    emoji: String,
    accent: Long,
    titleFormat: String,
    descriptionFormat: String,
    instruction: String,
    sampleTitle: String,
    sampleDescription: String,
    sampleLocation: String?,
    sampleWhen: String,
  ) = StyleTemplate(id, kind, emoji, accent, titleFormat, descriptionFormat, instruction, sampleTitle, sampleDescription, sampleLocation, sampleWhen)
}
