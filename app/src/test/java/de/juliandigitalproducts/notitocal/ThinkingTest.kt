// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.EmptyAnswerException
import de.juliandigitalproducts.notitocal.analysis.LlmJson
import de.juliandigitalproducts.notitocal.analysis.Thinking
import de.juliandigitalproducts.notitocal.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** C5: answers of thinking models (Qwen 3, Gemma 4, DeepSeek R1). */
class ThinkingTest {
  @Test fun removesClosedBlocks() {
    assertEquals("{\"kind\":\"TASK\"}", Thinking.strip("<think>\nThe user wants…\n</think>\n\n{\"kind\":\"TASK\"}"))
    assertEquals("Answer", Thinking.strip("<THINKING>a</THINKING>Answer"))
  }

  @Test fun removesReasoningBeforeStrayClosingTag() {
    // Some templates put the opening tag into the prompt, so only the closing tag shows up.
    assertEquals("Answer", Thinking.strip("Okay, let me check the date…</think>Answer"))
  }

  @Test fun unclosedBlockMeansNoAnswer() {
    assertEquals("", Thinking.strip("<think>I need to find the date, the message says"))
    assertEquals("Text", Thinking.strip("Text<think>half"))
    assertThrows(EmptyAnswerException::class.java) { Thinking.answer("<think>never finished", "test") }
    assertThrows(EmptyAnswerException::class.java) { Thinking.answer(null, "test") }
  }

  @Test fun jsonParserIgnoresReasoning() {
    val d = LlmJson.parse("<think>{\"kind\":\"NONE\"} is wrong</think>{\"kind\":\"EVENT\",\"start\":\"2026-11-04T10:00\",\"title\":\"Zahnarzt\"}", "x")!!
    assertEquals(Kind.EVENT, d.kind)
    assertEquals("Zahnarzt", d.title)
  }
}
