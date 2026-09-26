package xyz.mcxross.flare.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The size of the mark on the launch splash. The Android splash (androidApp's splash_mark.xml) and the
 * iOS launch screen draw it at this size in the middle of the screen, and so does the app's first
 * frame, so the handoff from the system to the app can't be seen.
 */
val SplashMarkSize = 72.dp

// The mark's three ascending bars, as fractions of the square they're drawn in.
private fun barStart(bar: Int, size: Size) =
  Offset(size.width * (0.15f + bar * 0.26f), size.height * 0.82f)

private fun barEnd(bar: Int, size: Size) =
  Offset(size.width * (0.39f + bar * 0.26f), size.height * (0.36f - bar * 0.10f))

private fun barStroke(size: Size) = size.width * 0.105f

/** The three bars of the Flare mark, filling this draw scope. */
internal fun DrawScope.drawFlareMark(color: Color) {
  val stroke = barStroke(size)
  for (bar in 0..2) drawLine(color, barStart(bar, size), barEnd(bar, size), stroke, cap = StrokeCap.Round)
}

/**
 * The mark and its light, in a square of [markSize] at this scope's origin. Each bar has its own
 * [color] and glows on its own, as strongly as [glow] says: 0 is unlit, 1 is lit, and past 1 the bar
 * runs hot for a moment, its core whitening as it flares.
 */
internal fun DrawScope.drawLitFlareMark(
  markSize: Size,
  color: (bar: Int) -> Color,
  glow: (bar: Int) -> Float,
) {
  val stroke = barStroke(markSize)
  for (bar in 0..2) {
    val level = glow(bar)
    if (level <= 0f) continue
    // A halo of stacked translucent strokes, each wider than the last, draws the same on every
    // platform, which a blur doesn't. Crowding the layers close to the bar makes the light fall off
    // fast and then trail away, the way a lit tube glows, rather than fusing the bars into one slab.
    val halo = color(bar).copy(alpha = (HALO_ALPHA * level).coerceAtMost(1f))
    for (layer in 1..HALO_LAYERS) {
      val reach = layer.toFloat() / HALO_LAYERS
      val width = stroke * (1f + HALO_SPREAD * reach * reach)
      drawLine(halo, barStart(bar, markSize), barEnd(bar, markSize), width, cap = StrokeCap.Round)
    }
  }
  for (bar in 0..2) {
    drawLine(color(bar), barStart(bar, markSize), barEnd(bar, markSize), stroke, cap = StrokeCap.Round)
  }
  for (bar in 0..2) {
    val heat = (glow(bar) - 1f).coerceIn(0f, 1f)
    if (heat <= 0f) continue
    drawLine(
      lerp(color(bar), Color.White, 0.7f).copy(alpha = heat),
      barStart(bar, markSize),
      barEnd(bar, markSize),
      stroke * 0.42f,
      cap = StrokeCap.Round,
    )
  }
}

/**
 * The Flare mark and its light; see [drawLitFlareMark]. [barColor] and [glow] are read while drawing,
 * so an animation redraws the mark without recomposing anything.
 */
@Composable
fun FlareMark(
  modifier: Modifier = Modifier,
  color: Color = FlareColors.Positive,
  barColor: (bar: Int) -> Color = { color },
  glow: (bar: Int) -> Float = { 0f },
) {
  Canvas(modifier) { drawLitFlareMark(size, barColor, glow) }
}

private const val HALO_LAYERS = 14
private const val HALO_SPREAD = 6f
private const val HALO_ALPHA = 0.03f

/** How brightly a bar rests once it has flared. */
const val IGNITION_REST = 0.5f
private const val IGNITION_PEAK = 1.8f

/**
 * Lights the mark bar by bar, bottom to top: each flares past full and settles to a steady glow. A
 * person who asked for less motion sees the bars lit at rest straight away.
 */
@Stable
class FlareIgnition internal constructor(initial: Float) {
  private val bars = List(3) { Animatable(initial) }

  /** How brightly [bar] glows right now; read it while drawing. */
  fun level(bar: Int): Float = bars[bar].value

  suspend fun ignite(
    reduceMotion: Boolean,
    rest: Float = IGNITION_REST,
    staggerMs: Long = 110,
    flareMs: Int = 240,
    settleMs: Int = 560,
  ) = coroutineScope {
    bars.forEachIndexed { index, bar ->
      launch {
        if (reduceMotion) {
          bar.snapTo(rest)
          return@launch
        }
        delay(index * staggerMs)
        bar.animateTo(IGNITION_PEAK, tween(flareMs, easing = FastOutSlowInEasing))
        bar.animateTo(rest, tween(settleMs, easing = LinearOutSlowInEasing))
      }
    }
  }

  /** Lights one bar to [level], flaring on the way unless motion is reduced. */
  suspend fun light(bar: Int, level: Float, reduceMotion: Boolean) {
    val target = bars[bar]
    if (reduceMotion || target.value >= level) {
      target.snapTo(level)
      return
    }
    target.animateTo(IGNITION_PEAK, tween(260, easing = FastOutSlowInEasing))
    target.animateTo(level, tween(620, easing = LinearOutSlowInEasing))
  }

  suspend fun snapTo(level: Float) = bars.forEach { it.snapTo(level) }
}

@Composable
fun rememberFlareIgnition(initial: Float = 0f): FlareIgnition = remember { FlareIgnition(initial) }

/** A wide, faint light around [center], as bright as [strength]. */
internal fun DrawScope.drawFlareAfterglow(center: Offset, radius: Float, strength: Float) {
  if (strength <= 0f || radius <= 0f) return
  drawCircle(
    Brush.radialGradient(
      0f to FlareColors.Positive.copy(alpha = 0.15f * strength),
      0.4f to FlareColors.Positive.copy(alpha = 0.055f * strength),
      1f to Color.Transparent,
      center = center,
      radius = radius,
    ),
    radius = radius,
    center = center,
  )
}

/**
 * How the afterglow breathes while it's on screen: slowly, between most and all of its strength. It
 * holds still for a person who asked for less motion.
 */
@Composable
fun rememberAfterglowBreath(): () -> Float {
  if (rememberReducedMotion()) return { 1f }
  val breath =
    rememberInfiniteTransition(label = "Afterglow")
      .animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Afterglow breath",
      )
  return { breath.value }
}

/**
 * The app's first frame: the mark alone on black, exactly as the launch splash draws it. It stays up
 * while Flare loads and while the account unlocks.
 */
@Composable
fun FlareSplashScreen(modifier: Modifier = Modifier, glow: (bar: Int) -> Float = { 0f }) {
  Box(modifier.fillMaxSize().background(FlareColors.Canvas), contentAlignment = Alignment.Center) {
    FlareMark(Modifier.size(SplashMarkSize), glow = glow)
  }
}
