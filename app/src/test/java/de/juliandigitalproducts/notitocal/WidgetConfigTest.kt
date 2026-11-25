package de.juliandigitalproducts.notitocal

import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.LogEntry
import de.juliandigitalproducts.notitocal.data.RoutingRule
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.SourceType
import de.juliandigitalproducts.notitocal.model.Status
import de.juliandigitalproducts.notitocal.ui.CalendarFilter
import de.juliandigitalproducts.notitocal.widget.WidgetConfig
import de.juliandigitalproducts.notitocal.widget.WidgetConfigStore
import de.juliandigitalproducts.notitocal.widget.WidgetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetConfigTest {
  private val work = RoutingRule(id = "work", name = "Arbeit")
  private val settings = AppSettings(routingRules = listOf(work))
  private fun entry(route: String?) =
    LogEntry(1, 0, SourceType.VOICE_MEMO, "", "Anti-Vocale", "", "x", Detection(kind = Kind.EVENT, title = "x", route = route), Status.ADDED, "")

  @Test fun valuesAreKeptInRange() {
    val c = WidgetConfig(opacity = 0f, textScale = 5f).sanitized()
    assertEquals(WidgetConfig.MIN_OPACITY, c.opacity)
    assertEquals(WidgetConfig.MAX_TEXT_SCALE, c.textScale)
  }

  @Test fun calendarFilterUsesTheRoutingRules() {
    val onlyWork = WidgetConfig(calendars = setOf("work"))
    assertTrue(onlyWork.shows(entry("work"), settings))
    assertFalse(onlyWork.shows(entry(null), settings))
    assertTrue(WidgetConfig(calendars = setOf(CalendarFilter.DEFAULT)).shows(entry(null), settings))
    assertTrue(WidgetConfig().shows(entry(null), settings))
  }

  @Test fun aDeletedRuleNoLongerHidesEverything() {
    assertTrue(WidgetConfig(calendars = setOf("gone")).shows(entry(null), settings))
  }

  @Test fun storeKeepsOneConfigPerWidget() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val c = WidgetConfig(opacity = 0.5f, theme = WidgetTheme.DARK, showActions = false, textScale = 1.5f, calendars = setOf("work"))
    WidgetConfigStore.save(context, 7, c)
    assertEquals(c, WidgetConfigStore.load(context, 7))
    assertEquals(WidgetConfig(), WidgetConfigStore.load(context, 8))
    WidgetConfigStore.delete(context, intArrayOf(7))
    assertEquals(WidgetConfig(), WidgetConfigStore.load(context, 7))
  }
}
