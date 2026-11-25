package de.juliandigitalproducts.notitocal.output

import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Decides whether a calendar event already exists and which empty fields may be filled,
 * and provides intelligent multi-event duplicate clustering, data quality scoring, and merge proposals.
 * Pure Kotlin (no Android framework dependencies) so it can be unit-tested directly.
 */
object DuplicateMatcher {
  /** About two hours either side of the start for live ingestion deduplication. */
  const val WINDOW_MILLIS = 2 * 60 * 60 * 1000L

  /** Extended window (6 hours) for multi-event duplicate finder on timed events. */
  const val FINDER_WINDOW_MILLIS = 6 * 60 * 60 * 1000L

  /** Same numeric value as CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR. */
  const val ACCESS_CONTRIBUTOR = 500

  const val THRESHOLD = 0.66

  /** Separates an appended block from text that was already on the event. */
  const val MARKER = "—— NotiToCal ——"

  const val MERGED_MESSAGE = "merged with existing event"

  /** Categories for found duplicate clusters. */
  @Serializable
  enum class DuplicateCategory {
    /** High confidence (>= 0.80): safe to merge directly. */
    CERTAIN,
    /** Medium-high confidence (0.62 ..< 0.80): likely duplicates, quick review recommended. */
    PROBABLE,
    /** Lower confidence (minThreshold ..< 0.62): manual review or AI verification recommended. */
    UNSURE,
  }

  /** How non-primary duplicate events are handled when merging. */
  @Serializable
  enum class RemovalMode {
    DELETE,
    MARK_DUPLICATE,
  }

  /** Rich representation of an existing calendar event for the Duplicate Finder & Undo store. */
  @Serializable
  data class CalendarEventItem(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String = "",
    val calendarAccount: String = "",
    val calendarColor: Int = 0xFF1976D2.toInt(),
    val accessLevel: Int = 700,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startMillis: Long,
    val endMillis: Long = startMillis + 60 * 60 * 1000L,
    val allDay: Boolean = false,
    val timezone: String = "UTC",
    val attendees: List<String> = emptyList(),
    val reminderMinutes: List<Int> = emptyList(),
    val rrule: String = "",
  ) {
    val isWritable: Boolean get() = accessLevel >= ACCESS_CONTRIBUTOR

    /** Local calendar date of this event (respects UTC midnight storage for allDay events). */
    fun localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
      if (allDay) Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate()
      else Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
  }

  /** Reason tag explaining why two or more events were matched as duplicates. */
  @Serializable
  data class MatchReason(
    val code: String,
    val detail: String = "",
  )

  /** AI recommendation attached to a cluster when AI verification runs. */
  @Serializable
  data class AiDuplicateAdvice(
    val isDuplicate: Boolean,
    val confidence: Double,
    val explanation: String,
    val recommendedPrimaryId: Long? = null,
    val recommendedTitle: String? = null,
    val recommendedLocation: String? = null,
    val recommendedDescription: String? = null,
  )

  /** Proposed merged state for a duplicate cluster. */
  @Serializable
  data class MergeProposal(
    val primaryEventId: Long,
    val targetCalendarId: Long,
    val targetCalendarName: String,
    val title: String,
    val location: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val attendees: List<String>,
    val reminderMinutes: List<Int>,
    /** Codes "title|…", "time", "location|…", "description", "attendees|n", "reminders|…"; the UI turns them into text. */
    val adoptedFieldsSummary: List<String> = emptyList(),
  )

  /** A cluster of 2 or more calendar events that likely describe the same appointment. */
  @Serializable
  data class DuplicateCluster(
    val id: String,
    val events: List<CalendarEventItem>,
    val confidence: Double,
    val category: DuplicateCategory,
    val reasons: List<MatchReason>,
    val defaultPrimaryId: Long,
    val proposal: MergeProposal,
    val aiAdvice: AiDuplicateAdvice? = null,
  )

  /** Record saved in local 30-day undo history when a cluster is merged. */
  @Serializable
  data class MergeHistoryRecord(
    val id: String,
    val timestamp: Long,
    val clusterId: String,
    val primaryBefore: CalendarEventItem,
    val primaryAfter: CalendarEventItem,
    val removedEvents: List<CalendarEventItem>,
    val removalMode: RemovalMode,
    val summary: String,
    val undone: Boolean = false,
  )

  // ---------- Existing Live Ingestion Deduplication API ----------

  data class Candidate(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val description: String,
    val location: String,
    val startMillis: Long,
    val accessLevel: Int,
  )

  data class Proposed(
    val title: String,
    val description: String,
    val location: String,
    val startMillis: Long,
  )

