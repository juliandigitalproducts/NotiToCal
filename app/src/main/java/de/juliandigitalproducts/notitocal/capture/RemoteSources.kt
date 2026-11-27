// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.capture

import androidx.core.net.toUri
import androidx.core.content.edit
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.NotiToCalApp
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.FilterListMode
import de.juliandigitalproducts.notitocal.data.ImapAccount
import de.juliandigitalproducts.notitocal.data.SecretKeys
import de.juliandigitalproducts.notitocal.data.TelegramDialogItem
import de.juliandigitalproducts.notitocal.data.TelegramDialogKind
import de.juliandigitalproducts.notitocal.diagnostics.DiagnosticLogger
import de.juliandigitalproducts.notitocal.model.Captured
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.net.Http
import de.juliandigitalproducts.notitocal.net.InsecureUrlException
import de.juliandigitalproducts.notitocal.net.LanHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Strictly read-only IMAP client (uses EXAMINE and BODY.PEEK only — never SELECT, STORE or EXPUNGE).
 * Supports multiple IMAP accounts simultaneously.
 */
object ImapClient {
  private const val TIMEOUT_MS = 12_000

  data class CheckResult(val ok: Boolean, val detail: String, val messageCount: Int = 0)

  private fun quoteImap(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "") + "\""

  suspend fun testAccount(account: ImapAccount, password: String): CheckResult = withContext(Dispatchers.IO) {
    val host = account.host.trim()
    val user = account.username.trim()
    val folder = account.folder.trim().ifBlank { "INBOX" }
    if (host.isBlank()) return@withContext CheckResult(false, "Host missing")
    if (user.isBlank()) return@withContext CheckResult(false, "Username missing")
    if (password.isBlank()) return@withContext CheckResult(false, "Password missing")
    if (!account.ssl && !LanHosts.isLocal(host)) {
      return@withContext CheckResult(false, InsecureUrlException(host).message ?: "SSL/TLS required")
    }

    runSafely {
      openSocket(host, account.port.coerceIn(1, 65535), account.ssl).use { socket ->
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val greeting = reader.readLine().orEmpty()
        if (!greeting.startsWith("* OK", ignoreCase = true)) {
          return@use CheckResult(false, greeting.take(120).ifBlank { "No IMAP greeting" })
        }
        sendCommand(writer, "A1 LOGIN ${quoteImap(user)} ${quoteImap(password)}")
        val loginRes = readTagged(reader, "A1")
        if (!loginRes.ok) {
          runSafely { sendCommand(writer, "A9 LOGOUT") }
          return@use CheckResult(false, "Login failed: ${loginRes.lastLine.take(100)}")
        }
        // EXAMINE is strictly read-only according to RFC 3501 §6.3.2
        sendCommand(writer, "A2 EXAMINE ${quoteImap(folder)}")
        val examineRes = readTagged(reader, "A2")
        runSafely { sendCommand(writer, "A3 LOGOUT") }
        if (!examineRes.ok) {
          return@use CheckResult(false, "Folder '$folder': ${examineRes.lastLine.take(100)}")
        }
        val exists = examineRes.lines.firstNotNullOfOrNull { line ->
          Regex("""\*\s+(\d+)\s+EXISTS""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 0
        CheckResult(true, "$folder ($exists, read-only)", exists)
      }
    }.getOrElse { e ->
      CheckResult(false, e.message?.take(120) ?: e.javaClass.simpleName)
    }
  }

  /** Polls recent messages read-only via EXAMINE and BODY.PEEK from all enabled accounts. */
  suspend fun pollEnabledAccounts(app: NotiToCalApp, maxPerAccount: Int = 3): Int = withContext(Dispatchers.IO) {
    val s = app.settings.current
    if (!s.imapEnabled || s.paused) return@withContext 0
    val prefs = app.getSharedPreferences("imap_state", Context.MODE_PRIVATE)
    var imported = 0
    for (acc in s.imapAccounts.filter { it.enabled && it.host.isNotBlank() && it.username.isNotBlank() }) {
      val pwd = app.secrets.get(SecretKeys.imap(acc.id))
      if (pwd.isBlank()) continue
      val messages = runSafely { fetchRecentReadOnly(acc, pwd, maxPerAccount) }.getOrDefault(emptyList())
      val seenKey = "seen_list_${acc.id}"
      // Oldest first, newest last; trimming keeps the newest UIDs (a Set has no order and could drop recent ones).
      val seen = ArrayDeque(
        prefs.getString(seenKey, null)?.split(',')?.filter { it.isNotBlank() }
          ?: prefs.getStringSet("seen_${acc.id}", emptySet()).orEmpty().toList(),
      )
      for (msg in messages) {
        if (msg.uid in seen) continue
        seen.addLast(msg.uid)
        while (seen.size > 300) seen.removeFirst()
        // Stored before the analysis: a failed analysis must not bring the mail back on every poll.
        prefs.edit { putString(seenKey, seen.joinToString(",")).remove("seen_${acc.id}") }
        val text = listOf(msg.subject, msg.body).filter { it.isNotBlank() }.joinToString("\n").trim()
        if (text.isNotBlank() && processSafely(app, Captured(source = SourceType.IMAP, appLabel = acc.label.ifBlank { acc.host }, sender = msg.from, text = text))) {
          imported++
        }
      }
    }
    imported
  }

  data class ImapMessage(val uid: String, val from: String, val subject: String, val body: String)

  private fun fetchRecentReadOnly(account: ImapAccount, password: String, limit: Int): List<ImapMessage> {
    val host = account.host.trim()
    if (!account.ssl && !LanHosts.isLocal(host)) return emptyList()
    val folder = account.folder.trim().ifBlank { "INBOX" }
    openSocket(host, account.port.coerceIn(1, 65535), account.ssl).use { socket ->
      val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
      val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
      if (!reader.readLine().orEmpty().startsWith("* OK", ignoreCase = true)) return emptyList()
      sendCommand(writer, "A1 LOGIN ${quoteImap(account.username.trim())} ${quoteImap(password)}")
      if (!readTagged(reader, "A1").ok) return emptyList()
      sendCommand(writer, "A2 EXAMINE ${quoteImap(folder)}")
      val ex = readTagged(reader, "A2")
      if (!ex.ok) {
        runSafely { sendCommand(writer, "A9 LOGOUT") }
        return emptyList()
      }
      val total = ex.lines.firstNotNullOfOrNull { line ->
        Regex("""\*\s+(\d+)\s+EXISTS""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
      } ?: 0
      val out = mutableListOf<ImapMessage>()
      if (total > 0) {
        val startSeq = (total - limit + 1).coerceAtLeast(1)
        for (seq in startSeq..total) {
          val tag = "F$seq"
          // BODY.PEEK never alters message flags
          sendCommand(writer, "$tag FETCH $seq (UID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] BODY.PEEK[TEXT]<0.16384>)")
          val fetch = readTagged(reader, tag, maxLines = 4000)
          if (fetch.ok) {
            parseFetch(fetch.lines)?.let { out += it }
          }
        }
      }
      runSafely { sendCommand(writer, "A9 LOGOUT") }
      return out
    }
  }

  private data class TaggedResponse(val ok: Boolean, val lines: List<String>, val lastLine: String)

  private fun sendCommand(writer: BufferedWriter, cmd: String) {
    writer.write(cmd)
    writer.write("\r\n")
    writer.flush()
  }

  /**
   * Splits one FETCH response into UID, header block and body. The two literals arrive in the requested order;
   * the body literal ends with the closing ")" of the FETCH line.
   */
  internal fun parseFetch(lines: List<String>): ImapMessage? {
    val raw = lines.dropLast(1).joinToString("\n")
    val uid = Regex("""UID\s+(\d+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1) ?: return null
    val hIdx = raw.indexOf("BODY[HEADER.FIELDS", ignoreCase = true)
    val bIdx = raw.indexOf("BODY[TEXT]", ignoreCase = true)
    fun literal(start: Int, end: Int): String {
      if (start < 0) return ""
      val from = raw.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: return ""
      return raw.substring(minOf(from, raw.length), maxOf(minOf(end, raw.length), minOf(from, raw.length)))
    }
    val header = literal(hIdx, if (bIdx > hIdx) raw.lastIndexOf('\n', bIdx) else raw.length)
    val bodyRaw = literal(bIdx, if (hIdx > bIdx) raw.lastIndexOf('\n', hIdx) else raw.length).trimEnd().removeSuffix(")")
    val h = MailText.headers(header)
    val from = MailText.decodeHeader(h["from"].orEmpty())
    val subject = MailText.decodeHeader(h["subject"].orEmpty())
    val body = MailText.bodyText(header, bodyRaw)
    if (subject.isBlank() && body.isBlank()) return null
    return ImapMessage(uid = uid, from = from.take(80), subject = subject.take(160), body = body.take(1500))
  }

  private fun readTagged(reader: BufferedReader, tag: String, maxLines: Int = 500): TaggedResponse {
    val lines = mutableListOf<String>()
    while (true) {
      val line = reader.readLine() ?: return TaggedResponse(false, lines, "Connection closed")
      lines += line
      if (line.startsWith("$tag ", ignoreCase = true)) {
        val rest = line.substring(tag.length).trim()
        return TaggedResponse(rest.startsWith("OK", ignoreCase = true), lines, rest)
      }
      if (lines.size > maxLines) return TaggedResponse(false, lines, "Response too long")
    }
  }

  private fun openSocket(host: String, port: Int, ssl: Boolean): Socket {
    return if (ssl) {
      val raw = Socket()
      raw.soTimeout = TIMEOUT_MS
      raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
      (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true).apply {
        soTimeout = TIMEOUT_MS
        (this as? SSLSocket)?.let { sslSocket ->
          val params = sslSocket.sslParameters
          params.endpointIdentificationAlgorithm = "HTTPS"
          sslSocket.sslParameters = params
          sslSocket.startHandshake()
        }
      }
    } else {
      Socket().apply {
        soTimeout = TIMEOUT_MS
        connect(InetSocketAddress(host, port), TIMEOUT_MS)
      }
    }
  }
}

/**
 * Telegram Bot integration (read-only via getMe / getUpdates).
 */
object TelegramSource {
  data class CheckResult(
    val ok: Boolean,
    val detail: String,
    val discoveredChatId: String? = null,
    val discoveredDialogs: List<TelegramDialogItem> = emptyList(),
  )

  suspend fun fetchChatById(token: String, chatId: String): TelegramDialogItem? {
    val cleanToken = token.trim()
    val cleanId = chatId.trim().removePrefix("tgchat_")
    if (cleanToken.isBlank() || cleanId.isBlank()) return null
    return runSafely {
      val raw = Http.get("https://api.telegram.org/bot$cleanToken/getChat?chat_id=$cleanId", timeoutMs = 10_000)
      val obj = AppJson.parseToJsonElement(raw).jsonObject
      if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) {
        TelegramUserApiSource.dialogFromBotChat(obj["result"]?.jsonObject)
      } else null
    }.getOrNull()
  }

  suspend fun testConnection(token: String, configuredChatId: String): CheckResult {
    val cleanToken = token.trim()
    if (cleanToken.isBlank()) return CheckResult(false, "Bot token missing")
    return runSafely {
      val meRaw = Http.get("https://api.telegram.org/bot$cleanToken/getMe", timeoutMs = 12_000)
      val meObj = AppJson.parseToJsonElement(meRaw).jsonObject
      if (meObj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
        return@runSafely CheckResult(false, meObj["description"]?.jsonPrimitive?.contentOrNull ?: "Invalid token")
      }
      val result = meObj["result"]?.jsonObject
      val username = result?.get("username")?.jsonPrimitive?.contentOrNull.orEmpty()
      val firstName = result?.get("first_name")?.jsonPrimitive?.contentOrNull.orEmpty()
      val updatesRaw = runSafely { Http.get("https://api.telegram.org/bot$cleanToken/getUpdates?limit=50&timeout=0", timeoutMs = 10_000) }.getOrNull()
      val updates = updatesRaw?.let { runSafely { AppJson.parseToJsonElement(it).jsonObject["result"]?.jsonArray }.getOrNull() }
      val dialogs = mutableListOf<TelegramDialogItem>()
      updates?.forEach { u ->
        TelegramUserApiSource.dialogsFromBotUpdate(u.jsonObject).forEach { dialogs += it }
      }
      if (configuredChatId.isNotBlank()) {
        configuredChatId.split(',', ';', ' ').map { it.trim() }.filter { it.isNotBlank() }.forEach { cid ->
          fetchChatById(cleanToken, cid)?.let { dialogs += it }
        }
      }
      val firstChatId = updates?.mapNotNull { u ->
        val o = u.jsonObject
        val msg = o["message"]?.jsonObject ?: o["channel_post"]?.jsonObject ?: o["my_chat_member"]?.jsonObject
        msg?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
      }?.lastOrNull()
      val chatInfo = when {
        configuredChatId.isNotBlank() -> "chat_id=$configuredChatId"
        firstChatId != null -> "chat_id=$firstChatId"
        else -> "✓"
      }
      CheckResult(true, "@${username.ifBlank { firstName }} ($chatInfo)", firstChatId, dialogs)
    }.getOrElse { e ->
      CheckResult(false, e.message?.take(120) ?: e.javaClass.simpleName)
    }
  }

  suspend fun pollUpdates(app: NotiToCalApp): Int {
    val s = app.settings.current
    if (!s.telegramEnabled || s.paused) return 0
    val token = app.secrets.get(SecretKeys.TELEGRAM_BOT).trim()
    if (token.isBlank()) return 0
    val prefs = app.getSharedPreferences("telegram_state", Context.MODE_PRIVATE)
    val offset = prefs.getLong("last_update_id", 0L)
    val url = "https://api.telegram.org/bot$token/getUpdates?limit=25&timeout=0" + if (offset > 0) "&offset=${offset + 1}" else ""
    return runSafely {
      val raw = Http.get(url, timeoutMs = 12_000)
      val arr = AppJson.parseToJsonElement(raw).jsonObject["result"]?.jsonArray ?: return@runSafely 0
      var maxId = offset
      var count = 0
      val allowedChat = s.telegramChatId.trim()
      val learned = mutableListOf<TelegramDialogItem>()
      for (el in arr) {
        val obj = el.jsonObject
        val updateId = obj["update_id"]?.jsonPrimitive?.longOrNull ?: continue
        if (updateId > maxId) {
          maxId = updateId
          // Progress is stored per update: an AI timeout further down must not bring these messages back next time.
          prefs.edit { putLong("last_update_id", maxId) }
        }
        // Always learn all seen groups and persons before any filter skips the message.
        learned += TelegramUserApiSource.dialogsFromBotUpdate(obj)
        val msg = obj["message"]?.jsonObject ?: obj["channel_post"]?.jsonObject ?: continue
        val dateSec = msg["date"]?.jsonPrimitive?.longOrNull
        if (dateSec != null && s.telegramTimeWindowHours > 0) {
          val cutoff = System.currentTimeMillis() - s.telegramTimeWindowHours * 3600_000L
          if (dateSec * 1000L < cutoff) continue
        }
        val chatId = msg["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
        // If no dialog list is used yet, respect a manually configured single Chat-ID;
        // once dialogs are managed in the checklist, shouldProcessTelegramMessage controls inclusion/exclusion.
        if (s.telegramDialogs.isEmpty() && allowedChat.isNotBlank() && chatId != allowedChat) continue
        val text = (msg["text"]?.jsonPrimitive?.contentOrNull ?: msg["caption"]?.jsonPrimitive?.contentOrNull).orEmpty().trim()
        if (text.isBlank()) continue
        val fromObj = msg["from"]?.jsonObject
        val person = listOfNotNull(
          fromObj?.get("first_name")?.jsonPrimitive?.contentOrNull,
          fromObj?.get("last_name")?.jsonPrimitive?.contentOrNull,
        ).joinToString(" ").ifBlank { fromObj?.get("username")?.jsonPrimitive?.contentOrNull ?: "Telegram" }
        val chatItem = TelegramUserApiSource.dialogFromBotChat(msg["chat"]?.jsonObject)
        val isGroup = chatItem != null && chatItem.kind != TelegramDialogKind.PERSON
        val sender = if (isGroup && chatItem != null && chatItem.title != person) "$person @ ${chatItem.title}" else person
        if (!TelegramUserApiSource.shouldProcessTelegramMessage(app.settings.current, sender, isGroup, chatId = chatId)) continue
        if (processSafely(app, Captured(source = SourceType.TELEGRAM, appLabel = "Telegram Bot", sender = sender, text = text, isGroup = isGroup))) count++
      }
      if (learned.isNotEmpty()) TelegramUserApiSource.mergeIntoSettings(app, learned)
      if (maxId > offset) prefs.edit { putLong("last_update_id", maxId) }
      count
    }.getOrDefault(0)
  }
}

/**
 * Share-Sheet & Clipboard import source helper and self-test.
 */
object ShareAndClipboardSource {
  data class CheckResult(val ok: Boolean, val detail: String)

  fun readClipboard(context: Context): String? = runSafely {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    item?.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotBlank() }
  }.getOrNull()

  fun testSource(context: Context): CheckResult = runSafely {
    val pm = context.packageManager
    val shareComp = ComponentName(context, ShareActivity::class.java)
    val info = pm.getActivityInfo(shareComp, 0)
    val sample = RuleEngine.analyze("Treffen morgen um 15:00 Uhr im Café Central", "Teilen-Test")
    val ok = info.enabled && sample.kind == Kind.EVENT
    CheckResult(ok, if (ok) context.getString(R.string.src_share_active, sample.title) else context.getString(R.string.src_share_inactive))
  }.getOrElse { e ->
    CheckResult(false, e.message ?: e.javaClass.simpleName)
  }
}

/**
 * Telegram User-API (Client API / MTProto / Bridge + Invisible Dialog & Flood Filter).
 * Unlike a Telegram Bot, User-API access is invisible in private/local groups and allows
 * explicit selection/deselection of groups and persons ("All except" vs. "Only following")
 * as well as rate-limiting high-volume groups (e.g. > 30 msgs/min).
 */
object TelegramUserApiSource {
  val TELEGRAM_PACKAGES = setOf(
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.telegram.messenger.beta",
    "org.telegram.plus",
    "tw.nekomimi.nekogram",
    "org.thunderdog.challegram",
    "ir.ilmili.telegraph",
    "com.radolyn.ayugram",
    "uz.unnarsx.cherrygram",
    "it.octogram.android",
  )

  private val chatTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()
  @Volatile var lastPhoneCodeHash: String? = null

  data class CheckResult(val ok: Boolean, val detail: String)

  fun isValidApiId(apiId: String): Boolean = apiId.trim().let { it.length in 4..12 && it.all(Char::isDigit) }
  fun isValidApiHash(apiHash: String): Boolean = apiHash.trim().matches(Regex("^[0-9a-fA-F]{16,64}$"))

  /**
   * Sliding-window flood limiter per group/chat (60 seconds).
   * Returns true if the chat exceeds [maxPerMinute] messages within the last 60 seconds.
   */
  fun isRateLimited(chatKey: String, maxPerMinute: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (maxPerMinute <= 0) return false
    val key = chatKey.trim().lowercase().ifBlank { "default" }
    val deque = chatTimestamps.getOrPut(key) { ArrayDeque() }
    synchronized(deque) {
      val cutoff = nowMs - 60_000L
      while (deque.isNotEmpty() && deque.first() < cutoff) {
        deque.removeFirst()
      }
      if (deque.size >= maxPerMinute) {
        return true
      }
      deque.addLast(nowMs)
      return false
    }
  }

  fun resetRateLimits() {
    chatTimestamps.clear()
  }

  /**
   * Evaluates whether a message from [sender] / [chatTitle] passes the explicit
   * group & person filter ([FilterListMode.ALL_EXCEPT] vs. [FilterListMode.ONLY_SELECTED]).
   *
   * - In [FilterListMode.ALL_EXCEPT] ("Alle außer"): all groups/persons are allowed EXCEPT those where `selected == true`.
   * - In [FilterListMode.ONLY_SELECTED] ("Nur folgende"): ONLY groups/persons where `selected == true` are allowed.
   */
  fun isAllowedByFilter(
    sender: String,
    chatTitle: String,
    mode: FilterListMode,
    dialogs: List<TelegramDialogItem>,
    chatId: String = "",
  ): Boolean {
    val activeItems = dialogs.filter { it.selected && (it.title.isNotBlank() || it.username.isNotBlank() || it.id.isNotBlank()) }
    if (activeItems.isEmpty()) {
      return mode == FilterListMode.ALL_EXCEPT
    }
    val combined = "$sender $chatTitle".lowercase()
    val normChatId = chatId.trim().removePrefix("tgchat_").lowercase()
    val matchesSelection = activeItems.any { item ->
      val t = item.title.trim().lowercase()
      val u = item.username.trim().removePrefix("@").lowercase()
      val id = item.id.trim().removePrefix("tgchat_").lowercase()
      (t.isNotBlank() && combined.contains(t)) ||
        (u.isNotBlank() && combined.contains(u)) ||
        (id.isNotBlank() && (
          sender.equals(id, ignoreCase = true) ||
            chatTitle.equals(id, ignoreCase = true) ||
            (normChatId.isNotBlank() && normChatId == id)
          ))
    }
    return when (mode) {
      FilterListMode.ALL_EXCEPT -> !matchesSelection
      FilterListMode.ONLY_SELECTED -> matchesSelection
    }
  }

  /**
   * Combined check for incoming Telegram messages (both Client-API, Bot-API, and device notifications).
   */
  fun shouldProcessTelegramMessage(
    s: AppSettings,
    sender: String,
    isGroup: Boolean,
    nowMs: Long = System.currentTimeMillis(),
    chatId: String = "",
  ): Boolean {
    val chatTitle = if (sender.contains(" @ ")) sender.substringAfter(" @ ").trim() else sender.trim()
    if (s.telegramRateLimitEnabled && isGroup) {
      if (isRateLimited(chatTitle, s.telegramMaxMsgsPerMinute, nowMs)) return false
    }
    if (s.telegramDialogs.isNotEmpty() || s.telegramFilterMode == FilterListMode.ONLY_SELECTED) {
      if (!isAllowedByFilter(sender, chatTitle, s.telegramFilterMode, s.telegramDialogs, chatId = chatId)) return false
    }
    return true
  }

  /**
   * Learns seen Telegram groups and contacts so the user can select/deselect them with 1 tap.
   */
  fun recordSeenDialog(app: NotiToCalApp, rawSender: String, isGroup: Boolean) {
    val items = dialogsFromSender(rawSender, isGroup)
    if (items.isEmpty()) return
    val s = app.settings.current
    if (items.all { item -> s.telegramDialogs.any { sameDialog(it, item) } }) return
    mergeIntoSettings(app, items)
  }

  /** Builds the primary list item from a notification / history sender ("Person @ Gruppe" or "Person"). */
  fun dialogFromSender(rawSender: String, isGroup: Boolean): TelegramDialogItem? {
    val raw = rawSender.trim()
    val grp = isGroup || raw.contains(" @ ")
    val title = if (raw.contains(" @ ")) raw.substringAfter(" @ ").trim() else raw
    if (title.length < 2 || title.equals("Telegram", ignoreCase = true) || title.equals("Telegram Bot", ignoreCase = true)) return null
    return TelegramDialogItem(
      id = "tg_${title.lowercase().hashCode()}",
      title = title,
      kind = if (grp) TelegramDialogKind.GROUP else TelegramDialogKind.PERSON,
      selected = false,
    )
  }

  /** Extracts both the group item and (for "Person @ Gruppe") the sender person item. */
  fun dialogsFromSender(rawSender: String, isGroup: Boolean): List<TelegramDialogItem> {
    val raw = rawSender.trim()
    if (raw.contains(" @ ")) {
      val personName = raw.substringBefore(" @ ").trim()
      val groupName = raw.substringAfter(" @ ").trim()
      return buildList {
        dialogFromSender(groupName, isGroup = true)?.let { add(it) }
        if (personName.isNotBlank() && !personName.equals(groupName, ignoreCase = true)) {
          dialogFromSender(personName, isGroup = false)?.let { add(it) }
        }
      }
    }
    return listOfNotNull(dialogFromSender(raw, isGroup))
  }

  /** Extracts both chat (group/channel/private) and sender person from a Bot API Update JSON object. */
  fun dialogsFromBotUpdate(updateObj: JsonObject): List<TelegramDialogItem> {
    val msg = updateObj["message"]?.jsonObject
      ?: updateObj["edited_message"]?.jsonObject
      ?: updateObj["channel_post"]?.jsonObject
      ?: updateObj["edited_channel_post"]?.jsonObject
      ?: updateObj["my_chat_member"]?.jsonObject
      ?: updateObj["chat_member"]?.jsonObject
      ?: return emptyList()
    val result = mutableListOf<TelegramDialogItem>()
    dialogFromBotChat(msg["chat"]?.jsonObject)?.let { result += it }
    val fromObj = msg["from"]?.jsonObject
    if (fromObj != null && fromObj["is_bot"]?.jsonPrimitive?.booleanOrNull != true) {
      val personName = listOfNotNull(
        fromObj["first_name"]?.jsonPrimitive?.contentOrNull,
        fromObj["last_name"]?.jsonPrimitive?.contentOrNull,
      ).joinToString(" ").trim().ifBlank {
        fromObj["username"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
      }
      val username = fromObj["username"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
      val fromId = fromObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
      if (personName.length >= 2 && result.none { it.title.equals(personName, ignoreCase = true) }) {
        result += TelegramDialogItem(
          id = if (fromId.isNotBlank()) "tguser_$fromId" else "tg_${personName.lowercase().hashCode()}",
          title = personName,
          username = username,
          kind = TelegramDialogKind.PERSON,
          selected = false,
        )
      }
    }
    return result
  }

  /** Builds a list item from a Bot API `chat` object (private / group / supergroup / channel). */
  fun dialogFromBotChat(chat: JsonObject?): TelegramDialogItem? {
    if (chat == null) return null
    val id = chat["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val type = chat["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val username = chat["username"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val title = chat["title"]?.jsonPrimitive?.contentOrNull
      ?: listOfNotNull(
        chat["first_name"]?.jsonPrimitive?.contentOrNull,
        chat["last_name"]?.jsonPrimitive?.contentOrNull,
      ).joinToString(" ").ifBlank { username }
    if (title.isBlank()) return null
    return TelegramDialogItem(
      id = if (id.isNotBlank()) "tgchat_$id" else "tg_${title.lowercase().hashCode()}",
      title = title,
      username = username,
      kind = when (type) {
        "private" -> TelegramDialogKind.PERSON
        "channel" -> TelegramDialogKind.CHANNEL
        else -> TelegramDialogKind.GROUP
      },
      selected = false,
    )
  }

  private fun sameDialog(a: TelegramDialogItem, b: TelegramDialogItem): Boolean =
    a.id == b.id || a.title.trim().equals(b.title.trim(), ignoreCase = true)

  /** Adds new items to [existing] without touching the user's selection of known entries. */
  fun merge(existing: List<TelegramDialogItem>, found: List<TelegramDialogItem>): List<TelegramDialogItem> {
    val out = existing.toMutableList()
    for (item in found) {
      val idx = out.indexOfFirst { sameDialog(it, item) }
      if (idx < 0) {
        if (out.size < MAX_DIALOGS) out += item
      } else {
        // Keep the selection; enrich missing username / better kind information.
        val old = out[idx]
        out[idx] = old.copy(
          username = old.username.ifBlank { item.username },
          kind = if (old.kind == TelegramDialogKind.PERSON && item.kind != TelegramDialogKind.PERSON) item.kind else old.kind,
        )
      }
    }
    return out
  }

  fun mergeIntoSettings(app: NotiToCalApp, found: List<TelegramDialogItem>) {
    if (found.isEmpty()) return
    app.scope.launch {
      app.settings.update { st ->
        val merged = merge(st.telegramDialogs, found)
        if (merged == st.telegramDialogs) st else st.copy(telegramDialogs = merged)
      }
    }
  }

  private const val MAX_DIALOGS = 500

  /**
   * Parses and imports chats from allowlist.json or formatted text lines.
   * Supports:
   * 1. JSON Map: {"-1001234567890": "Projekt-Team"}
   * 2. JSON Array: [{"id": "-1001234567890", "title": "Projekt-Team"}]
   * 3. Text lines: "Projekt-Team -1001234567890" or "-1001234567890 Projekt-Team"
   */
  fun importFromAllowlist(rawInput: String): List<TelegramDialogItem> {
    val clean = rawInput.trim()
    if (clean.isBlank()) return emptyList()
    val results = mutableListOf<TelegramDialogItem>()
    // 1. JSON Object / Map
    if (clean.startsWith("{") && clean.endsWith("}")) {
      runSafely {
        val root = AppJson.parseToJsonElement(clean).jsonObject
        for ((key, value) in root) {
          val title = value.jsonPrimitive.contentOrNull.orEmpty().ifBlank { key }
          val idClean = key.trim().removePrefix("tgchat_")
          val isGrp = idClean.startsWith("-") || !title.contains("direkt", ignoreCase = true)
          results += TelegramDialogItem(
            id = "tgchat_$idClean",
            title = title,
            kind = if (isGrp) TelegramDialogKind.GROUP else TelegramDialogKind.PERSON,
            selected = true,
          )
        }
      }
    } else if (clean.startsWith("[") && clean.endsWith("]")) {
      // 2. JSON Array
      runSafely {
        val arr = AppJson.parseToJsonElement(clean).jsonArray
        for (el in arr) {
          val obj = el.jsonObject
          val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty().removePrefix("tgchat_")
          val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
          val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
          if (title.isNotBlank() || id.isNotBlank()) {
            results += TelegramDialogItem(
              id = if (id.isNotBlank()) "tgchat_$id" else "tg_${title.hashCode()}",
              title = title.ifBlank { id },
              username = obj["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
              kind = if (type.contains("person", true) || type.contains("user", true) || !id.startsWith("-")) TelegramDialogKind.PERSON else TelegramDialogKind.GROUP,
              selected = true,
            )
          }
        }
      }
    }
    // 3. Fallback: Line-based parsing
    if (results.isEmpty()) {
      val lines = clean.lines().map { it.trim() }.filter { it.isNotBlank() }
      for (line in lines) {
        val tokens = line.split(Regex("""[\t,;|]""")).map { it.trim() }.filter { it.isNotBlank() }
        if (tokens.size >= 2) {
          val (first, second) = tokens[0] to tokens[1]
          val id = if (first.matches(Regex("""^-?\d{5,}$"""))) first else if (second.matches(Regex("""^-?\d{5,}$"""))) second else ""
          val title = if (id == first) second else first
          if (title.isNotBlank()) {
            val isGrp = id.startsWith("-") || !title.contains("direkt", ignoreCase = true)
            results += TelegramDialogItem(
              id = if (id.isNotBlank()) "tgchat_$id" else "tg_${title.hashCode()}",
              title = title,
              kind = if (isGrp) TelegramDialogKind.GROUP else TelegramDialogKind.PERSON,
              selected = true,
            )
          }
        } else {
          val idMatch = Regex("""(-?\d{6,})""").find(line)
          val id = idMatch?.groupValues?.getOrNull(1).orEmpty()
          val title = line.replace(id, "").trim().removeSuffix("-").removeSuffix(":").trim().ifBlank { id }
          if (title.isNotBlank()) {
            results += TelegramDialogItem(
              id = if (id.isNotBlank()) "tgchat_$id" else "tg_${title.hashCode()}",
              title = title,
              kind = if (id.startsWith("-")) TelegramDialogKind.GROUP else TelegramDialogKind.PERSON,
              selected = true,
            )
          }
        }
      }
    }
    DiagnosticLogger.log("TelegramImport", "Aus allowlist/Text importiert: ${results.size} Chats")
    return results
  }

  data class BridgeCheckResult(
    val ok: Boolean,
    val detail: String,
    val latencyMs: Long = 0L,
    val isLocalhostWarning: Boolean = false,
    val user: String? = null,
  )

  fun bridgeHeaders(token: String): Map<String, String> {
    val clean = token.trim()
    return if (clean.isNotBlank()) mapOf("Authorization" to "Bearer $clean") else emptyMap()
  }

  /**
   * Pings the Telethon bridge server to verify connectivity and authorization.
   */
  suspend fun testBridgeConnection(context: Context, bridgeUrl: String, token: String = ""): BridgeCheckResult = withContext(Dispatchers.IO) {
    val clean = bridgeUrl.trim().trimEnd('/')
    if (clean.isBlank()) return@withContext BridgeCheckResult(ok = false, detail = context.getString(R.string.tg_sync_no_bridge))
    if (token.isBlank()) return@withContext BridgeCheckResult(ok = false, detail = context.getString(R.string.telegram_bridge_token_missing_error))
    val isLocalhost = clean.contains("127.0.0.1") || clean.contains("localhost")
    val start = System.currentTimeMillis()
    try {
      val raw = Http.get("$clean/status", headers = bridgeHeaders(token), timeoutMs = 5000)
      val latency = System.currentTimeMillis() - start
      val parsed = runSafely { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull()
      val authorized = parsed?.get("authorized")?.jsonPrimitive?.booleanOrNull ?: false
      val user = parsed?.get("user")?.jsonPrimitive?.contentOrNull
      val reach = context.getString(R.string.telegram_bridge_reachable, latency.toInt())
      val detail = reach + " · " + if (authorized) context.getString(R.string.telegram_api_connected_as, user ?: "Telegram") else context.getString(R.string.tg_not_logged_in)
      DiagnosticLogger.log("TelegramBridge", "Test erfolgreich (authorized=$authorized)")
      BridgeCheckResult(ok = true, detail = detail, latencyMs = latency, user = user)
    } catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      val latency = System.currentTimeMillis() - start
      DiagnosticLogger.warn("TelegramBridge", "Test fehlgeschlagen", e)
      BridgeCheckResult(ok = false, detail = bridgeError(context, e, clean), latencyMs = latency, isLocalhostWarning = isLocalhost)
    }
  }


  /** Error text for a failed bridge call: 401 means a wrong token, a 2FA answer asks for the password. */
  private fun bridgeError(context: Context, e: Throwable?, bridgeUrl: String): String {
    val http = e as? de.juliandigitalproducts.notitocal.net.HttpException
    if (http?.code == 401) return context.getString(R.string.telegram_bridge_token_missing_error)
    val body = http?.body?.let { runSafely { AppJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    if (body?.get("error")?.jsonPrimitive?.contentOrNull == "2FA_NEEDED") return context.getString(R.string.tg_err_2fa)
    val detail = body?.get("detail")?.jsonPrimitive?.contentOrNull ?: body?.get("error")?.jsonPrimitive?.contentOrNull
    val base = context.getString(R.string.tg_err_unreachable, detail ?: e?.message ?: e?.javaClass?.simpleName.orEmpty())
    return if (bridgeUrl.contains("127.0.0.1") || bridgeUrl.contains("localhost")) base + "\n" + context.getString(R.string.tg_hint_localhost) else base
  }

  /** Checks shared by both login steps; returns an error text or null. */
  private fun loginPrecheck(context: Context, apiId: String, apiHash: String, phone: String, bridgeUrl: String, bridgeToken: String): String? = when {
    !isValidApiId(apiId) -> context.getString(R.string.tg_err_api_id)
    !isValidApiHash(apiHash) -> context.getString(R.string.tg_err_api_hash)
    phone.trim().length < 6 -> context.getString(R.string.tg_err_phone)
    bridgeUrl.isBlank() -> context.getString(R.string.tg_err_bridge_url)
    bridgeToken.isBlank() -> context.getString(R.string.telegram_bridge_token_missing_error)
    else -> null
  }

  /**
   * Asks the bridge to send a Telegram login code. A login without the bridge does not exist (Telethon runs there),
   * so without a bridge URL this fails instead of pretending success.
   */
  suspend fun requestLoginCode(
    context: Context,
    apiId: String,
    apiHash: String,
    phone: String,
    bridgeUrl: String,
    bridgeToken: String = "",
  ): CheckResult {
    loginPrecheck(context, apiId, apiHash, phone, bridgeUrl, bridgeToken)?.let { return CheckResult(false, it) }
    return runSafely {
      val url = bridgeUrl.trimEnd('/') + "/auth/sendCode"
      val body = AppJson.encodeToString(
        JsonObject.serializer(),
        JsonObject(
          mapOf(
            "api_id" to JsonPrimitive(apiId.trim()),
            "api_hash" to JsonPrimitive(apiHash.trim()),
            "phone" to JsonPrimitive(phone.trim()),
          ),
        ),
      )
      val resp = AppJson.parseToJsonElement(Http.postJson(url, body, headers = bridgeHeaders(bridgeToken), timeoutMs = 15_000)).jsonObject
      if (resp["ok"]?.jsonPrimitive?.booleanOrNull == false) {
        CheckResult(false, resp["detail"]?.jsonPrimitive?.contentOrNull ?: resp["error"]?.jsonPrimitive?.contentOrNull ?: "?")
      } else {
        val hash = resp["phone_code_hash"]?.jsonPrimitive?.contentOrNull
        if (!hash.isNullOrBlank()) {
          lastPhoneCodeHash = hash
          val app = (context.applicationContext as? NotiToCalApp) ?: (context as? NotiToCalApp)
          app?.secrets?.put("telegram_phone_code_hash", hash)
        }
        DiagnosticLogger.log("TelegramBridge", "Login-Code angefordert")
        CheckResult(true, context.getString(R.string.tg_code_requested))
      }
    }.getOrElse { e ->
      DiagnosticLogger.warn("TelegramBridge", "Code-Anforderung fehlgeschlagen", e)
      CheckResult(false, bridgeError(context, e, bridgeUrl))
    }
  }

  /**
   * Completes the Telegram User-API login through the bridge. Only a login the bridge confirmed with ok=true marks
   * the account as connected; "2FA_NEEDED" asks for the two-step password.
   *
   * Without a bridge URL this switches on the local mode (Telegram notifications + allowlist) and says so,
   * without claiming a connected account.
   */
  suspend fun completeLogin(
    app: NotiToCalApp,
    apiId: String,
    apiHash: String,
    phone: String,
    code: String,
    bridgeUrl: String,
    password2fa: String = "",
    bridgeToken: String = "",
  ): CheckResult {
    val effectiveToken = bridgeToken.ifBlank { app.secrets.get(SecretKeys.TELEGRAM_BRIDGE_TOKEN) }
    if (bridgeUrl.isBlank()) {
      app.settings.update { it.copy(telegramApiEnabled = true, telegramApiConnectedUser = "") }
      DiagnosticLogger.log("TelegramUserApi", "Lokaler Modus aktiviert (ohne Bridge)")
      return CheckResult(true, app.getString(R.string.tg_local_mode))
    }
    loginPrecheck(app, apiId, apiHash, phone, bridgeUrl, effectiveToken)?.let { return CheckResult(false, it) }
    if (code.isBlank()) return CheckResult(false, app.getString(R.string.tg_err_code))
    val result = runSafely {
      val payload = mutableMapOf<String, JsonPrimitive>(
        "phone" to JsonPrimitive(phone.trim()),
        "code" to JsonPrimitive(code.trim()),
      )
      val codeHash = lastPhoneCodeHash?.takeIf { it.isNotBlank() }
        ?: app.secrets.get("telegram_phone_code_hash").takeIf { it.isNotBlank() }
      if (!codeHash.isNullOrBlank()) {
        payload["phone_code_hash"] = JsonPrimitive(codeHash)
      }
      val pwd = password2fa.trim().ifBlank { app.secrets.get(SecretKeys.TELEGRAM_2FA_PASSWORD).trim() }
      if (pwd.isNotBlank()) payload["password"] = JsonPrimitive(pwd)
      val raw = Http.postJson(
        bridgeUrl.trimEnd('/') + "/auth/signIn",
        AppJson.encodeToString(JsonObject.serializer(), JsonObject(payload)),
        headers = bridgeHeaders(effectiveToken),
        timeoutMs = 20_000,
      )
      AppJson.parseToJsonElement(raw).jsonObject
    }
    val resp = result.getOrElse { e ->
      DiagnosticLogger.error("TelegramBridge", "Sign-in fehlgeschlagen", e)
      return CheckResult(false, bridgeError(app, e, bridgeUrl))
    }
    if (resp["ok"]?.jsonPrimitive?.booleanOrNull != true) {
      // Older bridges answer "2FA needed" with HTTP 200 and ok=false.
      val err = resp["error"]?.jsonPrimitive?.contentOrNull
      return CheckResult(false, if (err == "2FA_NEEDED") app.getString(R.string.tg_err_2fa) else resp["detail"]?.jsonPrimitive?.contentOrNull ?: err ?: "?")
    }
    val user = resp["user"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Telegram"
    app.secrets.put(SecretKeys.TELEGRAM_API_SESSION, "")
    app.settings.update { it.copy(telegramApiEnabled = true, telegramApiConnectedUser = user) }
    DiagnosticLogger.log("TelegramUserApi", "Login über Bridge erfolgreich")
    return CheckResult(true, app.getString(R.string.tg_logged_in, user))
  }

  data class SyncResult(
    val total: Int,
    val added: Int,
    val fromBridge: Int,
    val fromBot: Int,
    val fromNotifications: Int,
    val fromHistory: Int,
    val bridgeStatus: String? = null,
    val botStatus: String? = null,
    val notificationStatus: String? = null,
    val historyStatus: String? = null,
    val bridgeError: String? = null,
    val botError: String? = null,
  ) {
    fun summary(context: Context): String = buildString {
      append(context.getString(R.string.tg_sync_total, total))
      if (added > 0) append(context.getString(R.string.tg_sync_added, added))
      append("\n\n• Telethon-Bridge: ")
      append(bridgeError?.let { context.getString(R.string.error_detail, it) } ?: if (fromBridge > 0) context.getString(R.string.tg_sync_from_bridge, fromBridge) else bridgeStatus.orEmpty())
      append("\n• Telegram-Bot: ")
      append(botError?.let { context.getString(R.string.error_detail, it) } ?: if (fromBot > 0) context.getString(R.string.tg_sync_from_bot, fromBot) else botStatus.orEmpty())
      append("\n• " + context.getString(R.string.tg_sync_notifications) + ": ")
      append(if (fromNotifications > 0) context.getString(R.string.tg_sync_from_notifications, fromNotifications) else notificationStatus.orEmpty())
      append("\n• " + context.getString(R.string.tg_sync_history) + ": ")
      append(if (fromHistory > 0) context.getString(R.string.tg_sync_from_history, fromHistory) else historyStatus.orEmpty())
      if (total == 0) append("\n\n💡 " + context.getString(R.string.tg_sync_tip))
    }
  }

  /**
   * Synchronizes groups and contacts from all available sources:
   * optional MTProto bridge, the Telegram bot (getUpdates, not confirmed), the Telegram
   * notifications currently in the shade and the local message history.
   */
  suspend fun syncDialogs(app: NotiToCalApp): SyncResult = withContext(Dispatchers.IO) {
    val s = app.settings.current
    val before = s.telegramDialogs
    val bridgeToken = app.secrets.get(SecretKeys.TELEGRAM_BRIDGE_TOKEN)

    // 1. Optional bridge endpoint (real dialog list of the account)
    var bridgeError: String? = null
    var bridgeStatus: String? = null
    val fromBridge = if (s.telegramApiBridgeUrl.isNotBlank() && bridgeToken.isBlank()) {
      bridgeError = app.getString(R.string.telegram_bridge_token_missing_error)
      emptyList()
    } else if (s.telegramApiBridgeUrl.isNotBlank()) {
      runSafely {
        val raw = Http.get(s.telegramApiBridgeUrl.trimEnd('/') + "/dialogs", headers = bridgeHeaders(bridgeToken), timeoutMs = 10_000)
        val arr = AppJson.parseToJsonElement(raw).jsonObject["dialogs"]?.jsonArray.orEmpty()
        bridgeStatus = app.getString(R.string.tg_sync_from_bridge, arr.size)
        arr.mapNotNull { el ->
          val obj = el.jsonObject
          val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
          if (title.isBlank()) return@mapNotNull null
          val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
          val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
          TelegramDialogItem(
            id = if (id.isNotBlank()) "tgchat_$id" else "tg_${title.lowercase().hashCode()}",
            title = title,
            username = obj["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            kind = when {
              type.contains("user", true) || type.contains("person", true) || type.contains("private", true) -> TelegramDialogKind.PERSON
              type.contains("channel", true) -> TelegramDialogKind.CHANNEL
              else -> TelegramDialogKind.GROUP
            },
            selected = false,
          )
        }
      }.getOrElse { e ->
        bridgeError = bridgeError(app, e, s.telegramApiBridgeUrl)
        emptyList()
      }
    } else {
      bridgeStatus = app.getString(R.string.tg_sync_no_bridge)
      emptyList()
    }

    // 2. Telegram bot: chats it is a member of (getUpdates without offset + getChat for configured IDs)
    var botError: String? = null
    var botStatus: String? = null
    val botToken = app.secrets.get(SecretKeys.TELEGRAM_BOT).trim()
    val fromBot = if (botToken.isNotBlank()) {
      val list = mutableListOf<TelegramDialogItem>()
      runSafely {
        val raw = Http.get("https://api.telegram.org/bot$botToken/getUpdates?limit=100&timeout=0", timeoutMs = 12_000)
        val root = AppJson.parseToJsonElement(raw).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) {
          botError = root["description"]?.jsonPrimitive?.contentOrNull ?: "?"
        } else {
          root["result"]?.jsonArray.orEmpty().forEach { u ->
            list += dialogsFromBotUpdate(u.jsonObject)
          }
          botStatus = app.getString(R.string.tg_sync_from_bot, list.size)
        }
      }.onFailure { e -> botError = DiagnosticLogger.sanitize(e.message?.take(100) ?: e.javaClass.simpleName) }
      // Also query getChat for any configured telegramChatId(s) so chats are found even when getUpdates was already confirmed
      if (s.telegramChatId.isNotBlank()) {
        s.telegramChatId.split(',', ';', ' ').map { it.trim() }.filter { it.isNotBlank() }.forEach { cid ->
          TelegramSource.fetchChatById(botToken, cid)?.let { list += it }
        }
      }
      list
    } else {
      botStatus = app.getString(R.string.tg_sync_no_bot)
      emptyList()
    }

    // 3. Telegram notifications currently visible or snoozed
    val notifList = NotificationCaptureService.activeTelegramChats()
    val fromNotifications = notifList.flatMap { (sender, grp) -> dialogsFromSender(sender, grp) }
    val notifStatus = if (fromNotifications.isNotEmpty()) app.getString(R.string.tg_sync_from_notifications, fromNotifications.size) else app.getString(R.string.tg_sync_no_notifications)

    // 4. Local message history
    val historyList = app.log.entries.value.filter {
      it.source == SourceType.TELEGRAM ||
        it.source == SourceType.TELEGRAM_API ||
        it.packageName in TELEGRAM_PACKAGES ||
        it.appLabel.contains("Telegram", ignoreCase = true)
    }
    val fromHistory = historyList.flatMap { dialogsFromSender(it.sender, isGroup = false) }
    val historyStatus = if (fromHistory.isNotEmpty()) app.getString(R.string.tg_sync_from_history, fromHistory.size) else app.getString(R.string.tg_sync_no_history)

    val found = fromBridge + fromBot + fromNotifications + fromHistory
    var merged = before
    app.settings.update { st ->
      merged = merge(st.telegramDialogs, found)
      st.copy(telegramDialogs = merged)
    }

    val result = SyncResult(
      total = merged.size,
      added = (merged.size - before.size).coerceAtLeast(0),
      fromBridge = fromBridge.distinctBy { it.title.lowercase() }.size,
      fromBot = fromBot.distinctBy { it.title.lowercase() }.size,
      fromNotifications = fromNotifications.distinctBy { it.title.lowercase() }.size,
      fromHistory = fromHistory.distinctBy { it.title.lowercase() }.size,
      bridgeStatus = bridgeStatus,
      botStatus = botStatus,
      notificationStatus = notifStatus,
      historyStatus = historyStatus,
      bridgeError = bridgeError,
      botError = botError,
    )
    DiagnosticLogger.log("TelegramSync", "Sync abgeschlossen: ${result.total} Dialoge (${result.added} neu). Bridge=$bridgeStatus, Bot=$botStatus")
    result
  }

  suspend fun pollMessages(app: NotiToCalApp): Int {
    val s = app.settings.current
    if (!s.telegramApiEnabled || s.paused || s.telegramApiBridgeUrl.isBlank()) return 0
    val prefs = app.getSharedPreferences("telegram_user_api_state", Context.MODE_PRIVATE)
    val bridgeToken = app.secrets.get(SecretKeys.TELEGRAM_BRIDGE_TOKEN)
    if (bridgeToken.isBlank()) return 0

    // B1: Per-chat since IDs
    val chatSinceRaw = prefs.getString("chat_since_map", null).orEmpty()
    val chatSinceMap = mutableMapOf<String, Long>()
    if (chatSinceRaw.isNotBlank()) {
      chatSinceRaw.split(",").forEach { entry ->
        val parts = entry.split(":")
        if (parts.size == 2) {
          parts[1].trim().toLongOrNull()?.let { chatSinceMap[parts[0].trim()] = it }
        }
      }
    }
    val fallbackSinceId = prefs.getLong("last_msg_id", 0L)
    val sinceParam = if (chatSinceMap.isNotEmpty()) {
      chatSinceMap.entries.joinToString(",") { "${it.key}:${it.value}" }
    } else {
      fallbackSinceId.toString()
    }

    return runSafely {
      val url = s.telegramApiBridgeUrl.trimEnd('/') + "/messages?since=$sinceParam&limit=20"
      val raw = Http.get(url, headers = bridgeHeaders(bridgeToken), timeoutMs = 12_000)
      val arr = AppJson.parseToJsonElement(raw).jsonObject["messages"]?.jsonArray ?: return@runSafely 0
      var maxId = fallbackSinceId
      var count = 0
      for (el in arr) {
        val obj = el.jsonObject
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
        val chatIdStr = obj["chat_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (chatIdStr.isNotBlank()) {
          chatSinceMap[chatIdStr] = maxOf(chatSinceMap[chatIdStr] ?: 0L, id)
        }
        if (id > maxId) maxId = id
        // Stored per message, so a failed analysis further down does not fetch the same messages again.
        prefs.edit {
          putString("chat_since_map", chatSinceMap.entries.joinToString(",") { "${it.key}:${it.value}" })
          putLong("last_msg_id", maxId)
        }
        val dateSec = obj["date"]?.jsonPrimitive?.longOrNull
        if (dateSec != null && s.telegramTimeWindowHours > 0) {
          val cutoff = System.currentTimeMillis() - s.telegramTimeWindowHours * 3600_000L
          if (dateSec * 1000L < cutoff) continue
        }
        val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (text.isBlank()) continue
        val sender = obj["sender"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Telegram" }
        val chat = obj["chat"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val isGroup = obj["is_group"]?.jsonPrimitive?.booleanOrNull ?: chat.isNotBlank()
        val fullSender = if (chat.isNotBlank() && chat != sender) "$sender @ $chat" else sender
        recordSeenDialog(app, fullSender, isGroup)
        if (!shouldProcessTelegramMessage(s, fullSender, isGroup)) continue
        if (processSafely(app, Captured(source = SourceType.TELEGRAM_API, appLabel = "Telegram API", sender = fullSender, text = text, isGroup = isGroup))) count++
      }
      count
    }.getOrDefault(0)
  }

  fun testConnection(app: NotiToCalApp): CheckResult {
    val s = app.settings.current
    val apiHash = app.secrets.get(SecretKeys.TELEGRAM_API_HASH)
    if (!s.telegramApiEnabled && s.telegramApiId.isBlank()) {
      return CheckResult(true, app.getString(R.string.off))
    }
    if (!isValidApiId(s.telegramApiId) || !isValidApiHash(apiHash)) {
      return CheckResult(false, app.getString(R.string.tg_err_api_creds))
    }
    val modeLabel = app.getString(if (s.telegramFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_mode_all_except else R.string.filter_mode_only_selected)
    val selectedCount = s.telegramDialogs.count { it.selected }
    val rateInfo = if (s.telegramRateLimitEnabled) app.getString(R.string.tg_flood_on, s.telegramMaxMsgsPerMinute) else app.getString(R.string.tg_flood_off)
    return CheckResult(true, app.getString(R.string.tg_api_active, modeLabel, selectedCount, rateInfo))
  }
}

/**
 * Dedicated WhatsApp & WhatsApp Business source filter and rate-limit helper.
 */
object WhatsAppSource {
  const val PKG_PERSONAL = "com.whatsapp"
  const val PKG_BUSINESS = "com.whatsapp.w4b"
  val PACKAGES = setOf(PKG_PERSONAL, PKG_BUSINESS)

  private val chatTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()
  @Volatile var lastPhoneCodeHash: String? = null

  fun shouldProcess(s: AppSettings, pkg: String, sender: String, isGroup: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (!s.whatsappEnabled) return false
    if (pkg == PKG_PERSONAL && !s.whatsappPersonalEnabled) return false
    if (pkg == PKG_BUSINESS && !s.whatsappBusinessEnabled) return false
    if (isGroup && s.whatsappIgnoreGroups) return false

    // Rate-limit large WhatsApp groups
    if (isGroup && s.whatsappMaxMsgsPerMinute > 0) {
      val chatKey = if (sender.contains(" @ ")) sender.substringAfter(" @ ").trim().lowercase() else sender.trim().lowercase()
      val deque = chatTimestamps.getOrPut(chatKey.ifBlank { "wa_group" }) { ArrayDeque() }
      synchronized(deque) {
        val cutoff = nowMs - 60_000L
        while (deque.isNotEmpty() && deque.first() < cutoff) deque.removeFirst()
        if (deque.size >= s.whatsappMaxMsgsPerMinute) return false
        deque.addLast(nowMs)
      }
    }

    val tokens = s.whatsappFilterContacts.split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return s.whatsappFilterMode == FilterListMode.ALL_EXCEPT
    val lowerSender = sender.lowercase()
    val matches = tokens.any { lowerSender.contains(it) }
    return when (s.whatsappFilterMode) {
      FilterListMode.ALL_EXCEPT -> !matches
      FilterListMode.ONLY_SELECTED -> matches
    }
  }

  fun testPersonal(context: Context, s: AppSettings): ShareAndClipboardSource.CheckResult {
    if (!s.whatsappPersonalEnabled) return ShareAndClipboardSource.CheckResult(true, context.getString(R.string.off))
    if (!NotificationCaptureService.isEnabled(context)) {
      return ShareAndClipboardSource.CheckResult(false, context.getString(R.string.src_no_notification_access))
    }
    val modeLabel = context.getString(if (s.whatsappFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_mode_all_except else R.string.filter_mode_only_selected)
    return ShareAndClipboardSource.CheckResult(
      true,
      context.getString(R.string.src_active_rate, "WhatsApp", modeLabel, s.whatsappMaxMsgsPerMinute),
    )
  }

  fun testBusiness(context: Context, s: AppSettings): ShareAndClipboardSource.CheckResult {
    if (!s.whatsappBusinessEnabled) return ShareAndClipboardSource.CheckResult(true, context.getString(R.string.off))
    if (!NotificationCaptureService.isEnabled(context)) {
      return ShareAndClipboardSource.CheckResult(false, context.getString(R.string.src_no_notification_access))
    }
    val modeLabel = context.getString(if (s.whatsappFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_mode_all_except else R.string.filter_mode_only_selected)
    return ShareAndClipboardSource.CheckResult(
      true,
      context.getString(R.string.src_active_rate, "WhatsApp Business", modeLabel, s.whatsappMaxMsgsPerMinute),
    )
  }

  fun testSource(context: Context, s: AppSettings): ShareAndClipboardSource.CheckResult {
    if (!s.whatsappEnabled) return ShareAndClipboardSource.CheckResult(true, context.getString(R.string.off))
    if (!NotificationCaptureService.isEnabled(context)) {
      return ShareAndClipboardSource.CheckResult(false, context.getString(R.string.src_no_notification_access))
    }
    val activeVariants = buildList {
      if (s.whatsappPersonalEnabled) add("WhatsApp")
      if (s.whatsappBusinessEnabled) add("WhatsApp Business")
    }
    if (activeVariants.isEmpty()) {
      return ShareAndClipboardSource.CheckResult(false, context.getString(R.string.src_wa_none))
    }
    val modeLabel = context.getString(if (s.whatsappFilterMode == FilterListMode.ALL_EXCEPT) R.string.filter_mode_all_except else R.string.filter_mode_only_selected)
    return ShareAndClipboardSource.CheckResult(
      true,
      context.getString(R.string.src_active_rate, activeVariants.joinToString(" + "), modeLabel, s.whatsappMaxMsgsPerMinute),
    )
  }
}

/**
 * Dedicated SMS & RCS messaging source helper with 2FA/OTP noise suppression.
 */
object SmsRcsSource {
  val PACKAGES = setOf(
    "com.google.android.apps.messaging",
    "com.samsung.android.messaging",
    "com.android.mms",
    "org.fossify.messages",
    "com.simplemobiletools.smsmessenger",
  )

  private val OTP_REGEX = Regex(
    """(?i)\b(sicherheitscode|bestätigungscode|verification code|security code|einmalcode|otp|tan|passcode|authentifizierungscode|login code)\b""",
  )

  fun isTwoFactorNoise(text: String): Boolean {
    val t = text.trim()
    if (!OTP_REGEX.containsMatchIn(t)) return false
    return Regex("""\b\d{4,8}\b""").containsMatchIn(t)
  }

  fun shouldProcess(s: AppSettings, sender: String, text: String): Boolean {
    if (!s.smsRcsEnabled) return false
    if (s.smsIgnore2fa && isTwoFactorNoise(text)) return false
    val tokens = s.smsFilterSenders.split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return s.smsFilterMode == FilterListMode.ALL_EXCEPT
    val matches = tokens.any { sender.lowercase().contains(it) }
    return when (s.smsFilterMode) {
      FilterListMode.ALL_EXCEPT -> !matches
      FilterListMode.ONLY_SELECTED -> matches
    }
  }

  fun testSource(context: Context, s: AppSettings): ShareAndClipboardSource.CheckResult {
    if (!s.smsRcsEnabled) return ShareAndClipboardSource.CheckResult(true, context.getString(R.string.off))
    if (!NotificationCaptureService.isEnabled(context)) {
      return ShareAndClipboardSource.CheckResult(false, context.getString(R.string.src_no_notification_access))
    }
    val otpFiltered = isTwoFactorNoise("Ihr Sicherheitscode lautet 482910. Bitte nicht weitergeben.")
    return ShareAndClipboardSource.CheckResult(
      otpFiltered,
      context.getString(R.string.src_sms_active, context.getString(if (s.smsIgnore2fa) R.string.on else R.string.off)),
    )
  }
}

/**
 * Calendar invitations & .ics file source parser and folder watcher.
 */
object IcsCalendarSource {
  private const val PREFS = "ics_source_tracker"

  fun parseIcsToText(icsContent: String): String {
    // Handle RFC 5545 line folding (CRLF followed by space or tab)
    val unfolded = icsContent.replace(Regex("""\r?\n[ \t]"""), "")
    val lines = unfolded.lines()
    var summary = ""
    var dtStart = ""
    var dtEnd = ""
    var location = ""
    var description = ""
    var organizer = ""

    for (rawLine in lines) {
      val line = rawLine.trim()
      val key = line.substringBefore(':').substringBefore(';').uppercase()
      val value = line.substringAfter(':', "").trim()
        .replace("\\n", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
      when (key) {
        "SUMMARY" -> if (summary.isBlank()) summary = value
        "DTSTART" -> if (dtStart.isBlank()) dtStart = formatIcsDateTime(value)
        "DTEND", "DUE" -> if (dtEnd.isBlank()) dtEnd = formatIcsDateTime(value)
        "LOCATION" -> if (location.isBlank()) location = value
        "DESCRIPTION" -> if (description.isBlank()) description = value
        "ORGANIZER" -> if (organizer.isBlank()) {
          organizer = Regex("""CN=([^;:]+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)
            ?: value.removePrefix("mailto:")
        }
      }
    }

    if (summary.isBlank() && dtStart.isBlank()) return icsContent.take(1000)
    return buildString {
      append("Termin: ").append(summary.ifBlank { "Kalendereinladung" })
      if (dtStart.isNotBlank()) append("\nAm ").append(dtStart)
      if (dtEnd.isNotBlank()) append(" bis ").append(dtEnd)
      if (location.isNotBlank()) append("\nOrt: ").append(location)
      if (organizer.isNotBlank()) append("\nVon: ").append(organizer)
      if (description.isNotBlank()) append("\n\n").append(description.take(600))
    }
  }

