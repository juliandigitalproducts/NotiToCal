package de.juliandigitalproducts.notitocal.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.data.AiEngine
import de.juliandigitalproducts.notitocal.data.RoutingRule
import de.juliandigitalproducts.notitocal.data.RuleKind
import de.juliandigitalproducts.notitocal.data.TargetType
import de.juliandigitalproducts.notitocal.notify.Notifier
import de.juliandigitalproducts.notitocal.output.Targets

/** Rules that send items to a specific calendar, list or AI routine depending on sender, keywords or AI category. */
@Composable
fun RoutingScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
  val s by Ui.app.settings.state.collectAsState()
  SubScreen(stringResource(R.string.routing_rules), onBack) {
    SettingGroupCard(title = stringResource(R.string.ai_routing), emphasized = true) {
      Hint(stringResource(R.string.routing_intro))
      SettingSwitch(
        stringResource(R.string.ai_routing), s.aiRouting && s.engine != AiEngine.RULES,
        stringResource(if (s.engine == AiEngine.RULES) R.string.ai_routing_needs_ai else R.string.ai_routing_body),
        enabled = s.engine != AiEngine.RULES,
      ) { v -> Ui.update { it.copy(aiRouting = v) } }
    }

    SettingGroupCard(title = stringResource(R.string.routing_rules)) {
      if (s.routingRules.isEmpty()) Hint(stringResource(R.string.routing_empty))
      s.routingRules.forEach { r ->
        ListItem(
          colors = cardListItemColors(),
          headlineContent = { Text(r.name.ifBlank { stringResource(R.string.routing_unnamed) }) },
          supportingContent = { Text(ruleSummary(r)) },
          trailingContent = {
            Switch(checked = r.enabled, onCheckedChange = { v -> updateRule(r.id) { it.copy(enabled = v) } })
          },
        )
        OutlinedButton(onClick = { onOpen("routing/${r.id}") }, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)) {
          Text(stringResource(R.string.edit))
        }
      }
      Row(Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
        Button(onClick = {
          val rule = RoutingRule(name = "")
          Ui.update { it.copy(routingRules = it.routingRules + rule) }
          onOpen("routing/${rule.id}")
        }) {
          Icon(Icons.Filled.Add, null)
          Text(stringResource(R.string.routing_add), Modifier.padding(start = 8.dp))
        }
      }
      Hint(stringResource(R.string.routing_examples))
    }
    if (s.routingRules.isNotEmpty()) ResortCard()
  }
}

@Composable
private fun ruleSummary(r: RoutingRule): String {
  val context = LocalContext.current
  val parts = buildList {
    if (r.senders.isNotBlank()) add(stringResource(R.string.routing_from, r.senders))
    if (r.keywords.isNotBlank()) add(stringResource(R.string.routing_words, r.keywords))
    if (r.description.isNotBlank()) add(stringResource(R.string.routing_ai_label))
    if (r.extraTargets.isNotEmpty()) add("→ " + r.extraTargets.joinToString { Notifier.targetLabel(context, it) })
  }
  return parts.joinToString(" · ").ifBlank { stringResource(R.string.routing_no_condition) }
}

