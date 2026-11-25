package de.juliandigitalproducts.notitocal.ui

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.LocationOn
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.font.FontWeight
import de.juliandigitalproducts.notitocal.data.OcrEngineMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.capture.MediaProcessor
import de.juliandigitalproducts.notitocal.capture.NotificationCaptureService
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import kotlinx.coroutines.launch
import de.juliandigitalproducts.notitocal.model.runSafely

private enum class Filter { ALL, EVENTS, TASKS, PENDING, IGNORED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val entries by Ui.app.log.entries.collectAsState()
  val queued by Ui.app.log.queued.collectAsState()
  val tick = rememberResumeTick()
  val access = remember(tick) { NotificationCaptureService.isEnabled(context) }
  var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
  var calendarKey by rememberSaveable { mutableStateOf<String?>(null) }
  val calendarFilter = CalendarFilter.valid(calendarKey, s)
  var showTry by remember { mutableStateOf(false) }
  var showOcrQuickSwitch by remember { mutableStateOf(false) }
  var confirmClear by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf<Int?>(null) }
  var aiRecheckJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
  var aiRecheckFinished by remember { mutableStateOf(false) }
  var aiRecheckStatus by remember { mutableStateOf<String?>(null) }
  var aiRecheckProgress by remember { mutableFloatStateOf(0f) }
  var batchRunning by remember { mutableStateOf(false) }
  var realResult by remember { mutableStateOf<String?>(null) }
  var notice by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  fun runAndOpen(message: Int, block: suspend () -> Long?) {
    busy = message
    scope.launch {
      val outcome = runSafely { block() }
      busy = null
      outcome.onSuccess { id -> if (id != null) onOpen("entry/$id") }
        .onFailure { notice = it.message ?: it.javaClass.simpleName }
    }
  }

  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) runAndOpen(R.string.share_reading_image) { MediaProcessor.processImage(Ui.app, uri, SourceType.SHARED_IMAGE, userInitiated = true)?.logId }
  }

  Scaffold(
    modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    topBar = {
      LargeTopAppBar(
        title = { Text(stringResource(R.string.nav_home)) },
        actions = {
          IconButton(onClick = { onOpen("statistics") }) {
            Icon(Icons.Filled.Insights, stringResource(R.string.statistics_title))
          }
          IconButton(onClick = { Ui.update { it.copy(compactOverview = !it.compactOverview) } }) {
            Icon(
              if (s.compactOverview) Icons.Filled.ViewAgenda else Icons.Filled.ViewHeadline,
              stringResource(if (s.compactOverview) R.string.expanded_mode else R.string.compact_mode),
            )
          }
          IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.clear_log)) }
          IconButton(onClick = { onOpen("settings") }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
        },
        scrollBehavior = scroll,
      )
    },
  ) { pad ->
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp)) {
      item {
        StatusCard(
          access = access,
          paused = s.paused,
          engine = engineLabel(s.engine, s.effectiveModel),
          ocrMode = s.ocrEngineMode,
          onGrant = { Ui.openNotificationAccess(context) },
          onPause = { p -> Ui.update { it.copy(paused = p) } },
          onOcrClick = { showOcrQuickSwitch = true },
        )
      }
      if (queued > 0) item {
        // Collected messages waiting for the next scheduled analysis (battery saving).
        ListItem(
          headlineContent = { Text(pluralStringResource(R.plurals.queue_waiting, queued, queued)) },
          supportingContent = Ui.app.batch.plannedRun()?.let { t -> { Text(stringResource(R.string.next_run, formatRun(t))) } },
          trailingContent = {
            TextButton(onClick = { batchRunning = true }, enabled = !batchRunning) { Text(stringResource(R.string.analyze_now)) }
          },
          modifier = Modifier.clickable { onOpen("settings/battery") },
        )
      }
      item {
        LazyRow(contentPadding = PaddingValues(horizontal = Gaps.Inner), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          item { AssistChip(onClick = { showTry = true }, label = { Text(stringResource(R.string.try_text)) }, leadingIcon = { Icon(Icons.Filled.Keyboard, null) }) }
          item {
            AssistChip(
              onClick = { showOcrQuickSwitch = true },
              label = { Text("⚡ OCR: ${s.ocrEngineMode.label}") },
              leadingIcon = { Icon(Icons.Filled.Bolt, null, modifier = Modifier.size(16.dp)) },
            )
          }
          item {
            AssistChip(
              onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
              label = { Text(stringResource(R.string.try_image)) }, leadingIcon = { Icon(Icons.Filled.Image, null) },
            )
          }
          item {
            AssistChip(
              onClick = { onOpen("settings/duplicates") },
              label = { Text(stringResource(R.string.duplicates_check_btn)) },
              leadingIcon = { Icon(Icons.Filled.CallMerge, null) },
            )
          }
          item {
            AssistChip(
              onClick = {
                // Engine "Rules" without an on-device model: there is no AI to check with – say so.
                if (!de.juliandigitalproducts.notitocal.analysis.Analyzer.canCheckWithAi(Ui.app, s)) {
                  notice = resources.getString(R.string.recheck_no_ai)
                  return@AssistChip
                }
                // Entries already in the calendar are left alone: replacing their detection would not update the event.
                val candidates = entries.filter { e ->
                  e.text.isNotBlank() && e.status != Status.ADDED && e.status != Status.PARTIAL && CalendarFilter.matches(e, calendarFilter, s) && when (filter) {
                    Filter.ALL -> e.status != Status.IGNORED
                    Filter.EVENTS -> e.detection.kind == Kind.EVENT && e.status != Status.IGNORED
                    Filter.TASKS -> e.detection.kind == Kind.TASK && e.status != Status.IGNORED
                    Filter.PENDING -> e.status == Status.PENDING
                    Filter.IGNORED -> e.status == Status.IGNORED
                  }
                }.take(25)
                if (candidates.isEmpty()) {
                  notice = resources.getString(R.string.recheck_all_ai_done, 0)
                  return@AssistChip
                }
                aiRecheckFinished = false
                aiRecheckStatus = resources.getString(R.string.ai_recheck_progress, 0, candidates.size)
                aiRecheckProgress = 0f
                aiRecheckJob = scope.launch {
                  var checked = 0
                  candidates.forEachIndexed { i, e ->
                    aiRecheckStatus = resources.getString(R.string.ai_recheck_progress, i + 1, candidates.size)
                    aiRecheckProgress = i.toFloat() / candidates.size
                    val newDet = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                      runSafely {
                        Ui.app.analyzer.analyzeForcedAi(Captured(e.source, e.packageName, e.appLabel, e.sender, e.text), s)
                      }.getOrNull()
                    }
                    if (newDet != null) {
                      checked++
                      val nextStatus = if (e.status == Status.IGNORED && newDet.kind != Kind.NONE) Status.PENDING else e.status
                      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Ui.app.log.update(e.id, d = newDet, status = nextStatus) }
                    }
                  }
                  aiRecheckFinished = true
                  aiRecheckStatus = resources.getString(R.string.recheck_all_ai_done, checked)
                }
              },
              label = { Text(stringResource(R.string.recheck_all_ai_btn)) },
              leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
            )
          }
          item { AssistChip(onClick = { onOpen("settings/diagnose") }, label = { Text(stringResource(R.string.diagnose)) }, leadingIcon = { Icon(Icons.Filled.Science, null) }) }
          item { AssistChip(onClick = { onOpen("settings/bugreport") }, label = { Text(stringResource(R.string.bug_report_tab)) }, leadingIcon = { Icon(Icons.Filled.BugReport, null) }) }
        }
      }
      item {
        LazyRow(contentPadding = PaddingValues(horizontal = Gaps.Inner, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(Filter.entries) { f ->
            FilterChip(
              selected = filter == f, onClick = { filter = f },
              label = {
                Text(stringResource(when (f) {
                  Filter.ALL -> R.string.filter_all; Filter.EVENTS -> R.string.filter_events; Filter.TASKS -> R.string.filter_tasks
                  Filter.PENDING -> R.string.filter_pending; Filter.IGNORED -> R.string.filter_ignored
                }))
              },
            )
          }
        }
        CalendarFilterRow(s, calendarFilter) { calendarKey = it }
      }
      val shown = entries.filter { e ->
        CalendarFilter.matches(e, calendarFilter, s) && when (filter) {
          Filter.ALL -> e.status != Status.IGNORED
          Filter.EVENTS -> e.detection.kind == Kind.EVENT && e.status != Status.IGNORED
          Filter.TASKS -> e.detection.kind == Kind.TASK && e.status != Status.IGNORED
          Filter.PENDING -> e.status == Status.PENDING
          Filter.IGNORED -> e.status == Status.IGNORED
        }
      }
      if (shown.isEmpty()) item {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
          Text(stringResource(R.string.empty_log), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      items(shown, key = { it.id }) { e -> EntryCard(e, compact = s.compactOverview) { onOpen("entry/${e.id}") } }
    }
  }

  if (showTry) TryDialog(
    onDismiss = { showTry = false },
    onRun = { text ->
      showTry = false
      runAndOpen(R.string.analyzing) {
        Ui.app.pipeline.process(Captured(SourceType.MANUAL, appLabel = resources.getString(R.string.source_manual), text = text), userInitiated = true)?.logId
      }
    },
    onRunReal = { text ->
      showTry = false
      busy = R.string.busy_real_run
      scope.launch {
        // Processed exactly like a notification: filters, auto add / ask, targets.
        val res = runSafely {
          Ui.app.pipeline.process(Captured(SourceType.NOTIFICATION, appLabel = resources.getString(R.string.source_manual), text = text), logFiltered = true)
        }.getOrNull()
        busy = null
        // The entry screen explains what happened and why.
        if (res != null) onOpen("entry/${res.logId}") else realResult = resources.getString(R.string.nothing_found)
      }
    },
  )

  if (confirmClear) ConfirmClearHistoryDialog { confirmClear = false }
  if (batchRunning) {
    BatchRunDialog { r ->
      batchRunning = false
      notice = resources.getString(R.string.analyze_now_done, r.processed, r.added, r.asked) +
        (if (r.failed > 0) "\n" + resources.getQuantityString(R.plurals.analyze_now_failed, r.failed, r.failed) else "")
    }
  }
  busy?.let { BusyDialog(stringResource(it), analyzingHint(context, s) ?: stringResource(R.string.busy_hint_wait)) }
  if (aiRecheckJob != null) {
    AiProgressDialog(
      title = stringResource(R.string.recheck_all_ai_btn),
      isFinished = aiRecheckFinished,
      statusOverride = aiRecheckStatus,
      progress = aiRecheckProgress,
      onDismiss = { aiRecheckJob = null; aiRecheckFinished = false },
      onCancel = { aiRecheckJob?.cancel(); aiRecheckJob = null; aiRecheckFinished = false },
    )
  }
  notice?.let { message ->
    AlertDialog(
      onDismissRequest = { notice = null },
      confirmButton = { TextButton(onClick = { notice = null }) { Text(stringResource(R.string.ok)) } },
      title = { Text(stringResource(R.string.app_name)) },
      text = { Text(message) },
    )
  }
  realResult?.let { r ->
    AlertDialog(
      onDismissRequest = { realResult = null },
      confirmButton = { TextButton(onClick = { realResult = null }) { Text(stringResource(R.string.ok)) } },
      title = { Text(stringResource(R.string.test_real)) },
      text = { Text(r) },
    )
  }

  if (showOcrQuickSwitch) {
    AlertDialog(
      onDismissRequest = { showOcrQuickSwitch = false },
      icon = { Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary) },
      title = { Text(stringResource(R.string.ocr_quick_switch_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.ocr_quick_switch_body), style = MaterialTheme.typography.bodySmall)
          OcrEngineMode.entries.forEach { mode ->
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (s.ocrEngineMode == mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth().clickable {
                Ui.update { it.copy(ocrEngineMode = mode) }
                showOcrQuickSwitch = false
              },
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                RadioButton(
                  selected = s.ocrEngineMode == mode,
                  onClick = {
                    Ui.update { it.copy(ocrEngineMode = mode) }
                    showOcrQuickSwitch = false
                  },
                )
                Column {
                  Text(mode.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                  Text(
                    when (mode) {
                      OcrEngineMode.MLKIT_LOCAL -> stringResource(R.string.ocr_mode_mlkit_desc)
                      OcrEngineMode.VISION_AI -> stringResource(R.string.ocr_mode_vision_desc)
                      OcrEngineMode.HYBRID -> stringResource(R.string.ocr_mode_hybrid_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showOcrQuickSwitch = false }) { Text(stringResource(R.string.close)) }
      },
    )
  }
}

@Composable
fun engineLabel(engine: AiEngine, model: String): String = when (engine) {
  AiEngine.RULES -> stringResource(R.string.engine_rules)
  AiEngine.ON_DEVICE -> stringResource(R.string.engine_on_device)
  AiEngine.CLOUD -> stringResource(R.string.engine_cloud) + " · " + model
  AiEngine.SELF_HOSTED -> stringResource(R.string.engine_self_hosted)
}

@Composable
private fun StatusCard(
  access: Boolean,
  paused: Boolean,
  engine: String,
  ocrMode: OcrEngineMode,
  onGrant: () -> Unit,
  onPause: (Boolean) -> Unit,
  onOcrClick: () -> Unit,
) {
  val ok = access && !paused
  Card(
    Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 8.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
    ),
  ) {
    Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
          when { !access -> Icons.Filled.Warning; paused -> Icons.Filled.PauseCircle; else -> Icons.Filled.CheckCircle }, null,
          Modifier.size(32.dp),
        )
        Column(Modifier.weight(1f)) {
          Text(
            stringResource(when { !access -> R.string.status_no_access; paused -> R.string.status_paused; else -> R.string.status_active }),
            style = MaterialTheme.typography.titleMedium,
          )
          Text(engine, style = MaterialTheme.typography.bodySmall)
        }
        if (access) Switch(checked = !paused, onCheckedChange = { onPause(!it) })
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
          modifier = Modifier.clickable { onOcrClick() },
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(Icons.Filled.Bolt, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text("⚡ OCR: ${ocrMode.label}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
          }
        }
        if (!access) Button(onClick = onGrant) { Text(stringResource(R.string.grant_access)) }
      }
    }
  }
}

