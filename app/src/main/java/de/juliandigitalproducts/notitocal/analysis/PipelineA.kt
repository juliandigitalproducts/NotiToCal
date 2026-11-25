package de.juliandigitalproducts.notitocal.analysis

import android.content.Context
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.data.SecretStore
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Pipeline A: Event creation, style testing, and historical learning engine.
 * Adheres strictly to the 4 rules:
 * 1. Multi-message merge
 * 2. Intelligent context duration estimation
 * 3. Location cascade (HISTORY -> NEARBY_FALLBACK -> EXACT_MESSAGE -> NONE)
 * 4. Full style generation (compact, standard, detailed, customApplied)
 */
object PipelineA {
  private const val DEFAULT_MINUTES = 60

  /** "am Kino", "beim Griechen", "at Starbucks", "at the Station" – German and English prepositions. */
  private val PLACE_MENTION = Regex(
    """\b(?:am|im|beim|in\s+der|an\s+der|ins|zum|at|at\s+the|in\s+the|to\s+the)\s+([A-ZÄÖÜ][a-zäöüß\-]+(?:\s+[A-ZÄÖÜ][a-zäöüß\-]+)?)\b""",
  )

  const val ALGORITHM_VERSION = 2

  @Serializable
  data class PipelineAResult(
    val algorithmVersion: Int = ALGORITHM_VERSION,
    val isEventDetected: Boolean = false,
    val mergedMessageIds: List<String> = emptyList(),
    val timeData: TimeData = TimeData(),
    val locationData: LocationData = LocationData(),
    val styleVariants: StyleVariants = StyleVariants(),
    val aiReasoning: String = "",
  )

  @Serializable
  data class TimeData(
    val startIso: String = "",
    val endIso: String = "",
    val durationMinutes: Int = 0,
    val isEndEstimated: Boolean = false,
    val estimationReason: String = "",
  )

  @Serializable
  data class LocationData(
    val rawMention: String = "",
    val resolvedTitle: String = "",
    val resolvedAddress: String = "",
    val resolutionSource: String = "NONE", // "EXACT_MESSAGE", "HISTORY", "NEARBY_FALLBACK", "NONE"
  )

  @Serializable
  data class StyleVariants(
    val compact: StyleVariantItem = StyleVariantItem(),
    val standard: StyleVariantItem = StyleVariantItem(),
    val detailed: StyleVariantItem = StyleVariantItem(),
    val customApplied: StyleVariantItem = StyleVariantItem(),
  )

  @Serializable
  data class StyleVariantItem(
    val title: String = "",
    val location: String = "",
    val timeFormatted: String = "",
    val description: String = "",
  )

  @Serializable
  data class PipelineMessage(
    val id: String,
    val text: String,
    val sender: String = "",
    val timestamp: Long = System.currentTimeMillis(),
  )

  @Serializable
  data class UserHistoryItem(
    val title: String,
    val location: String,
    val dateIso: String,
  )

  @Serializable
  data class NearbyCandidate(
    val name: String,
    val address: String,
    val category: String = "",
  )

