package de.juliandigitalproducts.notitocal.analysis

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.diagnostics.DiagnosticLogger
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.net.Http
import de.juliandigitalproducts.notitocal.places.PlaceRecall
import de.juliandigitalproducts.notitocal.places.VenueKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object LocationEnricher {

  data class LocationProposal(
    val eventId: Long,
    val title: String,
    val startMillis: Long,
    val currentAddress: String,
    val proposedAddress: String,
    val source: String,
    var selected: Boolean = true,
  )

  data class EnrichedAddress(
    val street: String,
    val houseNumber: String?,
    val postalCode: String?,
    val city: String,
    val formatted: String,
  )

  data class ParsedEntityResult(
    val title: String,
    val description: String? = null,
    val date: String?,
    val time: String?,
    val street: String?,
    val houseNumber: String?,
    val city: String?,
    val postalCode: String?,
    val fullAddress: String?,
    val whenLabel: String,
  )

  private val STREET_AND_NUM_REGEX = Regex(
    """\b((?:(?:an\s+der|in\s+der|auf\s+der|am|im|bei)\s+)?(?:[A-ZÄÖÜ][a-zäöüß\-]+\s+){0,3}[A-ZÄÖÜa-zäöüß\-]+(?:straße|strasse|str\.|weg|platz|allee|gasse|ring|chaussee|damm|ufer|pfad|zeile|street|road|avenue|lane|drive|boulevard))\s+(\d{1,4}[a-zA-Z]?)\b""",
    RegexOption.IGNORE_CASE,
  )

  /**
   * Main entry point for the pipeline: enriches an event detection with a resolved
   * physical address if the detection only contains a place name or venue, or if the text
   * hints at a place where past events already hold an address, or if a street was named without a city.
   */
  suspend fun enrich(
    app: Context,
    detection: Detection,
    rawText: String,
    s: AppSettings,
    sender: String = "",
  ): Detection = withContext(Dispatchers.IO) {
    if (!s.locationEnrichmentEnabled || detection.kind != Kind.EVENT) return@withContext detection

    // "bei uns" / "zu Hause" name no place a map can find: searching them returns a random match.
    val loc = detection.location?.trim().orEmpty().takeUnless { RuleEngine.isVaguePlace(it) }.orEmpty()
    // If it's already a full postal address with street + number + city, keep it
    if (RuleEngine.fullAddress(loc) != null) return@withContext detection
    val effort = s.locationSearchEffort.ifBlank { "balanced" }
    // Without consent, a local engine (RULES / ON_DEVICE) keeps place names on the device.
    val sources = s.effectiveLocationSources

    // 0. A kind of place instead of an address ("beim Italiener", "zum Zahnarzt"): where the user usually goes
    //    (location history), else such a place nearby. Only when the location names nothing more specific.
    val venueText = listOf(loc, detection.title, rawText).joinToString(" \n ")
    val kind = VenueKind.inText(loc) ?: if (loc.isBlank() || PlaceRecall.isBareStreet(loc)) VenueKind.inText(venueText) else null
    if (kind != null && kind.searchable && RuleEngine.fullAddress(rawText) == null) {
      // Earlier appointments of the same kind ("Essen beim Italiener") show where such meetings take place.
      val word = VenueKind.wordIn(venueText, kind)
      val past = if ("history" in sources && word != null) runSafely { searchPastCalendarLocations(app, word) }.getOrNull() else null
      if (!past.isNullOrBlank()) {
        return@withContext detection.copy(
          location = past,
          reason = listOf(detection.reason, Wording.placeVia("history")).filter { it.isNotBlank() }.joinToString(" · "),
        )
      }
      val found = runSafely { PlaceRecall.find(app, s, kind, venueText) }.getOrNull()
      if (found != null) {
        DiagnosticLogger.log("LocationEnricher", "Ort für ${kind.name}: ${redact(found.label)} (Besuche ${found.visits})")
        return@withContext detection.copy(
          location = found.label,
          reason = listOf(detection.reason, Wording.placeVia(if (found.visits > 0) Wording.locationHistory(found.visits) else "OpenStreetMap"))
            .filter { it.isNotBlank() }.joinToString(" · "),
        )
      }
      // Nothing found: a bare "Italian" / "Italiener in der Hauptstraße" must not be searched worldwide (that
      // returned a restaurant in another country). The place stays as it was written.
      return@withContext if (loc.isBlank()) detection.copy(location = PlaceRecall.asWritten(kind, venueText) ?: detection.location) else detection
    }

    // 1. Check for street + house number without city
    val streetPair = extractStreetAndNumber(loc, rawText)
    if (streetPair != null) {
      val (street, houseNum) = streetPair
      val candidateCities = LocationMemory.getCandidateCities(sender, s)

      // Test candidate cities via Photon / Nominatim
      for (candidate in candidateCities) {
        val query = "$street $houseNum, ${candidate.city}"
        if ("photon" in sources) {
          val photonResult = searchPhoton(query, lat = s.homeLatitude, lon = s.homeLongitude)
          if (photonResult != null) {
            LocationMemory.recordLocation(sender, candidate.city, photonResult.postalCode)
            DiagnosticLogger.log("LocationEnricher", "Fund via Photon für ${redact(query)}: ${redact(photonResult.formatted)}")
            return@withContext detection.copy(
              location = photonResult.formatted,
              reason = listOf(detection.reason, Wording.placeVia("Photon (${candidate.city})")).filter { it.isNotBlank() }.joinToString(" · "),
            )
          }
        }
        if ("osm" in sources && effort != "fast") {
          val osmMatch = searchNominatim(query, effort)
          if (!osmMatch.isNullOrBlank()) {
            LocationMemory.recordLocation(sender, candidate.city)
            DiagnosticLogger.log("LocationEnricher", "Fund via OSM für ${redact(query)}: ${redact(osmMatch)}")
            return@withContext detection.copy(
              location = osmMatch,
              reason = listOf(detection.reason, Wording.placeVia("OpenStreetMap (${candidate.city})")).filter { it.isNotBlank() }.joinToString(" · "),
            )
          }
        }
      }

      // If candidates did not match, try Photon with geographic coordinate bias if home location coordinates are known
      if ("photon" in sources && s.homeLatitude != null && s.homeLongitude != null) {
        val directPhoton = searchPhoton("$street $houseNum", lat = s.homeLatitude, lon = s.homeLongitude)
        if (directPhoton != null && directPhoton.city.isNotBlank()) {
          LocationMemory.recordLocation(sender, directPhoton.city, directPhoton.postalCode)
          DiagnosticLogger.log("LocationEnricher", "Fund via Photon Geo-Bias für ${redact("$street $houseNum")}: ${redact(directPhoton.formatted)}")
          return@withContext detection.copy(
            location = directPhoton.formatted,
            reason = listOf(detection.reason, Wording.placeVia("Photon + home location")).filter { it.isNotBlank() }.joinToString(" · "),
          )
        }
      }
    }

    // 2. Check venue / place queries (e.g. "Restaurant Bella Italia", "am Sportplatz")
    val queryCandidates = extractSearchQueries(loc, rawText, detection.title)
    if (queryCandidates.isEmpty()) return@withContext detection

    for (candidate in queryCandidates) {
      // 2a. Check local calendar history
      if ("history" in sources) {
        val histMatch = searchPastCalendarLocations(app, candidate)
        if (!histMatch.isNullOrBlank()) {
          DiagnosticLogger.log("LocationEnricher", "Fund in Historie für ${redact(candidate)}: ${redact(histMatch)}")
          return@withContext detection.copy(
            location = histMatch,
            reason = listOf(detection.reason, Wording.placeVia("history")).filter { it.isNotBlank() }.joinToString(" · "),
          )
        }
      }

      // 2b. Check Photon
      if ("photon" in sources) {
        val photonMatch = searchPhoton(candidate, lat = s.homeLatitude, lon = s.homeLongitude)
        if (photonMatch != null) {
          DiagnosticLogger.log("LocationEnricher", "Fund in Photon für ${redact(candidate)}: ${redact(photonMatch.formatted)}")
          return@withContext detection.copy(
            location = photonMatch.formatted,
            reason = listOf(detection.reason, Wording.placeVia("Photon")).filter { it.isNotBlank() }.joinToString(" · "),
          )
        }
      }

      // 2c. Check OpenStreetMap / Nominatim
      if ("osm" in sources && effort != "fast") {
        val osmMatch = searchNominatim(candidate, effort)
        if (!osmMatch.isNullOrBlank()) {
          DiagnosticLogger.log("LocationEnricher", "Fund in OSM für ${redact(candidate)}: ${redact(osmMatch)}")
          return@withContext detection.copy(
            location = osmMatch,
            reason = listOf(detection.reason, Wording.placeVia("OpenStreetMap")).filter { it.isNotBlank() }.joinToString(" · "),
          )
        }
      }
    }

    detection
  }

  /**
   * Extracts street and house number from location string or message text.
   */
  fun extractStreetAndNumber(location: String, rawText: String): Pair<String, String>? {
    STREET_AND_NUM_REGEX.find(location)?.let { m ->
      val st = m.groupValues[1].trim()
      val nr = m.groupValues[2].trim()
      if (st.isNotBlank() && nr.isNotBlank()) return Pair(st, nr)
    }
    STREET_AND_NUM_REGEX.find(rawText)?.let { m ->
      val st = m.groupValues[1].trim()
      val nr = m.groupValues[2].trim()
      if (st.isNotBlank() && nr.isNotBlank()) return Pair(st, nr)
    }
    return null
  }

  /**
   * Extracts search keywords/queries from location, raw text and title.
   * e.g., "Treffen am Sportplatz", "Restaurant Bella Italia", "Olympiastadion", "beim Griechen"
   */
  fun extractSearchQueries(location: String, rawText: String, title: String): List<String> {
    val results = mutableListOf<String>()
    if (location.length >= 3 && !RuleEngine.isVaguePlace(location)) {
      results.add(location)
    }

    val placePatterns = listOf(
      Regex("""(?i)\b(?:am|im|beim|beim\s+restaurant|im\s+restaurant|in\s+der\s+pizzeria|im\s+cafe|im\s+café|im\s+hotel|beim\s+sportplatz|am\s+sportplatz|am\s+platz|im\s+büro|in\s+der\s+halle|beim\s+arzt|in\s+praxis)\s+([A-Za-z0-9äöüÄÖÜß\s\-\.\'\&]{3,40})"""),
      Regex("""(?i)\b(?:restaurant|pizzeria|hotel|cafe|café|bistro|bar|sportplatz|gym|fitnessstudio|halle|stadion|klinik|praxis)\s+([A-Za-z0-9äöüÄÖÜß\s\-\.\'\&]{3,40})"""),
      // English: "at the Grand Hotel", "meet at Luigi's"
      Regex("""\b(?:at|at\s+the)\s+([A-Z][A-Za-z0-9\-\.\'\&]+(?:\s+[A-Z][A-Za-z0-9\-\.\'\&]+){0,3})"""),
    )

    for (pattern in placePatterns) {
      pattern.findAll(rawText).forEach { m ->
        addQuery(results, m.groupValues[1])
      }
      pattern.findAll(title).forEach { m ->
        addQuery(results, m.groupValues[1])
      }
    }

    return results.distinct().take(4)
  }

  private fun addQuery(results: MutableList<String>, raw: String) {
    val group = raw.trim().removeSuffix(".")
    if (group.length >= 3 && !RuleEngine.isVaguePlace(group) && results.none { it.equals(group, ignoreCase = true) }) results.add(group)
  }

  /**
   * Searches past calendar events where EVENT_LOCATION is populated and either the title or location
   * matches the given keyword.
   */
  fun searchPastCalendarLocations(context: Context, keyword: String): String? {
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      return null
    }
    val kw = keyword.trim().lowercase()
    if (kw.length < 3) return null

    val uri = CalendarContract.Events.CONTENT_URI
    val projection = arrayOf(
      CalendarContract.Events._ID,
      CalendarContract.Events.TITLE,
      CalendarContract.Events.EVENT_LOCATION,
      CalendarContract.Events.DTSTART,
    )
    val selection = "${CalendarContract.Events.EVENT_LOCATION} IS NOT NULL AND ${CalendarContract.Events.EVENT_LOCATION} != ''"
    val sortOrder = "${CalendarContract.Events.DTSTART} DESC"

    return runSafely {
      context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
        val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
        val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
        var count = 0
        while (cursor.moveToNext() && count < 500) {
          count++
          val t = cursor.getString(titleIdx).orEmpty().lowercase()
          val l = cursor.getString(locIdx).orEmpty()
          val lLower = l.lowercase()

          // If the keyword matches event title or existing location label, and the location has more detail than just the keyword
          if ((t.contains(kw) || lLower.contains(kw)) && l.isNotBlank() && !lLower.equals(kw, ignoreCase = true)) {
            return@use l
          }
        }
        null
      }
    }.getOrNull()
  }

  /** Identifies the app as required by the Nominatim/Photon usage policies (no made-up contact address). */
  internal fun geoHeaders() = mapOf(
    "User-Agent" to "NotiToCal/${de.juliandigitalproducts.notitocal.BuildConfig.VERSION_NAME} (Android; +https://github.com/juliandigitalproducts/NotiToCal)",
    "Accept-Language" to "de,en;q=0.8",
  )

  internal fun coarse(v: Double) = Math.round(v * 100.0) / 100.0

  /**
   * Place texts and found addresses go into the diagnostic log, which ends up in bug reports: log only the length and
   * a short hash (enough to see whether two log lines concern the same place), never the text itself.
   */
  internal fun redact(text: String?): String {
    if (text.isNullOrBlank()) return "[leer]"
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.trim().lowercase(Locale.ROOT).toByteArray())
    val hash = digest.take(3).joinToString("") { "%02x".format(it) }
    return "[${text.length} Zeichen #$hash]"
  }

  private val throttleLock = kotlinx.coroutines.sync.Mutex()
  @Volatile private var lastGeoRequest = 0L

  /** At most one request per second to the public OSM services (Nominatim usage policy). */
  internal suspend fun throttle() = throttleLock.withLock {
    val wait = 1_000L - (System.currentTimeMillis() - lastGeoRequest)
    if (wait > 0) kotlinx.coroutines.delay(wait)
    lastGeoRequest = System.currentTimeMillis()
  }

  /**
   * Queries Komoot Photon Geocoding API for fast, reliable address and venue search.
   */
  suspend fun searchPhoton(
    query: String,
    lat: Double? = null,
    lon: Double? = null,
    limit: Int = 1,
  ): EnrichedAddress? = withContext(Dispatchers.IO) {
    val clean = query.trim()
    if (clean.length < 3) return@withContext null

    val encoded = runSafely { URLEncoder.encode(clean, "UTF-8") }.getOrDefault(clean)
    val urlBuilder = StringBuilder("https://photon.komoot.io/api/?q=").append(encoded)
    if (lat != null && lon != null) {
      // Only a coarse bias (~1 km) leaves the device, never the exact home coordinates.
      urlBuilder.append("&lat=").append(coarse(lat)).append("&lon=").append(coarse(lon))
    }
    urlBuilder.append("&limit=").append(limit)

    val headers = geoHeaders()

    runSafely {
      throttle()
      val jsonStr = Http.get(urlBuilder.toString(), headers = headers, timeoutMs = 4_000)
      val root = AppJson.parseToJsonElement(jsonStr).jsonObject
      val features = root["features"]?.jsonArray
      if (!features.isNullOrEmpty()) {
        val first = features[0].jsonObject
        val props = first["properties"]?.jsonObject ?: return@runSafely null

        val street = props["street"]?.jsonPrimitive?.contentOrNull
          ?: props["name"]?.jsonPrimitive?.contentOrNull
        val houseNumber = props["housenumber"]?.jsonPrimitive?.contentOrNull
        val postcode = props["postcode"]?.jsonPrimitive?.contentOrNull
        val city = props["city"]?.jsonPrimitive?.contentOrNull
          ?: props["town"]?.jsonPrimitive?.contentOrNull
          ?: props["village"]?.jsonPrimitive?.contentOrNull
          ?: props["municipality"]?.jsonPrimitive?.contentOrNull

        if (!street.isNullOrBlank() && !city.isNullOrBlank()) {
          val streetWithNum = if (!houseNumber.isNullOrBlank()) "$street $houseNumber" else street
          val cityWithZip = if (!postcode.isNullOrBlank()) "$postcode $city" else city
          val formatted = "$streetWithNum, $cityWithZip"
          return@runSafely EnrichedAddress(
            street = street,
            houseNumber = houseNumber,
            postalCode = postcode,
            city = city,
            formatted = formatted,
          )
        }
      }
      null
    }.getOrNull()
  }

  /**
   * Queries OpenStreetMap / Nominatim search API for venue/place resolution.
   */
  suspend fun searchNominatim(query: String, effort: String = "balanced"): String? = withContext(Dispatchers.IO) {
    val clean = query.trim()
    if (clean.length < 3) return@withContext null

    val encoded = runSafely { URLEncoder.encode(clean, "UTF-8") }.getOrDefault(clean)
    val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1&addressdetails=1"
    val headers = geoHeaders()

    val result = runSafely {
      val timeout = if (effort == "thorough") 8_000 else 4_000
      throttle()
      val jsonStr = Http.get(url, headers = headers, timeoutMs = timeout)
      val array = AppJson.parseToJsonElement(jsonStr).jsonArray
      if (array.isNotEmpty()) {
        val first = array[0].jsonObject
        val displayName = first["display_name"]?.jsonPrimitive?.contentOrNull
        val addressObj = first["address"]?.jsonObject

        if (addressObj != null) {
          val road = addressObj["road"]?.jsonPrimitive?.contentOrNull
          val houseNumber = addressObj["house_number"]?.jsonPrimitive?.contentOrNull
          val postcode = addressObj["postcode"]?.jsonPrimitive?.contentOrNull
          val city = addressObj["city"]?.jsonPrimitive?.contentOrNull
            ?: addressObj["town"]?.jsonPrimitive?.contentOrNull
            ?: addressObj["village"]?.jsonPrimitive?.contentOrNull

          if (!road.isNullOrBlank() && !city.isNullOrBlank()) {
            val streetWithNum = if (!houseNumber.isNullOrBlank()) "$road $houseNumber" else road
            val cityWithZip = if (!postcode.isNullOrBlank()) "$postcode $city" else city
            return@runSafely "$clean, $streetWithNum, $cityWithZip"
          }
        }
        if (!displayName.isNullOrBlank()) {
          val parts = displayName.split(",").map { p -> p.trim() }
          return@runSafely parts.take(4).joinToString(", ")
        }
      }
      null
    }.getOrNull()

    result
  }

  /**
   * Reverse-geocodes latitude and longitude into Postal Code and City via Geocoder with Photon fallback.
   */
  suspend fun reverseGeocode(context: Context, lat: Double, lon: Double): Pair<String, String>? = withContext(Dispatchers.IO) {
    // 1. Android Geocoder
    val geocoderMatch = runSafely {
      val geocoder = Geocoder(context, Locale.getDefault())
      @Suppress("DEPRECATION")
      val list = geocoder.getFromLocation(lat, lon, 1)
      if (!list.isNullOrEmpty()) {
        val addr = list[0]
        val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
        val zip = addr.postalCode
        if (!city.isNullOrBlank()) {
          return@runSafely Pair(zip.orEmpty(), city)
        }
      }
      null
    }.getOrNull()

    if (geocoderMatch != null) return@withContext geocoderMatch

    // 2. Photon reverse geocoding fallback
    val photonMatch = runSafely {
      val url = "https://photon.komoot.io/reverse?lat=$lat&lon=$lon"
      val jsonStr = Http.get(url, timeoutMs = 4_000)
      val root = AppJson.parseToJsonElement(jsonStr).jsonObject
      val features = root["features"]?.jsonArray
      if (!features.isNullOrEmpty()) {
        val props = features[0].jsonObject["properties"]?.jsonObject
        val city = props?.get("city")?.jsonPrimitive?.contentOrNull
          ?: props?.get("town")?.jsonPrimitive?.contentOrNull
          ?: props?.get("village")?.jsonPrimitive?.contentOrNull
          ?: props?.get("municipality")?.jsonPrimitive?.contentOrNull
        val zip = props?.get("postcode")?.jsonPrimitive?.contentOrNull
        if (!city.isNullOrBlank()) {
          return@runSafely Pair(zip.orEmpty(), city)
        }
      }
      null
    }.getOrNull()

    photonMatch
  }

  /**
   * Parses and enriches a sample message for the live preview under "Stile".
   */
  suspend fun parseAndEnrichSample(
    sampleText: String,
    s: AppSettings,
    now: LocalDateTime = LocalDateTime.now(),
  ): ParsedEntityResult = withContext(Dispatchers.IO) {
    val clean = sampleText.trim()
    val whenParsed = DateParser.parse(clean, now)
    val streetPair = extractStreetAndNumber("", clean)
    val candidateCities = LocationMemory.getCandidateCities("", s)

    var resolvedStreet = streetPair?.first
    var resolvedNum = streetPair?.second
    var resolvedCity: String? = null
    var resolvedPostalCode: String? = null
    var fullAddress: String? = null

    // The preview follows the same rule as the pipeline: no online lookup without consent for a local engine.
    if (streetPair != null && s.onlineLocationAllowed) {
      val (st, num) = streetPair
      // Try candidate cities
      for (candidate in candidateCities) {
        val query = "$st $num, ${candidate.city}"
        val res = searchPhoton(query, lat = s.homeLatitude, lon = s.homeLongitude)
        if (res != null) {
          resolvedStreet = res.street
          resolvedNum = res.houseNumber ?: num
          resolvedCity = res.city
          resolvedPostalCode = res.postalCode
          fullAddress = res.formatted
          break
        }
      }
      if (fullAddress == null && s.homeLatitude != null && s.homeLongitude != null) {
        val res = searchPhoton("$st $num", lat = s.homeLatitude, lon = s.homeLongitude)
        if (res != null) {
          resolvedStreet = res.street
          resolvedNum = res.houseNumber ?: num
          resolvedCity = res.city
          resolvedPostalCode = res.postalCode
          fullAddress = res.formatted
        }
      }
      if (fullAddress == null && s.homeCity.isNotBlank()) {
        resolvedCity = s.homeCity
        resolvedPostalCode = s.homePostalCode.ifBlank { null }
        fullAddress = "$st $num, ${listOfNotNull(resolvedPostalCode, resolvedCity).joinToString(" ")}"
      }
    }

    val extractedLoc = fullAddress ?: RuleEngine.extractLocation(clean)
    // Shown in the app: app language. Title and description follow the message.
    val ui = TextLang.app()
    val lang = TextLang.of(clean)
    val dateStr = whenParsed.date?.format(DateTimeFormatter.ofPattern(if (ui == TextLang.DE) "EEE dd. MMM" else "EEE d MMM", ui.locale))
    val timeStr = whenParsed.time?.let { Wording.hhmm(it) + if (ui == TextLang.DE) " Uhr" else "" }

    val whenLabel = when {
      dateStr != null && timeStr != null -> "$dateStr · $timeStr"
      dateStr != null -> dateStr
      timeStr != null -> (if (ui == TextLang.DE) "Heute" else "Today") + " · $timeStr"
      else -> if (ui == TextLang.DE) "Demnächst" else "Soon"
    }

    // Infer title & description
    val title = Activity.find(clean)?.title(lang) ?: clean.take(40).ifBlank { Wording.fallbackTitle(lang) }
    val desc = clean.takeIf { it.length > title.length } ?: if (lang == TextLang.DE) "Details & Notizen aus der Nachricht" else "Details & notes from the message"

    ParsedEntityResult(
      title = title,
      description = desc,
      date = dateStr,
      time = timeStr,
      street = resolvedStreet,
      houseNumber = resolvedNum,
      city = resolvedCity,
      postalCode = resolvedPostalCode,
      fullAddress = fullAddress ?: extractedLoc,
      whenLabel = whenLabel,
    )
  }

  /**
   * Scans existing calendar events in a given time window (e.g. next 30 days) that have
   * missing or incomplete locations, and generates enrichment proposals.
   */
  suspend fun scanCalendarEventsForImprovement(
    context: Context,
    daysAhead: Int = 30,
    s: AppSettings,
  ): List<LocationProposal> = withContext(Dispatchers.IO) {
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      return@withContext emptyList()
    }

    val now = System.currentTimeMillis()
    val end = now + (daysAhead.toLong() * 24 * 60 * 60 * 1000L)

    val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(builder, now)
    ContentUris.appendId(builder, end)

    val proposals = mutableListOf<LocationProposal>()
    val effort = s.locationSearchEffort.ifBlank { "balanced" }
    // Without consent, a local engine (RULES / ON_DEVICE) keeps place names on the device.
    val sources = s.effectiveLocationSources

    val uri = builder.build()
    val projection = arrayOf(
      CalendarContract.Instances.EVENT_ID,
      CalendarContract.Instances.TITLE,
      CalendarContract.Instances.BEGIN,
      CalendarContract.Instances.EVENT_LOCATION,
      CalendarContract.Instances.DESCRIPTION,
    )

    runSafely {
      context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
        val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        val descIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)

        while (cursor.moveToNext() && proposals.size < 60) {
          val eventId = cursor.getLong(idIdx)
          val title = cursor.getString(titleIdx).orEmpty()
          val begin = cursor.getLong(beginIdx)
          val currentLoc = cursor.getString(locIdx).orEmpty()
          val desc = cursor.getString(descIdx).orEmpty()

          // If current location is already a full address, skip
          if (RuleEngine.fullAddress(currentLoc) != null) continue

          val queries = extractSearchQueries(currentLoc, "$title\n$desc", title)
          if (queries.isEmpty()) continue

          var proposed: String? = null
          var foundSource = ""

          for (q in queries) {
            if ("history" in sources) {
              val hist = searchPastCalendarLocations(context, q)
              if (!hist.isNullOrBlank()) {
                proposed = hist
                foundSource = "Terminhistorie"
                break
              }
            }
            if ("photon" in sources) {
              val ph = searchPhoton(q, lat = s.homeLatitude, lon = s.homeLongitude)
              if (ph != null) {
                proposed = ph.formatted
                foundSource = "Photon"
                break
              }
            }
            if ("osm" in sources && effort != "fast") {
              val osm = searchNominatim(q, effort)
              if (!osm.isNullOrBlank()) {
                proposed = osm
                foundSource = "OpenStreetMap"
                break
              }
            }
          }

          if (!proposed.isNullOrBlank() && !proposed.equals(currentLoc, ignoreCase = true)) {
            proposals.add(
              LocationProposal(
                eventId = eventId,
                title = title,
                startMillis = begin,
                currentAddress = currentLoc,
                proposedAddress = proposed,
                source = foundSource,
                selected = true,
              )
            )
          }
        }
      }
    }

    proposals
  }

  /**
   * Applies the approved location proposals by updating the device calendar events.
   */
  fun applyImprovements(context: Context, proposals: List<LocationProposal>): Int {
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      return 0
    }
    var count = 0
    proposals.filter { it.selected }.forEach { p ->
      runSafely {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, p.eventId)
        val values = ContentValues().apply {
          put(CalendarContract.Events.EVENT_LOCATION, p.proposedAddress)
        }
        val rows = context.contentResolver.update(uri, values, null, null)
        if (rows > 0) count++
      }
    }
    return count
  }
}
