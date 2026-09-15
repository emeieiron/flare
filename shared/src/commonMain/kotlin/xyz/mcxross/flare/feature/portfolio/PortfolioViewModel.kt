package xyz.mcxross.flare.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.MarketCatalog
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.data.apiWalletTopUpFor
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.OrderDraft
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.SlippageBps
import xyz.mcxross.flare.decibel.model.absoluteSize
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.decibel.model.toChainUnits
import xyz.mcxross.flare.decibel.model.validate
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.WithdrawalContinuation

enum class FundingMode {
  DEPOSIT,
  WITHDRAW,
}

enum class PortfolioTab(val title: String) {
  POSITIONS("Positions"),
  HOLDINGS("Holdings"),
}

data class SpotHolding(
  val symbol: String,
  val name: String,
  val marketAddress: String? = null,
  val quantity: Double,
  val markPrice: Double,
  val valueUsd: Double,
  val isCollateral: Boolean = false,
  val badge: String? = null,
)

data class PortfolioUiState(
  val profile: WalletProfile = WalletProfile(),
  val marketSymbols: Map<String, String> = emptyMap(),
  val markPrices: Map<String, Double> = emptyMap(),
  val account: AccountSnapshot = AccountSnapshot(),
  val pendingTransactions: List<PendingTransaction> = emptyList(),
  val selectedTab: PortfolioTab = PortfolioTab.POSITIONS,
  val spotHoldings: List<SpotHolding> = emptyList(),
  val fundingMode: FundingMode? = null,
  val fundingAmount: String = "",
  val withdrawalDestination: String = "",
  val pendingWithdrawal: WithdrawalContinuation? = null,
  val fundingTransaction: TransactionState? = null,
  val managedPositionMarket: String? = null,
  val takeProfitInput: String = "",
  val stopLossInput: String = "",
  val positionTransaction: TransactionState? = null,
  val apiWalletNeedsTopUp: Boolean = false,
  val suggestedTopUpOctas: ULong? = null,
  val topUpTransaction: TransactionState? = null,
  val busy: Boolean = false,
  val actionError: String? = null,
) {
  val isLive: Boolean
    get() = account.overview != null && !account.stale

  val totalBalance: Double
    get() {
      val perpEquity = account.overview?.equityBalance ?: 0.0
      val spotAssetsValue = spotHoldings.filterNot { it.isCollateral }.sumOf { it.valueUsd }
      return perpEquity + spotAssetsValue
    }

  val totalSpotValue: Double
    get() = spotHoldings.filterNot { it.isCollateral }.sumOf { it.valueUsd }

  val collateralBalance: Double
    get() = account.overview?.crossWithdrawableBalance ?: account.overview?.availableToTrade ?: 0.0
}

sealed interface PortfolioIntent {
  data class SelectTab(val tab: PortfolioTab) : PortfolioIntent

  data object Refresh : PortfolioIntent

  data class OpenFunding(val mode: FundingMode) : PortfolioIntent

  data class ChangeWithdrawalDestination(val value: String) : PortfolioIntent

  data class ChangeFundingAmount(val value: String) : PortfolioIntent

  data object SubmitFunding : PortfolioIntent

  data object ConfirmSelfPay : PortfolioIntent

  data object CloseFunding : PortfolioIntent

  data class ManagePosition(val market: String) : PortfolioIntent

  data object DismissPositionManagement : PortfolioIntent

  data object ClosePosition : PortfolioIntent

  data class ChangeTakeProfit(val value: String) : PortfolioIntent

  data class ChangeStopLoss(val value: String) : PortfolioIntent

  data object SubmitTpSl : PortfolioIntent

  data object ConfirmPositionSelfPay : PortfolioIntent

  data object TopUpApiWallet : PortfolioIntent
}

