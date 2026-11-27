// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.places

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream

/**
 * Reads a Google Maps timeline export without loading it into memory (they reach hundreds of MB):
 * - "Timeline.json" exported on the phone (2024+): `semanticSegments[].visit.topCandidate`,
 * - the iPhone variant: a top-level array of `{visit: {topCandidate: {placeLocation: "geo:…"}}}`,
 * - Google Takeout: monthly `timelineObjects[].placeVisit` files, also as the whole Takeout .zip.
 * Only visits are taken; raw GPS points ("Records.json", `rawSignals`) are skipped.
 */
object TimelineImport {
  private const val BATCH = 500

  data class Result(val visits: Int, val newPlaces: Int)

  /** Parses [input] (JSON or ZIP, told apart by the first bytes) and stores the visits in [store]. */
  fun run(input: InputStream, store: PlaceStore, onProgress: (visits: Int) -> Unit = {}): Result {
    val buffered = input.buffered()
    buffered.mark(4)
    val zip = buffered.read() == 'P'.code && buffered.read() == 'K'.code
    buffered.reset()
    var visits = 0
    var created = 0
    val batch = ArrayList<Visit>(BATCH)
    fun flush() {
      created += store.record(batch)
      visits += batch.size
      batch.clear()
      onProgress(visits)
    }
    val sink: (Visit) -> Unit = { v -> batch += v; if (batch.size >= BATCH) flush() }
    if (zip) {
      ZipInputStream(buffered).use { z ->
        generateSequence { z.nextEntry }.forEach { e ->
          val name = e.name.substringAfterLast('/')
          // Records.json holds every raw GPS fix; the visits in the other files are what matters.
          if (!e.isDirectory && name.endsWith(".json", true) && !name.equals("Records.json", true) && !name.equals("Settings.json", true)) {
            parse(NonClosing(z), sink)
          }
        }
      }
    } else {
      buffered.use { parse(it, sink) }
    }
    flush()
    return Result(visits, created)
  }

  /** Streams one JSON document and hands every visit in it to [sink]. */
  fun parse(input: InputStream, sink: (Visit) -> Unit) {
    val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
    reader.isLenient = true
    when (reader.peek()) {
      JsonToken.BEGIN_ARRAY -> eachElement(reader) { visitOf(it)?.let(sink) }
      JsonToken.BEGIN_OBJECT -> {
        reader.beginObject()
        while (reader.hasNext()) {
          when (reader.nextName()) {
            "semanticSegments", "timelineObjects" -> eachElement(reader) { visitOf(it)?.let(sink) }
            else -> reader.skipValue()
          }
        }
        reader.endObject()
      }
      else -> reader.skipValue()
    }
  }

  private fun eachElement(reader: JsonReader, each: (Map<*, *>) -> Unit) {
    if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return }
    reader.beginArray()
    while (reader.hasNext()) {
      // One segment is small: it is read as a tree, the array around it is streamed.
      (readTree(reader) as? Map<*, *>)?.let(each)
    }
    reader.endArray()
  }

  private fun readTree(reader: JsonReader): Any? = when (reader.peek()) {
    JsonToken.BEGIN_OBJECT -> {
      val map = HashMap<String, Any?>()
      reader.beginObject()
      while (reader.hasNext()) {
        val key = reader.nextName()
        // Paths and activities inside a segment can be long and are not needed.
        if (key == "timelinePath" || key == "activity" || key == "activitySegment" || key == "waypoints") reader.skipValue()
        else map[key] = readTree(reader)
      }
      reader.endObject()
      map
    }
    JsonToken.BEGIN_ARRAY -> {
      val list = ArrayList<Any?>()
      reader.beginArray()
      while (reader.hasNext()) list += readTree(reader)
      reader.endArray()
      list
    }
    JsonToken.NUMBER, JsonToken.STRING -> reader.nextString()
    JsonToken.BOOLEAN -> reader.nextBoolean()
    else -> { reader.skipValue(); null }
  }

  /** A visit from one segment of any of the supported formats, or null when the segment is no visit. */
  internal fun visitOf(segment: Map<*, *>): Visit? {
    (segment["visit"] as? Map<*, *>)?.let { visit ->
      // Level 0 only: higher levels repeat the same stay as a larger area (city, district).
      if ((visit["hierarchyLevel"]?.toString()?.toIntOrNull() ?: 0) > 0) return null
      val top = visit["topCandidate"] as? Map<*, *> ?: return null
      val (lat, lon) = latLng(top["placeLocation"]) ?: return null
      val start = time(segment["startTime"]) ?: return null
      val end = time(segment["endTime"]) ?: start
      return Visit(
        lat = lat, lon = lon, start = start, minutes = minutes(start, end),
        placeId = (top["placeId"] ?: top["placeID"])?.toString(),
        kind = VenueKind.ofSemanticType(top["semanticType"]?.toString()),
      )
    }
    (segment["placeVisit"] as? Map<*, *>)?.let { pv ->
      val loc = pv["location"] as? Map<*, *> ?: return null
      val lat = loc["latitudeE7"]?.toString()?.toDoubleOrNull()?.div(1e7) ?: return null
      val lon = loc["longitudeE7"]?.toString()?.toDoubleOrNull()?.div(1e7) ?: return null
      val duration = pv["duration"] as? Map<*, *>
      val start = time(duration?.get("startTimestamp") ?: duration?.get("startTimestampMs")) ?: return null
      val end = time(duration?.get("endTimestamp") ?: duration?.get("endTimestampMs")) ?: start
      val name = loc["name"]?.toString()?.takeIf { it.isNotBlank() }
      return Visit(
        lat = lat, lon = lon, start = start, minutes = minutes(start, end),
        placeId = loc["placeId"]?.toString(),
        name = name,
        address = loc["address"]?.toString()?.replace('\n', ' ')?.takeIf { it.isNotBlank() },
        kind = VenueKind.ofSemanticType(loc["semanticType"]?.toString()) ?: VenueKind.ofName(name),
      )
    }
    return null
  }

  private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")

  /** "52.5200°, 13.4050°", "geo:52.52,13.405" or `{latLng: "…"}`. */
  internal fun latLng(value: Any?): Pair<Double, Double>? {
    val text = when (value) {
      is Map<*, *> -> value["latLng"]?.toString() ?: value["point"]?.toString()
      else -> value?.toString()
    } ?: return null
    val nums = NUMBER.findAll(text).map { it.value.toDouble() }.toList()
    if (nums.size < 2) return null
    val (lat, lon) = nums[0] to nums[1]
    return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
  }

  private fun time(value: Any?): Long? {
    val s = value?.toString()?.trim().orEmpty()
    if (s.isEmpty()) return null
    s.toLongOrNull()?.let { return it }
    return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
      ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
  }

  private fun minutes(start: Long, end: Long) = ((end - start) / 60_000L).coerceIn(0L, 24 * 60L).toInt()

  /** Lets the JSON reader close its stream without closing the ZIP around it. */
  private class NonClosing(input: InputStream) : java.io.FilterInputStream(input) {
    override fun close() = Unit
  }
}
