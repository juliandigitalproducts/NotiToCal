// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.output.CalendarDuplicates
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.CalendarEventItem
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.DuplicateCategory
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.DuplicateCluster
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.MatchReason
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.MergeHistoryRecord
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.MergeProposal
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.RemovalMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private enum class CategoryTab { ALL, CERTAIN, PROBABLE, UNSURE }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DuplicatesScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  val tick = rememberResumeTick()

  var hasReadPerm by remember(tick) { mutableStateOf(CalendarDuplicates.hasReadPermission(context)) }
  var hasWritePerm by remember(tick) { mutableStateOf(CalendarDuplicates.hasWritePermission(context)) }

  // Screen mode: 0 = Finder & Results, 1 = Undo & Change History (30 days)
  var mainTab by rememberSaveable { mutableIntStateOf(0) }
  var configExpanded by rememberSaveable { mutableStateOf(true) }
  var categoryTab by rememberSaveable { mutableStateOf(CategoryTab.ALL) }

  // Scan state
  var scanning by remember { mutableStateOf(false) }
  var scanStatus by remember { mutableStateOf("") }
  var totalScanned by remember { mutableIntStateOf(0) }
  var hasScannedOnce by remember { mutableStateOf(false) }
  var clusters by remember { mutableStateOf<List<DuplicateCluster>>(emptyList()) }
  var historyRecords by remember { mutableStateOf(CalendarDuplicates.loadHistoryRecords(context)) }

  // Dialogs
  var inspectEvent by remember { mutableStateOf<CalendarEventItem?>(null) }
  var confirmMergeTarget by remember { mutableStateOf<Pair<DuplicateCluster, MergeProposal>?>(null) }
  var confirmBatchMerge by remember { mutableStateOf<List<DuplicateCluster>?>(null) }
  var feedbackMessage by remember { mutableStateOf<String?>(null) }
  var aiBusyClusterId by remember { mutableStateOf<String?>(null) }
  var aiCheckingAll by remember { mutableStateOf(false) }
  var isAiFinished by remember { mutableStateOf(false) }
  var aiJob by remember { mutableStateOf<Job?>(null) }
  var pendingAfterPerm by remember { mutableStateOf<(() -> Unit)?>(null) }

  val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    hasReadPerm = CalendarDuplicates.hasReadPermission(context)
    hasWritePerm = CalendarDuplicates.hasWritePermission(context)
    if (hasReadPerm) {
      val action = pendingAfterPerm
      pendingAfterPerm = null
      action?.invoke()
    }
  }

  val calendars = remember(hasReadPerm, tick) {
    if (hasReadPerm) CalendarDuplicates.allCalendars(context) else emptyList()
  }

  fun isHolidayCalendar(name: String, account: String): Boolean {
    val n = name.lowercase()
    val a = account.lowercase()
    return n.contains("feiertag") || n.contains("holiday") || n.contains("ferien") ||
      n.contains("vacaciones") || n.contains("jours fériés") || n.contains("festivo") ||
      a.contains("holiday") || a.contains("feiertag")
  }

  val (holidayCals, regularCals) = remember(calendars) {
    calendars.partition { isHolidayCalendar(it.name, it.account) }
  }
  val regularByAccount = remember(regularCals) {
    regularCals.groupBy { cal ->
      cal.account.ifBlank { resources.getString(R.string.local_device_calendar) }
    }.toList().sortedBy { it.first.lowercase() }
  }
  var holidaysExpanded by rememberSaveable { mutableStateOf(false) }

  val backupExportLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/json")
  ) { uri ->
    if (uri != null) {
      scope.launch(Dispatchers.IO) {
        val json = CalendarDuplicates.exportClustersBackupJson(clusters)
        val ok = runSafely {
          context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        feedbackMessage = if (ok) {
          resources.getString(R.string.dup_backup_saved)
        } else {
          resources.getString(R.string.dup_backup_error)
        }
      }
    }
  }

  fun computeRangeMillis(): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000L
    val (backDays, aheadDays) = when (s.duplicateRangePreset) {
      "7d" -> 7 to 7
      "30d" -> 30 to 30
      "90d" -> 90 to 90
      "365d" -> 365 to 365
      else -> s.duplicateCustomDaysBack.coerceIn(1, 730) to s.duplicateCustomDaysAhead.coerceIn(1, 730)
    }
    return (now - backDays * dayMs) to (now + aheadDays * dayMs)
  }

  fun triggerScan(forceAiAll: Boolean = false) {
    hasReadPerm = CalendarDuplicates.hasReadPermission(context)
    if (!hasReadPerm) {
      pendingAfterPerm = { triggerScan(forceAiAll) }
      permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
      return
    }
    scanning = true
    val (startMs, endMs) = computeRangeMillis()
    val holidayIds = holidayCals.map { it.id }.toSet()
    val effectiveCalendarIds = if (s.duplicateIgnoreHolidays) {
      if (s.duplicateSelectedCalendarIds.isEmpty()) (calendars.map { it.id }.toSet() - holidayIds)
      else (s.duplicateSelectedCalendarIds - holidayIds)
    } else {
      s.duplicateSelectedCalendarIds
    }
    scope.launch {
      val result = runSafely {
        CalendarDuplicates.scanForDuplicates(
          context = context,
          startMillis = startMs,
          endMillis = endMs,
          selectedCalendarIds = effectiveCalendarIds,
          crossCalendar = s.duplicateCrossCalendar,
          useAiForUnsure = s.duplicateUseAiForUnsure || forceAiAll,
          aiThreshold = if (forceAiAll) 0.99 else s.duplicateAiThreshold,
          settings = s,
          secrets = Ui.app.secrets,
          onProgress = { msg -> scanStatus = msg },
        )
      }
      scanning = false
      hasScannedOnce = true
      result.onSuccess { res ->
        totalScanned = res.totalEventsScanned
        clusters = res.clusters
        if (res.clusters.isNotEmpty()) {
          configExpanded = false
        }
      }.onFailure { err ->
        feedbackMessage = err.message ?: err.javaClass.simpleName
      }
    }
  }

  fun checkClustersWithAi(onlyUnsure: Boolean = false, forceReprocess: Boolean = false) {
    if (clusters.isEmpty()) {
      triggerScan(forceAiAll = !onlyUnsure)
      return
    }

    val cloudKey = Ui.app.secrets.get(SecretKeys.llm(s.provider))
    val hasCloud = !s.provider.needsKey || cloudKey.isNotBlank()
    val hasSelfHosted = s.selfHostedUrl.isNotBlank()
    val hasOnDevice = s.onDeviceModelFile.isNotBlank() || (s.onDeviceModelUri.isNotBlank() && de.juliandigitalproducts.notitocal.analysis.SharedModel.accessible(context, s.onDeviceModelUri))
    val hasAnyAi = hasCloud || hasSelfHosted || hasOnDevice

    if (!hasAnyAi) {
      feedbackMessage = resources.getString(R.string.dup_no_ai_available)
      return
    }

    // Immediately set loading state
    aiCheckingAll = true
    isAiFinished = false
    scanning = true

    if (forceReprocess) {
      clusters = clusters.map { it.copy(aiAdvice = null) }
    }

    val targetList = if (onlyUnsure) {
      val unsure = clusters.filter { it.category == DuplicateCategory.UNSURE || it.confidence < 0.80 || it.aiAdvice == null }
      unsure.ifEmpty { clusters }
    } else if (forceReprocess) {
      clusters
    } else {
      clusters.filter { it.aiAdvice == null }.ifEmpty { clusters }
    }

    val firstTitle = targetList.firstOrNull()?.proposal?.title?.take(24).orEmpty()
    scanStatus = if (forceReprocess) {
      Ui.app.getString(R.string.dup_ai_rechecking, 1, targetList.size, firstTitle)
    } else {
      Ui.app.getString(R.string.dup_ai_progress, 1, targetList.size, firstTitle)
    }

    aiJob?.cancel()
    aiJob = scope.launch {
      try {
        val updatedMap = mutableMapOf<String, DuplicateCluster>()
        var successCount = 0

        for ((idx, cluster) in targetList.withIndex()) {
          if (!isActive) break
          scanStatus = if (forceReprocess) {
            Ui.app.getString(R.string.dup_ai_rechecking, idx + 1, targetList.size, cluster.proposal.title.take(24))
          } else {
            Ui.app.getString(R.string.dup_ai_progress, idx + 1, targetList.size, cluster.proposal.title.take(24))
          }
          val advice = runSafely {
            CalendarDuplicates.evaluateClusterWithAi(cluster, s, Ui.app.secrets, context, allowAlgorithmicFallback = false, forceReprocess = forceReprocess)
          }.getOrNull()

          if (!isActive) break

          if (advice != null) {
            successCount++
            val recPrimary = advice.recommendedPrimaryId?.takeIf { id -> cluster.events.any { it.eventId == id } } ?: cluster.defaultPrimaryId
            val baseProp = DuplicateMatcher.buildMergeProposal(cluster.events, recPrimary)
            val updatedProp = baseProp.copy(
              title = advice.recommendedTitle?.takeIf { it.isNotBlank() } ?: baseProp.title,
              location = advice.recommendedLocation?.takeIf { it.isNotBlank() } ?: baseProp.location,
              description = advice.recommendedDescription?.takeIf { it.isNotBlank() } ?: baseProp.description,
            )
            val newConf = if (advice.isDuplicate) maxOf(cluster.confidence, advice.confidence) else minOf(cluster.confidence, advice.confidence)
            val newCat = when {
              newConf >= 0.80 -> DuplicateCategory.CERTAIN
              newConf >= 0.62 -> DuplicateCategory.PROBABLE
              else -> DuplicateCategory.UNSURE
            }
            updatedMap[cluster.id] = cluster.copy(
              confidence = newConf,
              category = newCat,
              defaultPrimaryId = recPrimary,
              proposal = updatedProp,
              aiAdvice = advice,
            )
          }
        }
        clusters = clusters.map { updatedMap[it.id] ?: it }
        feedbackMessage = if (successCount > 0) {
          resources.getString(R.string.dup_ai_check_all_done, successCount)
        } else {
          resources.getString(R.string.dup_ai_no_response)
        }
        isAiFinished = true
        kotlinx.coroutines.delay(450)
      } catch (e: CancellationException) {
        feedbackMessage = resources.getString(R.string.dup_ai_cancelled)
      } finally {
        scanning = false
        aiCheckingAll = false
        isAiFinished = false
        scanStatus = ""
        aiJob = null
      }
    }
  }

  fun checkAllClustersWithAi(forceReprocess: Boolean = false) = checkClustersWithAi(onlyUnsure = false, forceReprocess = forceReprocess)
  fun checkUnsureClustersWithAi() = checkClustersWithAi(onlyUnsure = true)

  val removalMode = if (s.duplicateRemovalMode == "MARK") RemovalMode.MARK_DUPLICATE else RemovalMode.DELETE

  fun removeMergedFromView(removedIds: Set<String>) {
    val next = clusters.filterNot { it.id in removedIds }
    clusters = next
    if (categoryTab != CategoryTab.ALL) {
      val remainingInTab = next.count { c ->
        when (categoryTab) {
          CategoryTab.CERTAIN -> c.category == DuplicateCategory.CERTAIN
          CategoryTab.PROBABLE -> c.category == DuplicateCategory.PROBABLE
          CategoryTab.UNSURE -> c.category == DuplicateCategory.UNSURE
          CategoryTab.ALL -> true
        }
      }
      if (remainingInTab == 0) {
        categoryTab = CategoryTab.ALL
      }
    }
  }

  fun performMerge(cluster: DuplicateCluster, proposal: MergeProposal) {
    hasWritePerm = CalendarDuplicates.hasWritePermission(context)
    if (!hasWritePerm) {
      pendingAfterPerm = { performMerge(cluster, proposal) }
      permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
      return
    }
    // Optimistically remove from view so merged duplicates disappear immediately
    removeMergedFromView(setOf(cluster.id))
    scope.launch {
      val res = CalendarDuplicates.executeMerge(context, cluster, proposal, removalMode)
      res.onSuccess {
        historyRecords = CalendarDuplicates.loadHistoryRecords(context)
        feedbackMessage = resources.getString(R.string.dup_merge_success, proposal.title)
      }.onFailure { err ->
        CalendarDuplicates.ignoreCluster(context, cluster.id)
        feedbackMessage = err.message ?: resources.getString(R.string.dup_merge_error)
      }
    }
  }

  fun performBatchMerge(batch: List<DuplicateCluster>) {
    if (batch.isEmpty()) return
    hasWritePerm = CalendarDuplicates.hasWritePermission(context)
    if (!hasWritePerm) {
      pendingAfterPerm = { performBatchMerge(batch) }
      permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
      return
    }
    val batchIds = batch.map { it.id }.toSet()
    // Remove immediately from UI so safe duplicates disappear right away
    removeMergedFromView(batchIds)
    scope.launch {
      var mergedCount = 0
      for (c in batch) {
        val r = CalendarDuplicates.executeMerge(context, c, c.proposal, removalMode)
        if (r.isSuccess) {
          mergedCount++
        } else {
          CalendarDuplicates.ignoreCluster(context, c.id)
          mergedCount++
        }
      }
      historyRecords = CalendarDuplicates.loadHistoryRecords(context)
      feedbackMessage = resources.getString(R.string.dup_batch_merge_done, mergedCount)
    }
  }

  var backClicked by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.duplicates_title)) },
        navigationIcon = {
          IconButton(
            onClick = {
              if (!backClicked) {
                backClicked = true
                onBack()
              }
            },
            enabled = !backClicked,
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
          }
        },
        actions = {
          if (clusters.isNotEmpty()) {
            IconButton(
              onClick = { backupExportLauncher.launch("notitocal-duplicates-backup.json") },
            ) {
              Icon(Icons.Filled.FileUpload, contentDescription = stringResource(R.string.dup_export_backup))
            }
          }
          IconButton(onClick = { configExpanded = !configExpanded }) {
            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.dup_configure))
          }
        },
      )
    },
  ) { pad ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(pad),
      contentPadding = PaddingValues(bottom = 36.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Top Segmented Switch: Finder vs. History & Undo (30 days)
      item {
        SingleChoiceSegmentedButtonRow(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gaps.Inner, vertical = 4.dp),
        ) {
          SegmentedButton(
            selected = mainTab == 0,
            onClick = { mainTab = 0 },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = { Icon(Icons.Filled.CallMerge, null, modifier = Modifier.size(18.dp)) },
          ) {
            Text(
              if (clusters.isEmpty()) stringResource(R.string.dup_tab_finder)
              else "${stringResource(R.string.dup_tab_finder)} (${clusters.size})",
            )
          }
          SegmentedButton(
            selected = mainTab == 1,
            onClick = {
              historyRecords = CalendarDuplicates.loadHistoryRecords(context)
              mainTab = 1
            },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = { Icon(Icons.Filled.History, null, modifier = Modifier.size(18.dp)) },
          ) {
            val activeHistory = historyRecords.count { !it.undone }
            Text(
              if (activeHistory == 0) stringResource(R.string.dup_tab_history)
              else "${stringResource(R.string.dup_tab_history)} ($activeHistory)",
            )
          }
        }
      }

      // Permission Warning Card if calendar access is missing
      if (!hasReadPerm || !hasWritePerm) {
        item {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = Gaps.Edge),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(16.dp),
          ) {
            Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                  stringResource(R.string.dup_permission_missing_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  fontWeight = FontWeight.SemiBold,
                )
              }
              Text(
                stringResource(R.string.dup_permission_missing_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
              Button(
                onClick = {
                  permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                },
              ) {
                Text(stringResource(R.string.grant))
              }
            }
          }
        }
      }

      if (mainTab == 0) {
        // Configuration Panel (collapsible with smooth animation)
        item {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = Gaps.Edge)
              .animateContentSize(animationSpec = tween(280)),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          ) {
            Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { configExpanded = !configExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.primary)
                  Column {
                    Text(
                      stringResource(R.string.dup_config_header),
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      stringResource(R.string.dup_config_subheader),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                Icon(
                  if (configExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                  contentDescription = null,
                )
              }

              AnimatedVisibility(
                visible = configExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
              ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                  HorizontalDivider()

                  // 1. Time Range Selection
                  Text(
                    stringResource(R.string.dup_range_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = listOf(
                      "7d" to stringResource(R.string.dup_range_7d),
                      "30d" to stringResource(R.string.dup_range_30d),
                      "90d" to stringResource(R.string.dup_range_90d),
                      "365d" to stringResource(R.string.dup_range_365d),
                      "custom" to stringResource(R.string.dup_range_custom),
                    )
                    presets.forEach { (key, label) ->
                      FilterChip(
                        selected = s.duplicateRangePreset == key,
                        onClick = { Ui.update { it.copy(duplicateRangePreset = key) } },
                        label = { Text(label) },
                      )
                    }
                  }

                  AnimatedVisibility(visible = s.duplicateRangePreset == "custom") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                      Text(
                        stringResource(R.string.dup_custom_past_days, s.duplicateCustomDaysBack),
                        style = MaterialTheme.typography.bodySmall,
                      )
                      Slider(
                        value = s.duplicateCustomDaysBack.toFloat(),
                        onValueChange = { v -> Ui.update { it.copy(duplicateCustomDaysBack = v.roundToInt()) } },
                        valueRange = 1f..365f,
                      )
                      Text(
                        stringResource(R.string.dup_custom_future_days, s.duplicateCustomDaysAhead),
                        style = MaterialTheme.typography.bodySmall,
                      )
                      Slider(
                        value = s.duplicateCustomDaysAhead.toFloat(),
                        onValueChange = { v -> Ui.update { it.copy(duplicateCustomDaysAhead = v.roundToInt()) } },
                        valueRange = 1f..365f,
                      )
                    }
                  }

                  // 2. Calendar Selection
                  if (calendars.isNotEmpty()) {
                    Text(
                      stringResource(R.string.dup_calendars_label),
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.primary,
                    )

                    val allSelected = s.duplicateSelectedCalendarIds.isEmpty()
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      FilterChip(
                        selected = allSelected,
                        onClick = { Ui.update { it.copy(duplicateSelectedCalendarIds = emptySet()) } },
                        label = { Text(stringResource(R.string.dup_all_calendars, calendars.size)) },
                      )
                      if (!allSelected) {
                        TextButton(onClick = { Ui.update { it.copy(duplicateSelectedCalendarIds = emptySet()) } }) {
                          Text(stringResource(R.string.dup_select_all))
                        }
                      }
                    }

                    // Regular calendars grouped by Email / Account
                    regularByAccount.forEach { (accountName, accountCals) ->
                      Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Column(
                          modifier = Modifier.padding(10.dp),
                          verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                          Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                          ) {
                            Row(
                              verticalAlignment = Alignment.CenterVertically,
                              horizontalArrangement = Arrangement.spacedBy(6.dp),
                              modifier = Modifier.weight(1f),
                            ) {
                              Icon(
                                imageVector = if (accountName.contains("@")) Icons.Filled.Email else Icons.Filled.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                              )
                              Text(
                                text = accountName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                              )
                            }
                            val acctIds = accountCals.map { it.id }.toSet()
                            val allAcctSelected = !allSelected && acctIds.all { it in s.duplicateSelectedCalendarIds }
                            TextButton(
                              onClick = {
                                val current = if (allSelected) calendars.map { it.id }.toSet() else s.duplicateSelectedCalendarIds
                                val next = if (allAcctSelected) current - acctIds else current + acctIds
                                Ui.update { it.copy(duplicateSelectedCalendarIds = next) }
                              },
                              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                              Text(
                                if (allAcctSelected) stringResource(R.string.dup_select_none) else stringResource(R.string.stat_type_all),
                                style = MaterialTheme.typography.labelSmall,
                              )
                            }
                          }
                          FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                          ) {
                            accountCals.forEach { cal ->
                              val selected = allSelected || cal.id in s.duplicateSelectedCalendarIds
                              FilterChip(
                                selected = selected,
                                onClick = {
                                  val current = if (allSelected) calendars.map { it.id }.toSet() else s.duplicateSelectedCalendarIds
                                  val next = if (selected) current - cal.id else current + cal.id
                                  Ui.update { it.copy(duplicateSelectedCalendarIds = next) }
                                },
                                leadingIcon = {
                                  Box(
                                    Modifier
                                      .size(10.dp)
                                      .clip(CircleShape)
                                      .background(Color(cal.color or 0xFF000000.toInt())),
                                  )
                                },
                                label = {
                                  Text(cal.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                              )
                            }
                          }
                        }
                      }
                    }

                    // Collapsible Holiday Calendars ("Feiertagskalender")
                    if (holidayCals.isNotEmpty()) {
                      Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                          Row(
                            modifier = Modifier
                              .fillMaxWidth()
                              .clickable { holidaysExpanded = !holidaysExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                          ) {
                            Row(
                              verticalAlignment = Alignment.CenterVertically,
                              horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                              Icon(
                                imageVector = Icons.Filled.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                              )
                              Text(
                                text = stringResource(R.string.dup_holiday_cals, holidayCals.size),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                              )
                            }
                            Icon(
                              imageVector = if (holidaysExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                              contentDescription = null,
                              tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                          }
                          AnimatedVisibility(visible = holidaysExpanded) {
                            FlowRow(
                              horizontalArrangement = Arrangement.spacedBy(6.dp),
                              verticalArrangement = Arrangement.spacedBy(6.dp),
                              modifier = Modifier.padding(top = 4.dp),
                            ) {
                              holidayCals.forEach { cal ->
                                val selected = allSelected || cal.id in s.duplicateSelectedCalendarIds
                                FilterChip(
                                  selected = selected,
                                  onClick = {
                                    val current = if (allSelected) calendars.map { it.id }.toSet() else s.duplicateSelectedCalendarIds
                                    val next = if (selected) current - cal.id else current + cal.id
                                    Ui.update { it.copy(duplicateSelectedCalendarIds = next) }
                                  },
                                  leadingIcon = {
                                    Box(
                                      Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(cal.color or 0xFF000000.toInt())),
                                    )
                                  },
                                  label = {
                                    Text(cal.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                  },
                                )
                              }
                            }
                          }
                        }
                      }
                    }
                  }

                  // 3. Cross-calendar duplicate search
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                      Text(stringResource(R.string.dup_cross_calendar_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                      Text(
                        stringResource(R.string.dup_cross_calendar_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Switch(
                      checked = s.duplicateCrossCalendar,
                      onCheckedChange = { v -> Ui.update { it.copy(duplicateCrossCalendar = v) } },
                    )
                  }

                  // 3b. Ignore legal holidays
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                      Text(stringResource(R.string.dup_ignore_holidays), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                      Text(
                        stringResource(R.string.dup_ignore_holidays_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Switch(
                      checked = s.duplicateIgnoreHolidays,
                      onCheckedChange = { v -> Ui.update { it.copy(duplicateIgnoreHolidays = v) } },
                    )
                  }

                  // 4. Optional AI verification for uncertain cases
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                      Text(stringResource(R.string.dup_use_ai_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                      Text(
                        stringResource(R.string.dup_use_ai_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Switch(
                      checked = s.duplicateUseAiForUnsure,
                      onCheckedChange = { v -> Ui.update { it.copy(duplicateUseAiForUnsure = v) } },
                    )
                  }

                  AnimatedVisibility(visible = s.duplicateUseAiForUnsure) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                      Text(
                        stringResource(R.string.dup_ai_threshold_label, (s.duplicateAiThreshold * 100).roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                      )
                      Slider(
                        value = s.duplicateAiThreshold.toFloat(),
                        onValueChange = { v -> Ui.update { it.copy(duplicateAiThreshold = v.toDouble()) } },
                        valueRange = 0.40f..0.85f,
                      )
                    }
                  }

                  // 5. Auto-decide option ("Automatisch entscheiden")
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                      Text(stringResource(R.string.dup_auto_decide_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                      Text(
                        stringResource(R.string.dup_auto_decide_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Switch(
                      checked = s.duplicateAutoDecide,
                      onCheckedChange = { v -> Ui.update { it.copy(duplicateAutoDecide = v) } },
                    )
                  }

                  // 6. Removal mode (Delete vs. Mark as Duplicate)
                  Text(
                    stringResource(R.string.dup_removal_mode_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                      selected = s.duplicateRemovalMode != "MARK",
                      onClick = { Ui.update { it.copy(duplicateRemovalMode = "DELETE") } },
                      label = { Text(stringResource(R.string.dup_removal_delete)) },
                    )
                    FilterChip(
                      selected = s.duplicateRemovalMode == "MARK",
                      onClick = { Ui.update { it.copy(duplicateRemovalMode = "MARK") } },
                      label = { Text(stringResource(R.string.dup_removal_mark)) },
                    )
                  }
                }
              }

              // Primary Scan Action Button
              Button(
                onClick = { triggerScan(forceAiAll = false) },
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
              ) {
                if (scanning && !aiCheckingAll) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                  )
                  Spacer(Modifier.width(8.dp))
                  Text(stringResource(R.string.dup_scanning_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                  Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp))
                  Spacer(Modifier.width(6.dp))
                  Text(stringResource(R.string.duplicates_check_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }

              // AI Check Actions: "Alle (erneut) mit KI prüfen" & "Unsichere mit KI prüfen"
              val allChecked = clusters.isNotEmpty() && clusters.all { it.aiAdvice != null }
              val checkAllLabel = if (allChecked) stringResource(R.string.dup_recheck_all_ai_btn) else stringResource(R.string.dup_check_all_ai_btn)
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                OutlinedButton(
                  onClick = {
                    if (!aiCheckingAll && !scanning) {
                      checkAllClustersWithAi(forceReprocess = allChecked)
                    }
                  },
                  enabled = !scanning && !aiCheckingAll,
                  modifier = Modifier.weight(1f),
                ) {
                  Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text(checkAllLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                  onClick = {
                    if (!aiCheckingAll && !scanning) {
                      checkUnsureClustersWithAi()
                    }
                  },
                  enabled = !scanning && !aiCheckingAll,
                  modifier = Modifier.weight(1f),
                ) {
                  Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text(stringResource(R.string.dup_check_unsure_ai_btn), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }

              // Animated Loading Progress Bar (for local calendar scan only, AI check uses AiProgressDialog)
              AnimatedVisibility(visible = scanning && !aiCheckingAll) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                  if (scanStatus.isNotBlank()) {
                    Text(
                      scanStatus,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        }

        // Results Section
        if (hasScannedOnce && !scanning) {
          val certainCount = clusters.count { it.category == DuplicateCategory.CERTAIN }
          val probableCount = clusters.count { it.category == DuplicateCategory.PROBABLE }
          val unsureCount = clusters.count { it.category == DuplicateCategory.UNSURE }

          item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              LazyRow(
                contentPadding = PaddingValues(horizontal = Gaps.Inner),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                item {
                  FilterChip(
                    selected = categoryTab == CategoryTab.ALL,
                    onClick = { categoryTab = CategoryTab.ALL },
                    label = { Text("${stringResource(R.string.filter_all)} (${clusters.size})") },
                  )
                }
                item {
                  FilterChip(
                    selected = categoryTab == CategoryTab.CERTAIN,
                    onClick = { categoryTab = CategoryTab.CERTAIN },
                    leadingIcon = { Icon(Icons.Filled.Verified, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("${stringResource(R.string.dup_cat_certain)} ($certainCount)") },
                  )
                }
                item {
                  FilterChip(
                    selected = categoryTab == CategoryTab.PROBABLE,
                    onClick = { categoryTab = CategoryTab.PROBABLE },
                    leadingIcon = { Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("${stringResource(R.string.dup_cat_probable)} ($probableCount)") },
                  )
                }
                item {
                  FilterChip(
                    selected = categoryTab == CategoryTab.UNSURE,
                    onClick = { categoryTab = CategoryTab.UNSURE },
                    leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("${stringResource(R.string.dup_cat_unsure)} ($unsureCount)") },
                  )
                }
                if (clusters.isNotEmpty()) {
                  item {
                    val allCheckedChip = clusters.isNotEmpty() && clusters.all { it.aiAdvice != null }
                    val checkAllChipLabel = if (allCheckedChip) stringResource(R.string.dup_recheck_all_ai_btn) else stringResource(R.string.dup_check_all_ai_btn)
                    FilterChip(
                      selected = false,
                      onClick = {
                        if (!aiCheckingAll && !scanning) {
                          checkAllClustersWithAi(forceReprocess = allCheckedChip)
                        }
                      },
                      leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp)) },
                      label = { Text(checkAllChipLabel) },
                    )
                  }
                  item {
                    FilterChip(
                      selected = false,
                      onClick = {
                        if (!aiCheckingAll && !scanning) {
                          checkUnsureClustersWithAi()
                        }
                      },
                      leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp)) },
                      label = { Text(stringResource(R.string.dup_check_unsure_ai_btn)) },
                    )
                  }
                }
              }

              // Batch action bar when safe duplicates exist
              val autoBatchCandidates = clusters.filter {
                it.category == DuplicateCategory.CERTAIN || (it.aiAdvice?.isDuplicate == true && it.confidence >= 0.80)
              }
              if (autoBatchCandidates.isNotEmpty()) {
                Card(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gaps.Edge),
                  shape = RoundedCornerShape(14.dp),
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                      Text(
                        stringResource(R.string.dup_auto_batch_ready, autoBatchCandidates.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                      )
                      Text(
                        stringResource(R.string.dup_auto_batch_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                      )
                    }
                    Button(
                      onClick = {
                        if (s.duplicateAutoDecide) {
                          performBatchMerge(autoBatchCandidates)
                        } else {
                          confirmBatchMerge = autoBatchCandidates
                        }
                      },
                    ) {
                      Icon(Icons.Filled.CallMerge, null, modifier = Modifier.size(18.dp))
                      Spacer(Modifier.width(6.dp))
                      Text(stringResource(R.string.dup_merge_all_safe))
                    }
                  }
                }
              }
            }
          }

          val filteredClusters = clusters.filter { c ->
            when (categoryTab) {
              CategoryTab.ALL -> true
              CategoryTab.CERTAIN -> c.category == DuplicateCategory.CERTAIN
              CategoryTab.PROBABLE -> c.category == DuplicateCategory.PROBABLE
              CategoryTab.UNSURE -> c.category == DuplicateCategory.UNSURE
            }
          }

          if (filteredClusters.isEmpty()) {
            item {
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = Gaps.Edge, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(42.dp),
                  )
                  Text(
                    stringResource(R.string.dup_none_found_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    stringResource(R.string.dup_none_found_body, totalScanned),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Spacer(Modifier.height(8.dp))
                  Button(
                    onClick = {
                      if (!aiCheckingAll && !scanning) {
                        checkAllClustersWithAi(forceReprocess = true)
                      }
                    },
                    enabled = !scanning && !aiCheckingAll,
                  ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                      stringResource(
                        if (clusters.isNotEmpty()) R.string.dup_recheck_all_ai_btn
                        else R.string.dup_check_all_ai_btn
                      )
                    )
                  }
                }
              }
            }
          } else {
            items(filteredClusters, key = { it.id }) { cluster ->
              DuplicateClusterCard(
                cluster = cluster,
                aiLoading = aiBusyClusterId == cluster.id,
                onInspectEvent = { inspectEvent = it },
                onIgnore = {
                  CalendarDuplicates.ignoreCluster(context, cluster.id)
                  clusters = clusters.filterNot { it.id == cluster.id }
                },
                onAskAi = {
                  aiBusyClusterId = cluster.id
                  scope.launch {
                    val advice = runSafely {
                      CalendarDuplicates.evaluateClusterWithAi(cluster, s, Ui.app.secrets, context, allowAlgorithmicFallback = false)
                    }.getOrNull()
                    aiBusyClusterId = null
                    if (advice == null) {
                      feedbackMessage = resources.getString(R.string.dup_ai_single_no_response)
                    } else {
                      val recPrimary = advice.recommendedPrimaryId?.takeIf { id -> cluster.events.any { it.eventId == id } } ?: cluster.defaultPrimaryId
                      val baseProp = DuplicateMatcher.buildMergeProposal(cluster.events, recPrimary)
                      val updatedProp = baseProp.copy(
                        title = advice.recommendedTitle?.takeIf { it.isNotBlank() } ?: baseProp.title,
                        location = advice.recommendedLocation?.takeIf { it.isNotBlank() } ?: baseProp.location,
                        description = advice.recommendedDescription?.takeIf { it.isNotBlank() } ?: baseProp.description,
                      )
                      val newConf = if (advice.isDuplicate) maxOf(cluster.confidence, advice.confidence) else minOf(cluster.confidence, advice.confidence)
                      val newCat = when {
                        newConf >= 0.80 -> DuplicateCategory.CERTAIN
                        newConf >= 0.62 -> DuplicateCategory.PROBABLE
                        else -> DuplicateCategory.UNSURE
                      }
                      clusters = clusters.map { c ->
                        if (c.id == cluster.id) c.copy(
                          confidence = newConf,
                          category = newCat,
                          defaultPrimaryId = recPrimary,
                          proposal = updatedProp,
                          aiAdvice = advice,
                        ) else c
                      }
                    }
                  }
                },
                onMergeRequest = { customProposal ->
                  if (s.duplicateAutoDecide && cluster.category == DuplicateCategory.CERTAIN) {
                    performMerge(cluster, customProposal)
                  } else {
                    confirmMergeTarget = cluster to customProposal
                  }
                },
              )
            }
          }
        }
      } else {
        // Tab 1: 30-Day Merge History & Undo
        item {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = Gaps.Edge),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(Gaps.Inner),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  stringResource(R.string.dup_history_retention_title),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  stringResource(R.string.dup_history_retention_body),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              OutlinedButton(
                onClick = {
                  CalendarDuplicates.clearIgnoredClusters(context)
                  feedbackMessage = resources.getString(R.string.dup_ignored_reset_done)
                },
              ) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.dup_reset_ignored))
              }
            }
          }
        }

        if (historyRecords.isEmpty()) {
          item {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                stringResource(R.string.dup_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          items(historyRecords, key = { it.id }) { record ->
            MergeHistoryCard(
              record = record,
              onUndo = {
                scope.launch {
                  val res = CalendarDuplicates.undoMerge(context, record.id)
                  res.onSuccess {
                    historyRecords = CalendarDuplicates.loadHistoryRecords(context)
                    feedbackMessage = resources.getString(R.string.dup_undo_success, record.primaryBefore.title)
                  }.onFailure { err ->
                    feedbackMessage = err.message ?: resources.getString(R.string.dup_merge_error)
                  }
                }
              },
            )
          }
        }
      }
    }
  }

  // Event Details Dialog
  inspectEvent?.let { ev ->
    EventDetailsDialog(
      event = ev,
      onDismiss = { inspectEvent = null },
      onOpenInCalendar = {
        inspectEvent = null
        runSafely {
          val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev.eventId)
          context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      },
    )
  }

  // Single Cluster Merge Confirmation Summary Dialog
  confirmMergeTarget?.let { (cluster, proposal) ->
    MergeConfirmationDialog(
      cluster = cluster,
      proposal = proposal,
      removalMode = removalMode,
      onConfirm = {
        confirmMergeTarget = null
        performMerge(cluster, proposal)
      },
      onExportBackupFirst = {
        backupExportLauncher.launch("notitocal-duplicates-backup.json")
      },
      onDismiss = { confirmMergeTarget = null },
    )
  }

  // AI Verification Progress Dialog
  if (aiCheckingAll) {
    val allCheckedNow = clusters.isNotEmpty() && clusters.all { it.aiAdvice != null }
    AiProgressDialog(
      title = if (allCheckedNow) stringResource(R.string.dup_recheck_all_ai_btn) else stringResource(R.string.dup_check_all_ai_btn),
      statusOverride = scanStatus.takeIf { it.isNotBlank() },
      isFinished = isAiFinished,
      onDismiss = {
        aiCheckingAll = false
        isAiFinished = false
      },
      onCancel = {
        aiJob?.cancel()
        aiJob = null
        aiCheckingAll = false
        scanning = false
        isAiFinished = false
        scanStatus = ""
        feedbackMessage = resources.getString(R.string.dup_ai_cancelled)
      },
    )
  }

  // Batch Merge Confirmation Dialog
  confirmBatchMerge?.let { batch ->
    AlertDialog(
      onDismissRequest = { confirmBatchMerge = null },
      title = { Text(stringResource(R.string.dup_merge_all_safe)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.dup_batch_confirm_body, batch.size))
          batch.take(6).forEach { c ->
            Text(
              stringResource(R.string.dup_batch_line, c.proposal.title, c.events.size, c.proposal.targetCalendarName),
              style = MaterialTheme.typography.bodySmall,
            )
          }
          if (batch.size > 6) {
            Text("… +${batch.size - 6}", style = MaterialTheme.typography.bodySmall)
          }
        }
      },
      confirmButton = {
        Button(onClick = {
          confirmBatchMerge = null
          performBatchMerge(batch)
        }) {
          Text(stringResource(R.string.dup_merge_action))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmBatchMerge = null }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  // Feedback Snackbar/Dialog
  feedbackMessage?.let { msg ->
    AlertDialog(
      onDismissRequest = { feedbackMessage = null },
      confirmButton = { TextButton(onClick = { feedbackMessage = null }) { Text(stringResource(R.string.ok)) } },
      title = { Text(stringResource(R.string.duplicates_title)) },
      text = { Text(msg) },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DuplicateClusterCard(
  cluster: DuplicateCluster,
  aiLoading: Boolean,
  onInspectEvent: (CalendarEventItem) -> Unit,
  onIgnore: () -> Unit,
  onAskAi: () -> Unit,
  onMergeRequest: (MergeProposal) -> Unit,
) {
  val context = LocalContext.current
  var customizeExpanded by rememberSaveable { mutableStateOf(false) }

  // Interactive user customization state for this cluster
  var selectedPrimaryId by remember(cluster.id, cluster.defaultPrimaryId) {
    mutableLongStateOf(cluster.defaultPrimaryId)
  }
  var selectedTitleEventId by remember(cluster.id, cluster.defaultPrimaryId) {
    mutableLongStateOf(cluster.defaultPrimaryId)
  }
  var includeLocation by rememberSaveable(cluster.id) { mutableStateOf(true) }
  var includeDescriptions by rememberSaveable(cluster.id) { mutableStateOf(true) }
  var includeAttendees by rememberSaveable(cluster.id) { mutableStateOf(true) }
  var includeReminders by rememberSaveable(cluster.id) { mutableStateOf(true) }

  val baseProposal = remember(
    cluster.events,
    selectedPrimaryId,
    selectedTitleEventId,
    includeLocation,
    includeDescriptions,
    includeAttendees,
    includeReminders,
  ) {
    DuplicateMatcher.buildMergeProposal(
      events = cluster.events,
      primaryEventId = selectedPrimaryId,
      includeTitleFromId = selectedTitleEventId,
      includeLocation = includeLocation,
      includeDescriptions = includeDescriptions,
      includeAttendees = includeAttendees,
      includeReminders = includeReminders,
    )
  }

  var customTitle by remember(baseProposal.title) { mutableStateOf(baseProposal.title) }
  var customLocation by remember(baseProposal.location) { mutableStateOf(baseProposal.location) }

  val effectiveProposal = baseProposal.copy(
    title = customTitle.ifBlank { baseProposal.title },
    location = customLocation,
  )

  val (catLabel, catColor) = when (cluster.category) {
    DuplicateCategory.CERTAIN -> stringResource(R.string.dup_cat_certain) to Color(0xFF2E7D32)
    DuplicateCategory.PROBABLE -> stringResource(R.string.dup_cat_probable) to Color(0xFFE65100)
    DuplicateCategory.UNSURE -> stringResource(R.string.dup_cat_unsure) to Color(0xFF6A1B9A)
  }

  val animatedConf by animateFloatAsState(
    targetValue = cluster.confidence.toFloat(),
    animationSpec = tween(durationMillis = 500),
    label = "conf",
  )

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = Gaps.Edge)
      .animateContentSize(animationSpec = tween(260)),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    border = BorderStroke(1.dp, catColor.copy(alpha = 0.45f)),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      // Header: Category Badge + Confidence Score
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = catColor.copy(alpha = 0.15f),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              when (cluster.category) {
                DuplicateCategory.CERTAIN -> Icons.Filled.Verified
                DuplicateCategory.PROBABLE -> Icons.Filled.CheckCircle
                DuplicateCategory.UNSURE -> Icons.Filled.AutoAwesome
              },
              contentDescription = null,
              tint = catColor,
              modifier = Modifier.size(16.dp),
            )
            Text(
              catLabel,
              style = MaterialTheme.typography.labelMedium,
              color = catColor,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(R.string.dup_confidence_badge, (cluster.confidence * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = catColor,
          )
        }
      }

      LinearProgressIndicator(
        progress = { animatedConf },
        modifier = Modifier
          .fillMaxWidth()
          .height(5.dp)
          .clip(RoundedCornerShape(3.dp)),
        color = catColor,
      )

      // Involved Events List
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cluster.events.forEach { ev ->
          val isPrimary = ev.eventId == selectedPrimaryId
          val quality = DuplicateMatcher.dataQualityScore(ev)
          val isBestQuality = ev.eventId == cluster.defaultPrimaryId

          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onInspectEvent(ev) },
            shape = RoundedCornerShape(12.dp),
            color = if (isPrimary) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
              width = if (isPrimary) 1.5.dp else 1.dp,
              color = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              RadioButton(
                selected = isPrimary,
                onClick = { selectedPrimaryId = ev.eventId },
              )
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Text(
                    ev.title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  if (isBestQuality) {
                    Surface(
                      shape = RoundedCornerShape(4.dp),
                      color = MaterialTheme.colorScheme.primary,
                    ) {
                      Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                      ) {
                        Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
                        Text(
                          stringResource(R.string.dup_best_quality_badge, quality),
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onPrimary,
                          maxLines = 1,
                          softWrap = false,
                        )
                      }
                    }
                  }
                }

                // Date & Time
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Icon(Icons.Filled.Schedule, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                  Text(
                    formatEventDateTime(context, ev),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }

                // Location if present
                if (ev.location.isNotBlank()) {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.LocationOn, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(
                      ev.location,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }

                // Calendar badge
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  Box(
                    Modifier
                      .size(8.dp)
                      .clip(CircleShape)
                      .background(Color(ev.calendarColor or 0xFF000000.toInt())),
                  )
                  Text(
                    listOf(ev.calendarName, ev.calendarAccount).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }

              IconButton(onClick = { onInspectEvent(ev) }) {
                Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.dup_view_details))
              }
            }
          }
        }
      }

      // Reasons why these events were flagged as duplicates
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          stringResource(R.string.dup_reasons_header),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          cluster.reasons.forEach { reason ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
            ) {
              Text(
                formatMatchReason(context, reason),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // AI Advice Banner if available
      AnimatedVisibility(visible = cluster.aiAdvice != null) {
        cluster.aiAdvice?.let { ai ->
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.Top,
            ) {
              Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  stringResource(R.string.dup_ai_advice_title, (ai.confidence * 100).roundToInt()),
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                  ai.explanation,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
              }
            }
          }
        }
      }

      // Expandable Manual Merge Customization & Field Picker
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { customizeExpanded = !customizeExpanded }
          .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          stringResource(R.string.dup_customize_merge_label),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Icon(
          if (customizeExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      }

      AnimatedVisibility(
        visible = customizeExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          HorizontalDivider()
          Text(
            stringResource(R.string.dup_select_title_hint),
            style = MaterialTheme.typography.labelMedium,
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cluster.events.map { it.eventId to it.title }.distinctBy { it.second }.forEach { (id, t) ->
              FilterChip(
                selected = selectedTitleEventId == id && customTitle == t,
                onClick = {
                  selectedTitleEventId = id
                  customTitle = t
                },
                label = { Text(t) },
              )
            }
          }

          OutlinedTextField(
            value = customTitle,
            onValueChange = { customTitle = it },
            label = { Text(stringResource(R.string.dup_merged_title_field)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          OutlinedTextField(
            value = customLocation,
            onValueChange = { customLocation = it },
            label = { Text(stringResource(R.string.dup_merged_location_field)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          // Checkboxes for adopting fields from secondary events
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { includeLocation = !includeLocation }) {
            Checkbox(checked = includeLocation, onCheckedChange = { includeLocation = it })
            Text(stringResource(R.string.dup_adopt_location), style = MaterialTheme.typography.bodyMedium)
          }
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { includeDescriptions = !includeDescriptions }) {
            Checkbox(checked = includeDescriptions, onCheckedChange = { includeDescriptions = it })
            Text(stringResource(R.string.dup_adopt_description), style = MaterialTheme.typography.bodyMedium)
          }
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { includeAttendees = !includeAttendees }) {
            Checkbox(checked = includeAttendees, onCheckedChange = { includeAttendees = it })
            Text(stringResource(R.string.dup_adopt_attendees), style = MaterialTheme.typography.bodyMedium)
          }
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { includeReminders = !includeReminders }) {
            Checkbox(checked = includeReminders, onCheckedChange = { includeReminders = it })
            Text(stringResource(R.string.dup_adopt_reminders), style = MaterialTheme.typography.bodyMedium)
          }
        }
      }

      // Action Buttons Row
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = { onMergeRequest(effectiveProposal) },
        ) {
          Icon(Icons.Filled.CallMerge, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.dup_merge_action))
        }

        OutlinedButton(onClick = onIgnore) {
          Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.dup_keep_not_duplicate))
        }

        if (cluster.aiAdvice == null) {
          TextButton(
            onClick = onAskAi,
            enabled = !aiLoading,
          ) {
            if (aiLoading) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.dup_ask_ai_btn))
          }
        }
      }
    }
  }
}