  val SYSTEM_PROMPT = """
    Du bist die zentrale Extraktions- und Analyse-Engine der App NotiToCal.
    Deine Aufgabe ist es, aus Nachrichten (Chatverläufen, OCR-Texten, Sprachnachrichten-Transkripten) präzise Kalendertermine zu extrahieren und ausschließlich im vorgegebenen JSON-Schema zu antworten.

    Befolge strikt diese Regeln:

    1. KONVERSATIONS-ZUSAMMENFÜHRUNG (MULTI-MESSAGE MERGE):
    - Prüfe alle übergebenen Nachrichten im zeitlichen und inhaltlichen Zusammenhang.
    - Wenn Nachricht A den Startzeitpunkt nennt (z. B. "Wir treffen uns um 18 Uhr am Kino") und Nachricht B das Ende ergänzt (z. B. "Sollte so 22 Uhr zu Ende sein"), fasse beide zwingend zu EINEM einzigen Termin (18:00 bis 22:00 Uhr) zusammen und hinterlege alle genutzten IDs in `mergedMessageIds`.

    2. INTELLIGENTE DAUER-SCHÄTZUNG (CONTEXT DURATION):
    - Fehlt eine explizite Endzeit (z. B. "Morgen treffen wir uns um 14 Uhr am Kino"), schätze die Dauer realistisch anhand des Aktivitäts-Kontexts und früherer Termine aus `userHistory`:
      * Kino / Theater / Konzert: 180 Minuten (z. B. 14:00 bis 17:00 Uhr)
      * Restaurant / Abendessen / Treffen: 120 Minuten
      * Arzttermin / Friseur / Meeting: 60 Minuten
    - Setze in diesem Fall `isEndEstimated = true` und begründe die Schätzung kurz in `estimationReason`.

    3. ORTS-AUFLÖSUNG (LOCATION CASCADE):
    - Wird nur ein generischer Ort genannt (z. B. "am Kino"):
      * Schritt 1 (Historie): Prüfe das übergebene Array `userHistory` auf vergangene Termine mit diesem Schlagwort und übernimm dessen genaue Adresse (`resolutionSource = "HISTORY"`).
      * Schritt 2 (Fallback): Falls in der Historie nichts existiert, wähle aus `nearbyCandidates` (oder per Web-/Ortswissen zum Standort) den größten/beliebtesten passenden Ort in der Nähe inklusive vollständiger Adresse (`resolutionSource = "NEARBY_FALLBACK"`).
      * Schritt 3 (Exakt): Steht die volle Adresse direkt im Text, nutze diese (`resolutionSource = "EXACT_MESSAGE"`).

    4. VOLLSTÄNDIGE STIL-GENERIERUNG (LIVE-PREVIEW & SPEICHERUNG):
    - Generiere im Objekt `styleVariants` immer alle Stil-Varianten gleichzeitig (`compact`, `standard`, `detailed`, `customApplied` gemäß Nutzer-Beispielen) unter Verwendung des ermittelten Titels, der berechneten Zeitspanne und der aufgelösten Adresse.

    Antworte AUSSCHLIESSLICH als valides JSON nach diesem Schema:
    {
      "algorithmVersion": 2,
      "isEventDetected": true,
      "mergedMessageIds": ["id1"],
      "timeData": {
        "startIso": "2026-11-10T14:00:00+01:00",
        "endIso": "2026-11-10T17:00:00+01:00",
        "durationMinutes": 180,
        "isEndEstimated": true,
        "estimationReason": "Kontext Kino..."
      },
      "locationData": {
        "rawMention": "am Kino",
        "resolvedTitle": "CinemaxX Wuppertal",
        "resolvedAddress": "Bundesallee 250, 42103 Wuppertal",
        "resolutionSource": "NEARBY_FALLBACK"
      },
      "styleVariants": {
        "compact": { "title": "...", "location": "...", "timeFormatted": "...", "description": "..." },
        "standard": { "title": "...", "location": "...", "timeFormatted": "...", "description": "..." },
        "detailed": { "title": "...", "location": "...", "timeFormatted": "...", "description": "..." },
        "customApplied": { "title": "...", "location": "...", "timeFormatted": "...", "description": "..." }
      },
      "aiReasoning": "..."
    }
  """.trimIndent()

  /**
   * Main entry point for Pipeline A.
   */
  suspend fun process(
    messages: List<PipelineMessage>,
    userHistory: List<UserHistoryItem> = emptyList(),
    nearbyCandidates: List<NearbyCandidate> = emptyList(),
    settings: AppSettings,
    secrets: SecretStore? = null,
    context: Context? = null,
  ): PipelineAResult = withContext(Dispatchers.IO) {
    if (messages.isEmpty()) return@withContext PipelineAResult()

    // 1. Try AI first if available
    val aiResult = runSafely {
      executeAi(messages, userHistory, nearbyCandidates, settings, secrets, context)
    }.getOrNull()

    if (aiResult != null && aiResult.isEventDetected) {
      return@withContext aiResult
    }

    // 2. High-performance Algorithmic fallback implementing the exact same 4 rules
    return@withContext processAlgorithmic(messages, userHistory, nearbyCandidates, settings, context)
  }

