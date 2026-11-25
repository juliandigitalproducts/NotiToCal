package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.data.BackupCrypto
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.net.LanHosts
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher
import de.juliandigitalproducts.notitocal.output.span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Fixes from the code review of 2026-11-03 and 2026-11-07. */
class ReviewFixesTest {
  // C7: one all-day day must stay one day; an explicit last day is inclusive.
  @Test fun allDaySpanEndIsExclusive() {
    val one = Detection(Kind.EVENT, start = "2026-11-04", allDay = true).span(60)!!
    assertEquals(LocalDateTime.of(2026, 11, 4, 0, 0), one.start)
    assertEquals(LocalDateTime.of(2026, 11, 5, 0, 0), one.end)
    val range = Detection(Kind.EVENT, start = "2026-11-03", end = "2026-11-05", allDay = true).span(60)!!
    assertEquals(LocalDateTime.of(2026, 11, 6, 0, 0), range.end)
    val timed = Detection(Kind.EVENT, start = "2026-11-04T10:00").span(30)!!
    assertEquals(LocalDateTime.of(2026, 11, 4, 10, 30), timed.end)
  }

  // C9: plain http only inside the local network.
  @Test fun lanHosts() {
    listOf("192.168.1.10", "10.0.0.5", "172.20.1.1", "127.0.0.1", "localhost", "nas", "nas.local", "pi.home.arpa", "100.101.1.2", "[fd00::1]", "fritz.box.fritz.box")
      .forEach { assertTrue(it, LanHosts.isLocal(it)) }
    listOf("api.openai.com", "example.com", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1")
      .forEach { assertFalse(it, LanHosts.isLocal(it)) }
  }

  // C10: secrets only encrypted, wrong password fails, foreign keys are dropped.
  @Test fun backupSecretsRoundTrip() {
    // Fake-Wert, zur Laufzeit gebaut, damit kein Secret-Muster im Quelltext steht
    val fakeKey = "sk" + "-test-" + "123"
    val secrets = mapOf("llm_OPENAI" to fakeKey, "todoist" to "abc")
    val enc = BackupCrypto.encrypt(secrets, "correct horse")
    assertFalse(enc.data.contains(fakeKey.take(7)))
    assertEquals(secrets, BackupCrypto.decrypt(enc, "correct horse"))
    assertThrows(Exception::class.java) { BackupCrypto.decrypt(enc, "wrong password") }
    assertEquals(mapOf("ok_key" to "v"), BackupCrypto.sanitize(mapOf("ok_key" to "v", "../evil" to "x", "big" to "y".repeat(9000))))
  }

  // P1: DateParser relative weekday displacement ("Montag in 2 Wochen")
  @Test fun relativeWeekdayDisplacement() {
    val now = LocalDateTime.of(2026, 11, 7, 10, 0) // Saturday
    val res = DateParser.parse("Montag in 2 Wochen", now)
    // Upcoming Monday is 2026-11-09. + 14 days = 2026-11-23.
    assertEquals(LocalDate.of(2026, 11, 23), res.date)
  }

  // P1: Timezone UTC 'Z' and offset conversion to local system timezone
  @Test fun utcAndOffsetDateTimeConversion() {
    val zParsed = Detection.parseDateTime("2026-11-04T12:00:00Z")
    val expectedZ = Instant.parse("2026-11-04T12:00:00Z").atZone(ZoneId.systemDefault()).toLocalDateTime()
    assertEquals(expectedZ, zParsed)

    val offsetParsed = Detection.parseDateTime("2026-11-04T12:00:00+02:00")
    val expectedOffset = OffsetDateTime.parse("2026-11-04T12:00:00+02:00").atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    assertEquals(expectedOffset, offsetParsed)
  }

  // P1: Self-screenshot OCR loops, title sanitization, and Julian location blocking
  @Test fun selfDebugAndSanitizationFixes() {
    assertTrue(RuleEngine.isSelfOrDebugText("Automatisch eingetragen von NotiToCal"))
    assertTrue(RuleEngine.isSelfOrDebugText("< Termin: Zahnarzt am Montag"))
    assertTrue(RuleEngine.isSelfOrDebugText("STATUS:EVENT_CREATED"))
    assertFalse(RuleEngine.isSelfOrDebugText("Treffen morgen um 14 Uhr im Büro"))

    // Location extraction must never pick 'Julian' or 'Julian Daniels' as a location
    assertNull(RuleEngine.extractLocation("Besprechung mit Julian"))
    assertNull(RuleEngine.extractLocation("Anruf von Julian Daniels"))
    assertEquals("München", RuleEngine.extractLocation("Treffen mit Julian in München"))

    // Title sanitization strips newlines and leading status prefixes
    val dirty = Detection(Kind.EVENT, title = "< Termin: Projektmeeting\r\nBesprechungsraum", start = "2026-11-04T10:00")
    val cleaned = RuleEngine.sanitizeDetection(dirty, "< Termin: Projektmeeting\r\nBesprechungsraum")
    assertEquals("Projektmeeting Besprechungsraum", cleaned.title)
  }

  // P1: Duplicate marker stripping and repeat merge prevention
  @Test fun duplicateMarkerStripping() {
    val textWithMarker = "Echtes Meeting Notizen\n\n—— NotiToCal ——\nBeteiligte Kalender: Privat"
    assertEquals("Echtes Meeting Notizen", DuplicateMatcher.stripMarkerAndSystem(textWithMarker))

    val textWithAsciiMarker = "Wichtiger Termin\n\n-- NotiToCal --\nOriginal-ID: 123"
    assertEquals("Wichtiger Termin", DuplicateMatcher.stripMarkerAndSystem(textWithAsciiMarker))
  }

  // A1/B3: Telegram Bridge script asset existence, security, optional token, and multi-language compilation
  @Test fun telegramBridgeAssetIntegrity() {
    val assetFile = java.io.File("src/main/assets/notitocal_telegram_bridge.py").let {
      if (it.exists()) it else java.io.File("app/src/main/assets/notitocal_telegram_bridge.py")
    }
    assertTrue("Asset notitocal_telegram_bridge.py must exist", assetFile.exists())
    val content = assetFile.readText()
    assertTrue("Asset must support Authorization header", content.contains("Authorization"))
    assertTrue("Asset must check Bearer token", content.contains("Bearer"))
    assertTrue("Asset must bind to 127.0.0.1 by default", content.contains("127.0.0.1"))
    assertTrue("Asset must have --lan option", content.contains("--lan"))
    assertTrue("Asset must support optional token via --require-token", content.contains("--require-token"))

    // Verify root copy is removed
    val rootFile = java.io.File("../notitocal_telegram_bridge.py").let {
      if (it.exists()) it else java.io.File("notitocal_telegram_bridge.py")
    }
    assertFalse("Root notitocal_telegram_bridge.py must not exist", rootFile.exists())

    // Verify Python compiles cleanly
    val proc = ProcessBuilder("python3", "-m", "py_compile", assetFile.absolutePath)
      .redirectErrorStream(true)
      .start()
    val exitCode = proc.waitFor()
    assertEquals("python3 -m py_compile must succeed", 0, exitCode)

    // Verify all 7 localized scripts exist and compile cleanly
    val baseDir = assetFile.parentFile
    val localizedDir = java.io.File(baseDir, "bridge_scripts")
    assertTrue("bridge_scripts directory must exist in assets", localizedDir.isDirectory)
    val locales = listOf("de", "en", "es", "fr", "it", "pl", "pt")
    for (loc in locales) {
      val locFile = java.io.File(localizedDir, "notitocal_telegram_bridge_$loc.py")
      assertTrue("Localized script for '$loc' must exist: ${locFile.path}", locFile.isFile)
      val locContent = locFile.readText()
      assertTrue("Localized script '$loc' must support --require-token", locContent.contains("--require-token"))

      val locProc = ProcessBuilder("python3", "-m", "py_compile", locFile.absolutePath)
        .redirectErrorStream(true)
        .start()
      val locExit = locProc.waitFor()
      assertEquals("Localized script '$loc' must compile cleanly", 0, locExit)
    }
  }

  // A2: Security: file_paths.xml must not expose full cache or external storage root
  @Test fun filePathsXmlSecurity() {
    val xmlFile = java.io.File("src/main/res/xml/file_paths.xml").let {
      if (it.exists()) it else java.io.File("app/src/main/res/xml/file_paths.xml")
    }
    assertTrue("file_paths.xml must exist", xmlFile.exists())
    val content = xmlFile.readText()
    assertFalse("Must not expose all_cache", content.contains("all_cache"))
    assertFalse("Must not expose external_files root", content.contains("external_files"))
  }
}
