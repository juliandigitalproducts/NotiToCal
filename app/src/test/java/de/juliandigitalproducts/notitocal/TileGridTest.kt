// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.ui.tileRows
import org.junit.Assert.assertEquals
import org.junit.Test

class TileGridTest {
  @Test fun threeTilesOnAPhoneStretchTheThird() {
    assertEquals(listOf(listOf("a", "b"), listOf("c")), tileRows(listOf("a", "b", "c"), 2, stretchLastRow = true))
  }

  @Test fun fullRowsAreUnchanged() {
    assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), tileRows(listOf("a", "b", "c", "d"), 2, stretchLastRow = true))
  }

  @Test fun withoutStretchingEmptySlotsKeepTheColumns() {
    assertEquals(listOf(listOf("a", "b", "c"), listOf("d", null, null)), tileRows(listOf("a", "b", "c", "d"), 3, stretchLastRow = false))
  }
}