  enum class Action { CREATE, MERGE, SKIP_READONLY }

  data class Outcome(
    val action: Action,
    val candidate: Candidate? = null,
    val score: Double = 0.0,
    /** Non-null only when the existing location is empty and a value should be written. */
    val locationUpdate: String? = null,
    /** Non-null only when the description should be written. Existing text is always kept as a prefix. */
    val descriptionUpdate: String? = null,
  )

  fun evaluate(proposed: Proposed, candidates: List<Candidate>, threshold: Double = THRESHOLD): Outcome {
    val best = candidates
      .map { it to score(proposed, it) }
      .filter { it.second >= threshold }
      .maxByOrNull { it.second }
      ?: return Outcome(Action.CREATE)
    val candidate = best.first
    if (candidate.accessLevel < ACCESS_CONTRIBUTOR) {
      return Outcome(Action.SKIP_READONLY, candidate, best.second)
    }
    val (location, description) = merge(candidate, proposed)
    return Outcome(Action.MERGE, candidate, best.second, location, description)
  }

  fun score(proposed: Proposed, candidate: Candidate): Double {
    val delta = abs(proposed.startMillis - candidate.startMillis)
    if (delta > WINDOW_MILLIS) return 0.0

    val otherContext = listOf(proposed.title, proposed.location, candidate.title, candidate.location)
    val (coreProposed, embeddedLocProposed) = extractEmbeddedLocation(proposed.title, otherContext)
    val (coreCandidate, embeddedLocCandidate) = extractEmbeddedLocation(candidate.title, otherContext)

    val effProposedLoc = proposed.location.ifBlank { embeddedLocProposed.orEmpty() }
    val effCandidateLoc = candidate.location.ifBlank { embeddedLocCandidate.orEmpty() }

    val rawTitleScore = similarity(proposed.title, candidate.title)
    val coreTitleScore = similarity(coreProposed, coreCandidate)
    val titleScore = maxOf(rawTitleScore, coreTitleScore)

    val locationScore = when {
      effProposedLoc.isBlank() && effCandidateLoc.isBlank() -> 0.6
      effProposedLoc.isBlank() || effCandidateLoc.isBlank() -> 0.45
      else -> locationSimilarity(effProposedLoc, effCandidateLoc)
    }

    // If locations match strongly (>= 0.75) and times match within 30 min, allow lower title threshold
    val minTitleThreshold = if (delta <= 30 * 60 * 1000L && locationScore >= 0.75) 0.38 else 0.48
    if (titleScore < minTitleThreshold) return 0.0

    val timeScore = 1.0 - delta.toDouble() / WINDOW_MILLIS
    return 0.25 * timeScore + 0.60 * titleScore + 0.15 * locationScore
  }

  /** Strips any previous NotiToCal markers and system/debug logs from [raw]. */
  fun stripMarkerAndSystem(raw: String): String {
    var s = raw.substringBefore(MARKER)
    s = s.substringBefore("-- NotiToCal --")
    s = s.substringBefore("—— NotiToCal ——")
    s = de.juliandigitalproducts.notitocal.analysis.RuleEngine.stripDebugAndSystemText(s)
    return s.trim()
  }

  /**
   * Fills only empty location and description. A description that already has text is kept in full
   * and the new details are appended after [MARKER] (unless already present).
   */
  fun merge(existing: Candidate, incoming: Proposed): Pair<String?, String?> {
    val cleanLoc = incoming.location.trim()
    val location = cleanLoc.takeIf { it.isNotEmpty() && existing.location.isBlank() }
    val cleanDesc = stripMarkerAndSystem(incoming.description)
    val cleanExistingDesc = stripMarkerAndSystem(existing.description)
    val cleanIncoming = incoming.copy(description = cleanDesc, location = cleanLoc)
    val description = when {
      cleanExistingDesc.isBlank() -> cleanDesc.takeIf { it.isNotEmpty() }
      else -> {
        val normExisting = normalize(cleanExistingDesc)
        val normDesc = normalize(cleanDesc)
        val descAlreadyPresent = normDesc.isEmpty() || normExisting.contains(normDesc)
        val locAlreadyPresent = cleanLoc.isEmpty() || existing.location.isBlank() ||
          existing.location.equals(cleanLoc, ignoreCase = true) || normExisting.contains(normalize(cleanLoc))
        if (descAlreadyPresent && locAlreadyPresent) null
        else {
          val alreadyHasMarker = existing.description.contains(MARKER) || existing.description.contains("-- NotiToCal --")
          val block = if (alreadyHasMarker) {
            buildString {
              append('\n')
              if (cleanIncoming.title.isNotBlank() && !normExisting.contains(normalize(cleanIncoming.title))) append(cleanIncoming.title.trim()).append('\n')
              if (cleanIncoming.location.isNotBlank() && !locAlreadyPresent) append(cleanIncoming.location.trim()).append('\n')
              if (cleanIncoming.description.isNotBlank() && !descAlreadyPresent) append(cleanIncoming.description.trim())
            }.trimEnd()
          } else {
            markedBlock(cleanIncoming)
          }
          if (block.isBlank()) null else existing.description.trimEnd() + "\n\n" + block.trimStart()
        }
      }
    }
    return location to description
  }

