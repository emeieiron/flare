package xyz.mcxross.flare.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Past the threshold the row resists, so the point of release is easy to feel. */
private const val OVERDRAG_RESISTANCE = 0.35f

/** A left flick this fast (px/s) acts even before the threshold. */
private const val ACTION_FLING_VELOCITY = 1_500f

/**
 * Swiping the row left reveals [label] and, released past the threshold, runs [onAction]. The row
 * always springs back: the action asks for confirmation rather than removing the row.
 */
@Composable
fun SwipeAction(
  label: String,
  onAction: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  color: Color = FlareColors.Negative,
  content: @Composable () -> Unit,
) {
  val haptics = LocalHapticFeedback.current
  val threshold = with(LocalDensity.current) { 88.dp.toPx() }
  val action by rememberUpdatedState(onAction)
  var offset by remember { mutableFloatStateOf(0f) }
  var armed by remember { mutableStateOf(false) }
  Box(
    modifier.fillMaxWidth().semantics {
      customActions = listOf(CustomAccessibilityAction(label) { action(); true })
    }
  ) {
    Row(
      Modifier.matchParentSize().graphicsLayer { alpha = (-offset / threshold).coerceIn(0f, 1f) },
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
    Box(
      Modifier.offset { IntOffset(offset.roundToInt(), 0) }
        .background(FlareColors.Canvas)
        .draggable(
          state =
            rememberDraggableState { delta ->
              val resisted = if (offset <= -threshold && delta < 0f) delta * OVERDRAG_RESISTANCE else delta
              offset = (offset + resisted).coerceAtMost(0f)
              val nowArmed = offset <= -threshold
              if (nowArmed && !armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
              armed = nowArmed
            },
          orientation = Orientation.Horizontal,
          enabled = enabled,
          onDragStopped = { velocity ->
            if (armed || (velocity < -ACTION_FLING_VELOCITY && offset < -threshold / 3)) action()
            armed = false
            animate(offset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
              offset = value
            }
          },
        )
    ) {
      content()
    }
  }
}
