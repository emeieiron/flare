package xyz.mcxross.flare.feature.trade

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.formatCompact
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.IndicatorChip
import xyz.mcxross.flare.design.LocalFinishSetup
import xyz.mcxross.flare.design.LocalTransactionExplorer
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.TradeScreenSkeleton
import xyz.mcxross.flare.feature.auto.AutoTradingRoute
import xyz.mcxross.flare.feature.strategies.StrategiesRoute
import xyz.mcxross.flare.venue.model.ActionKind
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.MarketTrade
import xyz.mcxross.flare.venue.model.OrderBookLevel
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType

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
  var showStrategies by rememberSaveable { mutableStateOf(false) }
  var showAuto by rememberSaveable { mutableStateOf(false) }
  val assetCatalog: AssetCatalogRepository = koinInject()
  val assets by assetCatalog.assets.collectAsStateWithLifecycle()
  LaunchedEffect(marketAddress) { viewModel.onIntent(TradeIntent.SelectMarket(marketAddress)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(lifecycleOwner, viewModel) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      launch { viewModel.followLiveChart() }
      launch { viewModel.followMarketDetails() }
    }
  }
  TradeScreen(
    state,
    viewModel::onIntent,
    assets,
    modifier,
    onBack,
    onOpenSetup,
    onOpenActivity,
    onOpenStrategies = { showStrategies = true },
  )
  val market = state.quote?.market
  if (showStrategies && market != null) {
    StrategiesRoute(
      market.id,
      market.symbol,
      state.timeframe.durationMs,
      state.tradingAccountAddress ?: state.tradingKeyAddress,
      onDismiss = { showStrategies = false },
      onOpenAuto = {
        showStrategies = false
        showAuto = true
      },
    )
  }
  if (showAuto && market != null)
    AutoTradingRoute(
      market,
      state.timeframe.durationMs,
      state.tradingAccountAddress ?: state.tradingKeyAddress,
      onDismiss = { showAuto = false },
      onOpenSetup = {
        showAuto = false
        onOpenSetup()
      },
    )
}

internal enum class TradeStage {
  MARKET,
  EDIT,
  REVIEW,
  RESULT,
  FAILURE,
}