  fun markedBlock(incoming: Proposed): String = buildString {
    append("\n\n")
    append(MARKER)
    append('\n')
    if (incoming.title.isNotBlank()) append(incoming.title.trim()).append('\n')
    if (incoming.location.isNotBlank()) append(incoming.location.trim()).append('\n')
    if (incoming.description.isNotBlank()) append(incoming.description.trim())
  }.trimEnd()

  fun normalize(raw: String): String =
    raw.lowercase()
      .map { ch -> if (ch.isLetterOrDigit()) ch else ' ' }
      .joinToString("")
      .replace(Regex(" +"), " ")
      .trim()

  // ---------- Intelligent Normalization, Stemming & Abbreviation Matching ----------

  /** Common city and place abbreviations (German & international). */
  private val CITY_ABBREVIATIONS: Map<String, String> = mapOf(
    "lev" to "leverkusen",
    "k" to "koeln",
    "cgn" to "koeln",
    "ddorf" to "duesseldorf",
    "dus" to "duesseldorf",
    "ffm" to "frankfurt",
    "fra" to "frankfurt",
    "muc" to "muenchen",
    "hh" to "hamburg",
    "ber" to "berlin",
    "stgt" to "stuttgart",
    "str" to "stuttgart",
    "nbg" to "nuernberg",
    "han" to "hannover",
    "hb" to "bremen",
    "do" to "dortmund",
    "bo" to "bochum",
    "du" to "duisburg",
    "bn" to "bonn",
    "ac" to "aachen",
    "ms" to "muenster",
    "bi" to "bielefeld",
    "wi" to "wiesbaden",
    "mz" to "mainz",
    "ka" to "karlsruhe",
    "ma" to "mannheim",
    "hd" to "heidelberg",
    "fr" to "freiburg",
    "aux" to "augsburg",
    "wob" to "wolfsburg",
    "hbf" to "hauptbahnhof",
    "bhf" to "bahnhof",
    "flugh" to "flughafen",
    "str" to "strasse",
    "pl" to "platz",
    "homeoffice" to "home office",
    "ho" to "home office",
  )

  /** Common generic suffixes in German/English appointment titles that carry no differentiating meaning. */
  private val GENERIC_COMPOUND_SUFFIXES = listOf(
    "besuch", "termin", "treffen", "meeting", "call", "konferenz", "sitzung", "runde", "einladung", "feier", "party",
  )

  /** Stopwords ignored when extracting core subject tokens. */
  private val STOPWORDS = setOf(
    "am", "im", "in", "an", "um", "bei", "mit", "zu", "zum", "zur", "von", "vom", "fuer", "fur", "und", "oder",
    "der", "die", "das", "den", "dem", "ein", "eine", "einer", "einem", "at", "on", "in", "with", "to", "for", "the", "a",
  )

  /** Normalizes umlauts and German Fugen-s (e.g. "mittagsessen" -> "mittagessen", "geburtstagsfeier" -> "geburtstag"). */
  fun canonicalToken(token: String): String {
    var t = token.lowercase().trim()
      .replace("ä", "ae")
      .replace("ö", "oe")
      .replace("ü", "ue")
      .replace("ß", "ss")
    CITY_ABBREVIATIONS[t]?.let { return it }
    // Strip common compound suffixes if the remaining stem has at least 5 characters (e.g. "mittagessenbesuch" -> "mittagessen")
    for (suffix in GENERIC_COMPOUND_SUFFIXES) {
      if (t.length >= suffix.length + 5 && t.endsWith(suffix)) {
        t = t.removeSuffix(suffix)
        // Strip linking 's' or 'n' at the seam (e.g. "geburtstagsfeier" -> "geburtstags" -> "geburtstag")
        if (t.endsWith("s") && t.length >= 6) t = t.dropLast(1)
        break
      }
    }
    // Normalize internal German Fugen-s in common compounds: "mittagsessen" -> "mittagessen", "abendessen"
    t = t.replace("mittagsessen", "mittagessen")
      .replace("abendsessen", "abendessen")
      .replace("fruehstuecks", "fruehstueck")
      .replace("geburtstags", "geburtstag")
      .replace("hochzeits", "hochzeit")
      .replace("zahnarzts", "zahnarzt")
      .replace("arzts", "arzt")
    return t
  }