@Composable
private fun MergeConfirmationDialog(
  cluster: DuplicateCluster,
  proposal: MergeProposal,
  removalMode: RemovalMode,
  onConfirm: () -> Unit,
  onExportBackupFirst: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val primary = cluster.events.firstOrNull { it.eventId == proposal.primaryEventId } ?: cluster.events.first()
  val removed = cluster.events.filter { it.eventId != primary.eventId }
  val sourceCalendars = cluster.events.map { it.calendarName.ifBlank { "Kalender #${it.calendarId}" } }.distinct()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.dup_confirm_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // 1. Which event stays?
        Text(
          stringResource(R.string.dup_confirm_kept_header),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(proposal.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (proposal.location.isNotBlank()) {
              Text("📍 ${proposal.location}", style = MaterialTheme.typography.bodySmall)
            }
            Text("🗓️ ${proposal.targetCalendarName}", style = MaterialTheme.typography.bodySmall)
          }
        }

        // 2. Which events are removed / marked?
        Text(
          stringResource(
            if (removalMode == RemovalMode.DELETE) R.string.dup_confirm_removed_header
            else R.string.dup_confirm_marked_header
          ),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.error,
        )
        removed.forEach { sec ->
          Text(
            "• „${sec.title}“ (${sec.calendarName})",
            style = MaterialTheme.typography.bodySmall,
          )
        }

        // 3. Which information is adopted?
        Text(
          stringResource(R.string.dup_confirm_adopted_header),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        if (proposal.adoptedFieldsSummary.isEmpty()) {
          Text(
            stringResource(R.string.dup_confirm_adopted_default),
            style = MaterialTheme.typography.bodySmall,
          )
        } else {
          proposal.adoptedFieldsSummary.forEach { item ->
            Text("✓ " + adoptedLabel(item), style = MaterialTheme.typography.bodySmall)
          }
        }

        // 4. Which calendars do the events come from?
        Text(
          stringResource(R.string.dup_confirm_calendars_header, sourceCalendars.joinToString(", ")),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 5. Safety & 30-day undo note + optional file backup button
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.surfaceContainerHighest,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              stringResource(R.string.dup_confirm_undo_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
              onClick = onExportBackupFirst,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(Icons.Filled.FileUpload, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.dup_export_backup))
            }
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = onConfirm) {
        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.dup_merge_action))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
