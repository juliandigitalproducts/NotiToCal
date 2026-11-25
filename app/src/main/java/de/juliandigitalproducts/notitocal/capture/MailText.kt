package de.juliandigitalproducts.notitocal.capture

import java.nio.charset.Charset
import java.util.Base64

/**
 * Turns the raw IMAP header and body of an e-mail into readable text: RFC 2047 encoded words in headers,
 * MIME multipart (text/plain preferred, else text/html without tags), base64 and quoted-printable with the
 * declared charset. Bodies are fetched truncated, so every step tolerates a cut-off end.
 */
object MailText {
  private val ENCODED_WORD = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")

  fun decodeHeader(value: String): String {
    // Whitespace between two encoded words is not part of the text (RFC 2047 §6.2).
    val joined = value.replace(Regex("""\?=\s+=\?"""), "?==?")
    return ENCODED_WORD.replace(joined) { m ->
      val charset = charsetOf(m.groupValues[1])
      val bytes = if (m.groupValues[2].equals("b", true)) base64(m.groupValues[3]) else quotedPrintable(m.groupValues[3].replace('_', ' '))
      String(bytes, charset)
    }.trim()
  }

  /** Header fields (unfolded, names lower-case). */
  fun headers(block: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var last: String? = null
    for (line in block.replace("\r", "").lines()) {
      if (line.isEmpty()) break
      if ((line.startsWith(" ") || line.startsWith("\t")) && last != null) {
        out[last] = out[last] + " " + line.trim()
      } else {
        val i = line.indexOf(':')
        if (i <= 0) continue
        last = line.substring(0, i).trim().lowercase()
        out[last] = line.substring(i + 1).trim()
      }
    }
    return out
  }

  /** Readable text of a message whose top-level headers are [headerBlock] and whose body is [body]. */
  fun bodyText(headerBlock: String, body: String): String = part(headers(headerBlock), body, 0).trim()

  private fun part(h: Map<String, String>, content: String, depth: Int): String {
    val type = h["content-type"].orEmpty()
    val mime = type.substringBefore(';').trim().lowercase().ifBlank { "text/plain" }
    if (mime.startsWith("multipart/") && depth < 5) {
      val boundary = param(type, "boundary") ?: return ""
      val parts = content.replace("\r", "").split("--$boundary")
        .drop(1)
        .filterNot { it.startsWith("--") }
        .map { raw ->
          val p = raw.removePrefix("\n")
          val split = p.indexOf("\n\n").takeIf { it >= 0 } ?: p.length
          headers(p.substring(0, split)) to p.substring(minOf(split + 2, p.length))
        }
      val plain = parts.firstOrNull { (ph, _) -> ph["content-type"].orEmpty().lowercase().let { it.isBlank() || it.startsWith("text/plain") } && !isAttachment(ph) }
      val html = parts.firstOrNull { (ph, _) -> ph["content-type"].orEmpty().lowercase().startsWith("text/html") && !isAttachment(ph) }
      val nested = parts.filter { (ph, _) -> ph["content-type"].orEmpty().lowercase().startsWith("multipart/") }
      (plain ?: html)?.let { (ph, pc) -> return part(ph, pc, depth + 1) }
      for ((ph, pc) in nested) part(ph, pc, depth + 1).takeIf { it.isNotBlank() }?.let { return it }
      return ""
    }
    if (!mime.startsWith("text/")) return ""
    val charset = charsetOf(param(type, "charset") ?: "UTF-8")
    val bytes = when (h["content-transfer-encoding"].orEmpty().trim().lowercase()) {
      "base64" -> base64(content)
      "quoted-printable" -> quotedPrintable(content)
      // 7bit/8bit: the IMAP reader already decoded the bytes as UTF-8.
      else -> return clean(mime, content)
    }
    return clean(mime, String(bytes, charset))
  }

  private fun isAttachment(h: Map<String, String>) = h["content-disposition"].orEmpty().lowercase().startsWith("attachment")

  private fun clean(mime: String, text: String): String {
    val t = if (mime == "text/html") htmlToText(text) else text
    return t.replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
  }

  fun htmlToText(html: String): String = html
    .replace(Regex("""(?is)<(script|style|head)[^>]*>.*?</\1>"""), " ")
    .replace(Regex("""(?i)<br\s*/?>|</p>|</div>|</tr>|</li>|</h\d>"""), "\n")
    .replace(Regex("""<[^>]+>"""), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("""&#(\d{2,5});""")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
    .replace(Regex("""[ \t]{2,}"""), " ")

  private fun param(header: String, name: String): String? =
    Regex("""(?i)(?:^|;)\s*$name\s*=\s*("([^"]*)"|[^;\s]+)""").find(header)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } }

  private fun charsetOf(name: String): Charset = runCatching { Charset.forName(name.trim().trim('"')) }.getOrDefault(Charsets.UTF_8)

  private fun base64(s: String): ByteArray {
    val clean = s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }
    // A truncated fetch can cut the last group: decode only complete 4-character groups.
    return runCatching { Base64.getDecoder().decode(clean.substring(0, clean.length - clean.length % 4)) }.getOrDefault(ByteArray(0))
  }

  private fun quotedPrintable(s: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val text = s.replace("=\r\n", "").replace("=\n", "")
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (c == '=' && i + 2 < text.length) {
        val v = text.substring(i + 1, i + 3).toIntOrNull(16)
        if (v != null) {
          out.write(v)
          i += 3
          continue
        }
      }
      val bytes = c.toString().toByteArray(Charsets.UTF_8)
      out.write(bytes, 0, bytes.size)
      i++
    }
    return out.toByteArray()
  }
}
