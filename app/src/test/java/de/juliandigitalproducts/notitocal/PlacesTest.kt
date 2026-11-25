package de.juliandigitalproducts.notitocal

import androidx.test.core.app.ApplicationProvider
import de.juliandigitalproducts.notitocal.analysis.DateParser
import de.juliandigitalproducts.notitocal.analysis.RuleEngine
import de.juliandigitalproducts.notitocal.places.PlaceOnline
import de.juliandigitalproducts.notitocal.places.PlaceRecall
import de.juliandigitalproducts.notitocal.places.PlaceStore
import de.juliandigitalproducts.notitocal.places.TimelineImport
import de.juliandigitalproducts.notitocal.places.VenueKind
import de.juliandigitalproducts.notitocal.places.Visit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NotiToCalApp::class)
class PlacesTest {
  private val context get() = ApplicationProvider.getApplicationContext<NotiToCalApp>()
  private lateinit var store: PlaceStore

  @Before fun setUp() {
    store = PlaceStore(context)
    store.clear()
  }

  @After fun tearDown() { store.close() }

  private val phoneTimeline = """
    {"semanticSegments":[
      {"startTime":"2026-10-01T19:00:00.000+02:00","endTime":"2026-10-01T21:00:00.000+02:00",
       "visit":{"hierarchyLevel":0,"probability":0.9,"topCandidate":{"placeId":"capone","semanticType":"UNKNOWN","probability":0.8,
       "placeLocation":{"latLng":"52.5200100°, 13.4050100°"}}}},
      {"startTime":"2026-10-01T19:00:00.000+02:00","endTime":"2026-10-01T21:00:00.000+02:00",
       "visit":{"hierarchyLevel":1,"topCandidate":{"placeId":"city","placeLocation":{"latLng":"52.52°, 13.40°"}}}},
      {"startTime":"2026-10-08T19:00:00.000+02:00","endTime":"2026-10-08T20:00:00.000+02:00",
       "visit":{"hierarchyLevel":0,"topCandidate":{"placeId":"capone","placeLocation":{"latLng":"52.5200200°, 13.4050200°"}}}},
      {"startTime":"2026-10-02T08:00:00.000+02:00","endTime":"2026-10-02T17:00:00.000+02:00",
       "visit":{"hierarchyLevel":0,"topCandidate":{"placeId":"home","semanticType":"INFERRED_HOME","placeLocation":{"latLng":"52.53°, 13.41°"}}}},
      {"startTime":"2026-10-02T17:00:00.000+02:00","endTime":"2026-10-02T17:30:00.000+02:00",
       "timelinePath":[{"point":"52.53°, 13.41°","time":"2026-10-02T17:10:00.000+02:00"}]}
    ],"rawSignals":[{"position":{"LatLng":"52.5°, 13.4°"}}]}
  """.trimIndent()

  private val takeoutMonth = """
    {"timelineObjects":[{"placeVisit":{"location":{"latitudeE7":525100000,"longitudeE7":133900000,"placeId":"bella",
      "address":"Hauptstraße 5\n10115 Berlin","name":"Pizzeria Bella Napoli","semanticType":"TYPE_UNKNOWN"},
      "duration":{"startTimestamp":"2023-01-05T18:00:00Z","endTimestamp":"2023-01-05T20:00:00Z"}}}]}
  """.trimIndent()

  @Test fun phoneTimelineIsImportedWithoutDoubleCounting() {
    val r = TimelineImport.run(ByteArrayInputStream(phoneTimeline.toByteArray()), store)
    // Level-1 visits and paths are skipped: 2× restaurant + home.
    assertEquals(3, r.visits)
    assertEquals(2, r.newPlaces)
    assertEquals(VenueKind.HOME, store.home()?.kind)
  }

