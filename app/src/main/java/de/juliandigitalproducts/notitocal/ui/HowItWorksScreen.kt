// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Julian Digital Products

package de.juliandigitalproducts.notitocal.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.juliandigitalproducts.notitocal.R
import kotlinx.coroutines.launch

/** One slide: icon, title and its points. [warning] marks the slide about limitations. */
private data class Slide(val icon: ImageVector, @StringRes val title: Int, val points: List<Int>, val warning: Boolean = false)

private val SLIDES = listOf(
  Slide(Icons.Filled.NotificationsActive, R.string.hiw_1_title, listOf(R.string.hiw_1_1, R.string.hiw_1_2, R.string.hiw_1_3, R.string.hiw_1_4)),
  Slide(Icons.Filled.Route, R.string.hiw_2_title, listOf(R.string.hiw_2_1, R.string.hiw_2_2, R.string.hiw_2_3, R.string.hiw_2_4, R.string.hiw_2_5, R.string.hiw_2_6)),
  Slide(Icons.Filled.AutoAwesome, R.string.hiw_3_title, listOf(R.string.hiw_3_1, R.string.hiw_3_2, R.string.hiw_3_3, R.string.hiw_3_4)),
  Slide(Icons.Filled.Place, R.string.hiw_4_title, listOf(R.string.hiw_4_1, R.string.hiw_4_2, R.string.hiw_4_3, R.string.hiw_4_4)),
  Slide(Icons.Filled.Warning, R.string.hiw_5_title, listOf(R.string.hiw_5_1, R.string.hiw_5_2, R.string.hiw_5_3, R.string.hiw_5_4, R.string.hiw_5_5, R.string.hiw_5_6), warning = true),
  Slide(Icons.Filled.Lock, R.string.hiw_6_title, listOf(R.string.hiw_6_1, R.string.hiw_6_2, R.string.hiw_6_3, R.string.hiw_6_4, R.string.hiw_6_5)),
  Slide(Icons.Filled.Cloud, R.string.hiw_7_title, listOf(R.string.hiw_7_1, R.string.hiw_7_2, R.string.hiw_7_3, R.string.hiw_7_4, R.string.hiw_7_5, R.string.hiw_7_6, R.string.hiw_7_7)),
  Slide(Icons.Filled.Lightbulb, R.string.hiw_8_title, listOf(R.string.hiw_8_1, R.string.hiw_8_2, R.string.hiw_8_3, R.string.hiw_8_4, R.string.hiw_8_5)),
)

/** A swipeable presentation: how NotiToCal works, where its limits are and how privacy is protected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowItWorksScreen(onBack: () -> Unit) {
  val pager = rememberPagerState { SLIDES.size }
  val scope = rememberCoroutineScope()
  val last = pager.currentPage == SLIDES.lastIndex
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.hiw_screen_title)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
      )
    },
    bottomBar = {
      Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Gaps.Inner, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          PageDots(SLIDES.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(enabled = pager.currentPage > 0, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
              Text(stringResource(R.string.back))
            }
            Button(onClick = { if (last) onBack() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
              Text(stringResource(if (last) R.string.ok else R.string.next))
              if (!last) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
              }
            }
          }
        }
      }
    },
  ) { pad ->
    HorizontalPager(pager, Modifier.fillMaxSize().padding(pad)) { page -> SlidePage(SLIDES[page], page + 1) }
  }
}

@Composable
private fun SlidePage(slide: Slide, number: Int) {
  val accent = if (slide.warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
  val onAccent = if (slide.warning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gaps.Edge, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Surface(shape = CircleShape, color = accent, modifier = Modifier.size(72.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(slide.icon, null, Modifier.size(36.dp), tint = onAccent) }
      }
      Text(stringResource(R.string.hiw_slide_of, number, SLIDES.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
      Text(stringResource(slide.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(Modifier.padding(Gaps.Inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        slide.points.forEach { point ->
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
              if (slide.warning) Icons.Filled.Warning else Icons.Filled.CheckCircle, null,
              Modifier.size(18.dp).padding(top = 2.dp),
              tint = if (slide.warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(point), style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    }
  }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    repeat(count) { i ->
      val width by animateDpAsState(if (i == current) 20.dp else 8.dp, label = "dot")
      val color by animateColorAsState(
        if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotColor",
      )
      Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
    }
  }
}
