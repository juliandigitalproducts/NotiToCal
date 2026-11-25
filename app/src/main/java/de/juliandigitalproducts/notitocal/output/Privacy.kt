package de.juliandigitalproducts.notitocal.output

import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.analysis.StyleTemplates
import de.juliandigitalproducts.notitocal.analysis.TextLang
import de.juliandigitalproducts.notitocal.analysis.TextNormalizer
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind

/**
 * The privacy switches applied to an entry, in one place (third review, 20.11.2026). Every switch does what its label
 * says, whatever style or engine created the entry:
 * - "Keep message text" off: the message is neither stored nor written into the entry (description), so it does not
 *   reach the device calendar, CalDAV or any other target either ([forStorage]).
 * - "Send message text to webhook and server" off: webhook and own server get the entry without the message and
 *   without the sender – also not inside the title ("Treffen (Anna)"), the description, the place or the ICS ([shareable]).
 * - "Send sender names to the AI" off: titles and places that go into a model request lose the sender name ([forAi]).
 */
object Privacy {

  /**
   * Names standing for the sender: the name the analysis used ([Detection.senderName]), the notification sender
   * without the chat part ("Anna @ Familie" → "Anna"), and of a full name also the first name ("Abendessen mit Anna").
   */
  fun senderNames(d: Detection, sender: String): List<String> {
    val full = listOfNotNull(d.senderName, sender.substringBefore(" @ "))
      .map { it.trim().trim(',', '.', '!', ':') }
      .filter { it.length >= 2 }
    val first = full.mapNotNull { n -> n.split(Regex("""\s+""")).takeIf { it.size > 1 }?.first()?.takeIf { it.length >= 3 } }
    return (full + first).distinctBy { it.lowercase() }.sortedByDescending { it.length }
  }

  private const val L = """[\p{L}\p{N}]"""
  private const val LINK = """mit|with|von|from|bei|at|chez|avec|con|com|z|für|for|und|and|&"""