  @Test fun takeoutZipNamesAndKindsArePlaces() {
    val zip = ByteArrayOutputStream().also { out ->
      ZipOutputStream(out).use { z ->
        z.putNextEntry(ZipEntry("Takeout/Standortverlauf/Semantic Location History/2023/2023_JANUARY.json")); z.write(takeoutMonth.toByteArray()); z.closeEntry()
        z.putNextEntry(ZipEntry("Takeout/Standortverlauf/Records.json")); z.write("""{"locations":[]}""".toByteArray()); z.closeEntry()
      }
    }.toByteArray()
    val r = TimelineImport.run(ByteArrayInputStream(zip), store)
    assertEquals(1, r.visits)
    val italian = store.candidates(VenueKind.ITALIAN, null)
    assertEquals("Pizzeria Bella Napoli, Hauptstraße 5 10115 Berlin", italian.single().label)
  }

  @Test fun samplesExtendAStayAndCountArrivals() {
    val a = store.sample(52.5, 13.4, 1_000, 60, previousId = -1)
    val again = store.sample(52.50001, 13.40001, 2_000, 60, previousId = a)
    assertEquals(a, again)
    store.sample(52.6, 13.5, 3_000, 60, previousId = a)
    store.sample(52.5, 13.4, 4_000, 60, previousId = -1)
    assertEquals(2, store.visitsNear(52.5, 13.4))
  }

  @Test fun rankingPrefersHabitsNearby() {
    val now = 100L * 86_400_000L
    store.record(List(5) { Visit(52.50, 13.40, now, 90, name = "Trattoria Roma", kind = VenueKind.ITALIAN) })
    store.record(List(6) { Visit(48.10, 11.50, now, 90, name = "Pizzeria München", kind = VenueKind.ITALIAN) })
    store.record(listOf(Visit(52.51, 13.41, now, 30, name = "Da Mario", kind = VenueKind.ITALIAN)))
    val best = store.candidates(VenueKind.ITALIAN, 52.50 to 13.40, limit = 3, now = now)
    assertEquals("Trattoria Roma", best.first().name)
    assertTrue(best.size <= 3)
  }

  @Test fun venueKindsInMessagesAndNames() {
    assertEquals(VenueKind.ITALIAN, VenueKind.inText("Treffen beim Italiener in der Hauptstraße"))
    assertEquals(VenueKind.ITALIAN, VenueKind.inText("at the Italian place on Main Street"))
    assertEquals(VenueKind.DENTIST, VenueKind.inText("Morgen zum Zahnarzt"))
    assertNull(VenueKind.inText("Treffen bei Tim"))
    assertEquals(VenueKind.ITALIAN, VenueKind.ofName("Capone"))
    assertEquals(VenueKind.ITALIAN, VenueKind.ofTags(mapOf("amenity" to "restaurant", "cuisine" to "pizza;italian")))
    assertEquals("Italiener", VenueKind.wordIn("Treffen beim Italiener", VenueKind.ITALIAN))
  }

  @Test fun streetsInMessages() {
    assertEquals("Hauptstraße", PlaceRecall.street("Treffen beim Italiener in der Hauptstraße?"))
    assertEquals("Main Street", PlaceRecall.street("at the Italian place on Main Street?"))
    assertTrue(PlaceRecall.isBareStreet("Hauptstraße"))
    assertFalse(PlaceRecall.isBareStreet("Hauptstraße 5"))
    assertEquals("Hauptstr", PlaceOnline.streetStem("Hauptstraße"))
  }

  @Test fun overpassAnswerBecomesVenues() {
    val json = """{"elements":[
      {"type":"node","lat":52.5,"lon":13.4,"tags":{"name":"Capone","cuisine":"italian","addr:street":"Hauptstraße","addr:housenumber":"12","addr:postcode":"10115","addr:city":"Berlin"}},
      {"type":"way","center":{"lat":52.51,"lon":13.41},"tags":{"name":"Pizza Prima"}}]}"""
    val venues = PlaceOnline.parse(json)
    assertEquals("Hauptstraße 12, 10115 Berlin", venues[0].address)
    assertEquals(52.51, venues[1].lat, 0.0001)
  }

