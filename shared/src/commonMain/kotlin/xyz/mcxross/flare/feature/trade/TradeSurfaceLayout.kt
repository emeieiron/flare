package xyz.mcxross.flare.feature.trade

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import xyz.mcxross.flare.data.AssetMetadata
import xyz.mcxross.flare.data.assetKey
import xyz.mcxross.flare.data.formatPercent
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.design.AssetIcon
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.InstrumentBadge
import xyz.mcxross.flare.design.PriceChartSkeleton
import xyz.mcxross.flare.design.resolveAssetIdentity

/** The chart never shrinks below this, so the price and its trend stay readable. */
private val MinContext = 120.dp

/** The chart in market mode, and the most it may take while trading. */
private val MarketContextLimit = 440.dp
private const val MARKET_CONTEXT_FRACTION = 0.62f

/**
 * Measure the action first; in market mode the chart takes a fixed share of what remains. In the
 * trading stages the body is anchored to the action at its natural height and the chart takes the
 * rest, between [MinContext] and the market-mode height.
 */
@Composable
internal fun TradeSurfaceLayout(
  stage: TradeStage,
  modifier: Modifier = Modifier,
  naturalBodyHeight: () -> Int = { 0 },
  pullOffset: () -> Float = { 0f },
  context: @Composable () -> Unit,
  body: @Composable () -> Unit,
  action: @Composable () -> Unit,
) {
  val density = LocalDensity.current
  var totalHeight by remember { mutableIntStateOf(0) }
  var footerHeight by remember { mutableIntStateOf(0) }
  val currentStage by rememberUpdatedState(stage)
  val target by remember(density) {
    derivedStateOf {
      val remaining = (totalHeight - footerHeight).coerceAtLeast(0)
      if (remaining == 0) return@derivedStateOf null
      val marketCap = minOf(
        with(density) { MarketContextLimit.roundToPx() },
        (remaining * MARKET_CONTEXT_FRACTION).roundToInt(),
      )
      if (currentStage == TradeStage.MARKET) return@derivedStateOf currentStage to marketCap
      // Until the new body has measured itself, hold the chart where it is.
      val natural = naturalBodyHeight().takeIf { it > 0 } ?: return@derivedStateOf null
      val floor = minOf(with(density) { MinContext.roundToPx() }, remaining / 3)
      currentStage to (remaining - natural).coerceIn(floor, maxOf(floor, marketCap))
    }
  }
  val contextHeight = remember { Animatable(-1f) }
  LaunchedEffect(contextHeight) {
    var lastStage: TradeStage? = null
    snapshotFlow { target }.filterNotNull().collectLatest { (targetStage, height) ->
      when {
        contextHeight.value < 0f -> contextHeight.snapTo(height.toFloat())
        // Stage changes share the pull and page timing; content growing in place follows on a spring.
        targetStage != lastStage -> contextHeight.animateTo(height.toFloat(), tween(280))
        else -> contextHeight.animateTo(height.toFloat(), spring(stiffness = Spring.StiffnessMediumLow))
      }
      lastStage = targetStage
    }
  }
  Layout(
    modifier = modifier.fillMaxWidth().clipToBounds().onSizeChanged { totalHeight = it.height },
    content = {
      Box(Modifier.clipToBounds()) { context() }
      Box(Modifier.clipToBounds()) { body() }
      Box(Modifier.onSizeChanged { footerHeight = it.height }) { action() }
    },
  ) { measurables, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val footer = measurables[2].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
    val remaining = (height - footer.height).coerceAtLeast(0)
    val contextHeightPx =
      if (contextHeight.value >= 0f) contextHeight.value.roundToInt()
      else minOf(MarketContextLimit.roundToPx(), (remaining * MARKET_CONTEXT_FRACTION).roundToInt())
    val contextH = contextHeightPx.coerceIn(0, remaining)
    // A pull grows the chart and slides the body down without resizing it, so the form never reflows.
    val pull = pullOffset().roundToInt().coerceIn(0, remaining - contextH)
    val header = measurables[0].measure(Constraints.fixed(width, contextH + pull))
    val content = measurables[1].measure(Constraints.fixed(width, remaining - contextH))
    layout(width, height) {
      header.placeRelative(0, 0)
      content.placeRelative(0, contextH + pull)
      footer.placeRelative(0, remaining)
    }
  }
}

@Composable
internal fun MarketContext(
  state: TradeUiState,
  assets: Map<String, AssetMetadata>,
  stage: TradeStage,
) {
  val quote = state.quote ?: return
  BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
    val contextHeight = maxHeight
    val expansion by animateFloatAsState(
      if (stage == TradeStage.MARKET && contextHeight >= 280.dp) 1f else 0f,
      tween(240), label = "assetHeaderExpansion",
    )
    val identity = resolveAssetIdentity(quote.market.symbol, quote.market.name, assets[assetKey(quote.market.symbol)])
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.fillMaxWidth().height((72 * expansion).dp).clipToBounds().graphicsLayer { alpha = expansion },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        AssetIcon(identity, Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
          Text(identity.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
          Text(identity.symbol, color = FlareColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
        InstrumentBadge(if (quote.market.assetType == AssetType.SPOT) "SPOT" else "PERP")
      }
      Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatPrice(quote.markPrice), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
          fontSize = (24 + 16 * expansion).sp, maxLines = 1)
        Text(formatPercent(quote.changePercent24h), style = MaterialTheme.typography.labelMedium,
          color = if (quote.changePercent24h >= 0) FlareColors.Positive else FlareColors.Negative)
      }
      Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
        if (state.chartLoading) PriceChartSkeleton(Modifier.fillMaxSize(), chartStyle = state.chartStyle)
        else FlarePriceChart(state.candles, state.chartStyle, Modifier.fillMaxSize())
      }
    }
  }
}
