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
import xyz.mcxross.flare.data.AccountSetupGate
import xyz.mcxross.flare.data.SetupNeed
import xyz.mcxross.flare.data.gatedBy
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.RewardsRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.VaultsRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.tradingKeyTopUpFor
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.WithdrawalContinuation
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

enum class FundingMode {
  DEPOSIT,
  WITHDRAW,
  /** USDC from perpetuals to spot, where the venue keeps them apart. */
  TO_SPOT,
  /** USDC from spot to perpetuals. */
  TO_PERPS,
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

/** What the portfolio overview's Holdings card shows. */
sealed interface HoldingsPreview {
  data object Loading : HoldingsPreview

  data object NoneHeld : HoldingsPreview

  /** [valueUsd] is null while it isn't known. */
  data class Holding(val symbol: String, val subtitle: String, val valueUsd: Double?) : HoldingsPreview
}

data class PortfolioUiState(
  /** What the venue supports; screens hide the rest. */
  val capabilities: VenueCapabilities = VenueCapabilities.All,
  /** What the wallet still needs when its setup was left for later; its actions wait meanwhile. */
  val setupNeed: SetupNeed? = null,
  /** What the venue calls its reward points, such as Amps. */
  val rewardPointsName: String? = null,
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
  val fundingTransaction: ActionState? = null,
  val managedPositionMarket: String? = null,
  val takeProfitInput: String = "",
  val stopLossInput: String = "",
  val positionTransaction: ActionState? = null,
  /** The position a close is running for, or waiting on a fee decision for. */
  val closingMarket: String? = null,
  /** Set once a close lands, until the screen that asked for it has reacted. */
  val closedMarket: String? = null,
  val apiWalletNeedsTopUp: Boolean = false,
  val suggestedTopUp: ULong? = null,
  val topUpTransaction: ActionState? = null,
  val chartPoints: List<PortfolioPoint> = emptyList(),
  val chartRange: PortfolioChartRange = PortfolioChartRange.DAY_1,
  val chartMetric: PortfolioMetric = PortfolioMetric.EQUITY,
  val chartLoading: Boolean = false,
  val chartLoaded: Boolean = false,
  val historyVisible: Boolean = false,
  val chartError: String? = null,
  val holdingsError: String? = null,
  /** True where the venue binds a vaults port. */
  val vaultsAvailable: Boolean = true,
  /** True where the venue binds a rewards port. */
  val rewardsAvailable: Boolean = true,
  val vaultsLoading: Boolean = false,
  val vaultsLoaded: Boolean = false,
  val vaultsError: String? = null,
  val rewardsLoaded: Boolean = false,
  val rewardsError: Boolean = false,
  val rewards: Rewards? = null,
  val vaults: List<VaultSummary> = emptyList(),
  val accountVaults: List<VaultPosition> = emptyList(),
  val selectedVault: VaultSummary? = null,
  val vaultAction: VaultActionMode? = null,
  val vaultAmountInput: String = "",
  val vaultTransaction: ActionState? = null,
  val busy: Boolean = false,
  val actionError: String? = null,
) {
  val isLive: Boolean
    get() = account.overview != null && !account.stale

  val totalBalance: Double
    get() {
      val perpEquity = account.overview?.equity ?: 0.0
      val spotAssetsValue = totalSpotValue
      val vaultAssetsValue = account.overview?.vaultEquity ?: 0.0
      // Spot value that also backs perpetuals is already in the perpetuals equity.
      val shared = account.overview?.balances?.sharedWithEquity ?: 0.0
      return perpEquity + spotAssetsValue + vaultAssetsValue - shared
    }

  val totalSpotValue: Double
    get() = account.overview?.balances?.spot?.totalUsd
      ?: spotHoldings.filterNot { it.isCollateral }.sumOf { it.valueUsd }

  val holdingsPreview: HoldingsPreview
    get() {
      val overview = account.overview
      if (overview == null && account.error == null) return HoldingsPreview.Loading
      val usdc = spotHoldings.filter { it.symbol.equals("USDC", ignoreCase = true) }
      val top = spotHoldings.maxByOrNull { it.valueUsd }
      return when {
        usdc.isNotEmpty() -> HoldingsPreview.Holding("USDC", "Spot and trading collateral", usdc.sumOf { it.quantity })
        top != null -> HoldingsPreview.Holding(top.symbol, top.name, top.valueUsd.takeIf { top.markPrice > 0 })
        overview != null && !account.stale && !balanceIncomplete -> HoldingsPreview.NoneHeld
        else -> HoldingsPreview.Holding("USDC", "Spot and trading collateral", null)
      }
    }

  val balanceIncomplete: Boolean
    get() = account.overview?.balances?.spot == null || holdingsError != null ||
      (account.overview?.vaultEquity == null && accountVaults.isNotEmpty()) || vaultsError != null

  val collateralBalance: Double
    get() = account.overview?.withdrawable ?: 0.0

  /** Spot USDC free to move, where the venue reports spot with the account. */
  val spotUsdc: Double
    get() =
      account.overview?.balances?.spot?.assets
        ?.filter { it.symbol.equals("USDC", ignoreCase = true) }
        ?.sumOf { it.amount } ?: 0.0

  /** Whether USDC can move between perpetuals and spot for this account. */
  val canMovePerpSpot: Boolean
    get() = capabilities.perpSpotTransfer && account.overview?.perpSpotSeparate == true

  /** What [mode] can move now. */
  fun fundingAvailable(mode: FundingMode): Double =
    when (mode) {
      FundingMode.DEPOSIT -> primaryUsdcBalance
      FundingMode.WITHDRAW,
      FundingMode.TO_SPOT -> account.overview?.withdrawable ?: 0.0
      FundingMode.TO_PERPS -> spotUsdc
    }

  /**
   * What the wallet would pay for [transaction]'s network fee once the venue's sponsor declined it;
   * null when it wasn't declined, or the venue has no sponsored fees.
   */
  fun selfPayEstimate(transaction: ActionState?): ULong? =
    if (capabilities.sponsoredFees) (transaction as? ActionState.Failed)?.selfPay?.estimate
    else null
}

sealed interface PortfolioIntent {
  data object Refresh : PortfolioIntent