  @Test fun meetingAtAKindOfPlaceIsTheTitle() {
    val now = LocalDateTime.of(2026, 11, 11, 15, 0)
    val de = RuleEngine.analyze("Hi! Treffen wir uns am 12. um 19 Uhr beim Italiener in der Hauptstraße?", "Alex", now)
    assertEquals("Treffen beim Italiener (Alex)", de.title)
    assertEquals("2026-11-12T19:00", de.start)
    val en = RuleEngine.analyze("Hi! Shall we meet on the 12th at 7 pm at the Italian place on Main Street?", "Alex", now)
    assertEquals("Meet at the Italian (Alex)", en.title)
  }

  @Test fun locationHistoryTravelsOnlyEncryptedInABackup() {
    store.record(List(3) { Visit(52.50, 13.40, 1_000, 60, name = "Trattoria Roma", kind = VenueKind.ITALIAN) })
    val encrypted = de.juliandigitalproducts.notitocal.ui.BackupRestore.encryptPlaces(context, "geheim123")!!
    val backup = de.juliandigitalproducts.notitocal.data.SettingsBackup(
      settings = de.juliandigitalproducts.notitocal.data.AppSettings(), placesEncrypted = encrypted,
    )
    assertTrue(de.juliandigitalproducts.notitocal.ui.BackupRestore.needsPassword(backup))
    assertNull(de.juliandigitalproducts.notitocal.ui.BackupRestore.decrypt(backup, "falsch123"))
    val json = de.juliandigitalproducts.notitocal.ui.BackupRestore.decrypt(backup, "geheim123")!!.second!!
    store.clear()
    store.importAll(PlaceStore.decode(json))
    val restored = store.candidates(VenueKind.ITALIAN, null).single()
    assertEquals("Trattoria Roma", restored.name)
    assertEquals(3, restored.visits)
    // Importing again on top merges instead of duplicating.
    store.importAll(PlaceStore.decode(json))
    assertEquals(6, store.candidates(VenueKind.ITALIAN, null).single().visits)
  }

  @Test fun unresolvedKindStaysAsWritten() {
    assertEquals("Italiener, Hauptstraße", PlaceRecall.asWritten(VenueKind.ITALIAN, "Treffen beim Italiener in der Hauptstraße"))
    assertEquals("Italian", PlaceRecall.asWritten(VenueKind.ITALIAN, "Dinner at the Italian tonight"))
  }

  @Test fun preFilterKnowsMoreAndBlocksMore() {
    val now = LocalDateTime.of(2026, 11, 11, 15, 0)
    assertTrue(RuleEngine.relevance("Hier der Link für nachher: https://zoom.us/j/1234567", now) >= 0.2)
    assertTrue(RuleEngine.relevance("Ihre Buchungsnummer lautet XK7 – wir freuen uns auf Sie", now) >= 0.2)
    assertTrue(RuleEngine.relevance("Physiotherapie Donnerstag 10:30", now) >= 0.5)
    assertTrue(RuleEngine.relevance("Wohnungsbesichtigung am Samstag", now) >= 0.5)
    assertTrue(RuleEngine.relevance("Flight LH 1234 boarding soon", now) >= 0.2)
    assertTrue(RuleEngine.relevance("Black Friday: nur heute 50 % Rabatt, jetzt kaufen!", now) < 0.2)
    assertTrue(RuleEngine.relevance("Neue Anmeldung erkannt auf deinem Konto", now) < 0.2)
  }

  @Test fun sampleDateIsTheNextTwelfth() {
    val before = LocalDateTime.of(2026, 11, 11, 15, 0)
    assertEquals(LocalDate.of(2026, 11, 12), DateParser.parse("Treffen wir uns am 12. um 19 Uhr beim Italiener", before).date)
    assertEquals(LocalDate.of(2026, 12, 12), DateParser.parse("Shall we meet on the 12th at 7 pm", before.plusDays(5)).date)
  }
}
