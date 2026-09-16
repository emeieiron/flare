package xyz.mcxross.flare.feature.trade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.ChartRange
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.MarketDetails
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.data.apiWalletTopUpFor
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Candle
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.OrderValidationError
import xyz.mcxross.flare.decibel.model.validate
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences

enum class ChartStyle {
  LINE,
  CANDLESTICK,
}

data class TradeUiState(
  val quote: MarketQuote? = null,
  val range: ChartRange = ChartRange.DAY,
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

  data class SelectRange(val range: ChartRange) : TradeIntent

  data class SelectChartStyle(val style: ChartStyle) : TradeIntent

  data object ToggleRsi : TradeIntent

  data object ToggleMacd : TradeIntent

  data class SetOrderType(val type: OrderType) : TradeIntent

  data class SetSize(val value: String) : TradeIntent

  data class SetLimitPrice(val value: String) : TradeIntent

  data class SetTakeProfit(val value: String) : TradeIntent

  data class SetStopLoss(val value: String) : TradeIntent

  data class SetLeverage(val value: Int) : TradeIntent

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
) : ViewModel() {
  private val mutableUiState = MutableStateFlow(TradeUiState())
  val uiState: StateFlow<TradeUiState> = mutableUiState.asStateFlow()

  private var requestedMarket: String? = null
  private var chartJob: Job? = null
  private var marketDetailsJob: Job? = null
  private var baseBalanceJob: Job? = null
  private var lastBaseBalanceKey: String? = null

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
          if (quote != null) loadCandles(quote, mutableUiState.value.range)
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
        val range =
          ChartRange.entries.firstOrNull { it.name == values.chartRange } ?: ChartRange.DAY
        val chartStyle =
          ChartStyle.entries.firstOrNull { it.name == values.chartStyle } ?: ChartStyle.LINE
        val rangeChanged = range != mutableUiState.value.range
        mutableUiState.update {
          it.copy(
            range = range,
            chartStyle = chartStyle,
            showRsi = values.showRsi,
            showMacd = values.showMacd,
            slippageBps = values.slippageBps,
            tradingAccountAddress = values.selectedSubaccount,
          )
        }
        if (rangeChanged) mutableUiState.value.quote?.let { loadCandles(it, range) }
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
    val usdc =
      snapshot.overview?.availableToTrade ?: snapshot.overview?.crossWithdrawableBalance ?: 0.0
    mutableUiState.update { it.copy(quoteBalance = usdc) }
    if (quote?.market?.assetType == AssetType.SPOT) {
      val subaccount = snapshot.account ?: mutableUiState.value.tradingAccountAddress
      val key = "$subaccount:${quote.market.address}"
      if (subaccount != null && (forceBase || key != lastBaseBalanceKey)) {
        lastBaseBalanceKey = key
        baseBalanceJob?.cancel()
        baseBalanceJob = viewModelScope.launch {
          val base = trading.baseAssetBalance(subaccount, quote.market.symbol)
          mutableUiState.update { it.copy(baseBalance = base) }
        }
      }
    } else {
      lastBaseBalanceKey = null
      baseBalanceJob?.cancel()
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
          if (quote != null) loadCandles(quote, mutableUiState.value.range)
          if (quote != null) loadMarketDetails(quote.market.address)
        }
      }
      is TradeIntent.SelectRange -> {
        mutableUiState.update { it.copy(range = intent.range) }
        mutableUiState.value.quote?.let { loadCandles(it, intent.range) }
        viewModelScope.launch { preferences.setChartRange(intent.range.name) }
      }
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
          it.copy(orderType = intent.type, orderError = null).withTradingEnabled()
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
          val validation = draft.validate(market, state.marketDetails.orderBook)
          val validated =
            validation.value
              ?: error(
                validation.errors.joinToString("\n", transform = OrderValidationError::message)
              )
          val reconciliation = trading.reconcilePending()
          require(reconciliation.unresolved == 0) {
            "Flare is still confirming an earlier action. Try again in a moment."
          }
          accounts.refresh()
          val snapshot = accounts.snapshot.value
          require(!snapshot.stale && snapshot.account == subaccount) {
            "Your account is still reconnecting. Try again in a moment."
          }
          val isSpot = market.assetType == AssetType.SPOT
          val position = snapshot.positions.firstOrNull { it.market == market.address }
          val terminal =
            placeConfiguredOrder(
              configuration = if (isSpot) null else state.leverageCommand(subaccount, position),
              entry =
                if (isSpot) DecibelCommand.PlaceSpotOrder(subaccount, validated)
                else DecibelCommand.PlaceOrder(subaccount, validated),
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
            mutableUiState.update {
              it.copy(orderError = actionFailure(error.message, "Your order wasn’t placed."))
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

  /** The chart keeps trying on its own; selecting another market or range cancels the attempt. */
  private fun loadCandles(quote: MarketQuote, range: ChartRange) {
    chartJob?.cancel()
    chartJob = viewModelScope.launch {
      var backoffMs = CHART_RETRY_BASE_MS
      while (true) {
        mutableUiState.update { it.copy(chartLoading = true, error = null) }
        val loaded = runSuspendCatching {
          charts.candles(quote.market.address, range)
        }
          .onSuccess { snapshot ->
            mutableUiState.update {
              it.copy(
                candles = snapshot.candles,
                chartLoading = false,
                stale = snapshot.stale,
                error = snapshot.error,
              )
            }
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

internal fun OrderValidationError.message(): String =
  when (this) {
    is OrderValidationError.InvalidDecimal -> "$field: $reason"
    is OrderValidationError.InvalidMarketAddress -> "$field: $reason"
    is OrderValidationError.MarketMismatch -> "$field does not match the selected market"
    is OrderValidationError.Overflow -> "$field is too large"
    is OrderValidationError.TooPrecise -> "$field supports at most $allowedDecimals decimals"
    is OrderValidationError.NotAligned -> "$field must align to increment $increment"
    is OrderValidationError.BelowMinimum -> "$field is below minimum $minimum"
    is OrderValidationError.AboveMaximum -> "$field exceeds maximum $maximum"
    OrderValidationError.MissingLimitPrice -> "Enter a limit price"
    OrderValidationError.MissingMarketPrice -> "A reliable best bid and ask are required"
  }
