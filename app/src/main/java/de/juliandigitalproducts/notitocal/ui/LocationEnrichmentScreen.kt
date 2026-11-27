// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.data.LocationRecognitionMode
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.analysis.LocationEnricher
import de.juliandigitalproducts.notitocal.analysis.LocationEnricher.LocationProposal
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LocationEnrichmentScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  val s by Ui.app.settings.state.collectAsState()

  var isScanning by remember { mutableStateOf(false) }
  var isApplying by remember { mutableStateOf(false) }
  var selectedDaysAhead by rememberSaveable { mutableIntStateOf(30) }
  val proposals = remember { mutableStateListOf<LocationProposal>() }
  var scanMessage by remember { mutableStateOf<String?>(null) }

  SubScreen(
    title = stringResource(R.string.loc_screen_title),
    onBack = onBack,
  ) {
    if (isScanning) {
      BusyDialog(
        message = stringResource(R.string.ai_processing_title),
        hint = stringResource(R.string.ai_processing_hint),
      )
    }
    // 1. General Setting Card
    SettingGroupCard(title = stringResource(R.string.loc_auto_group), emphasized = true) {
      SettingSwitch(
        title = stringResource(R.string.loc_auto_title),
        checked = s.locationEnrichmentEnabled,
        subtitle = stringResource(R.string.loc_auto_body),
      ) { v -> Ui.update { it.copy(locationEnrichmentEnabled = v) } }

      if (s.locationEnrichmentEnabled) {
        CardContent {
          Spacer(Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.loc_effort),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp),
          )

          val effortOptions = listOf("fast" to stringResource(R.string.loc_effort_fast), "balanced" to stringResource(R.string.loc_effort_balanced), "thorough" to stringResource(R.string.loc_effort_thorough))
          val currentEffortIndex = when (s.locationSearchEffort) {
            "fast" -> 0
            "thorough" -> 2
            else -> 1
          }

          SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            effortOptions.forEachIndexed { index, (key, label) ->
              SegmentedButton(
                selected = currentEffortIndex == index,
                onClick = { Ui.update { it.copy(locationSearchEffort = key) } },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = effortOptions.size),
              ) {
                Text(label, style = MaterialTheme.typography.labelSmall)
              }
            }
          }

          Spacer(Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.loc_sources),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp),
          )

          FlowRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            val hasHistory = "history" in s.locationSearchSources
            val hasOsm = "osm" in s.locationSearchSources
            val hasPhoton = "photon" in s.locationSearchSources

            FilterChip(
              selected = hasHistory,
              onClick = {
                val next = if (hasHistory) s.locationSearchSources - "history" else s.locationSearchSources + "history"
                Ui.update { it.copy(locationSearchSources = next) }
              },
              label = { Text(stringResource(R.string.loc_src_history)) },
              leadingIcon = { Icon(Icons.Filled.History, null, modifier = Modifier.size(16.dp)) },
            )

            FilterChip(
              selected = hasPhoton,
              onClick = {
                val next = if (hasPhoton) s.locationSearchSources - "photon" else s.locationSearchSources + "photon"
                Ui.update { it.copy(locationSearchSources = next) }
              },
              label = { Text(stringResource(R.string.loc_src_photon)) },
              leadingIcon = { Icon(Icons.Filled.Place, null, modifier = Modifier.size(16.dp)) },
            )

            FilterChip(
              selected = hasOsm,
              onClick = {
                val next = if (hasOsm) s.locationSearchSources - "osm" else s.locationSearchSources + "osm"
                Ui.update { it.copy(locationSearchSources = next) }
              },
              label = { Text("OpenStreetMap") },
              leadingIcon = { Icon(Icons.Filled.Map, null, modifier = Modifier.size(16.dp)) },
            )
          }
        }
        // A local engine keeps message content on the device: online place lookups need an explicit yes.
        if (s.engine == de.juliandigitalproducts.notitocal.data.AiEngine.RULES ||
          s.engine == de.juliandigitalproducts.notitocal.data.AiEngine.ON_DEVICE
        ) {
          SettingSwitch(
            title = stringResource(R.string.loc_online_local_title),
            checked = s.locationOnlineWithLocalEngine,
            subtitle = stringResource(R.string.loc_online_local_body),
          ) { v -> Ui.update { it.copy(locationOnlineWithLocalEngine = v) } }
        }
      }
    }

    HomeLocationCard()

    LocationHistoryCards()

    // Drei Erkennungs-Modi
    SettingGroupCard(title = stringResource(R.string.recognition_mode_title)) {
      CardContent {
        Text(
          text = stringResource(R.string.loc_modes_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        LocationRecognitionMode.entries.forEach { mode ->
          val isSelected = s.locationRecognitionMode == mode
          Surface(
            onClick = { Ui.update { it.copy(locationRecognitionMode = mode) } },
            shape = RoundedCornerShape(12.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(mode.label),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                  color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                  text = stringResource(mode.description),
                  style = MaterialTheme.typography.bodySmall,
                  color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (isSelected) {
                Icon(
                  Icons.Filled.CheckCircle,
                  contentDescription = stringResource(R.string.loc_active),
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
              }
            }
          }
        }
      }
    }

    // 2. Batch Enhancement Card ("Alle Termine verbessern")
    SettingGroupCard(title = stringResource(R.string.loc_batch_title)) {
      CardContent {
        Text(
          text = stringResource(R.string.loc_batch_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        Text(
          text = stringResource(R.string.loc_period),
          style = MaterialTheme.typography.labelMedium,
        )

        val daysOptions = listOf(7, 30, 90, 180).map { it to pluralStringResource(R.plurals.loc_days, it, it) }
        val daysIndex = when (selectedDaysAhead) {
          7 -> 0
          90 -> 2
          180 -> 3
          else -> 1
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
          daysOptions.forEachIndexed { index, (days, label) ->
            SegmentedButton(
              selected = daysIndex == index,
              onClick = { selectedDaysAhead = days },
              shape = SegmentedButtonDefaults.itemShape(index = index, count = daysOptions.size),
            ) {
              Text(label, style = MaterialTheme.typography.labelSmall)
            }
          }
        }

        Spacer(Modifier.height(8.dp))
        Button(
          onClick = {
            scope.launch {
              isScanning = true
              scanMessage = null
              proposals.clear()
              val list = LocationEnricher.scanCalendarEventsForImprovement(context, selectedDaysAhead, s)
              proposals.addAll(list)
              isScanning = false
              scanMessage = if (list.isEmpty()) {
                resources.getString(R.string.loc_scan_none)
              } else {
                resources.getString(R.string.loc_scan_found, list.size)
              }
            }
          },
          enabled = !isScanning,
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (isScanning) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.loc_scan_running))
          } else {
            Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.loc_scan_btn))
          }
        }

        scanMessage?.let { msg ->
          Spacer(Modifier.height(8.dp))
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = if (proposals.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // 3. Interactive Proposal List
    if (proposals.isNotEmpty()) {
      val selectedCount = proposals.count { it.selected }

      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.loc_proposals, proposals.size),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
            )
            Row {
              TextButton(onClick = { proposals.indices.forEach { proposals[it] = proposals[it].copy(selected = true) } }) {
                Text(stringResource(R.string.select_all_short), style = MaterialTheme.typography.labelSmall)
              }
              TextButton(onClick = { proposals.indices.forEach { proposals[it] = proposals[it].copy(selected = false) } }) {
                Text(stringResource(R.string.select_none_short), style = MaterialTheme.typography.labelSmall)
              }
            }
          }

          Spacer(Modifier.height(6.dp))

          proposals.forEachIndexed { index, proposal ->
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { proposals[index] = proposal.copy(selected = !proposal.selected) },
              colors = CardDefaults.cardColors(
                containerColor = if (proposal.selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
              ),
              border = if (proposal.selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(
                  checked = proposal.selected,
                  onCheckedChange = { checked -> proposals[index] = proposal.copy(selected = checked) },
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = proposal.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  val dateText = DateUtils.formatDateTime(
                    context,
                    proposal.startMillis,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
                  )
                  Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  if (proposal.currentAddress.isNotBlank()) {
                    Text(
                      text = stringResource(R.string.loc_before, proposal.currentAddress),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Filled.Place, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                      text = proposal.proposedAddress,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.Medium,
                    )
                  }
                  Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                  ) {
                    Text(
                      text = stringResource(R.string.loc_source_label, proposal.source),
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSecondaryContainer,
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                  }
                }
              }
            }
          }

          Spacer(Modifier.height(10.dp))

          Button(
            onClick = {
              scope.launch {
                isApplying = true
                val updatedCount = LocationEnricher.applyImprovements(context, proposals)
                isApplying = false
                Toast.makeText(context, resources.getString(R.string.loc_applied, updatedCount), Toast.LENGTH_LONG).show()
                proposals.removeAll { it.selected }
                scanMessage = resources.getString(R.string.loc_applied, updatedCount)
              }
            },
            enabled = selectedCount > 0 && !isApplying,
            modifier = Modifier.fillMaxWidth(),
          ) {
            if (isApplying) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.loc_apply_running))
            } else {
              Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.loc_apply_btn, selectedCount))
            }
          }
        }
      }
    }
  }
}


