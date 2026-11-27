// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import de.juliandigitalproducts.notitocal.analysis.PipelineA
import de.juliandigitalproducts.notitocal.model.runSafely
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.analysis.LocationEnricher
import de.juliandigitalproducts.notitocal.analysis.StyleTemplate
import de.juliandigitalproducts.notitocal.analysis.StyleTemplates
import kotlinx.coroutines.launch

@Composable
fun TemplatesScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val s by Ui.app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var customSampleText by rememberSaveable { mutableStateOf("") }
  var testedEntities by remember { mutableStateOf<LocationEnricher.ParsedEntityResult?>(null) }
  var pipelineAResult by remember { mutableStateOf<PipelineA.PipelineAResult?>(null) }
  var isTesting by remember { mutableStateOf(false) }
  var isAiFinished by remember { mutableStateOf(false) }
  var testJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

  SubScreen(stringResource(R.string.section_templates), onBack) {
    Hint(stringResource(R.string.templates_intro))

    Card(
      modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 6.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          stringResource(R.string.style_sample_input_label),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = customSampleText,
            onValueChange = { customSampleText = it },
            placeholder = { Text(stringResource(R.string.style_sample_input_placeholder), style = MaterialTheme.typography.bodySmall) },
            trailingIcon = {
              if (customSampleText.isNotBlank()) {
                IconButton(onClick = { customSampleText = ""; testedEntities = null; pipelineAResult = null }) {
                  Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                }
              }
            },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          Button(
            onClick = {
              if (customSampleText.isNotBlank() && !isTesting) {
                testJob?.cancel()
                testJob = scope.launch {
                  isTesting = true
                  isAiFinished = false
                  try {
                    val msg = PipelineA.PipelineMessage(id = "sample_test", text = customSampleText)
                    val userHist = mutableListOf<PipelineA.UserHistoryItem>()
                    runSafely {
                      val past = LocationEnricher.searchPastCalendarLocations(context, customSampleText)
                      if (past != null) userHist += PipelineA.UserHistoryItem(customSampleText.take(30), past, "")
                    }
                    val nearby = mutableListOf<PipelineA.NearbyCandidate>()
                    if (s.homeCity.isNotBlank()) {
                      nearby += PipelineA.NearbyCandidate("CinemaxX ${s.homeCity}", "Bundesallee 250, 42103 ${s.homeCity}", "Kino")
                    }
                    val pRes = PipelineA.process(
                      messages = listOf(msg),
                      userHistory = userHist,
                      nearbyCandidates = nearby,
                      settings = s,
                      secrets = Ui.app.secrets,
                      context = context,
                    )
                    pipelineAResult = pRes
                    testedEntities = LocationEnricher.parseAndEnrichSample(customSampleText, s)
                    isAiFinished = true
                    kotlinx.coroutines.delay(450)
                  } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cancelled
                  } finally {
                    isTesting = false
                    isAiFinished = false
                    testJob = null
                  }
                }
              }
            },
            enabled = customSampleText.isNotBlank() && !isTesting,
          ) {
            Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.test_pipeline_btn))
          }
        }
      }
    }

    pipelineAResult?.let { res ->
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(16.dp),
      ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
              text = stringResource(R.string.ai_pipeline_analysis, res.algorithmVersion),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.tpl_timespan, res.styleVariants.compact.timeFormatted), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
              Text(stringResource(R.string.tpl_duration, res.timeData.durationMinutes) + if (res.timeData.isEndEstimated) stringResource(R.string.tpl_estimated) else "", style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.tpl_place, res.locationData.resolvedTitle.ifBlank { res.locationData.rawMention.ifBlank { "–" } }), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
              Text(stringResource(R.string.tpl_source, res.locationData.resolutionSource), style = MaterialTheme.typography.bodySmall)
            }
          }
          if (res.locationData.resolvedAddress.isNotBlank()) {
            Text(stringResource(R.string.tpl_address, res.locationData.resolvedAddress), style = MaterialTheme.typography.bodySmall)
          }
          if (res.aiReasoning.isNotBlank()) {
            Text("💡 ${res.aiReasoning}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }

    testedEntities?.let { ent ->
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(16.dp),
      ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = stringResource(R.string.test_entities_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.tpl_date, ent.date?.toString() ?: "–"), style = MaterialTheme.typography.bodySmall)
              Text(stringResource(R.string.tpl_time, ent.time?.toString() ?: "–"), style = MaterialTheme.typography.bodySmall)
              Text(stringResource(R.string.tpl_title, ent.title.toString()), style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.tpl_street, ent.street ?: "–"), style = MaterialTheme.typography.bodySmall)
              Text(stringResource(R.string.tpl_house, ent.houseNumber ?: "–"), style = MaterialTheme.typography.bodySmall)
              Text(stringResource(R.string.tpl_city, ent.city ?: "–"), style = MaterialTheme.typography.bodySmall)
            }
          }
          ent.fullAddress?.let { addr ->
            Text(stringResource(R.string.tpl_enriched, addr), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
          }
        }
      }
    }

    val activeTitle = pipelineAResult?.styleVariants?.standard?.title ?: testedEntities?.title ?: customSampleText
    val activeDesc = pipelineAResult?.styleVariants?.standard?.description ?: testedEntities?.description ?: customSampleText.takeIf { it.isNotBlank() }
    val activeLoc = pipelineAResult?.locationData?.resolvedAddress?.ifBlank { pipelineAResult?.locationData?.resolvedTitle } ?: testedEntities?.fullAddress
    val activeWhen = pipelineAResult?.styleVariants?.standard?.timeFormatted ?: testedEntities?.whenLabel

    if (isTesting) {
      AiProgressDialog(
        title = stringResource(R.string.test_pipeline_btn),
        isFinished = isAiFinished,
        onDismiss = {
          isTesting = false
          isAiFinished = false
        },
        onCancel = {
          testJob?.cancel()
          testJob = null
          isTesting = false
          isAiFinished = false
        },
      )
    }

    SectionHeader(stringResource(R.string.templates_events))
    StyleTemplates.events.forEach { template ->
      val variant = when (template.id) {
        "event_clean", "event_focus", "event_brief" -> pipelineAResult?.styleVariants?.compact
        "event_formal", "event_pin" -> pipelineAResult?.styleVariants?.detailed
        else -> pipelineAResult?.styleVariants?.standard
      }
      TemplateCard(
        template = template,
        selected = s.eventTemplateId == template.id,
        customText = variant?.title ?: activeTitle,
        customDescription = variant?.description ?: activeDesc,
        customLocation = variant?.location ?: activeLoc,
        customWhen = variant?.timeFormatted ?: activeWhen,
      ) {
        Ui.update { it.copy(eventTemplateId = template.id) }
      }
    }
    SectionHeader(stringResource(R.string.templates_tasks))
    StyleTemplates.tasks.forEach { template ->
      TemplateCard(
        template = template,
        selected = s.taskTemplateId == template.id,
        customText = activeTitle,
        customDescription = activeDesc,
        customLocation = activeLoc,
        customWhen = activeWhen,
      ) {
        Ui.update { it.copy(taskTemplateId = template.id) }
      }
    }
  }
}

