// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Language of a message for the rule-based parts. German and English are recognised side by side; texts written into
 * an appointment (title, description) follow the message, explanations shown in the app follow the app language.
 */
enum class TextLang(val locale: Locale) {
  DE(Locale.GERMAN),
  EN(Locale.ENGLISH);

  companion object {
    private val DE_MARKERS = Regex(
      """\b(?:der|die|das|und|nicht|ich|wir|uns|um|uhr|morgen|heute|übermorgen|mit|für|bei|beim|treffen|termin|bis|ende|kann|bitte|danke)\b|[äöüß]""",
      RegexOption.IGNORE_CASE,
    )
    private val EN_MARKERS = Regex(
      """\b(?:the|and|not|we|us|tomorrow|today|with|for|meet|meeting|appointment|until|can|please|thanks|let's|see\s+you|pm|o'clock)\b""",
      RegexOption.IGNORE_CASE,
    )

    /** The language the text is written in; a tie (or no words) falls back to the app language. */
    fun of(text: String): TextLang {
      val de = DE_MARKERS.findAll(text).count()
      val en = EN_MARKERS.findAll(text).count()
      return when {
        de > en -> DE
        en > de -> EN
        else -> app()
      }
    }

    /** The app language (German for a German UI, English for every other one). */
    fun app(): TextLang = if (Locale.getDefault().language == "de") DE else EN
  }
}

/** What a message is about, recognised from German and English words at the same time; first match wins. */
enum class Activity(pattern: String, private val de: String, private val en: String, val minutes: Int) {
  CINEMA("""kino\w*|cinema|movies?""", "Kino", "Cinema", 180),
  THEATRE("""theater\w*|theatre""", "Theater", "Theatre", 180),
  CONCERT("""konzert\w*|concerts?""", "Konzert", "Concert", 180),
  MEAL(
    """restaurant|(?:abend|mittag|früh(?:stücks)?)?essen|frühstück|brunch|dinner|lunch|breakfast""",
    "Essen", "Meal", 120,
  ),
  DOCTOR("""\w*arzt\w*|doktor|doctor|dentist""", "Arzttermin", "Doctor's appointment", 60),
  HAIRDRESSER("""friseur\w*|frisör\w*|hairdresser|haircut""", "Friseur", "Hairdresser", 60),
  MEETING("""meetings?""", "Meeting", "Meeting", 60),
  GET_TOGETHER("""treffen|meet|meet\s*up|catch\s+up|get\s+together""", "Treffen", "Get-together", 120),
  APPOINTMENT("""\w*termin\w*|appointment""", "Termin", "Appointment", 60);

  private val regex = Regex("""\b(?:$pattern)\b""", RegexOption.IGNORE_CASE)

  fun matches(text: String) = regex.containsMatchIn(text)
  fun title(lang: TextLang) = if (lang == TextLang.DE) de else en

  companion object {
    fun find(text: String): Activity? = entries.firstOrNull { it.matches(text) }

    /** Typical length when no end time was written: the longest kind of activity mentioned anywhere in the text. */
    fun typicalMinutes(text: String): Int? = entries.filter { it.matches(text) }.maxOfOrNull { it.minutes }
  }
}

