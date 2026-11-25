package de.juliandigitalproducts.notitocal.analysis

import de.juliandigitalproducts.notitocal.data.AppSettings
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

/**
 * Lightweight, privacy-preserving location memory across notifications and chats.
 * Maintains time-decayed frequency scores of recognized cities per sender/group and globally.
 */
object LocationMemory {

  data class LocationRecord(
    val city: String,
    val postalCode: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
  )

  data class CandidateCity(
    val city: String,
    val postalCode: String?,
    val score: Double,
  )

  // In-memory weighted city memory per sender / group
  private val chatMemory = ConcurrentHashMap<String, MutableList<LocationRecord>>()
  // Global city memory
  private val globalMemory = mutableListOf<LocationRecord>()

  private const val TAU_MILLIS = 30.0 * 24.0 * 60.0 * 60.0 * 1000.0 // 30 days decay time constant

  @Synchronized
  fun recordLocation(
    senderOrGroup: String,
    city: String,
    postalCode: String? = null,
    timestamp: Long = System.currentTimeMillis(),
  ) {
    val cleanCity = city.trim()
    if (cleanCity.length < 2) return
    val cleanSender = senderOrGroup.trim().lowercase()

    val record = LocationRecord(cleanCity, postalCode?.trim()?.takeIf { it.isNotBlank() }, timestamp)
    if (cleanSender.isNotBlank()) {
      val list = chatMemory.computeIfAbsent(cleanSender) { mutableListOf() }
      list.add(record)
      if (list.size > 50) list.removeAt(0)
    }

    globalMemory.add(record)
    if (globalMemory.size > 200) globalMemory.removeAt(0)
  }

  /**
   * Returns a scored list of candidate cities for a given sender/group, taking into account:
   * 1. Chat memory (weight 3.0 with time decay)
   * 2. Global memory (weight 1.5 with time decay)
   * 3. User home location (weight 2.0)
   */
  @Synchronized
  fun getCandidateCities(
    senderOrGroup: String,
    s: AppSettings,
    now: Long = System.currentTimeMillis(),
  ): List<CandidateCity> {
    val scores = mutableMapOf<String, Double>()
    val postcodes = mutableMapOf<String, String>()

    // 1. Chat/Group memory (weight 3.0 with decay)
    val cleanSender = senderOrGroup.trim().lowercase()
    if (cleanSender.isNotBlank()) {
      chatMemory[cleanSender]?.forEach { rec ->
        val age = (now - rec.timestamp).coerceAtLeast(0L)
        val weight = 3.0 * exp(-age / TAU_MILLIS)
        scores[rec.city] = (scores[rec.city] ?: 0.0) + weight
        if (rec.postalCode != null) postcodes[rec.city] = rec.postalCode
      }
    }

    // 2. Global memory (weight 1.5 with decay)
    globalMemory.forEach { rec ->
      val age = (now - rec.timestamp).coerceAtLeast(0L)
      val weight = 1.5 * exp(-age / TAU_MILLIS)
      scores[rec.city] = (scores[rec.city] ?: 0.0) + weight
      if (rec.postalCode != null && !postcodes.containsKey(rec.city)) postcodes[rec.city] = rec.postalCode
    }

    // 3. User Home Location (weight 2.0)
    if (s.homeCity.isNotBlank()) {
      val home = s.homeCity.trim()
      scores[home] = (scores[home] ?: 0.0) + 2.0
      if (s.homePostalCode.isNotBlank()) postcodes[home] = s.homePostalCode.trim()
    }

    return scores.entries
      .sortedByDescending { it.value }
      .map { CandidateCity(city = it.key, postalCode = postcodes[it.key], score = it.value) }
  }

  @Synchronized
  fun clear() {
    chatMemory.clear()
    globalMemory.clear()
  }
}