  /** [value] without [names]: "(Anna)", "mit Anna", "Anna:" and the bare name are removed, the rest is tidied up. */
  fun redactSender(value: String, names: List<String>): String {
    if (value.isBlank() || names.isEmpty()) return value
    var out = value
    names.forEach { n ->
      val q = Regex.escape(n)
      out = Regex("""\s*[(\[]\s*$q\s*[)\]]""", RegexOption.IGNORE_CASE).replace(out, "")
      out = Regex("""\s+(?:$LINK)\s+$q(?!$L)""", RegexOption.IGNORE_CASE).replace(out, "")
      out = Regex("""(?<!$L)$q(?!$L)\s*[:,–-]?\s*""", RegexOption.IGNORE_CASE).replace(out, " ")
    }
    return out.lines().joinToString("\n") { line ->
      line.replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\s+([,.!?;:)])"""), "$1").replace("()", "")
        .trim().trim(',', '–', '-').trim()
    }.trim()
  }

  /** [d] without the sender in title, description and place. A title that was only the name becomes a neutral one. */
  fun withoutSender(d: Detection, names: List<String>): Detection {
    if (names.isEmpty()) return d
    val title = redactSender(d.title, names).ifBlank { neutralTitle(d.kind) }
    val location = d.location?.let { redactSender(it, names) }?.takeIf { it.isNotBlank() }
    return d.copy(title = title, description = redactSender(d.description, names), location = location, senderName = null)
  }

  private fun neutralTitle(kind: Kind): String {
    val de = TextLang.app() == TextLang.DE
    return when (kind) {
      Kind.TASK -> if (de) "Aufgabe" else "Task"
      else -> if (de) "Termin" else "Appointment"
    }
  }

  /**
   * The description without the message, with the style's own lines kept (e.g. "📍 Café", "Subject: …").
   * - Offline rules / conversation: they use the (cleaned) message as notes. Without a copy of it that can be found
   *   (or without the message at hand) the whole description goes, unless it was made without the message.
   * - A model: every copy of the message is removed from its own text.
   */
  fun descriptionWithoutMessage(d: Detection, message: String): String = when {
    d.description.isBlank() || d.messageFreeDescription -> d.description
    StyleTemplates.ruleMade(d) -> {
      val cleaned = RuleEngine.stripDebugAndSystemText(TextNormalizer.normalize(message)).trim()
      listOf(message, cleaned).firstOrNull { it.isNotBlank() && carriesMessage(d.description, it) }
        ?.let { removeMessage(d.description, it) }
        ?.takeIf { rest -> !carriesMessage(rest, cleaned.ifBlank { message }) }
        .orEmpty()
    }
    message.isBlank() -> d.description
    else -> withoutQuotes(removeMessage(d.description, message), message)
  }

  private fun squash(t: String) = t.lowercase().replace(Regex("""\s+"""), " ").trim()

  internal fun carriesMessage(description: String, message: String): Boolean {
    val desc = squash(description)
    val msg = squash(message)
    return desc.isNotEmpty() && msg.isNotEmpty() && (desc.contains(msg) || msg.contains(desc))
  }

  private fun removeMessage(description: String, message: String): String {
    if (!carriesMessage(description, message)) return description
    if (squash(message).contains(squash(description))) return ""
    val words = message.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    val pattern = Regex(words.joinToString("""\s+""") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    val rest = pattern.replace(description, "")
    // Labels left without a value ("Notizen:", "• ") go too.
    return rest.lines().map { it.trim() }
      .filter { it.isNotEmpty() && !it.endsWith(":") && it !in setOf("•", "-", "📍") }
      .joinToString("\n")
  }

  private val WORD = Regex("""[\p{L}\p{N}]+""")
  private fun words(t: String) = WORD.findAll(t.lowercase()).map { it.value }.toList()
  private val QUOTED = Regex("""„([^“”"]+)[“”"]|“([^”]+)”|"([^"]+)"|«\s*([^»]+?)\s*»|‚([^‘’']+)[‘’']""")

  /**
   * Parts of the message a model quoted in its own description ("She writes: „treffen wir uns morgen …“"): a quoted
   * passage of 3+ words taken from the message goes, and so does every sentence sharing a run of [QUOTE_WORDS] words
   * (or the whole message, if shorter) with it. Paraphrases ("Meeting with Anna at the café") stay.
   */
  internal fun withoutQuotes(description: String, message: String): String {
    val msg = words(message)
    if (description.isBlank() || msg.size < 3) return description
    val msgText = " " + msg.joinToString(" ") + " "
    fun quotes(ws: List<String>) = ws.isNotEmpty() && msgText.contains(" " + ws.joinToString(" ") + " ")
    val n = minOf(QUOTE_WORDS, msg.size)
    val runs = msg.windowed(n).map { it.joinToString(" ") }.toHashSet()
    val unquoted = QUOTED.replace(description) { m ->
      val inner = m.groupValues.drop(1).first { it.isNotEmpty() }
      val ws = words(inner)
      if (ws.size >= 3 && quotes(ws)) "" else m.value
    }
    // Per sentence: quoting ones go, and so do labels left without their quote ("She writes: .").
    return unquoted.lines().map { line ->
      line.split(Regex("""(?<=[.!?…])\s+"""))
        .filterNot { sentence -> words(sentence).windowed(n).any { it.joinToString(" ") in runs } }
        .map { it.replace(Regex("""\s*:\s*[.!?]?$"""), ":").replace(Regex("""\s{2,}"""), " ").trim() }
        .filter { it.isNotEmpty() && !it.endsWith(":") && words(it).isNotEmpty() }
        .joinToString(" ")
    }
      .filter { it.isNotEmpty() && it !in setOf("•", "-", "📍") }
      .joinToString("\n")
  }

  /** Words in a row that make a sentence a quote of the message. */
  private const val QUOTE_WORDS = 5

  /**
   * "Keep message text" off: the entry as it may be stored and delivered – without the message in the description.
   * [keep] is true for texts the user typed into the app to test it (they are not messages of anybody).
   */
  fun forStorage(d: Detection, message: String, s: AppSettings, keep: Boolean = false): Detection =
    if (s.storeMessageText || keep) d else d.copy(description = descriptionWithoutMessage(d, message))

  /**
   * "Sender in title" off: the title without the sender's name, whatever engine or style made it (a style prefix such
   * as "📌 " stays). Description and place are left as they are; the other switches handle them.
   */
  fun titleForTargets(d: Detection, sender: String, s: AppSettings): Detection {
    if (s.senderInTitle || d.title.isBlank()) return d
    val names = senderNames(d, sender)
    if (names.isEmpty()) return d
    val title = redactSender(d.title, names)
    val bare = title.isBlank() || title.none { it.isLetterOrDigit() } || StyleTemplates.isOnlyPrefix(title)
    return d.copy(title = if (bare) "$title ${neutralTitle(d.kind)}".trim() else title)
  }

  /** What webhook and own server may get: with the switch off neither the message nor the sender. */
  fun shareable(d: Detection, src: SourceInfo, withSource: Boolean): Detection =
    if (withSource) d else withoutSender(d.copy(description = descriptionWithoutMessage(d, src.text)), senderNames(d, src.sender))

  /** Title / place for a model request: without the sender name unless "Send sender names to the AI" is on. */
  fun forAi(d: Detection, sender: String, s: AppSettings): Detection =
    if (s.shareSenderWithAi) d else withoutSender(d, senderNames(d, sender))
}
