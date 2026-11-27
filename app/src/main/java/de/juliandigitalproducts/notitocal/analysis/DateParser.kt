package de.juliandigitalproducts.notitocal.analysis

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.regex.Pattern
import de.juliandigitalproducts.notitocal.model.runSafely

/**
 * Unicode-aware, case-insensitive regex. Android's ICU engine is Unicode-aware by default but rejects the
 * inline (?U) flag; on the JVM (unit tests) UNICODE_CHARACTER_CLASS is needed for \b and \w with umlauts/accents.
 */
internal fun rx(pattern: String, ignoreCase: Boolean = true): Regex {
  val android = System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
  var flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
  if (!android) flags = flags or Pattern.UNICODE_CHARACTER_CLASS
  return Pattern.compile(pattern, flags).toRegex()
}

/** Result of scanning a message for date/time expressions. */
data class ParsedWhen(
  val date: LocalDate? = null,
  val endDate: LocalDate? = null,
  val time: LocalTime? = null,
  val endTime: LocalTime? = null,
  /** A date was written (absolute or relative), not just inferred from a lone time. */
  val dateExplicit: Boolean = false,
  /** The time came from a vague hint ("evening") rather than a clock time. */
  val vagueTime: Boolean = false,
  /** The date is phrased as a deadline ("until Friday", "bis morgen"). */
  val deadline: Boolean = false,
  /** Character ranges of the matched expressions (used to clean up titles). */
  val spans: List<IntRange> = emptyList(),
  /** Marked true when only a month was provided and the 1st was used as a provisional day fallback. */
  val isProvisionalDay: Boolean = false,
  /** A written duration ("Dauer 90 Minuten", "for 30 min", "(2 Std.)"); used for the end when no end time is given. */
  val durationMinutes: Int? = null,
) {
  val hasAnything: Boolean get() = date != null || endDate != null || time != null
}

/**
 * Offline, multilingual (de, en, fr, es, it, pt, pl) date/time extraction. Deliberately heuristic:
 * it recognises the phrasing people use in chats, not every possible calendar notation.
 */
object DateParser {

