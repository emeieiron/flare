package xyz.mcxross.flare.feature.trade

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
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

/** Measure the action first, reserve market context, then give the form the remaining viewport. */
@Composable
internal fun TradeSurfaceLayout(
  stage: TradeStage,
  modifier: Modifier = Modifier,
  context: @Composable () -> Unit,
  body: @Composable () -> Unit,
  action: @Composable () -> Unit,
) {
  val contextFraction by animateFloatAsState(
    when (stage) { TradeStage.MARKET -> 0.62f; TradeStage.EDIT -> 0.32f; else -> 0.23f },
    tween(280), label = "marketContextFraction",
  )
  val contextLimit by animateFloatAsState(
    when (stage) { TradeStage.MARKET -> 440f; TradeStage.EDIT -> 190f; else -> 140f },
    tween(280), label = "marketContextLimit",
  )
  Layout(
    modifier = modifier.fillMaxWidth().clipToBounds(),
    content = {
      Box(Modifier.clipToBounds()) { context() }
      Box(Modifier.clipToBounds()) { body() }
      Box { action() }
    },
  ) { measurables, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val footer = measurables[2].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
    val remaining = (height - footer.height).coerceAtLeast(0)
    val contextHeight = minOf(contextLimit.dp.roundToPx(), (remaining * contextFraction).roundToInt())
    val header = measurables[0].measure(Constraints.fixed(width, contextHeight))
    val content = measurables[1].measure(Constraints.fixed(width, remaining - contextHeight))
    layout(width, height) {
      header.placeRelative(0, 0)
      content.placeRelative(0, contextHeight)
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
