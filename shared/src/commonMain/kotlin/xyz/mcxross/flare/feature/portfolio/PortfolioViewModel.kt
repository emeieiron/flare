package xyz.mcxross.flare.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.data.apiWalletTopUpFor
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AccountVaultPerformance
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.OrderDraft
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.PortfolioChartPoint
import xyz.mcxross.flare.decibel.model.SlippageBps
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.TradingStreak
import xyz.mcxross.flare.decibel.model.VaultInfo
import xyz.mcxross.flare.decibel.model.absoluteSize
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.decibel.model.toChainUnits
import xyz.mcxross.flare.decibel.model.validate
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.WithdrawalContinuation

enum class FundingMode {
  DEPOSIT,
  WITHDRAW,
}

enum class PortfolioChartRange(val label: String, val wireValue: String) {
  DAY_1("1D", "1D"),
  WEEK_1("1W", "1W"),
  MONTH_1("1M", "1M"),
  ALL("ALL", "ALL"),
}

enum class PortfolioMetric(val label: String, val wireValue: String) {
  EQUITY("Net value", "account_value"),
  PNL("Realized P&L", "pnl"),
}

enum class VaultActionMode {
  DEPOSIT,
  REDEEM,
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
  val reservedQuantity: Double = 0.0,
)

data class PortfolioUiState(
  val profile: WalletProfile = WalletProfile(),
  val marketSymbols: Map<String, String> = emptyMap(),
  val markPrices: Map<String, Double> = emptyMap(),
  val account: AccountSnapshot = AccountSnapshot(),
  val pendingTransactions: List<PendingTransaction> = emptyList(),
  val spotHoldings: List<SpotHolding> = emptyList(),
  val fundingMode: FundingMode? = null,
  val fundingAmount: String = "",
  val primaryUsdcBalance: Double = 0.0,
  val withdrawalDestination: String = "",
  val pendingWithdrawal: WithdrawalContinuation? = null,
  val fundingTransaction: TransactionState? = null,
  val managedPositionMarket: String? = null,
  val takeProfitInput: String = "",
  val stopLossInput: String = "",
  val positionTransaction: TransactionState? = null,
  /** The position a close is running for, or waiting on a fee decision for. */
  val closingMarket: String? = null,
  /** Set once a close lands, until the screen that asked for it has reacted. */
  val closedMarket: String? = null,
  val apiWalletNeedsTopUp: Boolean = false,
  val suggestedTopUpOctas: ULong? = null,
  val topUpTransaction: TransactionState? = null,
  val chartPoints: List<PortfolioChartPoint> = emptyList(),
  val chartRange: PortfolioChartRange = PortfolioChartRange.DAY_1,
  val chartMetric: PortfolioMetric = PortfolioMetric.EQUITY,
  val chartLoading: Boolean = false,
  val chartLoaded: Boolean = false,
  val historyVisible: Boolean = false,
  val chartError: String? = null,
  val holdingsError: String? = null,
  val vaultsLoading: Boolean = false,
  val vaultsLoaded: Boolean = false,
  val vaultsError: String? = null,
  val rewardsLoaded: Boolean = false,
  val rewardsError: Boolean = false,
  val streak: TradingStreak? = null,
  val amps: AmpsBreakdown? = null,
  val tier: TierInfo? = null,
  val vaults: List<VaultInfo> = emptyList(),
  val accountVaults: List<AccountVaultPerformance> = emptyList(),
  val selectedVault: VaultInfo? = null,
  val vaultAction: VaultActionMode? = null,
  val vaultAmountInput: String = "",
  val vaultTransaction: TransactionState? = null,
  val busy: Boolean = false,
  val actionError: String? = null,
) {
  val isLive: Boolean
    get() = account.overview != null && !account.stale

  val totalBalance: Double
    get() {
      val perpEquity = account.overview?.equityBalance ?: 0.0
      val spotAssetsValue = totalSpotValue
      val vaultAssetsValue = account.overview?.freeVaultEquity ?: 0.0
      return perpEquity + spotAssetsValue + vaultAssetsValue
    }

  val totalSpotValue: Double
    get() = account.overview?.spot?.totalUsd
      ?: spotHoldings.filterNot { it.isCollateral }.sumOf { it.valueUsd }

  val balanceIncomplete: Boolean
    get() = account.overview?.spot == null || holdingsError != null ||
      (account.overview?.freeVaultEquity == null && accountVaults.isNotEmpty()) || vaultsError != null

  val collateralBalance: Double
    get() = account.overview?.crossWithdrawableBalance ?: account.overview?.availableToTrade ?: 0.0
}