  /** Checks whether two tokens match via abbreviation, prefix, stem, or small typo. */
  fun tokenSimilarity(rawA: String, rawB: String): Double {
    val a = canonicalToken(rawA)
    val b = canonicalToken(rawB)
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    // Direct abbreviation lookup
    if (CITY_ABBREVIATIONS[rawA.lowercase()] == b || CITY_ABBREVIATIONS[rawB.lowercase()] == a) return 0.95
    // Prefix abbreviation (e.g. "lev" <-> "leverkusen", "zahnarzt" <-> "zahnarztpraxis")
    val shorter = if (a.length <= b.length) a else b
    val longer = if (a.length <= b.length) b else a
    if (shorter.length >= 3 && longer.length >= 5 && longer.startsWith(shorter)) {
      return if (shorter.length >= 5) 0.92 else 0.86
    }
    // Compound inclusion (e.g. "mittagessen" inside "familienmittagessen")
    if (shorter.length >= 6 && longer.contains(shorter)) {
      return 0.88
    }
    // Levenshtein for typos ("mittagessen" vs "mittagsessen", "leverkusen" vs "leverkuusen")
    val dist = levenshtein(a, b)
    val maxLen = max(a.length, b.length)
    val levSim = 1.0 - dist.toDouble() / maxLen
    if (maxLen >= 5 && dist <= 2) return maxOf(levSim, 0.84)
    return levSim
  }

  /** Location-aware similarity that understands abbreviations like "Lev" <-> "Leverkusen". */
  fun locationSimilarity(locA: String, locB: String): Double {
    val tokensA = normalize(locA).split(' ').filter { it.isNotBlank() && it !in STOPWORDS }
    val tokensB = normalize(locB).split(' ').filter { it.isNotBlank() && it !in STOPWORDS }
    if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
    var total = 0.0
    for (ta in tokensA) {
      val best = tokensB.maxOfOrNull { tb -> tokenSimilarity(ta, tb) } ?: 0.0
      total += best
    }
    for (tb in tokensB) {
      val best = tokensA.maxOfOrNull { ta -> tokenSimilarity(tb, ta) } ?: 0.0
      total += best
    }
    return (total / (tokensA.size + tokensB.size)).coerceIn(0.0, 1.0)
  }

  fun similarity(a: String, b: String): Double {
    val na = normalize(a)
    val nb = normalize(b)
    if (na.isEmpty() && nb.isEmpty()) return 1.0
    if (na.isEmpty() || nb.isEmpty()) return 0.0
    val ca = na.split(' ').filter { it.isNotBlank() }.joinToString(" ") { canonicalToken(it) }
    val cb = nb.split(' ').filter { it.isNotBlank() }.joinToString(" ") { canonicalToken(it) }
    val levenshtein = 1.0 - levenshtein(ca, cb).toDouble() / maxOf(ca.length, cb.length)
    val tokensA = na.split(' ').filter { it.isNotBlank() && it !in STOPWORDS }
    val tokensB = nb.split(' ').filter { it.isNotBlank() && it !in STOPWORDS }
    if (tokensA.isEmpty() || tokensB.isEmpty()) return levenshtein

    // Pairwise token similarity (handles stems, typos, Fugen-s, and abbreviations)
    val matchedA = tokensA.map { ta -> tokensB.maxOfOrNull { tb -> tokenSimilarity(ta, tb) } ?: 0.0 }
    val matchedB = tokensB.map { tb -> tokensA.maxOfOrNull { ta -> tokenSimilarity(tb, ta) } ?: 0.0 }
    val avgPairwise = (matchedA.sum() + matchedB.sum()) / (tokensA.size + tokensB.size)

    // Strongest core-token match (when one title has an extra place name like "Mittagessen Leverkusen" vs "Mittagessenbesuch")
    val bestTokenMatch = matchedA.maxOrNull() ?: 0.0
    val coreSubjectScore = if (bestTokenMatch >= 0.85 && min(tokensA.size, tokensB.size) == 1 && max(tokensA.size, tokensB.size) <= 3) {
      bestTokenMatch * 0.88
    } else 0.0

    return maxOf(levenshtein, avgPairwise, coreSubjectScore).coerceIn(0.0, 1.0)
  }

