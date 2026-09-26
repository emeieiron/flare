package xyz.mcxross.flare.feature.trade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.design.FlareAmountField
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareIcons

/** A quiet, focusable amount surface shared by size, entry, and exit inputs. */
@Composable
internal fun OrderAmountField(
  label: String,
  unit: String,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  optional: Boolean = false,
  valueHint: String? = null,
) {
  FlareAmountField(
    value = value,
    onValueChange = onValueChange,
    label = label,
    unit = unit,
    placeholder = if (optional) "0.00" else "0",
    enabled = enabled,
    valueHint = valueHint,
    modifier = modifier,
  )
}

/** Leverage lives in one row; the scale opens only when a larger jump is needed. */
@Composable
internal fun LeverageControl(state: TradeUiState, margin: Double?, onChange: (Int) -> Unit) {
  var scaleOpen by rememberSaveable { mutableStateOf(false) }
  val maximum = state.quote?.market?.maxLeverage?.coerceIn(1, 100) ?: 1
  val adjustable = state.positionLeverage == null && maximum > 1
  val enabled = adjustable && !state.orderBusy
  val haptics = LocalHapticFeedback.current
  val change: (Int, HapticFeedbackType) -> Unit = { value, feedback ->
    val next = value.coerceIn(1, maximum)
    if (next != state.leverage) {
      haptics.performHapticFeedback(feedback)
      onChange(next)
    }
  }
  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 48.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Leverage", color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
      if (adjustable) {
        LeverageStepper(
          value = state.leverage,
          maximum = maximum,
          enabled = enabled,
          scaleOpen = scaleOpen,
          onToggleScale = { scaleOpen = !scaleOpen },
          onStep = { change(it, HapticFeedbackType.SegmentTick) },
        )
      } else {
        Text(
          "${state.leverage}×",
          style = MaterialTheme.typography.titleMedium,
          color = FlareColors.Positive,
        )
      }
      Spacer(Modifier.weight(1f))
      Column(horizontalAlignment = Alignment.End) {
        Text(
          "Est. margin",
          color = FlareColors.TextSecondary,
          style = MaterialTheme.typography.labelSmall,
        )
        Text(
          when {
            margin != null -> formatBalance(margin)
            state.sizeInput.isBlank() -> formatBalance(0.0)
            else -> "—"
          },
          style = MaterialTheme.typography.titleMedium,
          color = if (margin != null) FlareColors.TextPrimary else FlareColors.TextTertiary,
        )
      }
    }
    AnimatedVisibility(
      scaleOpen && adjustable,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      LeverageScale(state.leverage, maximum, enabled) { change(it, HapticFeedbackType.SegmentFrequentTick) }
    }
    if (state.positionLeverage != null)
      Text(
        "Close your ${state.quote?.market?.symbol.orEmpty()} position to change leverage.",
        style = MaterialTheme.typography.bodySmall,
        color = FlareColors.TextSecondary,
        modifier = Modifier.padding(top = 6.dp),
      )
  }
}

/** A 32dp pill drawn inside 48dp touch targets, so the row never grows. */
@Composable
private fun LeverageStepper(
  value: Int,
  maximum: Int,
  enabled: Boolean,
  scaleOpen: Boolean,
  onToggleScale: () -> Unit,
  onStep: (Int) -> Unit,
) {
  Row(
    Modifier.height(48.dp).drawBehind {
      val pill = 32.dp.toPx()
      drawRoundRect(
        FlareColors.Surface,
        topLeft = Offset(0f, (size.height - pill) / 2),
        size = Size(size.width, pill),
        cornerRadius = CornerRadius(pill / 2),
      )
    },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StepperButton(FlareIcons.Remove, "Decrease leverage", enabled && value > 1) { onStep(value - 1) }
    Text(
      "$value×",
      Modifier.fillMaxHeight()
        .widthIn(min = 40.dp)
        .clickable(
          enabled = enabled,
          role = Role.Button,
          onClickLabel = if (scaleOpen) "Hide leverage scale" else "Show leverage scale",
          onClick = onToggleScale,
        )
        .wrapContentHeight(Alignment.CenterVertically)
        .semantics { stateDescription = if (scaleOpen) "Scale shown" else "Scale hidden" },
      style = MaterialTheme.typography.titleMedium,
      color = if (enabled) FlareColors.Positive else FlareColors.TextDisabled,
      textAlign = TextAlign.Center,
    )
    StepperButton(FlareIcons.Add, "Increase leverage", enabled && value < maximum) { onStep(value + 1) }
  }
}

