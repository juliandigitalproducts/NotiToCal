package de.juliandigitalproducts.notitocal.ui

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import de.juliandigitalproducts.notitocal.R
import de.juliandigitalproducts.notitocal.model.Detection
import de.juliandigitalproducts.notitocal.model.Kind
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** "Sat, 1 Nov 2026 · 15:00–16:00" / "Due: Fri, 6 Nov". */
fun describeWhen(context: Context, d: Detection): String? {
  val locale = java.util.Locale.getDefault()
  val short = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
  val withYear = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMMy"), locale)
  val date = { ld: LocalDate -> ld.format(if (ld.year == LocalDate.now().year) short else withYear) }
  val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
  return when (d.kind) {
    Kind.EVENT -> d.startDateTime()?.let { s ->
      if (d.allDay || d.start?.contains('T') != true) "${date(s.toLocalDate())} · ${context.getString(R.string.all_day)}"
      else "${date(s.toLocalDate())} · ${s.format(time)}" + (d.endDateTime()?.let { "–" + it.format(time) } ?: "")
    }
    Kind.TASK -> d.dueDateTime()?.let { s ->
      context.getString(R.string.due_at, date(s.toLocalDate()) + if (d.due?.contains('T') == true) " · ${s.format(time)}" else "")
    }
    else -> null
  }
}

/**
 * Spacing shared by every screen: cards sit [Edge] from the screen edge and from each other, content keeps [Inner]
 * from the edge of its card (and free content from the screen edge), so nothing touches a card's border.
 */
object Gaps {
  val Edge = 8.dp
  val Inner = 12.dp
}

@Composable
fun SectionHeader(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = Gaps.Inner, end = Gaps.Inner, top = 20.dp, bottom = 6.dp),
  )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
  Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(horizontal = Gaps.Inner, vertical = 4.dp))
}

@Composable
fun SettingSwitch(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = Gaps.Inner, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Spacer(Modifier.width(12.dp))
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}

@Composable
fun SettingNav(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Gaps.Inner, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
      Spacer(Modifier.width(16.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
  }
}

/** Accent of a settings category: its card colour and the matching colours for its tiles' icons. */
data class CategoryTone(val container: Color, val onContainer: Color, val accent: Color, val onAccent: Color)

/** The four Material-You category tones (dynamic colours, so they follow the wallpaper and light / dark mode). */
@Composable
fun categoryTones(): List<CategoryTone> {
  val c = MaterialTheme.colorScheme
  return listOf(
    CategoryTone(c.primaryContainer, c.onPrimaryContainer, c.primary, c.onPrimary),
    CategoryTone(c.secondaryContainer, c.onSecondaryContainer, c.secondary, c.onSecondary),
    CategoryTone(c.tertiaryContainer, c.onTertiaryContainer, c.tertiary, c.onTertiary),
    CategoryTone(c.surfaceContainerHighest, c.onSurface, c.primary, c.onPrimary),
  )
}

/** Modern 2-column or grid tile for the main settings dashboard. A [wide] tile shows its whole description. */
@Composable
fun SettingTile(
  title: String,
  icon: ImageVector,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  wide: Boolean = false,
  iconContainer: Color = MaterialTheme.colorScheme.primaryContainer,
  iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    // Fills the row's height in a [TileGrid], so tiles side by side are equally tall.
    modifier = modifier.fillMaxWidth().fillMaxHeight(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 3.dp),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(Gaps.Inner),
    ) {
      Surface(
        shape = CircleShape,
        color = iconContainer,
        modifier = Modifier.size(38.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
          )
        }
      }

      Spacer(Modifier.height(10.dp))

      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = if (wide) 2 else 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = if (wide) Int.MAX_VALUE else 2,
          overflow = TextOverflow.Ellipsis,
          lineHeight = 15.sp,
        )
      }
    }
  }
}

/** Columns for a tile grid: 2 on phones, more on tablets / foldables. */
fun tileColumns(maxWidth: androidx.compose.ui.unit.Dp, wide: Int = 3, extraWide: Int = wide): Int = when {
  maxWidth >= 840.dp -> extraWide
  maxWidth >= 600.dp -> wide
  else -> 2
}

/**
 * Rows of [items] for a grid with [columns]. With [stretchLastRow] the tiles of an incomplete last row share its full
 * width (3 tiles on a phone → 2 + 1 wide), otherwise the row keeps empty slots.
 */
fun <T> tileRows(items: List<T>, columns: Int, stretchLastRow: Boolean): List<List<T?>> {
  val rows = items.chunked(columns)
  return rows.mapIndexed { i, row ->
    if (row.size == columns || (stretchLastRow && i == rows.lastIndex)) row else row + List(columns - row.size) { null }
  }
}

