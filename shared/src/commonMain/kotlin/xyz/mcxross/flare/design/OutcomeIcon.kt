package xyz.mcxross.flare.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import flare.shared.generated.resources.Res
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.dynamic.rememberLottieDynamicProperties
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import kotlinx.coroutines.delay

enum class Outcome(internal val file: String) {
  SUCCESS("files/order_success.json"),
  FAILURE("files/order_failure.json"),
}

/** How far the halo and the settle bounce draw past the 52dp circle. */
private val HALO_ROOM = 14.dp

/** Moment the icon settles, where the haptic lands so touch and motion agree. */
private const val SETTLE_MS = 550L

/**
 * A line icon that draws itself once: a lime check for success, a red exclamation that shakes for
 * failure. The animation files carry no palette; the accent comes from [FlareColors].
 */
@Composable
fun OutcomeIcon(outcome: Outcome, modifier: Modifier = Modifier) {
  val reducedMotion = rememberReducedMotion()
  val haptics = LocalHapticFeedback.current
  val composition by rememberLottieComposition(outcome) {
    LottieCompositionSpec.JsonString(Res.readBytes(outcome.file).decodeToString())
  }
  val progress by animateLottieCompositionAsState(composition, isPlaying = !reducedMotion, iterations = 1)
  val accent = if (outcome == Outcome.SUCCESS) FlareColors.Positive else FlareColors.Negative
  val dynamicProperties = rememberLottieDynamicProperties(outcome) {
    listOf("ring", "mark", "halo", "dot").forEach { name ->
      shapeLayer(name) {
        stroke("g", "stroke") { color { accent } }
        fill("g", "fill") { color { accent } }
      }
    }
  }
  LaunchedEffect(outcome, composition) {
    if (composition == null) return@LaunchedEffect
    if (!reducedMotion) delay(SETTLE_MS)
    haptics.performHapticFeedback(
      if (outcome == Outcome.SUCCESS) HapticFeedbackType.Confirm else HapticFeedbackType.Reject
    )
  }
  // The layout keeps the circle's 52dp width, so the ring lines up with the text beside and below
  // it, and reserves the halo's room above: a parent that clips at its top edge, such as a scroll
  // area, still shows the whole icon. Sideways the halo spreads into the screen gutter.
  Box(
    modifier.padding(top = HALO_ROOM).size(52.dp),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painter = rememberLottiePainter(
        composition = composition,
        progress = { if (reducedMotion) 1f else progress },
        dynamicProperties = dynamicProperties,
      ),
      contentDescription = null,
      modifier = Modifier.requiredSize(80.dp),
    )
  }
}

/** Content under an outcome icon waits for the icon to draw, then settles in. */
private const val REVEAL_DELAY_MS = 450L

/**
 * Fades and lifts the content that explains an outcome in just after [OutcomeIcon] has drawn, so
 * the icon lands first. With reduced motion the content is simply there.
 */
@Composable
fun rememberOutcomeReveal(): Modifier {
  val reducedMotion = rememberReducedMotion()
  val reveal = remember { Animatable(if (reducedMotion) 1f else 0f) }
  LaunchedEffect(Unit) {
    if (reveal.value < 1f) {
      delay(REVEAL_DELAY_MS)
      reveal.animateTo(1f, tween(320))
    }
  }
  return Modifier.graphicsLayer {
    alpha = reveal.value
    translationY = (1f - reveal.value) * 6.dp.toPx()
  }
}
