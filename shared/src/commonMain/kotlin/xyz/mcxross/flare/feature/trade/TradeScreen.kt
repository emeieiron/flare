package xyz.mcxross.flare.feature.trade

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.valentinilk.shimmer.shimmer
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.ChartRange
import xyz.mcxross.flare.data.formatCompact
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.MarketTrade
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.LocalTransactionExplorer
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.TimeRangeSelector
import xyz.mcxross.flare.design.TradeScreenSkeleton
import xyz.mcxross.flare.design.rememberFlareShimmer

@Composable
fun TradeRoute(
  marketAddress: String?,
  modifier: Modifier = Modifier,
  onBack: () -> Unit = {},
  onOpenSetup: () -> Unit = {},
  onOpenActivity: (() -> Unit)? = null,
  viewModel: TradeViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val assetCatalog: AssetCatalogRepository = koinInject()
  val assets by assetCatalog.assets.collectAsStateWithLifecycle()
  LaunchedEffect(marketAddress) { viewModel.onIntent(TradeIntent.SelectMarket(marketAddress)) }
  TradeScreen(state, viewModel::onIntent, assets, modifier, onBack, onOpenSetup, onOpenActivity)
}

internal enum class TradeStage { MARKET, EDIT, REVIEW, RESULT }

@Composable
fun TradeScreen(
  state: TradeUiState,
  onIntent: (TradeIntent) -> Unit,
  assets: Map<String, xyz.mcxross.flare.data.AssetMetadata> = emptyMap(),
  modifier: Modifier = Modifier,
  onBack: () -> Unit = {},
  onOpenSetup: () -> Unit = {},
  onOpenActivity: (() -> Unit)? = null,
) {
  val quote = state.quote
  var showTools by rememberSaveable { mutableStateOf(false) }
  var showBook by rememberSaveable(quote?.market?.address) { mutableStateOf(false) }
  var trading by rememberSaveable(quote?.market?.address) { mutableStateOf(false) }
  var side by rememberSaveable(quote?.market?.address) { mutableStateOf(OrderSide.BUY) }
  var reviewing by rememberSaveable(
    quote?.market?.address, state.sizeInput, state.limitPriceInput, state.takeProfitInput,
    state.stopLossInput, state.orderType, state.leverage,
    state.twapDurationMinutesInput, state.twapFrequencyMinutesInput, side,
  ) { mutableStateOf(false) }
  val committed = state.transaction as? TransactionState.Committed
  val stage = when {
    committed != null -> TradeStage.RESULT
    state.orderBusy || reviewing -> TradeStage.REVIEW
    trading -> TradeStage.EDIT
    else -> TradeStage.MARKET
  }
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val clearFocus = { focus.clearFocus(); keyboard?.hide(); Unit }
  val returnToMarket = {
    clearFocus()
    if (committed != null) onIntent(TradeIntent.DismissOrderReceipt)
    reviewing = false
    trading = false
  }
  val back = {
    if (!state.orderBusy) {
      clearFocus()
      when (stage) {
        TradeStage.MARKET -> onBack()
        TradeStage.REVIEW -> reviewing = false
        else -> returnToMarket()
      }
    }
  }
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = stage != TradeStage.MARKET,
    onBackCompleted = back,
  )
  val marketScroll = rememberScrollState()
  val editorScroll = rememberScrollState()
  val reviewScroll = rememberScrollState()
  LaunchedEffect(stage, state.orderError) {
    if (stage == TradeStage.REVIEW || stage == TradeStage.RESULT) reviewScroll.scrollTo(0)
  }
  Column(modifier.fillMaxSize().background(FlareColors.Canvas).imePadding()) {
    BackBar(
      when (stage) {
        TradeStage.MARKET -> quote?.market?.symbol ?: "Market"
        TradeStage.EDIT -> "Trade ${quote?.market?.symbol.orEmpty()}"
        TradeStage.REVIEW -> if (state.orderBusy) "Submitting order" else "Review order"
        TradeStage.RESULT -> "Order submitted"
      },
      back,
      Modifier.padding(horizontal = 8.dp),
      action = {
        if (stage == TradeStage.MARKET) {
          IconButton({ showTools = true }) { Icon(Icons.Outlined.Tune, "Chart settings") }
        } else if (stage == TradeStage.REVIEW && !state.orderBusy) {
          TextButton({ clearFocus(); reviewing = false }) { Text("Edit") }
        }
      },
    )
    if (quote == null) {
      if (state.error != null) EmptyState("Market offline", "Prices appear as soon as Flare reconnects.")
      else TradeScreenSkeleton(chartStyle = state.chartStyle)
      return@Column
    }
    TradeSurfaceLayout(
      stage = stage,
      modifier = Modifier.weight(1f),
      context = { MarketContext(state, assets, stage) },
      body = {
        Crossfade(stage, animationSpec = tween(180), label = "tradeContent") { displayed ->
          Column(
            Modifier.fillMaxSize().verticalScroll(when (displayed) {
              TradeStage.MARKET -> marketScroll
              TradeStage.EDIT -> editorScroll
              else -> reviewScroll
            }).padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 24.dp),
          ) {
            when (displayed) {
              TradeStage.MARKET -> {
                TimeRangeSelector(ChartRange.entries, state.range, ChartRange::label,
                  { onIntent(TradeIntent.SelectRange(it)) }, Modifier.fillMaxWidth())
                FlareIndicatorCharts(state.candles, state.showRsi, state.showMacd, Modifier.fillMaxWidth())
                if (state.error != null && !state.stale) ActionNotice(state.error, Modifier.padding(top = 16.dp), NoticeTone.ALERT)
                MarketInformation(state, showBook, { showBook = !showBook })
              }
              TradeStage.EDIT -> OrderEditor(state, side, { side = it }, onIntent)
              TradeStage.REVIEW -> OrderReviewStatus(state, side, onIntent)
              TradeStage.RESULT -> if (committed != null) {
                Text("Order submitted", style = MaterialTheme.typography.headlineMedium)
                Text("Your transaction is confirmed. Check Activity for order status and fills.",
                  Modifier.padding(top = 12.dp, bottom = 20.dp), color = FlareColors.TextSecondary)
                val explorer = LocalTransactionExplorer.current
                val browser = LocalUriHandler.current
                ActionRow("View transaction", onClick = { runCatching { browser.openUri(explorer.url(committed.hash)) } })
                onOpenActivity?.let { open ->
                  ActionRow("View Activity", onClick = { returnToMarket(); open() })
                }
              }
            }
          }
        }
      },
      action = {
        val shimmer = rememberFlareShimmer()
        val inputError = state.orderInputError(side)
        val enabled = when (stage) {
          TradeStage.MARKET, TradeStage.RESULT -> !state.orderBusy
          else -> state.tradingEnabled && inputError == null && state.sizeInput.isNotBlank() &&
            !state.orderBusy && (state.orderType != OrderType.LIMIT || state.limitPriceInput.isNotBlank())
        }
        Column(Modifier.fillMaxWidth().background(FlareColors.Canvas)) {
          HorizontalDivider(color = FlareColors.BorderSubtle)
          FlareButton(
            when (stage) {
              TradeStage.MARKET -> "Trade ${quote.market.symbol}"
              TradeStage.EDIT -> "Review order"
              TradeStage.REVIEW -> if (state.orderBusy) "Placing your order…" else "Confirm ${if (side == OrderSide.BUY) "buy" else "sell"}"
              TradeStage.RESULT -> "Done"
            },
            {
              clearFocus()
              when (stage) {
                TradeStage.MARKET -> if (state.tradingKeyAddress == null) onOpenSetup() else trading = true
                TradeStage.EDIT -> reviewing = true
                TradeStage.REVIEW -> onIntent(TradeIntent.Submit(side))
                TradeStage.RESULT -> returnToMarket()
              }
            },
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
              .then(if (state.orderBusy) Modifier.shimmer(shimmer) else Modifier),
            enabled = enabled,
          )
        }
      },
    )
  }
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
private fun MarketInformation(state: TradeUiState, showBook: Boolean, onToggleBook: () -> Unit) {
  val quote = state.quote ?: return
  val isSpot = quote.market.assetType == AssetType.SPOT
  SectionLabel("Market stats")
  if (isSpot) {
    DetailRow("24h volume", formatCompact(quote.volume24h))
    DetailRow("Base asset", quote.market.symbol)
    DetailRow("Quote asset", quote.market.name.substringAfter('/', "USDC").trim())
  } else {
    DetailRow("24h volume", formatCompact(quote.volume24h))
    DetailRow("Open interest", formatCompact(quote.openInterest))
    DetailRow("Funding rate", quote.fundingRateBps?.let {
      "${formatQuantity(it / 100.0, 4)}%"
    } ?: "—")
    DetailRow("Maximum leverage", "${quote.market.maxLeverage}×")
  }
  TextButton(onToggleBook, Modifier.fillMaxWidth().padding(top = 12.dp)) {
    Text(if (showBook) "Hide order book" else "Order book & recent trades")
  }
  if (showBook) {
    Text(
      if (state.marketDetails.stale) {
        "Reconnecting to the order book…"
      } else {
        "Live order book · best bid ${state.marketDetails.orderBook?.bestBid?.toDoubleOrNull()?.let(::formatPrice) ?: "—"} · best ask ${state.marketDetails.orderBook?.bestAsk?.toDoubleOrNull()?.let(::formatPrice) ?: "—"}"
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
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Price", style = MaterialTheme.typography.labelSmall, color = FlareColors.TextSecondary)
      Text("Size", style = MaterialTheme.typography.labelSmall, color = FlareColors.TextSecondary)
    }
    levels.forEach { level ->
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(level.getOrNull(0)?.toDoubleOrNull()?.let(::formatPrice) ?: "—", style = MaterialTheme.typography.labelSmall)
        Text(
          level.getOrNull(1)?.toDoubleOrNull()?.let { formatQuantity(it) } ?: "—",
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
