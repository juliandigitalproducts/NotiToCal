package de.juliandigitalproducts.notitocal.output

import de.juliandigitalproducts.notitocal.R
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import de.juliandigitalproducts.notitocal.analysis.CloudLlmClient
import de.juliandigitalproducts.notitocal.analysis.OnDeviceRunner
import de.juliandigitalproducts.notitocal.analysis.Pseudonymizer
import de.juliandigitalproducts.notitocal.analysis.SelfHosted
import de.juliandigitalproducts.notitocal.analysis.SharedModel
import de.juliandigitalproducts.notitocal.analysis.Thinking
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.data.SecretStore
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.net.Http
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.AiDuplicateAdvice
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.CalendarEventItem
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.Candidate
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.DuplicateCategory
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.DuplicateCluster
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.MergeHistoryRecord
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.MergeProposal
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.RemovalMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Reads calendar instances, executes intelligent duplicate scans, runs optional AI verification for uncertain clusters,
 * merges duplicate events, and maintains a local 30-day undo & backup store.
 */
object CalendarDuplicates {
  private const val HISTORY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000L // 30 days (1 month)
  private const val HISTORY_FILE = "duplicate_merge_history.json"
  private const val IGNORED_FILE = "duplicate_ignored_signatures.json"

  data class CalendarSourceItem(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val accessLevel: Int,
    val primary: Boolean,
  ) {
    val isWritable: Boolean get() = accessLevel >= DuplicateMatcher.ACCESS_CONTRIBUTOR
  }

  @Serializable
  data class DuplicateBackupContainer(
    val app: String = "NotiToCal",
    val createdAt: Long = System.currentTimeMillis(),
    val events: List<CalendarEventItem>,
  )

  fun hasReadPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

  fun hasWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

  // ---------- Live Ingestion Evaluation ----------

  fun evaluate(context: Context, proposed: DuplicateMatcher.Proposed, threshold: Double = DuplicateMatcher.THRESHOLD): DuplicateMatcher.Outcome {
    val candidates = runSafely { queryWindow(context, proposed.startMillis) }.getOrDefault(emptyList())
    return DuplicateMatcher.evaluate(proposed, candidates, threshold)
  }