  /**
   * Extracts a possible location embedded in an event title (e.g. "Mittagessen Leverkusen" -> "Leverkusen",
   * "Mittagsessen Lev" -> "Lev") by checking known places/abbreviations or trailing capitalized/place tokens.
   */
  fun extractEmbeddedLocation(title: String, otherTitlesAndLocations: List<String> = emptyList()): Pair<String, String?> {
    val rawWords = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (rawWords.size < 2) return title.trim() to null

    val lastWordClean = rawWords.last().trim { !it.isLetterOrDigit() }
    val lastLower = lastWordClean.lowercase()
    if (lastLower.isBlank()) return title.trim() to null

    // 1. Direct match in known city abbreviations or their full names
    val isKnownCityOrAbbr = lastLower in CITY_ABBREVIATIONS.keys ||
      lastLower in CITY_ABBREVIATIONS.values ||
      canonicalToken(lastLower) in CITY_ABBREVIATIONS.values

    // 2. Or matches a location/trailing token in another candidate event (e.g. "Leverkusen" <-> "Lev")
    val matchesOtherPlace = otherTitlesAndLocations.any { other ->
      val otherTokens = normalize(other).split(' ').filter { it.isNotBlank() }
      otherTokens.any { ot ->
        ot != lastLower && tokenSimilarity(lastLower, ot) >= 0.85 &&
          (ot in CITY_ABBREVIATIONS.keys || canonicalToken(ot) in CITY_ABBREVIATIONS.values || lastLower.length <= 4 || ot.length <= 4)
      }
    }

    // 3. Or preceded by "in", "bei", " @ "
    val secondLastLower = rawWords[rawWords.size - 2].lowercase()
    val hasLocPreposition = secondLastLower in setOf("in", "im", "bei", "nach", "@")

    if (isKnownCityOrAbbr || matchesOtherPlace || hasLocPreposition) {
      val coreWords = if (hasLocPreposition && rawWords.size >= 3) rawWords.dropLast(2) else rawWords.dropLast(1)
      return coreWords.joinToString(" ").trim() to lastWordClean
    }
    return title.trim() to null
  }

  /** Expands known city abbreviations to their full form when possible (e.g. "Lev" -> "Leverkusen"). */
  fun expandLocationIfAbbreviated(loc: String, allCandidateLocations: List<String>): String {
    val clean = loc.trim()
    if (clean.isEmpty()) return ""
    val lower = clean.lowercase()
    // If another candidate has a longer location that matches this abbreviation, use that exact string
    val longerFromCandidates = allCandidateLocations
      .map { it.trim() }
      .filter { it.length > clean.length && locationSimilarity(clean, it) >= 0.85 }
      .maxByOrNull { it.length }
    if (longerFromCandidates != null) return longerFromCandidates
    return clean
  }

  // ---------- Data Quality Scoring ----------

  /**
   * Computes a data quality score (0..100) for an event so the most complete, concrete event
   * is automatically chosen as the primary event when merging.
   */
  fun dataQualityScore(event: CalendarEventItem): Int {
    var score = 0
    // 1. Time precision: concrete timed event beats all-day event
    if (!event.allDay) {
      score += 30
      val durationMin = (event.endMillis - event.startMillis) / 60_000L
      if (durationMin in 15..480) score += 5
    } else {
      score += 10
    }
    // 2. Location completeness
    val loc = event.location.trim()
    when {
      loc.length >= 6 -> score += 20
      loc.isNotEmpty() -> score += 10
      else -> {
        val (_, embedded) = extractEmbeddedLocation(event.title)
        if (embedded != null) score += 6
      }
    }
    // 3. Description / notes quality
    val desc = event.description.substringBefore(MARKER).trim()
    if (desc.length >= 20) score += 18
    else if (desc.isNotEmpty()) score += 10
    // 4. Attendees & reminders
    if (event.attendees.isNotEmpty()) score += min(10, event.attendees.size * 4)
    if (event.reminderMinutes.isNotEmpty()) score += 7
    // 5. Title quality
    val titleLen = event.title.trim().length
    if (RuleEngine.isCrypticMessage(event.title)) score -= 15
    else if (titleLen in 6..60) score += 10 else if (titleLen > 0) score += 5
    // 6. Calendar writability
    if (event.isWritable) score += 15
    return score.coerceIn(0, 100)
  }

  // ---------- Pairwise Event Comparison & Clustering ----------

  data class PairAnalysis(
    val score: Double,
    val reasons: List<MatchReason>,
  )

