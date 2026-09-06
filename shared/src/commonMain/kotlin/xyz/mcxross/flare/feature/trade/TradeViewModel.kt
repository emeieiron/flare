package xyz.mcxross.flare.feature.trade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.TradingSigner
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.Candle
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.OrderDraft
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.OrderValidationError
import xyz.mcxross.flare.decibel.model.SlippageBps
import xyz.mcxross.flare.decibel.model.validate
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences

enum class ChartStyle {
  LINE,
  CANDLESTICK,
}

data class TradeUiState(
  val quote: MarketQuote? = null,
  val range: ChartRange = ChartRange.DAY,
  val chartStyle: ChartStyle = ChartStyle.CANDLESTICK,
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
  val slippageBps: Int = 50,
  val orderBusy: Boolean = false,
  val orderError: String? = null,
  val transaction: TransactionState? = null,
  val expectedSignerAddress: String? = null,
  val sessionSignerAddress: String? = null,
  val lastSide: OrderSide? = null,
  val apiWalletNeedsTopUp: Boolean = false,
  val suggestedTopUpOctas: ULong? = null,
  val topUpTransaction: TransactionState? = null,
)

sealed interface TradeIntent {
  data class SelectMarket(val marketAddress: String?) : TradeIntent

  data class SelectRange(val range: ChartRange) : TradeIntent

  data class SelectChartStyle(val style: ChartStyle) : TradeIntent

  data object ToggleRsi : TradeIntent

  data object ToggleMacd : TradeIntent

  data object Retry : TradeIntent

  data class SetOrderType(val type: OrderType) : TradeIntent

  data class SetSize(val value: String) : TradeIntent

  data class SetLimitPrice(val value: String) : TradeIntent

  data class Submit(val side: OrderSide) : TradeIntent