@Composable
private fun TemplateCard(
  template: StyleTemplate,
  selected: Boolean,
  customText: String = "",
  customDescription: String? = null,
  customLocation: String? = null,
  customWhen: String? = null,
  onClick: () -> Unit,
) {
  val preview = StyleTemplates.preview(
    template,
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language,
    customText = customText,
    customDescription = customDescription,
    customLocation = customLocation,
    customWhen = customWhen,
  )
  val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
  val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 6.dp),
    colors = CardDefaults.cardColors(containerColor = container),
    border = border,
    shape = RoundedCornerShape(16.dp),
  ) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      Column(Modifier.width(8.dp).fillMaxHeight().background(Color(template.accent))) {}
      Column(Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(templateName(template), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(preview.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(preview.whenLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            preview.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(preview.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
          }
        }
      }
    }
  }
}

@Composable
private fun templateName(template: StyleTemplate): String = stringResource(when (template.id) {
  "event_clean" -> R.string.template_event_clean
  "event_pin" -> R.string.template_event_pin
  "event_brief" -> R.string.template_event_brief
  "event_formal" -> R.string.template_event_formal
  "event_focus" -> R.string.template_event_focus
  "task_check" -> R.string.template_task_check
  "task_todo" -> R.string.template_task_todo
  "task_next" -> R.string.template_task_next
  "task_note" -> R.string.template_task_note
  else -> R.string.template_task_plain
})