  /**
   * Compares two calendar events and returns their duplicate confidence (0..1) and human-readable reasons,
   * or null if they are not on the same/overlapping day or clearly unrelated.
   */
  fun compareEvents(
    a: CalendarEventItem,
    b: CalendarEventItem,
    crossCalendar: Boolean = true,
    zone: ZoneId = ZoneId.systemDefault(),
  ): PairAnalysis? {
    if (a.eventId == b.eventId && a.calendarId == b.calendarId) return null
    if (!crossCalendar && a.calendarId != b.calendarId) return null

    val dateA = a.localDate(zone)
    val dateB = b.localDate(zone)
    val sameDay = dateA == dateB
    val timeDelta = abs(a.startMillis - b.startMillis)
    val overlapsTimed = !a.allDay && !b.allDay && a.startMillis < b.endMillis && b.startMillis < a.endMillis

    if (!sameDay && !overlapsTimed && timeDelta > FINDER_WINDOW_MILLIS) return null

    val otherContext = listOf(a.title, a.location, b.title, b.location)
    val (coreTitleA, embeddedLocA) = extractEmbeddedLocation(a.title, otherContext)
    val (coreTitleB, embeddedLocB) = extractEmbeddedLocation(b.title, otherContext)

    val effectiveLocA = a.location.ifBlank { embeddedLocA.orEmpty() }
    val effectiveLocB = b.location.ifBlank { embeddedLocB.orEmpty() }

    val fullTitleSim = similarity(a.title, b.title)
    val coreTitleSim = similarity(coreTitleA, coreTitleB)
    val titleScore = max(fullTitleSim, coreTitleSim)

    if (titleScore < 0.42) return null

    val locSim = if (effectiveLocA.isNotBlank() && effectiveLocB.isNotBlank()) {
      locationSimilarity(effectiveLocA, effectiveLocB)
    } else 0.0

    val reasons = mutableListOf<MatchReason>()

    // Time score & reason
    val timeScore = when {
      !a.allDay && !b.allDay && a.startMillis == b.startMillis -> {
        reasons += MatchReason("EXACT_TIME", "")
        1.0
      }
      overlapsTimed -> {
        reasons += MatchReason("OVERLAPPING_TIME", "")
        0.92
      }
      (a.allDay != b.allDay) && sameDay -> {
        reasons += MatchReason("ALLDAY_AND_TIMED", "")
        0.86
      }
      a.allDay && b.allDay && sameDay -> {
        reasons += MatchReason("SAME_DAY_ALLDAY", "")
        0.90
      }
      sameDay && timeDelta <= FINDER_WINDOW_MILLIS -> {
        reasons += MatchReason("SAME_DAY_NEAR_TIME", "${timeDelta / 60_000L} min")
        0.82
      }
      sameDay -> {
        reasons += MatchReason("SAME_DAY", "")
        0.72
      }
      else -> {
        reasons += MatchReason("NEAR_TIME", "")
        (1.0 - timeDelta.toDouble() / FINDER_WINDOW_MILLIS).coerceIn(0.4, 0.8)
      }
    }

    // Title reason
    when {
      normalize(a.title) == normalize(b.title) ->
        reasons += MatchReason("EXACT_TITLE", a.title)
      coreTitleSim >= 0.85 && normalize(coreTitleA) != normalize(coreTitleB) ->
        reasons += MatchReason("STEM_OR_TYPO_TITLE", "„${a.title}“ ↔ „${b.title}“")
      titleScore >= 0.70 ->
        reasons += MatchReason("SIMILAR_TITLE", "„${a.title}“ ↔ „${b.title}“")
      else ->
        reasons += MatchReason("PARTIAL_TITLE", "„${a.title}“ ↔ „${b.title}“")
    }

    // Location bonus & reason
    val locBonus = when {
      locSim >= 0.85 -> {
        val label = if (effectiveLocA.equals(effectiveLocB, ignoreCase = true)) effectiveLocA
        else "„$effectiveLocA“ ↔ „$effectiveLocB“"
        reasons += MatchReason("LOCATION_MATCH", label)
        0.12
      }
      locSim >= 0.55 -> {
        reasons += MatchReason("LOCATION_SIMILAR", "„$effectiveLocA“ ↔ „$effectiveLocB“")
        0.06
      }
      effectiveLocA.isBlank() || effectiveLocB.isBlank() -> 0.0
      else -> -0.10
    }

    if (a.calendarId != b.calendarId) {
      val cals = listOf(a.calendarName, b.calendarName).filter { it.isNotBlank() }.distinct().joinToString(" & ")
      reasons += MatchReason("CROSS_CALENDAR", cals)
    }

    val combined = (0.62 * titleScore + 0.30 * timeScore + 0.08 * (if (locSim > 0) locSim else 0.6) + locBonus)
      .coerceIn(0.0, 0.99)

    return PairAnalysis(combined, reasons)
  }

