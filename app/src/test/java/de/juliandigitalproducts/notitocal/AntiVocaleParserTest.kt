// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.capture.AntiVocaleParser
import org.junit.Assert.assertEquals
import org.junit.Test

class AntiVocaleParserTest {

  @Test
  fun testPlainText() {
    val input = "Treffen morgen um 15:00 Uhr am Hauptbahnhof."
    assertEquals("Treffen morgen um 15:00 Uhr am Hauptbahnhof.", AntiVocaleParser.cleanTranscript(input))
  }

  @Test
  fun testTextWithInlineTimestamps() {
    val input = """
      [00:01] Hallo zusammen,
      [00:04.250] wir haben morgen um 14:30 Uhr ein Meeting.
      [00:10] Bitte pünktlich sein!
    """.trimIndent()
    val expected = "Hallo zusammen, wir haben morgen um 14:30 Uhr ein Meeting. Bitte pünktlich sein!"
    assertEquals(expected, AntiVocaleParser.cleanTranscript(input))
  }

  @Test
  fun testSrtSubtitles() {
    val input = """
      1
      00:00:01,000 --> 00:00:03,500
      Treffen morgen

      2
      00:00:04,000 --> 00:00:07,000
      um 16:00 Uhr beim Zahnarzt.
    """.trimIndent()
    val expected = "Treffen morgen um 16:00 Uhr beim Zahnarzt."
    assertEquals(expected, AntiVocaleParser.cleanTranscript(input))
  }

  @Test
  fun testWebVttSubtitles() {
    val input = """
      WEBVTT
      Kind: captions
      Language: de

      00:00:01.000 --> 00:00:03.000
      Meeting mit Markus

      00:00:03.500 --> 00:00:06.000
      am Montag um 10 Uhr.
    """.trimIndent()
    val expected = "Meeting mit Markus am Montag um 10 Uhr."
    assertEquals(expected, AntiVocaleParser.cleanTranscript(input))
  }

  @Test
  fun testSpeakerLabels() {
    val input = """
      Speaker 0: Hallo!
      [Speaker 1]: Wir sehen uns morgen um 18 Uhr zum Abendessen.
      Sprecher 2: Klingt super!
    """.trimIndent()
    val expected = "Hallo! Wir sehen uns morgen um 18 Uhr zum Abendessen. Klingt super!"
    assertEquals(expected, AntiVocaleParser.cleanTranscript(input))
  }

  @Test
  fun testAppendedContentDeltaExtraction() {
    val initial = "Erste Sprachnotiz: Treffen morgen um 10."
    val full = "Erste Sprachnotiz: Treffen morgen um 10.\n\nZweite Sprachnotiz: Arzttermin übermorgen um 15 Uhr."
    val delta = full.substring(initial.length)
    assertEquals("Zweite Sprachnotiz: Arzttermin übermorgen um 15 Uhr.", AntiVocaleParser.cleanTranscript(delta))
  }
}
