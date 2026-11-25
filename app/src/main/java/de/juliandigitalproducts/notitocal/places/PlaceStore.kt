package de.juliandigitalproducts.notitocal.places

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlin.math.cos
import kotlin.math.sqrt

/** One row of the location history as it goes into an (encrypted) backup. */
@kotlinx.serialization.Serializable
data class PlaceRecord(
  val lat: Double,
  val lon: Double,
  val placeId: String? = null,
  val name: String? = null,
  val address: String? = null,
  val kind: String? = null,
  val visits: Int = 0,
  val minutes: Int = 0,
  val lastSeen: Long = 0,
  val resolved: Int = 0,
)

/** One stay at a place, from an imported timeline or a periodic location sample. */
data class Visit(
  val lat: Double,
  val lon: Double,
  val start: Long,
  val minutes: Int,
  val placeId: String? = null,
  val name: String? = null,
  val address: String? = null,
  val kind: VenueKind? = null,
)

/** A place the user has been to, merged from all its visits. */
data class Place(
  val id: Long,
  val lat: Double,
  val lon: Double,
  val name: String?,
  val address: String?,
  val kind: VenueKind?,
  val visits: Int,
  val minutes: Int,
  val lastSeen: Long,
  /** 0 = not looked up yet, 1 = looked up, 2 = lookup found nothing. */
  val resolved: Int,
) {
  /** "Pizzeria Capone, Hauptstraße 12, 10115 Berlin" – what goes into an appointment. */
  val label: String? get() = listOfNotNull(name, address).filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { null }
}

/**
 * The location history, kept only on this device. Visits within [SNAP_METERS] of a known place count for that place,
 * so thousands of samples stay a few hundred rows. Lookups go through indexes and return only a handful of rows:
 * nothing ever loads (or sends) the whole history.
 */
