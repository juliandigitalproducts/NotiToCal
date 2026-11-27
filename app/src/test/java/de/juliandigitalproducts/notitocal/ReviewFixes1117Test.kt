// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.LocationEnricher
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code review 17.11.2026. */
class ReviewFixes1117Test {
  private val all = setOf("history", "osm", "photon")

  private fun settings(engine: AiEngine, online: Boolean, sources: Set<String> = all) =
    AppSettings(engine = engine, locationOnlineWithLocalEngine = online, locationSearchSources = sources)

  // NTC-R7: local engines only go online on request, remote engines always.
  @Test fun onlineLocationAllowedPerEngine() {
    assertFalse(settings(AiEngine.RULES, online = false).onlineLocationAllowed)
    assertTrue(settings(AiEngine.RULES, online = true).onlineLocationAllowed)
    assertFalse(settings(AiEngine.ON_DEVICE, online = false).onlineLocationAllowed)
    assertTrue(settings(AiEngine.ON_DEVICE, online = true).onlineLocationAllowed)
    assertTrue(settings(AiEngine.CLOUD, online = false).onlineLocationAllowed)
    assertTrue(settings(AiEngine.CLOUD, online = true).onlineLocationAllowed)
    assertTrue(settings(AiEngine.SELF_HOSTED, online = false).onlineLocationAllowed)
    assertTrue(settings(AiEngine.SELF_HOSTED, online = true).onlineLocationAllowed)
  }

  @Test fun effectiveLocationSourcesPerEngine() {
    for (engine in listOf(AiEngine.RULES, AiEngine.ON_DEVICE)) {
      assertEquals(engine.name, setOf("history"), settings(engine, online = false).effectiveLocationSources)
      assertEquals(engine.name, all, settings(engine, online = true).effectiveLocationSources)
    }
    for (engine in listOf(AiEngine.CLOUD, AiEngine.SELF_HOSTED)) {
      assertEquals(engine.name, all, settings(engine, online = false).effectiveLocationSources)
      assertEquals(engine.name, all, settings(engine, online = true).effectiveLocationSources)
    }
  }

  @Test fun effectiveLocationSourcesKeepTheUserSelection() {
    // Only Photon selected: nothing left offline with a local engine, Photon alone otherwise.
    assertEquals(emptySet<String>(), settings(AiEngine.RULES, online = false, sources = setOf("photon")).effectiveLocationSources)
    assertEquals(setOf("photon"), settings(AiEngine.RULES, online = true, sources = setOf("photon")).effectiveLocationSources)
    assertEquals(setOf("photon"), settings(AiEngine.CLOUD, online = false, sources = setOf("photon")).effectiveLocationSources)
    // An empty selection falls back to all sources (minus the online ones with a local engine).
    assertEquals(setOf("history"), settings(AiEngine.ON_DEVICE, online = false, sources = emptySet()).effectiveLocationSources)
    assertEquals(all, settings(AiEngine.SELF_HOSTED, online = false, sources = emptySet()).effectiveLocationSources)
  }

  // NTC-R5: place texts never reach the diagnostic log (and with it bug reports).
  @Test fun redactHidesThePlaceText() {
    val r = LocationEnricher.redact("Café Central, Hauptstraße 5")
    assertFalse(r.contains("Café"))
    assertFalse(r.contains("Hauptstraße"))
    assertTrue(r.contains("27 Zeichen"))
    assertEquals(r, LocationEnricher.redact("Café Central, Hauptstraße 5"))
    assertNotEquals(r, LocationEnricher.redact("Café Museum, Hauptstraße 5"))
    assertEquals("[leer]", LocationEnricher.redact(null))
    assertEquals("[leer]", LocationEnricher.redact("  "))
  }
}