private fun EventDetailsDialog(
  event: CalendarEventItem,
  onDismiss: () -> Unit,
  onOpenInCalendar: () -> Unit,
) {
  val context = LocalContext.current
  val quality = DuplicateMatcher.dataQualityScore(event)
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(event.title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🕒 ${formatEventDateTime(context, event)}", style = MaterialTheme.typography.bodyMedium)
        if (event.location.isNotBlank()) {
          Text("📍 ${event.location}", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
          "🗓️ ${event.calendarName}" + (if (event.calendarAccount.isNotBlank()) " (${event.calendarAccount})" else ""),
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          stringResource(R.string.dup_best_quality_badge, quality),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        if (event.attendees.isNotEmpty()) {
          Text("👥 ${event.attendees.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
        if (event.reminderMinutes.isNotEmpty()) {
          Text("🔔 ${event.reminderMinutes.joinToString { "$it Min." }}", style = MaterialTheme.typography.bodySmall)
        }
        if (event.description.isNotBlank()) {
          HorizontalDivider()
          Text(
            event.description.take(600),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onOpenInCalendar) {
        Icon(Icons.AutoMirrored.Filled.Launch, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.open_in_calendar_app))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.ok))
      }
    },
  )
}

@Composable
private fun MergeHistoryCard(
  record: MergeHistoryRecord,
  onUndo: () -> Unit,
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = Gaps.Edge),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (record.undone) MaterialTheme.colorScheme.surfaceContainerLow
      else MaterialTheme.colorScheme.surfaceContainer,
    ),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          record.summary,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
        )
        Text(
          DateUtils.getRelativeTimeSpanString(record.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Text(
        stringResource(R.string.dup_history_primary, record.primaryAfter.title, record.primaryAfter.calendarName),
        style = MaterialTheme.typography.bodySmall,
      )
      record.removedEvents.forEach { sec ->
        Text(
          stringResource(R.string.dup_history_merged, sec.title, sec.calendarName),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        if (record.undone) {
          Text(
            stringResource(R.string.dup_already_undone),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
          )
        } else {
          OutlinedButton(onClick = onUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.dup_undo_btn))
          }
        }
      }
    }
  }
}

