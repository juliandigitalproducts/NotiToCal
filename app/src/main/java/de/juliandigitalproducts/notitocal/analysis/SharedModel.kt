// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.net.toUri
import de.juliandigitalproducts.notitocal.model.runSafely

/** Opens a model file the user picked, without copying it, and keeps the descriptor alive. */
object SharedModel {
  class HeldDescriptor(private val pfd: ParcelFileDescriptor) : AutoCloseable {
    val path: String = ModelSourceSelector.descriptorPath(pfd.fd)
    override fun close() {
      runSafely { pfd.close() }
    }
  }

  fun accessible(context: Context, uriString: String): Boolean {
    if (uriString.isBlank()) return false
    val uri = runSafely { uriString.toUri() }.getOrNull() ?: return false
    val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    if (!persisted) return false
    return runSafely {
      context.contentResolver.openFileDescriptor(uri, "r")?.use { it.fd >= 0 } ?: false
    }.getOrDefault(false)
  }

  fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) return c.getString(0)?.takeIf { it.isNotBlank() } ?: "model.task"
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "model.task"
  }

  fun open(context: Context, uriString: String): HeldDescriptor {
    val uri = uriString.toUri()
    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("The shared model file is no longer accessible")
    return HeldDescriptor(pfd)
  }
}

/** Last failure while opening a shared model in place, shown on the model screen. */
object ModelLoadStatus {
  @Volatile var sharedLoadError: String? = null
}
