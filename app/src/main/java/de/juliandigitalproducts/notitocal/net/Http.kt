package de.juliandigitalproducts.notitocal.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(300)}")

/** Plain http:// to a host outside the home network (API keys and messages would travel unencrypted). */
class InsecureUrlException(host: String) : IOException("Use https:// for $host (plain http:// only works for addresses in the local network)")

/** C9: decides whether plain http:// is acceptable for a host (local network only). */
object LanHosts {
  private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".fritz.box", ".ts.net")

  fun isLocal(hostRaw: String): Boolean {
    val host = hostRaw.lowercase().trim('[', ']').trimEnd('.')
    if (host.isEmpty()) return false
    if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
    ipv4(host)?.let { (a, b) ->
      return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
    if (':' in host) return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
      host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
    // A single-label name ("nas", "raspberrypi") only resolves in the local network.
    return '.' !in host
  }

  private fun ipv4(host: String): Pair<Int, Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val n = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }
    return n[0] to n[1]
  }
}

/** Minimal HTTP client on top of HttpURLConnection (keeps the APK free of extra network libraries). */
object Http {

  /** Responses larger than this are cut off with an error (a misbehaving server cannot fill the memory). */
  const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
  private const val MAX_REDIRECTS = 5

  /** Headers that carry credentials; they are never sent on to another host after a redirect. */
  private fun isCredential(name: String) = name.lowercase().let {
    it == "authorization" || it == "cookie" || it.contains("token") || it.contains("api-key") || it.contains("apikey") || it.contains("secret")
  }

  /** Headers for the next hop of a redirect: everything on the same host, no credentials on another host. */
  fun headersForRedirect(headers: Map<String, String>, from: URL, to: URL): Map<String, String> =
    if (from.host.equals(to.host, ignoreCase = true) && from.protocol == to.protocol) headers
    else headers.filterKeys { !isCredential(it) }

  suspend fun request(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    contentType: String? = null,
    timeoutMs: Int = 30_000,
  ): String = withContext(Dispatchers.IO) {
    var target = URL(url)
    var currentHeaders = headers
    var currentMethod = method
    var currentBody = body
    repeat(MAX_REDIRECTS + 1) {
      if (target.protocol.equals("http", ignoreCase = true) && !LanHosts.isLocal(target.host)) throw InsecureUrlException(target.host)
      val conn = (target.openConnection() as HttpURLConnection).apply {
        // Redirects are followed below, so credentials never travel to another host (HttpURLConnection would keep them).
        instanceFollowRedirects = false
        requestMethod = currentMethod
        connectTimeout = minOf(timeoutMs, 15_000)
        readTimeout = timeoutMs
        setRequestProperty("Accept", "application/json, text/plain, */*")
        setRequestProperty("User-Agent", "NotiToCal-Android")
        currentHeaders.forEach { (k, v) -> if (v.isNotEmpty()) setRequestProperty(k, v) }
        if (contentType != null && currentBody != null) setRequestProperty("Content-Type", contentType)
        if (currentBody != null) {
          doOutput = true
          setFixedLengthStreamingMode(currentBody!!.size)
        }
      }
      try {
        currentBody?.let { b -> conn.outputStream.use { it.write(b) } }
        val code = conn.responseCode
        if (code in 300..399 && code != 304) {
          val location = conn.getHeaderField("Location") ?: throw HttpException(code, "redirect without Location")
          val next = URL(target, location)
          currentHeaders = headersForRedirect(currentHeaders, target, next)
          // 303 (and 301/302 for POST, like browsers) continue as GET without a body.
          if (code == 303 || ((code == 301 || code == 302) && currentMethod == "POST")) {
            currentMethod = "GET"
            currentBody = null
          }
          target = next
          return@repeat
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { readLimited(it) }.orEmpty()
        if (code !in 200..299) throw HttpException(code, text)
        return@withContext text
      } finally {
        conn.disconnect()
      }
    }
    throw IOException("Too many redirects")
  }

  /** Reads at most [MAX_RESPONSE_BYTES] as UTF-8. */
  fun readLimited(input: java.io.InputStream, limit: Int = MAX_RESPONSE_BYTES): String {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      if (out.size() + n > limit) throw IOException("Response larger than ${limit / 1024 / 1024} MB")
      out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
  }

  suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): String =
    request("POST", url, headers, json.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", timeoutMs)

  suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): String =
    request("GET", url, headers, null, null, timeoutMs)
}