sealed interface PortfolioIntent {
  data object Refresh : PortfolioIntent

  data class SetHistoryVisible(val visible: Boolean) : PortfolioIntent

  data class SelectChartRange(val range: PortfolioChartRange) : PortfolioIntent

  data class SelectChartMetric(val metric: PortfolioMetric) : PortfolioIntent

  data class OpenFunding(val mode: FundingMode) : PortfolioIntent

  data class ChangeWithdrawalDestination(val value: String) : PortfolioIntent

  data class ChangeFundingAmount(val value: String) : PortfolioIntent

  data object SubmitFunding : PortfolioIntent

  data object ConfirmSelfPay : PortfolioIntent

  data object CloseFunding : PortfolioIntent

  data class ManagePosition(val market: String) : PortfolioIntent

  data object DismissPositionManagement : PortfolioIntent

  /** Sells or buys back the whole position in [market] at the market price. */
  data class ClosePosition(val market: String) : PortfolioIntent

  /** The close confirmation was dismissed without closing. */
  data object CancelClose : PortfolioIntent

  /** The screen reacted to [PortfolioUiState.closedMarket]. */
  data object CloseHandled : PortfolioIntent

  data class ChangeTakeProfit(val value: String) : PortfolioIntent

  data class ChangeStopLoss(val value: String) : PortfolioIntent

  data object SubmitTpSl : PortfolioIntent

  data object ConfirmPositionSelfPay : PortfolioIntent

  data object TopUpApiWallet : PortfolioIntent

  data class OpenVaultAction(val vault: VaultInfo, val mode: VaultActionMode) : PortfolioIntent

  data class ChangeVaultAmount(val value: String) : PortfolioIntent

  data object SubmitVaultAction : PortfolioIntent

  data object DismissVaultAction : PortfolioIntent

  data object RefreshVaults : PortfolioIntent
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
  private val spotBalances = MutableStateFlow<Map<String, Double>>(emptyMap())
  private val primaryUsdcBalance = MutableStateFlow(0.0)
  private var chartJob: Job? = null
  private var vaultsJob: Job? = null
  private var rewardsJob: Job? = null
  private var chartKey: Pair<PortfolioChartRange, PortfolioMetric>? = null
  private val balanceMutex = Mutex()
  private var lastPositionCommand: DecibelCommand? = null

  init {
    viewModelScope.launch {
      runSuspendCatching { accounts.refresh() }
    }
    viewModelScope.launch {
      wallets.profile
        .mapNotNull { it.ownerAddress }
        .distinctUntilChanged()
        .collect { owner ->
          fetchPrimaryUsdcBalance(owner)
        }
    }
    viewModelScope.launch {
      accounts.snapshot
        .mapNotNull { it.account }
        .distinctUntilChanged()
        .collect { subaccount ->
          fetchSpotBalances(subaccount)
          if (local.value.historyVisible) fetchPortfolioChart()
          fetchStreaksAndAmps()
          refreshVaults()
        }
    }
    viewModelScope.launch {
      markets.catalog
        .map { catalog ->
          catalog.quotes
            .filter { it.market.assetType == AssetType.SPOT }
            .map { it.market.address }
            .sorted()
        }
        .distinctUntilChanged()
        .collect { spotMarketAddresses ->
          if (spotMarketAddresses.isNotEmpty()) {
            val subaccount = accounts.snapshot.value.account
            if (subaccount != null) {
              fetchSpotBalances(subaccount)
            }
          }
        }
    }
    viewModelScope.launch {
      var wasPending = false
      trading.pendingTransactions.collect { pending ->
        if (wasPending && pending.isEmpty()) {
          accounts.snapshot.value.account?.let { fetchSpotBalances(it) }
        }
        wasPending = pending.isNotEmpty()
      }
    }
  }

