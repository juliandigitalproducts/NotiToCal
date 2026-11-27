// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

import de.juliandigitalproducts.notitocal.data.AliasEntry
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.model.Detection

/**
 * Replaces configured terms with aliases before a language-model call, and maps aliases back afterwards.
 * Matching is case-insensitive, respects letter/digit boundaries, and tries longer terms first.
 */
object Pseudonymizer {
  fun apply(text: String, entries: List<AliasEntry>): String =
    replace(text, entries.map { it.term to it.alias })

  fun reverse(text: String, entries: List<AliasEntry>): String =
    replace(text, entries.map { it.alias to it.term })

  fun restore(detection: Detection, entries: List<AliasEntry>): Detection {
    if (entries.isEmpty()) return detection
    return detection.copy(
      title = reverse(detection.title, entries),
      description = reverse(detection.description, entries),
      location = detection.location?.let { reverse(it, entries) },
      reason = reverse(detection.reason, entries),
      category = detection.category?.let { reverse(it, entries) },
      rawAnswer = detection.rawAnswer?.let { reverse(it, entries) },
    )
  }

  /** Masks [system] and [user], runs the model [call] and maps the aliases in its answer back if the user wants that. */
  suspend fun around(settings: AppSettings, system: String, user: String, call: suspend (String, String) -> String?): String? {
    val answer = call(apply(system, settings.aliases), apply(user, settings.aliases)) ?: return null
    return if (settings.restoreAliases) reverse(answer, settings.aliases) else answer
  }

  fun restoreIf(detection: Detection, settings: AppSettings): Detection =
    if (settings.restoreAliases) restore(detection, settings.aliases) else detection

  private fun replace(text: String, pairs: List<Pair<String, String>>): String {
    val usable = pairs
      .map { it.first.trim() to it.second }
      .filter { (from, to) -> from.isNotEmpty() && to.isNotEmpty() }
      .distinctBy { it.first.lowercase() }
      .sortedByDescending { it.first.length }
    if (usable.isEmpty() || text.isEmpty()) return text
    val pattern = usable.joinToString("|") { Regex.escape(it.first) }
    val regex = Regex("(?<![\\p{L}\\p{N}])(?:$pattern)(?![\\p{L}\\p{N}])", setOf(RegexOption.IGNORE_CASE))
    return regex.replace(text) { match ->
      val found = match.value
      usable.first { it.first.equals(found, ignoreCase = true) }.second
    }
  }
}
