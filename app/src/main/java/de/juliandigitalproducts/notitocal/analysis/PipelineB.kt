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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pipeline B: Deduplication & Merge Engine.
 * Adheres strictly to the 3 rules:
 * 1. Timespan & partial messages merge (e.g. 18:00 + 22:00 -> 18:00–22:00)
 * 2. Location enrichment on merge (HISTORY -> NEARBY_FALLBACK -> EXACT_MESSAGE -> NONE)
 * 3. Force reprocess support
 */
object PipelineB {

  const val ALGORITHM_VERSION = 2

  @Serializable
  data class PipelineBResult(
    val algorithmVersion: Int = ALGORITHM_VERSION,
    val totalChecked: Int = 0,
    val mergeGroups: List<MergeGroup> = emptyList(),
  )

  @Serializable
  data class MergeGroup(
    val groupId: String,
    val sourceEventIds: List<String>,
    val primaryKeepId: String,
    val idsToDelete: List<String>,
    val mergeReason: String,
    val mergedResult: MergedResult,
  )

  @Serializable
  data class MergedResult(
    val title: String,
    val startIso: String,
    val endIso: String,
    val durationMinutes: Int,
    val locationTitle: String,
    val locationAddress: String,
    val locationSource: String, // "EXACT_MESSAGE", "HISTORY", "NEARBY_FALLBACK", "NONE"
    val description: String,
  )

  @Serializable
  data class CandidateEvent(
    val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String = "",
    val description: String = "",
  )

  val SYSTEM_PROMPT = """
    Du bist die Deduplizierungs- und Merge-Engine der App NotiToCal.
    Du erhältst eine Liste bestehender Termine/Einträge (`candidateEvents`), vergangene Termine (`userHistory`) sowie lokale Orts-Vorschläge (`nearbyCandidates`).
    Deine Aufgabe ist es, zusammengehörige Einträge und Teil-Informationen desselben Ereignisses zu einem einzigen Termin zusammenzuführen.

    Befolge strikt diese Regeln:

    1. ZEITSPANNE & TEIL-NACHRICHTEN VERSCHMELZEN:
    - Führe Einträge zwingend auch dann zusammen, wenn sie NICHT exakt dieselbe Uhrzeit haben, aber inhaltlich zum selben Ereignis gehören.
    - Beispiel: Nachricht A ("Wir treffen uns um 18 Uhr am Kino", Start 18:00 Uhr) und Nachricht B ("Sollte so 22 Uhr zu Ende sein", Zeit 22:00 Uhr) ergeben EINEN gemeinsamen Termin "Kino" von 18:00 bis 22:00 Uhr.

    2. ORTS-ANREICHERUNG BEIM MERGE:
    - Fehlt eine genaue Adresse (z. B. nur "Kino"), ermittle die vollständige Adresse zuerst aus `userHistory` (vergangene Termine) oder nutze als Fallback das größte/beliebteste passende Ziel aus `nearbyCandidates`.

    3. ERNEUTE PRÜFUNG (FORCE REPROCESS):
    - Bewerte bei einem erneuten Prüf-Durchlauf alle übergebenen Einträge komplett neu, unabhängig davon, ob sie in einer früheren Version bereits geprüft wurden.

    Antworte AUSSCHLIESSLICH als valides JSON nach diesem Schema:
    {
      "algorithmVersion": 2,
      "totalChecked": 2,
      "mergeGroups": [
        {
          "groupId": "merge_01",
          "sourceEventIds": ["evt_1", "evt_2"],
          "primaryKeepId": "evt_1",
          "idsToDelete": ["evt_2"],
          "mergeReason": "Zusammenführung...",
          "mergedResult": {
            "title": "Kino",
            "startIso": "2026-11-12T18:00:00+01:00",
            "endIso": "2026-11-12T22:00:00+01:00",
            "durationMinutes": 240,
            "locationTitle": "CinemaxX",
            "locationAddress": "Bundesallee 250, 42103 Wuppertal",
            "locationSource": "HISTORY",
            "description": "..."
          }
        }
      ]
    }
  """.trimIndent()

  /**
   * Evaluates candidate events for merging, deduplication, and timespan fusion.
   */
  suspend fun process(
    candidateEvents: List<CandidateEvent>,
    userHistory: List<PipelineA.UserHistoryItem> = emptyList(),
    nearbyCandidates: List<PipelineA.NearbyCandidate> = emptyList(),
    settings: AppSettings,
    secrets: SecretStore? = null,
    context: Context? = null,
    forceReprocess: Boolean = false,
  ): PipelineBResult = withContext(Dispatchers.IO) {
    if (candidateEvents.isEmpty()) return@withContext PipelineBResult(totalChecked = 0)

    val aiResult = runSafely {
      executeAi(candidateEvents, userHistory, nearbyCandidates, settings, secrets, context, forceReprocess)
    }.getOrNull()

    if (aiResult != null && aiResult.mergeGroups.isNotEmpty()) {
      return@withContext aiResult
    }

    // High-precision algorithmic merge fallback
    return@withContext processAlgorithmic(candidateEvents, userHistory, nearbyCandidates, settings)
  }

