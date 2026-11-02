package xyz.mcxross.flare.feature.trade

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSetupGate
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.LiveCandles
import xyz.mcxross.flare.data.MarketDetails
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.OrderFeeReview
import xyz.mcxross.flare.data.SetupNeed
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.data.caughtUpWith
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatTokenBalance
import xyz.mcxross.flare.data.gatedBy
import xyz.mcxross.flare.data.mergeCandles
import xyz.mcxross.flare.data.withLiveCandle
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.model.AccountOverview
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.Candle
import xyz.mcxross.flare.venue.model.ChartTimeframe
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.NetworkFee
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.TradingFees

enum class ChartStyle {
  LINE,
  CANDLESTICK,
}

internal fun ChartStyle.flipped(): ChartStyle =
  if (this == ChartStyle.LINE) ChartStyle.CANDLESTICK else ChartStyle.LINE

data class TradeUiState(
  /** What the venue supports; the ticket hides the rest. */
  val capabilities: VenueCapabilities = VenueCapabilities.All,
  /** What the wallet still needs when its setup was left for later; its actions wait meanwhile. */
  val setupNeed: SetupNeed? = null,
  val quote: MarketQuote? = null,
  val timeframe: ChartTimeframe = ChartTimeframe.Default,
  val chartStyle: ChartStyle = ChartStyle.LINE,
  val showRsi: Boolean = false,
  val showMacd: Boolean = false,
  val candles: List<Candle> = emptyList(),
  val chartLoading: Boolean = false,
  val stale: Boolean = true,
  val error: String? = null,
  val tradingEnabled: Boolean = false,
  val marketDetails: MarketDetails = MarketDetails(),
  val orderType: OrderType = OrderType.MARKET,
  val sizeInput: String = "",
  val limitPriceInput: String = "",
  val takeProfitInput: String = "",
  val stopLossInput: String = "",
  val leverage: Int = 1,
  val positionLeverage: Int? = null,
  val positionIsolated: Boolean? = null,
  /** The open position's signed size in this market, negative for a short; null with none. */
  val positionSize: String? = null,
  val leverageTransaction: ActionState? = null,
  val slippageBps: Int = 50,
  val orderBusy: Boolean = false,
  val feeReview: OrderFeeReview? = null,
  val feeReviewPrepared: Boolean = false,
  val feeReviewLoading: Boolean = false,
  val feeReviewError: String? = null,
  val entryNetworkFee: NetworkFee? = null,
  val orderError: String? = null,
  val transaction: ActionState? = null,
  val tradingKeyAddress: String? = null,
  val tradingAccountAddress: String? = null,
  val lastSide: OrderSide? = null,
  val quoteBalance: Double? = null,
  val baseBalance: Double? = null,
  val fees: TradingFees? = null,
  val builderAddress: String? = null,
  val builderFeeBps: Int? = null,
  val builderApproved: Boolean = false,
  val spotBuilderFeeBps: Int? = null,
  val spotBuilderApproved: Boolean = false,
  val twapDurationMinutesInput: String = "60",
  val twapFrequencyMinutesInput: String = "1",
) {
  val orderFeePaymentBlocked: Boolean
    get() = feeReview?.let { it.sponsorshipAvailable == false && !it.canSelfPay } == true

  val orderFeesNeedRetry: Boolean
    get() = orderFeePaymentBlocked || feeReviewError != null

  val orderFeePaymentError: String?
    get() =
      if (!orderFeePaymentBlocked) null
      else if (feeReview?.localFeeReady == false || feeReview?.feePayerBalance == null)
        "Couldn’t prepare network fees. Try again."
      else "Add APT to cover network fees."

  /** The fee estimate retained on a definite sponsorship rejection. */
  val selfPayEstimate: ULong?
    get() =
      if (capabilities.sponsoredFees) (transaction as? ActionState.Failed)?.selfPay?.estimate
      else null

  fun availableDisplay(side: OrderSide): String? {
    val isSpot = quote?.market?.kind == MarketKind.SPOT
    return if (isSpot) {
      if (side == OrderSide.BUY) {
        quoteBalance?.let { formatBalance(it) }
      } else {
        val symbol = quote?.market?.symbol.orEmpty()
        baseBalance?.let { "${formatTokenBalance(it)} $symbol".trim() }
      }
    } else {
      quoteBalance?.let { formatBalance(it) }
    }
  }
}

