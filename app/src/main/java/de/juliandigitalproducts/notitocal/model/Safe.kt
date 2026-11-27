// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.model

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but a cancelled coroutine stays cancelled (C16): runCatching also caught
 * [CancellationException], so a stopped worker kept running and failed work looked finished.
 */
inline fun <T> runSafely(block: () -> T): Result<T> = try {
  Result.success(block())
} catch (e: CancellationException) {
  throw e
} catch (e: Throwable) {
  Result.failure(e)
}