  private fun queryWindow(context: Context, startMillis: Long): List<Candidate> {
    if (!hasReadPermission(context)) return emptyList()
    val access = calendarMetaMap(context)
    val begin = startMillis - DuplicateMatcher.WINDOW_MILLIS
    val end = startMillis + DuplicateMatcher.WINDOW_MILLIS
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
      ContentUris.appendId(this, begin)
      ContentUris.appendId(this, end)
    }.build()
    val out = mutableListOf<Candidate>()
    context.contentResolver.query(
      uri,
      arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.DESCRIPTION,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.CALENDAR_ID,
      ),
      null, null, null,
    )?.use { c ->
      val eventId = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
      val title = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
      val beginCol = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
      val description = c.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
      val location = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
      val calendarId = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
      while (c.moveToNext()) {
        val cal = c.getLong(calendarId)
        out += Candidate(
          eventId = c.getLong(eventId),
          calendarId = cal,
          title = c.getString(title).orEmpty(),
          description = c.getString(description).orEmpty(),
          location = c.getString(location).orEmpty(),
          startMillis = c.getLong(beginCol),
          accessLevel = access[cal]?.accessLevel ?: CalendarContract.Calendars.CAL_ACCESS_NONE,
        )
      }
    }
    return out
  }

  // ---------- Calendar Sources & Multi-Event Query ----------

  fun allCalendars(context: Context): List<CalendarSourceItem> {
    if (!hasReadPermission(context)) return emptyList()
    val list = mutableListOf<CalendarSourceItem>()
    runSafely {
      context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
          CalendarContract.Calendars._ID,
          CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
          CalendarContract.Calendars.ACCOUNT_NAME,
          CalendarContract.Calendars.CALENDAR_COLOR,
          CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
          CalendarContract.Calendars.IS_PRIMARY,
        ),
        "${CalendarContract.Calendars.VISIBLE} = 1",
        null,
        null,
      )?.use { c ->
        while (c.moveToNext()) {
          list += CalendarSourceItem(
            id = c.getLong(0),
            name = c.getString(1).orEmpty().ifBlank { "Kalender #${c.getLong(0)}" },
            account = c.getString(2).orEmpty(),
            color = c.getInt(3),
            accessLevel = c.getInt(4),
            primary = c.getInt(5) == 1,
          )
        }
      }
    }
    return list.sortedWith(compareByDescending<CalendarSourceItem> { it.primary }.thenByDescending { it.isWritable }.thenBy { it.name })
  }

  private fun calendarMetaMap(context: Context): Map<Long, CalendarSourceItem> =
    allCalendars(context).associateBy { it.id }

  fun isHolidayCalendar(name: String, account: String): Boolean {
    val n = name.lowercase()
    val a = account.lowercase()
    return n.contains("feiertag") || n.contains("holiday") || n.contains("ferien") ||
      n.contains("vacaciones") || n.contains("jours fériés") || n.contains("festivo") ||
      a.contains("holiday") || a.contains("feiertag")
  }

  /**
   * Queries all calendar events in [startMillis]..[endMillis] for the given [selectedCalendarIds] (empty = all visible calendars).
   */
  suspend fun queryEventsInRange(
    context: Context,
    startMillis: Long,
    endMillis: Long,
    selectedCalendarIds: Set<Long> = emptySet(),
    ignoreHolidays: Boolean = false,
  ): List<CalendarEventItem> = withContext(Dispatchers.IO) {
    if (!hasReadPermission(context)) return@withContext emptyList()
    val calMeta = calendarMetaMap(context)
    if (calMeta.isEmpty()) return@withContext emptyList()

    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
      ContentUris.appendId(this, startMillis)
      ContentUris.appendId(this, endMillis)
    }.build()

    val rawEvents = LinkedHashMap<Long, CalendarEventItem>()
    runSafely {
      context.contentResolver.query(
        uri,
        arrayOf(
          CalendarContract.Instances.EVENT_ID,
          CalendarContract.Instances.CALENDAR_ID,
          CalendarContract.Instances.TITLE,
          CalendarContract.Instances.DESCRIPTION,
          CalendarContract.Instances.EVENT_LOCATION,
          CalendarContract.Instances.BEGIN,
          CalendarContract.Instances.END,
          CalendarContract.Instances.ALL_DAY,
          CalendarContract.Instances.EVENT_TIMEZONE,
          CalendarContract.Instances.RRULE,
        ),
        null,
        null,
        "${CalendarContract.Instances.BEGIN} ASC",
      )?.use { c ->
        val idIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
        val calIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
        val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val descIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
        val locIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        val beginIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val endIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
        val allDayIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
        val tzIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_TIMEZONE)
        val rruleIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.RRULE)

        while (c.moveToNext()) {
          val calId = c.getLong(calIdx)
          if (selectedCalendarIds.isNotEmpty() && calId !in selectedCalendarIds) continue
          val meta = calMeta[calId] ?: continue
          if (ignoreHolidays && isHolidayCalendar(meta.name, meta.account)) continue
          val evId = c.getLong(idIdx)
          val title = c.getString(titleIdx).orEmpty().trim()
          if (title.isBlank() || title.startsWith("[Duplikat]", ignoreCase = true)) continue
          if (ignoreHolidays && (title.contains("feiertag", ignoreCase = true) || title.contains("holiday", ignoreCase = true))) continue
          val begin = c.getLong(beginIdx)
          val end = c.getLong(endIdx).takeIf { it > begin } ?: (begin + 60 * 60 * 1000L)
          if (evId !in rawEvents) {
            rawEvents[evId] = CalendarEventItem(
              eventId = evId,
              calendarId = calId,
              calendarName = meta.name,
              calendarAccount = meta.account,
              calendarColor = meta.color,
              accessLevel = meta.accessLevel,
              title = title,
              description = c.getString(descIdx).orEmpty(),
              location = c.getString(locIdx).orEmpty(),
              startMillis = begin,
              endMillis = end,
              allDay = c.getInt(allDayIdx) == 1,
              timezone = c.getString(tzIdx).orEmpty().ifBlank { ZoneId.systemDefault().id },
              rrule = c.getString(rruleIdx).orEmpty(),
            )
          }
        }
      }
    }

    if (rawEvents.isEmpty()) return@withContext emptyList()

    // Enrich with reminders and attendees in bulk for the queried events
    val eventIds = rawEvents.keys.toList()
    val remindersMap = queryReminders(context, eventIds)
    val attendeesMap = queryAttendees(context, eventIds)

    rawEvents.values.map { ev ->
      ev.copy(
        reminderMinutes = remindersMap[ev.eventId] ?: emptyList(),
        attendees = attendeesMap[ev.eventId] ?: emptyList(),
      )
    }
  }

  private fun queryReminders(context: Context, eventIds: List<Long>): Map<Long, List<Int>> {
    val out = mutableMapOf<Long, MutableList<Int>>()
    for (chunk in eventIds.chunked(200)) {
      val placeholders = chunk.joinToString(",") { "?" }
      runSafely {
        context.contentResolver.query(
          CalendarContract.Reminders.CONTENT_URI,
          arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
          "${CalendarContract.Reminders.EVENT_ID} IN ($placeholders)",
          chunk.map { it.toString() }.toTypedArray(),
          null,
        )?.use { c ->
          while (c.moveToNext()) {
            val evId = c.getLong(0)
            val mins = c.getInt(1)
            out.getOrPut(evId) { mutableListOf() }.add(mins)
          }
        }
      }
    }
    return out.mapValues { it.value.distinct().sorted() }
  }

  private fun queryAttendees(context: Context, eventIds: List<Long>): Map<Long, List<String>> {
    val out = mutableMapOf<Long, MutableList<String>>()
    for (chunk in eventIds.chunked(200)) {
      val placeholders = chunk.joinToString(",") { "?" }
      runSafely {
        context.contentResolver.query(
          CalendarContract.Attendees.CONTENT_URI,
          arrayOf(
            CalendarContract.Attendees.EVENT_ID,
            CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_EMAIL,
          ),
          "${CalendarContract.Attendees.EVENT_ID} IN ($placeholders)",
          chunk.map { it.toString() }.toTypedArray(),
          null,
        )?.use { c ->
          while (c.moveToNext()) {
            val evId = c.getLong(0)
            val name = c.getString(1).orEmpty().trim()
            val email = c.getString(2).orEmpty().trim()
            val label = when {
              name.isNotEmpty() && email.isNotEmpty() && !name.equals(email, ignoreCase = true) -> "$name <$email>"
              name.isNotEmpty() -> name
              email.isNotEmpty() -> email
              else -> ""
            }
            if (label.isNotEmpty()) {
              out.getOrPut(evId) { mutableListOf() }.add(label)
            }
          }
        }
      }
    }
    return out.mapValues { it.value.distinct() }
  }

  // ---------- Full Scan + Optional AI Verification ----------

  data class ScanResult(
    val totalEventsScanned: Int,
    val clusters: List<DuplicateCluster>,
  )

  suspend fun scanForDuplicates(
    context: Context,
    startMillis: Long,
    endMillis: Long,
    selectedCalendarIds: Set<Long>,
    crossCalendar: Boolean,
    useAiForUnsure: Boolean,
    aiThreshold: Double,
    settings: AppSettings,
    secrets: SecretStore,
    forceAiAll: Boolean = false,
    onProgress: (String) -> Unit = {},
  ): ScanResult = withContext(Dispatchers.IO) {
    onProgress(context.getString(R.string.dup_progress_loading))
    val events = queryEventsInRange(context, startMillis, endMillis, selectedCalendarIds, ignoreHolidays = settings.duplicateIgnoreHolidays)
    onProgress(context.getString(R.string.dup_progress_analysing, events.size))
    val ignored = loadIgnoredSignatures(context)
    val minConf = if (useAiForUnsure) minOf(0.42, aiThreshold) else 0.48
    var clusters = DuplicateMatcher.findClusters(
      events = events,
      crossCalendar = crossCalendar,
      minConfidence = minConf,
      ignoredSignatures = ignored,
    )

    if ((useAiForUnsure || forceAiAll) && clusters.isNotEmpty()) {
      val updated = mutableListOf<DuplicateCluster>()
      val targetClusters = if (forceAiAll) clusters else clusters.filter { it.category == DuplicateCategory.UNSURE || it.confidence < 0.78 }
      var idx = 0
      for (cluster in clusters) {
        if (cluster in targetClusters && (forceAiAll || idx < 12)) {
          idx++
          onProgress(context.getString(R.string.dup_progress_ai, idx, targetClusters.size))
          val advice = evaluateClusterWithAi(cluster, settings, secrets, context, allowAlgorithmicFallback = false)
          if (advice != null) {
            val newConf = if (advice.isDuplicate) maxOf(cluster.confidence, advice.confidence) else minOf(cluster.confidence, advice.confidence)
            val newCat = when {
              !advice.isDuplicate && newConf < aiThreshold -> DuplicateCategory.UNSURE
              newConf >= 0.80 -> DuplicateCategory.CERTAIN
              newConf >= 0.62 -> DuplicateCategory.PROBABLE
              else -> DuplicateCategory.UNSURE
            }
            val recPrimary = advice.recommendedPrimaryId?.takeIf { id -> cluster.events.any { it.eventId == id } } ?: cluster.defaultPrimaryId
            val baseProposal = DuplicateMatcher.buildMergeProposal(cluster.events, recPrimary)
            val aiProposal = baseProposal.copy(
              title = advice.recommendedTitle?.takeIf { it.isNotBlank() } ?: baseProposal.title,
              location = advice.recommendedLocation?.takeIf { it.isNotBlank() } ?: baseProposal.location,
              description = advice.recommendedDescription?.takeIf { it.isNotBlank() } ?: baseProposal.description,
            )
            updated += cluster.copy(
              confidence = newConf,
              category = newCat,
              defaultPrimaryId = recPrimary,
              proposal = aiProposal,
              aiAdvice = advice,
            )
          } else {
            updated += cluster
          }
        } else {
          updated += cluster
        }
      }
      clusters = updated
    }

    ScanResult(totalEventsScanned = events.size, clusters = clusters)
  }

  /**
   * Evaluates a single [DuplicateCluster] with the configured AI engine (Cloud, On-Device, Self-Hosted).
   * If [allowAlgorithmicFallback] is false (default for explicit AI checks), returns null when no AI produces an answer.
   */
  suspend fun evaluateClusterWithAi(
    cluster: DuplicateCluster,
    settings: AppSettings,
    secrets: SecretStore,
    context: Context? = null,
    allowAlgorithmicFallback: Boolean = false,
    forceReprocess: Boolean = false,
  ): AiDuplicateAdvice? = withContext(Dispatchers.IO) {
    val candidates = cluster.events.map { ev ->
      de.juliandigitalproducts.notitocal.analysis.PipelineB.CandidateEvent(
        id = ev.eventId.toString(),
        title = ev.title,
        startMillis = ev.startMillis,
        endMillis = ev.endMillis,
        location = ev.location,
        description = ev.description,
      )
    }

    val userHistory = mutableListOf<de.juliandigitalproducts.notitocal.analysis.PipelineA.UserHistoryItem>()
    if (context != null) {
      runSafely {
        val pastLoc = de.juliandigitalproducts.notitocal.analysis.LocationEnricher.searchPastCalendarLocations(
          context,
          cluster.proposal.title.ifBlank { de.juliandigitalproducts.notitocal.analysis.Activity.GET_TOGETHER.title(de.juliandigitalproducts.notitocal.analysis.TextLang.app()) },
        )
        if (pastLoc != null) {
          userHistory += de.juliandigitalproducts.notitocal.analysis.PipelineA.UserHistoryItem(
            title = cluster.proposal.title,
            location = pastLoc,
            dateIso = "",
          )
        }
      }
    }
    val nearbyCandidates = mutableListOf<de.juliandigitalproducts.notitocal.analysis.PipelineA.NearbyCandidate>()
    if (settings.homeCity.isNotBlank()) {
      nearbyCandidates += de.juliandigitalproducts.notitocal.analysis.PipelineA.NearbyCandidate(
        name = de.juliandigitalproducts.notitocal.analysis.Wording.homeArea(settings.homeCity),
        address = settings.homeCity,
      )
    }

    val bResult = runSafely {
      de.juliandigitalproducts.notitocal.analysis.PipelineB.process(
        candidateEvents = candidates,
        userHistory = userHistory,
        nearbyCandidates = nearbyCandidates,
        settings = settings,
        secrets = secrets,
        context = context,
        forceReprocess = forceReprocess,
      )
    }.getOrNull()

    if (bResult != null && bResult.mergeGroups.isNotEmpty()) {
      val mg = bResult.mergeGroups.first()
      val primaryId = mg.primaryKeepId.toLongOrNull() ?: cluster.defaultPrimaryId
      return@withContext AiDuplicateAdvice(
        isDuplicate = true,
        confidence = 0.95,
        explanation = mg.mergeReason,
        recommendedPrimaryId = primaryId,
        recommendedTitle = mg.mergedResult.title,
        recommendedLocation = mg.mergedResult.locationAddress.ifBlank { mg.mergedResult.locationTitle },
        recommendedDescription = mg.mergedResult.description,
      )
    }

    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    val eventsText = cluster.events.joinToString("\n") { ev ->
      val timeStr = if (ev.allDay) "${ev.localDate()} (Ganztägig)" else "${fmt.format(Instant.ofEpochMilli(ev.startMillis))} - ${fmt.format(Instant.ofEpochMilli(ev.endMillis))}"
      "- ID=${ev.eventId} | Kalender=${ev.calendarName} | Titel=\"${ev.title}\" | Zeit=$timeStr | Ort=\"${ev.location}\" | Beschreibung=\"${ev.description.take(200)}\""
    }
    val systemPrompt = """
      Du bist ein Kalender-Experte in NotiToCal. Prüfe, ob die folgenden Kalendereinträge dasselbe reale Ereignis (Duplikat) beschreiben.
      Beachte Tippfehler (z.B. „Mittagessen“ vs. „Mittagsessen“), Wortzusammensetzungen („Mittagessenbesuch“), Ortsabkürzungen („Lev“ = „Leverkusen“) sowie den Fall, dass ein Ganztagestermin und ein Uhrzeit-Termin am selben Tag dasselbe Ereignis meinen.
      Antworte AUSSCHLIESSLICH mit einem JSON-Objekt:
      {"isDuplicate":true,"confidence":0.92,"explanation":"Kurze Begründung auf ${java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.GERMAN)}","recommendedPrimaryId":123,"recommendedTitle":"Bester kombinierter Titel","recommendedLocation":"Vollständiger Ort","recommendedDescription":"Zusammengeführte Beschreibung"}
    """.trimIndent()

    val cloudKey = secrets.get(SecretKeys.llm(settings.provider))
    val hasCloudKey = !settings.provider.needsKey || cloudKey.isNotBlank()
    val onDeviceOk = context != null && (settings.onDeviceModelFile.isNotBlank() || (settings.onDeviceModelUri.isNotBlank() && SharedModel.accessible(context, settings.onDeviceModelUri)))

    val rawAnswer: String? = when {
      // 1. Cloud LLM
      settings.cloudAllowed && hasCloudKey -> runSafely {
        Pseudonymizer.around(settings, systemPrompt, eventsText) { sys, usr ->
          CloudLlmClient(settings, cloudKey).complete(sys, usr, json = settings.jsonMode)
        }
      }.getOrNull()

      // 2. Self-Hosted LLM
      settings.engine == AiEngine.SELF_HOSTED && settings.selfHostedUrl.isNotBlank() -> runSafely {
        Pseudonymizer.around(settings, systemPrompt, eventsText) { sys, usr ->
          val body = buildJsonObject {
            put("system", sys)
            put("user", usr)
          }
          Http.postJson(SelfHosted.url(settings.selfHostedUrl, "complete"), body.toString(), SelfHosted.auth(secrets.get(SecretKeys.SELF_HOSTED)))
        }
      }.getOrNull()

      // 3. On-Device LLM (MediaPipe .task model)
      settings.engine == AiEngine.ON_DEVICE && onDeviceOk && context != null -> runSafely {
        val masked = Pseudonymizer.apply(eventsText, settings.aliases)
        OnDeviceRunner.complete(
          context = context,
          s = settings,
          system = systemPrompt,
          user = masked,
          maxTokens = settings.maxTokens,
          prefill = "{\"isDuplicate\":",
        )
      }.getOrNull()

      // No cloud fallback: a stored key is only used with the engine "Cloud" (M5).
      // 4. Fallback to the on-device model (stays on the phone) if available even if engine was RULES
      onDeviceOk && context != null -> runSafely {
        val masked = Pseudonymizer.apply(eventsText, settings.aliases)
        OnDeviceRunner.complete(
          context = context,
          s = settings,
          system = systemPrompt,
          user = masked,
          maxTokens = settings.maxTokens,
          prefill = "{\"isDuplicate\":",
        )
      }.getOrNull()

      else -> null
    }

    if (!rawAnswer.isNullOrBlank()) {
      val parsed = parseAiAdviceJson(rawAnswer)
      if (parsed != null) return@withContext parsed
    }

    if (!allowAlgorithmicFallback) {
      return@withContext null
    }

    // Smart local semantic advisor fallback (only when allowAlgorithmicFallback == true)
    val best = cluster.events.maxByOrNull { DuplicateMatcher.dataQualityScore(it) } ?: cluster.events.first()
    val prop = DuplicateMatcher.buildMergeProposal(cluster.events, best.eventId)
    val hasTimedAndAllDay = cluster.events.any { it.allDay } && cluster.events.any { !it.allDay }
    val ctx = context ?: return@withContext null
    val explanation = buildString {
      append(ctx.getString(R.string.dup_explain_same_day, cluster.events.size))
      if (hasTimedAndAllDay) append(" " + ctx.getString(R.string.dup_explain_all_day_mix))
      if (prop.location.isNotBlank()) append(" " + ctx.getString(R.string.dup_explain_location, prop.location))
      append(" " + ctx.getString(R.string.dup_explain_recommend, best.title))
    }
    AiDuplicateAdvice(
      isDuplicate = cluster.confidence >= 0.52,
      confidence = (cluster.confidence + 0.12).coerceAtMost(0.95),
      explanation = explanation,
      recommendedPrimaryId = best.eventId,
      recommendedTitle = prop.title,
      recommendedLocation = prop.location,
      recommendedDescription = prop.description,
    )
  }

  private fun parseAiAdviceJson(raw: String): AiDuplicateAdvice? = runSafely {
    val clean = Thinking.strip(raw)
    val start = clean.indexOf('{')
    val end = clean.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val obj: JsonObject = AppJson.parseToJsonElement(clean.substring(start, end + 1)).jsonObject
    val isDup = (obj["isDuplicate"] as? JsonPrimitive)?.booleanOrNull ?: true
    val conf = (obj["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.8
    val exp = (obj["explanation"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val recId = (obj["recommendedPrimaryId"] as? JsonPrimitive)?.longOrNull
    val recTitle = (obj["recommendedTitle"] as? JsonPrimitive)?.contentOrNull
    val recLoc = (obj["recommendedLocation"] as? JsonPrimitive)?.contentOrNull
    val recDesc = (obj["recommendedDescription"] as? JsonPrimitive)?.contentOrNull
    AiDuplicateAdvice(
      isDuplicate = isDup,
      confidence = conf.coerceIn(0.0, 1.0),
      explanation = exp,
      recommendedPrimaryId = recId,
      recommendedTitle = recTitle,
      recommendedLocation = recLoc,
      recommendedDescription = recDesc,
    )
  }.getOrNull()

  // ---------- Merge Execution & 30-Day Undo Store ----------

  /**
   * Merges [cluster] according to [proposal] and [removalMode], saving a full undo snapshot first.
   */
  suspend fun executeMerge(
    context: Context,
    cluster: DuplicateCluster,
    proposal: MergeProposal,
    removalMode: RemovalMode,
    settings: AppSettings? = null,
  ): Result<MergeHistoryRecord> = withContext(Dispatchers.IO) {
    runSafely {
      check(hasWritePermission(context)) { context.getString(R.string.dup_err_no_write) }

      val primaryBefore = cluster.events.firstOrNull { it.eventId == proposal.primaryEventId } ?: cluster.events.first()
      val secondaryEvents = cluster.events.filter { it.eventId != primaryBefore.eventId }
      val isRecurring = primaryBefore.rrule.isNotBlank()

      var insertedNewPrimaryId: Long? = null

      if (primaryBefore.isWritable && isRecurring) {
        // A series: proposal.startMillis is one occurrence (Instances.BEGIN). Writing it to DTSTART would move the
        // start of the whole series and drop every earlier occurrence, so only the texts are merged.
        context.contentResolver.update(
          ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, primaryBefore.eventId),
          ContentValues().apply {
            put(CalendarContract.Events.TITLE, proposal.title)
            put(CalendarContract.Events.EVENT_LOCATION, proposal.location)
            put(CalendarContract.Events.DESCRIPTION, proposal.description)
          },
          null,
          null,
        )
        addReminders(context, primaryBefore.eventId, proposal.reminderMinutes - primaryBefore.reminderMinutes.toSet())
      } else if (primaryBefore.isWritable) {
        val primaryValues = ContentValues().apply {
          put(CalendarContract.Events.TITLE, proposal.title)
          put(CalendarContract.Events.EVENT_LOCATION, proposal.location)
          put(CalendarContract.Events.DESCRIPTION, proposal.description)
          put(CalendarContract.Events.ALL_DAY, if (proposal.allDay) 1 else 0)
          put(CalendarContract.Events.DTSTART, proposal.startMillis)
          put(CalendarContract.Events.DTEND, proposal.endMillis)
          putNull(CalendarContract.Events.DURATION)
          put(CalendarContract.Events.EVENT_TIMEZONE, if (proposal.allDay) "UTC" else ZoneId.systemDefault().id)
          put(CalendarContract.Events.HAS_ALARM, if (proposal.reminderMinutes.isNotEmpty() || primaryBefore.reminderMinutes.isNotEmpty()) 1 else 0)
        }

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, primaryBefore.eventId)
        val timeUpdateResult = runSafely {
          context.contentResolver.update(uri, primaryValues, null, null)
        }
        if (timeUpdateResult.isFailure || timeUpdateResult.getOrNull() == 0) {
          // Fallback for recurring/synced events that reject DTSTART/DTEND/DURATION changes
          runSafely {
            context.contentResolver.update(
              uri,
              ContentValues().apply {
                put(CalendarContract.Events.TITLE, proposal.title)
                put(CalendarContract.Events.EVENT_LOCATION, proposal.location)
                put(CalendarContract.Events.DESCRIPTION, proposal.description)
              },
              null,
              null,
            )
          }
        }
        addReminders(context, primaryBefore.eventId, proposal.reminderMinutes - primaryBefore.reminderMinutes.toSet())
      } else if (isRecurring) {
        error(context.getString(R.string.dup_err_readonly_series))
      } else {
        // Schreibgeschützter Primärtermin: Das zusammengeführte Event in den ersten beschreibbaren Kalender (oder settings.calendarId) als neues Event einfügen!
        val appSettings = settings ?: (context.applicationContext as? de.juliandigitalproducts.notitocal.NotiToCalApp)?.settings?.current
        val calendars = allCalendars(context)
        val targetCalId = appSettings?.calendarId?.takeIf { id -> calendars.any { it.id == id && it.isWritable } }
          ?: calendars.firstOrNull { it.isWritable }?.id
          ?: error(context.getString(R.string.dup_err_no_writable))

        val newValues = ContentValues().apply {
          put(CalendarContract.Events.CALENDAR_ID, targetCalId)
          put(CalendarContract.Events.TITLE, proposal.title)
          put(CalendarContract.Events.EVENT_LOCATION, proposal.location)
          put(CalendarContract.Events.DESCRIPTION, proposal.description)
          put(CalendarContract.Events.ALL_DAY, if (proposal.allDay) 1 else 0)
          put(CalendarContract.Events.DTSTART, proposal.startMillis)
          put(CalendarContract.Events.DTEND, proposal.endMillis)
          put(CalendarContract.Events.EVENT_TIMEZONE, if (proposal.allDay) "UTC" else ZoneId.systemDefault().id)
          put(CalendarContract.Events.HAS_ALARM, if (proposal.reminderMinutes.isNotEmpty()) 1 else 0)
        }
        val insertedUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, newValues)
        val newId = insertedUri?.lastPathSegment?.toLongOrNull()
        insertedNewPrimaryId = newId
        if (newId != null) addReminders(context, newId, proposal.reminderMinutes)
      }

      // 2. Remove or mark secondary events (Serientermine mit rrule.isNotBlank() dürfen NICHT gelöscht werden!)
      for (sec in secondaryEvents) {
        if (!sec.isWritable) continue
        val secUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, sec.eventId)
        runSafely {
          if (onlyMark(sec, removalMode)) {
            val markedTitle = if (sec.title.startsWith("[Duplikat]")) sec.title else "[Duplikat] ${sec.title}"
            context.contentResolver.update(
              secUri,
              ContentValues().apply {
                put(CalendarContract.Events.TITLE, markedTitle)
              },
              null,
              null,
            )
          } else {
            context.contentResolver.delete(secUri, null, null)
          }
        }
      }

      val primaryAfter = primaryBefore.copy(
        eventId = insertedNewPrimaryId ?: primaryBefore.eventId,
        title = proposal.title,
        location = proposal.location,
        description = proposal.description,
        startMillis = proposal.startMillis,
        endMillis = proposal.endMillis,
        allDay = proposal.allDay,
        attendees = proposal.attendees,
        reminderMinutes = proposal.reminderMinutes,
      )

      val record = MergeHistoryRecord(
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        clusterId = cluster.id,
        primaryBefore = primaryBefore,
        primaryAfter = primaryAfter,
        removedEvents = secondaryEvents,
        removalMode = removalMode,
        summary = context.getString(R.string.dup_history_summary, proposal.title, secondaryEvents.size + 1),
        undone = false,
      )
      saveHistoryRecord(context, record)
      ignoreCluster(context, cluster.id)
      record
    }
  }

  /**
   * Reverts a previous merge by restoring [MergeHistoryRecord.primaryBefore] and re-inserting
   * (or unmarking) all [MergeHistoryRecord.removedEvents].
   */
  suspend fun undoMerge(context: Context, recordId: String): Result<MergeHistoryRecord> = withContext(Dispatchers.IO) {
    runSafely {
      check(hasWritePermission(context)) { context.getString(R.string.dup_err_no_write) }
      val records = loadHistoryRecords(context).toMutableList()
      val idx = records.indexOfFirst { it.id == recordId }
      check(idx >= 0) { context.getString(R.string.dup_err_not_found) }
      val rec = records[idx]
      check(!rec.undone) { context.getString(R.string.dup_err_already_undone) }

      // 1. Restore primaryBefore (or delete newly created primary if primaryBefore wasn't writable)
      val primary = rec.primaryBefore
      if (!primary.isWritable) {
        if (rec.primaryAfter.eventId != primary.eventId) {
          runSafely {
            context.contentResolver.delete(
              ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rec.primaryAfter.eventId),
              null,
              null,
            )
          }
        }
      } else {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, primary.eventId)
        val isRecurring = primary.rrule.isNotBlank()
        val updated = runSafely {
          context.contentResolver.update(
            uri,
            ContentValues().apply {
              put(CalendarContract.Events.TITLE, primary.title)
              put(CalendarContract.Events.EVENT_LOCATION, primary.location)
              put(CalendarContract.Events.DESCRIPTION, primary.description)
              // A series was never moved by the merge (only its texts changed), so its schedule stays untouched.
              if (!isRecurring) {
                put(CalendarContract.Events.ALL_DAY, if (primary.allDay) 1 else 0)
                put(CalendarContract.Events.DTSTART, primary.startMillis)
                put(CalendarContract.Events.DTEND, primary.endMillis)
                putNull(CalendarContract.Events.DURATION)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (primary.allDay) "UTC" else primary.timezone.ifBlank { ZoneId.systemDefault().id })
              }
            },
            null,
            null,
          )
        }.getOrDefault(0)
        // Reminders the merge added are removed again.
        val added = rec.primaryAfter.reminderMinutes.filter { it !in primary.reminderMinutes }
        if (updated > 0 && added.isNotEmpty()) {
          runSafely {
            context.contentResolver.delete(
              CalendarContract.Reminders.CONTENT_URI,
              "${CalendarContract.Reminders.EVENT_ID} = ? AND ${CalendarContract.Reminders.MINUTES} IN (${added.joinToString(",") { "?" }})",
              arrayOf(primary.eventId.toString()) + added.map { it.toString() },
            )
          }
        }
        // If the primary event had been deleted externally in the meantime, re-insert it
        if (updated <= 0) {
          runSafely { reinsertEvent(context, primary) }
        }
      }

      // 2. Restore secondary events
      for (sec in rec.removedEvents) {
        if (!sec.isWritable) continue
        runSafely {
          if (onlyMark(sec, rec.removalMode)) {
            val secUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, sec.eventId)
            val updated = context.contentResolver.update(
              secUri,
              ContentValues().apply {
                put(CalendarContract.Events.TITLE, sec.title)
                put(CalendarContract.Events.DESCRIPTION, sec.description)
              },
              null,
              null,
            )
            if (updated <= 0) reinsertEvent(context, sec)
          } else {
            reinsertEvent(context, sec)
          }
        }
      }

      val updatedRecord = rec.copy(undone = true)
      records[idx] = updatedRecord
      writeHistoryRecords(context, records)
      unignoreCluster(context, rec.clusterId)
      updatedRecord
    }
  }

  private fun reinsertEvent(context: Context, ev: CalendarEventItem) {
    val isRecurring = ev.rrule.isNotBlank()
    val durationMinutes = ((ev.endMillis - ev.startMillis) / 60_000L).coerceAtLeast(1L)
    val values = ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, ev.calendarId)
      put(CalendarContract.Events.TITLE, ev.title)
      put(CalendarContract.Events.DESCRIPTION, ev.description)
      put(CalendarContract.Events.EVENT_LOCATION, ev.location)
      put(CalendarContract.Events.ALL_DAY, if (ev.allDay) 1 else 0)
      put(CalendarContract.Events.DTSTART, ev.startMillis)
      if (isRecurring) {
        put(CalendarContract.Events.RRULE, ev.rrule)
        put(CalendarContract.Events.DURATION, "PT${durationMinutes}M")
        putNull(CalendarContract.Events.DTEND)
      } else {
        put(CalendarContract.Events.DTEND, ev.endMillis)
        putNull(CalendarContract.Events.DURATION)
      }
      put(CalendarContract.Events.EVENT_TIMEZONE, if (ev.allDay) "UTC" else ev.timezone.ifBlank { ZoneId.systemDefault().id })
      put(CalendarContract.Events.HAS_ALARM, if (ev.reminderMinutes.isNotEmpty()) 1 else 0)
    }
    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
    val newId = uri?.lastPathSegment?.toLongOrNull() ?: return
    addReminders(context, newId, ev.reminderMinutes)
  }

  private fun addReminders(context: Context, eventId: Long, minutes: Collection<Int>) {
    for (min in minutes.distinct()) {
      runSafely {
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
          put(CalendarContract.Reminders.EVENT_ID, eventId)
          put(CalendarContract.Reminders.MINUTES, min)
          put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
      }
    }
  }

  /**
   * Secondary events that are only marked "[Duplikat]" instead of deleted: series (deleting one would delete all
   * occurrences) and events with guests (deleting an event you organise sends cancellations to every guest, and
   * an undo could not invite them again).
   */
  internal fun onlyMark(ev: CalendarEventItem, mode: RemovalMode): Boolean =
    mode == RemovalMode.MARK_DUPLICATE || ev.rrule.isNotBlank() || ev.attendees.isNotEmpty()

  // ---------- Local Persistence (30-Day History & Ignored Pairs) ----------

  fun loadHistoryRecords(context: Context): List<MergeHistoryRecord> {
    val file = File(context.filesDir, HISTORY_FILE)
    if (!file.exists()) return emptyList()
    val cutoff = System.currentTimeMillis() - HISTORY_RETENTION_MS
    val all = runSafely {
      AppJson.decodeFromString(ListSerializer(MergeHistoryRecord.serializer()), file.readText())
    }.getOrElse {
      // Keep an unreadable history file for inspection instead of silently overwriting it with an empty list.
      runSafely { file.renameTo(File(context.filesDir, "$HISTORY_FILE.corrupt-${System.currentTimeMillis()}")) }
      return emptyList()
    }
    val pruned = all.filter { it.timestamp >= cutoff }.sortedByDescending { it.timestamp }
    if (pruned.size != all.size) {
      writeHistoryRecords(context, pruned)
    }
    return pruned
  }

  private fun saveHistoryRecord(context: Context, record: MergeHistoryRecord) {
    val existing = loadHistoryRecords(context).toMutableList()
    existing.add(0, record)
    writeHistoryRecords(context, existing.take(200))
  }

  private fun writeHistoryRecords(context: Context, records: List<MergeHistoryRecord>) {
    runSafely {
      val file = File(context.filesDir, HISTORY_FILE)
      file.writeText(AppJson.encodeToString(ListSerializer(MergeHistoryRecord.serializer()), records))
    }
  }

  fun loadIgnoredSignatures(context: Context): Set<String> {
    val file = File(context.filesDir, IGNORED_FILE)
    if (!file.exists()) return emptySet()
    return runSafely {
      AppJson.decodeFromString(SetSerializer(String.serializer()), file.readText())
    }.getOrDefault(emptySet())
  }

  fun ignoreCluster(context: Context, signature: String) {
    val next = loadIgnoredSignatures(context) + signature
    runSafely {
      File(context.filesDir, IGNORED_FILE).writeText(AppJson.encodeToString(SetSerializer(String.serializer()), next))
    }
  }

  fun unignoreCluster(context: Context, signature: String) {
    val next = loadIgnoredSignatures(context) - signature
    runSafely {
      File(context.filesDir, IGNORED_FILE).writeText(AppJson.encodeToString(SetSerializer(String.serializer()), next))
    }
  }

  fun clearIgnoredClusters(context: Context) {
    runSafely { File(context.filesDir, IGNORED_FILE).delete() }
  }

  fun restoreHistoryAndIgnored(
    context: Context,
    records: List<MergeHistoryRecord>,
    ignored: Set<String>,
  ) {
    if (records.isNotEmpty()) {
      val merged = (records + loadHistoryRecords(context)).distinctBy { it.id }.sortedByDescending { it.timestamp }.take(200)
      writeHistoryRecords(context, merged)
    }
    if (ignored.isNotEmpty()) {
      val next = loadIgnoredSignatures(context) + ignored
      runSafely {
        File(context.filesDir, IGNORED_FILE).writeText(AppJson.encodeToString(SetSerializer(String.serializer()), next))
      }
    }
  }

  fun exportClustersBackupJson(clusters: List<DuplicateCluster>): String {
    val allEvents = clusters.flatMap { it.events }.distinctBy { "${it.calendarId}:${it.eventId}" }
    return AppJson.encodeToString(
      DuplicateBackupContainer.serializer(),
      DuplicateBackupContainer(events = allEvents),
    )
  }
}