/** Tile grid shared by the settings, sources and targets overviews; [tile] gets `wide` for a stretched tile. */
@Composable
fun <T> TileGrid(
  items: List<T>,
  modifier: Modifier = Modifier,
  stretchLastRow: Boolean = false,
  wideColumns: Int = 3,
  extraWideColumns: Int = wideColumns,
  spacing: androidx.compose.ui.unit.Dp = Gaps.Edge,
  tile: @Composable (item: T, wide: Boolean) -> Unit,
) {
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val columns = tileColumns(maxWidth, wideColumns, extraWideColumns)
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
      tileRows(items, columns, stretchLastRow).forEach { row ->
        // Intrinsic height: every tile in the row takes the height of the tallest one.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(spacing)) {
          row.forEach { item ->
            Box(Modifier.weight(1f).fillMaxHeight()) {
              if (item != null) tile(item, row.size < columns)
            }
          }
        }
      }
    }
  }
}

/** ListItem colours for rows inside a card: the card's own (main or detail) background shows through. */
@Composable
fun cardListItemColors() = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * A rounded card that groups related settings, with its [title] as the card's header.
 * The [emphasized] card is a detail screen's main settings: one highlighted tone on every screen; all others use the
 * standard card background.
 */
@Composable
fun SettingGroupCard(
  modifier: Modifier = Modifier,
  title: String? = null,
  emphasized: Boolean = false,
  content: @Composable ColumnScope.() -> Unit,
) {
  val container = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
  val onContainer = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
  Card(
    modifier = modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 6.dp),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
  ) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
      if (title != null) {
        Text(
          title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = if (emphasized) onContainer else MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(start = Gaps.Inner, end = Gaps.Inner, top = 10.dp, bottom = 4.dp),
        )
      }
      content()
    }
  }
}

/** Free content (text, buttons, chips, fields) inside a [SettingGroupCard]: keeps [Gaps.Inner] from the card's border. */
@Composable
fun CardContent(content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 4.dp), content = content)
}

@Composable
fun SettingSlider(
  title: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  steps: Int = 0,
  format: (Float) -> String = { "${(it * 100).roundToInt()} %" },
  subtitle: String? = null,
  onChange: (Float) -> Unit,
) {
  var local by remember(value) { mutableFloatStateOf(value) }
  Column(Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
  }
}

@Composable
fun SettingText(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  secret: Boolean = false,
  number: Boolean = false,
  singleLine: Boolean = true,
  placeholder: String? = null,
  supporting: String? = null,
  /** False inside a row or a [CardContent] that already keeps the distance to the edge. */
  padded: Boolean = true,
  onChange: (String) -> Unit,
) {
  // Local state while typing: settings are saved asynchronously, so re-keying on [value] would drop keystrokes.
  var text by rememberSaveable { mutableStateOf(value) }
  var focused by remember { mutableStateOf(false) }
  var visible by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(value) { if (!focused && value != text) text = value }
  OutlinedTextField(
    value = text,
    onValueChange = { text = it; onChange(it) },
    label = { Text(label) },
    placeholder = placeholder?.let { { Text(it) } },
    supportingText = supporting?.let { { Text(it) } },
    singleLine = singleLine,
    minLines = if (singleLine) 1 else 3,
    visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
    keyboardOptions = KeyboardOptions(keyboardType = when { number -> KeyboardType.Number; secret -> KeyboardType.Password; else -> KeyboardType.Text }),
    trailingIcon = if (secret) {
      { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } }
    } else null,
    modifier = modifier.fillMaxWidth().padding(horizontal = if (padded) Gaps.Inner else 0.dp, vertical = 4.dp).onFocusChanged { focused = it.isFocused },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingDropdown(label: String, options: List<T>, selected: T, display: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 4.dp)) {
    OutlinedTextField(
      value = display(selected),
      onValueChange = {},
      readOnly = true,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
      modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { o ->
        DropdownMenuItem(text = { Text(display(o)) }, onClick = { expanded = false; onSelect(o) })
      }
    }
  }
}

@Composable
fun InfoCard(title: String, body: String, modifier: Modifier = Modifier, icon: ImageVector? = null, action: (@Composable () -> Unit)? = null) {
  Card(
    modifier = modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 6.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Row(Modifier.padding(Gaps.Inner), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      if (icon != null) Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
      }
    }
  }
}