  /**
   * Finds all duplicate clusters among [events] above [minConfidence], grouping transitive duplicates
   * (e.g. Event 1 "Mittagessen Leverkusen", Event 2 "Mittagsessen Lev", Event 3 "Mittagessenbesuch" 14:00-15:00)
   * into unified clusters sorted by confidence descending.
   */
  fun findClusters(
    events: List<CalendarEventItem>,
    crossCalendar: Boolean = true,
    minConfidence: Double = 0.48,
    ignoredSignatures: Set<String> = emptySet(),
    zone: ZoneId = ZoneId.systemDefault(),
  ): List<DuplicateCluster> {
    if (events.size < 2) return emptyList()
    val sorted = events.sortedBy { it.startMillis }
    val n = sorted.size
    val adj = Array(n) { mutableListOf< Pair<Int, PairAnalysis> >() }

    for (i in 0 until n) {
      for (j in i + 1 until n) {
        val a = sorted[i]
        val b = sorted[j]
        // Fast pre-check: if startMillis is more than 36 hours apart, break inner loop because sorted by startMillis
        if (b.startMillis - a.startMillis > 36 * 60 * 60 * 1000L) break
        val analysis = compareEvents(a, b, crossCalendar, zone)
        if (analysis != null && analysis.score >= minConfidence) {
          adj[i].add(j to analysis)
          adj[j].add(i to analysis)
        }
      }
    }

    val visited = BooleanArray(n)
    val clusters = mutableListOf<DuplicateCluster>()

    for (i in 0 until n) {
      if (visited[i] || adj[i].isEmpty()) continue
      val component = mutableListOf<Int>()
      val queue = ArrayDeque<Int>()
      queue.add(i)
      visited[i] = true
      while (queue.isNotEmpty()) {
        val curr = queue.removeFirst()
        component.add(curr)
        for ((next, _) in adj[curr]) {
          if (!visited[next]) {
            visited[next] = true
            queue.add(next)
          }
        }
      }
      if (component.size < 2) continue

      val clusterEvents = component.map { sorted[it] }
        .sortedWith(compareByDescending<CalendarEventItem> { dataQualityScore(it) }.thenBy { it.startMillis })

      val sig = clusterSignature(clusterEvents)
      if (sig in ignoredSignatures) continue

      // Aggregate pairwise scores & reasons within this component
      val pairAnalyses = mutableListOf<PairAnalysis>()
      for (idxA in component.indices) {
        for (idxB in idxA + 1 until component.size) {
          val u = component[idxA]
          val v = component[idxB]
          adj[u].firstOrNull { it.first == v }?.second?.let { pairAnalyses.add(it) }
        }
      }
      val maxScore = pairAnalyses.maxOfOrNull { it.score } ?: minConfidence
      val avgScore = if (pairAnalyses.isNotEmpty()) pairAnalyses.map { it.score }.average() else maxScore
      val clusterConfidence = (0.65 * maxScore + 0.35 * avgScore).coerceIn(0.0, 0.99)

      val distinctReasons = pairAnalyses
        .flatMap { it.reasons }
        .distinctBy { it.code to it.detail }

      val category = when {
        clusterConfidence >= 0.80 -> DuplicateCategory.CERTAIN
        clusterConfidence >= 0.62 -> DuplicateCategory.PROBABLE
        else -> DuplicateCategory.UNSURE
      }

      val defaultPrimary = clusterEvents.first()
      val proposal = buildMergeProposal(clusterEvents, defaultPrimary.eventId)

      clusters += DuplicateCluster(
        id = sig,
        events = clusterEvents,
        confidence = clusterConfidence,
        category = category,
        reasons = distinctReasons,
        defaultPrimaryId = defaultPrimary.eventId,
        proposal = proposal,
      )
    }

    return clusters.sortedWith(
      compareBy<DuplicateCluster> {
        when (it.category) {
          DuplicateCategory.CERTAIN -> 0
          DuplicateCategory.PROBABLE -> 1
          DuplicateCategory.UNSURE -> 2
        }
      }.thenByDescending { it.confidence }
    )
  }

  /** Stable signature for a set of events so the user can mark a group as "Not a duplicate". */
  fun clusterSignature(events: List<CalendarEventItem>): String =
    events.map { "${it.calendarId}:${it.eventId}" }.sorted().joinToString("|")