  data object Unlock : TradeIntent

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
  private val sessions: SessionRepository,
  private val preferences: AppPreferences,
) : ViewModel() {
  private val mutableUiState = MutableStateFlow(TradeUiState())
  val uiState: StateFlow<TradeUiState> = mutableUiState.asStateFlow()

  private var requestedMarket: String? = null
  private var chartJob: Job? = null
  private var marketDetailsJob: Job? = null

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
          )
        }
        if (changed && quote != null) loadCandles(quote, mutableUiState.value.range)
        if (changed && quote != null) loadMarketDetails(quote.market.address)
      }
    }
    viewModelScope.launch {
      wallets.profile.collect { profile ->
        val expected = profile.apiWalletAddress ?: profile.ownerAddress
        mutableUiState.update { state ->
          state.copy(
            expectedSignerAddress = expected,
            tradingEnabled = state.canTrade(expected, sessions.status.value?.walletAddress),
          )
        }
      }
    }
    viewModelScope.launch {
      marketDetails.details.collect { details ->
        mutableUiState.update { state ->
          state.copy(
            marketDetails = details,
            tradingEnabled =
              state
                .copy(marketDetails = details)
                .canTrade(
                  state.expectedSignerAddress,
                  sessions.status.value?.walletAddress,
                ),
          )
        }
      }
    }
    viewModelScope.launch {
      preferences.values.collect { values ->
        val range =
          ChartRange.entries.firstOrNull { it.name == values.chartRange } ?: ChartRange.DAY
        val chartStyle =
          ChartStyle.entries.firstOrNull { it.name == values.chartStyle } ?: ChartStyle.CANDLESTICK
        val rangeChanged = range != mutableUiState.value.range
        mutableUiState.update {
          it.copy(
            range = range,
            chartStyle = chartStyle,
            showRsi = values.showRsi,
            showMacd = values.showMacd,
            slippageBps = values.slippageBps,
          )
        }
        if (rangeChanged) mutableUiState.value.quote?.let { loadCandles(it, range) }
      }
    }
    viewModelScope.launch {
      sessions.status.collect { session ->
        mutableUiState.update { state ->
          state.copy(
            sessionSignerAddress = session?.walletAddress,
            tradingEnabled = state.canTrade(state.expectedSignerAddress, session?.walletAddress),
          )
        }
      }
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
      TradeIntent.Retry ->
        mutableUiState.value.quote?.let {
          loadCandles(it, mutableUiState.value.range)
          loadMarketDetails(it.market.address)
        }
      is TradeIntent.SetOrderType ->
        mutableUiState.update {
          it.copy(
            orderType = intent.type,
            orderError = null,
            tradingEnabled =
              it
                .copy(orderType = intent.type)
                .canTrade(
                  it.expectedSignerAddress,
                  sessions.status.value?.walletAddress,
                ),
          )
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
      TradeIntent.ConfirmSelfPay ->
        mutableUiState.value.lastSide?.let { submitOrder(it, FeePayment.SELF_PAY) }
      TradeIntent.TopUpApiWallet -> topUpApiWallet()
      TradeIntent.Unlock -> unlockTrading()
    }
  }

  private fun unlockTrading() {
    if (mutableUiState.value.orderBusy) return
    viewModelScope.launch {
      mutableUiState.update { it.copy(orderBusy = true, orderError = null) }
      runSuspendCatching {
        val subaccount =
          preferences.values.first().selectedSubaccount
            ?: error("Set up a Decibel subaccount first")
        val profile = wallets.profile.first()
        if (profile.apiWalletAddress != null) {
          accounts.connectApi(
            subaccount,
            VaultPrompt(
              "Unlock trading wallet",
              "Authorize the delegated API wallet for five minutes.",
            ),
          )
        } else {
          accounts.connectOwner(
            subaccount,
            VaultPrompt("Unlock owner wallet", "Authorize Decibel trading for five minutes."),
          )
        }
      }
        .onFailure { error ->
          mutableUiState.update {
            it.copy(orderError = error.message ?: "Unable to unlock trading")
          }
        }
      mutableUiState.update { it.copy(orderBusy = false) }
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
    viewModelScope.launch {
      mutableUiState.update {
        it.copy(
          orderBusy = true,
          orderError = null,
          transaction = null,
          lastSide = side,
          apiWalletNeedsTopUp = false,
          suggestedTopUpOctas = null,
        )
      }
      runSuspendCatching {
        val state = mutableUiState.value
        val market = state.quote?.market ?: error("Select a market first")
        val subaccount =
          sessions.status.value?.subaccount ?: error("Unlock a wallet session before trading")
        if (state.orderType == OrderType.MARKET && state.marketDetails.stale) {
          error("A live order book is required for a market order")
        }
        val draft =
          OrderDraft(
            marketAddress = market.address,
            side = side,
            type = state.orderType,
            size = DecimalInput(state.sizeInput),
            limitPrice = state.limitPriceInput.takeIf(String::isNotBlank)?.let(::DecimalInput),
            slippage = SlippageBps(state.slippageBps.toUInt()),
          )
        val validation = draft.validate(market, state.marketDetails.orderBook)
        val validated =
          validation.value
            ?: error(
              validation.errors.joinToString("\n", transform = OrderValidationError::message)
            )
        val profile = wallets.profile.first()
        val signer =
          if (profile.apiWalletAddress != null) TradingSigner.API else TradingSigner.OWNER
        trading
          .execute(
            command = DecibelCommand.PlaceOrder(subaccount, validated),
            signer = signer,
            prompt =
              VaultPrompt(
                title = "Authorize ${if (side == OrderSide.BUY) "buy" else "sell"} order",
                subtitle = "Review and authorize the simulated Decibel transaction.",
              ),
            feePayment = feePayment,
          )
          .collect { transaction ->
            mutableUiState.update { it.copy(transaction = transaction) }
          }
        when (val transaction = mutableUiState.value.transaction) {
          is TransactionState.Committed -> accounts.refresh()
          is TransactionState.Failed -> {
            val estimate = transaction.selfPayEstimateOctas
            if (estimate == null) {
              error(transaction.message)
            } else if (signer == TradingSigner.API && profile.ownerAddress != null) {
              val balance = runSuspendCatching { trading.apiWalletAptBalance() }.getOrNull()
              if (balance != null && balance < estimate) {
                mutableUiState.update {
                  it.copy(
                    apiWalletNeedsTopUp = true,
                    suggestedTopUpOctas = suggestedApiTopUp(estimate),
                  )
                }
              }
            }
          }
          else -> Unit
        }
      }
        .onFailure { error ->
          mutableUiState.update {
            it.copy(orderError = error.message ?: "The order could not be submitted")
          }
        }
      mutableUiState.update { it.copy(orderBusy = false) }
    }
  }

  private fun topUpApiWallet() {
    if (mutableUiState.value.orderBusy) return
    viewModelScope.launch {
      mutableUiState.update {
        it.copy(orderBusy = true, orderError = null, topUpTransaction = null)
      }
      runSuspendCatching {
        val amount =
          mutableUiState.value.suggestedTopUpOctas ?: error("No API-wallet top-up is required")
        val subaccount =
          preferences.values.first().selectedSubaccount
            ?: error("Set up a Decibel subaccount first")
        accounts.connectOwner(
          subaccount,
          VaultPrompt(
            "Authorize API-wallet top-up",
            "Confirm the owner wallet before transferring APT to the API wallet.",
            requireFreshAuthorization = true,
          ),
        )
        trading
          .topUpApiWallet(
            amount,
            VaultPrompt(
              "Top up API wallet",
              "Transfer the displayed APT amount from the owner wallet.",
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
            it.copy(orderError = error.message ?: "API-wallet top-up failed")
          }
        }
      mutableUiState.update { it.copy(orderBusy = false) }
    }
  }

  private fun loadCandles(quote: MarketQuote, range: ChartRange) {
    chartJob?.cancel()
    chartJob = viewModelScope.launch {
      mutableUiState.update { it.copy(chartLoading = true, error = null) }
      runSuspendCatching { charts.candles(quote.market.address, range) }
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
        .onFailure { error ->
          mutableUiState.update {
            it.copy(
              chartLoading = false,
              stale = true,
              error = error.message ?: "Chart data is unavailable",
            )
          }
        }
    }
  }
}

private fun TradeUiState.canTrade(expectedSigner: String?, sessionSigner: String?): Boolean =
  expectedSigner != null &&
    expectedSigner.equals(sessionSigner, ignoreCase = true) &&
    !marketDetails.stale &&
    (orderType != OrderType.MARKET ||
      (marketDetails.orderBook?.bestBid != null && marketDetails.orderBook.bestAsk != null))

private fun decimalCharacters(value: String): String =
  value
    .filter { it.isDigit() || it == '.' }
    .let { filtered ->
      val firstDot = filtered.indexOf('.')
      if (firstDot < 0) filtered
      else filtered.take(firstDot + 1) + filtered.drop(firstDot + 1).replace(".", "")
    }

private fun OrderValidationError.message(): String =
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

private fun suggestedApiTopUp(estimate: ULong): ULong {
  val buffered = if (estimate <= ULong.MAX_VALUE / 3uL) estimate * 3uL else estimate
  return maxOf(1_000_000uL, buffered)
}