class PlaceStore(context: Context) : SQLiteOpenHelper(context, "places.db", null, 1) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE places (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        lat REAL NOT NULL, lon REAL NOT NULL,
        place_id TEXT, name TEXT, address TEXT, kind TEXT,
        visits INTEGER NOT NULL DEFAULT 0, minutes INTEGER NOT NULL DEFAULT 0,
        last_seen INTEGER NOT NULL DEFAULT 0, resolved INTEGER NOT NULL DEFAULT 0)""",
    )
    db.execSQL("CREATE INDEX places_lat ON places(lat)")
    db.execSQL("CREATE INDEX places_kind ON places(kind, visits)")
    db.execSQL("CREATE INDEX places_place_id ON places(place_id)")
    db.execSQL("CREATE INDEX places_resolved ON places(resolved, visits)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  /** Adds [visits] in one transaction; returns how many new places were created. */
  fun record(visits: List<Visit>): Int {
    if (visits.isEmpty()) return 0
    var created = 0
    writableDatabase.transaction { visits.forEach { v -> if (merge(this, v)) created++ } }
    return created
  }

  /** Merges one visit into its place (same Google place id, or within [SNAP_METERS]); true when the place is new. */
  private fun merge(db: SQLiteDatabase, v: Visit): Boolean {
    val existing = v.placeId?.let { pid -> db.query("places", COLUMNS, "place_id = ?", arrayOf(pid), null, null, null, "1").use { it.firstPlace() } }
      ?: nearest(db, v.lat, v.lon, SNAP_METERS)
    if (existing == null) {
      db.insert("places", null, ContentValues().apply {
        put("lat", v.lat); put("lon", v.lon)
        put("place_id", v.placeId); put("name", v.name); put("address", v.address); put("kind", v.kind?.name)
        put("visits", 1); put("minutes", v.minutes); put("last_seen", v.start)
        put("resolved", if (v.name != null || v.address != null) 1 else 0)
      })
      return true
    }
    db.update("places", ContentValues().apply {
      put("visits", existing.visits + 1)
      put("minutes", existing.minutes + v.minutes)
      put("last_seen", maxOf(existing.lastSeen, v.start))
      if (existing.name == null && v.name != null) put("name", v.name)
      if (existing.address == null && v.address != null) { put("address", v.address); put("resolved", 1) }
      if (existing.kind == null && v.kind != null) put("kind", v.kind.name)
      if (v.placeId != null) put("place_id", v.placeId)
    }, "id = ?", arrayOf(existing.id.toString()))
    return false
  }

  /**
   * Adds [minutes] to the place the user is at now (periodic samples). Arriving there counts as a visit; staying at
   * [previousId] (the place of the last sample) only adds time.
   */
  fun sample(lat: Double, lon: Double, at: Long, minutes: Int, previousId: Long): Long {
    val db = writableDatabase
    val existing = nearest(db, lat, lon, SNAP_METERS)
    if (existing == null) {
      return db.insert("places", null, ContentValues().apply {
        put("lat", lat); put("lon", lon); put("visits", 1); put("minutes", minutes); put("last_seen", at)
      })
    }
    db.update("places", ContentValues().apply {
      if (existing.id != previousId) put("visits", existing.visits + 1)
      put("minutes", existing.minutes + minutes)
      put("last_seen", at)
    }, "id = ?", arrayOf(existing.id.toString()))
    return existing.id
  }

  /** Places of [kind], best first: often and long visited, recently, close to [near]. At most [limit] rows. */
  fun candidates(kind: VenueKind, near: Pair<Double, Double>?, limit: Int = 3, now: Long = System.currentTimeMillis()): List<Place> {
    // The kind index keeps this small; the 50 most visited are plenty to rank.
    val rows = readableDatabase.query("places", COLUMNS, "kind = ?", arrayOf(kind.name), null, null, "visits DESC", "50").use { it.places() }
    return rank(rows, near, now).take(limit)
  }

  /** Places whose name contains [name] ("Capone" → "Pizzeria Capone"), best first. */
  fun named(name: String, near: Pair<Double, Double>?, limit: Int = 3, now: Long = System.currentTimeMillis()): List<Place> {
    val q = name.trim()
    if (q.length < 3) return emptyList()
    val rows = readableDatabase.query("places", COLUMNS, "name LIKE ?", arrayOf("%${q.replace("%", "")}%"), null, null, "visits DESC", "20").use { it.places() }
    return rank(rows, near, now).take(limit)
  }

  /** Most visited places that were never looked up (they have no name or address yet). */
  fun unresolved(limit: Int, minVisits: Int = 2): List<Place> =
    readableDatabase.query("places", COLUMNS, "resolved = 0 AND visits >= ?", arrayOf(minVisits.toString()), null, null, "visits DESC", limit.toString())
      .use { it.places() }

  fun resolve(id: Long, name: String?, address: String?, kind: VenueKind?) {
    writableDatabase.update("places", ContentValues().apply {
      put("name", name); put("address", address)
      if (kind != null) put("kind", kind.name)
      put("resolved", if (name != null || address != null) 1 else 2)
    }, "id = ?", arrayOf(id.toString()))
  }

  /** Remembers a place found online (OpenStreetMap) for a kind, so the next lookup needs no network. */
  fun remember(lat: Double, lon: Double, name: String?, address: String?, kind: VenueKind) {
    val db = writableDatabase
    val existing = nearest(db, lat, lon, SNAP_METERS)
    if (existing == null) {
      db.insert("places", null, ContentValues().apply {
        put("lat", lat); put("lon", lon); put("name", name); put("address", address); put("kind", kind.name); put("resolved", 1)
      })
    } else if (existing.kind == null) {
      resolve(existing.id, existing.name ?: name, existing.address ?: address, kind)
    }
  }

  /** Visits of the place at this spot (within 60 m), 0 when the user has never been there. */
  fun visitsNear(lat: Double, lon: Double): Int = nearest(readableDatabase, lat, lon, 60.0)?.visits ?: 0

  /** Every place, for the encrypted backup. */
  fun exportAll(): List<PlaceRecord> = readableDatabase.query(
    "places", arrayOf("lat", "lon", "place_id", "name", "address", "kind", "visits", "minutes", "last_seen", "resolved"),
    null, null, null, null, null,
  ).use { c ->
    buildList {
      while (c.moveToNext()) add(
        PlaceRecord(
          c.getDouble(0), c.getDouble(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
          c.getInt(6), c.getInt(7), c.getLong(8), c.getInt(9),
        ),
      )
    }
  }

  /**
   * Restores a backup into the history. A place that is already known (same spot) keeps its row and gains the
   * backup's visits, so importing on a phone that already collected places merges both.
   */
  fun importAll(records: List<PlaceRecord>) {
    writableDatabase.transaction {
      records.forEach { r ->
        val existing = nearest(this, r.lat, r.lon, SNAP_METERS)
        if (existing == null) {
          insert("places", null, ContentValues().apply {
            put("lat", r.lat); put("lon", r.lon); put("place_id", r.placeId); put("name", r.name); put("address", r.address)
            put("kind", r.kind); put("visits", r.visits); put("minutes", r.minutes); put("last_seen", r.lastSeen); put("resolved", r.resolved)
          })
        } else {
          update("places", ContentValues().apply {
            put("visits", existing.visits + r.visits)
            put("minutes", existing.minutes + r.minutes)
            put("last_seen", maxOf(existing.lastSeen, r.lastSeen))
            if (existing.name == null && r.name != null) put("name", r.name)
            if (existing.address == null && r.address != null) put("address", r.address)
            if (existing.kind == null && r.kind != null) put("kind", r.kind)
          }, "id = ?", arrayOf(existing.id.toString()))
        }
      }
    }
  }

  fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM places WHERE visits > 0", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

  fun home(): Place? = readableDatabase.query("places", COLUMNS, "kind = ?", arrayOf(VenueKind.HOME.name), null, null, "visits DESC", "1").use { it.firstPlace() }

  fun clear() {
    writableDatabase.delete("places", null, null)
  }

  /** The closest place within [meters], found through the latitude index (a box of a few hundred metres). */
  private fun nearest(db: SQLiteDatabase, lat: Double, lon: Double, meters: Double): Place? {
    val dLat = meters / 111_000.0
    val dLon = meters / (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
    return db.query(
      "places", COLUMNS, "lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
      arrayOf((lat - dLat).toString(), (lat + dLat).toString(), (lon - dLon).toString(), (lon + dLon).toString()),
      null, null, null, "20",
    ).use { it.places() }
      .map { it to distanceMeters(lat, lon, it.lat, it.lon) }
      .filter { it.second <= meters }
      .minByOrNull { it.second }?.first
  }

  private fun Cursor.places(): List<Place> = buildList { while (moveToNext()) add(place()) }
  private fun Cursor.firstPlace(): Place? = if (moveToFirst()) place() else null
  private fun Cursor.place() = Place(
    id = getLong(0), lat = getDouble(1), lon = getDouble(2),
    name = getString(3), address = getString(4),
    kind = getString(5)?.let { k -> VenueKind.entries.firstOrNull { it.name == k } },
    visits = getInt(6), minutes = getInt(7), lastSeen = getLong(8), resolved = getInt(9),
  )

  companion object {
    const val SNAP_METERS = 80.0
    private val RECORDS = kotlinx.serialization.builtins.ListSerializer(PlaceRecord.serializer())

    fun encode(records: List<PlaceRecord>): String = de.juliandigitalproducts.notitocal.data.AppJson.encodeToString(RECORDS, records)
    fun decode(json: String): List<PlaceRecord> = de.juliandigitalproducts.notitocal.data.AppJson.decodeFromString(RECORDS, json)
    private val COLUMNS = arrayOf("id", "lat", "lon", "name", "address", "kind", "visits", "minutes", "last_seen", "resolved")

    @Volatile private var instance: PlaceStore? = null
    fun get(context: Context): PlaceStore = instance ?: synchronized(this) {
      instance ?: PlaceStore(context.applicationContext).also { instance = it }
    }

    /**
     * Ranking: visits and time spent count most, a recent visit adds, distance from [near] costs (a place 20 km
     * away needs many more visits than one around the corner).
     */
    fun rank(places: List<Place>, near: Pair<Double, Double>?, now: Long): List<Place> = places.sortedByDescending { p ->
      val habit = p.visits + p.minutes / 120.0
      val days = ((now - p.lastSeen).coerceAtLeast(0) / 86_400_000.0)
      val recency = if (p.lastSeen > 0) 3.0 / (1.0 + days / 30.0) else 0.0
      val km = near?.let { distanceMeters(it.first, it.second, p.lat, p.lon) / 1000.0 } ?: 0.0
      (habit + recency) / (1.0 + km / 5.0)
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
      val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
      val y = Math.toRadians(lat2 - lat1)
      return sqrt(x * x + y * y) * 6_371_000.0
    }
  }
}
