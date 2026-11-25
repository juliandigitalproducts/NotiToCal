package de.juliandigitalproducts.notitocal.analysis

import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.SourceType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregates chat and notification messages belonging to the same thread / group conversation
 * within a configurable time window (1..24 hours) into a coherent appointment structure.
 * Supports:
 * - Multi-message timespan merge (e.g. "18 Uhr am Kino" + "22 Uhr zu Ende" -> "Kino (18–22 Uhr)")
 * - Context duration estimation (Kino: 180 min, Restaurant: 120 min, Arzt: 60 min)
 * - Slot-filling, negation/correction override ("nein die 123"), affirmation confirmation, cancellation.
 * - Rebuilding a thread from the stored history after the process was killed.
 */
object ConversationAggregator {
  private const val DEFAULT_MINUTES = 60

  data class SessionMessage(
    val sender: String,
    val text: String,
    val timestamp: Long,
  )

  enum class ThreadStatus { PROPOSED, CONFIRMED, CANCELLED }

  data class AggregatedSlotState(
    var date: LocalDate? = null,
    var time: LocalTime? = null,
    var endTime: LocalTime? = null,
    var durationMinutes: Int? = null,
    var isEndEstimated: Boolean = false,
    var estimationReason: String? = null,
    var street: String? = null,
    var houseNumber: String? = null,
    /** A complete address written in the conversation (street + number + PLZ/city), if any. */
    var location: String? = null,
    var title: String? = null,
    /** True when the title comes from a venue/event keyword ("Kino", "Arzt" ...), not the generic fallback. */
    var hasTopic: Boolean = false,
    var formattedTimeSpan: String = "",
    var status: ThreadStatus = ThreadStatus.PROPOSED,
    var confidence: Double = 0.5,
    val messageCount: Int,
  ) {
    /**
     * Enough evidence to suggest an event assembled from several messages: a date, a time or topic, more than one
     * message, and no cancellation. A promoted event is only ever suggested (ASK), never added automatically.
     */
    val promotable: Boolean
      get() = status == ThreadStatus.CONFIRMED && date != null && messageCount >= 2 && (time != null || hasTopic)
  }

  const val DEFAULT_WINDOW_MILLIS = 3 * 60 * 60 * 1000L // 3 hours

  private val sessions = ConcurrentHashMap<String, MutableList<SessionMessage>>()

  fun sessionKey(source: SourceType, packageName: String, sender: String): String {
    return "$source:$packageName:${sender.trim().lowercase()}"
  }

  // Regex patterns for negation & correction:
  // e.g. "nein die 123", "nicht 3 sondern 123", "doch lieber 123", "falsch, 123", "ne die 123"
  val NEGATION_CORRECTION_REGEX = Regex(
    """\b(?:nein|nicht|doch\s+lieber|eher|falsch|stattdessen|doch\s+die|lieber\s+die|ne\s+die|nein\s+die|nicht\s+die)\b.*?(?:nr\.?|hausnummer|die\s+)?\s*(\d{1,4}[a-zA-Z]?)\b""",
    RegexOption.IGNORE_CASE,
  )

  // Regex patterns for affirmation & confirmation:
  // e.g. "ok machen wir so", "passt", "alles klar", "abgemacht", "super machen wir so"
  val AFFIRMATION_CONFIRMATION_REGEX = Regex(
    """\b(?:ok|okay|passt|machen\s+wir(?:\s+so)?|alles\s+klar|top|abgemacht|gerne|super|gut|geht\s+klar|einverstanden|perfekt|sounds\s+good|deal|sure|agreed|perfect|great|works\s+for\s+me|see\s+you(?:\s+then)?)\b""",
    RegexOption.IGNORE_CASE,
  )

  // A declining/cancelling message ("kann nicht", "bin nicht da", "absagen") never confirms a meeting.
  val CANCEL_REGEX = Regex(
    """\b(?:kann\s+(?:ich\s+)?nicht|bin\s+(?:da\s+)?nicht\s+da|nicht\s+da|keine\s+zeit|absagen|abgesagt|fällt\s+aus|faellt\s+aus|klappt\s+nicht|geht\s+nicht|can'?t\s+make\s+it|cannot\s+make\s+it|won'?t\s+make\s+it|can'?t\s+come|not\s+able\s+to\s+(?:come|make\s+it)|no\s+time|called\s+off|cancel(?:l?ed)?)\b""",
    RegexOption.IGNORE_CASE,
  )