/** Selectable card used for choices with an explanation (wizard, engine picker). */
@Composable
fun ChoiceCard(title: String, body: String, selected: Boolean, icon: ImageVector, onClick: () -> Unit) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Edge, vertical = 5.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
  ) {
    Row(Modifier.padding(Gaps.Inner), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Icon(icon, null, Modifier.size(28.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

/** Non-dismissable progress dialog for actions that take a while (model loading, AI calls, transcription …). */
@Composable
fun BusyDialog(message: String, hint: String? = null) {
  androidx.compose.material3.AlertDialog(
    onDismissRequest = {},
    confirmButton = {},
    properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    text = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(36.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(message, style = MaterialTheme.typography.bodyLarge)
          hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
      }
    },
  )
}

/** Non-dismissable progress dialog with a LinearProgressIndicator and status text. */
@Composable
fun ProgressDialog(message: String, hint: String? = null, progress: Float? = null) {
  androidx.compose.material3.AlertDialog(
    onDismissRequest = {},
    confirmButton = {},
    properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    text = {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        if (progress != null) {
          LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
          )
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
    },
  )
}

/**
 * Asymptotic AI progress estimation logic.
 *
 * Curve phases:
 * - 0–15s: 0% -> 35% (KI-Engine wird initialisiert…)
 * - 15–40s: 35% -> 75% (Kontext und Zeitspannen werden analysiert…)
 * - 40–58s: 75% -> 94% (Ergebnisse werden abgeglichen…)
 * - >58s: asymptotic creep towards 96%
 * - On finish: 100% (Fertiggestellt!)
 */
object AiProgressEstimator {
  enum class Phase {
    INIT, ANALYZING, MATCHING, DONE
  }

  fun calculateProgress(elapsedMs: Long, isCompleted: Boolean): Float {
    if (isCompleted) return 1.0f
    val seconds = elapsedMs / 1000f
    return when {
      seconds < 15f -> (seconds / 15f) * 0.35f
      seconds < 40f -> 0.35f + ((seconds - 15f) / 25f) * 0.40f
      seconds < 58f -> {
        val u = ((seconds - 40f) / 18f).coerceIn(0f, 1f)
        0.75f + 0.19f * (1f - (1f - u) * (1f - u))
      }
      else -> {
        val extraSec = (seconds - 58f)
        0.94f + 0.02f * (1f - kotlin.math.exp(-extraSec / 15f))
      }
    }.coerceIn(0f, 0.96f)
  }

  fun determinePhase(elapsedMs: Long, isCompleted: Boolean): Phase {
    if (isCompleted) return Phase.DONE
    val seconds = elapsedMs / 1000f
    return when {
      seconds < 15f -> Phase.INIT
      seconds < 40f -> Phase.ANALYZING
      else -> Phase.MATCHING
    }
  }
}

/**
 * Inline Material-You AI Progress Indicator with asymptotic 60s estimation,
 * status phases and mandatory 1-minute notice note.
 * With a known [progress] (0…1, e.g. "message 3 of 8") the bar shows it instead of the time estimate.
 */
@Composable
fun AiProgressIndicator(
  modifier: Modifier = Modifier,
  title: String? = null,
  statusOverride: String? = null,
  isFinished: Boolean = false,
  progress: Float? = null,
  onDismiss: () -> Unit = {},
) {
  var elapsedMs by remember { mutableLongStateOf(0L) }
  var completed by remember { mutableStateOf(false) }

  LaunchedEffect(isFinished) {
    if (isFinished) {
      completed = true
      delay(400)
      onDismiss()
      return@LaunchedEffect
    }
    val startTime = System.currentTimeMillis()
    while (isActive && !completed) {
      delay(300)
      elapsedMs = System.currentTimeMillis() - startTime
    }
  }

  val rawProgress = when {
    completed -> 1f
    // Never shows 100 % before the work has really finished.
    progress != null -> progress.coerceIn(0f, 0.99f)
    else -> AiProgressEstimator.calculateProgress(elapsedMs, false)
  }

  val animatedProgress by animateFloatAsState(
    targetValue = rawProgress,
    animationSpec = tween(
      durationMillis = if (completed) 250 else 300,
      easing = FastOutSlowInEasing,
    ),
    label = "aiProgress",
  )

  val currentPhaseText = statusOverride ?: when (AiProgressEstimator.determinePhase(elapsedMs, completed)) {
    AiProgressEstimator.Phase.DONE -> stringResource(R.string.ai_status_done)
    AiProgressEstimator.Phase.INIT -> stringResource(R.string.ai_status_init)
    AiProgressEstimator.Phase.ANALYZING -> stringResource(R.string.ai_status_analyzing)
    AiProgressEstimator.Phase.MATCHING -> stringResource(R.string.ai_status_matching)
  }

  val percentInt = (animatedProgress * 100f).toInt().coerceIn(0, 100)

  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulseAlpha by infiniteTransition.animateFloat(
    initialValue = 0.65f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "pulseAlpha",
  )

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = pulseAlpha)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Filled.AutoAwesome,
          contentDescription = null,
          modifier = Modifier.size(22.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      Column(Modifier.weight(1f)) {
        if (!title.isNullOrBlank()) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = currentPhaseText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier
          .fillMaxWidth()
          .height(10.dp)
          .clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        Text(
          text = "$percentInt%",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          Icons.Filled.Info,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = stringResource(R.string.ai_progress_minute_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          lineHeight = 16.sp,
        )
      }
    }
  }
}

/**
 * Modern Material-You AI Progress Dialog with asymptotic estimation over 60 seconds.
 */
@Composable
fun AiProgressDialog(
  title: String,
  modifier: Modifier = Modifier,
  isFinished: Boolean = false,
  statusOverride: String? = null,
  progress: Float? = null,
  onDismiss: () -> Unit = {},
  onCancel: (() -> Unit)? = null,
) {
  androidx.compose.material3.AlertDialog(
    onDismissRequest = {
      if (onCancel != null) onCancel()
    },
    confirmButton = {},
    dismissButton = onCancel?.let { cancelAction ->
      {
        TextButton(onClick = cancelAction) {
          Text(stringResource(R.string.cancel))
        }
      }
    },
    properties = DialogProperties(
      dismissOnBackPress = onCancel != null,
      dismissOnClickOutside = false,
    ),
    shape = RoundedCornerShape(24.dp),
    modifier = modifier,
    text = {
      AiProgressIndicator(
        title = title,
        statusOverride = statusOverride,
        isFinished = isFinished,
        progress = progress,
        onDismiss = onDismiss,
      )
    },
  )
}

/**
 * Runs the queued-message analysis ("Analyse now") with the standard progress dialog and real per-step progress.
 * The work is tied to the dialog: it starts when the dialog is shown and [onFinished] gets the summary.
 */
@Composable
fun BatchRunDialog(onFinished: (de.juliandigitalproducts.notitocal.pipeline.Batch.Summary) -> Unit) {
  var done by remember { mutableIntStateOf(0) }
  var total by remember { mutableIntStateOf(0) }
  var summary by remember { mutableStateOf<de.juliandigitalproducts.notitocal.pipeline.Batch.Summary?>(null) }
  val latestOnFinished by rememberUpdatedState(onFinished)
  LaunchedEffect(Unit) {
    summary = Ui.app.batch.runNow { d, t -> done = d; total = t }
  }
  AiProgressDialog(
    title = stringResource(R.string.analyze_now),
    statusOverride = if (total > 0 && summary == null) stringResource(R.string.ai_recheck_progress, (done + 1).coerceAtMost(total), total) else null,
    progress = if (total > 0) done.toFloat() / total else null,
    isFinished = summary != null,
    onDismiss = { summary?.let(latestOnFinished) },
  )
}

/** Inline "working …" row with a spinner, for lists and settings pages. */
@Composable
fun BusyRow(message: String) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Progress text for analysing with the current engine: loading a local model can take up to a minute the first time. */
fun analyzingHint(context: Context, s: de.juliandigitalproducts.notitocal.data.AppSettings): String? = when (s.engine) {
  de.juliandigitalproducts.notitocal.data.AiEngine.ON_DEVICE ->
    if (de.juliandigitalproducts.notitocal.analysis.OnDeviceLlm.isLoadedKey(s.onDeviceModelUri.ifBlank { s.onDeviceModelFile })) context.getString(R.string.busy_hint_local)
    else context.getString(R.string.busy_model_loading)
  de.juliandigitalproducts.notitocal.data.AiEngine.CLOUD, de.juliandigitalproducts.notitocal.data.AiEngine.SELF_HOSTED -> context.getString(R.string.busy_hint_ai)
  else -> null
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TimeWindowSelector(
  selectedHours: Int,
  title: String,
  subtitle: String,
  onSelect: (Int) -> Unit,
) {
  val options = listOf(
    3 to "3 Std.",
    8 to "8 Std.",
    12 to "12 Std.",
    24 to "24 Std.",
    72 to "3 Tage",
    168 to "7 Tage",
    720 to "1 Monat",
    2160 to "3 Monate",
  )
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = Gaps.Inner, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    androidx.compose.foundation.layout.FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      options.forEach { (h, label) ->
        androidx.compose.material3.FilterChip(
          selected = selectedHours == h,
          onClick = { onSelect(h) },
          label = { Text(label) },
        )
      }
    }
  }
}
