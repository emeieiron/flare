package xyz.mcxross.flare.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.navigationevent.NavigationEvent

private const val PAGE_DURATION_MS = 240

fun pagePushEnter() = slideInHorizontally(tween(PAGE_DURATION_MS, easing = FastOutSlowInEasing)) { it }

fun pagePushExit() = slideOutHorizontally(tween(PAGE_DURATION_MS, easing = FastOutSlowInEasing)) { -it / 4 }

fun pagePopEnter() = slideInHorizontally(tween(PAGE_DURATION_MS, easing = FastOutSlowInEasing)) { -it / 4 }

fun pagePopExit() = slideOutHorizontally(tween(PAGE_DURATION_MS, easing = FastOutSlowInEasing)) { it }

// A back gesture tracks the finger directly rather than applying a timed easing curve.
fun pagePredictivePopEnter(swipeEdge: Int) =
  slideInHorizontally(tween(PAGE_DURATION_MS, easing = LinearEasing)) {
    if (swipeEdge == NavigationEvent.EDGE_RIGHT) it / 4 else -it / 4
  }

fun pagePredictivePopExit(swipeEdge: Int) =
  slideOutHorizontally(tween(PAGE_DURATION_MS, easing = LinearEasing)) {
    if (swipeEdge == NavigationEvent.EDGE_RIGHT) -it else it
  }

/** Pages move as opaque surfaces; their viewport never fades, scales, or changes size. */
@Composable
fun <T> FlarePageTransition(
  page: T,
  depth: (T) -> Int,
  modifier: Modifier = Modifier,
  content: @Composable (T) -> Unit,
) {
  AnimatedContent(
    targetState = page,
    // Each page carries the background, since a page must cover the one it slides over; the container
    // would only paint the same black a second time.
    modifier = modifier.fillMaxSize().clipToBounds(),
    transitionSpec = {
      val fromDepth = depth(initialState)
      val toDepth = depth(targetState)
      val transition = when {
        toDepth > fromDepth -> pagePushEnter() togetherWith pagePushExit()
        toDepth < fromDepth -> pagePopEnter() togetherWith pagePopExit()
        else -> EnterTransition.None togetherWith ExitTransition.None
      }
      transition.apply { targetContentZIndex = toDepth.toFloat() }.using(null)
    },
    label = "Page navigation",
  ) { currentPage ->
    Box(Modifier.fillMaxSize().background(FlareColors.Canvas)) { content(currentPage) }
  }
}
