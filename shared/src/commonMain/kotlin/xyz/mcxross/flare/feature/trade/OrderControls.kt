package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors

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
) {
  val interactions = remember { MutableInteractionSource() }
  val focused by interactions.collectIsFocusedAsState()
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    interactionSource = interactions,
    textStyle = MaterialTheme.typography.titleLarge.copy(color = FlareColors.TextPrimary),
    cursorBrush = SolidColor(FlareColors.Positive),
    modifier =
      modifier
        .background(FlareColors.Elevated, RoundedCornerShape(16.dp))
        .border(
          1.dp,
          if (focused) FlareColors.Positive else FlareColors.Elevated,
          RoundedCornerShape(16.dp),
        )
        .semantics { contentDescription = "$label ($unit)" }
        .padding(16.dp),
    decorationBox = { input ->
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextSecondary,
          )
          Text(unit, style = MaterialTheme.typography.labelSmall, color = FlareColors.TextTertiary)
        }
        Box {
          if (value.isBlank())
            Text(
              if (optional) "Optional" else "0",
              style = MaterialTheme.typography.titleLarge,
              color = FlareColors.TextTertiary,
            )
          input()
        }
      }
    },
  )
}

/** Expandable leverage scale, kept beside its practical effect on required margin. */
@Composable
internal fun LeverageControl(state: TradeUiState, margin: Double?, onChange: (Int) -> Unit) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val maximum = state.quote?.market?.maxLeverage?.coerceIn(1, 100) ?: 1
  val editable = !state.orderBusy && state.positionLeverage == null && maximum > 1
  Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Row(
        Modifier.weight(1f)
          .heightIn(min = 48.dp)
          .clickable(enabled = editable, role = Role.Button) { expanded = !expanded }
          .semantics { contentDescription = "Leverage ${state.leverage}×" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          "Leverage",
          color = FlareColors.TextSecondary,
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          "${state.leverage}×",
          style = MaterialTheme.typography.titleLarge,
          color = FlareColors.Positive,
        )
        if (editable)
          Icon(
            Icons.Outlined.ExpandMore,
            null,
            Modifier.size(20.dp),
            tint = FlareColors.TextSecondary,
          )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(
          "Est. margin",
          color = FlareColors.TextSecondary,
          style = MaterialTheme.typography.labelSmall,
        )
        Text(margin?.let(::formatBalance) ?: "—", style = MaterialTheme.typography.titleMedium)
      }
    }
    if (expanded && editable) {
      LeverageScale(state.leverage, maximum, onChange)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (listOf(1, 2, 5, 10, 20, maximum).filter { it <= maximum }.distinct()).forEach { value ->
          FlareChip("${value}×", state.leverage == value, { onChange(value) }, Modifier.weight(1f))
        }
      }
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

@Composable
private fun LeverageScale(value: Int, maximum: Int, onChange: (Int) -> Unit) {
  val update by rememberUpdatedState(onChange)
  Canvas(
    Modifier.fillMaxWidth()
      .height(48.dp)
      .semantics {
        contentDescription = "Select leverage"
        progressBarRangeInfo =
          ProgressBarRangeInfo(value.toFloat(), 1f..maximum.toFloat(), maximum - 2)
        setProgress {
          update(it.roundToInt().coerceIn(1, maximum))
          true
        }
      }
      .pointerInput(maximum) {
        fun select(x: Float) {
          val inset = 12.dp.toPx()
          val fraction = ((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
          update((1 + fraction * (maximum - 1)).roundToInt())
        }
        detectTapGestures { select(it.x) }
      }
      .pointerInput(maximum) {
        detectDragGestures { change, _ ->
          change.consume()
          val inset = 12.dp.toPx()
          val fraction =
            ((change.position.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(
              0f,
              1f,
            )
          update((1 + fraction * (maximum - 1)).roundToInt())
        }
      }
  ) {
    val inset = 12.dp.toPx()
    val end = size.width - inset
    val y = size.height / 2
    val x = inset + (end - inset) * (value - 1) / (maximum - 1)
    drawLine(
      FlareColors.BorderSubtle,
      Offset(inset, y),
      Offset(end, y),
      3.dp.toPx(),
      StrokeCap.Round,
    )
    drawLine(FlareColors.Positive, Offset(inset, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
    drawCircle(FlareColors.Positive, 8.dp.toPx(), Offset(x, y))
    drawCircle(FlareColors.Canvas, 3.dp.toPx(), Offset(x, y))
  }
}
