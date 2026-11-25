package de.juliandigitalproducts.notitocal.pipeline

import android.content.Intent
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.analysis.Analyzer
import de.juliandigitalproducts.notitocal.analysis.Decision
import de.juliandigitalproducts.notitocal.analysis.StyleTemplates
import de.juliandigitalproducts.notitocal.analysis.TextNormalizer
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.HistoryPolicy
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.model.Why
import de.juliandigitalproducts.notitocal.notify.Notifier
import de.juliandigitalproducts.notitocal.output.DeliveryResult
import de.juliandigitalproducts.notitocal.output.DuplicateJudge
import de.juliandigitalproducts.notitocal.output.Privacy
import de.juliandigitalproducts.notitocal.output.SourceInfo
import de.juliandigitalproducts.notitocal.output.Targets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import de.juliandigitalproducts.notitocal.model.runSafely

/** Filter → analyse → decide → log → deliver or ask. */
class Pipeline(private val app: NotiToCalApp) {
  private val lock = Mutex()

  /**
   * Clock for the decisions that depend on the current time (an appointment already in the past, quiet hours). The
   * analysis itself uses the arrival time of the message. Tests pin it to a fixed instant.
   */
  internal var clock: java.time.Clock = java.time.Clock.systemDefaultZone()

  data class Result(val logId: Long, val detection: Detection, val decision: Decision, val note: String)

  /**
   * [userInitiated]: shared or typed by the user – always shown for confirmation, never filtered.
   * [logFiltered]: also log content that the filters drop before analysis (used by "behave like real" tests), so the
   * user can see why. Otherwise such content returns null.
   * [quiet]: part of a collected analysis – no "added" notification per entry (one summary instead).
   */
  suspend fun process(raw: Captured, userInitiated: Boolean = false, logFiltered: Boolean = false, quiet: Boolean = false): Result? {
    // C17: the analysis (a model call can take a minute) runs outside the lock, so one hanging call no longer blocks
    // every other message. The same text is never analysed twice at once ([inFlight]); the lock only guards the
    // short duplicate check + log insert.
    val key = HistoryPolicy.contentHash(TextNormalizer.normalize(raw.text))
    if (!userInitiated && !inFlight.add(key)) return null
    try {
      return processUnlocked(raw, userInitiated, logFiltered, quiet)
    } finally {
      if (!userInitiated) inFlight.remove(key)
    }
  }

  private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

