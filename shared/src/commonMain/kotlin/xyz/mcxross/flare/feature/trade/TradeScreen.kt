package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.assetKey
import xyz.mcxross.flare.data.ChartRange
import xyz.mcxross.flare.data.formatCompact
import xyz.mcxross.flare.data.formatPercent
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.model.MarketTrade
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.AssetHeader
import xyz.mcxross.flare.design.resolveAssetIdentity
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.TimeRangeSelector

@Composable
fun TradeRoute(
  marketAddress: String?,
  modifier: Modifier = Modifier,
  onBack: () -> Unit = {},
  onOpenSetup: () -> Unit = {},
  viewModel: TradeViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val assetCatalog: AssetCatalogRepository = koinInject()
  val assets by assetCatalog.assets.collectAsStateWithLifecycle()
  LaunchedEffect(marketAddress) { viewModel.onIntent(TradeIntent.SelectMarket(marketAddress)) }
  TradeScreen(state, viewModel::onIntent, assets, modifier, onBack, onOpenSetup)
}

@Composable
fun TradeScreen(
  state: TradeUiState,
  onIntent: (TradeIntent) -> Unit,
  assets: Map<String, xyz.mcxross.flare.data.AssetMetadata> = emptyMap(),
  modifier: Modifier = Modifier,
  onBack: () -> Unit = {},
  onOpenSetup: () -> Unit = {},
) {
  var showTools by rememberSaveable { mutableStateOf(false) }
  var showTicket by rememberSaveable { mutableStateOf(false) }
  var showBook by rememberSaveable { mutableStateOf(false) }
  val quote = state.quote
  Column(modifier.fillMaxSize().background(FlareColors.Canvas)) {
    BackBar(
      quote?.market?.symbol ?: "Market",
      onBack,
      Modifier.padding(horizontal = 8.dp),
      action = {
        IconButton({ showTools = true }) { Icon(Icons.Outlined.Tune, "Chart settings") }
      },
    )
    if (quote == null) {
      EmptyState("Loading market…", "Prices appear as soon as Flare reconnects.")
      return@Column
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
      Spacer(Modifier.height(16.dp))
      AssetHeader(
        resolveAssetIdentity(
          quote.market.symbol,
          quote.market.name,
          assets[assetKey(quote.market.symbol)],
        ),
        formatPrice(quote.markPrice),
        formatPercent(quote.changePercent24h),
        quote.changePercent24h >= 0,
      )
      Spacer(Modifier.height(24.dp))
      if (state.chartLoading) {
        Column(
          Modifier.fillMaxWidth().height(280.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          CircularProgressIndicator()
        }
      } else {
        androidx.compose.runtime.key(quote.market.address, state.range, state.chartStyle) {
          FlareChartStack(
            state.candles,
            state.chartStyle,
            state.showRsi,
            state.showMacd,
            Modifier.fillMaxWidth(),
          )
        }
      }
      TimeRangeSelector(
        ChartRange.entries,
        state.range,
        ChartRange::label,
        { onIntent(TradeIntent.SelectRange(it)) },
        Modifier.fillMaxWidth().padding(top = 12.dp),
      )
      if (state.stale || state.error != null) {
        ActionNotice(
          "Reconnecting to live prices. Trading resumes automatically.",
          Modifier.padding(top = 16.dp),
          NoticeTone.PROGRESS,
        )
      }
      SectionLabel("Market stats")
      DetailRow("24h volume", formatCompact(quote.volume24h))
      DetailRow("Open interest", formatCompact(quote.openInterest))
      DetailRow("Maximum leverage", "${quote.market.maxLeverage}×")
      TextButton({ showBook = !showBook }, Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(if (showBook) "Hide order book" else "Order book & recent trades")
      }
      if (showBook) {
        Text(
          if (state.marketDetails.stale) {
            "Reconnecting to the order book…"
          } else {
            "Live order book · best bid ${state.marketDetails.orderBook?.bestBid ?: "—"} · best ask ${state.marketDetails.orderBook?.bestAsk ?: "—"}"
          },
          color = FlareColors.TextSecondary,
          style = MaterialTheme.typography.bodyMedium,
        )
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          OrderBookSide(
            label = "Bids",
            levels = state.marketDetails.orderBook?.bids.orEmpty().take(5),
            modifier = Modifier.weight(1f),
          )
          OrderBookSide(
            label = "Asks",
            levels = state.marketDetails.orderBook?.asks.orEmpty().take(5),
            modifier = Modifier.weight(1f),
          )
        }
        Text(
          "Recent trades",
          modifier = Modifier.padding(top = 20.dp),
          style = MaterialTheme.typography.labelMedium,
        )
        if (state.marketDetails.recentTrades.isEmpty()) {
          Text(
            "No recent trades",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
        } else {
          state.marketDetails.recentTrades.take(8).forEach { trade ->
            RecentTradeRow(trade)
          }
        }
      }
      Spacer(Modifier.height(24.dp))
    }
    HorizontalDivider(color = FlareColors.BorderSubtle)
    FlareButton(
      if (state.tradingKeyAddress == null) "Create or import account"
      else "Trade ${quote.market.symbol}",
      { if (state.tradingKeyAddress == null) onOpenSetup() else showTicket = true },
      Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
    )
  }
  if (showTicket) OrderTicket(state, onIntent, { if (!state.orderBusy) showTicket = false })
  if (showTools)
    FlareSheet("Chart settings", { showTools = false }) {
      Text(
        "Chart style",
        style = MaterialTheme.typography.labelMedium,
        color = FlareColors.TextSecondary,
      )
      Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ChartStyle.entries.forEach { style ->
          FlareChip(
            if (style == ChartStyle.LINE) "Line" else "Candles",
            state.chartStyle == style,
            { onIntent(TradeIntent.SelectChartStyle(style)) },
            Modifier.weight(1f),
          )
        }
      }
      SectionLabel("Indicators")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlareChip("RSI", state.showRsi, { onIntent(TradeIntent.ToggleRsi) })
        FlareChip("MACD", state.showMacd, { onIntent(TradeIntent.ToggleMacd) })
      }
      FlareButton("Done", { showTools = false }, Modifier.fillMaxWidth().padding(top = 28.dp))
    }
}

@Composable
private fun RecentTradeRow(trade: MarketTrade) {
  val isBuy = trade.action.equals("buy", ignoreCase = true)
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      if (isBuy) "B · Buy" else "S · Sell",
      modifier = Modifier.weight(1f),
      color = if (isBuy) FlareColors.Positive else FlareColors.Negative,
      style = MaterialTheme.typography.labelSmall,
    )
    Text(
      formatPrice(trade.price),
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.labelSmall,
    )
    Text(
      formatQuantity(trade.size),
      modifier = Modifier.weight(1f),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

@Composable
private fun OrderBookSide(
  label: String,
  levels: List<List<String>>,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    levels.forEach { level ->
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(level.getOrNull(0) ?: "—", style = MaterialTheme.typography.labelSmall)
        Text(
          level.getOrNull(1) ?: "—",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    if (levels.isEmpty()) {
      Text(
        "—",
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