private data class AppColors(
  val container: Color,
  val content: Color,
  val accent: Color,
  val border: Color,
)

@Composable
private fun rememberAppCardColors(pkg: String, label: String, source: SourceType): AppColors {
  val dark = isSystemInDarkTheme()
  val p = pkg.lowercase()
  val l = label.lowercase()
  return remember(pkg, label, source, dark) {
    when {
      // WhatsApp (green)
      p.contains("whatsapp") || l.contains("whatsapp") -> if (dark) {
        AppColors(
          container = Color(0xFF132A1E),
          content = Color(0xFFE8F5E9),
          accent = Color(0xFF25D366),
          border = Color(0xFF1E4632),
        )
      } else {
        AppColors(
          container = Color(0xFFE8F5E9),
          content = Color(0xFF1B5E20),
          accent = Color(0xFF25D366),
          border = Color(0xFFC8E6C9),
        )
      }
      // Telegram (sky blue)
      p.contains("telegram") || l.contains("telegram") || p.contains("challegram") -> if (dark) {
        AppColors(
          container = Color(0xFF0D2538),
          content = Color(0xFFE1F5FE),
          accent = Color(0xFF229ED9),
          border = Color(0xFF194263),
        )
      } else {
        AppColors(
          container = Color(0xFFE1F5FE),
          content = Color(0xFF01579B),
          accent = Color(0xFF229ED9),
          border = Color(0xFFB3E5FC),
        )
      }
      // Signal (indigo / blue)
      p.contains("thoughtcrime.securesms") || l.contains("signal") -> if (dark) {
        AppColors(
          container = Color(0xFF141A33),
          content = Color(0xFFE8EAF6),
          accent = Color(0xFF3A76F0),
          border = Color(0xFF232D57),
        )
      } else {
        AppColors(
          container = Color(0xFFE8EAF6),
          content = Color(0xFF1A237E),
          accent = Color(0xFF3A76F0),
          border = Color(0xFFC5CAE9),
        )
      }
      // Gmail / Mail (soft red)
      p.contains("google.android.gm") || l.contains("gmail") || l.contains("mail") -> if (dark) {
        AppColors(
          container = Color(0xFF2E1317),
          content = Color(0xFFFFEBEE),
          accent = Color(0xFFEA4335),
          border = Color(0xFF4D2026),
        )
      } else {
        AppColors(
          container = Color(0xFFFFEBEE),
          content = Color(0xFFB71C1C),
          accent = Color(0xFFEA4335),
          border = Color(0xFFFFCDD2),
        )
      }
      // Outlook (microsoft blue)
      p.contains("outlook") || l.contains("outlook") -> if (dark) {
        AppColors(
          container = Color(0xFF0D1E36),
          content = Color(0xFFE3F2FD),
          accent = Color(0xFF0078D4),
          border = Color(0xFF16345C),
        )
      } else {
        AppColors(
          container = Color(0xFFE3F2FD),
          content = Color(0xFF0D47A1),
          accent = Color(0xFF0078D4),
          border = Color(0xFFBBDEFB),
        )
      }
      // Audio / Voice memo (amber / warm gold)
      source == SourceType.VOICE_MEMO || l.contains("sprach") || l.contains("voice") -> if (dark) {
        AppColors(
          container = Color(0xFF2D200E),
          content = Color(0xFFFFF8E1),
          accent = Color(0xFFFFA000),
          border = Color(0xFF4C3618),
        )
      } else {
        AppColors(
          container = Color(0xFFFFF8E1),
          content = Color(0xFFE65100),
          accent = Color(0xFFFFA000),
          border = Color(0xFFFFECB3),
        )
      }
      // Photos / Screenshots (purple)
      source == SourceType.SCREENSHOT || source == SourceType.SHARED_IMAGE -> if (dark) {
        AppColors(
          container = Color(0xFF271333),
          content = Color(0xFFF3E5F5),
          accent = Color(0xFFAB47BC),
          border = Color(0xFF422156),
        )
      } else {
        AppColors(
          container = Color(0xFFF3E5F5),
          content = Color(0xFF4A148C),
          accent = Color(0xFF9C27B0),
          border = Color(0xFFE1BEE7),
        )
      }
      // Default
      else -> if (dark) {
        AppColors(
          container = Color(0xFF1E222A),
          content = Color(0xFFE0E2EC),
          accent = Color(0xFF8BB3FF),
          border = Color(0xFF2E333F),
        )
      } else {
        AppColors(
          container = Color(0xFFF0F2F8),
          content = Color(0xFF1A1C22),
          accent = Color(0xFF005AC1),
          border = Color(0xFFDDE1EB),
        )
      }
    }
  }
}