  private suspend fun processUnlocked(raw: Captured, userInitiated: Boolean, logFiltered: Boolean, quiet: Boolean): Result? {
    val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    val wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "NotiToCal:Pipeline")
    wakeLock?.acquire(60_000L)
    try {
      val s = app.settings.current
    val c = raw.copy(text = TextNormalizer.normalize(raw.text), sender = TextNormalizer.fixEncoding(raw.sender))
    // Same text from another app (SMS + WhatsApp, notification + screenshot) counts as the same message.
    val hash = HistoryPolicy.contentHash(c.text)
    if (!userInitiated) {
      val pre = preCheck(c, s, hash)
      if (pre != null) {
        if (!logFiltered && c.source != SourceType.VOICE_MEMO) {
          dropProcessed()
          return null
        }
        val d = Detection(Kind.NONE, engine = "filter", why = pre.first.name, whyLimit = pre.second, realDecision = Decision.IGNORE.name)
        // A filtered voice memo is listed too, but its text only with "Keep message text" on.
        val shownText = if (s.storeMessageText || logFiltered) c.text else ""
        val id = app.log.insert(c.source, c.packageName, c.appLabel, c.sender, shownText, d, Status.IGNORED, hash + ":test", fingerprintText = c.text, isGroup = c.isGroup)
        if (c.source == SourceType.VOICE_MEMO) {
          runSafely { de.juliandigitalproducts.notitocal.widget.VoiceMemosWidgetProvider.updateAll(app) }
        }
        dropProcessed()
        return Result(id, d, Decision.IGNORE, pre.first.name)
      }
    }

    // Texts typed into the app to try it out are no one's message: they stay visible for the test.
    val keepText = c.source == SourceType.MANUAL || logFiltered || isSelfTest(c)
    // "Sender in title" for every engine, then the chosen style for entries of the offline rules (a model gets it in
    // its prompt), with the message as notes only when it may be kept.
    val language = app.resources.configuration.locales[0]?.language ?: java.util.Locale.getDefault().language
    fun styled(d: Detection) = StyleTemplates.applyToRuleEntry(
      Privacy.titleForTargets(d, c.sender, s), s.eventTemplateId, s.taskTemplateId, language, withMessage = s.storeMessageText || keepText,
    )

    // Relative dates ("tomorrow") refer to when the message arrived, also when it is analysed hours later.
    val received = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.postedAt), ZoneId.systemDefault())

    // Update conversation session thread for multi-message aggregation
    val sessionState = de.juliandigitalproducts.notitocal.analysis.ConversationAggregator.addMessageAndEvaluate(
      source = c.source,
      packageName = c.packageName,
      sender = c.sender,
      text = c.text,
      timestamp = c.postedAt,
      nowDateTime = received,
      s = s,
      history = { since ->
        // An empty app or sender would match every shared/manual entry: no history then.
        if (c.packageName.isBlank() || c.sender.isBlank()) emptyList()
        else app.log.conversationSince(c.packageName, c.sender, since).map {
          de.juliandigitalproducts.notitocal.analysis.ConversationAggregator.SessionMessage(it.sender, it.text, it.created)
        }
      },
    )

    // Context for the AI only from the same chat (same person or same group of the same app), never from other chats.
    val recentChatMessages = app.log.entries.value
      .filter { ChatScope.sameChat(c.packageName, c.sender, it.packageName, it.sender, c.isGroup, it.isGroup) && it.text.isNotBlank() }
      .take(6)
      .map { it.text }

    // Only appointments of the same chat are offered to the model (and so may leave the device with a remote engine).
    val existingEvents = app.log.entries.value
      .filter { it.detection.kind == Kind.EVENT && it.status in listOf(Status.ADDED, Status.PENDING, Status.PARTIAL) }
      .filter { ChatScope.sameChat(c.packageName, c.sender, it.packageName, it.sender, c.isGroup, it.isGroup) }
      .take(10)
      .map {
        // These go into the model request: without the sender's name ("Treffen (Anna)") unless it may be shared.
        val shown = Privacy.forAi(it.detection, it.sender, s)
        de.juliandigitalproducts.notitocal.analysis.Prompt.ExistingEventContext(
          id = it.id.toString(),
          title = shown.title,
          start = it.detection.start,
          location = shown.location,
          isProvisionalDay = it.detection.isProvisionalDay,
        )
      }

    // Provisional events ("im Januar") of the same chat from the last weeks. Another member of the same group may
    // complete one, but only when the new detection is relevant to it (checked after the analysis, see [relevantTo]).
    val provisionalWindow = System.currentTimeMillis() - java.time.Duration.ofDays(PROVISIONAL_DAYS).toMillis()
    val provisionalCandidates = app.log.entries.value.filter {
      it.created >= provisionalWindow &&
      it.detection.kind == Kind.EVENT &&
      it.detection.isProvisionalDay &&
      ChatScope.sameChat(c.packageName, c.sender, it.packageName, it.sender, c.isGroup, it.isGroup)
    }
    val provisionalCandidate = provisionalCandidates.firstOrNull { ChatScope.sameSender(c.packageName, c.sender, it.packageName, it.sender) }
      ?: provisionalCandidates.firstOrNull()
    val provisionalDate = provisionalCandidate?.detection?.start?.let {
      runSafely { java.time.LocalDate.parse(it.substring(0, 10)) }.getOrNull()
    }

    // Mode-aware analysis:
    // FAST: pure algorithm (rules + regex + slot engine), 0 token cost, minimal latency
    // MEDIUM: algorithm first; if confidence >= 0.75 use rules, else trigger AI model
    // THOROUGH: full AI semantic analysis with algorithmic pre-structuring
    val rawDetection = when (s.locationRecognitionMode) {
      de.juliandigitalproducts.notitocal.data.LocationRecognitionMode.FAST -> {
        val cleanText = de.juliandigitalproducts.notitocal.analysis.RuleEngine.stripDebugAndSystemText(c.text)
        de.juliandigitalproducts.notitocal.analysis.RuleEngine.analyze(cleanText, c.sender, received, s.defaultEventMinutes, s.addressSuggestions, provisionalDate)
      }
      de.juliandigitalproducts.notitocal.data.LocationRecognitionMode.MEDIUM -> {
        val cleanText = de.juliandigitalproducts.notitocal.analysis.RuleEngine.stripDebugAndSystemText(c.text)
        val ruleResult = de.juliandigitalproducts.notitocal.analysis.RuleEngine.analyze(cleanText, c.sender, received, s.defaultEventMinutes, s.addressSuggestions, provisionalDate)
        if (ruleResult.kind != Kind.NONE && ruleResult.confidence >= 0.75) {
          ruleResult
        } else {
          app.analyzer.analyze(c, s, received, recentChatMessages, existingEvents)
        }
      }
      de.juliandigitalproducts.notitocal.data.LocationRecognitionMode.THOROUGH -> {
        app.analyzer.analyze(c, s, received, recentChatMessages, existingEvents)
      }
    }

    // Smart Patching & UPDATE_EXISTING.
    // The model may only name an event we offered it (existingEvents) – an id smuggled in through a message is ignored.
    val offeredIds = existingEvents.mapNotNull { it.id.toLongOrNull() }.toSet()
    val modelTarget = rawDetection.targetEventId?.toLongOrNull()?.takeIf { rawDetection.isUpdateExisting() && it in offeredIds }
    val provisionalTarget = if (modelTarget == null && rawDetection.kind == Kind.EVENT && !rawDetection.isProvisionalDay && rawDetection.start != null) {
      provisionalCandidates.firstOrNull { relevantTo(it, rawDetection, c) }
    } else null
    val isSmartUpdate = modelTarget != null || provisionalTarget != null
    // Blocked keywords still apply to updates.
    val updateAllowed = isSmartUpdate && Analyzer.decide(rawDetection, c.text, s, LocalDateTime.now(clock)).decision != Decision.IGNORE
    if (updateAllowed) {
      val targetId = modelTarget ?: provisionalTarget?.id
      val existingEntry = targetId?.let { app.log.get(it) }
      if (existingEntry != null) return patchExisting(existingEntry, styled(rawDetection), c, s, quiet)
    }

    // Several messages together can form an appointment ("am 14ten" + "Musterstraße 3" + "ok machen wir so").
    // Such an assembled event is only suggested (ASK), never added automatically, see below.
    val promoted = sessionState.promotable && rawDetection.kind == Kind.NONE
    val effectiveDetection = if (promoted) {
      val st = sessionState.street ?: ""
      val nr = sessionState.houseNumber ?: ""
      val loc = sessionState.location ?: if (st.isNotBlank()) "$st $nr".trim() else null
      val allDay = sessionState.time == null
      val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
      val startDt = sessionState.time?.let { sessionState.date!!.atTime(it) }
      val endDt = startDt?.let { st0 ->
        sessionState.endTime?.let { e -> sessionState.date!!.atTime(e).let { if (it.isAfter(st0)) it else it.plusDays(1) } }
          ?: st0.plusMinutes((sessionState.durationMinutes ?: s.defaultEventMinutes).toLong())
      }
      Detection(
        kind = Kind.EVENT,
        title = sessionState.title ?: app.getString(R.string.default_meeting_title),
        description = if (s.storeMessageText) c.text else "",
        start = startDt?.format(fmt) ?: sessionState.date.toString(),
        end = endDt?.format(fmt),
        allDay = allDay,
        location = loc,
        confidence = sessionState.confidence,
        reason = app.getString(R.string.conversation_reason, sessionState.messageCount),
        engine = SESSION_ENGINE,
        isEndEstimated = sessionState.isEndEstimated,
        estimationReason = sessionState.estimationReason,
      )
    } else rawDetection

    // Location lookups need the network: while offline they are postponed and done later (Deferred).
    val wantsLocation = s.locationEnrichmentEnabled && effectiveDetection.kind == Kind.EVENT
    val online = !wantsLocation || Deferred.isOnline(app)
    val detection = when {
      wantsLocation && online ->
        de.juliandigitalproducts.notitocal.analysis.LocationEnricher.enrich(app, effectiveDetection, c.text, s, c.sender)
      wantsLocation && de.juliandigitalproducts.notitocal.analysis.RuleEngine.fullAddress(effectiveDetection.location.orEmpty()) == null -> {
        Deferred.scheduleLocationRetry(app)
        effectiveDetection.copy(locationSource = Deferred.LOCATION_PENDING)
      }
      else -> effectiveDetection
    }
    val outcome = Analyzer.decide(styled(detection), c.text, s, LocalDateTime.now(clock))
    var realDecision = if (detection.engine == SESSION_ENGINE && outcome.decision == Decision.AUTO) Decision.ASK else outcome.decision
    var why = outcome.why
    var whyValue = outcome.value
    var whyLimit = outcome.limit
    var note = outcome.note

    // Same day, related content, other clock time: the end of an event, a moved appointment – or a second one.
    // Decided before the lock, because an unsure case asks the AI (that can take a while).
    // A short follow-up ("Ende der Veranstaltung 15 Uhr") is often too weak for an appointment of its own (below the
    // minimum confidence); as an end time or a move of an existing appointment it still counts.
    var possibleDuplicateOf: Long? = null
    val weakOnly = realDecision == Decision.IGNORE && why in setOf(Why.BELOW_MIN, Why.ASK_CONFIDENCE) &&
      outcome.detection.kind == Kind.EVENT && outcome.detection.hasExplicitTime()
    if ((realDecision != Decision.IGNORE || weakOnly) && c.source != SourceType.MANUAL && !isSelfTest(c)) {
      val related = app.log.classify(outcome.detection, c.text, maxOf(s.dedupeMinutes, 12 * 60), c.sender)
        ?.takeIf { !weakOnly || it.second == DuplicateJudge.Verdict.END_TIME || it.second == DuplicateJudge.Verdict.MOVED }
      if (related != null) {
        val (entry, verdict) = related
        val newStart = outcome.detection.start
        when (verdict) {
          DuplicateJudge.Verdict.END_TIME -> {
            // "Ende der Veranstaltung 15 Uhr": the new time is the end of the existing event, not a second event.
            val upd = outcome.detection.copy(title = "", start = entry.detection.start, end = newStart, location = outcome.detection.location)
            dropProcessed()
            return patchExisting(entry, upd, c, s, quiet)
          }
          DuplicateJudge.Verdict.MOVED -> return patchExisting(entry, outcome.detection.copy(end = null), c, s, quiet)
          DuplicateJudge.Verdict.UNCERTAIN -> when (
            DuplicateJudge.askAi(app, s, app.secrets, Privacy.forAi(entry.detection, entry.sender, s), Privacy.forAi(outcome.detection, c.sender, s), c.text)
          ) {
            true -> return patchExisting(entry, outcome.detection.copy(end = null), c, s, quiet)
            false -> Unit // the AI says: a second appointment
            null -> possibleDuplicateOf = entry.id // no AI: keep both, but let the user confirm the new one
          }
          DuplicateJudge.Verdict.SAME, DuplicateJudge.Verdict.DIFFERENT -> Unit // SAME is handled below, under the lock
        }
      }
    }
    if (possibleDuplicateOf != null && realDecision == Decision.AUTO) {
      realDecision = Decision.ASK
      why = Why.POSSIBLE_DUPLICATE
      whyValue = possibleDuplicateOf.toDouble()
      note = "possible duplicate of #$possibleDuplicateOf"
    }

    val (id, d, decision) = lock.withLock {
      if (realDecision != Decision.IGNORE) {
        app.log.findDuplicate(outcome.detection, c.text, maxOf(s.dedupeMinutes, 12 * 60), c.sender)?.let { dup ->
          realDecision = Decision.IGNORE
          why = Why.DUPLICATE
          whyValue = dup.id.toDouble()
          note = "duplicate of #${dup.id}"
        }
      }
      val isSelfTest = isSelfTest(c)
      val found = outcome.detection.kind in setOf(Kind.EVENT, Kind.TASK)
      val decision = when {
        isSelfTest -> if (found) Decision.ASK else Decision.IGNORE
        !userInitiated -> realDecision
        // "Test text" only shows what would happen; it never adds anything by itself.
        c.source == SourceType.MANUAL -> if (found) Decision.ASK else Decision.IGNORE
        // Shared content is added like a notification would be, and otherwise shown for confirmation.
        realDecision == Decision.AUTO -> Decision.AUTO
        found -> Decision.ASK
        else -> Decision.IGNORE
      }
      val route = Router.match(s.routingRules, outcome.detection, c.text, c.sender)
      val d = Privacy.forStorage(
        outcome.detection.copy(
          reason = listOf(outcome.detection.reason, note).filter { it.isNotBlank() }.joinToString(" · "),
          route = route?.id,
          why = why.name,
          whyValue = whyValue,
          whyLimit = whyLimit,
          realDecision = realDecision.name,
        ),
        c.text, s, keep = keepText,
      )

      val status = when (decision) {
        Decision.IGNORE -> Status.IGNORED
        else -> Status.PENDING
      }
      // "Keep message text" off: also a shared message is not stored (only the entry made from it).
      val storedText = if (s.storeMessageText || keepText) c.text else ""
      val id = app.log.insert(c.source, c.packageName, c.appLabel, c.sender, storedText, d, status, hash, fingerprintText = c.text, isGroup = c.isGroup)
      Triple(id, d, decision)
    }

    val isSelfTest = isSelfTest(c)
    when (decision) {
      // The message goes along only when it is kept: a later confirmed entry has nothing else to send either, so
      // "Send message text" behaves the same for both (it needs "Keep message text").
      Decision.AUTO -> if (!isSelfTest) deliver(id, d, c.toSource().let { if (s.storeMessageText || keepText) it else it.copy(text = "") }, quiet = quiet)
      Decision.ASK -> if (!isSelfTest && c.source != SourceType.MANUAL && s.notifyConfirm) {
        // During quiet hours the question is not lost: one summary follows when they end.
        if (inQuietHours(s)) Deferred.scheduleQuietSummary(app, s)
        else Notifier.confirm(app, id, d, c, quiet = why == Why.LOCATION_ONLY)
      }
      Decision.IGNORE -> Unit
    }
    if (s.unfilteredTargets.isNotEmpty() && !isSelfTest && c.source != SourceType.MANUAL && d.confidence >= s.unfilteredAutoConfidence) {
      val alreadyDelivered = if (decision == Decision.AUTO) {
        val rule = Router.rule(s, d)
        Router.targets(Router.apply(s, rule), d, rule)
      } else emptySet()
      val extraUnfiltered = s.unfilteredTargets - alreadyDelivered
      if (extraUnfiltered.isNotEmpty()) {
        val src = c.toSource()
        extraUnfiltered.sortedBy { it.ordinal }.forEach { target ->
          runSafely { Targets.deliver(app, target, d, s, app.secrets, src, unfiltered = true) }
        }
      }
    }
    if (c.source == SourceType.VOICE_MEMO) {
      runSafely { de.juliandigitalproducts.notitocal.widget.VoiceMemosWidgetProvider.updateAll(app) }
    }
    dropProcessed()
    return Result(id, d, decision, note)
    } finally {
      if (wakeLock?.isHeld == true) {
        try { wakeLock.release() } catch (_: Exception) {}
      }
    }
  }

  /**
   * Changes an already captured entry with what a new message says (other day, time, end, place) and passes the
   * change on to every target that already has the entry.
   */
  private suspend fun patchExisting(existingEntry: LogEntry, upd: Detection, c: Captured, s: AppSettings, quiet: Boolean): Result {
    val oldStart = existingEntry.detection.start
    var updatedStart = upd.start ?: oldStart
    if (updatedStart != null && oldStart != null && oldStart.contains('T') && !updatedStart.contains('T')) {
      updatedStart = "${updatedStart}T${oldStart.substringAfter('T')}"
    }

    // Without a new end time the event keeps its previous length (not the default length).
    val oldStartDt = existingEntry.detection.startDateTime()
    val oldEndDt = existingEntry.detection.endDateTime()
    val keptMinutes = if (oldStartDt != null && oldEndDt != null && oldEndDt.isAfter(oldStartDt)) {
      java.time.Duration.between(oldStartDt, oldEndDt).toMinutes()
    } else s.defaultEventMinutes.toLong()
    val updatedEnd = if (updatedStart != null && upd.end != null && upd.end.contains('T')) {
      upd.end
    } else if (updatedStart != null && updatedStart.contains('T')) {
      runSafely {
        val parsedStart = LocalDateTime.parse(updatedStart)
        parsedStart.plusMinutes(keptMinutes).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
      }.getOrNull() ?: existingEntry.detection.end
    } else existingEntry.detection.end

    val updatedTitle = if (upd.title.isNotBlank() && upd.title.lowercase() !in GENERIC_TITLES) upd.title else existingEntry.detection.title
    val updatedLoc = upd.location?.takeIf { it.isNotBlank() } ?: existingEntry.detection.location
    // The message text is only kept when the user allows storing it.
    val updatedDesc = if (s.storeMessageText) {
      "${existingEntry.detection.description}\n\n${app.getString(R.string.update_note, c.text)}".trim()
    } else existingEntry.detection.description

    val patchedDetection = existingEntry.detection.copy(
      title = updatedTitle,
      start = updatedStart,
      end = updatedEnd,
      allDay = if (updatedStart?.contains('T') == true) false else existingEntry.detection.allDay,
      location = updatedLoc,
      description = updatedDesc,
      isProvisionalDay = false,
      isEndEstimated = if (upd.end != null) false else existingEntry.detection.isEndEstimated,
      actionType = "UPDATE_EXISTING",
      targetEventId = existingEntry.id.toString(),
      reason = if (s.storeMessageText) app.getString(R.string.update_reason_text, c.text.take(60)) else app.getString(R.string.update_reason),
    )

    app.log.update(existingEntry.id, d = patchedDetection, status = existingEntry.status)
    if (existingEntry.status == Status.ADDED || existingEntry.status == Status.PARTIAL) {
      syncUpdate(existingEntry, patchedDetection)
    }

    if (s.notifyOnAdd && !quiet) {
      Notifier.merged(app, existingEntry.id, patchedDetection)
    }

    dropProcessed()
    return Result(existingEntry.id, patchedDetection, Decision.AUTO, app.getString(R.string.update_reason))
  }

  /**
   * Passes a changed entry on to the targets that already received it: the device calendar (by event id) and every
   * target that can update ([Targets.UPDATABLE]). The others (calendar app, Google Tasks, share …) are listed in the
   * entry as "not updated", so the user knows where the old state still is.
   */
  suspend fun syncUpdate(entry: LogEntry, patched: Detection) {
    val rule = Router.rule(app.settings.current, patched)
    val s = Router.apply(app.settings.current, rule)
    val delivered = Targets.succeededTargets(entry.result).ifEmpty { Router.targets(s, patched, rule) }
    val lines = mutableListOf<String>()
    if (TargetType.DEVICE_CALENDAR in delivered) {
      val ok = runSafely {
        Targets.updateDeviceCalendar(
          app, patched, s,
          oldTitle = entry.detection.title,
          eventId = entry.detection.calendarEventId,
          oldStartMillis = entry.detection.startDateTime()?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
          sender = entry.sender,
        )
      }.getOrNull()
      lines += "↻ ${TargetType.DEVICE_CALENDAR.name}: " + (if (ok?.ok == true) "updated" else ok?.message ?: "not updated")
      // The event could have been created anew (deleted by the user meanwhile): remember the new id.
      val newId = ok?.let { r -> r.intent?.data?.lastPathSegment?.toLongOrNull()?.takeIf { r.ok } }
      if (newId != null && newId != patched.calendarEventId) app.log.update(entry.id, d = patched.copy(calendarEventId = newId))
    }
    val src = SourceInfo(entry.appLabel, entry.packageName, entry.sender, entry.text, entry.source.name)
    delivered.filter { it != TargetType.DEVICE_CALENDAR }.sortedBy { it.ordinal }.forEach { t ->
      val r = Targets.update(app, t, patched, s, app.secrets, src)
      lines += "↻ ${t.name}: " + when {
        r == null -> "not updated automatically"
        r.ok -> r.message
        else -> "✗ " + r.message
      }
    }
    if (lines.isNotEmpty()) {
      val kept = entry.result.lineSequence().filterNot { it.startsWith("↻ ") }.joinToString("\n")
      app.log.update(entry.id, result = (kept + "\n" + lines.joinToString("\n")).trim())
    }
  }

  /** Filters applied before analysis; returns the reason and the setting involved. */
  private fun preCheck(c: Captured, s: AppSettings, hash: String): Pair<Why, Double?>? = when {
    s.paused -> Why.PAUSED to null
    c.packageName == app.packageName || de.juliandigitalproducts.notitocal.analysis.RuleEngine.isSelfOrDebugText(c.text) -> Why.NOTHING to null
    c.source == SourceType.NOTIFICATION && !s.notificationsEnabled -> Why.PAUSED to null
    (c.source == SourceType.SHARED_TEXT || c.source == SourceType.SHARED_IMAGE) && !s.shareEnabled -> Why.PAUSED to null
    c.text.trim().length < s.minTextLength && !(s.addressSuggestions && de.juliandigitalproducts.notitocal.analysis.RuleEngine.fullAddress(c.text) != null) -> Why.TOO_SHORT to s.minTextLength.toDouble()
    c.source == SourceType.NOTIFICATION && c.isGroup && s.ignoreGroupChats -> Why.GROUP to null
    c.sender.isNotBlank() && Analyzer.matchesAny(c.sender, s.blockedSenders) -> Why.BLOCKED_SENDER to null
    app.log.seenRecently(hash, s.dedupeMinutes, c.text) -> Why.SEEN to s.dedupeMinutes.toDouble()
    else -> null
  }

  /** Sends a detection to all configured targets of its kind. */
  /**
   * [alreadyDone]: targets that got the entry in an earlier, partly successful delivery – they are skipped (no second
   * copy), and their "✓" lines from [previousSummary] stay in the entry.
   */
  suspend fun deliver(
    logId: Long, d0: Detection, src: SourceInfo, fromUi: Boolean = false, quiet: Boolean = false,
    alreadyDone: Set<TargetType> = emptySet(), previousSummary: String = "",
  ): List<DeliveryResult> {
    // A stable id, so a later change of this entry updates the same item at CalDAV, the server and automations.
    val d = if (d0.syncUid == null) d0.copy(syncUid = java.util.UUID.randomUUID().toString()) else d0
    val rule = Router.rule(app.settings.current, d)
    val s = Router.apply(app.settings.current, rule)
    val allTargets = Router.targets(s, d, rule)
    val targets = allTargets - alreadyDone
    if (allTargets.isEmpty()) {
      app.log.update(logId, d, Status.FAILED, "no target configured")
      Notifier.failed(app, logId, d, "no target configured")
      dropProcessed()
      return emptyList()
    }
    val results = targets.sortedBy { it.ordinal }.map { Targets.deliver(app, it, d, s, app.secrets, src) }
    val ok = results.count { it.ok } + alreadyDone.count { it in allTargets }
    val total = results.size + alreadyDone.count { it in allTargets }
    val status = when {
      results.isNotEmpty() && results.all { it.skippedReadonly } && alreadyDone.isEmpty() -> Status.IGNORED
      ok == total -> Status.ADDED
      ok > 0 -> Status.PARTIAL
      else -> Status.FAILED
    }
    val keptLines = previousSummary.lineSequence()
      .filter { line -> alreadyDone.any { line.startsWith("✓ ${it.name}:") } }
      .toList()
    val summary = (keptLines + results.map { (if (it.ok) "✓ " else "✗ ") + it.target.name + ": " + it.message }).joinToString("\n")
    // Remember the created calendar event and the ids of the services, so later updates change exactly these items.
    val eventId = results.firstNotNullOfOrNull { Targets.calendarEventId(it) } ?: d.calendarEventId
    val remoteIds = d.remoteIds + results.filter { it.ok && it.remoteId != null }.associate { it.target.name to it.remoteId!! }
    app.log.update(logId, d.copy(calendarEventId = eventId, remoteIds = remoteIds), status, summary)

    results.firstOrNull { it.ok && it.target == TargetType.AI_AGENT }?.let { Notifier.agentResult(app, logId, d, it.message) }
    val interactive = results.filter { it.ok && it.intent != null && it.target.interactive }
    if (fromUi) {
      interactive.forEach { r -> runSafely { app.startActivity(r.intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    } else {
      interactive.forEach { r -> Notifier.openIntent(app, logId, d, r.intent!!) }
    }
    results.filter { it.skippedReadonly }.forEach { Notifier.duplicateReadonly(app, logId, d, it.message) }
    val hardFailures = results.filter { !it.ok && !it.skippedReadonly }
    if (status == Status.FAILED && hardFailures.isNotEmpty()) Notifier.failed(app, logId, d, hardFailures.first().message)
    else if (results.any { it.merged } && s.notifyOnAdd && !fromUi && !quiet) Notifier.merged(app, logId, d)
    else if (s.notifyOnAdd && !fromUi && !quiet && results.any { it.ok && !it.target.interactive }) Notifier.added(app, logId, d, results.filter { it.ok }.map { it.target })
    dropProcessed()
    return results
  }

  private fun dropProcessed() {
    app.log.dropFinalEntries(app.settings.current.deleteAfterProcessing)
  }

  /**
   * Delivers a confirmed entry. After a partly successful delivery only the failed targets are tried again; when the
   * user changed the entry meanwhile, the targets that already have it get the change as an update.
   */
  suspend fun deliverEntry(entry: LogEntry, d: Detection = entry.detection, fromUi: Boolean): List<DeliveryResult> {
    val done = if (entry.status == Status.PARTIAL) Targets.succeededTargets(entry.result) else emptySet()
    val src = SourceInfo(entry.appLabel, entry.packageName, entry.sender, entry.text, entry.source.name)
    val results = deliver(entry.id, d, src, fromUi, alreadyDone = done, previousSummary = entry.result)
    if (done.isNotEmpty() && d != entry.detection) {
      app.log.get(entry.id)?.let { fresh -> syncUpdate(fresh.copy(result = entry.result), fresh.detection) }
    }
    return results
  }

  private fun Captured.toSource() = SourceInfo(appLabel, packageName, sender, text, source.name)

  private fun inQuietHours(s: AppSettings): Boolean = Deferred.minutesUntilQuietEnd(s, LocalTime.now(clock)) != null

  /** Only real self-tests (diagnosis) – never a user message that happens to contain the test sentence. */
  private fun isSelfTest(c: Captured) =
    c.isSelfTest || c.appLabel == app.getString(R.string.diagnose_test_label) || c.appLabel == "Diagnose"

  /**
   * A new detection belongs to a provisional event of the same app when it comes from the same person, or when it is
   * about the same thing: similar title or place, or a generic title ("Treffen") in the same month.
   */
  private fun relevantTo(candidate: LogEntry, d: Detection, c: Captured): Boolean {
    if (ChatScope.sameSender(c.packageName, c.sender, candidate.packageName, candidate.sender)) return true
    val titleSim = de.juliandigitalproducts.notitocal.output.DuplicateMatcher.similarity(d.title, candidate.detection.title)
    val locSim = if (!d.location.isNullOrBlank() && !candidate.detection.location.isNullOrBlank()) {
      de.juliandigitalproducts.notitocal.output.DuplicateMatcher.locationSimilarity(d.location!!, candidate.detection.location!!)
    } else 0.0
    val generic = d.title.isBlank() || d.title.lowercase() in GENERIC_TITLES
    val sameMonth = runSafely {
      val a = java.time.LocalDate.parse(d.start!!.substring(0, 10))
      val b = java.time.LocalDate.parse(candidate.detection.start!!.substring(0, 10))
      a.year == b.year && a.month == b.month
    }.getOrDefault(false)
    // Another member of the same chat: a matching title or place. A generic title ("Treffen") alone says little,
    // so it only counts in the same month (the former rule "generic + same month" without any match is gone).
    return (titleSim >= 0.45 && (!generic || sameMonth)) || locSim >= 0.70
  }

  companion object {
    const val SESSION_ENGINE = "session_aggregator"
    private const val PROVISIONAL_DAYS = 45L
    private val GENERIC_TITLES = setOf("treffen", "termin", "meeting", "verabredung", "appointment")
  }
}
