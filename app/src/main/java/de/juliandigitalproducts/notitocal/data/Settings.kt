package de.juliandigitalproducts.notitocal.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/** How messages are analysed. */
@Serializable
enum class AiEngine { RULES, ON_DEVICE, CLOUD, SELF_HOSTED }

/** Engine used for OCR / text recognition in screenshots and notification images. */
@Serializable
enum class OcrEngineMode(val label: String, val subtitle: String) {
  MLKIT_LOCAL("ML Kit", "On-device text recognition"),
  VISION_AI("Vision AI", "Image analysis by the AI model"),
  HYBRID("Hybrid (ML Kit + AI)", "Local OCR first, AI when needed"),
}

/** Wire format of an LLM API. */
enum class ApiStyle { OPENAI, ANTHROPIC, GEMINI }

/** Supported LLM providers. Every OpenAI-compatible server works via [CUSTOM]. */
@Serializable
enum class ProviderType(
  val label: String,
  val style: ApiStyle,
  val defaultBaseUrl: String,
  val defaultModel: String,
  val needsKey: Boolean,
  val local: Boolean,
  val vision: Boolean,
) {
  OPENAI("OpenAI", ApiStyle.OPENAI, "https://api.openai.com/v1", "gpt-4.1-mini", true, false, true),
  ANTHROPIC("Anthropic Claude", ApiStyle.ANTHROPIC, "https://api.anthropic.com/v1", "claude-haiku-5-5", true, false, true),
  XAI("xAI Grok", ApiStyle.OPENAI, "https://api.x.ai/v1", "grok-3-mini", true, false, true),
  GEMINI("Google Gemini", ApiStyle.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", true, false, true),
  MISTRAL("Mistral AI", ApiStyle.OPENAI, "https://api.mistral.ai/v1", "mistral-small-latest", true, false, false),
  GROQ("Groq", ApiStyle.OPENAI, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true, false, false),
  OPENROUTER("OpenRouter", ApiStyle.OPENAI, "https://openrouter.ai/api/v1", "openrouter/auto", true, false, true),
  DEEPSEEK("DeepSeek", ApiStyle.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat", true, false, false),
  OLLAMA("Ollama (LAN)", ApiStyle.OPENAI, "http://192.168.1.10:11434/v1", "qwen2.5:3b", false, true, true),
  OMLX("oMLX (Apple Silicon LAN)", ApiStyle.OPENAI, "http://192.168.1.10:1060/v1", "mlx-community/Qwen2.5-7B-Instruct-4bit", false, true, false),
  LM_STUDIO("LM Studio (LAN)", ApiStyle.OPENAI, "http://192.168.1.10:1234/v1", "local-model", false, true, false),
  LLAMA_CPP("llama.cpp / LocalAI / vLLM", ApiStyle.OPENAI, "http://192.168.1.10:8080/v1", "default", false, true, false),
  CUSTOM("OpenAI-compatible", ApiStyle.OPENAI, "https://", "", false, false, false),
}

/** Delivery targets. */
@Serializable
enum class TargetType(val forEvents: Boolean, val forTasks: Boolean, val interactive: Boolean = false) {
  DEVICE_CALENDAR(true, true),
  CALENDAR_APP(true, false, interactive = true),
  TASK_PROVIDER(false, true),
  GOOGLE_TASKS(false, true, interactive = true),
  SAMSUNG_REMINDER(true, true, interactive = true),
  TODOIST(true, true),
  CALDAV(true, true),
  NOTION(true, true),
  SELF_HOSTED(true, true),
  WEBHOOK(true, true),
  AUTOMATION_BROADCAST(true, true),
  SHARE(true, true, interactive = true),
  AI_AGENT(true, true),
}

@Serializable
enum class RuleKind { ANY, EVENT, TASK }

/**
 * Routes a detection to a specific calendar / task list / AI routine, chosen by sender, keywords or (with an AI
 * engine) by the AI from [description]. Empty / -1 values keep the global setting.
 */
@Serializable
data class RoutingRule(
  val id: String = java.util.UUID.randomUUID().toString(),
  val name: String = "",
  val enabled: Boolean = true,
  val kind: RuleKind = RuleKind.ANY,
  val senders: String = "",
  val keywords: String = "",
  val description: String = "",
  val calendarId: Long = -1,
  val tasksListId: Long = -1,
  val todoistProjectId: String = "",
  val caldavEventUrl: String = "",
  val caldavTaskUrl: String = "",
  val notionDatabaseId: String = "",
  val extraTargets: Set<TargetType> = emptySet(),
  val onlyExtraTargets: Boolean = false,
  val agentInstructions: String = "",
)

/** When collected messages are analysed. Capturing itself always happens instantly. */
@Serializable
enum class AnalysisSchedule(val intervalMinutes: Int) { IMMEDIATE(0), EVERY_15_MIN(15), HOURLY(60), EVERY_3H(180), TIMES_DAILY(0), DAILY(0) }

@Serializable
enum class Strictness { RELAXED, BALANCED, STRICT, CUSTOM }

/** What happens with a detection above the minimum confidence. */
@Serializable
enum class ActionMode { AUTO, ASK, OFF }

/** Which apps' screenshots are analysed (the app is read from the end of the file name, e.g. "…_WhatsApp.jpg"). */
@Serializable
enum class ScreenshotAppFilter { ALL, NOTIFICATION_APPS, SELECTED }

/** Filter mode for groups, contacts, or senders: "All except" (blacklist) vs. "Only following" (whitelist). */
@Serializable
enum class FilterListMode { ALL_EXCEPT, ONLY_SELECTED }

/** Operational modes for address, location and appointment recognition. */
@Serializable
enum class LocationRecognitionMode(@androidx.annotation.StringRes val label: Int, @androidx.annotation.StringRes val description: Int) {
  FAST(de.juliandigitalproducts.notitocal.R.string.recog_fast, de.juliandigitalproducts.notitocal.R.string.recog_fast_body),
  MEDIUM(de.juliandigitalproducts.notitocal.R.string.recog_medium, de.juliandigitalproducts.notitocal.R.string.recog_medium_body),
  THOROUGH(de.juliandigitalproducts.notitocal.R.string.recog_thorough, de.juliandigitalproducts.notitocal.R.string.recog_thorough_body),
}

@Serializable
enum class TelegramDialogKind { GROUP, PERSON, CHANNEL }

/** A Telegram group, contact, or channel for explicit inclusion/exclusion in the Telegram User-API source. */
@Serializable
data class TelegramDialogItem(
  val id: String = java.util.UUID.randomUUID().toString(),
  val title: String = "",
  val username: String = "",
  val kind: TelegramDialogKind = TelegramDialogKind.GROUP,
  val selected: Boolean = true,
)

/** Read-only IMAP mailbox configuration (supports multiple accounts simultaneously). */
@Serializable
data class ImapAccount(
  val id: String = java.util.UUID.randomUUID().toString(),
  val label: String = "",
  val host: String = "",
  val port: Int = 993,
  val ssl: Boolean = true,
  val username: String = "",
  val folder: String = "INBOX",
  val enabled: Boolean = true,
)

@Serializable
data class AppSettings(
  /** Bumped by [SettingsMigration] when stored JSON gains fields. Absent JSON is treated as version 3. */
  val settingsVersion: Int = SettingsMigration.CURRENT,
  val onboardingDone: Boolean = false,
  val paused: Boolean = false,
  val advancedMode: Boolean = false,

  // Sources
  val notificationsEnabled: Boolean = true,
  val monitoredApps: Set<String> = DEFAULT_APPS,
  val ignoreGroupChats: Boolean = false,
  val screenshotsEnabled: Boolean = false,
  val screenshotVision: Boolean = false,
  /** Automatically analyse embedded screenshots and pictures inside push notifications with OCR. */
  val analyzeNotificationImages: Boolean = true,
  /** Send pictures from notifications (e.g. WhatsApp photos) to the vision AI. Off by default: only local OCR reads them. */
  val notificationImagesToAi: Boolean = false,
  /** Selected OCR engine mode for screenshots and images. */
  val ocrEngineMode: OcrEngineMode = OcrEngineMode.MLKIT_LOCAL,
  /** Use the transcripts of the Anti-Vocale app for voice messages (NotiToCal does not transcribe audio itself). */
  val antiVocaleEnabled: Boolean = true,
  /** SAF tree URI of Anti-Vocale's export folder where transcript files (.txt/.srt/.vtt) are saved. */
  val antiVocaleFolderUri: String = "",
  val antiVocaleAutoImport: Boolean = true,
  /** How often the Anti-Vocale folder is checked in the background, in minutes. 0 = only on a voice-message notification or by hand. */
  val antiVocaleCheckMinutes: Int = 30,
  val memoSummary: Boolean = true,
  /** Screenshot folders chosen with the system folder picker. Removing one only stops watching. */
  val screenshotFolderUris: List<String> = emptyList(),
  val screenshotAppFilter: ScreenshotAppFilter = ScreenshotAppFilter.ALL,
  /** Package names for [ScreenshotAppFilter.SELECTED]. */
  val screenshotApps: Set<String> = emptySet(),
  /** Transcripts of voice messages are analysed with the collected analysis instead of right when they arrive. */
  val voiceBatched: Boolean = true,
  /** Read-only IMAP accounts (multiple accounts supported). Passwords live in [SecretStore]. */
  val imapEnabled: Boolean = false,
  val imapAccounts: List<ImapAccount> = emptyList(),
  val imapPollMinutes: Int = 30,
  val imapTimeWindowHours: Int = 24,
  /** Telegram Bot source (read-only getUpdates). Token lives in [SecretStore]. */
  val telegramEnabled: Boolean = false,
  val telegramChatId: String = "",
  val telegramPollMinutes: Int = 15,
  val telegramTimeWindowHours: Int = 24,
  /** Telegram User-API (invisible client API access without a visible bot). */
  val telegramApiEnabled: Boolean = false,
  val telegramApiId: String = "",
  val telegramPhone: String = "",
  val telegramApiBridgeUrl: String = "",
  val telegramApiConnectedUser: String = "",
  val telegramFilterMode: FilterListMode = FilterListMode.ALL_EXCEPT,
  val telegramDialogs: List<TelegramDialogItem> = emptyList(),
  val telegramRateLimitEnabled: Boolean = true,
  val telegramMaxMsgsPerMinute: Int = 10,
  /** Dedicated WhatsApp & WhatsApp Business source configuration. */
  val whatsappEnabled: Boolean = true,
  val whatsappPersonalEnabled: Boolean = true,
  val whatsappBusinessEnabled: Boolean = true,
  val whatsappIgnoreGroups: Boolean = false,
  val whatsappFilterMode: FilterListMode = FilterListMode.ALL_EXCEPT,
  val whatsappFilterContacts: String = "",
  val whatsappMaxMsgsPerMinute: Int = 15,
  /** Dedicated SMS & RCS messages source configuration. */
  val smsRcsEnabled: Boolean = true,
  val smsIgnore2fa: Boolean = true,
  val smsFilterMode: FilterListMode = FilterListMode.ALL_EXCEPT,
  val smsFilterSenders: String = "",
  /** Calendar invitations & ICS file source configuration. */
  val icsEnabled: Boolean = true,
  val icsFolderUri: String = "",
  /** Share menu & clipboard import source. */
  val shareEnabled: Boolean = true,
  val clipboardEnabled: Boolean = false,

  // Battery & analysis
  val analysisSchedule: AnalysisSchedule = AnalysisSchedule.TIMES_DAILY,
  /** Minutes of day for [AnalysisSchedule.TIMES_DAILY]; the first one is used for [AnalysisSchedule.DAILY]. */
  val analysisTimes: List<Int> = listOf(8 * 60, 13 * 60, 18 * 60),
  /** Messages about today / the next hours are analysed at once even when collecting. */
  val urgentImmediately: Boolean = true,
  val analyzeOnlyWifi: Boolean = false,
  val analyzeOnlyCharging: Boolean = false,

  // Which notifications NotiToCal itself shows
  val notifyConfirm: Boolean = true,
  val notifyMemoText: Boolean = false,
  val notifyErrors: Boolean = true,

  // Engine
  val engine: AiEngine = AiEngine.RULES,
  val provider: ProviderType = ProviderType.OPENAI,
  val providerBaseUrl: String = "",
  val providerModel: String = "",
  val jsonMode: Boolean = true,
  val temperature: Double = 0.1,
  val maxTokens: Int = 500,
  val timeoutSeconds: Int = 45,
  val rulePrefilter: Boolean = true,
  val prefilterThreshold: Double = 0.2,
  val fallbackToRules: Boolean = true,
  val extraInstructions: String = "",
  /** Off by default (C27): sender and chat names stay on the phone unless the user wants them in AI requests. */
  val shareSenderWithAi: Boolean = false,
  val onDeviceModelFile: String = "",
  /** SAF document URI of a model file used in place (no copy). Empty when the imported file is the source. */
  val onDeviceModelUri: String = "",
  /** Display name of [onDeviceModelUri], kept so the screen can label it without opening the file. */
  val onDeviceModelName: String = "",
  val onDeviceTemplate: String = "qwen",
  val selfHostedUrl: String = "",

  // Strictness
  val strictness: Strictness = Strictness.BALANCED,
  val eventMinConfidence: Double = 0.6,
  val taskMinConfidence: Double = 0.6,
  val autoAddConfidence: Double = 0.85,
  /** Simple mode: add everything above the minimum confidence without asking. */
  val autoAddUnsure: Boolean = false,
  val eventMode: ActionMode = ActionMode.ASK,
  val taskMode: ActionMode = ActionMode.ASK,
  val requireTimeForEvents: Boolean = false,
  val undatedEventsAsTasks: Boolean = true,
  /** Optional (off): a message with only a complete address (street, number, city) becomes a quiet suggestion, never added automatically. */
  val addressSuggestions: Boolean = false,
  /** Location enrichment: automatically resolve venue names / places from past calendar events or OSM. */
  val locationEnrichmentEnabled: Boolean = true,
  val locationSearchEffort: String = "balanced", // "fast", "balanced", "thorough"
  val locationSearchSources: Set<String> = setOf("history", "osm", "photon"),
  /**
   * With a local engine (RULES / ON_DEVICE) place names do not go to Photon / OpenStreetMap unless the user turns
   * this on explicitly. With CLOUD / SELF_HOSTED the online sources are used as before.
   */
  val locationOnlineWithLocalEngine: Boolean = false,
  val locationRecognitionMode: LocationRecognitionMode = LocationRecognitionMode.MEDIUM,
  val homeCity: String = "",
  val homePostalCode: String = "",
  val homeLatitude: Double? = null,
  val homeLongitude: Double? = null,
  /** Periodically remembers where the user is (on the device only) to find places like "beim Italiener". */
  val locationSampling: Boolean = false,
  /** Minutes between two location samples (WorkManager allows 15 and more). */
  val locationSampleMinutes: Int = 60,
  val ignorePast: Boolean = true,
  val maxDaysAhead: Int = 365,
  val minTextLength: Int = 8,
  val dedupeMinutes: Int = 120,
  /** When a similar event is already on the device, fill empty fields instead of inserting another one. */
  val mergeDuplicateEvents: Boolean = true,
  /** Similarity threshold (0..1) for matching existing calendar events when [mergeDuplicateEvents] is enabled. */
  val duplicateThreshold: Double = 0.66,
  /** Duplicate finder & merger settings. */
  val duplicateRangePreset: String = "30d",
  val duplicateCustomDaysBack: Int = 30,
  val duplicateCustomDaysAhead: Int = 30,
  val duplicateCrossCalendar: Boolean = true,
  val duplicateSelectedCalendarIds: Set<Long> = emptySet(),
  val duplicateUseAiForUnsure: Boolean = false,
  val duplicateAiThreshold: Double = 0.55,
  val duplicateAutoDecide: Boolean = false,
  val duplicateRemovalMode: String = "DELETE",
  val quietHours: Boolean = false,
  val quietStartMinute: Int = 22 * 60,
  val quietEndMinute: Int = 7 * 60,
  val boostKeywords: String = "",
  val blockKeywords: String = "",
  val blockedSenders: String = "",
  val defaultEventMinutes: Int = 60,
  val reminderMinutes: Int = 30,
  val dayFirst: Boolean = true,
  val algorithmVersion: Int = 2,
  val sourceAlgorithmVersions: Map<String, Int> = emptyMap(),
  /** Style injected into the analysis prompt. See StyleTemplates. */
  val eventTemplateId: String = "event_clean",
  val taskTemplateId: String = "task_check",

  // Targets
  val eventTargets: Set<TargetType> = setOf(TargetType.DEVICE_CALENDAR),
  val taskTargets: Set<TargetType> = emptySet(),
  val unfilteredTargets: Set<TargetType> = emptySet(),
  /** Automatic forwarding threshold (0..1) for the Unfiltered category. */
  val unfilteredAutoConfidence: Double = 0.5,
  val calendarId: Long = -1,
  val tasksAuthority: String = "org.tasks.opentasks",
  val tasksListId: Long = -1,
  val todoistProjectId: String = "",
  val caldavEventUrl: String = "",
  val caldavTaskUrl: String = "",
  val caldavUser: String = "",
  val notionDatabaseId: String = "",
  val notionTitleProperty: String = "Name",
  val notionDateProperty: String = "Date",
  val notionTypeProperty: String = "",
  val webhookUrl: String = "",
  val sharePackage: String = "",
  /** Receiver of the automation broadcast; empty = Tasker, MacroDroid and Automate. */
  val broadcastPackage: String = "",
  /** The automation broadcast also carries the sender and the message text (on: as before). */
  val broadcastIncludesSource: Boolean = true,
  val notifyOnAdd: Boolean = true,
  val webhookBodyTemplate: String = "",

  // Routing and AI routines
  val routingRules: List<RoutingRule> = emptyList(),
  val aiRouting: Boolean = true,
  val agentProvider: ProviderType = ProviderType.ANTHROPIC,
  val agentBaseUrl: String = "",
  val agentModel: String = "",
  val agentInstructions: String = "",
  val agentMaxTokens: Int = 2000,

  // Privacy
  val storeMessageText: Boolean = true,
  val storeScreenshotHistory: Boolean = true,
  /** Webhook, self-hosted server: also send the original message text and sender (C27; before tied to [storeMessageText]). */
  val sendSourceToTargets: Boolean = false,
  /** The sender's name may stay in entry titles ("Treffen (Anna)") – for every target, style and engine. */
  val senderInTitle: Boolean = true,
  /** Terms replaced before any language-model request. */
  val aliases: List<AliasEntry> = emptyList(),
  /** After a model answer, map aliases back to the real terms. Off keeps the aliases in the entry. */
  val restoreAliases: Boolean = true,
  val keepLogDays: Int = 90,
  /** Drop log rows in a final state (added or ignored) as soon as processing finishes. Pending rows stay. */
  val deleteAfterProcessing: Boolean = false,

  // UI & Widgets
  val compactOverview: Boolean = false,
  val widgetMemosOnlyActionable: Boolean = false,
  /** Hide interactive setup wizards in all settings submenus for a compact view. */
  val hideWizardsInMenus: Boolean = false,

  // Duplicates & Holidays
  /** Automatically ignore and exclude public/legal holiday calendars and holiday entries during duplicate checks. */
  val duplicateIgnoreHolidays: Boolean = true,

  // Diagnostics & Logging
  /** When enabled, in-memory diagnostic events and debug logs are collected for bug reports. */
  val diagnosticLoggingEnabled: Boolean = true,
) {
  fun withPreset(p: Strictness): AppSettings = when (p) {
    Strictness.RELAXED -> copy(
      strictness = p, eventMinConfidence = 0.45, taskMinConfidence = 0.45, autoAddConfidence = 0.7,
      requireTimeForEvents = false, undatedEventsAsTasks = true, minTextLength = 6, prefilterThreshold = 0.1,
    )
    Strictness.BALANCED -> copy(
      strictness = p, eventMinConfidence = 0.6, taskMinConfidence = 0.6, autoAddConfidence = 0.85,
      requireTimeForEvents = false, undatedEventsAsTasks = true, minTextLength = 8, prefilterThreshold = 0.2,
    )
    Strictness.STRICT -> copy(
      strictness = p, eventMinConfidence = 0.75, taskMinConfidence = 0.8, autoAddConfidence = 0.93,
      requireTimeForEvents = true, undatedEventsAsTasks = false, minTextLength = 12, prefilterThreshold = 0.35,
    )
    Strictness.CUSTOM -> copy(strictness = p)
  }

  /** Image bytes are sent to a vision model only together with that option and a remote engine. */
  fun visionForImages(): Boolean =
    (screenshotVision || ocrEngineMode == OcrEngineMode.VISION_AI || ocrEngineMode == OcrEngineMode.HYBRID) &&
      engine in setOf(AiEngine.CLOUD, AiEngine.SELF_HOSTED)

  /**
   * A stored cloud API key is used only when "Cloud" is the chosen engine (M5). With Rules, On-Device or the own
   * server nothing goes to a cloud provider, also not for the duplicate check or the analysis pipelines.
   */
  val cloudAllowed: Boolean get() = engine == AiEngine.CLOUD

  /** Online place lookups (Photon, OpenStreetMap) are allowed: always with a remote engine, else only on request. */
  val onlineLocationAllowed: Boolean
    get() = engine in setOf(AiEngine.CLOUD, AiEngine.SELF_HOSTED) || locationOnlineWithLocalEngine

  /** The selected place sources, without the online ones when [onlineLocationAllowed] is false. */
  val effectiveLocationSources: Set<String>
    get() {
      val selected = locationSearchSources.ifEmpty { setOf("history", "osm", "photon") }
      return if (onlineLocationAllowed) selected else selected - ONLINE_LOCATION_SOURCES
    }

  val effectiveBaseUrl: String get() = providerBaseUrl.ifBlank { provider.defaultBaseUrl }.trimEnd('/')
  val effectiveModel: String get() = providerModel.ifBlank { provider.defaultModel }
  val effectiveAgentModel: String get() = agentModel.ifBlank { if (agentProvider == ProviderType.ANTHROPIC) "claude-opus-5-5" else agentProvider.defaultModel }

  /** Anti-Vocale transcripts are read automatically (folder chosen, switch on). */
  val antiVocaleActive: Boolean
    get() = antiVocaleEnabled && antiVocaleAutoImport && antiVocaleFolderUri.isNotBlank()

  /** Settings used for the AI routine target: same client code, other provider/model, free-text answers. */
  fun forAgent(): AppSettings = copy(
    provider = agentProvider, providerBaseUrl = agentBaseUrl, providerModel = effectiveAgentModel, jsonMode = false,
    maxTokens = agentMaxTokens, timeoutSeconds = maxOf(timeoutSeconds, 120),
  )

  companion object {
    /** Place sources that send the search text to a server. */
    val ONLINE_LOCATION_SOURCES = setOf("osm", "photon")

    val DEFAULT_APPS = setOf(
      "com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms", "org.telegram.messenger", "ch.threema.app",
      "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.google.android.gm",
      "com.microsoft.office.outlook", "com.microsoft.teams", "com.Slack", "com.discord", "com.facebook.orca",
      "com.instagram.android", "eu.faircode.email", "com.fsck.k9", "net.thunderbird.android",
    )
  }
}

/** Keys of values kept in [SecretStore]. */
object SecretKeys {
  fun llm(p: ProviderType) = "llm_key_${p.name}"
  fun imap(accountId: String) = "imap_pwd_$accountId"
  const val TELEGRAM_BOT = "telegram_bot_token"
  const val TELEGRAM_API_HASH = "telegram_api_hash"
  const val TELEGRAM_2FA_PASSWORD = "telegram_2fa_password"
  const val TELEGRAM_API_SESSION = "telegram_api_session"
  const val TELEGRAM_BRIDGE_TOKEN = "telegram_bridge_token"
  const val HF_TOKEN = "hf_token"
  const val TODOIST = "todoist_token"
  const val CALDAV = "caldav_password"
  const val NOTION = "notion_token"
  const val WEBHOOK_AUTH = "webhook_auth"
  const val SELF_HOSTED = "self_hosted_token"
}

/** Backup container for importing and exporting user settings locally as JSON. */
@Serializable
data class SettingsBackup(
  val app: String = "NotiToCal",
  val backupVersion: Int = 3,
  val exportedAt: Long = System.currentTimeMillis(),
  val settings: AppSettings,
  /** Saved credentials & API keys when exported without a separate encryption password. */
  val secrets: Map<String, String> = emptyMap(),
  /** API keys and passwords when encrypted with an optional backup password. */
  val secretsEncrypted: EncryptedSecrets? = null,
  /** Ignored duplicate event cluster signatures. */
  val ignoredDuplicates: Set<String> = emptySet(),
  /** 30-day duplicate merge history & undo records. */
  val mergeHistory: List<de.juliandigitalproducts.notitocal.output.DuplicateMatcher.MergeHistoryRecord> = emptyList(),
  /** The location history ([de.juliandigitalproducts.notitocal.places.PlaceStore]), only ever encrypted. */
  val placesEncrypted: EncryptedSecrets? = null,
)

@Serializable
data class EncryptedSecrets(val salt: String, val iv: String, val data: String, val iterations: Int = BackupCrypto.ITERATIONS)

/** Password encryption for secrets in a backup file: PBKDF2-HMAC-SHA256 key, AES-256-GCM (C10). */
object BackupCrypto {
  const val ITERATIONS = 210_000
  /** Accepted iteration counts of an imported file: a manipulated huge value would hang the app. */
  val ITERATION_RANGE = 100_000..2_000_000
  private val STRING_MAP = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())
  const val MIN_PASSWORD = 8

  fun encrypt(secrets: Map<String, String>, password: String): EncryptedSecrets {
    val random = java.security.SecureRandom()
    val salt = ByteArray(16).also(random::nextBytes)
    val iv = ByteArray(12).also(random::nextBytes)
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), javax.crypto.spec.GCMParameterSpec(128, iv))
    val plain = AppJson.encodeToString(STRING_MAP, secrets)
    val b64 = java.util.Base64.getEncoder()
    return EncryptedSecrets(b64.encodeToString(salt), b64.encodeToString(iv), b64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8))))
  }

  /** Throws [javax.crypto.AEADBadTagException] for a wrong password or a modified file. */
  fun decrypt(e: EncryptedSecrets, password: String): Map<String, String> {
    require(e.iterations in ITERATION_RANGE) { "unsupported iteration count" }
    val b64 = java.util.Base64.getDecoder()
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(password, b64.decode(e.salt), e.iterations), javax.crypto.spec.GCMParameterSpec(128, b64.decode(e.iv)))
    val plain = String(cipher.doFinal(b64.decode(e.data)), Charsets.UTF_8)
    return AppJson.decodeFromString(STRING_MAP, plain)
  }

  private fun key(password: String, salt: ByteArray, iterations: Int): javax.crypto.SecretKey {
    val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, 256)
    val raw = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    spec.clearPassword()
    return javax.crypto.spec.SecretKeySpec(raw, "AES")
  }

  /** Only the app's own secret names are restored, with a sane length (C10: no unchecked import). */
  fun sanitize(secrets: Map<String, String>): Map<String, String> =
    secrets.filter { (k, v) -> k.matches(Regex("""[A-Za-z0-9_.\-]{1,64}""")) && v.length <= 8192 }
}
