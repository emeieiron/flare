package xyz.mcxross.flare.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
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
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
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
import xyz.mcxross.flare.decibel.model.AccountVaultPerformance
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

enum class PortfolioChartRange(val label: String, val wireValue: String) {
  DAY_1("1D", "1D"),
  WEEK_1("1W", "1W"),
  MONTH_1("1M", "1M"),
  ALL("ALL", "ALL"),
}

enum class PortfolioMetric(val label: String, val wireValue: String) {
  EQUITY("Equity", "account_value"),
  PNL("Realized PnL", "pnl"),
}

enum class VaultActionMode {
  DEPOSIT,
  REDEEM,
}

enum class PortfolioTab(val title: String) {
  POSITIONS("Positions"),
  HOLDINGS("Holdings"),
  VAULTS("Vaults"),
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
  val chartPoints: List<PortfolioChartPoint> = emptyList(),
  val chartRange: PortfolioChartRange = PortfolioChartRange.DAY_1,
  val chartMetric: PortfolioMetric = PortfolioMetric.EQUITY,
  val chartLoading: Boolean = false,
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
      val spotAssetsValue = spotHoldings.filterNot { it.isCollateral }.sumOf { it.valueUsd }
      val vaultAssetsValue = accountVaults.sumOf { it.currentValue }
      return perpEquity + spotAssetsValue + vaultAssetsValue
    }

  val totalSpotValue: Double
    get() = spotHoldings.filterNot { it.isCollateral }.sumOf { it.valueUsd }

  val collateralBalance: Double
    get() = account.overview?.crossWithdrawableBalance ?: account.overview?.availableToTrade ?: 0.0
}

sealed interface PortfolioIntent {
  data class SelectTab(val tab: PortfolioTab) : PortfolioIntent

  data object Refresh : PortfolioIntent

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

  data object ClosePosition : PortfolioIntent

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
  private var lastPositionCommand: DecibelCommand? = null

  init {
    viewModelScope.launch {
      runSuspendCatching { accounts.refresh() }
    }
    viewModelScope.launch {
      accounts.snapshot
        .mapNotNull { it.account }
        .distinctUntilChanged()
        .collect { subaccount ->
          fetchSpotBalances(subaccount)
          fetchPortfolioChart()
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

        // 1. Collateral (USDC Cash)
        val usdcBalance =
          state.account.overview?.crossWithdrawableBalance
            ?: state.account.overview?.availableToTrade
            ?: 0.0
        if (usdcBalance > 0.0 || state.account.overview != null) {
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

        // 2. Spot crypto assets
        for ((symbol, quantity) in balances) {
          if (quantity > 0.0) {
            val spotQuote =
              catalog.quotes.firstOrNull { quote ->
                quote.market.assetType == AssetType.SPOT &&
                  (quote.market.symbol
                    .split("/")
                    .firstOrNull()
                    ?.trim()
                    ?.equals(symbol, ignoreCase = true) == true ||
                    quote.market.symbol.equals(symbol, ignoreCase = true))
              }
            val mark = spotQuote?.markPrice ?: 0.0
            val metadata = assetCatalog?.assetFor(symbol)
            holdings.add(
              SpotHolding(
                symbol = symbol,
                name =
                  metadata?.name
                    ?: if (symbol.equals("APT", ignoreCase = true)) "Aptos" else symbol,
                marketAddress = spotQuote?.market?.address,
                quantity = quantity,
                markPrice = mark,
                valueUsd = quantity * mark,
                isCollateral = false,
                badge = "SPOT",
              )
            )
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
      is PortfolioIntent.SelectTab -> {
        local.update { it.copy(selectedTab = intent.tab) }
        if (intent.tab == PortfolioTab.VAULTS) {
          refreshVaults()
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
        val tpStr = position?.takeProfitTriggerPrice?.let { p ->
          formatPrice(p).replace(",", "").replace("$", "").trim()
        }.orEmpty()
        val slStr = position?.stopLossTriggerPrice?.let { p ->
          formatPrice(p).replace(",", "").replace("$", "").trim()
        }.orEmpty()
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

  private fun closePosition(feePayment: FeePayment) =
    launchAction("Your position stayed open.") {
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
          .value
          ?: error("The exact position size cannot be aligned to the current market precision")
      val subaccount = checkNotNull(uiState.value.account.account)
      executePositionCommandInternal(DecibelCommand.PlaceOrder(subaccount, validated), feePayment)
    }

  private fun setTpSl(feePayment: FeePayment) =
    launchAction("Your exits weren’t changed.") {
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

  private suspend fun fetchSpotBalances(subaccount: String) {
    val spotSymbols =
      markets.catalog.value.quotes
        .filter { it.market.assetType == AssetType.SPOT }
        .mapNotNull { it.market.symbol.split("/").firstOrNull()?.trim() }
        .distinct()
        .ifEmpty { listOf("APT") }

    val updated = spotBalances.value.toMutableMap()
    for (symbol in spotSymbols) {
      val balance = trading.baseAssetBalance(subaccount, symbol)
      updated[symbol] = balance
    }
    spotBalances.value = updated.filterValues { it > 0.0 }
  }

  private fun fetchPortfolioChart() {
    viewModelScope.launch {
      local.update { it.copy(chartLoading = true) }
      val points =
        runSuspendCatching {
          accounts.portfolioChart(
            timeRange = local.value.chartRange.wireValue,
            metric = local.value.chartMetric.wireValue,
          )
        }.getOrDefault(emptyList())
      local.update { it.copy(chartPoints = points, chartLoading = false) }
    }
  }

  private fun fetchStreaksAndAmps() {
    viewModelScope.launch {
      val streak = runSuspendCatching { accounts.tradingStreak() }.getOrNull()
      val amps = runSuspendCatching { accounts.ampsBreakdown() }.getOrNull()
      val tier = runSuspendCatching { accounts.tierInfo() }.getOrNull()
      local.update { it.copy(streak = streak, amps = amps, tier = tier) }
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
            local.update { it.copy(actionError = actionFailure(error.message, outcome)) }
          }
      } finally {
        local.update { it.copy(busy = false) }
      }
    }
  }

  fun refreshVaults() {
    viewModelScope.launch {
      val v = runCatching { accounts.vaults() }.getOrDefault(emptyList())
      val perf = runCatching { accounts.accountVaultPerformance() }.getOrDefault(emptyList())
      local.update { it.copy(vaults = v, accountVaults = perf) }
    }
  }

  private fun submitVaultAction() {
    val state = local.value
    val vault = state.selectedVault ?: return
    val mode = state.vaultAction ?: return
    val amount = state.vaultAmountInput.trim()
    if (amount.isBlank() || (amount.toDoubleOrNull() ?: 0.0) <= 0.0) {
      local.update { it.copy(actionError = "Enter a valid positive amount") }
      return
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
          local.update { it.copy(selectedVault = null, vaultAction = null, vaultAmountInput = "") }
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