@Composable
fun TradeScreen(
  state: TradeUiState,
  onIntent: (TradeIntent) -> Unit,
  assets: Map<String, xyz.mcxross.flare.data.AssetMetadata> = emptyMap(),
  modifier: Modifier = Modifier,
  onBack: () -> Unit = {},
  onOpenSetup: () -> Unit = {},
  onOpenActivity: (() -> Unit)? = null,
  onOpenStrategies: (() -> Unit)? = null,
) {
  val quote = state.quote
  var showBook by rememberSaveable(quote?.market?.id) { mutableStateOf(false) }
  var trading by rememberSaveable(quote?.market?.id) { mutableStateOf(false) }
  var side by rememberSaveable(quote?.market?.id) { mutableStateOf(OrderSide.BUY) }
  var exitsOpen by
    rememberSaveable(quote?.market?.id) {
      mutableStateOf(state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank())
    }
  var showTimeframes by rememberSaveable { mutableStateOf(false) }
  // Shared by the price chart and the indicators under it; a new market or timeframe starts over.
  val chartWindow = remember(quote?.market?.id, state.timeframe) { ChartWindow() }
  var reviewing by
    rememberSaveable(
      quote?.market?.id,
      state.sizeInput,
      state.limitPriceInput,
      state.takeProfitInput,
      state.stopLossInput,
      state.orderType,
      state.leverage,
      state.twapDurationMinutesInput,
      state.twapFrequencyMinutesInput,
      side,
    ) {
      mutableStateOf(false)
    }
  val stage =
    when {
      state.orderSent -> TradeStage.RESULT
      state.orderBusy -> TradeStage.REVIEW
      reviewing && state.orderError != null -> TradeStage.FAILURE
      reviewing -> TradeStage.REVIEW
      trading -> TradeStage.EDIT
      else -> TradeStage.MARKET
    }
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val clearFocus = {
    focus.clearFocus()
    keyboard?.hide()
    Unit
  }
  val returnToMarket = {
    clearFocus()
    if (state.orderSent) onIntent(TradeIntent.DismissOrderReceipt)
    reviewing = false
    trading = false
  }
  val back = {
    if (!state.orderBusy) {
      clearFocus()
      when (stage) {
        TradeStage.MARKET -> onBack()
        TradeStage.REVIEW,
        TradeStage.FAILURE -> reviewing = false
        else -> returnToMarket()
      }
    }
  }
  val pullToReturn =
    rememberPullToReturnState(
      enabled = stage != TradeStage.MARKET && !state.orderBusy,
      onReturn = back,
    )
  val swipeBack =
    rememberSwipeBackState(
      enabled = (stage == TradeStage.REVIEW || stage == TradeStage.FAILURE) && !state.orderBusy,
      onBack = {
        clearFocus()
        reviewing = false
      },
    )
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = stage != TradeStage.MARKET,
    onBackCompleted = back,
  )
  // Each stage's body reports the height it needs; the chart takes whatever is left above it.
  val naturalHeights = remember { TradeStage.entries.associateWith { mutableIntStateOf(0) } }
  val currentStage = rememberUpdatedState(stage)
  val bodyInsetPx = with(LocalDensity.current) { 24.dp.roundToPx() }
  val marketScroll = rememberScrollState()
  val editorScroll = rememberScrollState()
  val reviewScroll = rememberScrollState()
  LaunchedEffect(stage, state.orderError) {
    if (stage != TradeStage.MARKET && stage != TradeStage.EDIT) reviewScroll.scrollTo(0)
  }
  Column(modifier.fillMaxSize().background(FlareColors.Canvas).imePadding()) {
    BackBar(
      when (stage) {
        TradeStage.MARKET -> quote?.market?.symbol ?: "Market"
        TradeStage.EDIT -> "Trade ${quote?.market?.symbol.orEmpty()}"
        TradeStage.REVIEW -> if (state.orderBusy) "Submitting order" else "Review order"
        TradeStage.RESULT,
        TradeStage.FAILURE -> "Trade ${quote?.market?.symbol.orEmpty()}"
      },
      back,
      Modifier.padding(horizontal = 8.dp),
      backEnabled = !state.orderBusy,
      action = {
        if (stage == TradeStage.REVIEW && !state.orderBusy) {
          TextButton(
            {
              clearFocus()
              reviewing = false
            },
          ) {
            Text("Edit")
          }
        }
      },
    )
    if (quote == null) {
      if (state.error != null) EmptyState("Market data unavailable", "")
      else
        TradeScreenSkeleton(
          modifier = Modifier.semantics { contentDescription = "Loading market data" },
          chartStyle = state.chartStyle,
        )
      return@Column
    }
    TradeSurfaceLayout(
      stage = stage,
      modifier = Modifier.weight(1f),
      naturalBodyHeight = {
        naturalHeights.getValue(currentStage.value).intValue.let {
          if (it > 0) it + bodyInsetPx else 0
        }
      },
      pullOffset = { pullToReturn.offset },
      context = {
        // The chart takes the same pull as the form, so stepping back works from anywhere above the
        // button.
        Box(
          Modifier.fillMaxSize()
            .draggable(
              state = rememberDraggableState { pullToReturn.dragBy(it) },
              orientation = Orientation.Vertical,
              enabled = stage != TradeStage.MARKET && !state.orderBusy,
              onDragStopped = { velocity -> pullToReturn.dragStopped(velocity) },
            ),
        ) {
          MarketContext(
            state,
            assets,
            stage,
            chartWindow,
            onReachHistoryStart = { onIntent(TradeIntent.LoadOlderCandles) },
          ) {
            onIntent(TradeIntent.SelectChartStyle(state.chartStyle.flipped()))
          }
        }
      },
      body = {
        Crossfade(
          stage,
          Modifier.nestedScroll(pullToReturn.connection)
            .draggable(
              state = rememberDraggableState { swipeBack.dragBy(it) },
              orientation = Orientation.Horizontal,
              enabled = stage == TradeStage.REVIEW || stage == TradeStage.FAILURE,
              onDragStopped = { velocity -> swipeBack.dragStopped(velocity) },
            )
            .graphicsLayer { translationX = swipeBack.offset },
          animationSpec = tween(180),
          label = "tradeContent",
        ) { displayed ->
          // Trading stages space their own groups from the top, so they only need the bottom inset.
          val topInset = if (displayed == TradeStage.MARKET) 16.dp else 0.dp
          CompositionLocalProvider(
            LocalGroupsNaturalHeight provides naturalHeights.getValue(displayed),
          ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
              val editorHeight = maxHeight - 24.dp
              Column(
                Modifier.fillMaxSize()
                  .verticalScroll(
                    when (displayed) {
                      TradeStage.MARKET -> marketScroll
                      TradeStage.EDIT -> editorScroll
                      else -> reviewScroll
                    },
                  )
                  .padding(horizontal = 24.dp)
                  .padding(top = topInset, bottom = 24.dp),
              ) {
                when (displayed) {
                  TradeStage.MARKET -> {
                    // One line of chart controls, so market stats show without scrolling.
                    Row(
                      Modifier.fillMaxWidth().heightIn(min = 48.dp),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      // Captures the style alone, so the toggle skips price ticks.
                      val chartStyle = state.chartStyle
                      ChartStyleToggle(
                        chartStyle,
                        { onIntent(TradeIntent.SelectChartStyle(chartStyle.flipped())) },
                      )
                      ChartTimeframeButton(state.timeframe, { showTimeframes = true })
                      Spacer(Modifier.weight(1f))
                      IndicatorChip(
                        "RSI",
                        state.showRsi,
                        FlareColors.IndicatorCyan,
                        { onIntent(TradeIntent.ToggleRsi) },
                      )
                      IndicatorChip(
                        "MACD",
                        state.showMacd,
                        FlareColors.IndicatorOrange,
                        { onIntent(TradeIntent.ToggleMacd) },
                      )
                    }
                    FlareIndicators(
                      state.candles,
                      chartWindow,
                      state.showRsi,
                      state.showMacd,
                    )
                    if (state.error != null && !state.stale)
                      ActionNotice(state.error, Modifier.padding(top = 16.dp), NoticeTone.ALERT)
                    // An order that failed after its receipt was dismissed says so here, once.
                    state.orderError?.let {
                      ActionNotice(it, Modifier.padding(top = 16.dp), NoticeTone.ALERT)
                    }
                    onOpenStrategies?.let { open ->
                      ActionRow(
                        "Strategies",
                        "Independent signals · optional Jev confluence",
                        onClick = open,
                      )
                    }
                    MarketInformation(state, showBook, { showBook = !showBook })
                  }
                  TradeStage.EDIT ->
                    OrderEditor(
                      state,
                      side,
                      { side = it },
                      onIntent,
                      exitsOpen,
                      { exitsOpen = it },
                      editorHeight,
                    )
                  TradeStage.REVIEW -> OrderReviewStatus(state, side, editorHeight)
                  TradeStage.FAILURE ->
                    OrderFailure(state, side, editorHeight) {
                      clearFocus()
                      reviewing = false
                    }
                  TradeStage.RESULT -> {
                    val explorer = LocalTransactionExplorer.current
                    val browser = LocalUriHandler.current
                    // The link appears once the network has the transaction to show.
                    val committed = state.transaction as? ActionState.Committed
                    OrderResult(
                      state,
                      side,
                      editorHeight,
                      onOpenActivity =
                        onOpenActivity?.let { open ->
                          {
                            returnToMarket()
                            open()
                          }
                        },
                      onViewTransaction =
                        committed?.let { explorer.url(it.reference) }?.let { url ->
                          { runCatching { browser.openUri(url) } }
                        },
                    )
                  }
                }
              }
            }
          }
        }
      },
      action = {
        val inputError = state.orderInputError(side)
        val actionsUnavailable = state.capabilities.unavailableReason(ActionKind.TRADING)
        val twapUnavailable =
          state.capabilities.unavailableReason(ActionKind.TWAP)?.takeIf {
            state.orderType == OrderType.TWAP && stage == TradeStage.EDIT
          }
        // A wallet that left setup for later is shown the way to finish it instead of a ticket.
        val setupNeed =
          state.setupNeed?.takeIf { stage == TradeStage.MARKET && actionsUnavailable == it.reason }
        val finishSetup = LocalFinishSetup.current
        val enabled =
          if (stage == TradeStage.REVIEW && state.orderFeesNeedRetry)
            !state.orderBusy && !state.feeReviewLoading
          else
            when (stage) {
              TradeStage.MARKET ->
                !state.orderBusy && (actionsUnavailable == null || setupNeed != null)
              TradeStage.RESULT -> !state.orderBusy
              else ->
                state.tradingEnabled &&
                  inputError == null &&
                  state.sizeInput.isNotBlank() &&
                  twapUnavailable == null &&
                  !state.orderBusy &&
                  !state.feeReviewLoading &&
                  (state.orderType != OrderType.LIMIT || state.limitPriceInput.isNotBlank())
            }
        // Once the order settles its fee is reviewed quietly, so the review opens with it ready.
        val prefetch = stage == TradeStage.EDIT && enabled
        LaunchedEffect(
          prefetch,
          side,
          state.sizeInput,
          state.limitPriceInput,
          state.orderType,
          state.leverage,
          state.takeProfitInput,
          state.stopLossInput,
          state.twapDurationMinutesInput,
          state.twapFrequencyMinutesInput,
        ) {
          if (prefetch) {
            delay(FEE_PREFETCH_DELAY_MS)
            onIntent(TradeIntent.PrefetchOrderFees(side))
          }
        }
        Column(Modifier.fillMaxWidth().background(FlareColors.Canvas)) {
          HorizontalDivider(color = FlareColors.BorderSubtle)
          val notice = if (stage == TradeStage.MARKET) actionsUnavailable else twapUnavailable
          if (notice != null) {
            Text(
              notice,
              Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
              color = FlareColors.TextSecondary,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          FlareButton(
            when (stage) {
              TradeStage.MARKET -> setupNeed?.actionLabel ?: "Trade ${quote.market.symbol}"
              TradeStage.EDIT -> "Review order"
              TradeStage.REVIEW ->
                when {
                  state.orderBusy -> "Placing your order…"
                  state.orderFeesNeedRetry -> "Retry"
                  else -> "Confirm ${if (side == OrderSide.BUY) "buy" else "sell"}"
                }
              TradeStage.RESULT -> "Done"
              TradeStage.FAILURE -> "Review order"
            },
            {
              clearFocus()
              when (stage) {
                TradeStage.MARKET ->
                  when {
                    setupNeed != null -> finishSetup(setupNeed)
                    state.tradingKeyAddress == null -> onOpenSetup()
                    else -> trading = true
                  }
                TradeStage.EDIT -> {
                  reviewing = true
                  onIntent(TradeIntent.PrepareOrderFees(side))
                }
                TradeStage.REVIEW ->
                  onIntent(
                    if (!state.feeReviewPrepared || state.orderFeesNeedRetry)
                      TradeIntent.PrepareOrderFees(side)
                    else TradeIntent.Submit(side),
                  )
                TradeStage.RESULT -> returnToMarket()
                TradeStage.FAILURE -> onIntent(TradeIntent.PrepareOrderFees(side))
              }
            },
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            enabled = enabled,
            working = state.orderBusy,
          )
        }
      },
    )
  }
  if (showTimeframes) {
    ChartTimeframeSheet(
      state.timeframe,
      onSelect = {
        onIntent(TradeIntent.SelectTimeframe(it))
        showTimeframes = false
      },
      onDismiss = { showTimeframes = false },
    )
  }
}