  private suspend fun executeAi(
    candidateEvents: List<CandidateEvent>,
    userHistory: List<PipelineA.UserHistoryItem>,
    nearbyCandidates: List<PipelineA.NearbyCandidate>,
    settings: AppSettings,
    secrets: SecretStore?,
    context: Context?,
    forceReprocess: Boolean,
  ): PipelineBResult? {
    val cloudKey = secrets?.let { it.get(SecretKeys.llm(settings.provider)) }.orEmpty()
    val hasCloudKey = !settings.provider.needsKey || cloudKey.isNotBlank()
    val onDeviceOk = context != null && (settings.onDeviceModelFile.isNotBlank() || (settings.onDeviceModelUri.isNotBlank() && SharedModel.accessible(context, settings.onDeviceModelUri)))

    val userPrompt = buildJsonObject {
      put("forceReprocess", forceReprocess)
      put("candidateEvents", AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(CandidateEvent.serializer()), candidateEvents))
      put("userHistory", AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PipelineA.UserHistoryItem.serializer()), userHistory))
      put("nearbyCandidates", AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PipelineA.NearbyCandidate.serializer()), nearbyCandidates))
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

  fun parseResultJson(raw: String): PipelineBResult? = runSafely {
    val clean = Thinking.strip(raw)
    val start = clean.indexOf('{')
    val end = clean.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val jsonStr = clean.substring(start, end + 1)
    AppJson.decodeFromString(PipelineBResult.serializer(), jsonStr)
  }.getOrNull()

  /**
   * Deterministic merge algorithm executing Rules 1-3.
   */
  fun processAlgorithmic(
    candidateEvents: List<CandidateEvent>,
    userHistory: List<PipelineA.UserHistoryItem>,
    nearbyCandidates: List<PipelineA.NearbyCandidate>,
    settings: AppSettings,
  ): PipelineBResult {
    if (candidateEvents.size < 2) {
      return PipelineBResult(algorithmVersion = ALGORITHM_VERSION, totalChecked = candidateEvents.size, mergeGroups = emptyList())
    }

    val fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    val zone = ZoneId.systemDefault()
    val sorted = candidateEvents.sortedBy { it.startMillis }
    val primary = sorted.first()
    val other = sorted.drop(1)

    val minStart = sorted.minOf { it.startMillis }
    val maxEnd = sorted.maxOf { it.endMillis }

    val startDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(minStart), zone)
    val endDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(maxEnd), zone)
    val durationMins = java.time.Duration.between(startDt, endDt).toMinutes().toInt().coerceAtLeast(60)

    val lang = TextLang.of(sorted.joinToString(" ") { it.title + " " + it.description })
    val combinedTitle = primary.title.ifBlank { sorted.firstOrNull { it.title.isNotBlank() }?.title ?: Wording.fallbackTitle(lang) }
    val allLocText = sorted.map { it.location }.filter { it.isNotBlank() }.joinToString(", ")
    val allDescText = sorted.map { it.description }.filter { it.isNotBlank() }.joinToString("\n")

    var locTitle = primary.location
    var locAddr = ""
    var locSrc = "NONE"

    val kw = (combinedTitle + " " + allLocText).lowercase(Locale.GERMAN)
    val hist = userHistory.firstOrNull { kw.contains(it.title.lowercase()) || kw.contains(it.location.lowercase()) }
    if (hist != null && hist.location.isNotBlank()) {
      locTitle = hist.title
      locAddr = hist.location
      locSrc = "HISTORY"
    } else {
      val nearby = nearbyCandidates.firstOrNull { kw.contains(it.name.lowercase()) }
      if (nearby != null) {
        locTitle = nearby.name
        locAddr = nearby.address
        locSrc = "NEARBY_FALLBACK"
      } else if (allLocText.isNotBlank()) {
        locAddr = allLocText
        locSrc = "EXACT_MESSAGE"
      } else if (settings.homeCity.isNotBlank()) {
        locAddr = settings.homeCity
        locSrc = "NEARBY_FALLBACK"
      }
    }

    val mergeGroup = MergeGroup(
      groupId = "merge_${primary.id}_${System.currentTimeMillis()}",
      sourceEventIds = sorted.map { it.id },
      primaryKeepId = primary.id,
      idsToDelete = other.map { it.id },
      mergeReason = Wording.mergeReason(startDt.toLocalTime(), endDt.toLocalTime()),
      mergedResult = MergedResult(
        title = combinedTitle,
        startIso = startDt.format(fmt),
        endIso = endDt.format(fmt),
        durationMinutes = durationMins,
        locationTitle = locTitle,
        locationAddress = locAddr,
        locationSource = locSrc,
        description = allDescText.ifBlank { Wording.mergedFrom(lang, sorted.size) },
      ),
    )

    return PipelineBResult(
      algorithmVersion = ALGORITHM_VERSION,
      totalChecked = candidateEvents.size,
      mergeGroups = listOf(mergeGroup),
    )
  }
}
