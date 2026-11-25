package de.juliandigitalproducts.notitocal.data

import de.juliandigitalproducts.notitocal.output.DuplicateJudge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import de.juliandigitalproducts.notitocal.model.runSafely

val AppJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  isLenient = true
  coerceInputValues = true
  explicitNulls = false
}

private val Context.dataStore by preferencesDataStore("settings")
private val SETTINGS_KEY = stringPreferencesKey("settings_json")
/** Copy of a settings document that could not be read, kept instead of being overwritten with defaults (C25). */
private val UNREADABLE_KEY = stringPreferencesKey("settings_json_unreadable")

/** Settings persisted as one JSON document; [state] is always the latest value. */
class SettingsRepository(private val context: Context, scope: CoroutineScope) {
  private fun decode(raw: String?): AppSettings {
    if (raw.isNullOrBlank()) return AppSettings()
    val obj = runSafely { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull()
    val version = (obj?.get("settingsVersion") as? JsonPrimitive)?.content?.toIntOrNull() ?: 3
    val decoded = runSafely { AppJson.decodeFromString(AppSettings.serializer(), raw) }.getOrNull() ?: return AppSettings()
    return SettingsMigration.migrate(decoded, version, (obj?.get("transcriber") as? JsonPrimitive)?.content)
  }

  private fun readable(raw: String?) = raw.isNullOrBlank() || runSafely { AppJson.decodeFromString(AppSettings.serializer(), raw) }.isSuccess

  // The first read no longer blocks Application.onCreate: it runs in the background, and only a caller that needs
  // the settings before they are loaded waits for them (normally a few milliseconds right after a cold start).
  private val firstValue = kotlinx.coroutines.CompletableDeferred<AppSettings>()
  @Volatile private var latest: AppSettings? = null

  val state: StateFlow<AppSettings> = context.dataStore.data
    .map { decode(it[SETTINGS_KEY]) }
    .onEach { latest = it; firstValue.complete(it) }
    .stateIn(scope, SharingStarted.Eagerly, AppSettings())

  /** True once the stored settings were read (the splash screen stays until then). */
  val isLoaded: Boolean get() = latest != null

  val current: AppSettings get() = latest ?: runBlocking { firstValue.await() }

  /** Like [current], but suspends instead of blocking the thread until the stored settings are read. */
  suspend fun loaded(): AppSettings = latest ?: firstValue.await()

  /** After it returns, [current] already holds the change (the [state] flow follows a moment later). */
  suspend fun update(transform: (AppSettings) -> AppSettings) {
    val saved = context.dataStore.edit { prefs ->
      val raw = prefs[SETTINGS_KEY]
      // The next write replaces an unreadable document with defaults; keep the original so it can be recovered.
      if (!readable(raw) && prefs[UNREADABLE_KEY] == null) {
        prefs[UNREADABLE_KEY] = raw!!
        android.util.Log.w("NotiToCal", "settings could not be read, original kept as settings_json_unreadable")
      }
      val next = transform(decode(raw))
      prefs[SETTINGS_KEY] = AppJson.encodeToString(AppSettings.serializer(), next)
    }
    // Read-your-write: code that changes a setting and acts on it right away must not see the old value.
    latest = decode(saved[SETTINGS_KEY])
  }
}

/** API keys and passwords, encrypted with an Android Keystore AES-GCM key. */
class SecretStore(context: Context) {
  private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
  private val alias = "notitocal_secrets"

  private fun key(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    gen.init(
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build(),
    )
    return gen.generateKey()
  }