  data class SetHistoryVisible(val visible: Boolean) : PortfolioIntent

  data class SelectChartRange(val range: PortfolioChartRange) : PortfolioIntent

  data class SelectChartMetric(val metric: PortfolioMetric) : PortfolioIntent

  data class OpenFunding(val mode: FundingMode) : PortfolioIntent

  data class ChangeWithdrawalDestination(val value: String) : PortfolioIntent

  /** Switches an open transfer between withdrawing and moving USDC between perps and spot. */
  data class SelectFundingMode(val mode: FundingMode) : PortfolioIntent

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

  data class OpenVaultAction(val vault: VaultSummary, val mode: VaultActionMode) : PortfolioIntent

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
  venue: VenueDescriptor,
  private val vaultsRepository: VaultsRepository? = null,
  private val rewardsRepository: RewardsRepository? = null,
  private val assetCatalog: AssetCatalogRepository? = null,
  private val setupGate: AccountSetupGate? = null,
) : ViewModel() {
  private val capabilities = venue.capabilities
  private val chain = venue.chain
  private val vaultMinimums = venue.vaultMinimums
  private val vaultTerms = venue.vaultTerms
  private val local =
    MutableStateFlow(
      PortfolioUiState(
        capabilities = capabilities,
        rewardPointsName = venue.rewardPointsName,
        vaultsAvailable = vaultsRepository != null,
        rewardsAvailable = rewardsRepository != null,
      )
    )
  private val spotBalances = MutableStateFlow<Map<String, Double>>(emptyMap())
  private val primaryUsdcBalance = MutableStateFlow(0.0)
  private var chartJob: Job? = null
  private var vaultsJob: Job? = null
  private var rewardsJob: Job? = null
  private var chartKey: Pair<PortfolioChartRange, PortfolioMetric>? = null
  private val balanceMutex = Mutex()
  private var lastPositionAction: TradeAction? = null

  init {
    setupGate?.let { gate ->
      viewModelScope.launch {
        gate.need.collect { need ->
          local.update { it.copy(setupNeed = need, capabilities = capabilities.gatedBy(need)) }
        }
      }
    }
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
          fetchRewards()
          refreshVaults()
        }
    }
    viewModelScope.launch {
      markets.catalog
        .map { catalog ->
          catalog.quotes
            .filter { it.market.kind == MarketKind.SPOT }
            .map { it.market.id }
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
              it.market.id == pos.marketId && it.market.kind == MarketKind.SPOT
            }
          }

