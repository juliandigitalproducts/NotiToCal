package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Emulator test NTC #17: a room's letter or number belongs to the place ("Raum A", not "Raum"). */
class RoomLocationTest {
  private val now = LocalDateTime.of(2026, 11, 20, 10, 0)

  @Test fun roomSuffixesStayWithThePlace() {
    mapOf(
      "Review am 04.12.2026 um 13 Uhr in Raum A" to "Raum A",
      "Review am 04.12.2026 um 13 Uhr in Raum A." to "Raum A",
      "Besprechung morgen um 10 Uhr im Raum 3.14" to "Raum 3.14",
      "Sprint review tomorrow at 3pm in Room B" to "Room B",
      "Treffen morgen um 9 Uhr in Raum 4" to "Raum 4",
      "Treffen morgen um 9 Uhr im Café Lotte" to "Café Lotte",
    ).forEach { (text, place) ->
      assertEquals(text, place, RuleEngine.extractLocation(text))
      assertEquals(text, place, RuleEngine.analyze(text, "Felix", now).location)
    }
  }
}