@Composable
private fun EntryCard(e: LogEntry, compact: Boolean, onClick: () -> Unit) {
  val context = LocalContext.current
  val d = e.detection
  val appColors = rememberAppCardColors(e.packageName, e.appLabel, e.source)
  val isTest = e.appLabel == stringResource(R.string.diagnose_test_label) || e.appLabel == "Diagnose" || e.text.contains("Appointment tomorrow at 15:00")
  val whenText = describeWhen(context, d)

  if (compact) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Gaps.Edge, vertical = 3.dp)
        .clickable(onClick = onClick),
      shape = RoundedCornerShape(12.dp),
      colors = CardDefaults.cardColors(containerColor = appColors.container),
      border = BorderStroke(1.dp, appColors.border),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = appColors.accent.copy(alpha = 0.2f),
          modifier = Modifier.size(32.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              when (d.kind) {
                Kind.EVENT -> Icons.Filled.Event
                Kind.TASK -> Icons.Filled.TaskAlt
                else -> Icons.AutoMirrored.Filled.Notes
              },
              null,
              tint = appColors.accent,
              modifier = Modifier.size(18.dp),
            )
          }
        }
        Column(Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isTest) {
              Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiary) {
                Text(
                  stringResource(R.string.diagnose_test_badge),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onTertiary,
                  modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                  maxLines = 1,
                  softWrap = false,
                )
              }
            }
            Text(
              d.title.ifBlank { e.text.take(60) },
              modifier = Modifier.weight(1f, fill = false),
              style = MaterialTheme.typography.titleSmall,
              color = appColors.content,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (whenText != null) {
            Text(
              whenText,
              style = MaterialTheme.typography.bodySmall,
              color = appColors.content.copy(alpha = 0.75f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        StatusBadge(e.status)
      }
    }
  } else {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = Gaps.Edge, vertical = 6.dp)
        .clickable(onClick = onClick),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = appColors.container),
      border = BorderStroke(1.dp, appColors.border),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Surface(
              modifier = Modifier.weight(1f, fill = false),
              shape = RoundedCornerShape(6.dp),
              color = appColors.accent.copy(alpha = 0.2f),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(
                  when (d.kind) {
                    Kind.EVENT -> Icons.Filled.Event
                    Kind.TASK -> Icons.Filled.TaskAlt
                    else -> Icons.AutoMirrored.Filled.Notes
                  },
                  null,
                  tint = appColors.accent,
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  listOf(e.appLabel, e.sender).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "NotiToCal" },
                  style = MaterialTheme.typography.labelSmall,
                  color = appColors.content,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            if (isTest) {
              Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiary) {
                Text(
                  stringResource(R.string.diagnose_test_badge),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onTertiary,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                  maxLines = 1,
                  softWrap = false,
                )
              }
            }
          }
          Text(
            DateUtils.getRelativeTimeSpanString(e.created, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            style = MaterialTheme.typography.bodySmall,
            color = appColors.content.copy(alpha = 0.65f),
            maxLines = 1,
            softWrap = false,
          )
        }

        Text(
          d.title.ifBlank { e.text.take(80) },
          style = MaterialTheme.typography.titleMedium,
          color = appColors.content,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        if (whenText != null || d.location != null) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (whenText != null) {
              Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(Icons.Filled.Schedule, null, tint = appColors.accent, modifier = Modifier.size(16.dp))
                Text(
                  whenText,
                  style = MaterialTheme.typography.bodySmall,
                  color = appColors.content,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            if (d.location != null) {
              Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(Icons.Filled.LocationOn, null, tint = appColors.accent, modifier = Modifier.size(16.dp))
                Text(d.location, style = MaterialTheme.typography.bodySmall, color = appColors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
              modifier = Modifier.weight(1f, fill = false),
              shape = RoundedCornerShape(6.dp),
              color = appColors.content.copy(alpha = 0.08f),
            ) {
              Text(
                "Engine: " + d.engine.ifBlank { "Regeln" },
                style = MaterialTheme.typography.labelSmall,
                color = appColors.content.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            if (d.confidence > 0.0) {
              Surface(shape = RoundedCornerShape(6.dp), color = appColors.content.copy(alpha = 0.08f)) {
                Text(
                  "${(d.confidence * 100).roundToInt()}%",
                  style = MaterialTheme.typography.labelSmall,
                  color = appColors.content.copy(alpha = 0.8f),
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  maxLines = 1,
                  softWrap = false,
                )
              }
            }
          }
          StatusBadge(e.status)
        }
      }
    }
  }
}

@Composable
fun StatusBadge(status: Status) {
  val (label, color) = when (status) {
    Status.PENDING -> R.string.status_pending to MaterialTheme.colorScheme.tertiary
    Status.ADDED -> R.string.status_added to MaterialTheme.colorScheme.primary
    Status.PARTIAL -> R.string.status_partial to MaterialTheme.colorScheme.tertiary
    Status.FAILED -> R.string.status_failed to MaterialTheme.colorScheme.error
    Status.DISMISSED -> R.string.status_dismissed to MaterialTheme.colorScheme.outline
    Status.IGNORED -> R.string.status_ignored to MaterialTheme.colorScheme.outline
  }
  Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, softWrap = false)
}

@Composable
private fun TryDialog(onDismiss: () -> Unit, onRun: (String) -> Unit, onRunReal: (String) -> Unit) {
  var text by rememberSaveable { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.try_text)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = text, onValueChange = { text = it }, minLines = 3,
          placeholder = { Text(stringResource(R.string.try_placeholder)) }, modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.try_modes_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = text.isNotBlank(), onClick = { onRunReal(text) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.test_real)) }
      }
    },
    confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onRun(text) }) { Text(stringResource(R.string.test_only)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
