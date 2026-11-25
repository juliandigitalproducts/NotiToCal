package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Alignment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.analysis.CloudLlmClient
import de.juliandigitalproducts.notitocal.analysis.SelfHosted
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.ProviderType
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.data.Strictness
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Detection
import androidx.compose.runtime.mutableIntStateOf
import de.juliandigitalproducts.notitocal.analysis.LocationEnricher
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.analysis.Analyzer
import de.juliandigitalproducts.notitocal.net.Http
import de.juliandigitalproducts.notitocal.notify.Notifier
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import de.juliandigitalproducts.notitocal.model.runSafely

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
  title: String,
  onBack: () -> Unit,
  bottomBar: @Composable () -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  var backClicked by remember { mutableStateOf(false) }
  Scaffold(topBar = {
    TopAppBar(
      title = { Text(title) },
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
    )
  }, bottomBar = bottomBar) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp), content = content)
  }
}

@Composable
fun SecretField(label: String, key: String, supporting: String? = null) {
  val initial = remember(key) { Ui.app.secrets.get(key) }
  SettingText(label, initial, secret = true, supporting = supporting) { Ui.app.secrets.put(key, it.trim()) }
}

@Composable
fun strictnessLabel(s: Strictness): String = stringResource(when (s) {
  Strictness.RELAXED -> R.string.strict_relaxed
  Strictness.BALANCED -> R.string.strict_balanced
  Strictness.STRICT -> R.string.strict_strict
  Strictness.CUSTOM -> R.string.strict_custom
})

// ------------------------------------------------------------------ Root

private data class SettingTileItem(
  val title: String,
  val subtitle: String,
  val icon: androidx.compose.ui.graphics.vector.ImageVector,
  val route: String,
)

private data class SettingGroupSpec(
  val title: String,
  val icon: androidx.compose.ui.graphics.vector.ImageVector,
  val tiles: List<SettingTileItem>,
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
  val s by Ui.app.settings.state.collectAsState()
  SubScreen(stringResource(R.string.settings), onBack) {
    SettingGroupCard {
      SettingSwitch(
        stringResource(R.string.advanced_mode),
        s.advancedMode,
        stringResource(R.string.advanced_mode_body),
      ) { v -> Ui.update { it.copy(advancedMode = v) } }
    }

    Spacer(Modifier.height(4.dp))

    val groups = listOf(
      SettingGroupSpec(
        title = stringResource(R.string.set_group_ai),
        icon = Icons.Filled.AutoAwesome,
        tiles = listOf(
          SettingTileItem(stringResource(R.string.section_engine), engineLabel(s.engine, s.effectiveModel), Icons.Filled.AutoAwesome, "settings/engine"),
          SettingTileItem(stringResource(R.string.section_strictness), strictnessLabel(s.strictness), Icons.Filled.Tune, "settings/strictness"),
          // Third = full width on phones, so the longer description stays readable.
          SettingTileItem(stringResource(R.string.section_templates), stringResource(R.string.templates_intro), Icons.Filled.Palette, "settings/templates"),
        ),
      ),
      SettingGroupSpec(
        title = stringResource(R.string.set_group_sources),
        icon = Icons.Filled.Inbox,
        tiles = listOf(
          SettingTileItem(stringResource(R.string.section_sources), installedCount(s.monitoredApps).let { n -> pluralStringResource(R.plurals.summary_apps, n, n) }, Icons.Filled.Inbox, "settings/sources"),
          SettingTileItem(stringResource(R.string.section_targets), (s.eventTargets + s.taskTargets).joinToString { Notifier.targetLabel(Ui.app, it) }.ifBlank { stringResource(R.string.none) }, Icons.Filled.CalendarMonth, "settings/targets"),
          SettingTileItem(stringResource(R.string.monitored_folders), stringResource(R.string.monitored_folders_body), Icons.Filled.Folder, "settings/folders"),
        ),
      ),
      SettingGroupSpec(
        title = stringResource(R.string.set_group_automation),
        icon = Icons.Filled.Tune,
        tiles = listOf(
          SettingTileItem(stringResource(R.string.duplicates_title), stringResource(R.string.duplicates_subtitle), Icons.Filled.CallMerge, "settings/duplicates"),
          SettingTileItem(stringResource(R.string.set_locations_title), stringResource(R.string.set_locations_body), Icons.Filled.LocationOn, "settings/locations"),
          SettingTileItem(stringResource(R.string.section_battery), scheduleLabel(Ui.app, s.analysisSchedule), Icons.Filled.BatteryChargingFull, "settings/battery"),
          SettingTileItem(stringResource(R.string.wizards_overview_title), stringResource(R.string.set_wizards_body), Icons.Filled.AutoAwesome, "wizards"),
        ),
      ),
      SettingGroupSpec(
        title = stringResource(R.string.set_group_security),
        icon = Icons.Filled.Shield,
        tiles = listOf(
          SettingTileItem(stringResource(R.string.full_history_title), stringResource(R.string.full_history_subtitle), Icons.Filled.History, "history_full"),
          SettingTileItem(stringResource(R.string.section_privacy), stringResource(R.string.section_privacy_body), Icons.Filled.Shield, "settings/privacy"),
          SettingTileItem(stringResource(R.string.statistics_title), stringResource(R.string.set_stats_body), Icons.Filled.Insights, "statistics"),
          SettingTileItem(stringResource(R.string.diagnose_and_backup), stringResource(R.string.diagnose_and_backup_body), Icons.Filled.Science, "settings/diagnose"),
        ),
      ),
    )

    val tones = categoryTones()
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge),
      verticalArrangement = Arrangement.spacedBy(Gaps.Edge),
    ) {
      groups.forEachIndexed { index, group ->
        val tone = tones[index % tones.size]
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = tone.container, contentColor = tone.onContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          // The tiles keep the same gap to the category card as to each other.
          Column(Modifier.padding(Gaps.Edge), verticalArrangement = Arrangement.spacedBy(Gaps.Edge)) {
            Row(
              modifier = Modifier.padding(start = 6.dp, top = 6.dp, end = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(group.icon, null, tint = tone.onContainer, modifier = Modifier.size(20.dp))
              Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            TileGrid(group.tiles, stretchLastRow = true, spacing = Gaps.Edge) { tile, wide ->
              SettingTile(
                title = tile.title,
                subtitle = tile.subtitle,
                icon = tile.icon,
                wide = wide,
                iconContainer = tone.accent,
                iconTint = tone.onAccent,
                onClick = { onOpen(tile.route) },
              )
            }
          }
        }
      }
    }
  }
}