  fun get(name: String): String {
    val raw = prefs.getString(name, null) ?: return ""
    return runSafely {
      val bytes = Base64.decode(raw, Base64.NO_WRAP)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
      String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrDefault("")
  }

  fun put(name: String, value: String) {
    if (value.isBlank()) { prefs.edit().remove(name).apply(); return }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val enc = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    prefs.edit().putString(name, Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
  }

  fun has(name: String) = prefs.contains(name)

  fun all(): Map<String, String> =
    prefs.all.keys.associateWith { get(it) }.filterValues { it.isNotBlank() }

  fun restoreAll(map: Map<String, String>) {
    map.forEach { (k, v) -> put(k, v) }
  }
}

data class LogEntry(
  val id: Long,
  val created: Long,
  val source: SourceType,
  val packageName: String,
  val appLabel: String,
  val sender: String,
  val text: String,
  val detection: Detection,
  val status: Status,
  val result: String,
  /** Came from a group chat (stored since DB version 6; older rows are false). */
  val isGroup: Boolean = false,
)

/** Local history of everything NotiToCal analysed. */
class LogRepository(context: Context) : SQLiteOpenHelper(context, "notitocal.db", null, 6) {
  private val appContext: Context = context.applicationContext ?: context
  private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
  /** The newest [IN_MEMORY_LIMIT] entries for the UI. Use [queryHistory], [allEntries] or [get] for older rows. */
  val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

  /** Screenshot files are deleted off the calling (often main) thread. */
  private val fileCleanup = java.util.concurrent.Executors.newSingleThreadExecutor()

  companion object {
    const val IN_MEMORY_LIMIT = 300
    private const val COLUMNS = "id, created, source, pkg, app, sender, text, detection, status, result, grp"
  }

  private fun readRow(c: android.database.Cursor) = LogEntry(
    id = c.getLong(0),
    created = c.getLong(1),
    source = runSafely { SourceType.valueOf(c.getString(2)) }.getOrDefault(SourceType.MANUAL),
    packageName = c.getString(3).orEmpty(),
    appLabel = c.getString(4).orEmpty(),
    sender = c.getString(5).orEmpty(),
    text = c.getString(6).orEmpty(),
    detection = runSafely { AppJson.decodeFromString(Detection.serializer(), c.getString(7)) }.getOrDefault(Detection()),
    status = runSafely { Status.valueOf(c.getString(8)) }.getOrDefault(Status.IGNORED),
    result = c.getString(9).orEmpty(),
    isGroup = c.getInt(10) == 1,
  )

  private fun query(sql: String, args: Array<String>? = null): List<LogEntry> {
    val list = mutableListOf<LogEntry>()
    readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) list += readRow(c) }
    return list
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE log (
        id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, source TEXT NOT NULL, pkg TEXT, app TEXT,
        sender TEXT, text TEXT, detection TEXT NOT NULL, status TEXT NOT NULL, result TEXT, hash TEXT,
        grp INTEGER NOT NULL DEFAULT 0)""",
    )
    db.execSQL("CREATE INDEX log_hash ON log(hash, created)")
    db.execSQL("CREATE INDEX IF NOT EXISTS log_created ON log(created)")
    createQueue(db)
    createHashStore(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) createQueue(db)
    if (oldVersion < 3) {
      createHashStore(db)
      backfillHashStore(db)
    }
    if (oldVersion < 4) db.execSQL("CREATE INDEX IF NOT EXISTS log_created ON log(created)")
    // 1.5.17: start time in the fingerprint, so "Meeting 9:00" and "Meeting 15:00" are no longer one duplicate.
    if (oldVersion in 3..4) {
      db.execSQL("ALTER TABLE hash_store ADD COLUMN time TEXT NOT NULL DEFAULT ''")
      db.rawQuery("SELECT id, detection FROM log", null).use { c ->
        while (c.moveToNext()) {
          val d = runSafely { AppJson.decodeFromString(Detection.serializer(), c.getString(1)) }.getOrNull() ?: continue
          db.update("hash_store", ContentValues().apply { put("time", HistoryPolicy.timeOf(d)) }, "log_id = ?", arrayOf(c.getLong(0).toString()))
        }
      }
    }
    // Group flag, so a group message without a person still belongs to the group chat (ChatScope, NTC-R6).
    if (oldVersion < 6) db.execSQL("ALTER TABLE log ADD COLUMN grp INTEGER NOT NULL DEFAULT 0")
  }

  private fun createQueue(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS queue (
        id INTEGER PRIMARY KEY AUTOINCREMENT, posted INTEGER NOT NULL, source TEXT NOT NULL, pkg TEXT, app TEXT,
        sender TEXT, text TEXT, grp INTEGER NOT NULL DEFAULT 0)""",
    )
  }

