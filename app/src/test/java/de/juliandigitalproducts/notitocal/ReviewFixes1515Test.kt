package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.capture.CapturePolicy
import de.juliandigitalproducts.notitocal.capture.ImapClient
import de.juliandigitalproducts.notitocal.capture.MailText
import de.juliandigitalproducts.notitocal.capture.SmsRcsSource
import de.juliandigitalproducts.notitocal.capture.WhatsAppSource
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.diagnostics.DiagnosticLogger
import de.juliandigitalproducts.notitocal.output.CalendarDuplicates
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.CalendarEventItem
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher.RemovalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code review 2026-11-08: capture opt-in, duplicate merge safety, log redaction and IMAP decoding. */
class ReviewFixes1515Test {
  private val sms = SmsRcsSource.PACKAGES.first()

  @Test fun appRemovedFromListIsNeverRead() {
    val s = AppSettings(monitoredApps = setOf("com.example.other"))
    assertFalse(CapturePolicy.isActive(s, WhatsAppSource.PKG_PERSONAL))
    assertFalse(CapturePolicy.isActive(s, WhatsAppSource.PKG_BUSINESS))
    assertFalse(CapturePolicy.isActive(s, sms))
  }

  @Test fun sourceSwitchesStillApply() {
    val apps = setOf(WhatsAppSource.PKG_PERSONAL, WhatsAppSource.PKG_BUSINESS, sms, "com.example.mail")
    val on = AppSettings(monitoredApps = apps)
    assertTrue(CapturePolicy.isActive(on, WhatsAppSource.PKG_PERSONAL))
    assertTrue(CapturePolicy.isActive(on, sms))
    assertTrue(CapturePolicy.isActive(on, "com.example.mail"))
    val off = on.copy(whatsappPersonalEnabled = false, smsRcsEnabled = false, notificationsEnabled = false)
    assertFalse(CapturePolicy.isActive(off, WhatsAppSource.PKG_PERSONAL))
    assertTrue(CapturePolicy.isActive(off, WhatsAppSource.PKG_BUSINESS))
    assertFalse(CapturePolicy.isActive(off, sms))
    assertFalse(CapturePolicy.isActive(off, "com.example.mail"))
  }

  @Test fun notificationPicturesNeedTheirOwnOptIn() {
    assertFalse(AppSettings().notificationImagesToAi)
    assertFalse(CapturePolicy.sendPictureToAi(AppSettings(screenshotVision = true)))
  }

  @Test fun seriesAndEventsWithGuestsAreOnlyMarked() {
    val single = CalendarEventItem(eventId = 1, calendarId = 1, title = "Zahnarzt", startMillis = 0)
    assertFalse(CalendarDuplicates.onlyMark(single, RemovalMode.DELETE))
    assertTrue(CalendarDuplicates.onlyMark(single, RemovalMode.MARK_DUPLICATE))
    assertTrue(CalendarDuplicates.onlyMark(single.copy(rrule = "FREQ=WEEKLY"), RemovalMode.DELETE))
    assertTrue(CalendarDuplicates.onlyMark(single.copy(attendees = listOf("anna@example.com")), RemovalMode.DELETE))
  }

  @Test fun logRedactsTokensAndPhoneNumbers() {
    // Fake-Wert, zur Laufzeit gebaut, damit kein Secret-Muster im Quelltext steht
    val fakeBotId = "1234" + "56789"
    val fakeToken = "A" + "x".repeat(33)
    val out = DiagnosticLogger.sanitize(
      "GET https://api.telegram.org/bot$fakeBotId:$fakeToken/getUpdates · Bearer abcdefghijklmnop · Code für +49 151 12345678 · 2026-11-08 · 523 ms",
    )
    assertFalse(out.contains(fakeToken))
    assertTrue(out.contains("[REDACTED_BOT_TOKEN]"))
    assertFalse(out.contains("abcdefghijklmnop"))
    assertFalse(out.contains("151 12345678"))
    assertTrue(out.contains("2026-11-08"))
    assertTrue(out.contains("523 ms"))
  }

  @Test fun encodedHeadersAreDecoded() {
    assertEquals("Terminbestätigung Zahnarzt", MailText.decodeHeader("=?UTF-8?B?VGVybWluYmVzdMOkdGlndW5n?= =?UTF-8?Q?_Zahnarzt?="))
    assertEquals("Grüße", MailText.decodeHeader("=?ISO-8859-1?Q?Gr=FC=DFe?="))
    assertEquals("plain subject", MailText.decodeHeader("plain subject"))
  }

  @Test fun multipartPrefersPlainTextAndDecodesQuotedPrintable() {
    val header = "Content-Type: multipart/alternative; boundary=\"b1\"\n"
    val body = """
      |--b1
      |Content-Type: text/plain; charset=UTF-8
      |Content-Transfer-Encoding: quoted-printable
      |
      |Termin am Montag um 10 Uhr, Stra=C3=9Fe 5
      |--b1
      |Content-Type: text/html; charset=UTF-8
      |
      |<p>Termin am Montag</p>
      |--b1--
    """.trimMargin()
    assertEquals("Termin am Montag um 10 Uhr, Straße 5", MailText.bodyText(header, body))
  }

  @Test fun htmlOnlyAndBase64BodiesBecomeText() {
    val html = "Content-Type: text/html; charset=UTF-8\nContent-Transfer-Encoding: base64\n"
    val encoded = java.util.Base64.getEncoder().encodeToString("<html><style>p{}</style><p>Abholung&nbsp;Freitag</p><br>8 Uhr</html>".toByteArray())
    assertEquals("Abholung Freitag 8 Uhr", MailText.bodyText(html, encoded).replace(Regex("\\s+"), " ").trim())
  }

  @Test fun fetchResponseIsSplitIntoHeaderAndBody() {
    val lines = listOf(
      "* 3 FETCH (UID 812 BODY[HEADER.FIELDS (FROM SUBJECT CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] {120}",
      "From: =?UTF-8?Q?Praxis_M=C3=BCller?= <info@example.com>",
      "Subject: Ihr Termin",
      "Content-Type: text/plain; charset=UTF-8",
      "",
      " BODY[TEXT]<0> {40}",
      "Ihr Termin ist am 12.11. um 9:30 Uhr.",
      ")",
      "F3 OK FETCH completed",
    )
    val msg = ImapClient.parseFetch(lines)
    assertNotNull(msg)
    assertEquals("812", msg!!.uid)
    assertEquals("Praxis Müller <info@example.com>", msg.from)
    assertEquals("Ihr Termin", msg.subject)
    assertEquals("Ihr Termin ist am 12.11. um 9:30 Uhr.", msg.body)
  }
}
