package de.juliandigitalproducts.notitocal.data

import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.SourceType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * RFC-4180 compliant CSV Exporter for messages, OCR texts, voice note transcripts,
 * and optional AI classification metadata.
 */
object CsvExporter {

  private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

  /**
   * Generates RFC-4180 compliant CSV string with UTF-8 BOM.
   *
   * @param entries List of [LogEntry] to export
   * @param includeAi If true, dynamically appends AI result columns:
   *                  Art, Titel, Startzeit, Endzeit, Dauer_Minuten, Ort, Konfidenz, KI_Begruendung
   */
  fun generateCsv(entries: List<LogEntry>, includeAi: Boolean): String {
    val sb = StringBuilder()
    // Prepend UTF-8 BOM for seamless Excel compatibility
    sb.append("\uFEFF")

    // Headers
    val headers = if (includeAi) {
      listOf(
        "ID",
        "Datum",
        "Quelle",
        "Paket",
        "Absender",
        "Nachrichtentext",
        "Medientyp",
        "Art",
        "Titel",
        "Startzeit",
        "Endzeit",
        "Dauer_Minuten",
        "Ort",
        "Konfidenz",
        "KI_Begruendung",
      )
    } else {
      listOf(
        "ID",
        "Datum",
        "Quelle",
        "Paket",
        "Absender",
        "Nachrichtentext",
        "Medientyp",
      )
    }
    sb.append(headers.joinToString(",") { escape(it) }).append("\r\n")

    for (entry in entries) {
      val mediaType = when (entry.source) {
        SourceType.SCREENSHOT, SourceType.SHARED_IMAGE -> "Bild / OCR"
        SourceType.VOICE_MEMO -> "Sprachnachricht"
        SourceType.WHATSAPP, SourceType.TELEGRAM, SourceType.TELEGRAM_API -> "Chat"
        SourceType.SMS_RCS -> "SMS / RCS"
        SourceType.IMAP -> "E-Mail"
        SourceType.NOTIFICATION -> "Benachrichtigung"
        SourceType.CLIPBOARD, SourceType.SHARED_TEXT -> "Text / Zwischenablage"
        SourceType.CALENDAR_ICS -> "Kalender ICS"
        SourceType.MANUAL -> "Manuell"
      }

      val row = mutableListOf(
        entry.id.toString(),
        DATE_FORMATTER.format(Instant.ofEpochMilli(entry.created)),
        entry.source.name,
        entry.packageName,
        entry.sender,
        entry.text,
        mediaType,
      )

      if (includeAi) {
        val d = entry.detection
        val durationMins = calculateDurationMinutes(d)
        row.add(d.kind.name)
        row.add(d.title)
        row.add(d.start.orEmpty())
        row.add(d.end.orEmpty())
        row.add(if (durationMins > 0) durationMins.toString() else "")
        row.add(d.location.orEmpty())
        row.add(String.format(java.util.Locale.US, "%.2f", d.confidence))
        row.add(d.reason)
      }

      sb.append(row.joinToString(",") { escape(it) }).append("\r\n")
    }

    return sb.toString()
  }

  private fun calculateDurationMinutes(d: Detection): Int {
    val s = d.startDateTime() ?: return 0
    val e = d.endDateTime() ?: return 0
    val mins = java.time.Duration.between(s, e).toMinutes().toInt()
    return if (mins > 0) mins else 0
  }

  /**
   * RFC-4180 Escaping:
   * - Fields containing commas, double-quotes, or line breaks must be enclosed in double quotes.
   * - Double-quote characters inside fields must be escaped by doubling them ("").
   */
  fun escape(field: String): String {
    val clean = field.replace("\r\n", "\n").replace("\r", "\n")
    val needsQuotes = clean.contains(',') || clean.contains('"') || clean.contains('\n') || clean.contains(';')
    return if (needsQuotes) {
      "\"" + clean.replace("\"", "\"\"") + "\""
    } else {
      clean
    }
  }
}
