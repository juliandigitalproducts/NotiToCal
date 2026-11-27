// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.data.RoutingRule
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.pipeline.Resort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ResortTest {
  private val work = RoutingRule(id = "work", name = "Arbeit", keywords = "Kunde")
  private val family = RoutingRule(id = "family", name = "Familie", senders = "Mama")

  private fun entry(
    title: String, start: String? = "2027-03-20T14:00", sender: String = "", text: String = title,
    route: String? = null, status: Status = Status.ADDED, kind: Kind = Kind.EVENT,
  ) = LogEntry(1, 0, SourceType.NOTIFICATION, "pkg", "Chat", sender, text, Detection(kind = kind, title = title, start = start, route = route), status, "")

  @Test fun newRuleMatchesAnOldEntry() {
    val c = Resort.changes(listOf(work, family), listOf(entry("Kunde in Solingen")))
    assertEquals("work", c.single().newRoute)
  }

  @Test fun unchangedRouteIsNotAChange() {
    assertTrue(Resort.changes(listOf(work), listOf(entry("Kunde in Solingen", route = "work"))).isEmpty())
  }

  @Test fun entryLosesARuleThatNoLongerFits() {
    val c = Resort.changes(listOf(family), listOf(entry("Zahnarzt", route = "work")))
    assertNull(c.single().newRoute)
  }

  @Test fun withoutStoredTextAnEnabledRuleIsKept() {
    // The keyword may only have been in the (not stored) message.
    assertTrue(Resort.changes(listOf(work), listOf(entry("Termin", text = "", route = "work"))).isEmpty())
  }

  @Test fun rangeUsesStartOrDueAndSkipsIgnoredEntries() {
    val from = LocalDate.of(2027, 3, 1)
    val to = LocalDate.of(2027, 3, 31)
    assertTrue(Resort.inRange(entry("A"), from, to))
    assertTrue(Resort.inRange(entry("B", start = null, kind = Kind.TASK).let { it.copy(detection = it.detection.copy(due = "2027-03-31")) }, from, to))
    assertFalse(Resort.inRange(entry("C", start = "2027-04-01T09:00"), from, to))
    assertFalse(Resort.inRange(entry("D", status = Status.IGNORED), from, to))
    assertFalse(Resort.inRange(entry("E", start = null), from, to))
  }
}
