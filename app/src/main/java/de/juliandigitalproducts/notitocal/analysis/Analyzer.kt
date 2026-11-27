// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

import android.content.Context
import de.juliandigitalproducts.notitocal.data.ActionMode
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.data.SecretStore
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Why
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Decision { AUTO, ASK, IGNORE }

data class Outcome(
  val detection: Detection,
  val decision: Decision,
  val note: String,
  val why: Why,
  val value: Double? = null,
  val limit: Double? = null,
)

/** Runs the configured engine and applies the strictness rules. */
class Analyzer(private val context: Context, private val secrets: SecretStore) {

  fun client(s: AppSettings): LlmClient? = when (s.engine) {
    AiEngine.CLOUD -> CloudLlmClient(s, secrets.get(SecretKeys.llm(s.provider)))
    AiEngine.SELF_HOSTED -> SelfHostedLlmClient(s.selfHostedUrl, secrets.get(SecretKeys.SELF_HOSTED), s.timeoutSeconds * 1000)
    else -> null
  }

  suspend fun analyze(
    c: Captured,
    s: AppSettings,
    now: LocalDateTime = LocalDateTime.now(),
    recentChatMessages: List<String> = emptyList(),
    existingEvents: List<Prompt.ExistingEventContext> = emptyList(),
  ): Detection {
    if (RuleEngine.isSelfOrDebugText(c.text)) {
      return Detection(Kind.NONE, confidence = 0.0, reason = "self/debug text", engine = "rules")
    }
    val cleanText = RuleEngine.stripDebugAndSystemText(c.text)
    val provisionalDate = existingEvents.firstOrNull { it.isProvisionalDay && !it.start.isNullOrBlank() }?.start?.let { str ->
      try { java.time.LocalDate.parse(str.substring(0, 10)) } catch (_: Exception) { null }
    }
    val rules = { RuleEngine.analyze(cleanText, c.sender, now, s.defaultEventMinutes, s.addressSuggestions, provisionalDate) }
    if (s.engine == AiEngine.RULES) return rules()

    // Pre-filter: skip the (slow / paid) model for messages without any signal.
    prefilterRelevance(c.text, s, now, hasImage = c.imageBytes != null)?.let { relevance ->
      if (relevance < s.prefilterThreshold) {
        return Detection(
          Kind.NONE, confidence = 1 - relevance, reason = "pre-filter (%.2f)".format(relevance), engine = "rules",
          why = Why.PREFILTER.name, whyValue = relevance, whyLimit = s.prefilterThreshold,
        )
      }
    }

    return try {
      val language = Prompt.userLanguage(context)
      if (s.engine == AiEngine.ON_DEVICE) {
        val masked = Pseudonymizer.apply(c.text, s.aliases)
        // Small local models: short example-based prompt, forced JSON start, dates and places checked by the rules.
        val raw = OnDeviceRunner.complete(
          context, s,
          Pseudonymizer.apply(Prompt.compact(now, language, s.eventTemplateId, s.taskTemplateId), s.aliases),
          "Message: " + masked.take(1500),
          s.maxTokens, Prompt.COMPACT_PREFILL,
        )
        val parsed = LlmJson.parse(raw, "on-device")?.copy(rawAnswer = raw.take(800))
        val llm = parsed?.let { Pseudonymizer.restoreIf(it, s) }
        val mergeText = if (s.restoreAliases) cleanText else masked
        val answer = if (s.restoreAliases) Pseudonymizer.reverse(raw, s.aliases) else raw
        val refused = parsed == null && LlmJson.isRefusal(raw)
        val merged = Hybrid.merge(llm, rules(), mergeText, now, s.defaultEventMinutes, refused).copy(rawAnswer = answer.take(800))
        return RuleEngine.sanitizeDetection(merged, cleanText, c.sender)
      }
      val system = Pseudonymizer.apply(Prompt.system(s, now, language), s.aliases)
      val user = Pseudonymizer.apply(Prompt.user(c.copy(text = cleanText), s, recentChatMessages, existingEvents), s.aliases)
      val client = client(s)!!
      val image = if (client.supportsImages && s.screenshotVision) c.imageBytes else null
      val raw = client.complete(system, user, image, c.imageMime)
      val parsed = LlmJson.parse(raw, client.name)?.copy(rawAnswer = raw.take(800)) ?: error("Unreadable answer: ${raw.take(120)}")
      val restored = Pseudonymizer.restoreIf(parsed, s)
      RuleEngine.sanitizeDetection(restored, cleanText, c.sender)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      throw e
    } catch (e: Exception) {
      if (s.fallbackToRules) rules().copy(reason = "AI failed (${e.message?.take(80)}), rules used")
      else Detection(Kind.NONE, reason = "AI error: ${e.message?.take(160)}", engine = "error", why = Why.AI_ERROR.name)
    }
  }