  // Street address pattern with optional prefix and house number:
  val STREET_PATTERN = Regex(
    """\b(?:(?:an\s+der|in\s+der|auf\s+der|am|im|bei)\s+)?((?:[A-ZÄÖÜ][a-zäöüß\-]+\s+){0,3}[A-ZÄÖÜa-zäöüß\-]+(?:straße|strasse|str\.|weg|platz|allee|gasse|ring|chaussee|damm|ufer|pfad|zeile|street|road|avenue|lane|drive|boulevard))\s*(\d{1,4}[a-zA-Z]?)?""",
    RegexOption.IGNORE_CASE,
  )


  /**
   * Adds a message to its thread and evaluates the thread. [history] is asked for stored messages of the same
   * thread since a cutoff when the in-memory thread is empty (e.g. after the process was killed).
   */
  @Synchronized
  fun addMessageAndEvaluate(
    source: SourceType,
    packageName: String,
    sender: String,
    text: String,
    timestamp: Long = System.currentTimeMillis(),
    windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    nowDateTime: LocalDateTime = LocalDateTime.now(),
    s: AppSettings,
    history: ((since: Long) -> List<SessionMessage>)? = null,
  ): AggregatedSlotState {
    val key = sessionKey(source, packageName, sender)
    val cutoff = timestamp - windowMillis
    // Threads that went quiet are dropped so the map does not grow forever.
    sessions.entries.removeIf { (k, v) -> k != key && v.all { it.timestamp < cutoff } }
    val list = sessions.computeIfAbsent(key) { mutableListOf() }

    if (list.isEmpty() && history != null) {
      runCatching { history(cutoff) }.getOrNull()
        ?.filter { it.timestamp in cutoff until timestamp && it.text.isNotBlank() }
        ?.sortedBy { it.timestamp }
        ?.let { list.addAll(it) }
    }

    list.removeAll { it.timestamp < cutoff }
    list.add(SessionMessage(sender, text, timestamp))

    return evaluateThread(list, nowDateTime, s, sender)
  }