  val uiState: StateFlow<PortfolioUiState> =
    combine(local, wallets.profile, accounts.snapshot, trading.pendingTransactions, primaryUsdcBalance) {
        state,
        profile,
        account,
        pending,
        primaryUsdc ->
        state.copy(
          profile = profile,
          account = account,
          pendingTransactions = pending,
          primaryUsdcBalance = primaryUsdc,
        )
      }
      .combine(preferences.values) { state, saved ->
        state.copy(
          pendingWithdrawal =
            saved.profiles.firstOrNull { it.id == saved.activeProfileId }?.withdrawal
        )
      }
      .combine(spotBalances) { state, balances ->
        state to balances
      }
      .combine(markets.catalog) { (state, balances), catalog ->
        val nonSpotPositions =
          state.account.positions.filter { pos ->
            catalog.quotes.none {
              it.market.address == pos.market && it.market.assetType == AssetType.SPOT
            }
          }

        val holdings = mutableListOf<SpotHolding>()

        // Collateral is an owned balance, not the amount currently available to withdraw.
        state.account.overview?.crossUsdcBalance?.let { cash ->
          holdings.add(SpotHolding("USDC", "Trading collateral", quantity = cash,
            markPrice = 1.0, valueUsd = cash, isCollateral = true, badge = "MARGIN"))
        }
        val spot = state.account.overview?.spot
        if (spot != null) {
          val assets = (spot.positions.map { it.assetAddress } +
            spot.reservations.map { it.assetAddress }).distinct()
          assets.forEach { address ->
            val position = spot.positions.firstOrNull { it.assetAddress == address }
            val reserved = spot.reservations.filter { it.assetAddress == address }
            val reservationMarket = reserved.firstOrNull()?.let { item ->
              catalog.quotes.firstOrNull { it.market.address == item.market }
            }
            val symbol = position?.symbol?.takeIf { it.isNotBlank() }
              ?: if (reserved.firstOrNull()?.isBuy == true) "USDC"
              else reservationMarket?.market?.symbol ?: xyz.mcxross.flare.design.shortAddress(address)
            val quote = catalog.quotes.firstOrNull {
              it.market.assetType == AssetType.SPOT && it.market.symbol.substringBefore("/").equals(symbol, true)
            }
            val reservedQuantity = reserved.sumOf { it.amount }
            val quantity = (position?.amount ?: 0.0) + reservedQuantity
            if (quantity > 0) {
              val value = (position?.valueUsd ?: 0.0) + reserved.sumOf { it.valueUsd }
              holdings.add(SpotHolding(symbol, assetCatalog?.assetFor(symbol)?.name ?: if (symbol == "APT") "Aptos" else symbol,
                marketAddress = quote?.market?.address, quantity = quantity,
                markPrice = if (quantity > 0) value / quantity else 0.0, valueUsd = value,
                badge = "SPOT", reservedQuantity = reservedQuantity))
            }
          }
        } else {
          balances.filterValues { it > 0 }.forEach { (symbol, quantity) ->
            val quote = catalog.quotes.firstOrNull {
              it.market.assetType == AssetType.SPOT && it.market.symbol.substringBefore("/").equals(symbol, true)
            }
            val price = if (symbol == "USDC") 1.0 else quote?.markPrice ?: 0.0
            holdings.add(SpotHolding(symbol, assetCatalog?.assetFor(symbol)?.name ?: if (symbol == "APT") "Aptos" else symbol,
              marketAddress = quote?.market?.address, quantity = quantity,
              markPrice = price, valueUsd = quantity * price, badge = "SPOT"))
          }
        }

        val sortedHoldings =
          holdings.sortedWith(
            compareByDescending<SpotHolding> { it.isCollateral }.thenByDescending { it.valueUsd }
          )

        state.copy(
          account = state.account.copy(positions = nonSpotPositions),
          spotHoldings = sortedHoldings,
          marketSymbols = catalog.quotes.associate { it.market.address to it.market.symbol },
          markPrices = catalog.quotes.associate { it.market.address to it.markPrice },
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState())

  fun onIntent(intent: PortfolioIntent) {
    when (intent) {
      is PortfolioIntent.SetHistoryVisible -> {
        local.update { it.copy(historyVisible = intent.visible) }
        if (intent.visible && chartJob?.isActive != true) fetchPortfolioChart()
        if (!intent.visible) {
          chartJob?.cancel()
          local.update { it.copy(chartLoading = false) }
        }
      }
      is PortfolioIntent.SelectChartRange -> {
        local.update { it.copy(chartRange = intent.range) }
        fetchPortfolioChart()
      }
      is PortfolioIntent.SelectChartMetric -> {
        local.update { it.copy(chartMetric = intent.metric) }
        fetchPortfolioChart()
      }
      PortfolioIntent.Refresh ->
        viewModelScope.launch {
          runSuspendCatching {
            accounts.refresh()
            markets.refresh()
            accounts.snapshot.value.account?.let { fetchSpotBalances(it) }
            uiState.value.profile.ownerAddress?.let { fetchPrimaryUsdcBalance(it) }
            fetchPortfolioChart()
            fetchStreaksAndAmps()
            refreshVaults()
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
        uiState.value.profile.ownerAddress?.let { owner ->
          viewModelScope.launch { fetchPrimaryUsdcBalance(owner) }
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
      is PortfolioIntent.ManagePosition -> {
        val position = accounts.snapshot.value.positions.firstOrNull { it.market == intent.market }
        val tpStr = exitPriceInput(position?.takeProfitTriggerPrice)
        val slStr = exitPriceInput(position?.stopLossTriggerPrice)
        local.update {
          it.copy(
            managedPositionMarket = intent.market,
            takeProfitInput = tpStr,
            stopLossInput = slStr,
            positionTransaction = null,
            actionError = null,
          )
        }
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
      is PortfolioIntent.ClosePosition -> {
        local.update {
          it.copy(closingMarket = intent.market, closedMarket = null, positionTransaction = null)
        }
        closePosition(intent.market, FeePayment.SPONSORED)
      }
      PortfolioIntent.CancelClose ->
        if (!local.value.busy) {
          local.update { it.copy(closingMarket = null, positionTransaction = null, actionError = null) }
        }
      PortfolioIntent.CloseHandled -> local.update { it.copy(closedMarket = null) }
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
      is PortfolioIntent.OpenVaultAction ->
        local.update {
          it.copy(
            selectedVault = intent.vault,
            vaultAction = intent.mode,
            vaultAmountInput = "",
            vaultTransaction = null,
            actionError = null,
          )
        }
      is PortfolioIntent.ChangeVaultAmount ->
        local.update {
          it.copy(
            vaultAmountInput = decimalCharacters(intent.value),
            vaultTransaction = null,
            actionError = null,
          )
        }
      PortfolioIntent.SubmitVaultAction -> submitVaultAction()
      PortfolioIntent.DismissVaultAction ->
        local.update {
          it.copy(
            selectedVault = null,
            vaultAction = null,
            vaultAmountInput = "",
            vaultTransaction = null,
            actionError = null,
          )
        }
      PortfolioIntent.RefreshVaults -> refreshVaults()
    }
  }

  private fun closePosition(market: String, feePayment: FeePayment) =
    launchAction("Your position stayed open.") {
      val position =
        uiState.value.account.positions.firstOrNull { it.market == market }
          ?: error("This position is no longer open")
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
          .value
          ?: error("The exact position size cannot be aligned to the current market precision")
      val subaccount = checkNotNull(uiState.value.account.account)
      executePositionCommandInternal(DecibelCommand.PlaceOrder(subaccount, validated), feePayment)
    }

  private fun setTpSl(feePayment: FeePayment) =
    launchAction("Your exits weren’t changed.") {
      local.update { it.copy(closingMarket = null) }
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
      is TransactionState.Committed -> {
        local.value.closingMarket?.let { market ->
          if (command is DecibelCommand.PlaceOrder) {
            local.update { it.copy(closingMarket = null, closedMarket = market) }
          }
        }
        accounts.refresh()
      }
      is TransactionState.Failed -> {
        if (terminal.selfPayEstimateOctas == null) error(terminal.message)
        trading.apiWalletTopUpFor(terminal, wallets.profile.first())?.let { topUp ->
          local.update { it.copy(apiWalletNeedsTopUp = true, suggestedTopUpOctas = topUp) }
        }
      }
      else -> Unit
    }
  }

  private fun topUpApiWallet() =
    launchAction("The network fee wasn’t covered.") {
      val amount = local.value.suggestedTopUpOctas ?: error("No network-fee top-up is required")
      trading
        .topUpApiWallet(
          amount,
          VaultPrompt(
            "Cover network fees",
            "Confirm your identity",
            requireFreshAuthorization = true,
          ),
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
    val amount = state.fundingAmount.toDoubleOrNull()
    require(amount != null && amount > 0.0) { "Enter an amount to transfer" }
    val available =
      if (mode == FundingMode.DEPOSIT) uiState.value.primaryUsdcBalance
      else uiState.value.account.overview?.crossWithdrawableBalance ?: 0.0
    require(amount <= available + 1e-9) {
      "You can transfer up to ${formatQuantity(available, 2)} USDC."
    }
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
        uiState.value.profile.ownerAddress?.let { fetchPrimaryUsdcBalance(it) }
        local.update { it.copy(fundingAmount = "") }
      }
      is TransactionState.Failed -> {
        if (terminal.selfPayEstimateOctas == null) error(terminal.message)
      }
      else -> Unit
    }
  }

  private suspend fun fetchPrimaryUsdcBalance(ownerAddress: String) {
    val balance = runSuspendCatching {
      trading.baseAssetBalance(ownerAddress, "USDC")
    }.getOrDefault(0.0)
    primaryUsdcBalance.value = balance
  }

  private suspend fun fetchSpotBalances(subaccount: String) = balanceMutex.withLock {
    if (accounts.snapshot.value.overview?.spot != null) {
      local.update { it.copy(holdingsError = null) }
      return@withLock
    }
    val symbols = markets.catalog.value.quotes
      .filter { it.market.assetType == AssetType.SPOT }
      .map { it.market.symbol }.distinct() + "USDC"
    val result = runSuspendCatching {
      symbols.associateWith { trading.baseAssetBalance(subaccount, it) }
    }
    if (accounts.snapshot.value.account != subaccount) return@withLock
    result.onSuccess { values ->
      spotBalances.value = values.filterValues { it > 0.0 }
      local.update { it.copy(holdingsError = null) }
    }.onFailure {
      local.update { it.copy(holdingsError = "Some holdings couldn’t be updated.") }
    }
  }

  /** Retry only reads, and only while the route is visible. Mutations never enter this loop. */
  suspend fun refreshWhileVisible() {
    var waitMs = 2_000L
    while (currentCoroutineContext().isActive) {
      delay(waitMs)
      val snapshot = accounts.snapshot.value
      val failed = snapshot.stale || snapshot.error != null || local.value.vaultsError != null ||
        local.value.rewardsError || local.value.holdingsError != null ||
        (local.value.historyVisible && local.value.chartError != null)
      if (snapshot.account != null && (snapshot.stale || snapshot.error != null) && !snapshot.loading) {
        runSuspendCatching { accounts.restoreTrading() }
      }
      if (local.value.historyVisible && local.value.chartError != null && chartJob?.isActive != true) {
        fetchPortfolioChart()
      }
      if (local.value.vaultsError != null && vaultsJob?.isActive != true) refreshVaults()
      if (local.value.rewardsError && rewardsJob?.isActive != true) fetchStreaksAndAmps()
      if (local.value.holdingsError != null) snapshot.account?.let { fetchSpotBalances(it) }
      waitMs = if (failed) (waitMs * 2).coerceAtMost(30_000L) else 15_000L
    }
  }

  private fun fetchPortfolioChart() {
    chartJob?.cancel()
    val range = local.value.chartRange
    val metric = local.value.chartMetric
    val sameSelection = chartKey == (range to metric)
    chartKey = range to metric
    chartJob = viewModelScope.launch {
      local.update { it.copy(chartLoading = true,
        chartPoints = if (sameSelection) it.chartPoints else emptyList(),
        chartLoaded = sameSelection && it.chartLoaded,
        chartError = if (sameSelection) it.chartError else null) }
      runSuspendCatching { accounts.portfolioChart(range.wireValue, metric.wireValue) }
        .onSuccess { points ->
          local.update { it.copy(chartPoints = points, chartLoading = false, chartLoaded = true, chartError = null) }
        }.onFailure {
          local.update { it.copy(chartLoading = false, chartError = "History is temporarily unavailable. Reconnecting automatically.") }
        }
    }
  }

  private fun fetchStreaksAndAmps() {
    if (rewardsJob?.isActive == true) return
    rewardsJob = viewModelScope.launch {
      val streak = runSuspendCatching { accounts.tradingStreak() }
      val amps = runSuspendCatching { accounts.ampsBreakdown() }
      val tier = runSuspendCatching { accounts.tierInfo() }
      local.update {
        it.copy(
          streak = if (streak.isSuccess) streak.getOrNull() else it.streak,
          amps = if (amps.isSuccess) amps.getOrNull() else it.amps,
          tier = if (tier.isSuccess) tier.getOrNull() else it.tier,
          rewardsLoaded = true,
          rewardsError = streak.isFailure || amps.isFailure || tier.isFailure,
        )
      }
    }
  }

  /** [outcome] states what did not happen, so a failure reads as a result instead of a log line. */
  private fun launchAction(outcome: String, block: suspend () -> Unit) {
    if (local.value.busy) return
    viewModelScope.launch {
      local.update { it.copy(busy = true, actionError = null) }
      try {
        runSuspendCatching { block() }
          .onFailure { error ->
            if (!error.isAuthorizationCancelled())
              local.update { it.copy(actionError = actionFailure(error.message, outcome)) }
          }
      } finally {
        local.update { it.copy(busy = false) }
      }
    }
  }

  fun refreshVaults() {
    if (vaultsJob?.isActive == true) return
    vaultsJob = viewModelScope.launch {
      local.update { it.copy(vaultsLoading = true) }
      runSuspendCatching {
        val vaults = accounts.vaults()
        val positions = accounts.accountVaultPerformance()
        local.update { it.copy(vaults = vaults, accountVaults = positions, vaultsLoaded = true, vaultsError = null) }
      }.onFailure {
        local.update { it.copy(vaultsError = "Vaults are temporarily unavailable. Reconnecting automatically.") }
      }
      local.update { it.copy(vaultsLoading = false) }
    }
  }

  private fun submitVaultAction() {
    val state = local.value
    val vault = state.selectedVault ?: return
    val mode = state.vaultAction ?: return
    val amount = state.vaultAmountInput.trim()
    val parsedAmount = amount.toDoubleOrNull() ?: 0.0
    if (amount.isBlank() || parsedAmount <= 0.0) {
      local.update { it.copy(actionError = "Enter a valid positive amount") }
      return
    }
    if (mode == VaultActionMode.DEPOSIT && parsedAmount < 10.0) {
      local.update { it.copy(actionError = "The minimum deposit for DLP vaults is 10 USDC.") }
      return
    }
    if (mode == VaultActionMode.REDEEM) {
      val position = state.accountVaults.firstOrNull { it.vault.address == vault.address }
      val maxShares = position?.currentNumShares ?: 0.0
      if (parsedAmount < 5.0) {
        local.update { it.copy(actionError = "The minimum redemption for DLP vaults is 5 shares.") }
        return
      }
      if (maxShares > 0.0 && parsedAmount > maxShares) {
        local.update {
          it.copy(
            actionError =
              "You cannot redeem more than your balance of ${formatQuantity(maxShares, 4)} shares."
          )
        }
        return
      }
    }
    launchAction(if (mode == VaultActionMode.DEPOSIT) "Vault deposit failed." else "Vault redemption failed.") {
      val prompt =
        VaultPrompt(
          title = if (mode == VaultActionMode.DEPOSIT) "Contribute to ${vault.name}" else "Redeem from ${vault.name}",
          subtitle = "Confirm with owner wallet",
        )
      val flow =
        if (mode == VaultActionMode.DEPOSIT) {
          accounts.contributeToVault(vault.address, amount, prompt)
        } else {
          accounts.redeemFromVault(vault.address, amount, prompt)
        }
      flow.collect { transaction ->
        local.update { it.copy(vaultTransaction = transaction) }
      }
      when (val terminal = local.value.vaultTransaction) {
        is TransactionState.Committed -> {
          refreshVaults()
          accounts.refresh()
          // Keep the committed action visible until the receipt is dismissed.
        }
        is TransactionState.Failed -> error(terminal.message)
        else -> Unit
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

/** A saved exit price as the plain number the exit fields edit. */
internal fun exitPriceInput(price: Double?): String =
  price?.let { formatPrice(it).replace(",", "").replace("$", "").trim() }.orEmpty()