@Composable
private fun MarketInformation(state: TradeUiState, showBook: Boolean, onToggleBook: () -> Unit) {
  val quote = state.quote ?: return
  val isSpot = quote.market.kind == MarketKind.SPOT
  SectionLabel("Market stats")
  if (isSpot) {
    DetailRow("24h volume", formatCompact(quote.volume24h))
    DetailRow("Base asset", quote.market.symbol)
    DetailRow("Quote asset", quote.market.displayName.substringAfter('/', "USDC").trim())
  } else {
    DetailRow("24h volume", formatCompact(quote.volume24h))
    DetailRow("Open interest", formatCompact(quote.openInterest))
    DetailRow(
      "Funding rate",
      quote.fundingRateBps?.let {
        "${formatQuantity(it / 100.0, 4)}%"
      } ?: "—",
    )
    DetailRow("Maximum leverage", "${quote.market.maxLeverage}×")
  }
  TextButton(onToggleBook, Modifier.fillMaxWidth().padding(top = 12.dp)) {
    Text(if (showBook) "Hide order book" else "Order book & recent trades")
  }
  if (showBook) {
    Text(
      if (state.marketDetails.stale || state.marketDetails.orderBook == null) {
        "Order book unavailable"
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
  val isBuy = trade.isBuy
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
  levels: List<OrderBookLevel>,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Row(
      Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("Price", style = MaterialTheme.typography.labelSmall, color = FlareColors.TextSecondary)
      Text("Size", style = MaterialTheme.typography.labelSmall, color = FlareColors.TextSecondary)
    }
    levels.forEach { level ->
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          level.price.toDoubleOrNull()?.let(::formatPrice) ?: "—",
          style = MaterialTheme.typography.labelSmall,
        )
        Text(
          level.size.toDoubleOrNull()?.let { formatQuantity(it) } ?: "—",
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

/** How long an edited order rests before its fee is reviewed in the background. */
private const val FEE_PREFETCH_DELAY_MS = 500L
