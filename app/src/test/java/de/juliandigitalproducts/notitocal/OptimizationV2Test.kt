// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.analysis.StyleTemplates
import de.juliandigitalproducts.notitocal.capture.NotificationCaptureService
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.output.DuplicateMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OptimizationV2Test {

  @Test
  fun testBirthdayDetectionAndFormatting() {
    val now = LocalDateTime.of(2026, 11, 10, 10, 0)

    val d1 = RuleEngine.analyze("Anna hat morgen Geburtstag", "Familie", now)
    assertEquals(Kind.EVENT, d1.kind)
    assertEquals("Geburtstag Anna", d1.title)
    assertTrue("Should be all-day event", d1.allDay)

    val d2 = RuleEngine.analyze("Geburtstag von Markus", "Freunde", now)
    assertEquals(Kind.EVENT, d2.kind)
    assertEquals("Geburtstag Markus", d2.title)

    val d3 = RuleEngine.analyze("Morgen hat Peter Geburtstag", "Chat", now)
    assertEquals(Kind.EVENT, d3.kind)
    assertEquals("Geburtstag Peter", d3.title)
  }

  @Test
  fun testSenderSanitization() {
    val service = NotificationCaptureService()

    assertEquals("Familie", service.cleanSenderTitle("Familie (11 Nachrichten)"))
    assertEquals("Max Mustermann", service.cleanSenderTitle("Max Mustermann (2 neue Nachrichten)"))
    assertEquals("Sportfreunde", service.cleanSenderTitle("Sportfreunde (+3)"))
    assertEquals("Projekt X", service.cleanSenderTitle("Projekt X: 5 neue Nachrichten"))
  }

  @Test
  fun testStyleTemplatesCustomTextAndDescription() {
    val template = StyleTemplates.event("event_formal")
    val preview = StyleTemplates.preview(
      template = template,
      language = "de",
      customText = "Projekt Kick-off",
      customDescription = "Bitte Folien mitbringen.",
      customLocation = "Besprechungsraum A",
      customWhen = "Di 10. Nov · 14:00 Uhr",
    )

    assertTrue("Title should contain custom text", preview.title.contains("Projekt Kick-off"))
    assertTrue("Description should contain subject", preview.description.contains("Projekt Kick-off"))
    assertTrue("Description should contain location", preview.description.contains("Besprechungsraum A"))
    assertTrue("Description should contain custom description", preview.description.contains("Bitte Folien mitbringen."))
  }

  @Test
  fun testDuplicateMatcherScoringWithCoreLocationAndTitle() {
    val baseMillis = 1700000000000L
    val proposed = DuplicateMatcher.Proposed(
      title = "Treffen",
      description = "Morgen Kaffee",
      location = "Kölner Dom, Köln",
      startMillis = baseMillis,
    )

    val candidate = DuplicateMatcher.Candidate(
      eventId = 42L,
      calendarId = 1L,
      title = "Treffen Kölner Dom",
      description = "Alte Notiz",
      location = "Köln",
      startMillis = baseMillis,
      accessLevel = 700,
    )

    val score = DuplicateMatcher.score(proposed, candidate)
    assertTrue("Score should comfortably exceed threshold for same time and matching core title/location: $score", score >= DuplicateMatcher.THRESHOLD)
  }
}
