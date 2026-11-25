package de.juliandigitalproducts.notitocal

/**
 * Invented notifications (chats, mails, SMS, bookings, noise) with the expected result, written for a fixed clock:
 * Friday 20 November 2026, 10:00 Europe/Berlin. Used by [PrefilterSamplesTest] (rules only) and
 * [PipelineHarnessTest] (whole pipeline with fake targets).
 */
object MessageSamples {
  val ZONE: java.time.ZoneId = java.time.ZoneId.of("Europe/Berlin")
  val NOW: java.time.LocalDateTime = java.time.LocalDateTime.of(2026, 11, 20, 10, 0)

  data class Sample(
    val id: Int,
    val source: String,
    val text: String,
    /** Expected: an event suggestion (start/end) or nothing. */
    val event: Boolean,
    val start: String? = null,
    val end: String? = null,
    val allDay: Boolean = false,
    /** A word the title has to contain. */
    val title: String? = null,
    /** Wording only a model can judge reliably: listed, not asserted. */
    val aiPath: Boolean = false,
    /** Clear noise the pre-filter has to stop before a model sees it (default for negatives). */
    val preBlock: Boolean = !event && !aiPath,
  )

  /** The 30 invented messages of the pre-filter review (20.11.2026). */
  val base = listOf(
    Sample(1, "WhatsApp (de)", "Hey, treffen wir uns morgen um 15 Uhr im Café Lotte?", true, "2026-11-21T15:00", "2026-11-21T16:00", title = "Treffen"),
    Sample(2, "SMS Doctolib (de)", "Terminerinnerung: Ihr Termin bei Dr. Weber (Zahnarzt) am 25.11.2026 um 08:30 Uhr. Absage bitte bis 24h vorher.", true, "2026-11-25T08:30", "2026-11-25T09:30", title = "Termin"),
    Sample(3, "SMS Friseur (de)", "Hallo Julian, dein Termin beim Friseur ist am Dienstag, 24.11. um 15.30 Uhr bestätigt.", true, "2026-11-24T15:30", "2026-11-24T16:30", title = "Friseur"),
    Sample(4, "DHL (de)", "DHL: Ihr Paket wird morgen zwischen 14-16 Uhr zugestellt. Lieferfenster ändern unter dhl.de", true, "2026-11-21T14:00", "2026-11-21T16:00", title = "Paket"),
    Sample(5, "Gmail Lufthansa (de)", "Buchungsbestätigung Flug LH 1234 Frankfurt – Lissabon am 2026-12-03, Abflug 07:15, Gate B12", true, "2026-12-03T07:15", title = "Flug"),
    Sample(6, "DB Navigator (de)", "Ihre Reise: ICE 597 München Hbf → Berlin Hbf am 28.11.2026, Abfahrt 09:28, Gleis 14", true, "2026-11-28T09:28", title = "ICE"),
    Sample(7, "Gmail OpenTable (en)", "Your reservation at Trattoria Roma is confirmed: Sat, Nov 28 at 7:30 PM, table for 4.", true, "2026-11-28T19:30", title = "Trattoria"),
    Sample(8, "WhatsApp Gruppe (de)", "Ich lade euch zu meiner Geburtstagsfeier am Samstag, 5.12. ab 19 Uhr ein! 🎉🎂", true, "2026-12-05T19:00", title = "Geburtstag"),
    Sample(9, "Teams (de)", "Das Jour fixe wurde verschoben auf Montag 14:00–15:00.", true, "2026-11-23T14:00", "2026-11-23T15:00", title = "Jour fixe"),
    Sample(10, "Slack (en)", "Can we do the sprint review next Tuesday at 3pm?", true, "2026-11-24T15:00", title = "review"),
    Sample(11, "Telegram (de)", "übermorgen 18:00 Training in der Halle Nord, wer ist dabei?", true, "2026-11-22T18:00", title = "Training"),
    Sample(12, "Signal (de)", "Kontrolltermin in 2 Wochen um 9 Uhr, Praxis Dr. Klein", true, "2026-12-04T09:00", title = "Kontrolltermin"),
    Sample(13, "Outlook (de)", "Betriebsausflug am 11.12.2026 – ganztägig, bitte freihalten", true, "2026-12-11", allDay = true, title = "Betriebsausflug"),
    Sample(14, "Gmail (en)", "Webinar: Product launch on 2026-12-01 at 17:00 CET", true, "2026-12-01T17:00", title = "Webinar"),
    Sample(15, "SMS (fr)", "Rendez-vous chez le dentiste lundi à 10h30", true, "2026-11-23T10:30", title = "dentiste"),
    Sample(16, "WhatsApp (es)", "¿Quedamos el viernes 27 a las 20:00 para cenar?", true, "2026-11-27T20:00"),
    Sample(17, "Outlook (en)", "Meeting invitation: Q4 budget review, Dec 2, 2026 11:00-12:30, Room 4.12", true, "2026-12-02T11:00", "2026-12-02T12:30", title = "budget"),
    Sample(18, "SMS Praxis (de)", "Erinnerung: Ihre Physiotherapie morgen um 7:45. Praxis Bewegt", true, "2026-11-21T07:45", title = "Physiotherapie"),
    Sample(19, "WhatsApp (it)", "Ci vediamo domani alle 13 per pranzo?", true, "2026-11-21T13:00"),
    Sample(20, "SMS OTP (de)", "Dein Bestätigungscode lautet 482913. Gültig bis 10:15 Uhr.", false),
    Sample(21, "Shop-Push (de)", "Black Week Sale: 30% auf alles, nur bis 30.11.! Jetzt shoppen", false),
    Sample(22, "Gmail Newsletter (de)", "Unser Newsletter im November: Die 5 besten Tipps für den Winter. Hier abmelden.", false),
    Sample(23, "WhatsApp (en)", "call me at 5 if you want lol", false, aiPath = true),
    Sample(24, "PayPal (de)", "Sie haben 12,50 € an Max Mustermann gesendet. Zahlung vom 20.11.2026.", false),
    Sample(25, "WhatsApp (de)", "War gestern ein super Abend beim Konzert, danke euch! 😊", false),
    Sample(26, "UPS (de)", "Ihre Sendung 1Z999AA10123456784 ist unterwegs. Sendungsverfolgung unter ups.com", false),
    Sample(27, "WhatsApp Gruppe (de)", "😂😂😂 haha ja genau 👍", false),
    Sample(28, "Telegram (de)", "Neue Version 1.2.3 ist da, kostet nur 12.50, Infos unter 0171 2345678", false),
    Sample(29, "Banking (de)", "Kontostand: 1.234,56 €. Abbuchung Netflix 13,99 € am 19.11.", false),
    // A cancellation may reach a model (it answers NONE for cancelled events); the rules must not suggest an event.
    Sample(30, "Teams (de)", "Das Meeting morgen um 10 Uhr fällt leider aus.", false, preBlock = false),
  )