/** Times written as an end or as a range, in German and English ("bis 22 Uhr", "until 10 pm", "6 to 10 pm"). */
object TimeCues {
  private val END = Regex(
    """\b(?:bis|ende|schluss|zu\s+ende|bis\s+um|bis\s+ca\.?|ende\s+ca\.?|until|till|ends?\s+(?:at|around|by)|finish(?:es|ed)?\s+(?:at|around|by)|over\s+by|done\s+by)""" +
      """\s*(?:so\s+|gegen\s+|um\s+|about\s+|around\s+|at\s+)?(\d{1,2})(?::(\d{2}))?\s*(uhr|am|pm|a\.m\.|p\.m\.)?(?!\w)""" +
      """|\b(\d{1,2})(?::(\d{2}))?\s*uhr\s*(?:zu\s+ende|schluss)\b""",
    RegexOption.IGNORE_CASE,
  )
  private val SPAN = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(?:-|–|bis)\s*(\d{1,2})(?::(\d{2}))?\s*(?:uhr)?\b""", RegexOption.IGNORE_CASE)

  // English ranges need "am"/"pm" at the end, so "3 to 4 people" is never read as a time.
  private val SPAN_EN = Regex(
    """\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*(?:to|until|till|-|–)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)(?!\w)""",
    RegexOption.IGNORE_CASE,
  )

  fun end(text: String): LocalTime? {
    val m = END.find(text) ?: return null
    val g = m.groupValues
    return if (g[1].isNotEmpty()) time(g[1], g[2], g[3]) else time(g[4], g[5], "")
  }

  fun span(text: String): Pair<LocalTime, LocalTime>? {
    SPAN_EN.find(text)?.groupValues?.let { g ->
      val endSuffix = g[6]
      // "6 to 10 pm": the start takes the end's am/pm unless that would put it after the end.
      val startSuffix = g[3].ifEmpty { if ((g[1].toIntOrNull() ?: 0) <= (g[4].toIntOrNull() ?: 0)) endSuffix else "" }
      val start = time(g[1], g[2], startSuffix)
      val end = time(g[4], g[5], endSuffix)
      if (start != null && end != null) return start to end
    }
    val g = SPAN.find(text)?.groupValues ?: return null
    val start = time(g[1], g[2], "")
    val end = time(g[3], g[4], "")
    return if (start != null && end != null) start to end else null
  }

  private fun time(hour: String, minute: String, suffix: String): LocalTime? {
    var h = hour.toIntOrNull() ?: return null
    val m = minute.toIntOrNull() ?: 0
    val s = suffix.lowercase().replace(".", "")
    if (s == "pm" && h in 1..11) h += 12
    if (s == "am" && h == 12) h = 0
    return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
  }
}

/** Texts the rule-based parts write, in German or English. */
object Wording {
  private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

  fun hhmm(t: LocalTime): String = t.format(HHMM)

  /** Title of an announced parcel delivery. */
  fun delivery(lang: TextLang) = if (lang == TextLang.DE) "Paketzustellung" else "Parcel delivery"

  /** "18:00–22:00 Uhr" / "18:00–22:00". */
  fun span(lang: TextLang, start: LocalTime, end: LocalTime) =
    if (lang == TextLang.DE) "${hhmm(start)}–${hhmm(end)} Uhr" else "${hhmm(start)}–${hhmm(end)}"

  /** "18:00 bis 22:00 Uhr" / "18:00 to 22:00". */
  fun range(lang: TextLang, start: LocalTime, end: LocalTime) =
    if (lang == TextLang.DE) "${hhmm(start)} bis ${hhmm(end)} Uhr" else "${hhmm(start)} to ${hhmm(end)}"

  /** "Kino (18–22 Uhr)" / "Cinema (18–22)". */
  fun titleWithHours(lang: TextLang, title: String, start: LocalTime, end: LocalTime) =
    if (lang == TextLang.DE) "$title (${start.hour}–${end.hour} Uhr)" else "$title (${start.hour}–${end.hour})"

  fun date(lang: TextLang, d: LocalDate): String =
    d.format(DateTimeFormatter.ofPattern(if (lang == TextLang.DE) "dd.MM.yyyy" else "d MMM yyyy", lang.locale))

  fun duration(lang: TextLang, minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when (lang) {
      TextLang.DE -> listOfNotNull("$h Std.".takeIf { h > 0 }, "$m Min.".takeIf { m > 0 }).joinToString(" ")
      TextLang.EN -> listOfNotNull("$h h".takeIf { h > 0 }, "$m min".takeIf { m > 0 }).joinToString(" ")
    }
  }

  /** "Treffen beim Italiener" / "Meet at the Italian" (from "at the Italian place"). */
  fun meetAtVenue(lang: TextLang, venue: String) =
    if (lang == TextLang.DE) "Treffen $venue" else "Meet at " + venue.replace(Regex("""\s+place$""", RegexOption.IGNORE_CASE), "")

  fun meetingAt(lang: TextLang, place: String) = if (lang == TextLang.DE) "Treffen $place" else "Meeting at $place"

  fun appointmentOn(lang: TextLang, title: String, d: LocalDate, reason: String) =
    if (lang == TextLang.DE) "Termin: $title am ${date(lang, d)}. $reason" else "Appointment: $title on ${date(lang, d)}. $reason"

  fun detailed(lang: TextLang, title: String, time: String, place: String, details: String) = when (lang) {
    TextLang.DE -> "Termin: $title\nZeit: $time\nOrt: $place\nDetails: $details"
    TextLang.EN -> "Appointment: $title\nTime: $time\nPlace: $place\nDetails: $details"
  }

  fun detailedTime(lang: TextLang, start: LocalTime, end: LocalTime, minutes: Int) = when (lang) {
    TextLang.DE -> "${hhmm(start)} Uhr bis ${hhmm(end)} Uhr (Dauer: ${duration(lang, minutes)})"
    TextLang.EN -> "${hhmm(start)} to ${hhmm(end)} (duration: ${duration(lang, minutes)})"
  }

  fun fallbackTitle(lang: TextLang) = Activity.APPOINTMENT.title(lang)

  fun mergedFrom(lang: TextLang, count: Int) =
    if (lang == TextLang.DE) "Zusammengeführt aus $count Ereignissen." else "Merged from $count events."

  // ---- Explanations shown in the app: app language ----

  fun explicitEnd(start: LocalTime, end: LocalTime) = when (TextLang.app()) {
    TextLang.DE -> "Endzeit steht in der Nachricht (${hhmm(start)}–${hhmm(end)})."
    TextLang.EN -> "Explicit end time in the message (${hhmm(start)}–${hhmm(end)})."
  }

  fun endFromFollowUp() = when (TextLang.app()) {
    TextLang.DE -> "Endzeit aus einer späteren Nachricht."
    TextLang.EN -> "Explicit end time from a follow-up message."
  }

  /** Why the appointment got this length when no end time was written. */
  fun estimated(minutes: Int, activity: Activity?): String {
    val lang = TextLang.app()
    val what = activity?.title(lang)
    return when (lang) {
      TextLang.DE -> if (what != null) "Kontext $what: etwa ${duration(lang, minutes)}." else "Standarddauer (${duration(lang, minutes)})."
      TextLang.EN -> if (what != null) "$what context: about ${duration(lang, minutes)}." else "Default duration (${duration(lang, minutes)})."
    }
  }

  fun reasoning(span: String, estimation: String, source: String) = when (TextLang.app()) {
    TextLang.DE -> "Termin erkannt; Zeitspanne $span ($estimation); Ort über $source ermittelt."
    TextLang.EN -> "Appointment found; time span $span ($estimation); place resolved via $source."
  }

  fun mergeReason(start: LocalTime, end: LocalTime) = when (TextLang.app()) {
    TextLang.DE -> "Ereignisse und Zeitspannen zusammengeführt (${hhmm(start)} bis ${hhmm(end)} Uhr)."
    TextLang.EN -> "Events and time spans merged (${hhmm(start)} to ${hhmm(end)})."
  }

  /** "Place found via Photon (Köln)". */
  fun placeVia(source: String) = when (TextLang.app()) {
    TextLang.DE -> "Ort über $source ermittelt"
    TextLang.EN -> "Place found via $source"
  }

  /** "location history (7 visits)". */
  fun locationHistory(visits: Int) = when (TextLang.app()) {
    TextLang.DE -> "Standortverlauf ($visits ${if (visits == 1) "Besuch" else "Besuche"})"
    TextLang.EN -> "location history ($visits ${if (visits == 1) "visit" else "visits"})"
  }

  fun homeArea(city: String) = if (TextLang.app() == TextLang.DE) "Ort $city" else "Area $city"
}
