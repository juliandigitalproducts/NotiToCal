// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import de.juliandigitalproducts.notitocal.model.runSafely

/**
 * Repairs text before analysis so umlauts and accents come out right:
 * - decomposed characters from iPhones/Macs ("a" + combining diaeresis) → "ä" (NFC),
 * - HTML entities from mail apps ("&uuml;", "&#252;"), literal "ü" escapes from small models,
 * - mojibake ("Ã¼" → "ü") from servers that sent UTF-8 as Latin-1, and the byte alphabet of small models
 *   ("Ãľ" → "Ü", Qwen/GPT-2 style tokenizers on MediaPipe),
 * - German words typed without umlauts ("uebermorgen", "naechsten Freitag", "Muenchen") → proper spelling.
 */
object TextNormalizer {

  private val NAMED = mapOf(
    "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
    "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ", "euro" to "€",
    "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "aacute" to "á", "agrave" to "à", "acirc" to "â", "iacute" to "í",
    "oacute" to "ó", "ocirc" to "ô", "uacute" to "ú", "ugrave" to "ù", "ccedil" to "ç", "ntilde" to "ñ", "atilde" to "ã",
    "otilde" to "õ", "Eacute" to "É", "Ccedil" to "Ç", "Ntilde" to "Ñ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
    "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "bdquo" to "„",
  )
  private val ENTITY = Regex("""&(#[0-9]{2,6}|#[xX][0-9a-fA-F]{2,5}|[A-Za-z]{2,7});""")
  private val U_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
  private val CP1252 = runSafely { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

  /**
   * Which byte a character stands for when UTF-8 was shown byte by byte: Latin-1, Windows-1252 and the byte alphabet
   * of GPT-2 style tokenizers (unprintable bytes moved to U+0100…U+0143). Only bytes ≥ 0x80 matter here.
   */
  private val BYTE_OF: Map<Char, Int> = buildMap {
    var shifted = 0
    for (b in 0..255) {
      val printable = b in 33..126 || b in 161..172 || b in 174..255
      if (!printable) { if (b >= 0x80) put((256 + shifted).toChar(), b); shifted++ }
    }
    for (b in 0x80..0xFF) putIfAbsent(b.toChar(), b)
    for (b in 0x80..0x9F) {
      val c = String(byteArrayOf(b.toByte()), CP1252).single()
      if (c != '\uFFFD') putIfAbsent(c, b)
    }
  }

  /** German word stems commonly typed with ae/oe/ue (only unambiguous ones). */
  private val DIGRAPH_STEMS = listOf(
    "uebermorgen", "naechst", "maerz", "fruehstueck", "frueh", "spaet", "faellig", "faellt", "muess", "koenn", "koennt",
    "wuerd", "fuer", "ueber", "zurueck", "kuendig", "gespraech", "vorstellungsgespraech", "buero", "pruef", "rueckruf",
    "zahnaerzt", "aerzt", "moecht", "geschaeft", "taeglich", "woechentlich", "erklaer", "bestaetig", "aender",
    "erhaelt", "hinzufueg", "praesentation", "kuehlschrank", "waesche", "schluessel", "gebuehr",
    "rueckgabe", "muell", "einkaeuf", "kaeuf", "muenchen", "koeln", "duesseldorf", "nuernberg", "zuerich", "wuerzburg",
    "loesch", "oeffn", "gruess", "ueberweis", "abschlusspruef", "begruess", "persoenlich", "moeglich",
    "naechte", "saeumig", "fuehr", "glueckwunsch", "fruehjahr", "schoenen", "spaetestens",
  ).sortedByDescending { it.length }
  private val DIGRAPH = rx("""\b(${DIGRAPH_STEMS.joinToString("|")})""")

  fun normalize(text: String): String {
    if (text.isEmpty()) return text
    var t = fixEncoding(text)
    t = DIGRAPH.replace(t) { m -> resolveDigraphs(m.value) }
    return t
  }

  /** Encoding repairs only (no spelling changes) – used for model output. */
  fun fixEncoding(text: String): String {
    if (text.isEmpty()) return text
    var t = repairBytes(text)
    if ('&' in t) t = ENTITY.replace(t) { m ->
      val e = m.groupValues[1]
      when {
        e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
        else -> NAMED[e] ?: m.value
      }
    }
    if ("\\u" in t) t = U_ESCAPE.replace(t) { m -> m.groupValues[1].toInt(16).toChar().toString() }
    return Normalizer.normalize(t, Normalizer.Form.NFC)
  }

  /**
   * Turns runs of byte characters back into text when they form valid UTF-8 ("Ã¼" → "ü", "Ãľ" → "Ü"). Real text like
   * "Größe" is never valid UTF-8 read this way, so it stays as it is.
   */
  private fun repairBytes(text: String): String {
    if (text.none { it.code >= 0x80 && BYTE_OF.containsKey(it) }) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      val decoded = sequenceAt(text, i)
      if (decoded != null) {
        out.append(decoded.first)
        i += decoded.second
      } else {
        out.append(text[i])
        i++
      }
    }
    return out.toString()
  }

  /** One UTF-8 sequence written as byte characters at [i]: the decoded text and how many characters it used. */
  private fun sequenceAt(text: String, i: Int): Pair<String, Int>? {
    val lead = BYTE_OF[text[i]] ?: return null
    val length = when (lead) {
      in 0xC2..0xDF -> 2
      in 0xE0..0xEF -> 3
      in 0xF0..0xF4 -> 4
      else -> return null
    }
    if (i + length > text.length) return null
    val bytes = ByteArray(length)
    for (k in 0 until length) {
      val b = BYTE_OF[text[i + k]] ?: return null
      if (k > 0 && b !in 0x80..0xBF) return null
      bytes[k] = b.toByte()
    }
    val decoded = runSafely {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull() ?: return null
    return decoded to length
  }

  private fun resolveDigraphs(word: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < word.length) {
      val c = word[i]
      val next = word.getOrNull(i + 1)
      val umlaut = if (next == 'e' || next == 'E') when (c) {
        'a' -> 'ä'; 'o' -> 'ö'; 'u' -> 'ü'; 'A' -> 'Ä'; 'O' -> 'Ö'; 'U' -> 'Ü'; else -> null
      } else null
      // "eue"/"aue" (as in "teuer", "Bauer") keep their "ue".
      val prev = word.getOrNull(i - 1)?.lowercaseChar()
      if (umlaut != null && !(c.lowercaseChar() == 'u' && (prev == 'e' || prev == 'a' || prev == 'q'))) {
        sb.append(umlaut); i += 2
      } else {
        sb.append(c); i++
      }
    }
    return sb.toString()
  }
}