  /** Cases added in the second review: "zwischen … und …" and times written with a time zone. */
  val extra = listOf(
    Sample(31, "SMS Hermes (de)", "Hermes: Ihre Sendung wird übermorgen zwischen 14 und 16 Uhr zugestellt.", true, "2026-11-22T14:00", "2026-11-22T16:00", title = "Sendung"),
    Sample(32, "Gmail (en)", "Webinar invite: Kubernetes basics on 2026-12-08 at 17:00 EST", true, "2026-12-08T23:00", title = "Kubernetes"),
    Sample(33, "Slack (en)", "Call with the Seattle team Monday 9am PST, please join", true, "2026-11-23T18:00", title = "Seattle"),
  )

  /**
   * Delivery notices with a tracking sentence (third review): with a delivery day or window they are appointments,
   * without one (or already delivered) they stay noise for the pre-filter.
   */
  val delivery = listOf(
    Sample(34, "DHL (de)", "Ihr Paket kommt am 07.12. zwischen 10:00 und 12:00 Uhr. Sendungsverfolgung unter dhl.de/sendung", true, "2026-12-07T10:00", "2026-12-07T12:00", title = "Paket"),
    Sample(35, "Hermes (de)", "Ihre Sendung 12345678 wird am Montag, 23.11. zwischen 8 und 12 Uhr zugestellt. Sendungsverfolgung: myhermes.de/track", true, "2026-11-23T08:00", "2026-11-23T12:00", title = "zustellung"),
    Sample(36, "Amazon (en)", "Your package will arrive on Nov 30 between 2pm and 6pm. Track your package: https://amzn.eu/d/xyz", true, "2026-11-30T14:00", "2026-11-30T18:00", title = "package"),
    Sample(37, "DPD (en)", "DPD: your parcel will be delivered on 24.11.2026 between 09:00 and 10:00. Tracking number 01234567890123", true, "2026-11-24T09:00", "2026-11-24T10:00", title = "parcel"),
    Sample(38, "GLS (de)", "Zustellung Ihres Pakets voraussichtlich am 26.11.2026, 13:00–15:00 Uhr. Paket verfolgen: gls-pakete.de", true, "2026-11-26T13:00", "2026-11-26T15:00", title = "Paket"),
    Sample(39, "DHL (de)", "Ihr Paket wurde zugestellt. Sendungsverfolgung unter dhl.de", false),
    Sample(40, "Amazon (en)", "Your order has shipped! Track your package here: https://amzn.eu/d/abc", false),
    Sample(41, "DPD (de)", "Sendungsverfolgung: Ihr Paket ist im Paketzentrum angekommen. Tracking-Nummer 0123456789", false),
    Sample(42, "UPS (en)", "UPS Update: Your package was delivered today at 11:42. Tracking number 1Z999AA10123456784", false),
    Sample(43, "Shop-Push (de)", "Jetzt bestellen, Lieferung schon morgen zwischen 10 und 12 Uhr! 20% Rabatt mit Code WINTER", false),
    Sample(44, "Versand (de)", "Versandbestätigung: Ihre Bestellung wird in 3-5 Werktagen geliefert. Sendungsverfolgung unter dhl.de", false),
    Sample(45, "Shop (en)", "Order now and get delivery tomorrow between 8am and 12pm! Use promo code FAST", false),
  )

  val all = base + extra + delivery
}
