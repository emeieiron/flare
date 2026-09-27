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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.ChartTimeframe
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.LiveCandles
import xyz.mcxross.flare.data.MarketDetails
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.data.apiWalletTopUpFor
import xyz.mcxross.flare.data.caughtUpWith
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.mergeCandles
import xyz.mcxross.flare.data.withLiveCandle
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Candle
import xyz.mcxross.flare.decibel.model.Market
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.OrderValidationError
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.decibel.model.validate
import xyz.mcxross.flare.decibel.model.validateTwap
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
import xyz.mcxross.flare.store.AppPreferences

enum class ChartStyle {
  LINE,
  CANDLESTICK,
}

internal fun ChartStyle.flipped(): ChartStyle =
  if (this == ChartStyle.LINE) ChartStyle.CANDLESTICK else ChartStyle.LINE

data class TradeUiState(
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
  val leverageTransaction: TransactionState? = null,
  val slippageBps: Int = 50,
  val orderBusy: Boolean = false,
  val orderError: String? = null,
  val transaction: TransactionState? = null,
  val tradingKeyAddress: String? = null,
  val tradingAccountAddress: String? = null,
  val lastSide: OrderSide? = null,
  val apiWalletNeedsTopUp: Boolean = false,
  val suggestedTopUpOctas: ULong? = null,
  val topUpTransaction: TransactionState? = null,
  val quoteBalance: Double? = null,
  val baseBalance: Double? = null,
  val fees: xyz.mcxross.flare.decibel.model.AccountFees? = null,
  val builderAddress: String? = null,
  val builderFeeBps: Int? = null,
  val builderApproved: Boolean = false,
  val spotBuilderFeeBps: Int? = null,
  val spotBuilderApproved: Boolean = false,
  val twapDurationMinutesInput: String = "60",
  val twapFrequencyMinutesInput: String = "1",
) {
  fun availableDisplay(side: OrderSide): String? {
    val isSpot = quote?.market?.assetType == AssetType.SPOT
    return if (isSpot) {
      if (side == OrderSide.BUY) {
        quoteBalance?.let { formatBalance(it) }
      } else {
        val symbol = quote?.market?.symbol.orEmpty()
        baseBalance?.let { "${formatQuantity(it, 4)} $symbol".trim() }
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

  data class Submit(val side: OrderSide) : TradeIntent

  data object DismissOrderReceipt : TradeIntent

  data object ConfirmSelfPay : TradeIntent

  data object TopUpApiWallet : TradeIntent
}

class TradeViewModel(
  private val charts: ChartRepository,
  private val markets: MarketsRepository,
  private val marketDetails: MarketDetailsRepository,
  private val trading: TradingRepository,
  private val accounts: AccountRepository,
  private val wallets: WalletRepository,
  private val preferences: AppPreferences,
  private val runtime: FlareRuntimeConfig,
) : ViewModel() {
  private val mutableUiState = MutableStateFlow(TradeUiState())
  val uiState: StateFlow<TradeUiState> = mutableUiState.asStateFlow()

  private var requestedMarket: String? = null
  private var chartJob: Job? = null
  private var olderCandlesJob: Job? = null
  /** The chart whose history is loaded. The live stream follows it while the chart is on screen. */
  private val loadedChart = MutableStateFlow<ChartKey?>(null)
  private var chartLoadedAtMs = 0L
  private var historyComplete = false
  private var marketDetailsJob: Job? = null
  private var baseBalanceJob: Job? = null
  private var lastBaseBalanceKey: String? = null
  private var feeAccount: String? = null

  init {
    viewModelScope.launch {
      markets.catalog.collect { catalog ->
        val quote =
          requestedMarket?.let { address ->
            catalog.quotes.firstOrNull { it.market.address == address }
          } ?: catalog.quotes.firstOrNull()
        val changed = quote?.market?.address != mutableUiState.value.quote?.market?.address
        mutableUiState.update {
          it.copy(
            quote = quote,
            stale = catalog.stale,
            error = if (quote == null) catalog.error else it.error,
            leverage = if (changed) 1 else it.leverage,
          )
        }
        if (changed) {
          syncPositionLeverage()
          syncBalances()
          if (quote != null) loadCandles(quote, mutableUiState.value.timeframe)
          if (quote != null) loadMarketDetails(quote.market.address)
        }
      }
    }
    viewModelScope.launch {
      wallets.profile.collect { profile ->
        mutableUiState.update { state ->
          state.copy(tradingKeyAddress = profile.apiWalletAddress).withTradingEnabled()
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
        val timeframe =
          ChartTimeframe.entries.firstOrNull { it.name == values.chartRange } ?: ChartTimeframe.Default
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
            builderAddress = values.builderAddress ?: runtime.defaultBuilderAddress,
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
    mutableUiState.update { state ->
      val isSpot = state.quote?.market?.assetType == AssetType.SPOT
      if (isSpot) {
        state.copy(
          positionLeverage = null,
          positionIsolated = null,
          leverage = 1,
        )
      } else {
        val position =
          accounts.snapshot.value.positions.firstOrNull {
            it.market == state.quote?.market?.address
          }
        state.copy(
          positionLeverage = position?.leverage,
          positionIsolated = position?.isIsolated,
          leverage =
            position?.leverage
              ?: state.leverage.coerceIn(
                1,
                state.quote?.market?.maxLeverage?.coerceIn(1, 100) ?: 1,
              ),
        )
      }
    }
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
    if (quote?.market?.assetType == AssetType.SPOT) {
      val spot = snapshot.overview?.spot
      val availableCash = spot?.positions?.filter { it.symbol.equals("USDC", true) }?.sumOf { it.amount }
      mutableUiState.update {
        it.copy(quoteBalance = availableCash?.plus(snapshot.overview?.crossWithdrawableBalance ?: 0.0))
      }
      val base = spot?.positions?.filter { it.symbol.equals(quote.market.symbol, true) }?.sumOf { it.amount }
      if (spot != null) {
        baseBalanceJob?.cancel()
        mutableUiState.update { it.copy(baseBalance = base) }
        return
      }
      val key = "$subaccount:${quote.market.address}"
      if (subaccount != null && (forceBase || key != lastBaseBalanceKey)) {
        lastBaseBalanceKey = key
        baseBalanceJob?.cancel()
        mutableUiState.update { it.copy(baseBalance = null, quoteBalance = null) }
        baseBalanceJob = viewModelScope.launch {
          val availableBase = runSuspendCatching { trading.baseAssetBalance(subaccount, quote.market.symbol) }.getOrNull()
          val cash = runSuspendCatching { trading.baseAssetBalance(subaccount, "USDC") }.getOrNull()
          mutableUiState.update {
            it.copy(baseBalance = availableBase,
              quoteBalance = cash?.plus(snapshot.overview?.crossWithdrawableBalance ?: 0.0))
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
    when (intent) {
      is TradeIntent.SelectMarket -> {
        viewModelScope.launch {
          val marketAddress = intent.marketAddress ?: preferences.values.first().selectedMarket
          requestedMarket = marketAddress
          val quote =
            marketAddress?.let { address ->
              markets.catalog.value.quotes.firstOrNull { it.market.address == address }
            } ?: markets.catalog.value.quotes.firstOrNull()
          mutableUiState.update { it.copy(quote = quote) }
          syncPositionLeverage()
          syncBalances(forceBase = true)
          preferences.setSelectedMarket(quote?.market?.address)
          if (quote != null) loadCandles(quote, mutableUiState.value.timeframe)
          if (quote != null) loadMarketDetails(quote.market.address)
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
          it.copy(orderType = intent.type, orderError = null,
            takeProfitInput = if (intent.type == OrderType.TWAP) "" else it.takeProfitInput,
            stopLossInput = if (intent.type == OrderType.TWAP) "" else it.stopLossInput,
          ).withTradingEnabled()
        }
      is TradeIntent.SetSize ->
        mutableUiState.update {
          it.copy(sizeInput = decimalCharacters(intent.value), orderError = null)
        }
      is TradeIntent.SetLimitPrice ->
        mutableUiState.update {
          it.copy(limitPriceInput = decimalCharacters(intent.value), orderError = null)
        }
      is TradeIntent.Submit -> submitOrder(intent.side, FeePayment.SPONSORED)
      is TradeIntent.SetLeverage ->
        mutableUiState.update {
          if (
            it.orderBusy ||
              it.positionLeverage != null ||
              it.quote?.market?.assetType == AssetType.SPOT
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
      TradeIntent.ConfirmSelfPay ->
        mutableUiState.value.lastSide?.let { submitOrder(it, FeePayment.SELF_PAY) }
      TradeIntent.TopUpApiWallet -> topUpApiWallet()
      TradeIntent.DismissOrderReceipt ->
        mutableUiState.update {
          if (it.orderBusy || it.transaction !is TransactionState.Committed) it
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

  private fun loadMarketDetails(market: String) {
    marketDetailsJob?.cancel()
    marketDetailsJob = viewModelScope.launch {
      marketDetails.refresh(market)
      marketDetails.connectLive(market)
    }
  }

  private fun submitOrder(side: OrderSide, feePayment: FeePayment) {
    if (mutableUiState.value.orderBusy) return
    val selfPayLeverage =
      feePayment == FeePayment.SELF_PAY && mutableUiState.value.transaction == null
    viewModelScope.launch {
      mutableUiState.update {
        it.copy(
          orderBusy = true,
          orderError = null,
          transaction = null,
          lastSide = side,
          apiWalletNeedsTopUp = false,
          suggestedTopUpOctas = null,
          leverageTransaction = null,
        )
      }
      try {
        runSuspendCatching {
          val state = mutableUiState.value
          val market = state.quote?.market ?: error("Select a market first")
          val subaccount =
            preferences.values.first().selectedSubaccount ?: error("Complete account setup")
          if (state.orderType == OrderType.MARKET && state.marketDetails.stale) {
            error("A live order book is required for a market order")
          }
          val draft = state.orderDraft(side)
          val isSpot = market.assetType == AssetType.SPOT
          val entryCommand =
            if (isSpot) {
              val validation = draft.validate(market, state.marketDetails.orderBook)
              val validated =
                validation.value
                  ?: error(
                    validation.errors.joinToString("\n") { it.message(market) }
                  )
              DecibelCommand.PlaceSpotOrder(subaccount, validated)
            } else if (state.orderType == OrderType.TWAP) {
              val duration = state.twapDurationMinutesInput.toULongOrNull()?.times(60uL) ?: 3600uL
              val frequency = state.twapFrequencyMinutesInput.toULongOrNull()?.times(60uL) ?: 60uL
              val twapValidation = draft.validateTwap(market, frequency, duration)
              val twapValidated =
                twapValidation.value
                  ?: error(
                    twapValidation.errors.joinToString("\n") { it.message(market) }
                  )
              DecibelCommand.PlaceTwapOrder(subaccount, twapValidated)
            } else {
              val validation = draft.validate(market, state.marketDetails.orderBook)
              val validated =
                validation.value
                  ?: error(
                    validation.errors.joinToString("\n") { it.message(market) }
                  )
              DecibelCommand.PlaceOrder(subaccount, validated)
            }
          val reconciliation = trading.reconcilePending()
          require(reconciliation.unresolved == 0) {
            "Flare is still confirming an earlier action. Try again in a moment."
          }
          accounts.refresh()
          val snapshot = accounts.snapshot.value
          require(!snapshot.stale && snapshot.account == subaccount) {
            "Your account is still reconnecting. Try again in a moment."
          }
          val position = snapshot.positions.firstOrNull { it.market == market.address }
          val terminal =
            trading.confirmedAction(
              VaultPrompt(
                title = "Confirm ${if (side == OrderSide.BUY) "buy" else "sell"} order",
                subtitle = "Confirm your identity",
              )
            ) {
              placeConfiguredOrder(
                configuration = if (isSpot) null else state.leverageCommand(subaccount, position),
                entry = entryCommand,
                execute = { command ->
                  trading.execute(
                    command,
                    VaultPrompt(
                      title =
                        if (command is DecibelCommand.ConfigureMarket)
                          "Apply ${state.leverage}× leverage"
                        else "Confirm ${if (side == OrderSide.BUY) "buy" else "sell"} order",
                      subtitle = "Confirm your identity",
                    ),
                    if (
                      feePayment == FeePayment.SELF_PAY &&
                        (command is DecibelCommand.ConfigureMarket) == selfPayLeverage
                    )
                      FeePayment.SELF_PAY
                    else FeePayment.SPONSORED,
                  )
                },
                onState = { stage, transaction ->
                  mutableUiState.update {
                    if (stage == OrderStage.LEVERAGE) it.copy(leverageTransaction = transaction)
                    else it.copy(transaction = transaction)
                  }
                },
              )
            }
          when (val transaction = terminal) {
            is TransactionState.Committed -> accounts.refresh()
            is TransactionState.Failed -> {
              if (transaction.selfPayEstimateOctas == null) error(transaction.message)
              trading.apiWalletTopUpFor(transaction, wallets.profile.first())?.let { topUp ->
                mutableUiState.update {
                  it.copy(apiWalletNeedsTopUp = true, suggestedTopUpOctas = topUp)
                }
              }
            }
            else -> Unit
          }
        }
          .onFailure { error ->
            // Dismissing the confirmation just leaves the review as it was.
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

  private fun topUpApiWallet() {
    if (mutableUiState.value.orderBusy) return
    viewModelScope.launch {
      mutableUiState.update {
        it.copy(orderBusy = true, orderError = null, topUpTransaction = null)
      }
      try {
        runSuspendCatching {
          val amount =
            mutableUiState.value.suggestedTopUpOctas ?: error("No network-fee top-up is required")
          trading
            .topUpApiWallet(
              amount,
              VaultPrompt(
                "Cover network fees",
                "Confirm your identity",
                requireFreshAuthorization = true,
              ),
            )
            .collect { transaction ->
              mutableUiState.update { it.copy(topUpTransaction = transaction) }
            }
          when (val terminal = mutableUiState.value.topUpTransaction) {
            is TransactionState.Committed ->
              mutableUiState.update {
                it.copy(apiWalletNeedsTopUp = false, suggestedTopUpOctas = null)
              }
            is TransactionState.Failed -> error(terminal.message)
            else -> Unit
          }
        }
          .onFailure { error ->
            mutableUiState.update {
              it.copy(orderError = actionFailure(error.message, "The network fee wasn’t covered."))
            }
          }
      } finally {
        mutableUiState.update { it.copy(orderBusy = false) }
      }
    }
  }

  /** The chart keeps trying on its own; selecting another market or timeframe cancels the attempt. */
  private fun loadCandles(quote: MarketQuote, timeframe: ChartTimeframe) {
    chartJob?.cancel()
    olderCandlesJob?.cancel()
    loadedChart.value = null
    historyComplete = false
    val key = ChartKey(quote.market.address, timeframe)
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
        val older =
          runSuspendCatching { charts.candlesBefore(key.market, key.timeframe, oldest.openTimeMs) }
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
 * Trading needs a device key and live prices only. Sessions are established by submitting, so an
 * expired one never disables the ticket.
 */
private fun TradeUiState.withTradingEnabled(): TradeUiState =
  copy(
    tradingEnabled =
      tradingKeyAddress != null &&
        !marketDetails.stale &&
        (orderType != OrderType.MARKET ||
          (marketDetails.orderBook?.bestBid != null && marketDetails.orderBook.bestAsk != null))
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

/**
 * Validation speaks in the market's own units: sizes in the asset, prices in dollars, never the
 * raw on-chain integers the checks compare.
 */
internal fun OrderValidationError.message(market: Market): String {
  val precision = market.precision
  val asset = market.symbol.substringBefore('/')
  fun label(field: String) =
    when (field) {
      "size" -> "Size"
      "price" -> "Price"
      "take-profit trigger", "take-profit limit" -> "Take profit"
      "stop-loss trigger", "stop-loss limit" -> "Stop loss"
      else -> field.replaceFirstChar(Char::uppercase)
    }
  fun amount(field: String, units: ULong): String =
    if (field == "size") "${units.plainDecimal(precision.sizeDecimals)} $asset"
    else "$${units.plainDecimal(precision.priceDecimals)}"
  return when (this) {
    is OrderValidationError.InvalidDecimal -> "${label(field)}: $reason"
    is OrderValidationError.InvalidMarketAddress -> "${label(field)}: $reason"
    is OrderValidationError.InvalidBuilderAddress -> "${label(field)}: $reason"
    is OrderValidationError.InvalidBuilderFee -> "${label(field)}: $reason"
    is OrderValidationError.MarketMismatch -> "${label(field)} doesn’t match the selected market"
    is OrderValidationError.Overflow -> "${label(field)} is too large"
    is OrderValidationError.TooPrecise ->
      "${label(field)} can have at most $allowedDecimals decimal${if (allowedDecimals == 1) "" else "s"}"
    is OrderValidationError.NotAligned ->
      "${label(field)} must be in steps of ${amount(field, increment)}"
    is OrderValidationError.BelowMinimum ->
      if (field == "size") "Minimum size is ${amount(field, minimum)}"
      else "${label(field)} must be at least ${amount(field, minimum)}"
    is OrderValidationError.AboveMaximum ->
      "${label(field)} can be at most ${amount(field, maximum)}"
    OrderValidationError.MissingLimitPrice -> "Enter a limit price"
    OrderValidationError.MissingMarketPrice -> "A reliable best bid and ask are required"
  }
}

/** On-chain units as a readable decimal: 1000000000 at 8 decimals reads "10". */
private fun ULong.plainDecimal(decimals: Int): String =
  toDecimalString(decimals).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }
