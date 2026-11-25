package de.juliandigitalproducts.notitocal

import de.juliandigitalproducts.notitocal.analysis.Pseudonymizer
import de.juliandigitalproducts.notitocal.data.AliasEntry
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PseudonymizerTest {
  private val entries = listOf(
    AliasEntry("Anna", "PersonA"),
    AliasEntry("New York", "CityX"),
    AliasEntry("York", "CityY"),
  )

  @Test fun forwardIsCaseInsensitiveAndLongestFirst() {
    assertEquals("Meet PersonA in CityX", Pseudonymizer.apply("Meet Anna in New York", entries))
    assertEquals("Meet PersonA in CityX", Pseudonymizer.apply("Meet ANNA in new york", entries))
    assertFalse(Pseudonymizer.apply("New York", entries).contains("CityY"))
  }

  @Test fun wordBoundariesKeepLongerWords() {
    assertEquals("Annalena visits PersonA", Pseudonymizer.apply("Annalena visits Anna", entries))
    assertEquals("Anniversary party", Pseudonymizer.apply("Anniversary party", listOf(AliasEntry("Ann", "X"))))
  }

  @Test fun reverseRestoresRealTerms() {
    assertEquals("Meet Anna in New York", Pseudonymizer.reverse("Meet PersonA in CityX", entries))
    assertEquals("Meet Anna", Pseudonymizer.reverse("Meet persona", listOf(AliasEntry("Anna", "PersonA"))))
  }

  @Test fun restoreToggleKeepsAliasesWhenOff() {
    val detection = Detection(Kind.EVENT, title = "PersonA", description = "In CityX", location = "CityX")
    val restored = Pseudonymizer.restoreIf(detection, AppSettings(aliases = entries, restoreAliases = true))
    assertEquals("Anna", restored.title)
    assertEquals("In New York", restored.description)
    assertEquals("New York", restored.location)
    val kept = Pseudonymizer.restoreIf(detection, AppSettings(aliases = entries, restoreAliases = false))
    assertEquals("PersonA", kept.title)
    assertEquals("CityX", kept.location)
  }
}
