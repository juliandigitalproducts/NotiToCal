// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.Prompt
import de.juliandigitalproducts.notitocal.analysis.StyleTemplates
import de.juliandigitalproducts.notitocal.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class StyleTemplateTest {
  private val now = LocalDateTime.of(2026, 10, 31, 10, 0)

  @Test fun promptContainsOnlyTheSelectedStyles() {
    val settings = AppSettings(eventTemplateId = "event_formal", taskTemplateId = "task_note")
    val block = StyleTemplates.promptBlock(settings.eventTemplateId, settings.taskTemplateId)
    assertTrue(block.contains("Style id event_formal:"))
    assertTrue(block.contains("Style id task_note:"))
    assertFalse(block.contains("Style id event_pin:"))
    assertFalse(block.contains("Style id task_todo:"))

    val prompt = Prompt.system(settings, now, "English")
    assertTrue(prompt.contains(block))
    val compact = Prompt.compact(now, "English", settings.eventTemplateId, settings.taskTemplateId)
    assertTrue(compact.contains("Style id event_formal:"))
    assertTrue(compact.contains("Style id task_note:"))
  }

  @Test fun unknownIdFallsBackAndPreviewUsesTheFormat() {
    assertEquals("event_clean", StyleTemplates.event("missing").id)
    assertEquals(5, StyleTemplates.events.size)
    assertEquals(5, StyleTemplates.tasks.size)
    val pin = StyleTemplates.preview(StyleTemplates.event("event_pin"))
    assertTrue(pin.title.startsWith("📌"))
    assertTrue(pin.description.startsWith("📍"))
    val plain = StyleTemplates.preview(StyleTemplates.task("task_plain"))
    assertFalse(plain.title.startsWith("☐"))
    assertEquals("Renew the passport", plain.title)
  }
}