  /** Content-free duplicate index: hashes and a day, no message text. Same retention as [log]. */
  private fun createHashStore(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS hash_store (
        log_id INTEGER PRIMARY KEY, created INTEGER NOT NULL, hash TEXT NOT NULL, status TEXT NOT NULL,
        kind TEXT NOT NULL, title_hash TEXT NOT NULL, text_hash TEXT NOT NULL, day TEXT NOT NULL,
        time TEXT NOT NULL DEFAULT '')""",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS hash_store_hash ON hash_store(hash, created)")
  }

  private fun backfillHashStore(db: SQLiteDatabase) {
    db.rawQuery("SELECT id, created, hash, text, detection, status FROM log", null).use { c ->
      while (c.moveToNext()) {
        val detection = runSafely { AppJson.decodeFromString(Detection.serializer(), c.getString(4)) }.getOrDefault(Detection())
        val status = runSafely { Status.valueOf(c.getString(5)) }.getOrDefault(Status.IGNORED)
        writeHash(db, hashRecord(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), status, detection, c.getString(3).orEmpty()))
      }
    }
  }

  private fun writeHash(db: SQLiteDatabase, record: HashRecord) {
    db.insertWithOnConflict("hash_store", null, ContentValues().apply {
      put("log_id", record.logId)
      put("created", record.created)
      put("hash", record.hash)
      put("status", record.status.name)
      put("kind", record.kind.name)
      put("title_hash", record.titleHash)
      put("text_hash", record.textHash)
      put("day", record.day)
      put("time", record.time)
    }, SQLiteDatabase.CONFLICT_REPLACE)
  }

  // ---------------- Queue: messages captured instantly, analysed later in one go (battery saving) ----------------

  private val _queued = MutableStateFlow(0)
  /** Number of messages waiting for the next analysis. */
  val queued: StateFlow<Int> = _queued.asStateFlow()

  data class Queued(val id: Long, val captured: de.juliandigitalproducts.notitocal.model.Captured)

  @Synchronized
  fun enqueue(c: de.juliandigitalproducts.notitocal.model.Captured) {
    // A notification without text (e.g. the group summary of a messenger) has nothing to analyse.
    if (c.text.isBlank() && c.source != de.juliandigitalproducts.notitocal.model.SourceType.VOICE_MEMO) return
    val db = writableDatabase
    // The same text twice (e.g. a notification that was updated) is kept once.
    if (c.text.isNotBlank()) db.rawQuery("SELECT 1 FROM queue WHERE text = ? AND pkg = ? LIMIT 1", arrayOf(c.text, c.packageName)).use {
      if (it.moveToFirst()) return
    }
    db.insert("queue", null, ContentValues().apply {
      put("posted", c.postedAt); put("source", c.source.name); put("pkg", c.packageName); put("app", c.appLabel)
      put("sender", c.sender); put("text", c.text); put("grp", if (c.isGroup) 1 else 0)
    })
    refreshQueued()
  }

  @Synchronized
  fun queuedItems(): List<Queued> {
    val list = mutableListOf<Queued>()
    readableDatabase.rawQuery("SELECT id, posted, source, pkg, app, sender, text, grp FROM queue ORDER BY posted", null).use { c ->
      while (c.moveToNext()) list += Queued(
        c.getLong(0),
        de.juliandigitalproducts.notitocal.model.Captured(
          source = runSafely { SourceType.valueOf(c.getString(2)) }.getOrDefault(SourceType.NOTIFICATION),
          packageName = c.getString(3).orEmpty(), appLabel = c.getString(4).orEmpty(), sender = c.getString(5).orEmpty(),
          text = c.getString(6).orEmpty(), isGroup = c.getInt(7) == 1, postedAt = c.getLong(1),
        ),
      )
    }
    return list
  }

  @Synchronized
  fun dequeue(id: Long) {
    writableDatabase.delete("queue", "id = ?", arrayOf(id.toString()))
    refreshQueued()
  }

  @Synchronized
  fun refreshQueued() {
    readableDatabase.rawQuery("SELECT COUNT(*) FROM queue", null).use { if (it.moveToFirst()) _queued.value = it.getInt(0) }
  }

  @Synchronized
  fun reload(limit: Int = IN_MEMORY_LIMIT) {
    _entries.value = query("SELECT $COLUMNS FROM log ORDER BY created DESC LIMIT $limit")
  }

  /** One entry by id: from memory if loaded, otherwise straight from the database (also rows older than the UI list). */
  fun get(id: Long): LogEntry? =
    _entries.value.firstOrNull { it.id == id }
      ?: query("SELECT $COLUMNS FROM log WHERE id = ? LIMIT 1", arrayOf(id.toString())).firstOrNull()

  /**
   * A page of the complete history, newest first, optionally filtered by [search] (text, sender, app, title).
   * Used by the full-history screen so it is not limited to the [IN_MEMORY_LIMIT] newest entries.
   */
  fun queryHistory(search: String = "", limit: Int = 100, offset: Int = 0): List<LogEntry> {
    val q = search.trim()
    if (q.isEmpty()) return query("SELECT $COLUMNS FROM log ORDER BY created DESC LIMIT $limit OFFSET $offset")
    val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    return query(
      "SELECT $COLUMNS FROM log WHERE text LIKE ? ESCAPE '\\' OR sender LIKE ? ESCAPE '\\' OR app LIKE ? ESCAPE '\\' " +
        "OR detection LIKE ? ESCAPE '\\' ORDER BY created DESC LIMIT $limit OFFSET $offset",
      arrayOf(like, like, like, like),
    )
  }

  fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM log", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

  /** Every stored entry (export, statistics, bug report) – not capped like [entries]. */
  fun allEntries(): List<LogEntry> = query("SELECT $COLUMNS FROM log ORDER BY created DESC")

  /** Entries created at or after [since], newest first (duplicate check beyond the in-memory list). */
  fun entriesSince(since: Long, limit: Int = 2000): List<LogEntry> =
    query("SELECT $COLUMNS FROM log WHERE created >= ? ORDER BY created DESC LIMIT $limit", arrayOf(since.toString()))

  /** Stored messages of one conversation since [since], oldest first – rebuilds a chat thread after a process restart. */
  fun conversationSince(pkg: String, sender: String, since: Long): List<LogEntry> =
    query(
      "SELECT $COLUMNS FROM log WHERE pkg = ? AND sender = ? AND created >= ? AND text <> '' ORDER BY created ASC, id ASC LIMIT 50",
      arrayOf(pkg, sender, since.toString()),
    )

  fun getOutdatedEntries(targetVersion: Int = Detection.CURRENT_ALGORITHM_VERSION): List<LogEntry> {
    return _entries.value.filter { it.detection.algorithmVersion < targetVersion && it.text.isNotBlank() }
  }

  @Synchronized
  fun insert(
    source: SourceType, pkg: String, app: String, sender: String, text: String, d: Detection, status: Status, hash: String,
    fingerprintText: String = text,
    isGroup: Boolean = false,
  ): Long {
    val created = System.currentTimeMillis()
    val db = writableDatabase
    val id = db.insert("log", null, ContentValues().apply {
      put("created", created)
      put("source", source.name)
      put("pkg", pkg); put("app", app); put("sender", sender); put("text", text)
      put("grp", if (isGroup) 1 else 0)
      put("detection", AppJson.encodeToString(Detection.serializer(), d))
      put("status", status.name)
      put("hash", hash)
    })
    if (id > 0) writeHash(db, hashRecord(id, created, hash, status, d, fingerprintText))
    Log.i("NotiToCal", "log_insert id=$id source=${source.name}")
    if (id > 0) {
      // Prepend instead of reloading 300 rows (and decoding their JSON) on every insert.
      val entry = LogEntry(id, created, source, pkg, app, sender, text, d, status, "", isGroup)
      _entries.value = (listOf(entry) + _entries.value).take(IN_MEMORY_LIMIT)
    }
    return id
  }

  @Synchronized
  fun update(id: Long, d: Detection? = null, status: Status? = null, result: String? = null) {
    val db = writableDatabase
    db.update("log", ContentValues().apply {
      d?.let { put("detection", AppJson.encodeToString(Detection.serializer(), it)) }
      status?.let { put("status", it.name) }
      result?.let { put("result", it) }
    }, "id = ?", arrayOf(id.toString()))
    if (d != null || status != null) {
      db.update("hash_store", ContentValues().apply {
        status?.let { put("status", it.name) }
        d?.let {
          put("kind", it.kind.name)
          put("title_hash", HistoryPolicy.keyedHash(it.title, 4))
          put("day", HistoryPolicy.dayOf(it))
          put("time", HistoryPolicy.timeOf(it))
        }
      }, "log_id = ?", arrayOf(id.toString()))
    }
    // Only the changed row is re-read.
    val fresh = query("SELECT $COLUMNS FROM log WHERE id = ? LIMIT 1", arrayOf(id.toString())).firstOrNull()
    if (fresh != null && _entries.value.any { it.id == id }) {
      _entries.value = _entries.value.map { if (it.id == id) fresh else it }
    }
  }

  fun seenRecently(hash: String, withinMinutes: Int, text: String = ""): Boolean {
    val now = System.currentTimeMillis()
    val since = HistoryPolicy.since(withinMinutes, now) ?: return false
    return HashIndex(loadHash(since)).seenRecently(hash, withinMinutes, now, text)
  }

  /** Status of a log row, or of its fingerprint when immediate deletion already removed the row. */
  fun statusOf(id: Long): Status? {
    _entries.value.firstOrNull { it.id == id }?.let { return it.status }
    readableDatabase.rawQuery("SELECT status FROM log WHERE id = ?", arrayOf(id.toString())).use { c ->
      if (c.moveToFirst()) return runSafely { Status.valueOf(c.getString(0)) }.getOrNull()
    }
    readableDatabase.rawQuery("SELECT status FROM hash_store WHERE log_id = ?", arrayOf(id.toString())).use { c ->
      if (c.moveToFirst()) return runSafely { Status.valueOf(c.getString(0)) }.getOrNull()
    }
    return null
  }

  /**
   * An entry from the last [withinMinutes] that describes the same thing (same message, or same title on the same day
   * at the same time), no matter whether it was detected as event or task – so one message never ends up twice in the
   * calendar. Related entries at another time (end time, moved, unsure) come from [classify].
   */
  fun findDuplicate(d: Detection, text: String, withinMinutes: Int, sender: String = ""): LogEntry? {
    val now = System.currentTimeMillis()
    val since = HistoryPolicy.since(withinMinutes, now) ?: return null
    val day = HistoryPolicy.dayOf(d)
    val hit = HashIndex(loadHash(since)).findDuplicate(d.title, text, day, withinMinutes, now, HistoryPolicy.timeOf(d))
    if (hit != null) {
      return _entries.value.firstOrNull { it.id == hit.logId }
        ?: get(hit.logId)
        ?: LogEntry(
          id = hit.logId, created = hit.created, source = SourceType.MANUAL, packageName = "", appLabel = "", sender = "", text = "",
          detection = Detection(kind = hit.kind), status = hit.status, result = "",
        )
    }

    return classify(d, text, withinMinutes, sender)?.takeIf { it.second == DuplicateJudge.Verdict.SAME }?.first
  }

  /**
   * The captured entry of the last [withinMinutes] the new detection relates to, with [DuplicateJudge]'s verdict:
   * SAME before END_TIME before MOVED before UNCERTAIN. Null when nothing on that day is related.
   */
  fun classify(d: Detection, text: String, withinMinutes: Int, sender: String = ""): Pair<LogEntry, DuplicateJudge.Verdict>? {
    val now = System.currentTimeMillis()
    val since = HistoryPolicy.since(withinMinutes, now) ?: return null
    val day = HistoryPolicy.dayOf(d)
    if (day.isEmpty()) return null
    val candidates = if (_entries.value.lastOrNull()?.let { it.created <= since } == true) _entries.value else entriesSince(since)
    val judged = candidates.asSequence()
      .filter { it.created >= since && it.status in HistoryPolicy.duplicateStatuses && it.detection.kind in HistoryPolicy.duplicateKinds }
      .filter { HistoryPolicy.dayOf(it.detection) == day }
      .map { it to DuplicateJudge.judge(d, text, sender, it.detection, it.text, it.sender) }
      .filter { it.second != DuplicateJudge.Verdict.DIFFERENT }
      .toList()
    return judged.minByOrNull { it.second.ordinal }
  }

  private fun loadHash(since: Long): List<HashRecord> {
    val list = mutableListOf<HashRecord>()
    readableDatabase.rawQuery(
      "SELECT log_id, created, hash, status, kind, title_hash, text_hash, day, time FROM hash_store WHERE created >= ? ORDER BY created DESC",
      arrayOf(since.toString()),
    ).use { c ->
      while (c.moveToNext()) {
        list += HashRecord(
          logId = c.getLong(0),
          created = c.getLong(1),
          hash = c.getString(2).orEmpty(),
          status = runSafely { Status.valueOf(c.getString(3)) }.getOrDefault(Status.IGNORED),
          kind = runSafely { Kind.valueOf(c.getString(4)) }.getOrDefault(Kind.NONE),
          titleHash = c.getString(5).orEmpty(),
          textHash = c.getString(6).orEmpty(),
          day = c.getString(7).orEmpty(),
          time = c.getString(8).orEmpty(),
        )
      }
    }
    return list
  }

  @Synchronized
  fun delete(id: Long) {
    val db = writableDatabase
    db.delete("log", "id = ?", arrayOf(id.toString()))
    db.delete("hash_store", "log_id = ?", arrayOf(id.toString()))
    _entries.value = _entries.value.filterNot { it.id == id }
    sweepScreenshots()
  }

  /** Removes analysed messages. The queue and the duplicate-hash store stay. */
  @Synchronized
  fun clearHistory() {
    writableDatabase.delete("log", null, null)
    reload()
    sweepScreenshots()
  }

  /** Removes log rows in [HistoryPolicy.deletableAfterProcessing]. Fingerprints stay so duplicates are still caught. */
  @Synchronized
  fun dropFinalEntries(enabled: Boolean) {
    if (!enabled) return
    val states = HistoryPolicy.deletableAfterProcessing.map { it.name }
    val changed = writableDatabase.delete("log", "status = ? OR status = ?", states.toTypedArray())
    if (changed > 0) {
      reload()
      sweepScreenshots()
    }
  }

  @Synchronized
  fun clear() {
    val db = writableDatabase
    db.delete("log", null, null)
    db.delete("queue", null, null)
    db.delete("hash_store", null, null)
    refreshQueued()
    reload()
    sweepScreenshots(deleteAll = true)
  }

  @Synchronized
  fun prune(keepDays: Int) {
    val before = HistoryPolicy.retentionCutoff(keepDays, System.currentTimeMillis()) ?: return
    val db = writableDatabase
    val cutoff = before.toString()
    db.delete("log", "created < ?", arrayOf(cutoff))
    db.delete("hash_store", "created < ?", arrayOf(cutoff))
    reload()
    sweepScreenshots()
  }

  /**
   * Deletes stored screenshots whose log row no longer exists (also orphans left by older versions), or all of them
   * when [deleteAll] is set. Runs on a background thread.
   */
  fun sweepScreenshots(deleteAll: Boolean = false) {
    val dir = java.io.File(appContext.filesDir, SCREENSHOT_DIR)
    if (!dir.isDirectory) return
    fileCleanup.execute {
      runSafely {
        val files = dir.listFiles().orEmpty()
        if (files.isEmpty()) return@runSafely
        val keep: Set<Long> = if (deleteAll) emptySet() else {
          val ids = HashSet<Long>()
          readableDatabase.rawQuery("SELECT id FROM log", null).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
          ids
        }
        files.forEach { f ->
          val id = screenshotId(f.name)
          if (deleteAll || id == null || id !in keep) f.delete()
        }
      }
    }
  }
}

const val SCREENSHOT_DIR = "screenshots"

/** Log id encoded in a stored screenshot file name ("screenshot_<id>.jpg"), or null for foreign files. */
fun screenshotId(fileName: String): Long? =
  Regex("""^screenshot_(\d+)\.jpg$""").matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull()