/** Home town and position: the fallback for "Hauptstraße 5" without a town and the centre for "beim Italiener". */
@Composable
fun HomeLocationCard() {
  val context = LocalContext.current
  val resources = LocalResources.current
  val scope = rememberCoroutineScope()
  val s by Ui.app.settings.state.collectAsState()
  var isFetchingLocation by remember { mutableStateOf(false) }

  suspend fun fetchAndSaveCurrentLocation() {
    isFetchingLocation = true
    try {
      val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
      val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
      val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
      if (!hasFine && !hasCoarse) {
        Toast.makeText(context, resources.getString(R.string.loc_perm_required), Toast.LENGTH_SHORT).show()
        return
      }

      @Suppress("MissingPermission")
      val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

      if (loc != null) {
        val geo = LocationEnricher.reverseGeocode(context, loc.latitude, loc.longitude)
        if (geo != null) {
          Ui.update {
            it.copy(
              homePostalCode = geo.first,
              homeCity = geo.second,
              homeLatitude = loc.latitude,
              homeLongitude = loc.longitude,
            )
          }
          Toast.makeText(context, resources.getString(R.string.loc_saved, "${geo.first} ${geo.second}"), Toast.LENGTH_SHORT).show()
        } else {
          Ui.update { it.copy(homeLatitude = loc.latitude, homeLongitude = loc.longitude) }
          Toast.makeText(context, resources.getString(R.string.loc_coords_found, "${loc.latitude}, ${loc.longitude}"), Toast.LENGTH_SHORT).show()
        }
      } else {
        Toast.makeText(context, resources.getString(R.string.loc_current_failed), Toast.LENGTH_SHORT).show()
      }
    } catch (e: Exception) {
      Toast.makeText(context, resources.getString(R.string.loc_error, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
    } finally {
      isFetchingLocation = false
    }
  }

  val locationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
  ) { perms ->
    val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true || perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    if (granted) {
      scope.launch { fetchAndSaveCurrentLocation() }
    } else {
      Toast.makeText(context, resources.getString(R.string.loc_perm_denied), Toast.LENGTH_SHORT).show()
    }
  }

  SettingGroupCard(title = stringResource(R.string.home_location_title)) {
    CardContent {
      Text(
        text = stringResource(R.string.home_location_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Spacer(Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Local text while typing (SettingText): writing every key stroke straight into the settings mixed up the
        // letters ("BnrBie" for "Berlin").
        SettingText(stringResource(R.string.home_location_plz), s.homePostalCode, Modifier.weight(0.35f), padded = false) { v ->
          Ui.update { it.copy(homePostalCode = v.trim()) }
        }
        SettingText(stringResource(R.string.home_location_city), s.homeCity, Modifier.weight(0.65f), padded = false) { v ->
          Ui.update { it.copy(homeCity = v.trim()) }
        }
      }

      if (s.homeLatitude != null && s.homeLongitude != null) {
        Spacer(Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.home_location_coordinates, s.homeLatitude!!, s.homeLongitude!!),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }

      Spacer(Modifier.height(8.dp))
      Button(
        onClick = {
          val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
          val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
          if (!hasFine && !hasCoarse) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
          } else {
            scope.launch { fetchAndSaveCurrentLocation() }
          }
        },
        enabled = !isFetchingLocation,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (isFetchingLocation) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.home_location_fetching))
        } else {
          Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.home_location_fetch_btn))
        }
      }
    }
  }
}