  private fun formatIcsDateTime(raw: String): String {
    val clean = raw.trim().removeSuffix("Z")
    val m = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2}))?""").find(clean) ?: return raw
    val (y, mo, d, h, mi) = m.destructured
    return if (h.isNotEmpty() && mi.isNotEmpty()) "$d.$mo.$y um $h:$mi Uhr" else "$d.$mo.$y"
  }

  suspend fun scanFolder(app: NotiToCalApp): Int = withContext(Dispatchers.IO) {
    val s = app.settings.current
    if (!s.icsEnabled || s.icsFolderUri.isBlank() || s.paused) return@withContext 0
    val tree = runSafely { s.icsFolderUri.toUri() }.getOrNull() ?: return@withContext 0
    val docs = runSafely { FolderWatch.list(app, tree, FolderWatch.Kind.TEXT) }.getOrDefault(emptyList())
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var count = 0
    for (doc in docs) {
      val seenKey = "ics_${doc.id}"
      if (prefs.getLong(seenKey, 0L) >= doc.modified) continue
      val raw = runSafely {
        app.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use { it.readText() }
      }.getOrNull().orEmpty()
      prefs.edit { putLong(seenKey, doc.modified) }
      if (!raw.contains("BEGIN:VCALENDAR", ignoreCase = true) && !raw.contains("BEGIN:VEVENT", ignoreCase = true)) continue
      val parsed = parseIcsToText(raw)
      if (parsed.isNotBlank()) {
        app.pipeline.process(
          Captured(
            source = SourceType.CALENDAR_ICS,
            appLabel = "ICS Kalender",
            text = parsed,
          ),
        )
        count++
      }
    }
    count
  }

  fun testSource(app: NotiToCalApp): ShareAndClipboardSource.CheckResult {
    val s = app.settings.current
    if (!s.icsEnabled) return ShareAndClipboardSource.CheckResult(true, "Aus")
    val sampleIcs = """
      BEGIN:VCALENDAR
      BEGIN:VEVENT
      SUMMARY:Projektbesprechung
      DTSTART:20261118T140000
      LOCATION:Konferenzraum B
      END:VEVENT
      END:VCALENDAR
    """.trimIndent()
    val parsed = parseIcsToText(sampleIcs)
    val detected = RuleEngine.analyze(parsed, "ICS-Test")
    val ok = detected.kind == Kind.EVENT && detected.title.contains("Projektbesprechung", ignoreCase = true)
    return ShareAndClipboardSource.CheckResult(
      ok,
      if (ok) "ICS parser ✓ (${detected.title} · ${detected.location.orEmpty()})" else "ICS parser ✗",
    )
  }
}

/**
 * Runs the analysis for one remote message. A failure (e.g. an AI timeout) is logged and skipped so the rest of
 * the batch is still processed; cancellation is passed on.
 */
internal suspend fun processSafely(app: NotiToCalApp, captured: Captured): Boolean = try {
  app.pipeline.process(captured)
  true
} catch (e: kotlinx.coroutines.CancellationException) {
  throw e
} catch (e: Exception) {
  DiagnosticLogger.warn("RemoteSource", "Analyse fehlgeschlagen (${captured.source})", e)
  false
}