  fun evaluateThread(
    messages: List<SessionMessage>,
    nowDateTime: LocalDateTime,
    @Suppress("UNUSED_PARAMETER") s: AppSettings,
    @Suppress("UNUSED_PARAMETER") chatSender: String = "",
  ): AggregatedSlotState {
    var date: LocalDate? = null
    var startTime: LocalTime? = null
    var endTime: LocalTime? = null
    var durationMinutes: Int? = null
    var isEndEstimated = false
    var estimationReason: String? = null

    var street: String? = null
    var houseNumber: String? = null

    var status = ThreadStatus.PROPOSED
    var confidence = 0.5
    var hasConfirmation = false
    var topic: Activity? = null
    var hasTopic = false

    val combinedText = messages.joinToString(" ") { it.text }
    // Titles follow the language of the chat (German and English are recognised side by side).
    val lang = TextLang.of(combinedText)
    // Relative words ("morgen") refer to when each message was written, not to the newest message.
    val newest = messages.maxOfOrNull { it.timestamp } ?: 0L

    for (msg in messages) {
      val text = msg.text
      val msgNow = nowDateTime.minusSeconds(((newest - msg.timestamp) / 1000).coerceAtLeast(0))

      // 1. Check Date / Start Time
      val whenParsed = DateParser.parse(text, msgNow)
      if (whenParsed.date != null) {
        date = whenParsed.date
        confidence = maxOf(confidence, 0.72)
      }
      if (whenParsed.time != null && startTime == null) {
        startTime = whenParsed.time
        confidence = maxOf(confidence, 0.78)
      }

      // 2. A time span in one message ("18-22 Uhr", "6 to 10 pm")
      TimeCues.span(text)?.let { (start, end) ->
        startTime = start
        endTime = end
        confidence = maxOf(confidence, 0.85)
      }

      // 3. An end time in a later message ("Sollte so 22 Uhr zu Ende sein", "should be over by 10 pm")
      if (endTime == null) {
        TimeCues.end(text)?.let {
          endTime = it
          confidence = maxOf(confidence, 0.85)
        }
      }

      // 4. Check Street & House number
      val streetMatch = STREET_PATTERN.find(text)
      if (streetMatch != null) {
        street = streetMatch.groupValues[1].trim()
        val num = streetMatch.groupValues[2].trim()
        if (num.isNotBlank()) {
          houseNumber = num
        }
        confidence = maxOf(confidence, 0.75)
      }

      // 5. Negation & Correction Override: e.g. "nein die 123"
      val negationMatch = NEGATION_CORRECTION_REGEX.find(text)
      if (negationMatch != null) {
        val correctedNum = negationMatch.groupValues[1].trim()
        if (correctedNum.isNotBlank()) {
          houseNumber = correctedNum
          confidence = maxOf(confidence, 0.82)
        }
      }

      // 6. Cancellation beats confirmation; a later confirmation re-opens the thread.
      if (CANCEL_REGEX.containsMatchIn(text)) {
        hasConfirmation = false
        status = ThreadStatus.CANCELLED
      } else if (AFFIRMATION_CONFIRMATION_REGEX.containsMatchIn(text)) {
        hasConfirmation = true
        status = ThreadStatus.CONFIRMED
        confidence = maxOf(confidence, 0.90)
      }

      // 7. What it is about ("Kino", "dinner", "Arzt", "meeting" …)
      Activity.find(text)?.let {
        topic = it
        hasTopic = true
      }
    }

    // Process Timespan / Duration
    var formattedTimeSpan = ""
    val start = startTime
    if (start != null) {
      val writtenEnd = endTime
      if (writtenEnd != null) {
        durationMinutes = Duration.between(start, writtenEnd).toMinutes().toInt().let { if (it <= 0) it + 24 * 60 else it }
        isEndEstimated = false
        estimationReason = Wording.endFromFollowUp()
        formattedTimeSpan = Wording.span(lang, start, writtenEnd)
      } else {
        val minutes = Activity.typicalMinutes(combinedText) ?: DEFAULT_MINUTES
        val estimatedEnd = start.plusMinutes(minutes.toLong())
        durationMinutes = minutes
        endTime = estimatedEnd
        isEndEstimated = true
        estimationReason = Wording.estimated(minutes, topic)
        formattedTimeSpan = Wording.span(lang, start, estimatedEnd)
      }
    }

    // Only a written address counts; no venue names are invented.
    val resolvedLocation = RuleEngine.fullAddress(combinedText)

    // The very high confidence needs a confirmed date AND a time; a date alone stays a suggestion.
    if (hasConfirmation && date != null && startTime != null) {
      confidence = maxOf(confidence, 0.95)
    }
    if (status == ThreadStatus.CANCELLED) confidence = minOf(confidence, 0.4)

    // Without a topic it is a get-together. Cinema and get-togethers carry their hours: "Kino (18–22 Uhr)".
    val activity = topic ?: Activity.GET_TOGETHER
    val title = activity.title(lang)
    val spanStart = startTime
    val spanEnd = endTime
    val finalTitle = if (spanStart != null && spanEnd != null && activity in setOf(Activity.CINEMA, Activity.GET_TOGETHER)) {
      Wording.titleWithHours(lang, title, spanStart, spanEnd)
    } else {
      title
    }

    return AggregatedSlotState(
      date = date,
      time = startTime,
      endTime = endTime,
      durationMinutes = durationMinutes,
      isEndEstimated = isEndEstimated,
      estimationReason = estimationReason,
      street = street,
      houseNumber = houseNumber,
      location = resolvedLocation,
      title = finalTitle,
      hasTopic = hasTopic,
      formattedTimeSpan = formattedTimeSpan,
      status = status,
      confidence = confidence,
      messageCount = messages.size,
    )
  }

  @Synchronized
  fun clearSession(source: SourceType, packageName: String, sender: String) {
    sessions.remove(sessionKey(source, packageName, sender))
  }

  @Synchronized
  fun clearAll() {
    sessions.clear()
  }
}
