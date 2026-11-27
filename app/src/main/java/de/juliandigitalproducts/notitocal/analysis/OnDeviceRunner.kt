// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.analysis

import android.content.Context
import de.juliandigitalproducts.notitocal.data.AppSettings
import java.io.File

/** Loads the on-device model from a shared file descriptor, or from an imported copy if that fails. */
object OnDeviceRunner {
  /** An on-device model is chosen and its file can be read. */
  fun hasModel(context: Context, s: AppSettings): Boolean =
    (s.onDeviceModelFile.isNotBlank() && File(s.onDeviceModelFile).isFile) ||
      (s.onDeviceModelUri.isNotBlank() && runCatching { SharedModel.accessible(context, s.onDeviceModelUri) }.getOrDefault(false))

  suspend fun complete(
    context: Context,
    s: AppSettings,
    system: String,
    user: String,
    maxTokens: Int,
    prefill: String = "",
  ): String {
    val importedOk = s.onDeviceModelFile.isNotBlank() && File(s.onDeviceModelFile).isFile
    val sharedOk = s.onDeviceModelUri.isNotBlank() && SharedModel.accessible(context, s.onDeviceModelUri)
    val decision = ModelSourceSelector.select(
      ModelSourceSelector.Input(s.onDeviceModelUri, sharedOk, s.onDeviceModelFile, importedOk),
    )
    return when (decision.choice) {
      ModelSourceSelector.Choice.SHARED -> sharedOrFallback(context, s, decision.fallbackPath, system, user, maxTokens, prefill)
      ModelSourceSelector.Choice.IMPORTED -> OnDeviceLlm.complete(
        context, decision.importedPath, s.onDeviceTemplate, system, user, maxTokens, prefill, sourceKey = decision.importedPath,
      )
      ModelSourceSelector.Choice.UNAVAILABLE -> error(
        if (decision.sharedMissing) "The shared model file is no longer accessible" else "No on-device model selected",
      )
    }
  }

  private suspend fun sharedOrFallback(
    context: Context,
    s: AppSettings,
    fallback: String?,
    system: String,
    user: String,
    maxTokens: Int,
    prefill: String,
  ): String {
    try {
      return completeShared(context, s, system, user, maxTokens, prefill)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      throw e
    } catch (e: Exception) {
      OnDeviceLlm.release()
      ModelLoadStatus.sharedLoadError = e.message?.take(200)
      if (fallback != null) {
        return OnDeviceLlm.complete(context, fallback, s.onDeviceTemplate, system, user, maxTokens, prefill, sourceKey = fallback)
      }
      throw e
    }
  }

  private suspend fun completeShared(context: Context, s: AppSettings, system: String, user: String, maxTokens: Int, prefill: String): String {
    if (OnDeviceLlm.isLoadedKey(s.onDeviceModelUri)) {
      return OnDeviceLlm.complete(
        context, OnDeviceLlm.loadedPath(), s.onDeviceTemplate, system, user, maxTokens, prefill, sourceKey = s.onDeviceModelUri,
      )
    }
    val opened = SharedModel.open(context, s.onDeviceModelUri)
    return OnDeviceLlm.complete(
      context, opened.path, s.onDeviceTemplate, system, user, maxTokens, prefill, sourceKey = s.onDeviceModelUri, hold = opened,
    )
  }
}