// ------------------------------------------------------------------ AI engine

@Composable
fun EngineScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
  SubScreen(stringResource(R.string.section_engine), onBack) { EngineContent(wizard = false, onOpen = onOpen) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineContent(wizard: Boolean, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var testResult by remember { mutableStateOf<String?>(null) }
  var testing by remember { mutableStateOf(false) }
  var realRun by remember { mutableStateOf(false) }
  var realDone by remember { mutableStateOf(false) }
  var sample by remember { mutableStateOf<Detection?>(null) }
  var pendingSample by remember { mutableStateOf<Detection?>(null) }
  var sampleNote by remember { mutableStateOf<String?>(null) }
  var samplePhase by remember { mutableIntStateOf(0) }
  var sampleDone by remember { mutableStateOf(false) }
  var loadingModels by remember { mutableStateOf(false) }
  var connecting by remember { mutableStateOf(false) }
  var models by remember { mutableStateOf<List<String>>(emptyList()) }

  ChoiceCard(stringResource(R.string.engine_rules), stringResource(R.string.engine_rules_body), s.engine == AiEngine.RULES, Icons.AutoMirrored.Filled.Rule) { Ui.update { it.copy(engine = AiEngine.RULES) } }
  ChoiceCard(stringResource(R.string.engine_on_device), stringResource(R.string.engine_on_device_body), s.engine == AiEngine.ON_DEVICE, Icons.Filled.Memory) { Ui.update { it.copy(engine = AiEngine.ON_DEVICE) } }
  // A remote AI receives message contents: the user confirms that they are responsible for it (privacy is only
  // guaranteed with the on-device AI, where nothing leaves the phone).
  var confirmRemote by remember { mutableStateOf<AiEngine?>(null) }
  ChoiceCard(stringResource(R.string.engine_cloud), stringResource(R.string.engine_cloud_body), s.engine == AiEngine.CLOUD, Icons.Filled.Cloud) {
    if (s.engine != AiEngine.CLOUD) confirmRemote = AiEngine.CLOUD
  }
  ChoiceCard(stringResource(R.string.engine_self_hosted), stringResource(R.string.engine_self_hosted_body), s.engine == AiEngine.SELF_HOSTED, Icons.Filled.Dns) {
    if (s.engine != AiEngine.SELF_HOSTED) confirmRemote = AiEngine.SELF_HOSTED
  }
  confirmRemote?.let { target ->
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { confirmRemote = null },
      icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
      title = { Text(stringResource(R.string.remote_ai_privacy_title)) },
      text = { Text(stringResource(R.string.remote_ai_privacy_body)) },
      confirmButton = {
        androidx.compose.material3.TextButton(onClick = {
          Ui.update { it.copy(engine = target) }
          confirmRemote = null
        }) { Text(stringResource(R.string.remote_ai_privacy_accept)) }
      },
      dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRemote = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
  if (s.engine == AiEngine.CLOUD || s.engine == AiEngine.SELF_HOSTED) RemoteAiPrivacyWarning()

  when (s.engine) {
    AiEngine.RULES -> Hint(stringResource(R.string.engine_rules_hint))
    AiEngine.ON_DEVICE -> SettingGroupCard(title = stringResource(R.string.on_device_model)) {
      Text(
        when {
          s.onDeviceModelUri.isNotBlank() && de.juliandigitalproducts.notitocal.analysis.SharedModel.accessible(context, s.onDeviceModelUri) ->
            stringResource(R.string.model_shared_selected, s.onDeviceModelName.ifBlank { s.onDeviceModelUri })
          s.onDeviceModelUri.isNotBlank() -> stringResource(R.string.model_shared_missing)
          s.onDeviceModelFile.isBlank() -> stringResource(R.string.no_model_selected)
          else -> s.onDeviceModelFile.substringAfterLast('/')
        },
        Modifier.padding(horizontal = Gaps.Inner), style = MaterialTheme.typography.bodyLarge,
      )
      Button(onClick = { onOpen("models") }, modifier = Modifier.padding(Gaps.Inner)) { Text(stringResource(R.string.manage_models)) }
      if (s.advancedMode) SettingDropdown(stringResource(R.string.prompt_template), listOf("qwen", "gemma", "llama", "phi", "raw"), s.onDeviceTemplate, { it }) { v -> Ui.update { it.copy(onDeviceTemplate = v) } }
      Hint(stringResource(R.string.on_device_hint))
    }
    AiEngine.CLOUD -> SettingGroupCard(title = stringResource(R.string.provider)) {
      SettingDropdown(stringResource(R.string.provider), ProviderType.entries, s.provider, { it.label }) { p ->
        models = emptyList()
        Ui.update { it.copy(provider = p, providerBaseUrl = "", providerModel = "") }
      }
      if (s.provider.local || s.provider == ProviderType.CUSTOM || s.advancedMode) {
        SettingText(stringResource(R.string.base_url), s.providerBaseUrl, placeholder = s.provider.defaultBaseUrl, supporting = if (s.provider.local) stringResource(R.string.lan_hint) else null) { v ->
          Ui.update { it.copy(providerBaseUrl = v.trim()) }
        }
      }
      SettingText(stringResource(R.string.model), s.providerModel, placeholder = s.provider.defaultModel) { v -> Ui.update { it.copy(providerModel = v.trim()) } }
      if (models.isNotEmpty()) SettingDropdown(stringResource(R.string.available_models), models, s.effectiveModel, { it }) { v -> Ui.update { it.copy(providerModel = v) } }
      if (s.provider.needsKey || s.provider == ProviderType.CUSTOM) {
        androidx.compose.runtime.key(s.provider) { SecretField(stringResource(R.string.api_key), SecretKeys.llm(s.provider), stringResource(R.string.api_key_hint)) }
      }
      FlowRow(Modifier.padding(horizontal = Gaps.Inner), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !loadingModels, onClick = {
          loadingModels = true
          scope.launch {
            models = runSafely { CloudLlmClient(Ui.app.settings.current, Ui.app.secrets.get(SecretKeys.llm(s.provider))).listModels() }
              .onFailure { testResult = resources.getString(R.string.error_prefix, it.message ?: "") }.getOrDefault(emptyList())
            loadingModels = false
          }
        }) { Text(stringResource(R.string.load_models)) }
      }
      if (loadingModels) BusyRow(stringResource(R.string.busy_loading_models))
      Hint(stringResource(if (s.provider.local) R.string.cloud_local_privacy else R.string.cloud_privacy))
    }
    AiEngine.SELF_HOSTED -> SettingGroupCard(title = stringResource(R.string.server)) {
      SettingText(stringResource(R.string.server_url), s.selfHostedUrl, placeholder = "https://example.com/notitocal/") { v -> Ui.update { it.copy(selfHostedUrl = v.trim()) } }
      SecretField(stringResource(R.string.server_token), SecretKeys.SELF_HOSTED)
      OutlinedButton(enabled = !connecting, onClick = {
        connecting = true
        scope.launch {
          testResult = runSafely { Http.get(SelfHosted.url(Ui.app.settings.current.selfHostedUrl, "ping"), SelfHosted.auth(Ui.app.secrets.get(SecretKeys.SELF_HOSTED))) }
            .fold({ resources.getString(R.string.server_ok, it.take(200)) }, { resources.getString(R.string.error_prefix, it.message ?: "") })
          connecting = false
        }
      }, modifier = Modifier.padding(horizontal = Gaps.Inner)) { Text(stringResource(R.string.test_connection)) }
      if (connecting) BusyRow(stringResource(R.string.busy_connecting))
      Hint(stringResource(R.string.server_hint))
      TextButton(onClick = {}, enabled = false, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(stringResource(R.string.server_docs))
      }
    }
  }

  if (s.engine != AiEngine.RULES && !s.advancedMode) SettingGroupCard {
    SettingSwitch(stringResource(R.string.share_sender), s.shareSenderWithAi, stringResource(R.string.share_sender_body)) { v -> Ui.update { it.copy(shareSenderWithAi = v) } }
  }
  if (s.engine != AiEngine.RULES && s.advancedMode) SettingGroupCard(title = stringResource(R.string.ai_parameters)) {
    SettingSwitch(stringResource(R.string.rule_prefilter), s.rulePrefilter, stringResource(R.string.rule_prefilter_body)) { v -> Ui.update { it.copy(rulePrefilter = v, strictness = Strictness.CUSTOM) } }
    if (s.rulePrefilter) SettingSlider(stringResource(R.string.prefilter_threshold), s.prefilterThreshold.toFloat(), 0f..0.8f) { v -> Ui.update { it.copy(prefilterThreshold = v.toDouble(), strictness = Strictness.CUSTOM) } }
    SettingSwitch(stringResource(R.string.fallback_rules), s.fallbackToRules, stringResource(R.string.fallback_rules_body)) { v -> Ui.update { it.copy(fallbackToRules = v) } }
    SettingSwitch(stringResource(R.string.share_sender), s.shareSenderWithAi, stringResource(R.string.share_sender_body)) { v -> Ui.update { it.copy(shareSenderWithAi = v) } }
    if (s.engine == AiEngine.CLOUD) SettingSwitch(stringResource(R.string.json_mode), s.jsonMode, stringResource(R.string.json_mode_body)) { v -> Ui.update { it.copy(jsonMode = v) } }
    SettingSlider(stringResource(R.string.temperature), s.temperature.toFloat(), 0f..1f, steps = 9, format = { "%.1f".format(it) }) { v -> Ui.update { it.copy(temperature = v.toDouble()) } }
    SettingSlider(stringResource(R.string.max_tokens), s.maxTokens.toFloat(), 150f..1500f, format = { it.roundToInt().toString() }) { v -> Ui.update { it.copy(maxTokens = v.roundToInt()) } }
    SettingSlider(stringResource(R.string.timeout), s.timeoutSeconds.toFloat(), 10f..180f, format = { "${it.roundToInt()} s" }) { v -> Ui.update { it.copy(timeoutSeconds = v.roundToInt()) } }
    SettingText(stringResource(R.string.extra_instructions), s.extraInstructions, singleLine = false, placeholder = stringResource(R.string.extra_instructions_hint)) { v -> Ui.update { it.copy(extraInstructions = v) } }
  }

  SettingGroupCard(title = stringResource(R.string.test)) {
    Hint(stringResource(R.string.sample_label, stringResource(R.string.sample_message)))
    Button(enabled = !testing, onClick = {
      testing = true
      sampleDone = false
      samplePhase = 0
      scope.launch {
        val st = Ui.app.settings.current
        val text = resources.getString(R.string.sample_message)
        val found = analyzeSample(text, st) { samplePhase = it }
        pendingSample = found
        sampleNote = explainTest(context, found) +
          (found.rawAnswer?.takeIf { st.advancedMode }?.let { "\n\n" + resources.getString(R.string.raw_answer_label) + "\n" + it } ?: "")
        sampleDone = true
      }
    }, modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 4.dp)) {
      Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(stringResource(R.string.test_engine))
    }
    sample?.let { found ->
      DetectionPreviewCard(found)
      sampleNote?.let { Hint(it) }
      // Same message once more, but processed exactly like a real notification (filters, auto add, targets).
      OutlinedButton(enabled = !realRun, onClick = {
        realRun = true
        realDone = false
        scope.launch {
          val c = Captured(SourceType.NOTIFICATION, appLabel = "Test", sender = "Alex", text = resources.getString(R.string.sample_message))
          val res = Ui.app.pipeline.process(c, userInitiated = false, logFiltered = true)
          testResult = res?.let { realRunSummary(context, it.logId) } ?: resources.getString(R.string.nothing_found)
          realDone = true
        }
      }, modifier = Modifier.padding(horizontal = Gaps.Inner, vertical = 4.dp)) { Text(stringResource(R.string.test_real)) }
    }
  }

  if (testing) AiProgressDialog(
    title = stringResource(R.string.test_engine),
    statusOverride = stringResource(if (samplePhase == 0) R.string.sample_phase_analyse else R.string.sample_phase_place),
    progress = if (samplePhase == 0) null else 0.75f,
    isFinished = sampleDone,
    onDismiss = { testing = false; sample = pendingSample },
  )
  if (realRun) AiProgressDialog(
    title = stringResource(R.string.busy_real_run),
    isFinished = realDone,
    onDismiss = { realRun = false },
  )

  if (!realRun) testResult?.let { r ->
    AlertDialog(
      onDismissRequest = { testResult = null },
      confirmButton = { TextButton(onClick = { testResult = null }) { Text(stringResource(R.string.ok)) } },
      title = { Text(stringResource(R.string.test)) },
      text = { Text(r, modifier = Modifier.verticalScroll(rememberScrollState())) },
    )
  }
}

