package de.juliandigitalproducts.notitocal.capture

/**
 * Normalises transcripts exported by Anti-Vocale or similar offline speech tools.
 * Handles plain text, text with timestamps, SRT subtitles, and WebVTT subtitles,
 * stripping subtitle indices, timestamps, and speaker tags so the raw spoken text
 * can be cleanly processed by NotiToCal's rule engine and LLMs.
 */
object AntiVocaleParser {

  private val VTT_HEADER = Regex("""(?i)^\s*WEBVTT.*$""")
  private val VTT_METADATA = Regex("""(?i)^\s*(?:Kind|Language|Region|X-TIMESTAMP):.*$""")
  private val SUBTITLE_INDEX = Regex("""^\s*\d+\s*$""")
  private val TIME_ARROW = Regex("""\d{1,2}:\d{2}(?::\d{2})?[.,]\d{2,3}\s*-->\s*\d{1,2}:\d{2}(?::\d{2})?[.,]\d{2,3}.*""")
  private val BRACKET_TIMESTAMP = Regex("""\[\s*\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d{1,3})?\s*\]""")
  private val SPEAKER_TAG = Regex("""(?i)(?:\[?\b(?:Speaker|Sprecher)\s*\d+\]?:?|\b(?:Speaker|Sprecher)\s*\d+:\s*)""")

  fun cleanTranscript(raw: String): String {
    if (raw.isBlank()) return ""
    val lines = raw.lines()
    val kept = mutableListOf<String>()

    for (line in lines) {
      val trimmed = line.trim()
      if (trimmed.isEmpty()) continue
      if (VTT_HEADER.matches(trimmed)) continue
      if (VTT_METADATA.matches(trimmed)) continue
      if (SUBTITLE_INDEX.matches(trimmed)) continue
      if (TIME_ARROW.matches(trimmed)) continue

      var cleaned = trimmed
      cleaned = BRACKET_TIMESTAMP.replace(cleaned, "")
      cleaned = SPEAKER_TAG.replace(cleaned, "")
      cleaned = cleaned.trim()

      if (cleaned.isNotEmpty()) {
        kept.add(cleaned)
      }
    }

    return kept.joinToString(" ").replace(Regex("""\s{2,}"""), " ").trim()
  }
}
