package xyz.mcxross.flare.feature.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareIcons
import xyz.mcxross.flare.design.IGNITION_REST
import xyz.mcxross.flare.design.SplashMarkSize
import xyz.mcxross.flare.design.drawFlareAfterglow
import xyz.mcxross.flare.design.drawLitFlareMark
import xyz.mcxross.flare.design.rememberAfterglowBreath
import xyz.mcxross.flare.design.rememberFlareIgnition
import xyz.mcxross.flare.design.rememberReducedMotion

private val HeroMarkSize = 96.dp

/** Material's emphasized deceleration: a quick start that lands softly. */
private val Glide = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * The first screen. On its first showing after launch it takes the mark over from the splash, at the
 * same place and size, and carries it up into the hero while the bars ignite one after another and
 * the words rise in beneath. [intro] is false once that has played, so returning here doesn't replay it.
 */
@Composable
internal fun WelcomeScreen(
  intro: Boolean,
  onIntroShown: () -> Unit,
  busy: Boolean,
  onCreate: () -> Unit,
  onImport: () -> Unit,
  onBack: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val reduceMotion = rememberReducedMotion()
  val play = intro && !reduceMotion
  val glide = remember { Animatable(if (play) 0f else 1f) }
  val ignition = rememberFlareIgnition(initial = if (play) 0f else IGNITION_REST)
  val afterglow = remember { Animatable(if (play) 0f else 1f) }
  val reveal = remember { Animatable(if (play) 0f else 1f) }
  val breath = rememberAfterglowBreath()
  // Where the hero sits, and where this screen sits, both in window coordinates.
  var hero by remember { mutableStateOf<Rect?>(null) }
  var origin by remember { mutableStateOf(Offset.Zero) }

  LaunchedEffect(Unit) {
    onIntroShown()
    if (!play) return@LaunchedEffect
    snapshotFlow { hero }.first { it != null }
    coroutineScope {
      launch {
        delay(120)
        glide.animateTo(1f, tween(640, easing = Glide))
      }
      launch {
        delay(320)
        ignition.ignite(reduceMotion = false)
      }
      launch {
        delay(460)
        afterglow.animateTo(1f, tween(900, easing = LinearOutSlowInEasing))
      }
      launch {
        delay(560)
        reveal.animateTo(1f, tween(760, easing = LinearEasing))
      }
    }
  }

  Box(
    modifier.fillMaxSize().background(FlareColors.Canvas).onGloballyPositioned {
      origin = it.positionInRoot()
    }
  ) {
    // The light sits behind the words: it only crosses them while they're still hidden.
    Canvas(Modifier.fillMaxSize()) {
      val splash = SplashMarkSize.toPx()
      val start = Rect(center, splash / 2f)
      val end = hero?.translate(-origin) ?: start
      val mark = lerp(start, end, glide.value)
      drawFlareAfterglow(mark.center, mark.width * 2.8f, afterglow.value * breath())
      translate(mark.left, mark.top) { drawLitFlareMark(mark.size, { FlareColors.Positive }, ignition::level) }
    }
    Column(
      Modifier.fillMaxSize()
        .safeDrawingPadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
      Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).revealed(reveal::value, 0),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (onBack != null) {
          IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
            Icon(FlareIcons.ArrowBack, contentDescription = "Back")
          }
        }
        Text("flare", style = MaterialTheme.typography.headlineSmall)
      }
      Box(
        Modifier.fillMaxWidth().weight(1f).heightIn(min = HeroMarkSize * 2.4f),
        contentAlignment = Alignment.Center,
      ) {
        Spacer(Modifier.size(HeroMarkSize).onGloballyPositioned { hero = it.boundsInRoot() })
      }
      Text(
        "A clearer way\nto trade.",
        Modifier.revealed(reveal::value, 1),
        style = MaterialTheme.typography.displaySmall,
      )
      Text(
        "Trade Decibel perps and spot\nfrom a wallet only you control.",
        Modifier.padding(top = 16.dp).revealed(reveal::value, 2),
        style = MaterialTheme.typography.bodyLarge,
        color = FlareColors.TextSecondary,
      )
      Spacer(Modifier.height(40.dp))
      FlareButton(
        "Create account",
        onCreate,
        Modifier.fillMaxWidth().revealed(reveal::value, 3),
        enabled = !busy,
      )
      Spacer(Modifier.height(12.dp))
      FlareButton(
        "Import account",
        onImport,
        Modifier.fillMaxWidth().revealed(reveal::value, 4),
        enabled = !busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
  }
}

/**
 * Fades an element up into place as [progress] runs from 0 to 1, each [order] a beat after the one
 * before, so the screen assembles top to bottom rather than all at once.
 */
private fun Modifier.revealed(progress: () -> Float, order: Int): Modifier = graphicsLayer {
  val start = order * 0.1f
  val local = ((progress() - start) / 0.5f).coerceIn(0f, 1f)
  val eased = FastOutSlowInEasing.transform(local)
  alpha = eased
  translationY = (1f - eased) * 14.dp.toPx()
}