  /** Forces an AI analysis (bypassing rule pre-filter and using Cloud/Self-Hosted/On-Device if configured). */
  suspend fun analyzeForcedAi(
    c: Captured,
    s: AppSettings,
    now: LocalDateTime = LocalDateTime.now(),
    recentChatMessages: List<String> = emptyList(),
    existingEvents: List<Prompt.ExistingEventContext> = emptyList(),
  ): Detection {
    // With "Rules" only the on-device model may help: a stored cloud key or server URL does not switch the engine (M5).
    // Without one there is no AI to check with – say so instead of returning the rules' result as an AI check.
    if (!canCheckWithAi(context, s)) throw NoAiForCheck()
    val effectiveEngine = if (s.engine == AiEngine.RULES) AiEngine.ON_DEVICE else s.engine
    return analyze(c, s.copy(engine = effectiveEngine, rulePrefilter = false, fallbackToRules = true), now, recentChatMessages, existingEvents)
  }

  /** "Check with AI" has no model: engine "Rules" and no on-device model chosen. */
  class NoAiForCheck : IllegalStateException("No AI model for the check: engine 'Rules' without an on-device model")

  companion object {
    /** Whether "Check with AI" has a model: any engine but "Rules", or "Rules" with an on-device model chosen. */
    fun canCheckWithAi(context: android.content.Context, s: AppSettings): Boolean =
      s.engine != AiEngine.RULES || OnDeviceRunner.hasModel(context, s)

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    private val FOREIGN_SCRIPTS = setOf(
      Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
      Character.UnicodeScript.KATAKANA, Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW,
      Character.UnicodeScript.GREEK, Character.UnicodeScript.THAI, Character.UnicodeScript.DEVANAGARI,
    )

    /**
     * Relevance score of the rule pre-filter that runs before a model engine, or null when the pre-filter does not apply:
     * switched off, an image (the text alone says little), or foreign scripts the rules cannot read (the model decides).
     * The message is skipped when the value is below [AppSettings.prefilterThreshold].
     */
    fun prefilterRelevance(text: String, s: AppSettings, now: LocalDateTime, hasImage: Boolean = false): Double? {
      if (!s.rulePrefilter || hasImage) return null
      if (text.any { Character.UnicodeScript.of(it.code) in FOREIGN_SCRIPTS }) return null
      return RuleEngine.relevance(text, now, s.addressSuggestions) + if (matchesAny(text, s.boostKeywords)) 0.3 else 0.0
    }

    fun matchesAny(text: String, csv: String): Boolean {
      val t = text.lowercase()
      return csv.split(',', '\n').map { it.trim().lowercase() }.any { it.isNotEmpty() && t.contains(it) }
    }

    /** Strictness rules: decides whether a detection is added automatically, needs confirmation or is ignored. */
    fun decide(input: Detection, text: String, s: AppSettings, now: LocalDateTime = LocalDateTime.now()): Outcome {
      var d = input
      if (matchesAny(text, s.blockKeywords)) return Outcome(d, Decision.IGNORE, "blocked keyword", Why.BLOCKED_KEYWORD)
      if (matchesAny(text, s.boostKeywords) && d.kind != Kind.NONE) d = d.copy(confidence = (d.confidence + 0.15).coerceAtMost(1.0))

      if (d.kind == Kind.EVENT) {
        val start = d.startDateTime()
        val noTime = s.requireTimeForEvents && (d.allDay || d.start?.contains('T') != true)
        val address = if (s.addressSuggestions && (start == null || noTime)) RuleEngine.fullAddress(d.location.orEmpty()) else null
        when {
          // Optional (C6): an undated event at a complete address becomes a suggestion for the day at noon.
          address != null -> {
            val noon = (start?.toLocalDate() ?: now.toLocalDate()).atTime(12, 0)
            d = d.copy(
              start = noon.format(ISO),
              end = noon.plusMinutes(s.defaultEventMinutes.toLong()).format(ISO),
              allDay = false,
              confidence = minOf(d.confidence, RuleEngine.ADDRESS_CONFIDENCE),
              why = Why.LOCATION_ONLY.name,
            )
          }
          start == null ->
            if (s.undatedEventsAsTasks) d = d.copy(kind = Kind.TASK, due = d.due)
            else return Outcome(d, Decision.IGNORE, "event without date", Why.NO_DATE)
          noTime ->
            if (s.undatedEventsAsTasks) d = d.copy(kind = Kind.TASK, due = d.start, start = null, end = null)
            else return Outcome(d, Decision.IGNORE, "event without time", Why.NO_TIME)
        }
      }
      if (d.kind == Kind.EVENT) {
        val start = d.startDateTime()!!
        val effectiveEnd = d.endDateTime() ?: if (d.allDay || d.start?.contains('T') != true) start.plusDays(1) else start.plusHours(1)
        if (s.ignorePast && effectiveEnd.isBefore(now)) return Outcome(d, Decision.IGNORE, "in the past", Why.PAST)
        if (start.isAfter(now.plusDays(s.maxDaysAhead.toLong()))) return Outcome(d, Decision.IGNORE, "too far ahead", Why.TOO_FAR, limit = s.maxDaysAhead.toDouble())
        if (d.end == null && d.start?.contains('T') == true && !d.allDay) {
          d = d.copy(end = start.plusMinutes(s.defaultEventMinutes.toLong()).format(ISO))
        }
      }
      if (d.kind == Kind.TASK && s.ignorePast) {
        val due = d.dueDateTime()
        if (due != null && due.toLocalDate().isBefore(now.toLocalDate().minusDays(1))) return Outcome(d, Decision.IGNORE, "deadline passed", Why.DEADLINE_PASSED)
      }

      val (mode, min) = when (d.kind) {
        Kind.EVENT -> s.eventMode to s.eventMinConfidence
        Kind.TASK -> s.taskMode to s.taskMinConfidence
        else -> return Outcome(
          d, Decision.IGNORE, if (d.kind == Kind.NOTE) "note" else "nothing found",
          d.why?.let { w -> Why.entries.firstOrNull { it.name == w } } ?: if (d.kind == Kind.NOTE) Why.NOTE else Why.NOTHING,
          d.whyValue, d.whyLimit,
        )
      }
      if (mode == ActionMode.OFF) return Outcome(d, Decision.IGNORE, "disabled for ${d.kind.name.lowercase()}s", Why.KIND_OFF)

      // Address-only suggestions are never added automatically, whatever the mode and thresholds say.
      if (d.kind == Kind.EVENT && d.why == Why.LOCATION_ONLY.name) {
        if (!s.addressSuggestions) return Outcome(d, Decision.IGNORE, "event without date", Why.NO_DATE)
        if (d.title.isBlank()) d = d.copy(title = d.location ?: text.take(60))
        return Outcome(d, Decision.ASK, "address only", Why.LOCATION_ONLY, d.confidence)
      }

      if (d.confidence < min) return Outcome(d, Decision.IGNORE, "confidence %.2f < %.2f".format(d.confidence, min), Why.BELOW_MIN, d.confidence, min)
      if (d.title.isBlank()) d = d.copy(title = text.take(60))
      return when {
        mode == ActionMode.AUTO && (d.confidence >= s.autoAddConfidence || s.autoAddUnsure) ->
          Outcome(d, Decision.AUTO, "auto", Why.AUTO, d.confidence, s.autoAddConfidence)
        mode != ActionMode.AUTO -> Outcome(d, Decision.ASK, "confirm", Why.ASK_MODE)
        else -> Outcome(d, Decision.ASK, "confirm", Why.ASK_CONFIDENCE, d.confidence, s.autoAddConfidence)
      }
    }
  }
}