private fun formatEventDateTime(context: android.content.Context, ev: CalendarEventItem): String {
  val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
  val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
  val date = ev.localDate()
  return if (ev.allDay) {
    "${date.format(dateFmt)} · ${context.getString(R.string.all_day)}"
  } else {
    val zone = ZoneId.systemDefault()
    val startDt = Instant.ofEpochMilli(ev.startMillis).atZone(zone).toLocalDateTime()
    val endDt = Instant.ofEpochMilli(ev.endMillis).atZone(zone).toLocalDateTime()
    "${startDt.format(dateFmt)}, ${startDt.format(timeFmt)}–${endDt.format(timeFmt)}"
  }
}

private fun formatMatchReason(context: android.content.Context, reason: MatchReason): String = when (reason.code) {
  "EXACT_TIME" -> context.getString(R.string.dup_reason_exact_time)
  "OVERLAPPING_TIME" -> context.getString(R.string.dup_reason_overlapping_time)
  "ALLDAY_AND_TIMED" -> context.getString(R.string.dup_reason_allday_and_timed)
  "SAME_DAY_ALLDAY" -> context.getString(R.string.dup_reason_same_day_allday)
  "SAME_DAY_NEAR_TIME" -> context.getString(R.string.dup_reason_same_day_near, reason.detail)
  "SAME_DAY" -> context.getString(R.string.dup_reason_same_day)
  "NEAR_TIME" -> context.getString(R.string.dup_reason_near_time)
  "EXACT_TITLE" -> context.getString(R.string.dup_reason_exact_title, reason.detail)
  "STEM_OR_TYPO_TITLE" -> context.getString(R.string.dup_reason_stem_title, reason.detail)
  "SIMILAR_TITLE" -> context.getString(R.string.dup_reason_similar_title, reason.detail)
  "PARTIAL_TITLE" -> context.getString(R.string.dup_reason_similar_title, reason.detail)
  "LOCATION_MATCH" -> context.getString(R.string.dup_reason_location_match, reason.detail)
  "LOCATION_SIMILAR" -> context.getString(R.string.dup_reason_location_match, reason.detail)
  "CROSS_CALENDAR" -> context.getString(R.string.dup_reason_cross_calendar, reason.detail)
  else -> reason.detail.ifBlank { reason.code }
}

/** Text for one code of [DuplicateMatcher.MergeProposal.adoptedFieldsSummary]; older entries are shown as they are. */
@Composable
private fun adoptedLabel(code: String): String {
  val arg = code.substringAfter('|', "")
  return when (code.substringBefore('|')) {
    "title" -> stringResource(R.string.dup_adopt_title, arg)
    "time" -> stringResource(R.string.dup_adopt_time)
    "location" -> stringResource(R.string.dup_adopt_location, arg)
    "description" -> stringResource(R.string.dup_adopt_description)
    "attendees" -> stringResource(R.string.dup_adopt_attendees, arg.toIntOrNull() ?: 0)
    "reminders" -> stringResource(R.string.dup_adopt_reminders, arg)
    else -> code
  }
}