class PortfolioViewModel(
  private val accounts: AccountRepository,
  private val wallets: WalletRepository,
  private val preferences: AppPreferences,
  private val trading: TradingRepository,
  private val markets: MarketsRepository,
  private val marketDetails: MarketDetailsRepository,
  private val assetCatalog: AssetCatalogRepository? = null,
) : ViewModel() {
  private val local = MutableStateFlow(PortfolioUiState())
  private var lastPositionCommand: DecibelCommand? = null

  init {
    viewModelScope.launch {
      runSuspendCatching { accounts.refresh() }
    }
    viewModelScope.launch {
      combine(accounts.snapshot, markets.catalog) { snapshot, catalog ->
        snapshot to catalog
      }.collect { (snapshot, catalog) ->
        syncSpotHoldings(snapshot, catalog)
      }
    }
  }

  val uiState: StateFlow<PortfolioUiState> =
    combine(local, wallets.profile, accounts.snapshot, trading.pendingTransactions) {
        state,
        profile,
        account,
        pending ->
        state.copy(profile = profile, account = account, pendingTransactions = pending)
      }
      .combine(preferences.values) { state, saved ->
        state.copy(
          pendingWithdrawal =
            saved.profiles.firstOrNull { it.id == saved.activeProfileId }?.withdrawal
        )
      }
      .combine(markets.catalog) { state, catalog ->
        val nonSpotPositions =
          state.account.positions.filter { pos ->
            catalog.quotes.none { it.market.address == pos.market && it.market.assetType == AssetType.SPOT }
          }
        val updatedHoldings =
          state.spotHoldings.map { holding ->
            if (holding.isCollateral || holding.marketAddress == null) {
              holding
            } else {
              val mark =
                catalog.quotes.firstOrNull { it.market.address == holding.marketAddress }?.markPrice
                  ?: holding.markPrice
              holding.copy(markPrice = mark, valueUsd = holding.quantity * mark)
            }
          }
        state.copy(
          account = state.account.copy(positions = nonSpotPositions),
          spotHoldings = updatedHoldings,
          marketSymbols = catalog.quotes.associate { it.market.address to it.market.symbol },
          markPrices = catalog.quotes.associate { it.market.address to it.markPrice },
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState())

  fun onIntent(intent: PortfolioIntent) {
    when (intent) {
      is PortfolioIntent.SelectTab ->
        local.update { it.copy(selectedTab = intent.tab) }
      PortfolioIntent.Refresh ->
        viewModelScope.launch {
          runSuspendCatching {
            accounts.refresh()
            markets.refresh()
          }
        }
      is PortfolioIntent.ChangeWithdrawalDestination ->
        local.update { it.copy(withdrawalDestination = intent.value, fundingTransaction = null) }
      is PortfolioIntent.OpenFunding -> {
        val pending = uiState.value.pendingWithdrawal.takeIf { intent.mode == FundingMode.WITHDRAW }
        local.update {
          it.copy(
            fundingMode = intent.mode,
            fundingAmount = pending?.amount.orEmpty(),
            withdrawalDestination = pending?.destination.orEmpty(),
            fundingTransaction = null,
            actionError = null,
          )
        }
        viewModelScope.launch { runSuspendCatching { accounts.restoreTrading() } }
      }
      is PortfolioIntent.ChangeFundingAmount ->
        local.update {
          it.copy(
            fundingAmount =
              intent.value.filter { character -> character.isDigit() || character == '.' },
            fundingTransaction = null,
            actionError = null,
          )
        }
      PortfolioIntent.SubmitFunding -> submitFunding(FeePayment.SPONSORED)
      PortfolioIntent.ConfirmSelfPay -> submitFunding(FeePayment.SELF_PAY)
      PortfolioIntent.CloseFunding ->
        local.update {
          it.copy(
            fundingMode = null,
            fundingAmount = "",
            fundingTransaction = null,
            actionError = null,
          )
        }
      is PortfolioIntent.ManagePosition ->
        local.update {
          it.copy(
            managedPositionMarket = intent.market,
            takeProfitInput = "",
            stopLossInput = "",
            positionTransaction = null,
            actionError = null,
          )
        }
      PortfolioIntent.DismissPositionManagement ->
        local.update {
          it.copy(
            managedPositionMarket = null,
            takeProfitInput = "",
            stopLossInput = "",
            positionTransaction = null,
            actionError = null,
          )
        }
      PortfolioIntent.ClosePosition -> closePosition(FeePayment.SPONSORED)
      is PortfolioIntent.ChangeTakeProfit ->
        local.update {
          it.copy(takeProfitInput = decimalCharacters(intent.value), positionTransaction = null)
        }
      is PortfolioIntent.ChangeStopLoss ->
        local.update {
          it.copy(stopLossInput = decimalCharacters(intent.value), positionTransaction = null)
        }
      PortfolioIntent.SubmitTpSl -> setTpSl(FeePayment.SPONSORED)
      PortfolioIntent.ConfirmPositionSelfPay ->
        lastPositionCommand?.let { executePositionCommand(it, FeePayment.SELF_PAY) }
      PortfolioIntent.TopUpApiWallet -> topUpApiWallet()
    }
  }

  private fun closePosition(feePayment: FeePayment) = launchAction("Your position stayed open.") {
    val position = managedPosition()
    val quote = marketQuote(position.market)
    marketDetails.refresh(position.market)
    val details = marketDetails.details.value
    require(!details.stale && details.orderBook != null) {
      "A live order book is required to close a position"
    }
    val slippage = preferences.values.first().slippageBps
    val validated =
      OrderDraft(
          marketAddress = position.market,
          side = if (position.isLong) OrderSide.SELL else OrderSide.BUY,
          type = OrderType.MARKET,
          size = DecimalInput(position.absoluteSize),
          reduceOnly = true,
          slippage = SlippageBps(slippage.toUInt()),
        )
        .validate(quote.market, details.orderBook)
        .value ?: error("The exact position size cannot be aligned to the current market precision")
    val subaccount = checkNotNull(uiState.value.account.account)
    executePositionCommandInternal(DecibelCommand.PlaceOrder(subaccount, validated), feePayment)
  }

  private fun setTpSl(feePayment: FeePayment) = launchAction("Your exits weren’t changed.") {
    val position = managedPosition()
    val quote = marketQuote(position.market)
    val state = local.value
    val takeProfit = optionalPrice(state.takeProfitInput, quote.market.precision.priceDecimals)
    val stopLoss = optionalPrice(state.stopLossInput, quote.market.precision.priceDecimals)
    require(takeProfit != null || stopLoss != null) { "Enter a take-profit or stop-loss price" }
    listOfNotNull(takeProfit, stopLoss).forEach { units ->
      require(
        quote.market.precision.tickSize == 0uL || units % quote.market.precision.tickSize == 0uL
      ) {
        "TP/SL prices must align to tick size ${quote.market.precision.tickSize}"
      }
    }
    val subaccount = checkNotNull(uiState.value.account.account)
    executePositionCommandInternal(
      DecibelCommand.SetPositionTpSl(
        subaccount = subaccount,
        market = position.market,
        takeProfitTrigger = takeProfit,
        takeProfitLimit = takeProfit,
        stopLossTrigger = stopLoss,
        stopLossLimit = stopLoss,
      ),
      feePayment,
    )
  }

  private fun executePositionCommand(command: DecibelCommand, feePayment: FeePayment) =
    launchAction("Your position wasn’t updated.") {
      executePositionCommandInternal(command, feePayment)
    }

  private suspend fun executePositionCommandInternal(
    command: DecibelCommand,
    feePayment: FeePayment,
  ) {
    accounts.refresh()
    lastPositionCommand = command
    local.update { it.copy(apiWalletNeedsTopUp = false, suggestedTopUpOctas = null) }
    trading
      .execute(
        command = command,
        prompt = VaultPrompt("Manage position", "Confirm your identity"),
        feePayment = feePayment,
      )
      .collect { transaction -> local.update { it.copy(positionTransaction = transaction) } }
    when (val terminal = local.value.positionTransaction) {
      is TransactionState.Committed -> accounts.refresh()
      is TransactionState.Failed -> {
        if (terminal.selfPayEstimateOctas == null) error(terminal.message)
        trading.apiWalletTopUpFor(terminal, wallets.profile.first())?.let { topUp ->
          local.update { it.copy(apiWalletNeedsTopUp = true, suggestedTopUpOctas = topUp) }
        }
      }
      else -> Unit
    }
  }

  private fun topUpApiWallet() = launchAction("The network fee wasn’t covered.") {
    val amount = local.value.suggestedTopUpOctas ?: error("No network-fee top-up is required")
    trading
      .topUpApiWallet(
        amount,
        VaultPrompt("Cover network fees", "Confirm your identity", requireFreshAuthorization = true),
      )
      .collect { transaction -> local.update { it.copy(topUpTransaction = transaction) } }
    when (val terminal = local.value.topUpTransaction) {
      is TransactionState.Committed ->
        local.update { it.copy(apiWalletNeedsTopUp = false, suggestedTopUpOctas = null) }
      is TransactionState.Failed -> error(terminal.message)
      else -> Unit
    }
  }

  private suspend fun marketQuote(market: String) =
    (markets.catalog.value.quotes.firstOrNull { it.market.address == market }
      ?: run {
        markets.refresh()
        markets.catalog.value.quotes.firstOrNull { it.market.address == market }
      }) ?: error("Market metadata is unavailable")

  private fun managedPosition() =
    uiState.value.account.positions.firstOrNull { it.market == local.value.managedPositionMarket }
      ?: error("Select an open position")

  private fun optionalPrice(value: String, decimals: Int): ULong? =
    value.takeIf(String::isNotBlank)?.let {
      DecimalInput(it).toChainUnits("TP/SL price", decimals).getOrThrow()
    }

  private fun submitFunding(feePayment: FeePayment) =
    launchAction(
      if (local.value.fundingMode == FundingMode.WITHDRAW) "Your withdrawal didn’t go through."
      else "Your deposit didn’t go through."
    ) {
      submitFundingInternal(feePayment)
    }

  private suspend fun submitFundingInternal(feePayment: FeePayment) {
    val state = local.value
    val mode = state.fundingMode ?: error("Choose deposit or withdrawal")
    val prompt =
      VaultPrompt(
        title = if (mode == FundingMode.DEPOSIT) "Deposit USDC" else "Withdraw USDC",
        subtitle = "Confirm your identity",
      )
    val flow =
      if (mode == FundingMode.DEPOSIT) {
        accounts.depositUsdc(state.fundingAmount, prompt, feePayment)
      } else {
        accounts.withdrawUsdc(state.fundingAmount, state.withdrawalDestination, prompt, feePayment)
      }
    flow.collect { transaction -> local.update { it.copy(fundingTransaction = transaction) } }
    when (val terminal = local.value.fundingTransaction) {
      is TransactionState.Committed -> {
        accounts.refresh()
        local.update { it.copy(fundingAmount = "") }
      }
      is TransactionState.Failed -> {
        if (terminal.selfPayEstimateOctas == null) error(terminal.message)
      }
      else -> Unit
    }
  }

  private suspend fun syncSpotHoldings(snapshot: AccountSnapshot, catalog: MarketCatalog) {
    val subaccount = snapshot.account
    if (subaccount == null) {
      local.update { it.copy(spotHoldings = emptyList()) }
      return
    }
    val holdings = mutableListOf<SpotHolding>()

    // 1. Collateral (USDC Cash)
    val usdcBalance =
      snapshot.overview?.crossWithdrawableBalance
        ?: snapshot.overview?.availableToTrade
        ?: 0.0
    if (usdcBalance > 0.0 || snapshot.overview != null) {
      holdings.add(
        SpotHolding(
          symbol = "USDC",
          name = "USD Coin",
          marketAddress = null,
          quantity = usdcBalance,
          markPrice = 1.0,
          valueUsd = usdcBalance,
          isCollateral = true,
          badge = "CASH",
        )
      )
    }

    // 2. Spot crypto assets from catalog
    val spotQuotes = catalog.quotes.filter { it.market.assetType == AssetType.SPOT }
    for (quote in spotQuotes) {
      val symbol = quote.market.symbol.split("/").firstOrNull()?.trim() ?: quote.market.symbol
      val balance = trading.baseAssetBalance(subaccount, symbol)
      if (balance > 0.0) {
        val metadata = assetCatalog?.assetFor(symbol)
        holdings.add(
          SpotHolding(
            symbol = symbol,
            name = metadata?.name ?: if (symbol.equals("APT", ignoreCase = true)) "Aptos" else symbol,
            marketAddress = quote.market.address,
            quantity = balance,
            markPrice = quote.markPrice,
            valueUsd = balance * quote.markPrice,
            isCollateral = false,
            badge = "SPOT",
          )
        )
      }
    }

    val sortedHoldings =
      holdings.sortedWith(
        compareByDescending<SpotHolding> { it.isCollateral }
          .thenByDescending { it.valueUsd }
      )
    local.update { it.copy(spotHoldings = sortedHoldings) }
  }

  /** [outcome] states what did not happen, so a failure reads as a result instead of a log line. */
  private fun launchAction(outcome: String, block: suspend () -> Unit) {
    if (local.value.busy) return
    viewModelScope.launch {
      local.update { it.copy(busy = true, actionError = null) }
      try {
        runSuspendCatching { block() }
          .onFailure { error ->
            local.update { it.copy(actionError = actionFailure(error.message, outcome)) }
          }
      } finally {
        local.update { it.copy(busy = false) }
      }
    }
  }
}

private fun decimalCharacters(value: String): String =
  value
    .filter { it.isDigit() || it == '.' }
    .let { filtered ->
      val dot = filtered.indexOf('.')
      if (dot < 0) filtered else filtered.take(dot + 1) + filtered.drop(dot + 1).replace(".", "")
    }