  private val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
    fun put(day: DayOfWeek, vararg names: String) = names.forEach { put(it, day) }
    put(DayOfWeek.MONDAY, "montag", "monday", "mon", "lundi", "lunes", "lunedì", "lunedi", "segunda-feira", "segunda", "poniedziałek", "poniedzialek")
    put(DayOfWeek.TUESDAY, "dienstag", "tuesday", "tue", "tues", "mardi", "martes", "martedì", "martedi", "terça-feira", "terca-feira", "terça", "terca", "wtorek")
    put(DayOfWeek.WEDNESDAY, "mittwoch", "wednesday", "wed", "mercredi", "miércoles", "miercoles", "mercoledì", "mercoledi", "quarta-feira", "quarta", "środa", "sroda", "środę", "srode")
    put(DayOfWeek.THURSDAY, "donnerstag", "thursday", "thu", "thurs", "jeudi", "jueves", "giovedì", "giovedi", "quinta-feira", "quinta", "czwartek")
    put(DayOfWeek.FRIDAY, "freitag", "friday", "fri", "vendredi", "viernes", "venerdì", "venerdi", "sexta-feira", "sexta", "piątek", "piatek")
    put(DayOfWeek.SATURDAY, "samstag", "sonnabend", "saturday", "sat", "samedi", "sábado", "sabado", "sabato", "sobota", "sobotę", "sobote")
    put(DayOfWeek.SUNDAY, "sonntag", "sunday", "sun", "dimanche", "domingo", "domenica", "niedziela", "niedzielę", "niedziele")
    // Recurring ("immer montags", "every mondays") → the next such day; frequent typos in chats ("Mitwoch", "Donerstag").
    put(DayOfWeek.MONDAY, "montags", "mondays", "montg")
    put(DayOfWeek.TUESDAY, "dienstags", "tuesdays", "dienstg", "dientag")
    put(DayOfWeek.WEDNESDAY, "mittwochs", "wednesdays", "mitwoch", "mittwoh", "mittwch", "mitwochs", "wensday", "wednsday")
    put(DayOfWeek.THURSDAY, "donnerstags", "thursdays", "donerstag", "donnerstg", "thurday")
    put(DayOfWeek.FRIDAY, "freitags", "fridays", "freitg", "fritag")
    put(DayOfWeek.SATURDAY, "samstags", "saturdays", "samstg", "samtag")
    put(DayOfWeek.SUNDAY, "sonntags", "sundays", "sonntg", "sontag")
  }

  /**
   * German two-letter weekdays ("Mo 14 Uhr", "Fr. 18:00", "Do 3.12."), case-sensitive and only right before a time or
   * date, so "so", "do" or "Fr. Müller" never count.
   */
  private val WEEKDAY_ABBR_DE = rx("""(?<![\p{L}])(Mo|Di|Mi|Do|Fr|Sa|So)(\.?)(?=,?\s+(?:den\s+)?(\d{1,2}\.\d{1,2}\.|\d{1,2}(?:[:.]\d{2}|\s*[Uu]hr\b)))""", ignoreCase = false)
  private val ABBR_DAYS = mapOf(
    "Mo" to DayOfWeek.MONDAY, "Di" to DayOfWeek.TUESDAY, "Mi" to DayOfWeek.WEDNESDAY, "Do" to DayOfWeek.THURSDAY,
    "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY, "So" to DayOfWeek.SUNDAY,
  )

  /** Fixed-date holidays people name instead of the date ("Silvester feiern wir …", "Christmas Eve dinner"). */
  private val HOLIDAYS: List<Pair<Regex, Pair<Int, Int>>> = listOf(
    rx("""\b(?:silvester(?:abend|party|feier)?|new\s+year'?s\s+eve|réveillon|nochevieja)\b""") to (12 to 31),
    rx("""\b(?:heilig(?:en)?\s*abend|christmas\s+eve)\b""") to (12 to 24),
    rx("""\b(?:(?:1\.|erste[rnm]?)\s*weihnachts(?:feier)?tag|christmas\s+day)\b""") to (12 to 25),
    rx("""\b(?:(?:2\.|zweite[rnm]?)\s*weihnachts(?:feier)?tag|boxing\s+day)\b""") to (12 to 26),
    rx("""\b(?:neujahrstag|new\s+year'?s\s+day)\b""") to (1 to 1),
  )

  /**
   * Rescheduling ("statt Dienstag jetzt Mittwoch", "vom Freitag auf Samstag", "moved from Monday to Tuesday"): the old
   * day is blanked out so the new one is read. Only with a rescheduling word in the message.
   */
  private val RESCHEDULE = rx("""\b(?:verschieb\w*|verschoben|verlegt|verlegen|umgelegt|statt|anstatt|stattdessen|moved?|rescheduled?|postponed?|instead)\b""")

  private val MONTHS: Map<String, Int> = buildMap {
    fun put(m: Int, vararg names: String) = names.forEach { put(it, m) }
    put(1, "januar", "jänner", "january", "jan", "janvier", "enero", "gennaio", "janeiro", "styczeń", "stycznia", "styczniu")
    put(2, "februar", "february", "feb", "février", "fevrier", "febrero", "febbraio", "fevereiro", "luty", "lutego", "lutym")
    put(3, "märz", "maerz", "mär", "mrz", "march", "mar", "mars", "marzo", "março", "marco", "marzec", "marca", "marcu")
    put(4, "april", "apr", "avril", "abril", "aprile", "kwiecień", "kwietnia", "kwietniu")
    put(5, "mai", "may", "mayo", "maggio", "maio", "maj", "maja", "maju")
    put(6, "juni", "june", "jun", "juin", "junio", "giugno", "junho", "czerwiec", "czerwca", "czerwcu")
    put(7, "juli", "july", "jul", "juillet", "julio", "luglio", "julho", "lipiec", "lipca", "lipcu")
    put(8, "august", "aug", "août", "aout", "agosto", "sierpień", "sierpnia", "sierpniu")
    put(9, "september", "sep", "sept", "septembre", "septiembre", "setiembre", "settembre", "setembro", "wrzesień", "września", "wrześniu")
    put(10, "oktober", "october", "okt", "oct", "octobre", "octubre", "ottobre", "outubro", "październik", "października", "październiku")
    put(11, "november", "nov", "novembre", "noviembre", "novembro", "listopad", "listopada", "listopadzie")
    put(12, "dezember", "december", "dez", "dec", "décembre", "decembre", "diciembre", "dicembre", "dezembro", "grudzień", "grudnia", "grudniu")
  }

  private val NUMBER_WORDS = mapOf(
    "a" to 1, "an" to 1, "one" to 1, "einem" to 1, "einer" to 1, "ein" to 1, "un" to 1, "une" to 1, "uno" to 1, "una" to 1,
    "um" to 1, "uma" to 1, "jeden" to 1, "jedną" to 1,
    "two" to 2, "zwei" to 2, "deux" to 2, "dos" to 2, "due" to 2, "dois" to 2, "duas" to 2, "dwa" to 2, "dwie" to 2,
    "three" to 3, "drei" to 3, "trois" to 3, "tres" to 3, "tre" to 3, "três" to 3, "trzy" to 3,
  )

  private fun alt(words: Collection<String>) =
    words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

  private val monthAlt = alt(MONTHS.keys)
  private val weekdayAlt = alt(WEEKDAYS.keys)

  private val ISO_DATE = rx("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
  // (?<![\d.]) / (?!\.\d): "1.5.15.3" or "v2.1.5.15" are version numbers, not dates.
  private val DOT_DATE = rx("""(?<![\d.])\b(\d{1,2})\.(\d{1,2})\.(\d{2,4})?(?![\d:])(?!\.\d)""")
  private val SHORT_DOT_DATE = rx("""\b(?:am|on|le|el|il|dia|dnia|den|vom|ab|bis|by|until|zum)\s+(\d{1,2})\.(\d{1,2})\b(?![.:\d])(?!\s*(?:uhr|h\b|€|eur|%))""")
  private val SLASH_DATE = rx("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
  private val DAY_MONTH = rx("""\b(\d{1,2})(?:\.|st|nd|rd|th|er|º|°)?\s*(?:de\s+|of\s+)?($monthAlt)\b\.?(?:\s*,?\s*(?:de\s+)?(\d{4}))?""")
  private val MONTH_DAY = rx("""\b($monthAlt)\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:\s*,?\s*(\d{4}))?""")
  // Month only without day (e.g. "im Januar", "in January", "en janvier", "für den März", "im Januar 2027")
  private val MONTH_ONLY = rx("""\b(?:im|in|en|nel|a|w|we|em|no|für\s+(?:den)?|fuer\s+(?:den)?|den|ab|zum|ab\s+dem)\s+($monthAlt)\b(?:\s*,?\s*(\d{4}))?""")
  private val WEEKDAY = rx("""\b(?:(next|this|coming|nächsten|nächste|naechsten|kommenden|kommende|diesen|letzten|letzter|vergangenen|vorigen|last|prochain|ce|el próximo|el proximo|este|prossimo|questo|próximo|proximo|próxima|proxima|esta|w|we|w przyszły|w przyszłą|przyszły|przyszłą)\s+)?($weekdayAlt)\b(?:\s+(prochain|que viene|prossimo|próximo))?""")
  private val OLD_DATE = rx("""\b(?:(?:statt|anstatt|anstelle\s+von|instead\s+of)\s+(?:dem\s+|am\s+|the\s+|on\s+)?(?:$weekdayAlt|\d{1,2}\.\d{1,2}\.?(?:\d{2,4})?|heute|morgen|today|tomorrow)|(?:vom|von|from)\s+(?:dem\s+|the\s+)?(?:$weekdayAlt|\d{1,2}\.\d{1,2}\.?(?:\d{2,4})?|heute|morgen|today|tomorrow)(?=\s+(?:auf|to|nach)\s))\b""")
  private val PAST_WEEKDAY_PREFIX = setOf("letzten", "letzter", "vergangenen", "vorigen", "last")
  private val IN_N = rx("""\b(?:in|dans|en|tra|fra|em|daqui a|za)\s+(\d{1,2}|${alt(NUMBER_WORDS.keys)})\s+(tag|tagen|days?|jours?|días|dias|día|dia|giorni|giorno|dni|dzień|woche|wochen|weeks?|semaines?|semanas?|settimane|settimana|tygodnie|tygodni|tydzień|stunden?|hours?|heures?|horas?|ore|ora|godzin|godziny|godzinę|minuten|minutes?|minutos?|minuti|minut)\b""")

  // Relative ordinal day of month without explicit month (e.g. "am 14ten", "am 14.", "am 14te", "am 14tem", "am 14")
  private val DAY_ORDINAL_CURRENT_OR_NEXT = rx("""\b(?:am|den|zum|ab|vom|für\s+den|fuer\s+den|on\s+the|le|el|il|dia|dnia)\s+(\d{1,2})(?:\.|ten|tem|te|th|st|nd|rd)?\b(?!\s*(?:uhr|h\b|€|eur|%|\.\d|\s*$monthAlt))""")

  private val DAY_AFTER = rx("""\b(übermorgen|uebermorgen|day after tomorrow|après[- ]demain|apres[- ]demain|pasado mañana|pasado manana|dopodomani|depois de amanhã|depois de amanha|pojutrze)\b""")
  private val TONIGHT = rx("""\b(heute abend|heute nacht|tonight|ce soir|esta noche|stasera|hoje à noite|hoje a noite|esta noite|dziś wieczorem|dzisiaj wieczorem|dzis wieczorem)\b""")
  private val TODAY = rx("""\b(heute|today|aujourd['’]hui|hoy|oggi|hoje|dziś|dzis|dzisiaj)\b""")
  private val TOMORROW = rx("""(?<!guten |am |heute |des |zum |jeden |la |por la |de la |esta |cada |una )\b(morgen|tomorrow|tmrw|tmr|demain|mañana|manana|domani|amanhã|amanha|jutro)\b""")
  private val NEXT_WEEK = rx("""\b(nächste woche|naechste woche|nächster woche|naechster woche|kommende woche|kommender woche|next week|la semaine prochaine|semaine prochaine|la próxima semana|la proxima semana|la semana que viene|la prossima settimana|settimana prossima|próxima semana|proxima semana|w przyszłym tygodniu|przyszły tydzień)\b""")
  private val WEEKEND = rx("""\b(wochenende|weekend|week-end|fin de semana|fine settimana|weekend|fim de semana)\b""")

  private val RANGE_DAY_MONTH = rx("""\b(?:vom\s+|from\s+|du\s+|del\s+)?(\d{1,2})\.?\s*(?:bis(?:\s+zum)?|-|–|—|to|au|a|al|até)\s*(\d{1,2})\.?\s*($monthAlt)\b(?:\s*(\d{2,4}))?""")
  private val RANGE_MONTH_DAYS = rx("""\b($monthAlt)\.?\s+(\d{1,2})(?:st|nd|rd|th)?\s*(?:-|–|—|to|until|through|thru)\s*(\d{1,2})(?:st|nd|rd|th)?\b(?![:.]\d)(?:\s*,?\s*(\d{4}))?""")
  private val RANGE_DOT_DATES = rx("""\b(?:vom\s+|from\s+)?(\d{1,2})\.(\d{1,2})\.?(?:\s*(\d{2,4}))?\s*(?:bis(?:\s+zum)?|-|–|—|to)\s*(\d{1,2})\.(\d{1,2})\.?(?:\s*(\d{2,4}))?\b""")
  private val RANGE_SHORT_DOT = rx("""\b(?:vom\s+|from\s+)?(\d{1,2})\.?\s*(?:bis(?:\s+zum)?|-|–|—|to)\s*(\d{1,2})\.(\d{1,2})\.?(?:\s*(\d{2,4}))?\b""")
  private val RANGE_WEEKDAY = rx("""\b(?:von\s+|from\s+)?($weekdayAlt)\s*(?:bis(?:\s+zum)?|-|–|—|to|until|au|a|al|até)\s*($weekdayAlt)\b""")

  private val DEADLINE = rx("""\b(bis|spätestens|by|until|till|before|due|avant|d'ici|jusqu'à|antes del?|hasta|entro|prima di|até|ate|antes de|do|przed|deadline|frist|fällig|échéance|fecha límite|scadenza|prazo|termin)\b""")

  // Time expressions.
  private val CLOCK = rx("""\b(\d{1,2}):(\d{2})\s*(a\.?m\.?|p\.?m\.?|uhr|h\b)?""")
  private val DOT_CLOCK = rx("""\b(\d{1,2})\.(\d{2})\s*(uhr|h)\b""")
  private val H_CLOCK = rx("""\b(\d{1,2})h(\d{2})?\b""")
  // Hour ranges with one marker: "14-16 Uhr", "14 bis 16 Uhr", "9–11h", "zwischen 14 und 16 Uhr" (both hours belong to
  // the range). "h" only written on: "2-3 h" is a duration. Group 1 or 2 is the start hour, group 3 the end hour.
  private val HOUR_RANGE = rx("""\b(?:(\d{1,2})\s*(?:-|–|—|bis|to)|zwischen\s+(\d{1,2})\s+und)\s*(\d{1,2})(?:\s*uhr\b|h\b|\s*([ap])\.?m\b\.?)""")
  // A day number right after a weekday: "viernes 27", "Freitag, den 27.", "Friday the 27th".
  private val WEEKDAY_DAY_TAIL = rx(""",?\s+(?:den\s+|der\s+|el\s+|le\s+|il\s+|the\s+)?(\d{1,2})(?:\.|st|nd|rd|th|º)?(?![\d:])(?!\s*(?:uhr|h\b|am\b|pm\b|a\.m|p\.m|[:.]\d|leute|personen|people|persons|€|eur|euro|%|min|std|stunden|hours))""")
  // German "um 7:45" is a 24h clock time (the evening would be written "um 19:45"). Matched with transparent bounds
  // right before the clock time, so "Datum 7:45" or "warum  7:45" do not count.
  private val UM_BEFORE = rx("""\bum\s{1,2}$""")
  private val MARKED_HOUR = rx("""\b(\d{1,2})\s*(uhr|a\.?m\.?|p\.?m\.?)(?![a-z])""")
  private val PREP_HOUR = rx("""(?:\b(?:um|gegen|at|around|à|vers|a las|a la|sobre las|alle|verso le|às|as|pelas|o|około|ok\.)\s+|(?<!\S)@\s*)(\d{1,2})(?![\d.:/,]|\s*(?:tag|day|jour|día|dia|giorn|dni|%|€|euro|uhr|h\b|min|std|hour|heure|hora|ora|godz|person|leute|people))""")
  // "von 9 bis 12:30 Uhr", "9-12:30": a bare start hour right before a clock time it ranges to.
  private val HOUR_TO_CLOCK = rx("""\b(?:(?:von|from|zwischen|between)\s+)?(\d{1,2})\s*(?:-|–|—|bis|to|till|until)\s*(?=\d{1,2}:\d{2}|\d{1,2}\.\d{2}\s*(?:uhr|h)\b)""")
  // "morgen ab 3": only used when a date is written and no other time was found ("ab 18 Jahren" has no date).
  private val AB_HOUR = rx("""\bab\s+(\d{1,2})(?![\d.:/,]|\s*(?:jahr|j\.|tag|woche|monat|%|€|euro|eur|person|leute|teilnehm|gäste|stück|stk|kg|km|grad|°|uhr|h\b|min|std|stunde|pers))""")
  private val DURATION = rx("""(?:\bdauer(?:t)?\s*(?:ca\.?|etwa|ungefähr|rund|circa)?\s*:?\s*|\bfür\s+(?:ca\.?\s+|etwa\s+|circa\s+)?|\bfor\s+(?:about\s+|around\s+|approx\.?\s+)?|\(\s*(?:ca\.?\s*)?)(\d{1,3}(?:[.,]\d{1,2})?|eine?|einer|an|one|two|three|zwei|drei|anderthalb|eineinhalb)\s*(minuten|min\.?|mins|minutes?|stunden?|std\.?|h|hours?|hrs?)(?![\p{L}])""")
  private val HALF_PAST_DE = rx("""\bhalb\s+(\d{1,2})\b""")
  // Colloquial quarters: "viertel nach 4" = 4:15, "viertel vor 5" = 4:45, "dreiviertel 5" = 4:45, regional "viertel 5" = 4:15.
  private val QUARTER_PAST = rx("""\b(?:viertel\s+nach|quarter\s+past|quarter\s+after)\s+(\d{1,2})\b(?![:.]\d)""")
  private val QUARTER_TO = rx("""\b(?:viertel\s+vor|quarter\s+to|quarter\s+till|quarter\s+of)\s+(\d{1,2})\b(?![:.]\d)""")
  private val THREE_QUARTER = rx("""\b(?:drei\s*viertel|dreiviertel)\s+(\d{1,2})\b(?![:.]\d)""")
  private val QUARTER_REGIONAL = rx("""(?<![a-zäöüß])viertel\s+(\d{1,2})\b(?![:.]\d)""")
  private val NOON = rx("""\b(mittag|mittags|noon|midi|mediodía|mediodia|mezzogiorno|meio-dia|południe|poludnie)\b""")
  private val RANGE_SEP = rx("""^\s*(?:-|–|—|bis|to|till|until|à|a|al|alle|às|ate|até|do)\s*$""")
  // "zwischen 14:00 und 16:00", "between 2pm and 4pm": "und"/"and" joins two times only after "zwischen"/"between".
  private val AND_SEP = rx("""^\s*(?:und|and)\s*$""")
  private val BETWEEN_BEFORE = rx("""\b(?:zwischen|between)\s+$""")

  /**
   * Time zone written right after the time ("17:00 CET", "3pm EST", "10 Uhr MEZ", "18:00 UTC+2"). Upper case only, so
   * words like "west" or "est" never count. Abbreviations stand for their region's local time all year ("CET" is
   * often written in summer too), only UTC/GMT (with an optional offset) are fixed. Ambiguous ones (CST, IST, BST …)
   * are left out on purpose: the time then stays as written.
   */
  private val TZ_AFTER = rx("""^\s*\(?((?:UTC|GMT)\s*[+-]\s*\d{1,2}(?::?\d{2})?|UTC|GMT|CET|CEST|MEZ|MESZ|WET|WEST|WEZ|WESZ|EET|EEST|OEZ|OESZ|EST|EDT|PST|PDT)\b\)?""", ignoreCase = false)
  private val TZ_OFFSET = Regex("""^(?:UTC|GMT)\s*([+-])\s*(\d{1,2})(?::?(\d{2}))?$""")
  private val TZ_REGIONS: Map<String, ZoneId> = buildMap {
    fun put(zone: String, vararg names: String) = names.forEach { put(it, ZoneId.of(zone)) }
    put("UTC", "UTC", "GMT")
    put("Europe/Berlin", "CET", "CEST", "MEZ", "MESZ")
    put("Europe/Lisbon", "WET", "WEST", "WEZ", "WESZ")
    put("Europe/Athens", "EET", "EEST", "OEZ", "OESZ")
    put("America/New_York", "EST", "EDT")
    put("America/Los_Angeles", "PST", "PDT")
  }

  internal fun zoneOf(abbreviation: String): ZoneId? {
    val a = abbreviation.replace(" ", "")
    TZ_OFFSET.matchEntire(a)?.let { m ->
      val h = m.groupValues[2].toInt()
      val min = m.groupValues[3].ifEmpty { "0" }.toInt()
      if (h > 14 || min > 59) return null
      val total = (h * 3600 + min * 60) * if (m.groupValues[1] == "-") -1 else 1
      return ZoneOffset.ofTotalSeconds(total)
    }
    return TZ_REGIONS[a]
  }

  private val EVENING = rx("""\b(abend|abends|evening|tonight|soir|soirée|noche|sera|stasera|noite|wieczór|wieczorem|nachmittag|nachmittags|afternoon|après-midi|apres-midi|tarde|pomeriggio|po południu)\b""")
  private val MORNING = rx("""\b(morgens|früh|frueh|vormittag|vormittags|morning|matin|matinée|por la mañana|de la mañana|mattina|mattino|manhã|de manhã|rano|rana)\b""")
  private val AFTERNOON = rx("""\b(nachmittag|nachmittags|afternoon|après-midi|apres-midi|por la tarde|pomeriggio|à tarde|a tarde|po południu)\b""")

  private const val DAY_WORDS = """montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|sonnabend|monday|tuesday|wednesday|thursday|friday|saturday|sunday|wochenende|weekend"""

  /**
   * Greetings and wishes that contain date words but name no date: "Guten Morgen", "Schönen Abend", "Happy Friday",
   * "Möge Gott uns heute und jeden Tag …". They are blanked out (same length, so spans stay valid) before parsing.
   */
  private val GREETING = rx(
    """(?<![\p{L}])(?:""" +
      """guten\s+(?:morgen|tag|abend)|gute\s+nacht|good\s+(?:morning|afternoon|evening|night)|""" +
      """(?:einen\s+|ein\s+)?(?:schönen|schoenen|schönes|schoenes|gesegneten|gesegnetes|frohen|frohes|angenehmen|entspannten|erholsamen|guten)\s+(?:abend|nachmittag|vormittag|morgen|tag|$DAY_WORDS)(?:\s+(?:noch|euch|dir|allen|zusammen))?|""" +
      """(?:have\s+a\s+)?(?:nice|good|great|lovely|blessed|happy)\s+(?:day|evening|morning|afternoon|$DAY_WORDS)|""" +
      """heute\s+und\s+(?:jeden\s+tag|immer|für\s+immer)|today\s+and\s+(?:every\s+day|always)|""" +
      """buona\s+sera|buongiorno|buenas\s+(?:noches|tardes)|buenos\s+d[ií]as|boa\s+(?:noite|tarde)|bom\s+dia|bonne\s+(?:soirée|soiree|nuit|journée|journee)|dobry\s+wieczór|dzień\s+dobry""" +
      """)(?![\p{L}])""",
  )

  /** "Morgen!" / "Morgen, ihr Lieben" at the start of a line is short for "Guten Morgen". */
  private val MORNING_GREETING = rx("""(?m)^\s*(?:morgen|moin|mahlzeit)\s*(?=[!,.]|$)""")

  internal fun maskGreetings(text: String): String {
    var t = text
    for (r in listOf(GREETING, MORNING_GREETING)) {
      t = r.replace(t) { m -> " ".repeat(m.value.length) }
    }
    return t
  }

  /**
   * [explicitAmPm]: am/pm was written. [explicit24h]: the writing leaves no doubt it is a 24h clock time
   * ("06:45", "6:45 Uhr"), so the afternoon heuristic must not move it.
   */
  private data class TimeHit(val range: IntRange, val time: LocalTime, val explicitAmPm: Boolean, val explicit24h: Boolean = false)

  fun parse(
    raw: String,
    now: LocalDateTime,
    dayFirst: Boolean = true,
    provisionalContextDate: LocalDate? = null,
    /** The phone's time zone: a time written with another zone ("17:00 EST") is converted to it. */
    zone: ZoneId = ZoneId.systemDefault(),
  ): ParsedWhen {
    val text = maskGreetings(raw.replace(' ', ' ')).let { t ->
      if (RESCHEDULE.containsMatchIn(t)) OLD_DATE.replace(t) { m -> " ".repeat(m.value.length) } else t
    }
    val spans = mutableListOf<IntRange>()
    var date: LocalDate? = null
    var endDate: LocalDate? = null
    var dateRange: IntRange? = null
    var vagueTime: LocalTime? = null
    var isProvisionalDay = false
    val today = now.toLocalDate()

    fun setDate(d: LocalDate?, r: IntRange, isProvisional: Boolean = false) {
      if (d != null && date == null) {
        date = d
        dateRange = r
        if (isProvisional) isProvisionalDay = true
      }
      if (d != null) spans += r
    }

    fun fixYear(day: Int, month: Int, year: String?): LocalDate? {
      if (year.isNullOrEmpty()) {
        // Without a year the nearest reading wins: a date of the last weeks stays in the past ("Fotos vom 14.11.",
        // "danke für den 21.11." – the event is then ignored as past), anything older means the coming year
        // (in November "am 3.1." is January next year, in January "am 28.12." the December just gone).
        val earliest = today.minusDays(RECENT_PAST_DAYS)
        return (-1..1).asSequence()
          .mapNotNull { runSafely { LocalDate.of(today.year + it, month, day) }.getOrNull() }
          .firstOrNull { !it.isBefore(earliest) }
      }
      val y = if (year.length == 2) 2000 + year.toInt() else year.toInt()
      return runSafely { LocalDate.of(y, month, day) }.getOrNull()
    }

    // 0) Date ranges (multi-day appointments)
    RANGE_DAY_MONTH.find(text)?.let { m ->
      val mo = MONTHS[m.groupValues[3].lowercase()] ?: return@let
      val y = m.groupValues[4].ifEmpty { null }
      val d1 = fixYear(m.groupValues[1].toInt(), mo, y)
      val d2 = fixYear(m.groupValues[2].toInt(), mo, y)
      if (d1 != null && d2 != null && !d2.isBefore(d1)) {
        setDate(d1, m.range)
        endDate = d2
        spans += m.range
      }
    }
    if (endDate == null) {
      RANGE_MONTH_DAYS.find(text)?.let { m ->
        val mo = MONTHS[m.groupValues[1].lowercase()] ?: return@let
        val y = m.groupValues[4].ifEmpty { null }
        val d1 = fixYear(m.groupValues[2].toInt(), mo, y)
        val d2 = fixYear(m.groupValues[3].toInt(), mo, y)
        if (d1 != null && d2 != null && d2.isAfter(d1)) {
          setDate(d1, m.range)
          endDate = d2
          spans += m.range
        }
      }
    }
    if (endDate == null) {
      RANGE_DOT_DATES.find(text)?.let { m ->
        val d1 = fixYear(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { null })
        val d2 = fixYear(m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].ifEmpty { null })
        if (d1 != null && d2 != null && !d2.isBefore(d1)) {
          setDate(d1, m.range)
          endDate = d2
          spans += m.range
        }
      }
    }
    if (endDate == null) {
      RANGE_SHORT_DOT.find(text)?.let { m ->
        val mo = m.groupValues[3].toInt()
        val y = m.groupValues[4].ifEmpty { null }
        val d1 = fixYear(m.groupValues[1].toInt(), mo, y)
        val d2 = fixYear(m.groupValues[2].toInt(), mo, y)
        if (d1 != null && d2 != null && !d2.isBefore(d1)) {
          setDate(d1, m.range)
          endDate = d2
          spans += m.range
        }
      }
    }
    if (endDate == null) {
      RANGE_WEEKDAY.find(text)?.let { m ->
        val w1 = WEEKDAYS[m.groupValues[1].lowercase()]
        val w2 = WEEKDAYS[m.groupValues[2].lowercase()]
        if (w1 != null && w2 != null) {
          val d1 = today.with(TemporalAdjusters.nextOrSame(w1))
          var d2 = d1.with(TemporalAdjusters.nextOrSame(w2))
          if (d2 == d1) d2 = d2.plusWeeks(1)
          setDate(d1, m.range)
          endDate = d2
          spans += m.range
        }
      }
    }

    // 1) Absolute dates.
    ISO_DATE.find(text)?.let { m ->
      setDate(runSafely { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull(), m.range)
    }
    DAY_MONTH.find(text)?.let { m ->
      val mo = MONTHS[m.groupValues[2].lowercase()] ?: return@let
      setDate(fixYear(m.groupValues[1].toInt(), mo, m.groupValues[3]), m.range)
    }
    MONTH_DAY.find(text)?.let { m ->
      val mo = MONTHS[m.groupValues[1].lowercase()] ?: return@let
      setDate(fixYear(m.groupValues[2].toInt(), mo, m.groupValues[3]), m.range)
    }
    DOT_DATE.find(text)?.let { m ->
      val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
      val resolved = if (d in 1..31 && mo in 1..12) fixYear(d, mo, m.groupValues[3]) else null
      // A two-digit year far in the past ("1.5.15") is almost always a version number, not an appointment.
      if (resolved != null && m.groupValues[3].length == 2 && resolved.year < today.year - 1) return@let
      setDate(resolved, m.range)
    }
    SHORT_DOT_DATE.find(text)?.let { m ->
      val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
      if (d in 1..31 && mo in 1..12) setDate(fixYear(d, mo, null), m.range)
    }
    SLASH_DATE.find(text)?.let { m ->
      var a = m.groupValues[1].toInt(); var b = m.groupValues[2].toInt()
      if (!dayFirst || b > 12) { val t = a; a = b; b = t }
      if (a > 12 && b > 12) return@let
      if (a in 1..31 && b in 1..12) setDate(fixYear(a, b, m.groupValues[3]), m.range)
    }
    for ((r, md) in HOLIDAYS) {
      // The holiday is the event's name ("Silvester feiern wir …"): it stays in the title, so its span is not kept.
      r.find(text)?.let { m -> setDate(fixYear(md.second, md.first, null), m.range); spans.remove(m.range) }
    }

    // 2) Relative dates.
    DAY_AFTER.find(text)?.let { setDate(today.plusDays(2), it.range) }
    TONIGHT.find(text)?.let { setDate(today, it.range); vagueTime = LocalTime.of(19, 0) }
    if (date == null) TOMORROW.find(text)?.let { setDate(today.plusDays(1), it.range) }
    if (date == null) TODAY.find(text)?.let { setDate(today, it.range) }
    if (date == null) {
      DAY_ORDINAL_CURRENT_OR_NEXT.find(text)?.let { m ->
        val day = m.groupValues[1].toIntOrNull()
        if (day != null && day in 1..31) {
          val targetDate = if (provisionalContextDate != null) {
            val maxDay = provisionalContextDate.lengthOfMonth()
            provisionalContextDate.withDayOfMonth(minOf(day, maxDay))
          } else if (today.dayOfMonth <= day) {
            val maxDay = today.lengthOfMonth()
            today.withDayOfMonth(minOf(day, maxDay))
          } else {
            val nextMonth = today.plusMonths(1)
            val maxDay = nextMonth.lengthOfMonth()
            nextMonth.withDayOfMonth(minOf(day, maxDay))
          }
          setDate(targetDate, m.range)
        }
      }
    }
    // Lone month without day: Fallback to 1st of month + Jahreswechsel-Logik + isProvisionalDay = true
    if (date == null) {
      MONTH_ONLY.find(text)?.let { m ->
        val mo = MONTHS[m.groupValues[1].lowercase()]
        if (mo != null) {
          val explicitYear = m.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull()
          val resolvedYear = when {
            explicitYear != null -> explicitYear
            mo < today.monthValue -> today.year + 1
            else -> today.year
          }
          val targetDate = LocalDate.of(resolvedYear, mo, 1)
          setDate(targetDate, m.range, isProvisional = true)
        }
      }
    }
    var weekdayHitDate: LocalDate? = null
    // A plain weekday that falls on today ("Thursday 2pm" said on Thursday afternoon): moved a week on below when the
    // time has passed already.
    var weekdayMayBeNextWeek = false
    WEEKDAY.findAll(text).firstOrNull { m ->
      // "mar"/"sat"/"sun" etc. are only accepted as weekday when written as a standalone token.
      val w = m.groupValues[2].lowercase()
      w.length > 3 || m.groupValues[1].isNotEmpty()
    }?.let { m ->
      val dow = WEEKDAYS[m.groupValues[2].lowercase()] ?: return@let
      val prefix = m.groupValues[1].lowercase()
      val next = prefix.isNotEmpty() && prefix !in setOf("this", "diesen", "ce", "este", "questo", "esta", "w", "we") && prefix !in PAST_WEEKDAY_PREFIX
      var d = today.with(TemporalAdjusters.nextOrSame(dow))
      if (d == today && next) d = d.plusWeeks(1)
      // "letzten Freitag", "last Monday": the day before today, a look back (ignored as past later on).
      if (prefix in PAST_WEEKDAY_PREFIX) d = today.with(TemporalAdjusters.previous(dow))
      if (prefix.isEmpty() && d == today) weekdayMayBeNextWeek = true
      var range = m.range
      // "viernes 27": the weekday names the 27th, taken when that day really is such a weekday this or next month.
      val tail = WEEKDAY_DAY_TAIL.toPattern().matcher(text).region(m.range.last + 1, text.length)
      if (tail.lookingAt()) {
        val day = tail.group(1).toInt()
        val match = (0L..1L).asSequence()
          .map { today.withDayOfMonth(1).plusMonths(it) }
          .filter { day <= it.lengthOfMonth() }
          .map { it.withDayOfMonth(day) }
          .firstOrNull { !it.isBefore(today) && it.dayOfWeek == dow }
        if (match != null) { d = match; range = m.range.first until tail.end() }
      }
      weekdayHitDate = d
      setDate(d, range)
    }
    if (weekdayHitDate == null) {
      WEEKDAY_ABBR_DE.findAll(text).firstOrNull { m ->
        // "So" is also "so" at the start of a sentence: only "So." or "So 6.12." count.
        m.groupValues[1] != "So" || m.groupValues[2] == "." || m.groupValues[3].endsWith(".")
      }?.let { m ->
        val dow = ABBR_DAYS.getValue(m.groupValues[1])
        val d = today.with(TemporalAdjusters.nextOrSame(dow))
        if (d == today) weekdayMayBeNextWeek = true
        weekdayHitDate = d
        setDate(d, m.range)
      }
    }
    IN_N.find(text)?.let { m ->
      val n = m.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[m.groupValues[1].lowercase()] ?: return@let
      val unit = m.groupValues[2].lowercase()
      when {
        listOf("week", "woche", "sem", "sett", "tyg", "tydz").any { unit.startsWith(it) } -> {
          val base = weekdayHitDate ?: date ?: today
          date = base.plusWeeks(n.toLong())
          spans += m.range
        }
        unit.startsWith("st") || unit.startsWith("hour") || unit.startsWith("heure") || unit.startsWith("hora") || unit.startsWith("ora") || unit == "ore" || unit.startsWith("godz") -> {
          val t = now.plusHours(n.toLong())
          setDate(t.toLocalDate(), m.range); vagueTime = vagueTime ?: t.toLocalTime().withSecond(0).withNano(0)
        }
        unit.startsWith("min") -> {
          val t = now.plusMinutes(n.toLong())
          setDate(t.toLocalDate(), m.range); vagueTime = vagueTime ?: t.toLocalTime().withSecond(0).withNano(0)
        }
        else -> {
          val base = weekdayHitDate ?: date ?: today
          date = base.plusDays(n.toLong())
          spans += m.range
        }
      }
    }
    NEXT_WEEK.find(text)?.let { m ->
      val hit = weekdayHitDate
      if (hit != null && date == hit) {
        // "nächste Woche Dienstag": the weekday of next week, not the next occurrence of that weekday.
        val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        date = nextMonday.with(TemporalAdjusters.nextOrSame(hit.dayOfWeek))
        spans += m.range
      } else {
        setDate(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)), m.range)
      }
    }
    WEEKEND.find(text)?.let { setDate(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)), it.range) }

    // 3) Times.
    // A duration ("Dauer 90 Minuten", "for 30 min", "(2 Std.)", "für 2h") is no clock time.
    var durationMinutes: Int? = null
    var durationRange: IntRange? = null
    DURATION.find(text)?.let { m ->
      val n = m.groupValues[1].lowercase()
      val amount = when (n) {
        "ein", "eine", "einer", "an", "one" -> 1.0
        "zwei", "two" -> 2.0
        "drei", "three" -> 3.0
        "anderthalb", "eineinhalb" -> 1.5
        else -> n.replace(',', '.').toDoubleOrNull()
      } ?: return@let
      val unit = m.groupValues[2].lowercase()
      val minutes = if (unit.startsWith("min")) amount else amount * 60
      if (minutes >= 5 && minutes <= 24 * 60) {
        durationMinutes = minutes.toInt()
        durationRange = m.range
        spans += m.range
      }
    }
    val hits = mutableListOf<TimeHit>()
    fun addHit(r: IntRange, h: Int, m: Int, marker: String?, explicit24h: Boolean = false) {
      durationRange?.let { d -> if (d.first <= r.last && r.first <= d.last) return }
      var hour = h
      val mk = marker?.lowercase()?.replace(".", "")
      val pm = mk == "pm"; val am = mk == "am"
      if (pm && hour < 12) hour += 12
      if (am && hour == 12) hour = 0
      if (hour !in 0..23 || m !in 0..59) return
      if (hits.any { it.range.first <= r.last && r.first <= it.range.last }) return
      hits += TimeHit(r, LocalTime.of(hour, m), pm || am, explicit24h)
    }
    // Half/quarter phrases first, so "halb 4 Uhr" is not taken as 4:00 by the marked-hour pattern.
    HALF_PAST_DE.findAll(text).forEach { m ->
      val h = m.groupValues[1].toInt()
      if (h in 1..24) addHit(m.range, (h - 1 + 24) % 24, 30, null)
    }
    QUARTER_PAST.findAll(text).forEach { m ->
      val h = m.groupValues[1].toInt()
      if (h in 0..24) addHit(m.range, h % 24, 15, null)
    }
    QUARTER_TO.findAll(text).forEach { m ->
      val h = m.groupValues[1].toInt()
      if (h in 1..24) addHit(m.range, (h - 1 + 24) % 24, 45, null)
    }
    THREE_QUARTER.findAll(text).forEach { m ->
      val h = m.groupValues[1].toInt()
      if (h in 1..24) addHit(m.range, (h - 1 + 24) % 24, 45, null)
    }
    QUARTER_REGIONAL.findAll(text).forEach { m ->
      val h = m.groupValues[1].toInt()
      if (h in 1..24) addHit(m.range, (h - 1 + 24) % 24, 15, null)
    }
    CLOCK.findAll(text).forEach {
      val marker = it.groupValues[3].ifEmpty { null }
      // "06:45" (leading zero) or "6:45 Uhr" is an unambiguous 24h clock time.
      val strict = it.groupValues[1].length == 2 && it.groupValues[1].startsWith("0") ||
        marker?.lowercase() == "uhr" || marker?.lowercase() == "h" ||
        (marker == null && UM_BEFORE.toPattern().matcher(text).region(maxOf(0, it.range.first - 4), it.range.first).useTransparentBounds(true).find())
      addHit(it.range, it.groupValues[1].toInt(), it.groupValues[2].toInt(), marker, explicit24h = strict)
    }
    DOT_CLOCK.findAll(text).forEach { addHit(it.range, it.groupValues[1].toInt(), it.groupValues[2].toInt(), null, explicit24h = true) }
    H_CLOCK.findAll(text).forEach { addHit(it.range, it.groupValues[1].toInt(), it.groupValues[2].ifEmpty { "0" }.toInt(), null) }
    HOUR_TO_CLOCK.findAll(text).forEach { m ->
      val g = m.groups[1]!!
      if (spans.none { it.first <= g.range.last && g.range.first <= it.last }) addHit(g.range, g.value.toInt(), 0, null)
    }
    HOUR_RANGE.findAll(text).forEach { m ->
      val start = m.groups[1] ?: m.groups[2]!!
      // "10-11am": the marker belongs to both hours; "11-1pm" starts in the morning.
      val endMarker = m.groups[4]?.value?.lowercase()?.let { it + "m" }
      val startMarker = endMarker?.let { if (start.value.toInt() % 12 > m.groupValues[3].toInt() % 12) (if (it == "pm") "am" else "pm") else it }
      addHit(start.range, start.value.toInt(), 0, startMarker)
      addHit(m.groups[3]!!.range.first..m.range.last, m.groupValues[3].toInt(), 0, endMarker)
    }
    MARKED_HOUR.findAll(text).forEach { addHit(it.range, it.groupValues[1].toInt(), 0, it.groupValues[2]) }
    PREP_HOUR.findAll(text).forEach { m ->
      val h = m.groupValues[1].toInt()
      // A date like "am 12." is caught by the date patterns; skip numbers that belong to one.
      if (spans.none { it.first <= m.range.last && m.range.first <= it.last }) addHit(m.range, h, 0, null)
    }
    if (hits.isEmpty() && date != null) {
      AB_HOUR.find(text)?.let { m -> if (spans.none { it.first <= m.range.last && m.range.first <= it.last }) addHit(m.range, m.groupValues[1].toInt(), 0, null) }
    }
    if (hits.isEmpty()) NOON.find(text)?.let { addHit(it.range, 12, 0, null) }
    hits.sortBy { it.range.first }

    val evening = EVENING.containsMatchIn(text)
    val morning = MORNING.containsMatchIn(text)
    fun adjust(hit: TimeHit): LocalTime {
      val t = hit.time
      if (hit.explicitAmPm) return t
      if (evening && t.hour in 1..11) return t.plusHours(12)
      // "6:45 Uhr", "06:45" or "morgen früh um 7" are morning times and stay as written.
      if (hit.explicit24h || morning) return t
      // Chats rarely mean 1-7 o'clock at night; a bare "um 5" means the afternoon.
      if (t.hour in 1..7) return t.plusHours(12)
      return t
    }

    var time: LocalTime? = null
    var endTime: LocalTime? = null
    var timeEnd = -1
    if (hits.isNotEmpty()) {
      val first = hits.first()
      time = adjust(first)
      spans += first.range
      timeEnd = first.range.last
      val second = hits.getOrNull(1)
      if (second != null) {
        val between = text.substring(first.range.last + 1, second.range.first)
        val joinedByAnd = AND_SEP.matches(between) &&
          BETWEEN_BEFORE.containsMatchIn(text.substring(maxOf(0, first.range.first - 12), first.range.first))
        if (RANGE_SEP.matches(between) || joinedByAnd) {
          timeEnd = second.range.last
          var e = adjust(second)
          if (second.explicitAmPm.not() && e.isBefore(time) && e.plusHours(12).isAfter(time)) e = e.plusHours(12)
          endTime = e
          // One span for the whole range, so a title loses "14:00–15:00" and not just the two numbers.
          spans.remove(first.range)
          spans += first.range.first..second.range.last
        }
      }
    }

    var vague = false
    if (time == null) {
      when {
        vagueTime != null -> { time = vagueTime; vague = true }
        AFTERNOON.containsMatchIn(text) && date != null -> { time = LocalTime.of(15, 0); vague = true }
        EVENING.containsMatchIn(text) && date != null -> { time = LocalTime.of(19, 0); vague = true }
        MORNING.containsMatchIn(text) && date != null -> { time = LocalTime.of(9, 0); vague = true }
      }
    }

    // "17:00 EST": converted to the phone's zone (the day may change, too). Without a written date only the clock
    // time moves; the day is then chosen below as for any lone time.
    if (time != null && !vague && timeEnd >= 0) {
      val tz = TZ_AFTER.find(text.substring(timeEnd + 1))
      val from = tz?.let { zoneOf(it.groupValues[1]) }
      if (tz != null && from != null && from.normalized() != zone.normalized()) {
        val base = date ?: today
        val local = LocalDateTime.of(base, time).atZone(from).withZoneSameInstant(zone).toLocalDateTime()
        val shift = ChronoUnit.DAYS.between(base, local.toLocalDate())
        endTime = endTime?.let { LocalDateTime.of(base, it).atZone(from).withZoneSameInstant(zone).toLocalTime() }
        time = local.toLocalTime()
        if (date != null) {
          date = local.toLocalDate()
          endDate = endDate?.plusDays(shift)
        }
      }
      if (tz != null && from != null) spans += (timeEnd + 1 + tz.range.first)..(timeEnd + 1 + tz.range.last)
    }

    // "Thursday 2pm" written on Thursday at 14:30 means next week's Thursday.
    if (weekdayMayBeNextWeek && date == today && time != null && time.isBefore(now.toLocalTime())) {
      date = today.plusWeeks(1)
      endDate = endDate?.plusWeeks(1)
    }

    val dateExplicit = date != null
    if (date == null && time != null && !vague) {
      // Only a clock time: today if it is still ahead, otherwise tomorrow.
      date = if (time.isAfter(now.toLocalTime())) today else today.plusDays(1)
    }

    val deadline = dateRange?.let { r ->
      val before = text.substring(maxOf(0, r.first - 14), r.first)
      DEADLINE.containsMatchIn(before)
    } ?: false

    return ParsedWhen(
      date = date,
      endDate = endDate,
      time = time,
      endTime = endTime,
      dateExplicit = dateExplicit,
      vagueTime = vague,
      deadline = deadline,
      spans = spans.sortedBy { it.first },
      isProvisionalDay = isProvisionalDay,
      durationMinutes = durationMinutes,
    )
  }

  /** How far back a date without a year may lie and still be read as this year's (past) date. */
  private const val RECENT_PAST_DAYS = 60L
}
