// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import android.content.Context
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.analysis.Decision
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import de.juliandigitalproducts.notitocal.model.Why
import kotlin.math.roundToInt

private fun pct(v: Double?) = ((v ?: 0.0) * 100).roundToInt()

/** One sentence in the user's language: why the item was or was not added automatically. */
fun explainWhy(context: Context, d: Detection): String? {
  val why = d.why?.let { w -> Why.entries.firstOrNull { it.name == w } } ?: return null
  val kind = context.getString(if (d.kind == Kind.EVENT) R.string.kind_events_plural else R.string.kind_tasks_plural)
  return when (why) {
    Why.AUTO -> context.getString(R.string.why_auto, pct(d.whyValue), pct(d.whyLimit))
    Why.ASK_MODE -> context.getString(R.string.why_ask_mode, kind)
    Why.ASK_CONFIDENCE -> context.getString(R.string.why_ask_confidence, pct(d.whyValue), pct(d.whyLimit))
    Why.BELOW_MIN -> context.getString(R.string.why_below_min, pct(d.whyValue), pct(d.whyLimit))
    Why.BLOCKED_KEYWORD -> context.getString(R.string.why_blocked_keyword)
    Why.NO_DATE -> context.getString(R.string.why_no_date)
    Why.NO_TIME -> context.getString(R.string.why_no_time)
    Why.PAST -> context.getString(R.string.why_past)
    Why.DEADLINE_PASSED -> context.getString(R.string.why_deadline_passed)
    Why.TOO_FAR -> (d.whyLimit ?: 0.0).roundToInt().let { context.resources.getQuantityString(R.plurals.why_too_far, it, it) }
    Why.KIND_OFF -> context.getString(R.string.why_kind_off, kind)
    Why.NOTHING -> context.getString(R.string.why_nothing)
    Why.NOTE -> context.getString(R.string.why_note)
    Why.PREFILTER -> context.getString(R.string.why_prefilter, pct(d.whyValue), pct(d.whyLimit))
    Why.AI_ERROR -> context.getString(R.string.why_ai_error, d.reason.substringAfter("AI error:").trim().take(120))
    Why.DUPLICATE -> context.getString(R.string.why_duplicate, (d.whyValue ?: 0.0).roundToInt())
    Why.POSSIBLE_DUPLICATE -> context.getString(R.string.why_possible_duplicate, (d.whyValue ?: 0.0).roundToInt())
    Why.SEEN -> (d.whyLimit ?: 0.0).roundToInt().let { context.resources.getQuantityString(R.plurals.why_seen, it, it) }
    Why.TOO_SHORT -> (d.whyLimit ?: 0.0).roundToInt().let { context.resources.getQuantityString(R.plurals.why_too_short, it, it) }
    Why.BLOCKED_SENDER -> context.getString(R.string.why_blocked_sender)
    Why.GROUP -> context.getString(R.string.why_group)
    Why.PAUSED -> context.getString(R.string.why_paused)
    Why.LOCATION_ONLY -> context.getString(R.string.why_location_only)
  }
}

/** "Test: with your current settings this would be added automatically / asked / ignored." */
fun explainTest(context: Context, d: Detection): String {
  val head = context.getString(when (d.realDecision) {
    Decision.AUTO.name -> R.string.test_would_add
    Decision.ASK.name -> R.string.test_would_ask
    else -> R.string.test_would_ignore
  })
  // The reason sentences start with "Not added: …" – after the test headline only the part after the colon is needed.
  val reason = explainWhy(context, d)?.let { w ->
    val colon = w.indexOf(':')
    if (colon in 1..40) w.substring(colon + 1).trim().replaceFirstChar { it.uppercase() } else w
  }
  return listOfNotNull(head, reason).joinToString("\n")
}