private fun updateRule(id: String, t: (RoutingRule) -> RoutingRule) =
  Ui.update { s -> s.copy(routingRules = s.routingRules.map { if (it.id == id) t(it) else it }) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutingRuleScreen(id: String, onBack: () -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val s by Ui.app.settings.state.collectAsState()
  val rule = s.routingRules.firstOrNull { it.id == id }
  SubScreen(stringResource(R.string.routing_edit), onBack) {
    if (rule == null) { Hint(stringResource(R.string.routing_deleted)); return@SubScreen }

    SettingGroupCard(title = stringResource(R.string.routing_name), emphasized = true) {
      SettingText(stringResource(R.string.routing_name), rule.name, placeholder = stringResource(R.string.routing_name_hint)) { v -> updateRule(id) { it.copy(name = v) } }

      val kinds = listOf(RuleKind.ANY, RuleKind.EVENT, RuleKind.TASK)
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
        kinds.forEachIndexed { i, k ->
          SegmentedButton(selected = rule.kind == k, onClick = { updateRule(id) { it.copy(kind = k) } }, shape = SegmentedButtonDefaults.itemShape(i, kinds.size)) {
            Text(stringResource(when (k) { RuleKind.ANY -> R.string.routing_kind_any; RuleKind.EVENT -> R.string.filter_events; RuleKind.TASK -> R.string.filter_tasks }))
          }
        }
      }
    }

    SettingGroupCard(title = stringResource(R.string.routing_when)) {
      SettingText(stringResource(R.string.routing_senders), rule.senders, singleLine = false, supporting = stringResource(R.string.routing_senders_hint)) { v ->
        updateRule(id) { it.copy(senders = v) }
      }
      SettingText(stringResource(R.string.routing_keywords), rule.keywords, singleLine = false, supporting = stringResource(R.string.routing_keywords_hint)) { v ->
        updateRule(id) { it.copy(keywords = v) }
      }
      SettingText(stringResource(R.string.routing_description), rule.description, singleLine = false, supporting = stringResource(R.string.routing_description_hint)) { v ->
        updateRule(id) { it.copy(description = v) }
      }
    }

    val all = s.eventTargets + s.taskTargets + rule.extraTargets
    SettingGroupCard(title = stringResource(R.string.routing_then)) {
      if (TargetType.DEVICE_CALENDAR in all && Ui.granted(context, Manifest.permission.READ_CALENDAR)) {
        val cals = remember { Targets.calendars(context) }
        val options = listOf(-1L) + cals.map { it.id }
        SettingDropdown(stringResource(R.string.calendar), options, rule.calendarId.takeIf { it in options } ?: -1L, { cid ->
          if (cid < 0) resources.getString(R.string.routing_default) else cals.first { it.id == cid }.let { "${it.name} (${it.account})" }
        }) { v -> updateRule(id) { it.copy(calendarId = v) } }
      }
      if (TargetType.TASK_PROVIDER in all) {
        val lists = remember(s.tasksAuthority) { Targets.taskLists(context, s.tasksAuthority) }
        val options = listOf(-1L) + lists.map { it.id }
        SettingDropdown(stringResource(R.string.task_list), options, rule.tasksListId.takeIf { it in options } ?: -1L, { lid ->
          if (lid < 0) resources.getString(R.string.routing_default) else lists.first { it.id == lid }.let { "${it.name} (${it.account})" }
        }) { v -> updateRule(id) { it.copy(tasksListId = v) } }
      }
      if (TargetType.TODOIST in all) SettingText(stringResource(R.string.todoist_project), rule.todoistProjectId, placeholder = stringResource(R.string.routing_default)) { v ->
        updateRule(id) { it.copy(todoistProjectId = v.trim()) }
      }
      if (TargetType.CALDAV in all) {
        SettingText(stringResource(R.string.caldav_event_url), rule.caldavEventUrl, placeholder = stringResource(R.string.routing_default)) { v -> updateRule(id) { it.copy(caldavEventUrl = v.trim()) } }
        SettingText(stringResource(R.string.caldav_task_url), rule.caldavTaskUrl, placeholder = stringResource(R.string.routing_default)) { v -> updateRule(id) { it.copy(caldavTaskUrl = v.trim()) } }
      }
      if (TargetType.NOTION in all) SettingText(stringResource(R.string.notion_database), rule.notionDatabaseId, placeholder = stringResource(R.string.routing_default)) { v ->
        updateRule(id) { it.copy(notionDatabaseId = v.trim()) }
      }
    }

    SettingGroupCard(title = stringResource(R.string.routing_extra_targets)) {
      FlowRow(Modifier.padding(horizontal = Gaps.Inner, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TargetType.entries.forEach { t ->
          FilterChip(
            selected = t in rule.extraTargets,
            onClick = { updateRule(id) { it.copy(extraTargets = if (t in it.extraTargets) it.extraTargets - t else it.extraTargets + t) } },
            label = { Text(Notifier.targetLabel(context, t)) },
          )
        }
      }
      SettingSwitch(stringResource(R.string.routing_only_extra), rule.onlyExtraTargets, stringResource(R.string.routing_only_extra_body), enabled = rule.extraTargets.isNotEmpty()) { v ->
        updateRule(id) { it.copy(onlyExtraTargets = v) }
      }
      if (TargetType.AI_AGENT in all) {
        SettingText(
          stringResource(R.string.agent_routine), rule.agentInstructions, singleLine = false,
          placeholder = stringResource(R.string.agent_routine_placeholder), supporting = stringResource(R.string.routing_agent_hint),
        ) { v -> updateRule(id) { it.copy(agentInstructions = v) } }
      }
      Row(Modifier.padding(horizontal = Gaps.Inner, vertical = 8.dp)) {
        OutlinedButton(onClick = {
          Ui.update { st -> st.copy(routingRules = st.routingRules.filter { it.id != id }) }
          onBack()
        }) {
          Icon(Icons.Filled.Delete, null)
          Text(stringResource(R.string.delete), Modifier.padding(start = 8.dp))
        }
      }
    }

    ResortCard()
  }
}
