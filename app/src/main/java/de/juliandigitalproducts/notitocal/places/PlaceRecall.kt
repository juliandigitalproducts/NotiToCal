package de.juliandigitalproducts.notitocal.places

import android.content.Context
import de.juliandigitalproducts.notitocal.analysis.rx
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.runSafely

/**
 * Turns "beim Italiener (in der Hauptstraße)" into a concrete place. Order:
 * 1. the location history: places of that kind the user really goes to (indexed lookup, at most 3 rows ranked),
 * 2. OpenStreetMap around home: the venue of that kind in the named street, or (without a street) only one the user
 *    has visited; the winner is remembered with its kind, so next time step 1 finds it offline.
 * The AI never sees the history: it is ranked here and only the chosen place goes into the appointment.
 */
object PlaceRecall {
  data class Found(val label: String, val visits: Int, val online: Boolean)

  private val STREET = rx(
    """(?:in\s+der|an\s+der|auf\s+der|in|an|on|at)\s+((?:[A-ZÄÖÜ][\p{L}\-]*\s+)?[\p{L}\-]*(?:straße|strasse|str\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee)|[A-Z][\p{L}\-]+\s+(?:Street|Road|Avenue|Lane|St\.?|Rd\.?|Ave\.?))(?![\p{L}])""",
    ignoreCase = false,
  )

  private val BARE_STREET = rx("""^[\p{L}\-\s.]*(?:straße|strasse|str\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee|street|road|avenue|lane)$""")

  /** "Hauptstraße" without a house number: a street to search in, not an address. */
  fun isBareStreet(location: String): Boolean = BARE_STREET.matches(location.trim())

  /** What was written, when no concrete place is found: "Italiener, Hauptstraße". */
  fun asWritten(kind: VenueKind, text: String): String? =
    listOfNotNull(VenueKind.wordIn(text, kind)?.replaceFirstChar { it.uppercase() }, street(text)).joinToString(", ").ifBlank { null }

  /** "beim Italiener in der Hauptstraße" → "Hauptstraße" (without a house number; with one it is an address). */
  fun street(text: String): String? = STREET.find(text)?.groupValues?.get(1)?.trim()

  suspend fun find(context: Context, s: AppSettings, kind: VenueKind, text: String): Found? {
    val store = PlaceStore.get(context)
    val street = street(text)
    val online = "osm" in s.effectiveLocationSources && s.locationSearchEffort != "fast"
    val ref = reference(store, s, online)

    // 1. Where the user usually goes.
    val known = runSafely { store.candidates(kind, ref) }.getOrDefault(emptyList())
      .filter { it.visits > 0 && it.label != null }
      .let { list -> if (street == null) list else list.filter { p -> matchesStreet(p.address ?: p.name, street) } }
    known.firstOrNull()?.let { return Found(it.label!!, it.visits, online = false) }

    // 2. Venues of that kind in the named street, or ones the user has been to: by name first (fast), then by
    //    OpenStreetMap tags (finds "Capone" without "Pizzeria" in the name, but the public server is often busy).
    if (!online || ref == null) return null
    val venues = search(kind, ref, street, store)
    val best = venues.take(15).maxByOrNull { v ->
      val visited = store.visitsNear(v.lat, v.lon)
      visited * 10.0 - PlaceStore.distanceMeters(ref.first, ref.second, v.lat, v.lon) / 1000.0 + if (v.name != null) 0.5 else 0.0
    } ?: return null
    val label = listOfNotNull(best.name, best.address).joinToString(", ").ifBlank { return null }
    runSafely { store.remember(best.lat, best.lon, best.name, best.address, kind) }
    return Found(label, store.visitsNear(best.lat, best.lon), online = true)
  }

  /**
   * With a street ("beim Italiener in der Hauptstraße") the venue of that kind in that street, in the home town or
   * nearby towns. Without one, only a venue the user has actually been to: the closest restaurant of some kind is a
   * guess, not the place that was meant.
   */
  private suspend fun search(kind: VenueKind, ref: Pair<Double, Double>, street: String?, store: PlaceStore): List<PlaceOnline.Venue> {
    val (lat, lon) = ref
    if (street != null) {
      PlaceOnline.byName(kind, lat, lon, 25_000, street).filter { matchesStreet(it.address, street) }.ifEmpty { null }?.let { return it }
      return PlaceOnline.nearby(kind, lat, lon, 25_000, street)
    }
    if (runSafely { store.count() }.getOrDefault(0) == 0) return emptyList()
    val visited: (PlaceOnline.Venue) -> Boolean = { store.visitsNear(it.lat, it.lon) > 0 }
    PlaceOnline.byName(kind, lat, lon, 10_000).filter(visited).ifEmpty { null }?.let { return it }
    return PlaceOnline.nearby(kind, lat, lon, 10_000).filter(visited)
  }

  /** Where "nearby" is: the home position from the settings, else the home in the history, else the home town. */
  private suspend fun reference(store: PlaceStore, s: AppSettings, online: Boolean): Pair<Double, Double>? {
    if (s.homeLatitude != null && s.homeLongitude != null) return s.homeLatitude to s.homeLongitude
    runSafely { store.home() }.getOrNull()?.let { return it.lat to it.lon }
    return if (online && s.homeCity.isNotBlank()) PlaceOnline.cityCenter(s.homeCity) else null
  }

  private fun matchesStreet(text: String?, street: String): Boolean {
    val stem = PlaceOnline.streetStem(street).lowercase()
    return text?.lowercase()?.replace("straße", "str")?.replace("strasse", "str")?.contains(stem) == true
  }
}
