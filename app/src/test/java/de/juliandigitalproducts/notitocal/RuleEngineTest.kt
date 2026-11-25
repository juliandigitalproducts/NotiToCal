package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.analysis.LlmJson
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.analysis.TextNormalizer
import de.juliandigitalproducts.notitocal.capture.IcsCalendarSource
import de.juliandigitalproducts.notitocal.capture.SmsRcsSource
import de.juliandigitalproducts.notitocal.capture.TelegramUserApiSource
import de.juliandigitalproducts.notitocal.capture.VoiceNotes
import de.juliandigitalproducts.notitocal.capture.WhatsAppSource
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.FilterListMode
import de.juliandigitalproducts.notitocal.data.RoutingRule
import de.juliandigitalproducts.notitocal.data.RuleKind
import de.juliandigitalproducts.notitocal.data.TelegramDialogItem
import de.juliandigitalproducts.notitocal.data.TelegramDialogKind
import de.juliandigitalproducts.notitocal.analysis.Analyzer
import de.juliandigitalproducts.notitocal.analysis.Decision
import de.juliandigitalproducts.notitocal.data.ActionMode
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.pipeline.Router
import de.juliandigitalproducts.notitocal.pipeline.Schedule
import de.juliandigitalproducts.notitocal.data.AnalysisSchedule
import de.juliandigitalproducts.notitocal.model.Why
import de.juliandigitalproducts.notitocal.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RuleEngineTest {
  // Saturday, 31 October 2026, 10:00
  private val now = LocalDateTime.of(2026, 10, 31, 10, 0)

  @Test fun germanTomorrowWithTime() {
    val w = DateParser.parse("Treffen wir uns morgen um 15 Uhr im Café Central?", now)
    assertEquals(LocalDate.of(2026, 11, 1), w.date)
    assertEquals(LocalTime.of(15, 0), w.time)
  }

  @Test fun guterMorgenIsNotTomorrow() {
    val w = DateParser.parse("Guten Morgen! Wie geht's?", now)
    assertNull(w.date)
  }

  @Test fun englishWeekdayPm() {
    val w = DateParser.parse("Dinner on Friday at 7pm?", now)
    assertEquals(LocalDate.of(2026, 11, 6), w.date)
    assertEquals(LocalTime.of(19, 0), w.time)
  }

  @Test fun numericDateAndRange() {
    val w = DateParser.parse("Besprechung am 12.11. von 14:00 bis 15:30", now)
    assertEquals(LocalDate.of(2026, 11, 12), w.date)
    assertEquals(LocalTime.of(14, 0), w.time)
    assertEquals(LocalTime.of(15, 30), w.endTime)
  }

  @Test fun pastDateWithoutYearRollsOver() {
    val w = DateParser.parse("Termin am 3. März um 9 Uhr", now)
    assertEquals(LocalDate.of(2027, 3, 3), w.date)
    assertEquals(LocalTime.of(9, 0), w.time)
  }

  @Test fun frenchHour() {
    val w = DateParser.parse("Rendez-vous demain à 15h30 chez le dentiste", now)
    assertEquals(LocalDate.of(2026, 11, 1), w.date)
    assertEquals(LocalTime.of(15, 30), w.time)
  }

  @Test fun spanishMananaMorning() {
    val w = DateParser.parse("Nos vemos mañana por la mañana", now)
    assertEquals(LocalDate.of(2026, 11, 1), w.date)
    assertEquals(LocalTime.of(9, 0), w.time)
    assertTrue(w.vagueTime)
  }

  @Test fun italianMonthName() {
    val w = DateParser.parse("Appuntamento il 5 dicembre alle 10:15", now)
    assertEquals(LocalDate.of(2026, 12, 5), w.date)
    assertEquals(LocalTime.of(10, 15), w.time)
  }

  @Test fun polishDayAfterTomorrow() {
    val w = DateParser.parse("Spotkanie pojutrze o 18:00", now)
    assertEquals(LocalDate.of(2026, 11, 2), w.date)
    assertEquals(LocalTime.of(18, 0), w.time)
  }

  @Test fun germanHalf() {
    val w = DateParser.parse("Kommst du morgen um halb 4?", now)
    assertEquals(LocalTime.of(15, 30), w.time)
  }

  @Test fun deadlineDetection() {
    val w = DateParser.parse("Bitte die Rechnung bis Freitag bezahlen", now)
    assertTrue(w.deadline)
    assertEquals(LocalDate.of(2026, 11, 6), w.date)
  }

  @Test fun inThreeDays() {
    val w = DateParser.parse("Pick up the package in 3 days", now)
    assertEquals(LocalDate.of(2026, 11, 3), w.date)
  }

  @Test fun priceIsNotATime() {
    val w = DateParser.parse("Das kostet 15.50 Euro", now)
    assertNull(w.time)
  }

  @Test fun eventDetected() {
    val d = RuleEngine.analyze("Hey, treffen wir uns morgen um 15 Uhr im Café Central?", "Anna", now)
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("2026-11-01T15:00", d.start)
    assertEquals("2026-11-01T16:00", d.end)
    assertTrue(d.confidence > 0.7)
    assertNotNull(d.location)
    assertFalse(d.title, d.title.contains(" um "))
  }

  @Test fun taskDetected() {
    val d = RuleEngine.analyze("Vergiss nicht, morgen Milch zu kaufen!", "Mama", now)
    assertEquals(Kind.TASK, d.kind)
    assertEquals("2026-11-01", d.due)
    assertFalse(d.title.lowercase().startsWith("vergiss"))
  }

  @Test fun englishTask() {
    val d = RuleEngine.analyze("Can you please send me the report by Monday?", "Boss", now)
    assertEquals(Kind.TASK, d.kind)
    assertEquals("2026-11-02", d.due)
  }

  @Test fun englishTaskTitleHasNoStrayPunctuation() {
    val d = RuleEngine.analyze("Don't forget to pay the electricity bill by Friday, it's urgent!", "", now)
    assertEquals(Kind.TASK, d.kind)
    assertEquals("2026-11-06", d.due)
    assertEquals(3, d.priority)
    assertFalse(d.title, d.title.contains(" ,"))
  }

  @Test fun frenchAndPolishEvents() {
    val fr = RuleEngine.analyze("Rendez-vous chez le dentiste le 12 novembre à 10h30", "", now)
    assertEquals(Kind.EVENT, fr.kind)
    assertEquals("2026-11-12T10:30", fr.start)
    val pl = RuleEngine.analyze("Spotkanie w piątek o 18:00 w biurze", "", now)
    assertEquals(Kind.EVENT, pl.kind)
    assertEquals("2026-11-06T18:00", pl.start)
  }

  @Test fun smallTalkIgnored() {
    assertEquals(Kind.NONE, RuleEngine.analyze("Haha ja voll lustig 😂", "Tom", now).kind)
    assertEquals(Kind.NONE, RuleEngine.analyze("Ok, danke dir!", "Tom", now).kind)
  }

  @Test fun promotionIgnored() {
    val d = RuleEngine.analyze("Nur heute: 30% Rabatt auf alles! Code: SALE30", "Shop", now)
    assertEquals(Kind.NONE, d.kind)
  }

  @Test fun otpIgnored() {
    val d = RuleEngine.analyze("Your verification code is 482913. It expires in 10 minutes.", "Bank", now)
    assertEquals(Kind.NONE, d.kind)
  }

  @Test fun cancelledMeetingIsNotEvent() {
    val d = RuleEngine.analyze("Das Meeting morgen um 10 Uhr fällt aus.", "Chef", now)
    assertTrue(d.kind != Kind.EVENT || d.confidence < 0.5)
  }

  @Test fun llmJsonParsing() {
    val raw = "Sure! ```json\n{\"kind\":\"EVENT\",\"title\":\"Zahnarzt\",\"start\":\"2026-11-03T09:30\",\"end\":null,\"all_day\":false,\"confidence\":0.92,\"extra\":1}\n```"
    val d = LlmJson.parse(raw, "test")!!
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("Zahnarzt", d.title)
    assertEquals(0.92, d.confidence, 0.001)
  }

  @Test fun llmJsonLowercaseKind() {
    val d = LlmJson.parse("{\"kind\":\"task\",\"title\":\"Milch\",\"due\":\"2026-11-01\",\"confidence\":\"0.8\"}", "test")!!
    assertEquals(Kind.TASK, d.kind)
    assertEquals(0.8, d.confidence, 0.001)
  }

  // ---- Umlauts / encoding

  @Test fun asciiUmlautsAreResolved() {
    assertEquals("Treffen übermorgen in München", TextNormalizer.normalize("Treffen uebermorgen in Muenchen"))
    assertEquals("Bitte bis nächsten Freitag zurückrufen", TextNormalizer.normalize("Bitte bis naechsten Freitag zurueckrufen"))
    // Words that really contain "ue" stay as they are.
    assertEquals("Das ist teuer, Steuer und Feuer, Quelle, aktuell", TextNormalizer.normalize("Das ist teuer, Steuer und Feuer, Quelle, aktuell"))
  }

  @Test fun encodingIsRepaired() {
    assertEquals("Zahnärztin Müller", TextNormalizer.fixEncoding("Zahn&auml;rztin M&#252;ller"))
    assertEquals("Frühstück", TextNormalizer.fixEncoding("FrÃ¼hstÃ¼ck"))
    assertEquals("Grüße", TextNormalizer.fixEncoding("Gru\u0308\u00dfe"))
    assertEquals("Café", TextNormalizer.fixEncoding("Cafe\u0301"))
  }

  @Test fun asciiUmlautDateIsParsed() {
    val d = RuleEngine.analyze("Treffen wir uns uebermorgen um 18 Uhr im Buero?", "Anna", now)
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("2026-11-02T18:00", d.start)
    assertTrue(d.title, d.title.contains("Büro"))
  }

  // ---- Event / task separation

  @Test fun errandWithClockTimeStaysTask() {
    val d = RuleEngine.analyze("Kannst du morgen um 17 Uhr Brot kaufen?", "Mama", now)
    assertEquals(Kind.TASK, d.kind)
  }

  @Test fun appointmentInReminderStaysEvent() {
    val d = RuleEngine.analyze("Denk dran, morgen um 10 Uhr ist der Zahnarzttermin!", "Mama", now)
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("2026-11-01T10:00", d.start)
  }

  @Test fun englishErrandWithTime() {
    val d = RuleEngine.analyze("Please pay the rent tomorrow at 9am", "", now)
    assertEquals(Kind.TASK, d.kind)
  }

  // ---- Strictness: auto add even when unsure

  @Test fun autoAddUnsure() {
    val d = Detection(Kind.TASK, title = "Milch", due = "2026-11-01", confidence = 0.65)
    val s = AppSettings(taskMode = ActionMode.AUTO, taskMinConfidence = 0.6, autoAddConfidence = 0.85)
    assertEquals(Decision.ASK, Analyzer.decide(d, "x", s, now).decision)
    assertEquals(Decision.AUTO, Analyzer.decide(d, "x", s.copy(autoAddUnsure = true), now).decision)
  }

  // ---- Routing

  @Test fun routingBySenderKeywordAndCategory() {
    val work = RoutingRule(id = "w", name = "Arbeit", senders = "Chef", keywords = "meeting, kunde")
    val family = RoutingRule(id = "f", name = "Familie", description = "Kinder, Schule")
    val sport = RoutingRule(id = "s", name = "Sport", kind = RuleKind.TASK, keywords = "training")
    val rules = listOf(work, family, sport)
    val ev = Detection(Kind.EVENT, title = "Abendessen", start = "2026-11-01T19:00")
    assertEquals("w", Router.match(rules, ev, "Essen?", "Chef Müller")?.id)
    assertEquals("w", Router.match(rules, ev, "Kunde kommt", "Anna")?.id)
    assertEquals("f", Router.match(rules, ev.copy(category = "familie"), "Elternabend", "Anna")?.id)
    // A task-only rule does not catch events.
    assertNull(Router.match(rules, ev, "Training am Montag", "Tom"))
    val s = Router.apply(AppSettings(calendarId = 1), work.copy(calendarId = 7))
    assertEquals(7L, s.calendarId)
  }

  @Test fun voiceMessageNotifications() {
    assertTrue(VoiceNotes.isVoiceMessage("🎤 Sprachnachricht (0:07)"))
    assertTrue(VoiceNotes.isVoiceMessage("Voice message (1:12)"))
    assertTrue(VoiceNotes.isVoiceMessage("🎤 Audio"))
    assertFalse(VoiceNotes.isVoiceMessage("Hast du die Sprachnachricht von gestern gehört?"))
    assertFalse(VoiceNotes.isVoiceMessage("Treffen morgen um 15 Uhr"))
  }

  @Test fun reasoningOutputParsed() {
    val d = LlmJson.parse("<think>maybe {kind} event?</think>{\"kind\":\"EVENT\",\"title\":\"Gesch&auml;ftsessen\",\"start\":\"2026-11-03T12:00\",\"confidence\":0.9,\"category\":\"Arbeit\"}", "t")!!
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("Geschäftsessen", d.title)
    assertEquals("Arbeit", d.category)
  }

  // ---- Explained decisions

  @Test fun decisionReasons() {
    val s = AppSettings(taskMode = ActionMode.AUTO, eventMode = ActionMode.ASK, taskMinConfidence = 0.6, autoAddConfidence = 0.85)
    val task = Detection(Kind.TASK, title = "Milch", due = "2026-11-01", confidence = 0.7)
    val low = Analyzer.decide(task, "x", s, now)
    assertEquals(Why.ASK_CONFIDENCE, low.why)
    assertEquals(0.85, low.limit!!, 0.001)
    assertEquals(Why.AUTO, Analyzer.decide(task.copy(confidence = 0.9), "x", s, now).why)
    assertEquals(Why.BELOW_MIN, Analyzer.decide(task.copy(confidence = 0.3), "x", s, now).why)
    val ev = Detection(Kind.EVENT, title = "Arzt", start = "2026-11-02T10:00", confidence = 0.95)
    assertEquals(Why.ASK_MODE, Analyzer.decide(ev, "x", s, now).why)
    assertEquals(Why.PAST, Analyzer.decide(ev.copy(start = "2026-10-01T10:00"), "x", s, now).why)
    assertEquals(Why.BLOCKED_KEYWORD, Analyzer.decide(ev, "Werbung", s.copy(blockKeywords = "werbung"), now).why)
    assertEquals(Why.NOTHING, Analyzer.decide(Detection(Kind.NONE), "x", s, now).why)
    assertEquals(Why.PREFILTER, Analyzer.decide(Detection(Kind.NONE, why = "PREFILTER"), "x", s, now).why)
  }

  // ---- Battery: schedule and urgency (now = Sat 31 Oct 2026, 10:00)

  @Test fun nextRunTimes() {
    val s = AppSettings() // default: collected 3× daily at 08:00, 13:00, 18:00
    assertEquals(LocalDateTime.of(2026, 10, 31, 13, 0), Schedule.nextRun(s, now))
    assertEquals(LocalDateTime.of(2026, 11, 1, 8, 0), Schedule.nextRun(s, LocalDateTime.of(2026, 10, 31, 19, 30)))
    assertEquals(LocalDateTime.of(2026, 10, 31, 11, 0), Schedule.nextRun(s.copy(analysisSchedule = AnalysisSchedule.HOURLY), now))
    assertEquals(LocalDateTime.of(2026, 10, 31, 18, 30), Schedule.nextRun(s.copy(analysisSchedule = AnalysisSchedule.DAILY, analysisTimes = listOf(18 * 60 + 30)), now))
    assertNull(Schedule.nextRun(s.copy(analysisSchedule = AnalysisSchedule.IMMEDIATE), now))
  }

  @Test fun urgentMessages() {
    val next = LocalDateTime.of(2026, 10, 31, 13, 0)
    assertTrue(Schedule.isUrgent("Bin in 20 Minuten da", now, next))
    assertTrue(Schedule.isUrgent("Treffen heute um 12 Uhr?", now, next))
    assertTrue(Schedule.isUrgent("Treffen um 14 Uhr?", now, next)) // today, before next run + 1 h
    assertFalse(Schedule.isUrgent("Zahnarzt nächsten Freitag um 10", now, next))
    assertFalse(Schedule.isUrgent("Haha ok", now, next))
    // Next run tomorrow 08:00: "tomorrow at 9" cannot wait for it
    assertTrue(Schedule.isUrgent("Morgen um 9 Uhr Meeting", now, LocalDateTime.of(2026, 11, 1, 8, 0)))
  }

  @Test fun multiDayDateRanges() {
    val r1 = DateParser.parse("Workshop vom 12. bis 15. November", now)
    assertEquals(LocalDate.of(2026, 11, 12), r1.date)
    assertEquals(LocalDate.of(2026, 11, 15), r1.endDate)

    val r2 = DateParser.parse("Urlaub vom 10.-14.08.2027", now)
    assertEquals(LocalDate.of(2027, 8, 10), r2.date)
    assertEquals(LocalDate.of(2027, 8, 14), r2.endDate)

    val r3 = DateParser.parse("Konferenz Freitag bis Sonntag", now)
    assertEquals(LocalDate.of(2026, 11, 6), r3.date)
    assertEquals(LocalDate.of(2026, 11, 8), r3.endDate)

    val det = RuleEngine.analyze("Konferenz vom 12. bis 15. November in Berlin", "Boss", now, 60)
    assertEquals(Kind.EVENT, det.kind)
    assertEquals("2026-11-12", det.start)
    assertEquals("2026-11-15", det.end)
    assertTrue(det.allDay)
  }

  @Test fun settingsBackupSerialization() {
    val backup = de.juliandigitalproducts.notitocal.data.SettingsBackup(
      settings = AppSettings(compactOverview = true, widgetMemosOnlyActionable = true),
      secrets = mapOf("test_key" to "secret_123"),
    )
    val json = de.juliandigitalproducts.notitocal.data.AppJson.encodeToString(
      de.juliandigitalproducts.notitocal.data.SettingsBackup.serializer(), backup
    )
    assertTrue(json.contains("secret_123"))
    assertTrue(json.contains("widgetMemosOnlyActionable"))
    val restored = de.juliandigitalproducts.notitocal.data.AppJson.decodeFromString(
      de.juliandigitalproducts.notitocal.data.SettingsBackup.serializer(), json
    )
    assertTrue(restored.settings.compactOverview)
    assertTrue(restored.settings.widgetMemosOnlyActionable)
    assertEquals("secret_123", restored.secrets["test_key"])
  }

  // C6: a place alone is no appointment, a complete address only optionally a quiet suggestion.
  @Test fun placeWithoutDateIsNoEvent() {
    listOf("Bin gerade im Büro", "Milch bei Aldi kaufen", "Treffpunkt: Marienplatz 1, München", "Wir sind in der Bahnhofstraße 42",
      "Hier ist der Ort: https://maps.google.com/?q=52.5200,13.4050").forEach { t ->
      val d = RuleEngine.analyze(t, "Anna", now, 60)
      assertTrue("$t -> ${d.kind}", d.kind != Kind.EVENT)
      assertTrue(t, Analyzer.decide(d, t, AppSettings(), now).decision != Decision.AUTO)
    }
  }

  @Test fun fullAddressNeedsStreetNumberAndCity() {
    assertEquals("Marienplatz 1, München", RuleEngine.fullAddress("Treffpunkt: Marienplatz 1, München"))
    assertEquals("Bahnhofstraße 42, 10115 Berlin", RuleEngine.fullAddress("Wir sind in der Bahnhofstraße 42, 10115 Berlin."))
    assertEquals("Via della Spiga 5, Milano", RuleEngine.fullAddress("Ci vediamo in Via della Spiga 5, Milano"))
    assertEquals("ul. Długa 5, 80-831 Gdańsk", RuleEngine.fullAddress("Adres: ul. Długa 5, 80-831 Gdańsk"))
    assertEquals("221B Baker Street, London", RuleEngine.fullAddress("Come to 221B Baker Street, London"))
    assertEquals("12 rue de la Paix, 75002 Paris", RuleEngine.fullAddress("RDV au 12 rue de la Paix, 75002 Paris"))
    listOf("Wir sind in der Bahnhofstraße 42", "Bin im Büro", "Milch bei Aldi", "https://maps.google.com/?q=52.52,13.40", "Ich habe 3 Äpfel")
      .forEach { assertNull(it, RuleEngine.fullAddress(it)) }
  }

  @Test fun addressSuggestionIsOptionalQuietAndNeverAutomatic() {
    val text = "Treffpunkt: Marienplatz 1, München"
    val on = AppSettings(addressSuggestions = true, eventMode = ActionMode.AUTO, autoAddUnsure = true, autoAddConfidence = 0.1, eventMinConfidence = 0.1)
    val det = RuleEngine.analyze(text, "Friend", now, 60, addressSuggestions = true)
    assertEquals(Kind.EVENT, det.kind)
    assertEquals("2026-10-31T12:00", det.start)
    assertEquals("Marienplatz 1, München", det.location)
    assertEquals(Why.LOCATION_ONLY.name, det.why)
    assertNull(det.category)
    assertTrue(det.confidence <= RuleEngine.ADDRESS_CONFIDENCE)
    val dec = Analyzer.decide(det, text, on, now)
    assertEquals(Decision.ASK, dec.decision)
    assertEquals(Why.LOCATION_ONLY, dec.why)
    // Setting off: no suggestion.
    assertEquals(Kind.NONE, RuleEngine.analyze(text, "Friend", now, 60).kind)
    assertEquals(Decision.IGNORE, Analyzer.decide(det, text, AppSettings(), now).decision)
    // Street without city: no suggestion even when on.
    assertEquals(Kind.NONE, RuleEngine.analyze("Wir sind in der Bahnhofstraße 42", "A", now, 60, addressSuggestions = true).kind)
  }

  @Test fun undatedAiEventAtAddressBecomesSuggestion() {
    val ai = Detection(Kind.EVENT, title = "Treffen", location = "Bahnhofstraße 42, 10115 Berlin", confidence = 0.95, engine = "cloud")
    val on = AppSettings(addressSuggestions = true, eventMode = ActionMode.AUTO, autoAddUnsure = true)
    val dec = Analyzer.decide(ai, "Treffen Bahnhofstraße 42, 10115 Berlin", on, now)
    assertEquals(Decision.ASK, dec.decision)
    assertEquals("2026-10-31T12:00", dec.detection.start)
    assertTrue(dec.detection.confidence <= RuleEngine.ADDRESS_CONFIDENCE)
    // Off: an undated event becomes a task as before.
    assertEquals(Kind.TASK, Analyzer.decide(ai, "x", AppSettings(), now).detection.kind)
  }

  @Test fun placeStillFillsLocationOfDatedEvent() {
    val d = RuleEngine.analyze("Morgen um 15 Uhr im Café Central", "", now, 60)
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("Café Central", d.location)
  }

  @Test fun greetedRecipientIsNeverLocationAndSenderUsedForInvite() {
    val msg = "Hallo lieber Julian, wir möchten euch einladen am 14.11.26 um 14:00 Uhr bei uns zum Mittagessen! Wann passt das bei euch? Viele Grüße Max Mustermann"
    val d = RuleEngine.analyze(msg, "Marion Franziska", now, 60)
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("2026-11-14T14:00", d.start)
    assertFalse("Location must not be recipient Julian, was: ${d.location}", d.location?.contains("Julian", ignoreCase = true) == true)
    assertEquals("Max Mustermann", d.location)
    assertFalse("Title must not say 'bei Julian': ${d.title}", d.title.contains("bei Julian", ignoreCase = true))

    // Also verify post-LLM sanitization when an LLM wrongly outputs location="Julian" and title="Mittagessenbesuch bei Julian"
    val badLlm = Detection(
      kind = Kind.EVENT,
      title = "Mittagessenbesuch bei Julian",
      description = msg,
      start = "2026-11-14T14:00",
      location = "Julian",
      confidence = 0.85,
      engine = "cloud",
    )
    val sanitized = RuleEngine.sanitizeDetection(badLlm, msg, "Marion Franziska")
    assertEquals("Max Mustermann", sanitized.location)
    assertFalse("Sanitized title must not contain 'bei Julian': ${sanitized.title}", sanitized.title.contains("bei Julian", ignoreCase = true))
  }

  @Test fun explicitAtRecipientKeepsLocation() {
    val msg = "Hallo lieber Julian, wir treffen uns morgen um 15 Uhr bei dir zu Hause!"
    assertTrue(RuleEngine.isExplicitlyAtRecipient(msg))
  }

  @Test fun selfScreenshotAndDebugTextIgnoredAndStripped() {
    val selfOcr = """
      < Termin
      Automatisch eingetragen: Die Sicherheit von 85 % erreicht deine Schwelle von 85 % für automatisches Eintragen.
      Titel
      Mittagessenbesuch
      Samstag 14.11.2026
      ✓ DEVICE_CALENDAR: event #764
      {"kind": "EVENT", "title": "Mittagessenbesuch", "confidence": 0.85}
    """.trimIndent()
    assertTrue(RuleEngine.isSelfOrDebugText(selfOcr))
    assertEquals(Kind.NONE, RuleEngine.analyze(selfOcr, "", now, 60).kind)

    val dirtyDetection = Detection(
      kind = Kind.EVENT,
      title = "< Termin\nAutomatisch eingetragen: Die Sicherheit von 85 % erreicht deine Schwelle von 85 %",
      description = "Mittagessen am Samstag\n—— NotiToCal ——\n✓ DEVICE_CALENDAR: event #764",
      start = "2026-11-14T14:00",
      confidence = 0.85,
    )
    val clean = RuleEngine.sanitizeDetection(dirtyDetection, "Wir treffen uns am 14.11.2026 um 14:00 Uhr zum Mittagessen", "Anna")
    assertFalse(clean.title.contains("Automatisch eingetragen"))
    assertFalse(clean.description.contains("DEVICE_CALENDAR"))
    assertFalse(clean.description.contains("—— NotiToCal ——"))
  }

  @Test fun telegramUserApiFilterAllExceptAndOnlySelected() {
    val dialogs = listOf(
      TelegramDialogItem(id = "1", title = "Krypto Spam Gruppe", kind = TelegramDialogKind.GROUP, selected = true),
      TelegramDialogItem(id = "2", title = "Familie Daniels", kind = TelegramDialogKind.GROUP, selected = false),
      TelegramDialogItem(id = "3", title = "Max Mustermann", username = "@maxm", kind = TelegramDialogKind.PERSON, selected = true),
    )

    // Mode 1: ALL_EXCEPT ("Alle außer") -> selected items are excluded, unselected/other items are allowed
    assertFalse(TelegramUserApiSource.isAllowedByFilter("User @ Krypto Spam Gruppe", "Krypto Spam Gruppe", FilterListMode.ALL_EXCEPT, dialogs))
    assertFalse(TelegramUserApiSource.isAllowedByFilter("Max Mustermann", "Max Mustermann", FilterListMode.ALL_EXCEPT, dialogs))
    assertTrue(TelegramUserApiSource.isAllowedByFilter("Anna @ Familie Daniels", "Familie Daniels", FilterListMode.ALL_EXCEPT, dialogs))
    assertTrue(TelegramUserApiSource.isAllowedByFilter("Lisa", "Lisa", FilterListMode.ALL_EXCEPT, dialogs))

    // Mode 2: ONLY_SELECTED ("Nur folgende") -> ONLY selected items are allowed
    assertTrue(TelegramUserApiSource.isAllowedByFilter("Max Mustermann", "Max Mustermann", FilterListMode.ONLY_SELECTED, dialogs))
    assertFalse(TelegramUserApiSource.isAllowedByFilter("Anna @ Familie Daniels", "Familie Daniels", FilterListMode.ONLY_SELECTED, dialogs))
    assertFalse(TelegramUserApiSource.isAllowedByFilter("Lisa", "Lisa", FilterListMode.ONLY_SELECTED, dialogs))
  }

  @Test fun telegramUserApiFloodRateLimiterDropsHighFrequencyGroup() {
    TelegramUserApiSource.resetRateLimits()
    val t0 = 1_700_000_000_000L
    // Allow up to 5 messages per minute in "MegaGruppe"
    for (i in 1..5) {
      assertFalse(TelegramUserApiSource.isRateLimited("MegaGruppe", maxPerMinute = 5, nowMs = t0 + i * 1_000L))
    }
    // 6th message within the same minute must be rate-limited
    assertTrue(TelegramUserApiSource.isRateLimited("MegaGruppe", maxPerMinute = 5, nowMs = t0 + 10_000L))
    // After 61 seconds, messages are allowed again
    assertFalse(TelegramUserApiSource.isRateLimited("MegaGruppe", maxPerMinute = 5, nowMs = t0 + 62_000L))
  }

  @Test fun whatsappPersonalAndBusinessAndSmsAndIcsSources() {
    val s = AppSettings(
      whatsappEnabled = true,
      whatsappPersonalEnabled = true,
      whatsappBusinessEnabled = true,
      whatsappFilterMode = FilterListMode.ALL_EXCEPT,
      whatsappFilterContacts = "Stummgruppe",
    )
    assertTrue(WhatsAppSource.shouldProcess(s, WhatsAppSource.PKG_BUSINESS, "Kunde Schmidt", isGroup = false))
    assertFalse(WhatsAppSource.shouldProcess(s, WhatsAppSource.PKG_PERSONAL, "Hans @ Stummgruppe", isGroup = true))

    assertTrue(SmsRcsSource.isTwoFactorNoise("Ihr Sicherheitscode lautet 839201. Gültig für 5 Minuten."))
    assertFalse(SmsRcsSource.isTwoFactorNoise("Praxis Dr. Müller: Ihr Termin am 14.11. um 10:30 Uhr ist bestätigt."))

    val ics = """
      BEGIN:VCALENDAR
      BEGIN:VEVENT
      SUMMARY:Zahnarztkontrolle
      DTSTART:20261120T093000
      LOCATION:Praxis am Markt 4
      END:VEVENT
      END:VCALENDAR
    """.trimIndent()
    val parsed = IcsCalendarSource.parseIcsToText(ics)
    val d = RuleEngine.analyze(parsed, "ICS", now, 60)
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("2026-11-20T09:30", d.start)
    assertTrue(d.title.contains("Zahnarztkontrolle", ignoreCase = true))
  }

  @Test fun crypticTitleBlockedAndCleanedWhileNormalTavernAllowed() {
    val cryptic = "kex%treffen&an&Tarverne"
    assertTrue(RuleEngine.isCrypticMessage(cryptic))
    assertEquals(Kind.NONE, RuleEngine.analyze(cryptic, "Sender", now, 60).kind)
    assertEquals("Treffen an Tarverne", RuleEngine.cleanCrypticTitle(cryptic))

    // Sanitize LLM detection that returned a cryptic title
    val llmWithCrypticTitle = Detection(
      kind = Kind.EVENT,
      title = "kex%treffen&an&Tarverne",
      start = "2026-11-01T18:00",
      confidence = 0.9,
      engine = "cloud",
    )
    val sanitized = RuleEngine.sanitizeDetection(llmWithCrypticTitle, "Treffen an Tarverne morgen um 18 Uhr", "Anna")
    assertEquals("Treffen an Tarverne", sanitized.title)
    assertFalse(sanitized.title.contains("%"))
    assertFalse(sanitized.title.contains("&"))

    // Normal "treffen an Terverne" is allowed and never flagged as cryptic
    val allowed = "treffen an Terverne"
    assertFalse(RuleEngine.isCrypticMessage(allowed))
    val allowedDet = RuleEngine.analyze(allowed, "", now, 60)
    assertEquals(Kind.EVENT, allowedDet.kind)
    assertEquals("Treffen an Terverne", allowedDet.title)
    assertEquals("Terverne", allowedDet.location)
  }
}
