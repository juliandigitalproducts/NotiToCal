package de.juliandigitalproducts.notitocal.analysis

import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import java.time.format.DateTimeFormatter

/**
 * Combines a small on-device model with the offline rules. Measured with Qwen 2.5 0.5B/1.5B: the models recognise
 * appointment vs. task and write good titles, but miscalculate weekdays ("Friday" → Thursday) and invent places.
 * So the rules provide date, time and place checks, the model the kind and wording.
 */
object Hybrid {
  private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
  private val PREPOSITION_END = rx("""\s+(um|am|ab|bis|at|on|by|à|le|a las|alle|às|o)\s*$""")

  /** Removes dates and times the model wrote into the title ("Meeting morgen um 10" → "Meeting"). */
  fun cleanTitle(title: String, now: java.time.LocalDateTime): String {
    var t = title.trim()
    DateParser.parse(t, now).spans.sortedByDescending { it.first }.forEach { r ->
      if (r.first >= 0 && r.last < t.length) t = t.removeRange(r)
    }
    t = t.replace(Regex("""\s{2,}"""), " ").trim(' ', ',', '.', '!', '?', '-')
    t = PREPOSITION_END.replace(t, "").trim()
    return if (t.length < 3) title.trim() else t
  }

  /** Rules alone after the model declined never reach the usual automatic threshold. */
  const val REFUSED_MAX_CONFIDENCE = 0.6

  fun merge(
    llm: Detection?,
    rules: Detection,
    text: String,
    now: java.time.LocalDateTime,
    defaultMinutes: Int,
    refused: Boolean = false,
  ): Detection {
    if (llm == null && refused && rules.kind != Kind.NONE) {
      // The model refused (often on long screenshot texts): the rules decide, but only as a suggestion.
      return rules.copy(
        confidence = minOf(rules.confidence, REFUSED_MAX_CONFIDENCE),
        reason = listOf("model refused, rules used", rules.reason).filter { it.isNotBlank() }.joinToString(" · "),
        engine = "on-device + rules",
      )
    }
    if (llm == null) return rules.copy(reason = "model answer unreadable, rules used", engine = "on-device + rules")
    val w = DateParser.parse(TextNormalizer.normalize(text), now)
    val rulesFound = rules.kind == Kind.EVENT || rules.kind == Kind.TASK
    val modelFound = llm.kind == Kind.EVENT || llm.kind == Kind.TASK
    val signals = RuleEngine.scores(TextNormalizer.normalize(text))
    val hasForeignScript = text.any {
      val script = Character.UnicodeScript.of(it.code)
      script in setOf(
        Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW,
        Character.UnicodeScript.GREEK, Character.UnicodeScript.THAI, Character.UnicodeScript.DEVANAGARI
      )
    }

    val kind = when {
      // Cancellations and ads look like appointments to small models; the rules know better.
      signals.cancelled -> Kind.NONE
      signals.noise >= 0.3 && !rulesFound -> Kind.NONE
      // Nothing in the text points to an appointment or a task: small models then tend to invent one.
      // However, if the model found an event/task with good confidence (e.g. foreign languages where rule keywords don't match) or a location, let AI take over.
      modelFound && !rulesFound && !w.hasAnything && signals.event < 0.2 && signals.task < 0.2 && llm.confidence < 0.70 && !hasForeignScript && llm.location.isNullOrBlank() -> Kind.NONE
      // The model missed it, the rules did not.
      !modelFound && rulesFound -> rules.kind
      else -> llm.kind
    }
    if (kind != Kind.EVENT && kind != Kind.TASK) {
      return llm.copy(kind = kind, engine = "on-device + rules", reason = listOf(llm.reason, "no event or task").filter { it.isNotBlank() }.joinToString(" · "))
    }

    // Dates from the parser whenever the text contains one; the model's own date only as a fallback.
    val parsedStart = w.date?.let { d -> w.time?.let { d.atTime(it).format(ISO) } ?: d.toString() }
    val start: String?
    val end: String?
    val due: String?
    if (kind == Kind.EVENT) {
      start = parsedStart ?: llm.start ?: llm.due
      end = when {
        w.endDate != null -> w.endTime?.let { w.endDate.atTime(it).format(ISO) } ?: w.endDate.toString()
        parsedStart != null && w.date != null && w.time != null -> {
          val s = w.date.atTime(w.time)
          (w.endTime?.let { w.date.atTime(it).let { e -> if (e.isAfter(s)) e else e.plusDays(1) } } ?: s.plusMinutes((w.durationMinutes ?: defaultMinutes).toLong())).format(ISO)
        }
        else -> llm.end
      }
      due = null
    } else {
      start = null; end = null
      due = parsedStart ?: llm.due ?: llm.start
    }

    // Places only when they really appear in the message.
    val normalizedText = text.lowercase()
    val location = llm.location?.takeIf { loc -> loc.isNotBlank() && loc.lowercase().split(' ', ',').filter { it.length > 2 }.any { normalizedText.contains(it) } }
      ?: rules.location

    val agree = rulesFound && rules.kind == kind
    val confidence = when {
      agree -> maxOf(llm.confidence.coerceAtMost(0.9), rules.confidence)
      rulesFound -> minOf(llm.confidence, 0.6)
      else -> minOf(llm.confidence, 0.7)
    }
    val merged = llm.copy(
      kind = kind,
      title = cleanTitle(llm.title, now).ifBlank { rules.title },
      description = llm.description.ifBlank { text },
      start = start,
      end = end,
      allDay = kind == Kind.EVENT && start != null && 'T' !in start,
      due = due,
      location = location,
      confidence = confidence,
      engine = "on-device + rules",
      reason = listOfNotNull(llm.reason.takeIf { it.isNotBlank() }, if (agree) "rules agree" else "rules: ${rules.kind.name.lowercase()}").joinToString(" · "),
    )
    return RuleEngine.sanitizeDetection(merged, text)
  }
}