sealed interface TradeIntent {
  data class SelectMarket(val marketAddress: String?) : TradeIntent

  data class SelectTimeframe(val timeframe: ChartTimeframe) : TradeIntent

  /** The chart has panned close to its oldest loaded candle. */
  data object LoadOlderCandles : TradeIntent

  data class SelectChartStyle(val style: ChartStyle) : TradeIntent

  data object ToggleRsi : TradeIntent

  data object ToggleMacd : TradeIntent

  data class SetOrderType(val type: OrderType) : TradeIntent

  data class SetSize(val value: String) : TradeIntent

  data class SetLimitPrice(val value: String) : TradeIntent

  data class SetTakeProfit(val value: String) : TradeIntent

  data class SetStopLoss(val value: String) : TradeIntent

  data class SetLeverage(val value: Int) : TradeIntent

  data class SetTwapDurationMinutes(val value: String) : TradeIntent

  data class SetTwapFrequencyMinutes(val value: String) : TradeIntent

  data class PrepareOrderFees(val side: OrderSide) : TradeIntent

  data class Submit(val side: OrderSide) : TradeIntent

  data object DismissOrderReceipt : TradeIntent
}

class TradeViewModel(
  private val charts: ChartRepository,
  private val markets: MarketsRepository,
  private val marketDetails: MarketDetailsRepository,
  private val trading: TradingRepository,
  private val accounts: AccountRepository,
  private val wallets: WalletRepository,
  private val preferences: AppPreferences,
  venue: VenueDescriptor,
  private val setupGate: AccountSetupGate? = null,
) : ViewModel() {
  private val capabilities = venue.capabilities
  private val defaultBuilderAddress = venue.builderAddress
  private val mutableUiState = MutableStateFlow(TradeUiState(capabilities = capabilities))
  val uiState: StateFlow<TradeUiState> = mutableUiState.asStateFlow()

  private data class OrderInputIdentity(
    val account: String?,
    val marketId: String?,
    val request: OrderRequest,
    val leverage: Int,
    val positionLeverage: Int?,
    val isolated: Boolean?,
  )

  private fun TradeUiState.orderInputIdentity(side: OrderSide) =
    OrderInputIdentity(
      tradingAccountAddress,
      quote?.market?.id,
      orderInput(side),
      leverage,
      positionLeverage,
      positionIsolated ?: quote?.market?.isolatedOnly,
    )

  private var reviewedOrderInput: OrderInputIdentity? = null
  private var feeReviewJob: Job? = null
  private var feeReviewRevision = 0L
  private var feeReviewProfile: String? = null
  private var requestedMarket: String? = null
  private var chartJob: Job? = null
  private var olderCandlesJob: Job? = null
  /** The chart whose history is loaded. The live stream follows it while the chart is on screen. */
  private val loadedChart = MutableStateFlow<ChartKey?>(null)
  private var chartLoadedAtMs = 0L
  private var historyComplete = false
  private var baseBalanceJob: Job? = null
  private var lastBaseBalanceKey: String? = null
  private var feeAccount: String? = null

  init {
    setupGate?.let { gate ->
      viewModelScope.launch {
        gate.need.collect { need ->
          mutableUiState.update {
            it.copy(setupNeed = need, capabilities = capabilities.gatedBy(need))
          }
        }
      }
    }
    viewModelScope.launch {
      markets.catalog.collect { catalog ->
        val quote =
          requestedMarket?.let { address ->
            catalog.quotes.firstOrNull { it.market.id == address }
          } ?: catalog.quotes.firstOrNull()
        val changed = quote?.market?.id != mutableUiState.value.quote?.market?.id
        mutableUiState.update {
          it.copy(
            quote = quote,
            stale = catalog.stale,
            error = if (quote == null) catalog.error else it.error,
            leverage = if (changed) 1 else it.leverage,
          )
        }
        if (changed) {
          invalidateFeeReview()
          syncPositionLeverage()
          syncBalances()
          if (quote != null) loadCandles(quote, mutableUiState.value.timeframe)
        }
      }
    }
    viewModelScope.launch {
      wallets.profile.collect { profile ->
        if (mutableUiState.value.tradingKeyAddress != profile.apiWalletAddress)
          invalidateFeeReview()
        mutableUiState.update { state ->
          state
            .copy(
              tradingKeyAddress = profile.apiWalletAddress,
            )
            .withTradingEnabled()
        }
      }
    }
    viewModelScope.launch {
      marketDetails.details.collect { details ->
        mutableUiState.update { state -> state.copy(marketDetails = details).withTradingEnabled() }
      }
    }
    viewModelScope.launch {
      preferences.values.collect { values ->
        if (
          feeReviewProfile != values.activeProfileId ||
            mutableUiState.value.tradingAccountAddress != values.selectedSubaccount ||
            mutableUiState.value.slippageBps != values.slippageBps ||
            mutableUiState.value.builderAddress !=
              (values.builderAddress ?: defaultBuilderAddress) ||
            mutableUiState.value.builderFeeBps != values.builderFeeBps ||
            mutableUiState.value.builderApproved != values.builderApproved ||
            mutableUiState.value.spotBuilderFeeBps != values.spotBuilderFeeBps ||
            mutableUiState.value.spotBuilderApproved != values.spotBuilderApproved
        )
          invalidateFeeReview()
        feeReviewProfile = values.activeProfileId
        val timeframe =
          ChartTimeframe.entries.firstOrNull { it.name == values.chartRange }
            ?: ChartTimeframe.Default
        val chartStyle =
          ChartStyle.entries.firstOrNull { it.name == values.chartStyle } ?: ChartStyle.LINE
        val timeframeChanged = timeframe != mutableUiState.value.timeframe
        mutableUiState.update {
          it.copy(
            timeframe = timeframe,
            chartStyle = chartStyle,
            showRsi = values.showRsi,
            showMacd = values.showMacd,
            slippageBps = values.slippageBps,
            tradingAccountAddress = values.selectedSubaccount,
            builderAddress = values.builderAddress ?: defaultBuilderAddress,
            builderFeeBps = values.builderFeeBps,
            builderApproved = values.builderApproved,
            spotBuilderFeeBps = values.spotBuilderFeeBps,
            spotBuilderApproved = values.spotBuilderApproved,
          )
        }
        if (timeframeChanged) mutableUiState.value.quote?.let { loadCandles(it, timeframe) }
      }
    }
    viewModelScope.launch {
      accounts.snapshot.collect {
        syncPositionLeverage()
        syncBalances(forceBase = true)
      }
    }
  }

  private fun syncPositionLeverage() {
    val before = mutableUiState.value
    mutableUiState.update { state ->
      val isSpot = state.quote?.market?.kind == MarketKind.SPOT
      if (isSpot) {
        state.copy(
          positionLeverage = null,
          positionIsolated = null,
          positionSize = null,
          leverage = 1,
        )
      } else {
        val position =
          accounts.snapshot.value.positions.firstOrNull {
            it.marketId == state.quote?.market?.id
          }
        state.copy(
          positionLeverage = position?.leverage,
          positionIsolated = position?.isolated,
          positionSize = position?.size,
          leverage =
            if (state.orderBusy) state.leverage
            else
              position?.leverage
                ?: state.leverage.coerceIn(
                  1,
                  state.quote?.market?.maxLeverage?.coerceIn(1, 100) ?: 1,
                ),
        )
      }
    }
    val after = mutableUiState.value
    if (
      !after.orderBusy &&
        (before.positionLeverage != after.positionLeverage ||
          before.positionIsolated != after.positionIsolated)
    )
      invalidateFeeReview()
  }

  private fun syncBalances(forceBase: Boolean = false) {
    val snapshot = accounts.snapshot.value
    val quote = mutableUiState.value.quote
    val subaccount = snapshot.account
    if (subaccount != null && subaccount != feeAccount) {
      feeAccount = subaccount
      mutableUiState.update { it.copy(fees = null) }
      viewModelScope.launch {
        val fees = runSuspendCatching { accounts.fees() }.getOrNull()
        if (accounts.snapshot.value.account == subaccount) {
          mutableUiState.update { it.copy(fees = fees) }
        }
      }
    }
    if (quote?.market?.kind == MarketKind.SPOT) {
      val spot = snapshot.overview?.balances?.spot
      mutableUiState.update { it.copy(quoteBalance = spotQuoteBalance(snapshot.overview)) }
      val base =
        spot?.assets?.filter { it.symbol.equals(quote.market.symbol, true) }?.sumOf { it.amount }
      if (spot != null) {
        baseBalanceJob?.cancel()
        mutableUiState.update { it.copy(baseBalance = base) }
        return
      }
      val key = "$subaccount:${quote.market.id}"
      if (subaccount != null && (forceBase || key != lastBaseBalanceKey)) {
        lastBaseBalanceKey = key
        baseBalanceJob?.cancel()
        mutableUiState.update { it.copy(baseBalance = null, quoteBalance = null) }
        baseBalanceJob = viewModelScope.launch {
          val availableBase = runSuspendCatching {
            trading.baseAssetBalance(subaccount, quote.market.symbol)
          }
            .getOrNull()
          val cash = runSuspendCatching { trading.baseAssetBalance(subaccount, "USDC") }.getOrNull()
          mutableUiState.update {
            it.copy(
              baseBalance = availableBase,
              quoteBalance = cash?.plus(snapshot.overview?.withdrawable ?: 0.0),
            )
          }
        }
      }
    } else {
      lastBaseBalanceKey = null
      baseBalanceJob?.cancel()
      mutableUiState.update { it.copy(quoteBalance = snapshot.overview?.availableToTrade) }
    }
  }

  fun onIntent(intent: TradeIntent) {
    if (
      intent is TradeIntent.SelectMarket ||
        intent is TradeIntent.SetOrderType ||
        intent is TradeIntent.SetSize ||
        intent is TradeIntent.SetLimitPrice ||
        intent is TradeIntent.SetLeverage ||
        intent is TradeIntent.SetTakeProfit ||
        intent is TradeIntent.SetStopLoss ||
        intent is TradeIntent.SetTwapDurationMinutes ||
        intent is TradeIntent.SetTwapFrequencyMinutes
    )
      invalidateFeeReview()
    when (intent) {
      is TradeIntent.SelectMarket -> {
        viewModelScope.launch {
          val marketAddress = intent.marketAddress ?: preferences.values.first().selectedMarket
          requestedMarket = marketAddress
          val quote =
            marketAddress?.let { address ->
              markets.catalog.value.quotes.firstOrNull { it.market.id == address }
            } ?: markets.catalog.value.quotes.firstOrNull()
          mutableUiState.update { it.copy(quote = quote) }
          syncPositionLeverage()
          syncBalances(forceBase = true)
          preferences.setSelectedMarket(quote?.market?.id)
          if (quote != null) loadCandles(quote, mutableUiState.value.timeframe)
        }
      }
      is TradeIntent.SelectTimeframe -> {
        mutableUiState.update { it.copy(timeframe = intent.timeframe) }
        mutableUiState.value.quote?.let { loadCandles(it, intent.timeframe) }
        viewModelScope.launch { preferences.setChartRange(intent.timeframe.name) }
      }
      TradeIntent.LoadOlderCandles -> loadOlderCandles()
      is TradeIntent.SelectChartStyle -> {
        mutableUiState.update { it.copy(chartStyle = intent.style) }
        viewModelScope.launch { preferences.setChartStyle(intent.style.name) }
      }
      TradeIntent.ToggleRsi -> {
        val show = !mutableUiState.value.showRsi
        mutableUiState.update { it.copy(showRsi = show) }
        viewModelScope.launch { preferences.setShowRsi(show) }
      }
      TradeIntent.ToggleMacd -> {
        val show = !mutableUiState.value.showMacd
        mutableUiState.update { it.copy(showMacd = show) }
        viewModelScope.launch { preferences.setShowMacd(show) }
      }
      is TradeIntent.SetOrderType ->
        mutableUiState.update {
          it
            .copy(
              orderType = intent.type,
              orderError = null,
              takeProfitInput = if (intent.type == OrderType.TWAP) "" else it.takeProfitInput,
              stopLossInput = if (intent.type == OrderType.TWAP) "" else it.stopLossInput,
            )
            .withTradingEnabled()
        }
      is TradeIntent.SetSize ->
        mutableUiState.update {
          it.copy(sizeInput = decimalCharacters(intent.value), orderError = null)
        }
      is TradeIntent.SetLimitPrice ->
        mutableUiState.update {
          it.copy(limitPriceInput = decimalCharacters(intent.value), orderError = null)
        }
      is TradeIntent.PrepareOrderFees -> prepareOrderFees(intent.side)
      is TradeIntent.Submit -> submitOrder(intent.side)
      is TradeIntent.SetLeverage ->
        mutableUiState.update {
          if (
            it.orderBusy || it.positionLeverage != null || it.quote?.market?.kind == MarketKind.SPOT
          )
            it
          else
            it.copy(
              leverage =
                intent.value.coerceIn(1, it.quote?.market?.maxLeverage?.coerceIn(1, 100) ?: 1),
              orderError = null,
            )
        }
      is TradeIntent.SetTakeProfit ->
        mutableUiState.update {
          it.copy(takeProfitInput = decimalCharacters(intent.value), orderError = null)
        }
      is TradeIntent.SetStopLoss ->
        mutableUiState.update {
          it.copy(stopLossInput = decimalCharacters(intent.value), orderError = null)
        }
      is TradeIntent.SetTwapDurationMinutes ->
        mutableUiState.update {
          it.copy(twapDurationMinutesInput = decimalCharacters(intent.value), orderError = null)
        }
      is TradeIntent.SetTwapFrequencyMinutes ->
        mutableUiState.update {
          it.copy(twapFrequencyMinutesInput = decimalCharacters(intent.value), orderError = null)
        }
      TradeIntent.DismissOrderReceipt ->
        mutableUiState.update {
          if (it.orderBusy || it.transaction !is ActionState.Committed) it
          else
            it.copy(
              sizeInput = "",
              limitPriceInput = "",
              takeProfitInput = "",
              stopLossInput = "",
              leverageTransaction = null,
              transaction = null,
              orderError = null,
            )
        }
    }
  }

  /** Keeps the selected book live only while the trade route owns this collector. */
  suspend fun followMarketDetails() {
    mutableUiState
      .map { it.quote?.market?.id }
      .distinctUntilChanged()
      .collectLatest { market ->
        if (market != null) {
          marketDetails.refresh(market)
          marketDetails.connectLive(market)
        }
      }
  }

  private fun invalidateFeeReview() {
    feeReviewRevision++
    reviewedOrderInput = null
    feeReviewJob?.cancel()
    mutableUiState.update {
      it.copy(
        feeReview = null,
        feeReviewPrepared = false,
        feeReviewLoading = false,
        feeReviewError = null,
      )
    }
  }

  private fun orderActions(state: TradeUiState, side: OrderSide): List<TradeAction> {
    val market = state.quote?.market ?: error("Select a market first")
    val subaccount = state.tradingAccountAddress ?: error("Complete account setup")
    val position = accounts.snapshot.value.positions.firstOrNull { it.marketId == market.id }
    return listOfNotNull(
      if (market.kind == MarketKind.SPOT) null else state.leverageCommand(subaccount, position),
      TradeAction.PlaceOrder(subaccount, market, state.checkedOrder(side)),
    )
  }

  private fun prepareOrderFees(side: OrderSide) {
    if (mutableUiState.value.orderBusy || mutableUiState.value.feeReviewLoading) return
    invalidateFeeReview()
    val revision = feeReviewRevision
    feeReviewJob = viewModelScope.launch {
      mutableUiState.update {
        it.copy(
          feeReviewLoading = true,
          orderError = null,
          lastSide = side,
          entryNetworkFee = null,
        )
      }
      try {
        val input = mutableUiState.value.orderInputIdentity(side)
        val actions = orderActions(mutableUiState.value, side)
        val review = trading.prepareOrderFees(actions)
        if (revision == feeReviewRevision) {
          reviewedOrderInput = input
          mutableUiState.update {
            it.copy(
              feeReview = review,
              feeReviewPrepared = true,
              feeReviewLoading = false,
            )
          }
        }
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (revision == feeReviewRevision)
          mutableUiState.update {
            it.copy(
              feeReviewLoading = false,
              feeReviewError =
                actionFailure(error.message, "Network fees could not be checked. Try again."),
            )
          }
      }
    }
  }

  private fun submitOrder(side: OrderSide) {
    if (mutableUiState.value.orderBusy || mutableUiState.value.feeReviewLoading) return
    if (!mutableUiState.value.feeReviewPrepared) {
      prepareOrderFees(side)
      return
    }
    if (mutableUiState.value.orderFeePaymentBlocked) {
      prepareOrderFees(side)
      return
    }
    viewModelScope.launch {
      mutableUiState.update {
        it.copy(
          orderBusy = true,
          orderError = null,
          transaction = null,
          lastSide = side,
          leverageTransaction = null,
          entryNetworkFee = null,
        )
      }
      try {
        runSuspendCatching {
          val subaccount =
            preferences.values.first().selectedSubaccount ?: error("Complete account setup")
          val reconciliation = trading.reconcilePending()
          require(reconciliation.unresolved == 0) {
            "Flare is still confirming an earlier action. Try again in a moment."
          }
          accounts.refresh()
          val snapshot = accounts.snapshot.value
          require(!snapshot.stale && snapshot.account == subaccount) {
            "Your account is still reconnecting. Try again in a moment."
          }
          // Refresh can change position settings or connection state while submission waits.
          // Read those facts now, before authorizing the reviewed actions.
          syncPositionLeverage()
          val state = mutableUiState.value
          checkNotNull(state.quote) { "Select a market first" }
          require(state.tradingAccountAddress == subaccount) {
            "Your account changed. Check network fees again."
          }
          if (state.orderType == OrderType.MARKET && state.marketDetails.stale) {
            error("A live order book is required for a market order")
          }
          val review = state.feeReview
          check(reviewedOrderInput == state.orderInputIdentity(side)) {
            "Your order changed. Check network fees again."
          }
          // Book updates may change a newly resolved market price. Confirmation executes the
          // reviewed bound exactly; it must never widen that bound to follow the live book.
          val actions = review?.actions ?: orderActions(state, side)
          val entryAction = actions.filterIsInstance<TradeAction.PlaceOrder>().single()
          val configuration = actions.filterIsInstance<TradeAction.ConfigureMarket>().singleOrNull()
          val prompt =
            VaultPrompt(
              title = "Confirm ${if (side == OrderSide.BUY) "buy" else "sell"} order",
              subtitle = "Confirm your identity",
            )
          val place: suspend () -> ActionState = {
            placeConfiguredOrder(
              configuration = configuration,
              entry = entryAction,
              execute = { action ->
                trading.execute(
                  action,
                  VaultPrompt(
                    title =
                      if (action is TradeAction.ConfigureMarket) "Apply ${state.leverage}× leverage"
                      else "Confirm ${if (side == OrderSide.BUY) "buy" else "sell"} order",
                    subtitle = "Confirm your identity",
                  ),
                  FeePayment.SPONSORED,
                )
              },
              onState = { stage, transaction ->
                mutableUiState.update {
                  val fee =
                    when (transaction) {
                      is ActionState.FeeEstimated -> transaction.fee
                      is ActionState.Pending -> transaction.networkFee
                      is ActionState.Committed -> transaction.networkFee
                      else -> null
                    }
                  if (stage == OrderStage.LEVERAGE)
                    it.copy(
                      leverageTransaction = transaction,
                    )
                  else
                    it.copy(
                      transaction = transaction,
                      entryNetworkFee = fee ?: it.entryNetworkFee,
                    )
                }
              },
            )
          }
          val terminal =
            if (review != null) {
              trading.confirmedOrderFees(review, review.canSelfPay, prompt, place)
            } else trading.confirmedAction(prompt, place)
          // A reviewed plan is single-use; a new attempt must review a fresh budget.
          invalidateFeeReview()
          when (val transaction = terminal) {
            is ActionState.Committed -> accounts.refresh()
            is ActionState.Failed -> error(transaction.message)
            else -> Unit
          }
        }
          .onFailure { error ->
            // A cancelled or failed authorization cannot reuse a single-use fee plan.
            invalidateFeeReview()
            if (!error.isAuthorizationCancelled()) {
              mutableUiState.update {
                it.copy(orderError = actionFailure(error.message, "Your order wasn’t placed."))
              }
            }
          }
      } finally {
        mutableUiState.update { it.copy(orderBusy = false) }
      }
    }
  }

  /**
   * The chart keeps trying on its own; selecting another market or timeframe cancels the attempt.
   */
  private fun loadCandles(quote: MarketQuote, timeframe: ChartTimeframe) {
    chartJob?.cancel()
    olderCandlesJob?.cancel()
    loadedChart.value = null
    historyComplete = false
    val key = ChartKey(quote.market.id, timeframe)
    chartJob = viewModelScope.launch {
      var backoffMs = CHART_RETRY_BASE_MS
      while (true) {
        mutableUiState.update { it.copy(chartLoading = true, error = null) }
        val loaded = runSuspendCatching {
          charts.candles(key.market, timeframe)
        }
          .onSuccess { snapshot ->
            chartLoadedAtMs = Clock.System.now().toEpochMilliseconds()
            mutableUiState.update {
              it.copy(
                candles = snapshot.candles,
                chartLoading = false,
                stale = snapshot.stale,
                error = snapshot.error,
              )
            }
            loadedChart.value = key
          }
          .onFailure {
            mutableUiState.update {
              it.copy(
                chartLoading = false,
                stale = true,
                error = "Reconnecting to the price history…",
              )
            }
          }
          .isSuccess
        if (loaded) return@launch
        delay(backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(CHART_RETRY_MAX_MS)
      }
    }
  }

  /** Streams the chart's forming candle and each new one. Run it while the chart is on screen. */
  suspend fun followLiveChart() {
    loadedChart.collectLatest { key ->
      if (key == null) return@collectLatest
      charts.liveCandles(key.market, key.timeframe).collect { event ->
        when (event) {
          LiveCandles.Connected -> catchUpChart(key)
          is LiveCandles.Update ->
            mutableUiState.update { it.copy(candles = it.candles.withLiveCandle(event.candle)) }
        }
      }
    }
  }

  /** Fills in what the stream missed while away, unless the history is only moments old. */
  private suspend fun catchUpChart(key: ChartKey) {
    val now = Clock.System.now().toEpochMilliseconds()
    if (now - chartLoadedAtMs < CHART_FRESH_MS) return
    val latest = runSuspendCatching { charts.candles(key.market, key.timeframe) }.getOrNull()
    if (latest == null || latest.stale || loadedChart.value != key) return
    chartLoadedAtMs = now
    historyComplete = false
    mutableUiState.update {
      it.copy(candles = it.candles.caughtUpWith(latest.candles, key.timeframe.durationMs))
    }
  }

  /**
   * Adds the page before the oldest candle, quietly, as the chart pans toward it. A failed page is
   * tried again a few times, because the chart only asks again once it moves.
   */
  private fun loadOlderCandles() {
    val key = loadedChart.value ?: return
    val loaded = mutableUiState.value.candles
    val oldest = loaded.firstOrNull() ?: return
    if (historyComplete || loaded.size >= MAX_CHART_CANDLES || olderCandlesJob?.isActive == true) {
      return
    }
    olderCandlesJob = viewModelScope.launch {
      var backoffMs = CHART_RETRY_BASE_MS
      repeat(OLDER_CANDLES_ATTEMPTS) { attempt ->
        val older = runSuspendCatching {
          charts.candlesBefore(key.market, key.timeframe, oldest.openTimeMs)
        }
          .getOrNull()
        if (loadedChart.value != key) return@launch
        if (older != null) {
          if (older.isEmpty()) historyComplete = true
          else mutableUiState.update { it.copy(candles = mergeCandles(older, it.candles)) }
          return@launch
        }
        if (attempt < OLDER_CANDLES_ATTEMPTS - 1) delay(backoffMs)
        backoffMs *= 2
      }
    }
  }

  private data class ChartKey(val market: String, val timeframe: ChartTimeframe)
}

/**
 * The USDC a spot buy can spend: the venue's own figure where it gives one, such as Hyperliquid's
 * unified margin whose spot USDC already holds the perpetuals' value, else spot USDC plus what
 * perpetuals can withdraw.
 */
fun spotQuoteBalance(overview: AccountOverview?): Double? {
  val balances = overview?.balances ?: return null
  balances.spotBuyingPower?.let {
    return it
  }
  val cash = balances.spot?.assets?.filter { it.symbol.equals("USDC", true) }?.sumOf { it.amount }
  return cash?.plus(overview.withdrawable)
}

/**
 * Trading needs a device key and live prices only. Sessions are established by submitting, so an
 * expired one never disables the ticket.
 */
private fun TradeUiState.withTradingEnabled(): TradeUiState =
  copy(
    tradingEnabled =
      tradingKeyAddress != null &&
        !marketDetails.stale &&
        (orderType != OrderType.MARKET ||
          (marketDetails.orderBook?.bestBid != null && marketDetails.orderBook.bestAsk != null)),
  )

private fun decimalCharacters(value: String): String =
  value
    .filter { it.isDigit() || it == '.' }
    .let { filtered ->
      val firstDot = filtered.indexOf('.')
      if (firstDot < 0) filtered
      else filtered.take(firstDot + 1) + filtered.drop(firstDot + 1).replace(".", "")
    }

private const val CHART_RETRY_BASE_MS = 2_000L
private const val CHART_RETRY_MAX_MS = 30_000L
private const val CHART_FRESH_MS = 5_000L
private const val OLDER_CANDLES_ATTEMPTS = 3

/** Enough history to pan through without slowing the chart down as it keeps growing. */
private const val MAX_CHART_CANDLES = 10_000
