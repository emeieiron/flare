package xyz.mcxross.flare.feature.trade

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Resistance applied to a pull, so the view moves slower than the finger. */
private const val PULL_RESISTANCE = 0.5f

/** A downward flick this fast (px/s) steps back even before the threshold. */
private const val RETURN_FLING_VELOCITY = 2_000f

/** Matches the stage change animation, so the chart grows without a jump when a pull commits. */
private const val RETURN_DURATION_MS = 280

/**
 * Pulling past the top of the order view steps back one stage. Normal scrolling is untouched:
 * only drag distance the content could not consume becomes pull.
 */
@Stable
internal class PullToReturnState(
  private val scope: CoroutineScope,
  private val threshold: Float,
  private val haptics: HapticFeedback,
) {
  var offset by mutableFloatStateOf(0f)
    private set

  internal var enabled = false
  internal var onReturn: () -> Unit = {}
  private var settle: Job? = null
  private var armed = false

  val connection =
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (offset <= 0f || available.y >= 0f || source != NestedScrollSource.UserInput) {
          return Offset.Zero
        }
        // Dragging back up first retracts the pull before the content scrolls.
        val consumed = maxOf(available.y, -offset / PULL_RESISTANCE)
        drag(consumed)
        return Offset(0f, consumed)
      }

      override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
      ): Offset {
        if (!enabled || available.y <= 0f || source != NestedScrollSource.UserInput) {
          return Offset.Zero
        }
        drag(available.y)
        return Offset(0f, available.y)
      }

      override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset <= 0f) return Velocity.Zero
        release(available.y)
        return available
      }
    }

  /** A direct drag on surfaces that don't scroll, such as the chart above the form. */
  fun dragBy(delta: Float) {
    if (!enabled || (delta < 0f && offset <= 0f)) return
    drag(delta)
  }

  fun dragStopped(velocity: Float) {
    if (offset > 0f) release(velocity)
  }

  private fun drag(delta: Float) {
    settle?.cancel()
    offset = (offset + delta * PULL_RESISTANCE).coerceAtLeast(0f)
    val nowArmed = offset >= threshold
    if (nowArmed && !armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    armed = nowArmed
  }

  private fun release(velocity: Float) {
    val commit =
      enabled && (offset >= threshold || (velocity > RETURN_FLING_VELOCITY && offset > threshold / 4))
    armed = false
    if (commit) onReturn()
    val from = offset
    settle =
      scope.launch {
        animate(
          from,
          0f,
          animationSpec =
            if (commit) tween(RETURN_DURATION_MS) else spring(stiffness = Spring.StiffnessMediumLow),
        ) { value, _ ->
          offset = value
        }
      }
  }
}

@Composable
internal fun rememberPullToReturnState(enabled: Boolean, onReturn: () -> Unit): PullToReturnState {
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val threshold = with(LocalDensity.current) { 80.dp.toPx() }
  val state = remember(scope, haptics, threshold) { PullToReturnState(scope, threshold, haptics) }
  SideEffect {
    state.enabled = enabled
    state.onReturn = onReturn
  }
  return state
}
