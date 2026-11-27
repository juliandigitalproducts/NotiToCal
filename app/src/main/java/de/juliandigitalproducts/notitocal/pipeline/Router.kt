// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.pipeline

import de.juliandigitalproducts.notitocal.analysis.Analyzer
import de.juliandigitalproducts.notitocal.data.AppSettings
import de.juliandigitalproducts.notitocal.data.RoutingRule
import de.juliandigitalproducts.notitocal.data.RuleKind
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind

/** Picks the routing rule (calendar, task list, AI routine …) for a detection. */
object Router {

  private fun RoutingRule.accepts(kind: Kind) =
    enabled && (this.kind == RuleKind.ANY || (this.kind == RuleKind.EVENT && kind == Kind.EVENT) || (this.kind == RuleKind.TASK && kind == Kind.TASK))

  /** Sender rules win over keyword rules, both over the AI's category; the first matching rule in the list wins. */
  fun match(rules: List<RoutingRule>, d: Detection, text: String, sender: String): RoutingRule? {
    val candidates = rules.filter { it.accepts(d.kind) }
    if (sender.isNotBlank()) candidates.firstOrNull { it.senders.isNotBlank() && Analyzer.matchesAny(sender, it.senders) }?.let { return it }
    val haystack = listOf(text, d.title, d.description, d.location.orEmpty()).joinToString("\n")
    candidates.firstOrNull { it.keywords.isNotBlank() && Analyzer.matchesAny(haystack, it.keywords) }?.let { return it }
    val category = d.category?.trim()?.lowercase()
    if (!category.isNullOrEmpty() && category != "null") {
      candidates.firstOrNull { it.name.trim().lowercase() == category }?.let { return it }
    }
    return null
  }

  fun rule(s: AppSettings, d: Detection): RoutingRule? = d.route?.let { id -> s.routingRules.firstOrNull { it.id == id && it.enabled } }

  /** Settings with the rule's calendar, list, project, URLs and routine in place of the global ones. */
  fun apply(s: AppSettings, rule: RoutingRule?): AppSettings {
    if (rule == null) return s
    return s.copy(
      calendarId = rule.calendarId.takeIf { it >= 0 } ?: s.calendarId,
      tasksListId = rule.tasksListId.takeIf { it >= 0 } ?: s.tasksListId,
      todoistProjectId = rule.todoistProjectId.ifBlank { s.todoistProjectId },
      caldavEventUrl = rule.caldavEventUrl.ifBlank { s.caldavEventUrl },
      caldavTaskUrl = rule.caldavTaskUrl.ifBlank { s.caldavTaskUrl },
      notionDatabaseId = rule.notionDatabaseId.ifBlank { s.notionDatabaseId },
      agentInstructions = rule.agentInstructions.ifBlank { s.agentInstructions },
    )
  }

  fun targets(s: AppSettings, d: Detection, rule: RoutingRule?): Set<TargetType> {
    val base = if (d.kind == Kind.EVENT) s.eventTargets else s.taskTargets
    val extra = rule?.extraTargets.orEmpty().filter { if (d.kind == Kind.EVENT) it.forEvents else it.forTasks }.toSet()
    return if (rule?.onlyExtraTargets == true && extra.isNotEmpty()) extra else base + extra
  }
}