  private suspend fun executeAi(
    messages: List<PipelineMessage>,
    userHistory: List<UserHistoryItem>,
    nearbyCandidates: List<NearbyCandidate>,
    settings: AppSettings,
    secrets: SecretStore?,
    context: Context?,
  ): PipelineAResult? {
    val cloudKey = secrets?.let { it.get(SecretKeys.llm(settings.provider)) }.orEmpty()
    val hasCloudKey = !settings.provider.needsKey || cloudKey.isNotBlank()
    val onDeviceOk = context != null && (settings.onDeviceModelFile.isNotBlank() || (settings.onDeviceModelUri.isNotBlank() && SharedModel.accessible(context, settings.onDeviceModelUri)))

    val userPrompt = buildJsonObject {
      put("currentSystemTime", LocalDateTime.now().toString())
      put("messages", AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PipelineMessage.serializer()), messages))
      put("userHistory", AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(UserHistoryItem.serializer()), userHistory))
      put("nearbyCandidates", AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(NearbyCandidate.serializer()), nearbyCandidates))
    }.toString()

    val rawAnswer: String? = when {
      settings.cloudAllowed && hasCloudKey -> runSafely {
        Pseudonymizer.around(settings, SYSTEM_PROMPT, userPrompt) { sys, usr ->
          CloudLlmClient(settings, cloudKey).complete(sys, usr, json = true)
        }
      }.getOrNull()

      settings.engine == AiEngine.SELF_HOSTED && settings.selfHostedUrl.isNotBlank() -> runSafely {
        Pseudonymizer.around(settings, SYSTEM_PROMPT, userPrompt) { sys, usr ->
          val body = buildJsonObject {
            put("system", sys)
            put("user", usr)
          }
          Http.postJson(SelfHosted.url(settings.selfHostedUrl, "complete"), body.toString(), SelfHosted.auth(secrets?.get(SecretKeys.SELF_HOSTED).orEmpty()))
        }
      }.getOrNull()

      settings.engine == AiEngine.ON_DEVICE && onDeviceOk && context != null -> runSafely {
        val masked = Pseudonymizer.apply(userPrompt, settings.aliases)
        OnDeviceRunner.complete(
          context = context,
          s = settings,
          system = SYSTEM_PROMPT,
          user = masked,
          maxTokens = settings.maxTokens,
          prefill = "{\"algorithmVersion\":2,",
        )
      }.getOrNull()


      else -> null
    }

    if (rawAnswer.isNullOrBlank()) return null
    return parseResultJson(rawAnswer)
  }

  fun parseResultJson(raw: String): PipelineAResult? = runSafely {
    val clean = Thinking.strip(raw)
    val start = clean.indexOf('{')
    val end = clean.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val jsonStr = clean.substring(start, end + 1)
    AppJson.decodeFromString(PipelineAResult.serializer(), jsonStr)
  }.getOrNull()

  /**
   * Deterministic algorithmic pipeline implementing Rules 1-4.
   */
  fun processAlgorithmic(
    messages: List<PipelineMessage>,
    userHistory: List<UserHistoryItem>,
    nearbyCandidates: List<NearbyCandidate>,
    settings: AppSettings,
    context: Context? = null,
  ): PipelineAResult {
    val combinedText = messages.joinToString(" ") { it.text }
    val now = LocalDateTime.now()

    // 1. Date & Time parsing across all messages
    var detectedDate: LocalDate? = null
    var detectedStartTime: LocalTime? = null
    var detectedEndTime: LocalTime? = null

    for (msg in messages) {
      val parsed = DateParser.parse(msg.text, now)
      if (parsed.date != null) detectedDate = parsed.date
      if (parsed.time != null) {
        if (detectedStartTime == null) detectedStartTime = parsed.time else detectedEndTime = parsed.time
      }
    }

    // Explicit end time, German or English ("22 Uhr zu Ende", "bis 22 Uhr", "until 10 pm")
    val writtenEnd = TimeCues.end(combinedText)
    if (writtenEnd != null) detectedEndTime = writtenEnd
    // Content follows the message's language, so a German and an English chat both read naturally.
    val lang = TextLang.of(combinedText)
    val activity = Activity.find(combinedText)

    if (detectedDate == null) detectedDate = now.toLocalDate()
    if (detectedStartTime == null) detectedStartTime = LocalTime.of(14, 0) // Default test hour if none specified

    // Rule 2: Duration Estimation
    var durationMins: Int
    var isEndEstimated = false
    var estimationReason = ""

    val endTime = detectedEndTime
    if (endTime != null) {
      durationMins = java.time.Duration.between(detectedStartTime, endTime).toMinutes().toInt().let { if (it <= 0) it + 24 * 60 else it }
      estimationReason = Wording.explicitEnd(detectedStartTime, endTime)
    } else {
      durationMins = Activity.typicalMinutes(combinedText) ?: DEFAULT_MINUTES
      detectedEndTime = detectedStartTime.plusMinutes(durationMins.toLong())
      isEndEstimated = true
      estimationReason = Wording.estimated(durationMins, activity)
    }

    // Rule 3: Location Cascade
    var rawMention = ""
    var resolvedTitle = ""
    var resolvedAddress = ""
    var resolutionSource = "NONE"

    // Check raw address in text
    val fullAddr = RuleEngine.fullAddress(combinedText)
    if (fullAddr != null) {
      rawMention = fullAddr
      resolvedTitle = fullAddr
      resolvedAddress = fullAddr
      resolutionSource = "EXACT_MESSAGE"
    } else {
      val placeMatch = PLACE_MENTION.find(combinedText)
      rawMention = placeMatch?.groupValues?.get(1) ?: if (activity == Activity.CINEMA) Activity.CINEMA.title(lang) else ""

      if (rawMention.isNotBlank()) {
        val kw = rawMention.lowercase()
        // Step 1: User History Check
        val hist = userHistory.firstOrNull { it.title.lowercase().contains(kw) || it.location.lowercase().contains(kw) }
        if (hist != null && hist.location.isNotBlank()) {
          resolvedTitle = hist.title
          resolvedAddress = hist.location
          resolutionSource = "HISTORY"
        } else {
          // Step 2: Nearby Candidates Fallback
          val nearby = nearbyCandidates.firstOrNull { it.name.lowercase().contains(kw) || it.category.lowercase().contains(kw) }
          if (nearby != null) {
            resolvedTitle = nearby.name
            resolvedAddress = nearby.address
            resolutionSource = "NEARBY_FALLBACK"
          } else if (settings.homeCity.isNotBlank()) {
            val fallbackVenue = when {
              kw.contains("kino") -> "CinemaxX ${settings.homeCity}"
              kw.contains("theater") -> "Stadttheater ${settings.homeCity}"
              else -> "$rawMention ${settings.homeCity}"
            }
            resolvedTitle = fallbackVenue
            resolvedAddress = "${settings.homeCity}${if (settings.homePostalCode.isNotBlank()) " (${settings.homePostalCode})" else ""}"
            resolutionSource = "NEARBY_FALLBACK"
          }
        }
      }
    }

    // Title determination
    val titleCandidate = (activity ?: Activity.APPOINTMENT).title(lang)

    val startFmt = Wording.hhmm(detectedStartTime)
    val endFmt = Wording.hhmm(detectedEndTime)
    val timeSpanFormatted = Wording.span(lang, detectedStartTime, detectedEndTime)
    val startIso = "${detectedDate}T${startFmt}:00"
    val endIso = "${detectedDate}T${endFmt}:00"

    // Rule 4: Style Variants Generation
    val locationDisplay = if (resolvedAddress.isNotBlank()) {
      if (resolvedTitle.isNotBlank() && !resolvedAddress.contains(resolvedTitle)) "$resolvedTitle, $resolvedAddress" else resolvedAddress
    } else ""

    val styleVariants = StyleVariants(
      compact = StyleVariantItem(
        title = titleCandidate,
        location = locationDisplay,
        timeFormatted = timeSpanFormatted,
        description = "$titleCandidate (${Wording.range(lang, detectedStartTime, detectedEndTime)})",
      ),
      standard = StyleVariantItem(
        title = if (rawMention.isNotBlank()) Wording.meetingAt(lang, rawMention) else titleCandidate,
        location = locationDisplay,
        timeFormatted = Wording.range(lang, detectedStartTime, detectedEndTime),
        description = Wording.appointmentOn(lang, titleCandidate, detectedDate, estimationReason),
      ),
      detailed = StyleVariantItem(
        title = if (resolvedTitle.isNotBlank()) "$titleCandidate ($resolvedTitle)" else titleCandidate,
        location = locationDisplay,
        timeFormatted = Wording.detailedTime(lang, detectedStartTime, detectedEndTime, durationMins),
        description = Wording.detailed(lang, titleCandidate, timeSpanFormatted, locationDisplay, estimationReason),
      ),
      customApplied = StyleVariantItem(
        title = "📅 $titleCandidate",
        location = locationDisplay,
        timeFormatted = timeSpanFormatted,
        description = Wording.range(lang, detectedStartTime, detectedEndTime) + " - $resolvedTitle",
      ),
    )

    return PipelineAResult(
      algorithmVersion = ALGORITHM_VERSION,
      isEventDetected = true,
      mergedMessageIds = messages.map { it.id },
      timeData = TimeData(
        startIso = startIso,
        endIso = endIso,
        durationMinutes = durationMins,
        isEndEstimated = isEndEstimated,
        estimationReason = estimationReason,
      ),
      locationData = LocationData(
        rawMention = rawMention,
        resolvedTitle = resolvedTitle,
        resolvedAddress = resolvedAddress,
        resolutionSource = resolutionSource,
      ),
      styleVariants = styleVariants,
      aiReasoning = Wording.reasoning(timeSpanFormatted, estimationReason, resolutionSource),
    )
  }
}
