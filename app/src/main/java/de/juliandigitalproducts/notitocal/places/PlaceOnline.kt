package de.juliandigitalproducts.notitocal.places

import de.juliandigitalproducts.notitocal.analysis.LocationEnricher
import de.juliandigitalproducts.notitocal.data.AppJson
import de.juliandigitalproducts.notitocal.model.runSafely
import de.juliandigitalproducts.notitocal.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Venues from OpenStreetMap (Overpass) and city centres (Photon). Only a coarse position (~1 km) leaves the device,
 * never the visited places themselves: matching results against the location history happens on the phone.
 */
object PlaceOnline {
  data class Venue(val lat: Double, val lon: Double, val name: String?, val address: String?, val street: String?)

  private const val OVERPASS = "https://overpass-api.de/api/interpreter"

  /**
   * Venues of [kind] around [lat]/[lon], closest first. With [street] only venues in that street are returned
   * (searched in a wider radius, since a named street narrows it down anyway).
   */
  suspend fun nearby(kind: VenueKind, lat: Double, lon: Double, radiusMeters: Int, street: String? = null): List<Venue> =
    withContext(Dispatchers.IO) {
      if (!kind.searchable) return@withContext emptyList()
      val cLat = LocationEnricher.coarse(lat)
      val cLon = LocationEnricher.coarse(lon)
      val streetFilter = street?.let { """["addr:street"~"^${quote(streetStem(it))}",i]""" }.orEmpty()
      val parts = kind.osm.joinToString("") { (key, values) ->
        """nwr["$key"~"^($values)$",i]$streetFilter(around:$radiusMeters,$cLat,$cLon);"""
      }
      val query = "[out:json][timeout:6];($parts);out center tags 30;"
      val json = runSafely {
        LocationEnricher.throttle()
        Http.get("$OVERPASS?data=" + URLEncoder.encode(query, "UTF-8"), headers = LocationEnricher.geoHeaders(), timeoutMs = 7_000)
      }.getOrNull() ?: return@withContext emptyList()
      parse(json).sortedBy { PlaceStore.distanceMeters(lat, lon, it.lat, it.lon) }
    }

  /**
   * Venues of [kind] found by the words in their names ("Pizzeria …", "Ristorante …") with Photon, biased to
   * [lat]/[lon] and kept within [radiusMeters]. Fast and reliable; misses venues whose name tells nothing.
   */
  suspend fun byName(kind: VenueKind, lat: Double, lon: Double, radiusMeters: Int, street: String? = null): List<Venue> =
    withContext(Dispatchers.IO) {
      val key = kind.photonKey ?: return@withContext emptyList()
      kind.nameTerms.take(3).flatMap { term ->
        val q = if (street != null) "$term $street" else term
        val url = "https://photon.komoot.io/api/?q=" + URLEncoder.encode(q, "UTF-8") +
          "&lat=${LocationEnricher.coarse(lat)}&lon=${LocationEnricher.coarse(lon)}&limit=10&osm_tag=$key"
        runSafely {
          LocationEnricher.throttle()
          parsePhoton(Http.get(url, headers = LocationEnricher.geoHeaders(), timeoutMs = 5_000))
        }.getOrDefault(emptyList()).filter { v ->
          val name = v.name?.lowercase().orEmpty()
          term.lowercase().split(' ').first() in name || VenueKind.ofName(v.name) == kind
        }
      }
        .filter { PlaceStore.distanceMeters(lat, lon, it.lat, it.lon) <= radiusMeters }
        .distinctBy { "${it.name}|${it.address}" }
        .sortedBy { PlaceStore.distanceMeters(lat, lon, it.lat, it.lon) }
    }

  internal fun parsePhoton(json: String): List<Venue> = runSafely {
    AppJson.parseToJsonElement(json).jsonObject["features"]?.jsonArray.orEmpty().mapNotNull { f ->
      val o = f.jsonObject
      val coords = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
      val lon = coords?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
      val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
      val p = o["properties"] as? JsonObject
      fun prop(k: String) = p?.get(k)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
      val street = prop("street")
      val line1 = listOfNotNull(street, prop("housenumber")).joinToString(" ").ifBlank { null }
      val line2 = listOfNotNull(prop("postcode"), prop("city") ?: prop("town") ?: prop("village")).joinToString(" ").ifBlank { null }
      Venue(lat, lon, prop("name"), listOfNotNull(line1, line2).joinToString(", ").ifBlank { null }, street)
    }
  }.getOrDefault(emptyList())

  /** Centre of a town ("Köln") for searches when no home position is known. */
  suspend fun cityCenter(city: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    if (city.isBlank()) return@withContext null
    runSafely {
      LocationEnricher.throttle()
      val url = "https://photon.komoot.io/api/?q=" + URLEncoder.encode(city, "UTF-8") + "&limit=1&osm_tag=place"
      val root = AppJson.parseToJsonElement(Http.get(url, headers = LocationEnricher.geoHeaders(), timeoutMs = 5_000)).jsonObject
      val coords = root["features"]?.jsonArray?.firstOrNull()?.jsonObject?.get("geometry")?.jsonObject?.get("coordinates")?.jsonArray
      val lon = coords?.getOrNull(0)?.jsonPrimitive?.doubleOrNull
      val lat = coords?.getOrNull(1)?.jsonPrimitive?.doubleOrNull
      if (lat != null && lon != null) lat to lon else null
    }.getOrNull()
  }

  internal fun parse(json: String): List<Venue> = runSafely {
    AppJson.parseToJsonElement(json).jsonObject["elements"]?.jsonArray.orEmpty().mapNotNull { el ->
      val o = el.jsonObject
      val center = o["center"]?.jsonObject ?: o
      val lat = center["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
      val lon = center["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
      val tags = o["tags"] as? JsonObject
      fun tag(k: String) = tags?.get(k)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
      val street = tag("addr:street")
      val line1 = listOfNotNull(street, tag("addr:housenumber")).joinToString(" ").ifBlank { null }
      val line2 = listOfNotNull(tag("addr:postcode"), tag("addr:city")).joinToString(" ").ifBlank { null }
      Venue(lat, lon, tag("name"), listOfNotNull(line1, line2).joinToString(", ").ifBlank { null }, street)
    }
  }.getOrDefault(emptyList())

  /** "Hauptstraße" also matches "Hauptstr." and "Hauptstrasse" in the data. */
  internal fun streetStem(street: String): String =
    street.trim().replace(Regex("""(?i)(straße|strasse|str\.?)$"""), "str")

  /** Overpass regexes have no quoting: street names only keep letters, digits, spaces and hyphens. */
  private fun quote(s: String) = s.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
}
