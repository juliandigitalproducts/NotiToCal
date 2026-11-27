// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.ModelSourceSelector
import de.juliandigitalproducts.notitocal.analysis.ModelSourceSelector.Choice
import de.juliandigitalproducts.notitocal.analysis.ModelSourceSelector.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceSelectorTest {
  @Test fun sharedFileWinsWhileItIsReadable() {
    val decision = ModelSourceSelector.select(Input("content://models/qwen.task", true, "/data/copy.task", true))
    assertEquals(Choice.SHARED, decision.choice)
    assertEquals("/data/copy.task", decision.fallbackPath)
    assertFalse(decision.sharedMissing)
  }

  @Test fun missingSharedFileFallsBackToTheCopy() {
    val decision = ModelSourceSelector.select(Input("content://models/qwen.task", false, "/data/copy.task", true))
    assertEquals(Choice.IMPORTED, decision.choice)
    assertEquals("/data/copy.task", decision.importedPath)
    assertTrue(decision.sharedMissing)
    assertNull(decision.fallbackPath)
  }

  @Test fun unavailableWhenNothingCanBeOpened() {
    val missing = ModelSourceSelector.select(Input("content://models/qwen.task", false, "", false))
    assertEquals(Choice.UNAVAILABLE, missing.choice)
    assertTrue(missing.sharedMissing)
    val none = ModelSourceSelector.select(Input("", false, "/data/copy.task", false))
    assertEquals(Choice.UNAVAILABLE, none.choice)
    assertFalse(none.sharedMissing)
  }

  @Test fun importedCopyIsUsedWhenNoSharedUriExists() {
    val decision = ModelSourceSelector.select(Input("", false, "/data/copy.task", true))
    assertEquals(Choice.IMPORTED, decision.choice)
    assertNull(decision.fallbackPath)
  }

  @Test fun descriptorPathStaysOnTheOpenFd() {
    assertEquals("/proc/self/fd/7", ModelSourceSelector.descriptorPath(7))
  }
}
