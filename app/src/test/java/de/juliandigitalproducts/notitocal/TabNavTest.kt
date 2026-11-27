// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.ui.TabNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabNavTest {
  private val tabs = listOf("home", "settings/duplicates", "settings/sources", "settings/targets", "settings")

  @Test fun subScreenOpenedFromSettingsStaysUnderSettings() {
    assertEquals("settings", TabNav.owner("settings", "home", "home", tabs))
    assertEquals("settings", TabNav.owner("settings/sources", "settings", "settings", tabs))
    assertEquals("settings", TabNav.owner("settings/sources/telegram", "settings/sources", "settings", tabs))
  }

  @Test fun tabOpenedDirectlyOwnsItself() {
    assertEquals("settings/sources", TabNav.owner("settings/sources", "home", "settings", tabs))
  }

  @Test fun backToHomeSelectsHome() {
    assertEquals("home", TabNav.owner("home", null, "settings", tabs))
  }

  @Test fun barIsHiddenOnlyInTheWizard() {
    assertFalse(TabNav.showBar("wizard"))
    assertFalse(TabNav.showBar(null))
    assertTrue(TabNav.showBar("settings/sources/telegram"))
  }
}