@Composable
private fun StepperButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.size(width = 36.dp, height = 48.dp)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      icon,
      contentDescription = null,
      modifier = Modifier.size(16.dp),
      tint = if (enabled) FlareColors.TextSecondary else FlareColors.TextDisabled,
    )
  }
}

private val ScaleInset = 10.dp

/** Round, evenly spaced stops that never crowd the 1× origin. */
internal fun leverageStops(maximum: Int): List<Int> {
  if (maximum <= 1) return listOf(1)
  val step = listOf(1, 2, 5, 10, 15, 20, 25, 50).firstOrNull { maximum / it.toDouble() <= 5 }
    ?: ((maximum + 4) / 5)
  val span = (maximum - 1).toDouble()
  val stops = mutableListOf(1)
  var value = step
  while (value < maximum) {
    if ((value - stops.last()) / span >= MIN_STOP_GAP) stops += value
    value += step
  }
  if (stops.size > 1 && (maximum - stops.last()) / span < MIN_STOP_GAP) stops.removeAt(stops.lastIndex)
  stops += maximum
  return stops
}

private const val MIN_STOP_GAP = 0.15

@Composable
private fun LeverageScale(value: Int, maximum: Int, enabled: Boolean, onChange: (Int) -> Unit) {
  val update by rememberUpdatedState(onChange)
  val stops = remember(maximum) { leverageStops(maximum) }
  val active = if (enabled) FlareColors.Positive else FlareColors.TextDisabled
  Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
    Canvas(
      Modifier.fillMaxWidth()
        .height(24.dp)
        .semantics {
          contentDescription = "Select leverage"
          progressBarRangeInfo =
            ProgressBarRangeInfo(value.toFloat(), 1f..maximum.toFloat(), maximum - 2)
          setProgress {
            update(it.roundToInt().coerceIn(1, maximum))
            true
          }
        }
        .pointerInput(maximum, enabled) {
          if (!enabled) return@pointerInput
          fun select(x: Float) {
            val inset = ScaleInset.toPx()
            val fraction = ((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
            update((1 + fraction * (maximum - 1)).roundToInt())
          }
          detectTapGestures { select(it.x) }
        }
        .pointerInput(maximum, enabled) {
          if (!enabled) return@pointerInput
          detectHorizontalDragGestures { change, _ ->
            change.consume()
            val inset = ScaleInset.toPx()
            val fraction =
              ((change.position.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
            update((1 + fraction * (maximum - 1)).roundToInt())
          }
        }
    ) {
      val inset = ScaleInset.toPx()
      val end = size.width - inset
      val y = size.height / 2
      fun xOf(v: Int) = inset + (end - inset) * (v - 1) / (maximum - 1)
      drawLine(FlareColors.BorderSubtle, Offset(inset, y), Offset(end, y), 2.dp.toPx())
      drawLine(active, Offset(inset, y), Offset(xOf(value), y), 2.dp.toPx())
      stops.filter { it != value }.forEach { stop ->
        drawDiamond(
          Offset(xOf(stop), y),
          3.5.dp.toPx(),
          if (stop <= value) active else FlareColors.BorderDefault,
          1.5.dp.toPx(),
        )
      }
      drawDiamond(Offset(xOf(value), y), 7.dp.toPx(), active, 2.dp.toPx())
    }
    Layout(
      modifier = Modifier.fillMaxWidth(),
      content = {
        stops.forEach { stop ->
          Text(
            "$stop×",
            Modifier.clip(RoundedCornerShape(4.dp))
              .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Set leverage to $stop×") {
                update(stop)
              }
              .padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (stop == value) FlareColors.TextPrimary else FlareColors.TextTertiary,
          )
        }
      },
    ) { measurables, constraints ->
      val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
      val width = constraints.maxWidth
      val inset = ScaleInset.roundToPx()
      layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
        placeables.forEachIndexed { index, placeable ->
          val center = inset + (width - 2 * inset) * (stops[index] - 1f) / (maximum - 1)
          val x = (center - placeable.width / 2f).roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
          placeable.placeRelative(x, 0)
        }
      }
    }
  }
}

/** A square turned on its point: black inside, outlined in [stroke]. */
private fun DrawScope.drawDiamond(center: Offset, radius: Float, stroke: Color, strokeWidth: Float) {
  val path = Path().apply {
    moveTo(center.x, center.y - radius)
    lineTo(center.x + radius, center.y)
    lineTo(center.x, center.y + radius)
    lineTo(center.x - radius, center.y)
    close()
  }
  drawPath(path, FlareColors.Canvas)
  drawPath(path, stroke, style = Stroke(strokeWidth, join = StrokeJoin.Round))
}
