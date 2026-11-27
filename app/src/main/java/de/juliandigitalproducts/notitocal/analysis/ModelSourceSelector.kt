// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

/**
 * Chooses where the on-device model is read from. A shared file (opened by the user and kept in place)
 * wins while it is still readable. An imported copy is the fallback.
 */
object ModelSourceSelector {
  enum class Choice { SHARED, IMPORTED, UNAVAILABLE }

  data class Input(
    val sharedUri: String,
    val sharedAccessible: Boolean,
    val importedPath: String,
    val importedReadable: Boolean,
  )

  data class Decision(
    val choice: Choice,
    /** Local copy path when [choice] is [Choice.IMPORTED]. */
    val importedPath: String = "",
    /** Local copy to try when loading the shared file fails. */
    val fallbackPath: String? = null,
    /** A shared URI is saved, but it can no longer be opened. */
    val sharedMissing: Boolean = false,
  )

  fun select(input: Input): Decision {
    val imported = input.importedPath.isNotBlank() && input.importedReadable
    val shared = input.sharedUri.isNotBlank() && input.sharedAccessible
    val sharedMissing = input.sharedUri.isNotBlank() && !input.sharedAccessible
    return when {
      shared -> Decision(Choice.SHARED, fallbackPath = input.importedPath.takeIf { imported }, sharedMissing = false)
      imported -> Decision(Choice.IMPORTED, importedPath = input.importedPath, sharedMissing = sharedMissing)
      else -> Decision(Choice.UNAVAILABLE, sharedMissing = sharedMissing)
    }
  }

  /** MediaPipe model path for an already-open file descriptor. The descriptor must stay open. */
  fun descriptorPath(fd: Int): String {
    require(fd >= 0) { "file descriptor" }
    return "/proc/self/fd/$fd"
  }
}