  /**
   * Builds a rich [MergeProposal] using [primaryEventId] as the base event and enriching it
   * with the best fields (concrete time, full location, descriptions, attendees, reminders)
   * from the other events in [events].
   */
  fun buildMergeProposal(
    events: List<CalendarEventItem>,
    primaryEventId: Long,
    includeTitleFromId: Long? = null,
    includeLocation: Boolean = true,
    includeDescriptions: Boolean = true,
    includeAttendees: Boolean = true,
    includeReminders: Boolean = true,
  ): MergeProposal {
    val primary = events.firstOrNull { it.eventId == primaryEventId } ?: events.first()
    val others = events.filter { it.eventId != primary.eventId }
    val adopted = mutableListOf<String>()

    // 1. Title: use explicitly chosen title or primary's title (or a cleaner/longer title if primary's is very short)
    val chosenTitleEvent = includeTitleFromId?.let { id -> events.firstOrNull { it.eventId == id } } ?: primary
    val rawTitle = chosenTitleEvent.title.trim().ifBlank {
      events.firstOrNull { it.title.isNotBlank() }?.title.orEmpty()
    }
    val finalTitle = RuleEngine.cleanCrypticTitle(rawTitle).ifBlank { rawTitle }
    if (chosenTitleEvent.eventId != primary.eventId) {
      adopted += "title|$finalTitle"
    }

    // 2. Time: if primary is allDay, but another event in the cluster has a concrete clock time (!allDay), adopt the concrete time!
    val timedEvent = if (!primary.allDay) primary else others.firstOrNull { !it.allDay }
    val finalStart: Long
    val finalEnd: Long
    val finalAllDay: Boolean
    if (timedEvent != null) {
      finalStart = timedEvent.startMillis
      finalEnd = timedEvent.endMillis
      finalAllDay = false
      if (timedEvent.eventId != primary.eventId) {
        adopted += "time"
      }
    } else {
      finalStart = primary.startMillis
      finalEnd = primary.endMillis
      finalAllDay = primary.allDay
    }

    // 3. Location: gather explicit locations + embedded locations from titles (e.g. "Leverkusen" from "Mittagessen Leverkusen")
    val allStrings = events.flatMap { listOf(it.title, it.location) }
    val candidateLocations = mutableListOf<String>()
    for (ev in events) {
      if (ev.location.isNotBlank()) {
        candidateLocations += expandLocationIfAbbreviated(ev.location, allStrings)
      }
      val (_, embedded) = extractEmbeddedLocation(ev.title, allStrings)
      if (!embedded.isNullOrBlank()) {
        candidateLocations += expandLocationIfAbbreviated(embedded, allStrings)
      }
    }
    // Prefer the longest non-abbreviated location
    val bestLocation = candidateLocations
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .maxByOrNull { it.length }
      .orEmpty()

    val finalLocation = if (includeLocation) {
      if (bestLocation.length > primary.location.trim().length) {
        if (primary.location.trim() != bestLocation) {
          adopted += "location|$bestLocation"
        }
        bestLocation
      } else primary.location.trim()
    } else primary.location.trim()

    // 4. Description: combine unique descriptions without duplicating blocks
    val finalDescription = if (includeDescriptions) {
      val blocks = mutableListOf<String>()
      for (ev in listOf(primary) + others) {
        val clean = stripMarkerAndSystem(ev.description)
        if (clean.isNotEmpty() && blocks.none { normalize(it).contains(normalize(clean)) || normalize(clean).contains(normalize(it)) }) {
          blocks += clean
        }
      }
      val mergedDesc = blocks.joinToString("\n\n")
      if (mergedDesc.length > stripMarkerAndSystem(primary.description).length) {
        adopted += "description"
      }
      mergedDesc
    } else stripMarkerAndSystem(primary.description)

    // 5. Attendees
    val finalAttendees = if (includeAttendees) {
      val merged = events.flatMap { it.attendees }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
      if (merged.size > primary.attendees.size) {
        adopted += "attendees|${merged.size}"
      }
      merged
    } else primary.attendees

    // 6. Reminders
    val finalReminders = if (includeReminders) {
      val merged = events.flatMap { it.reminderMinutes }.filter { it >= 0 }.distinct().sorted()
      if (merged.size > primary.reminderMinutes.size) {
        adopted += "reminders|${merged.joinToString()}"
      }
      merged
    } else primary.reminderMinutes

    return MergeProposal(
      primaryEventId = primary.eventId,
      targetCalendarId = primary.calendarId,
      targetCalendarName = primary.calendarName,
      title = finalTitle,
      location = finalLocation,
      description = finalDescription,
      startMillis = finalStart,
      endMillis = finalEnd,
      allDay = finalAllDay,
      attendees = finalAttendees,
      reminderMinutes = finalReminders,
      adoptedFieldsSummary = adopted,
    )
  }

  private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in a.indices) {
      cur[0] = i + 1
      for (j in b.indices) {
        val cost = if (a[i] == b[j]) 0 else 1
        cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
      }
      val swap = prev
      prev = cur
      cur = swap
    }
    return prev[b.length]
  }
}