        val holdings = mutableListOf<SpotHolding>()

        // Collateral is an owned balance, not the amount currently available to withdraw. Like any
        // holding, it's listed only when there is some.
        state.account.overview?.balances?.collateral?.takeIf { it > 0.0 }?.let { cash ->
          holdings.add(SpotHolding("USDC", "Trading collateral", quantity = cash,
            markPrice = 1.0, valueUsd = cash, isCollateral = true, badge = "MARGIN"))
        }
        val spot = state.account.overview?.balances?.spot
        if (spot != null) {
          val assets = (spot.assets.map { it.assetId } + spot.reserved.map { it.assetId }).distinct()
          assets.forEach { address ->
            val position = spot.assets.firstOrNull { it.assetId == address }
            val reserved = spot.reserved.filter { it.assetId == address }
            val reservationMarket = reserved.firstOrNull()?.let { item ->
              catalog.quotes.firstOrNull { it.market.id == item.marketId }
            }
            val symbol = position?.symbol?.takeIf { it.isNotBlank() }
              ?: if (reserved.firstOrNull()?.isBuy == true) "USDC"
              else reservationMarket?.market?.symbol ?: xyz.mcxross.flare.design.shortAddress(address)
            val quote = catalog.quotes.firstOrNull {
              it.market.kind == MarketKind.SPOT && it.market.symbol.substringBefore("/").equals(symbol, true)
            }
            val reservedQuantity = reserved.sumOf { it.amount }
            val quantity = (position?.amount ?: 0.0) + reservedQuantity
            if (quantity > 0) {
              val value = (position?.valueUsd ?: 0.0) + reserved.sumOf { it.valueUsd }
              holdings.add(SpotHolding(symbol, assetCatalog?.assetFor(symbol)?.name ?: if (symbol == chain.feeToken) chain.name else symbol,
                marketAddress = quote?.market?.id, quantity = quantity,
                markPrice = if (quantity > 0) value / quantity else 0.0, valueUsd = value,
                badge = "SPOT", reservedQuantity = reservedQuantity))
            }
          }
        } else {
          balances.filterValues { it > 0 }.forEach { (symbol, quantity) ->
            val quote = catalog.quotes.firstOrNull {
              it.market.kind == MarketKind.SPOT && it.market.symbol.substringBefore("/").equals(symbol, true)
            }
            val price = if (symbol == "USDC") 1.0 else quote?.markPrice ?: 0.0
            holdings.add(SpotHolding(symbol, assetCatalog?.assetFor(symbol)?.name ?: if (symbol == chain.feeToken) chain.name else symbol,
              marketAddress = quote?.market?.id, quantity = quantity,
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
          marketSymbols = catalog.quotes.associate { it.market.id to it.market.symbol },
          // Only the markets with open positions are ever looked up. Keeping the others out means a
          // price tick elsewhere leaves this state equal, so the portfolio doesn't recompose for it.
          markPrices =
            nonSpotPositions.mapTo(HashSet()) { it.marketId }.let { held ->
              catalog.quotes.filter { it.market.id in held }.associate { it.market.id to it.markPrice }
            },
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
            fetchRewards()
            refreshVaults()
          }
        }
      is PortfolioIntent.ChangeWithdrawalDestination ->
        local.update { it.copy(withdrawalDestination = intent.value, fundingTransaction = null) }
      is PortfolioIntent.OpenFunding -> {
        // Without in-app deposits, a transfer can only go out to the wallet.
        val mode =
          if (intent.mode == FundingMode.DEPOSIT && !capabilities.inAppDeposit) FundingMode.WITHDRAW
          else intent.mode
        val pending = uiState.value.pendingWithdrawal.takeIf { mode == FundingMode.WITHDRAW }
        local.update {
          it.copy(
            fundingMode = mode,
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
      is PortfolioIntent.SelectFundingMode ->
        if (!local.value.busy) {
          local.update {
            it.copy(fundingMode = intent.mode, fundingAmount = "", fundingTransaction = null, actionError = null)
          }
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
        val position = accounts.snapshot.value.positions.firstOrNull { it.marketId == intent.market }
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
        lastPositionAction?.let { executePositionAction(it, FeePayment.SELF_PAY) }
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
        uiState.value.account.positions.firstOrNull { it.marketId == market }
          ?: error("This position is no longer open")
      val quote = marketQuote(position.marketId)
      marketDetails.refresh(position.marketId)
      val details = marketDetails.details.value
      require(!details.stale && details.orderBook != null) {
        "A live order book is required to close a position"
      }
      val slippage = preferences.values.first().slippageBps
      val order =
        OrderRequest(
          marketId = position.marketId,
          side = if (position.isLong) OrderSide.SELL else OrderSide.BUY,
          type = OrderType.MARKET,
          size = position.absoluteSize,
          reduceOnly = true,
          slippageBps = slippage,
        )
      val checked =
        (quote.market.rules.check(order, details.orderBook) as? OrderCheck.Valid)?.order
          ?: error("The exact position size cannot be aligned to the current market precision")
      val subaccount = checkNotNull(uiState.value.account.account)
      executePositionActionInternal(TradeAction.PlaceOrder(subaccount, quote.market, checked), feePayment)
    }

  private fun setTpSl(feePayment: FeePayment) =
    launchAction("Your exits weren’t changed.") {
      local.update { it.copy(closingMarket = null) }
      val position = managedPosition()
      val quote = marketQuote(position.marketId)
      val state = local.value
      require(state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank()) {
        "Enter a take-profit or stop-loss price"
      }
      // Only the exits that changed are replaced; the others stay as they are.
      val (takeProfit, stopLoss) = exitChanges(state.takeProfitInput, state.stopLossInput, position)
      require(takeProfit != null || stopLoss != null) { "These exits are already set" }
      quote.market.rules.checkExitPrices(takeProfit, stopLoss)?.let { throw IllegalArgumentException(it) }
      val subaccount = checkNotNull(uiState.value.account.account)
      executePositionActionInternal(
        TradeAction.SetPositionTpSl(
          account = subaccount,
          market = quote.market,
          takeProfitTrigger = takeProfit,
          takeProfitLimit = takeProfit,
          stopLossTrigger = stopLoss,
          stopLossLimit = stopLoss,
        ),
        feePayment,
      )
    }

  private fun executePositionAction(action: TradeAction, feePayment: FeePayment) =
    launchAction("Your position wasn’t updated.") {
      executePositionActionInternal(action, feePayment)
    }

  private suspend fun executePositionActionInternal(
    action: TradeAction,
    feePayment: FeePayment,
  ) {
    accounts.refresh()
    lastPositionAction = action
    local.update { it.copy(apiWalletNeedsTopUp = false, suggestedTopUp = null) }
    trading
      .execute(
        action = action,
        prompt = VaultPrompt("Manage position", "Confirm your identity"),
        feePayment = feePayment,
      )
      .collect { transaction -> local.update { it.copy(positionTransaction = transaction) } }
    when (val terminal = local.value.positionTransaction) {
      is ActionState.Committed -> {
        local.value.closingMarket?.let { market ->
          if (action is TradeAction.PlaceOrder) {
            local.update { it.copy(closingMarket = null, closedMarket = market) }
          }
        }
        accounts.refresh()
      }
      is ActionState.Failed -> {
        if (terminal.selfPay == null) error(terminal.message)
        // Venues without a fee token to send never offer a top-up.
        if (capabilities.networkFeeTopUp) {
          trading.tradingKeyTopUpFor(terminal, wallets.profile.first(), chain.minimumFeeTopUp)?.let { topUp ->
            local.update { it.copy(apiWalletNeedsTopUp = true, suggestedTopUp = topUp) }
          }
        }
      }
      else -> Unit
    }
  }

  private fun topUpApiWallet() =
    launchAction("The network fee wasn’t covered.") {
      val amount = local.value.suggestedTopUp ?: error("No network-fee top-up is required")
      trading
        .topUpTradingKey(
          amount,
          VaultPrompt(
            "Cover network fees",
            "Confirm your identity",
            requireFreshAuthorization = true,
          ),
        )
        .collect { transaction -> local.update { it.copy(topUpTransaction = transaction) } }
      when (val terminal = local.value.topUpTransaction) {
        is ActionState.Committed ->
          local.update { it.copy(apiWalletNeedsTopUp = false, suggestedTopUp = null) }
        is ActionState.Failed -> error(terminal.message)
        else -> Unit
      }
    }

  private suspend fun marketQuote(market: String) =
    (markets.catalog.value.quotes.firstOrNull { it.market.id == market }
      ?: run {
        markets.refresh()
        markets.catalog.value.quotes.firstOrNull { it.market.id == market }
      }) ?: error("Market metadata is unavailable")

  private fun managedPosition() =
    uiState.value.account.positions.firstOrNull { it.marketId == local.value.managedPositionMarket }
      ?: error("Select an open position")

  private fun submitFunding(feePayment: FeePayment) =
    launchAction(
      when (local.value.fundingMode) {
        FundingMode.WITHDRAW -> "Your withdrawal didn’t go through."
        FundingMode.TO_SPOT, FundingMode.TO_PERPS -> "Your USDC wasn’t moved."
        else -> "Your deposit didn’t go through."
      }
    ) {
      submitFundingInternal(feePayment)
    }

  private suspend fun submitFundingInternal(feePayment: FeePayment) {
    val state = local.value
    val mode = state.fundingMode ?: error("Choose deposit or withdrawal")
    val amount = state.fundingAmount.toDoubleOrNull()
    require(amount != null && amount > 0.0) { "Enter an amount to transfer" }
    val available = uiState.value.fundingAvailable(mode)
    require(amount <= available + 1e-9) {
      "You can transfer up to ${formatQuantity(available, 2)} USDC."
    }
    val prompt =
      VaultPrompt(
        title =
          when (mode) {
            FundingMode.DEPOSIT -> "Deposit USDC"
            FundingMode.WITHDRAW -> "Withdraw USDC"
            FundingMode.TO_SPOT, FundingMode.TO_PERPS -> "Move USDC"
          },
        subtitle = "Confirm your identity",
      )
    val flow =
      when (mode) {
        FundingMode.DEPOSIT -> accounts.depositUsdc(state.fundingAmount, prompt, feePayment)
        FundingMode.WITHDRAW ->
          accounts.withdrawUsdc(state.fundingAmount, state.withdrawalDestination, prompt, feePayment)
        FundingMode.TO_SPOT, FundingMode.TO_PERPS -> {
          val account = checkNotNull(uiState.value.account.account) { "Select a trading account" }
          trading.execute(
            TradeAction.MovePerpSpot(account, state.fundingAmount, toSpot = mode == FundingMode.TO_SPOT),
            prompt,
            feePayment,
          )
        }
      }
    flow.collect { transaction -> local.update { it.copy(fundingTransaction = transaction) } }
    when (val terminal = local.value.fundingTransaction) {
      is ActionState.Committed -> {
        accounts.refresh()
        uiState.value.profile.ownerAddress?.let { fetchPrimaryUsdcBalance(it) }
        local.update { it.copy(fundingAmount = "") }
      }
      is ActionState.Failed -> {
        if (terminal.selfPay?.estimate == null) error(terminal.message)
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
    // Spot holdings come with the account where the venue reports them, and not at all without spot.
    if (accounts.snapshot.value.overview?.balances?.spot != null || !capabilities.spot) {
      local.update { it.copy(holdingsError = null) }
      return@withLock
    }
    val symbols = markets.catalog.value.quotes
      .filter { it.market.kind == MarketKind.SPOT }
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
      if (local.value.rewardsError && rewardsJob?.isActive != true) fetchRewards()
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
      runSuspendCatching { accounts.portfolioChart(range, metric) }
        .onSuccess { points ->
          local.update { it.copy(chartPoints = points, chartLoading = false, chartLoaded = true, chartError = null) }
        }.onFailure {
          local.update { it.copy(chartLoading = false, chartError = "History is temporarily unavailable. Reconnecting automatically.") }
        }
    }
  }

  /** A part that fails to load keeps what was shown for it, and is retried while visible. */
  private fun fetchRewards() {
    val repository = rewardsRepository ?: return
    if (rewardsJob?.isActive == true) return
    rewardsJob = viewModelScope.launch {
      val result = runSuspendCatching { repository.rewards() }
      local.update {
        val rewards = result.getOrNull()
        it.copy(
          rewards =
            if (result.isFailure) it.rewards else rewards?.keepingUnavailableFrom(it.rewards),
          rewardsLoaded = true,
          rewardsError = result.isFailure || rewards?.unavailable?.isNotEmpty() == true,
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
    val repository = vaultsRepository ?: return
    if (vaultsJob?.isActive == true) return
    vaultsJob = viewModelScope.launch {
      local.update { it.copy(vaultsLoading = true) }
      runSuspendCatching {
        val vaults = repository.vaults()
        val positions = repository.vaultPositions()
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
    val minimums = vaultMinimums
    if (mode == VaultActionMode.DEPOSIT && minimums != null && parsedAmount < minimums.deposit) {
      local.update {
        it.copy(
          actionError =
            "The minimum deposit for ${minimums.vaults} is ${formatQuantity(minimums.deposit, 2)} USDC."
        )
      }
      return
    }
    if (mode == VaultActionMode.REDEEM) {
      val position = state.accountVaults.firstOrNull { it.vault.address == vault.address }
      val maxShares = position?.shares ?: 0.0
      if (minimums != null && parsedAmount < minimums.redemptionShares) {
        local.update {
          it.copy(
            actionError =
              "The minimum redemption for ${minimums.vaults} is " +
                "${formatQuantity(minimums.redemptionShares, 4)} shares."
          )
        }
        return
      }
      if (maxShares > 0.0 && parsedAmount > maxShares) {
        local.update {
          it.copy(
            actionError =
              if (vaultTerms.stakeInUsdc) "You can redeem up to ${formatBalance(maxShares)}."
              else "You cannot redeem more than your balance of ${formatQuantity(maxShares, 4)} shares."
          )
        }
        return
      }
    }
    val repository = vaultsRepository ?: return
    launchAction(if (mode == VaultActionMode.DEPOSIT) "Vault deposit failed." else "Vault redemption failed.") {
      val prompt =
        VaultPrompt(
          title = if (mode == VaultActionMode.DEPOSIT) "Contribute to ${vault.name}" else "Redeem from ${vault.name}",
          subtitle = "Confirm with owner wallet",
        )
      val flow =
        if (mode == VaultActionMode.DEPOSIT) {
          repository.contribute(vault.address, amount, prompt)
        } else {
          repository.redeem(vault.address, amount, prompt)
        }
      flow.collect { transaction ->
        local.update { it.copy(vaultTransaction = transaction) }
      }
      when (val terminal = local.value.vaultTransaction) {
        is ActionState.Committed -> {
          refreshVaults()
          accounts.refresh()
          // Keep the committed action visible until the receipt is dismissed.
        }
        is ActionState.Failed -> error(terminal.message)
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

/** The exit prices entered that differ from the position's own; null where one is left as it is. */
internal data class ExitChanges(val takeProfit: String?, val stopLoss: String?) {
  val any: Boolean
    get() = takeProfit != null || stopLoss != null
}

internal fun exitChanges(takeProfitInput: String, stopLossInput: String, position: Position?): ExitChanges =
  ExitChanges(
    takeProfit = changedExit(takeProfitInput, position?.takeProfitTriggerPrice),
    stopLoss = changedExit(stopLossInput, position?.stopLossTriggerPrice),
  )

private fun changedExit(input: String, current: Double?): String? {
  val entered = input.trim().takeIf(String::isNotEmpty) ?: return null
  if (current != null && (entered == exitPriceInput(current) || entered.toDoubleOrNull() == current)) return null
  return entered
}