/**
 * Analyses the sample message like a real one: the configured engine, then the place lookup ("beim Italiener in der
 * Hauptstraße" → a real Italian restaurant nearby), then the strictness rules. [onPhase]: 0 = analysing, 1 = place.
 */
private suspend fun analyzeSample(text: String, st: de.juliandigitalproducts.notitocal.data.AppSettings, onPhase: (Int) -> Unit): Detection {
  val c = Captured(SourceType.MANUAL, appLabel = "Test", sender = "Alex", text = text)
  val d = Ui.app.analyzer.analyze(c, st)
  onPhase(1)
  // The sample always shows the place lookup, even when it is switched off for real messages.
  val placed = runSafely { LocationEnricher.enrich(Ui.app, d, text, st.copy(locationEnrichmentEnabled = true)) }.getOrDefault(d)
  val o = Analyzer.decide(placed, text, st)
  return o.detection.copy(why = o.why.name, whyValue = o.value, whyLimit = o.limit, realDecision = o.decision.name)
}

/** What happened when a test ran like a real notification: added / asked / ignored and why. */
fun realRunSummary(context: android.content.Context, logId: Long): String {
  val e = Ui.app.log.get(logId) ?: return context.getString(R.string.nothing_found)
  val head = context.getString(when (e.status) {
    Status.ADDED -> R.string.real_added
    Status.PARTIAL -> R.string.real_partial
    Status.FAILED -> R.string.real_failed
    Status.PENDING -> R.string.real_asked
    else -> R.string.real_ignored
  })
  return listOfNotNull(head, explainWhy(context, e.detection), e.result.takeIf { it.isNotBlank() }).joinToString("\n\n")
}

fun formatDetection(context: android.content.Context, d: Detection): String = buildString {
  append(context.getString(when (d.kind) { Kind.EVENT -> R.string.kind_event; Kind.TASK -> R.string.kind_task; Kind.NOTE -> R.string.kind_note; Kind.NONE -> R.string.kind_none }))
  append(": ").append(d.title).append('\n')
  describeWhen(context, d)?.let { append(it).append('\n') }
  d.location?.let { append("📍 ").append(it).append('\n') }
  append(context.getString(R.string.confidence_value, (d.confidence * 100).roundToInt())).append('\n')
  append(d.engine).append(" · ").append(d.reason)
}

/** Permanent reminder while a remote AI is selected: privacy is then the user's responsibility. */
@Composable
fun RemoteAiPrivacyWarning(modifier: Modifier = Modifier) {
  androidx.compose.material3.Card(
    modifier = modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 8.dp),
    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Row(Modifier.padding(Gaps.Inner), verticalAlignment = Alignment.Top) {
      Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
      Column(Modifier.padding(start = 12.dp)) {
        Text(stringResource(R.string.remote_ai_privacy_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(stringResource(R.string.remote_ai_privacy_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
      }
    }
  }
}
