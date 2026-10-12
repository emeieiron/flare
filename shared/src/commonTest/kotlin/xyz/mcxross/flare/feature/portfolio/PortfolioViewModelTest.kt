package xyz.mcxross.flare.feature.portfolio

import xyz.mcxross.flare.venue.model.ActionKind
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.data.SetupNeed
import xyz.mcxross.flare.data.AccountSetupGate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.BareCapabilities
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.AssetMetadata
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.MarketCatalog
import xyz.mcxross.flare.data.MarketDetails
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.testing.AcceptingOrderRules
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.model.AccountOverview
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.Balances
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.RewardPart
import xyz.mcxross.flare.venue.model.RewardPoints
import xyz.mcxross.flare.venue.model.RewardTier
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.SelfPayOffer
import xyz.mcxross.flare.venue.model.SpotBalance
import xyz.mcxross.flare.venue.model.SpotBalances
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createMarket(
    name: String,
    address: String,
    kind: MarketKind,
    category: String,
  ): Market =
    Market(
      id = address,
      symbol = name.substringBefore('/'),
      displayName = name,
      kind = kind,
      category = category,
      priceDecimals = 2,
      sizeDecimals = 2,
      maxLeverage = 20,
      isolatedOnly = false,
      rules = AcceptingOrderRules,
    )

  private val btcPerp = createMarket("BTC/USD", "0xbtc_perp", MarketKind.PERP, "crypto")
  private val aptSpot = createMarket("APT/USDC", "0xapt_spot", MarketKind.SPOT, "crypto")

  private val quotes =
    listOf(
      MarketQuote(
        market = btcPerp,
        markPrice = 70_000.0,
        changePercent24h = 0.0,
        volume24h = 0.0,
        openInterest = 0.0,
        favorite = true,
      ),
      MarketQuote(
        market = aptSpot,
        markPrice = 0.5866,
        changePercent24h = -14.2,
        volume24h = 28.9,
        openInterest = 0.0,
        favorite = true,
      ),
    )

  private open class FakeAccountRepository(initialSnapshot: AccountSnapshot) : AccountRepository {
    override val snapshot: StateFlow<AccountSnapshot> = MutableStateFlow(initialSnapshot)
    override val history: StateFlow<AccountHistorySnapshot> =
      MutableStateFlow(AccountHistorySnapshot())

    override suspend fun delegations(): List<TradingKey> = emptyList()

    override suspend fun revokeDelegation(address: String, prompt: VaultPrompt): ActionState =
      ActionState.Committed("0x")

    override suspend fun restoreTrading() {}

    override suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt) {}

    override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount> =
      emptyList()

    override suspend fun subaccounts(owner: String): List<TradingAccount> = emptyList()

    override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) {}

    override suspend fun createSubaccount(
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): ActionState = ActionState.Committed("0x")

    override suspend fun openTradingAccount(prompt: VaultPrompt): String = "0x"

    override suspend fun delegateApiWallet(
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): ActionState = ActionState.Committed("0x")

    override suspend fun prepareTradingWallet(prompt: VaultPrompt, feePayment: FeePayment) {}

    override fun depositUsdc(
      amount: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<ActionState> = emptyFlow()

    override fun withdrawUsdc(
      amount: String,
      destination: String?,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<ActionState> = emptyFlow()

    override suspend fun approveBuilderFee(
      builderAddress: String,
      feeBps: UInt,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      product: MarketKind,
    ): ActionState = ActionState.Committed("0x")

    override suspend fun revokeBuilderFee(
      builderAddress: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      product: MarketKind,
    ): ActionState = ActionState.Committed("0x")

    override suspend fun refresh() {}

    override suspend fun refreshHistory() {}

    override suspend fun loadMoreHistory(kind: AccountHistoryKind) {}

    override suspend fun portfolioChart(
      range: PortfolioChartRange,
      metric: PortfolioMetric,
    ): List<PortfolioPoint> = emptyList()

    var rewardReads = 0
    var vaultReads = 0

    override suspend fun rewards(): Rewards? {
      rewardReads++
      return null
    }

    override suspend fun redeemReferralCode(code: String): Boolean = true

    override suspend fun activeTwaps(): List<TwapOrder> = emptyList()

    override suspend fun twapHistory(limit: Int): List<TwapOrder> = emptyList()

    override suspend fun vaults(limit: Int): List<VaultSummary> {
      vaultReads++
      return emptyList()
    }

    override suspend fun vaultPositions(): List<VaultPosition> = emptyList()

    override fun contributeToVault(
      vaultAddress: String,
      amount: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<ActionState> = emptyFlow()

    override fun redeemFromVault(
      vaultAddress: String,
      shares: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<ActionState> = emptyFlow()

    override fun startLive() {}
  }

  private class FakeWalletRepository : WalletRepository {
    override val profile: Flow<WalletProfile> =
      MutableStateFlow(WalletProfile(ownerAddress = "0xowner", apiWalletAddress = "0xapi"))
    override val authorizationGeneration: Long = 0L

    override fun requireAuthorization(generation: Long) {}

    override suspend fun createOwner(prompt: VaultPrompt): OwnerBackup =
      OwnerBackup("0xowner", emptyList())

    override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String = "0xowner"

    override suspend fun confirmOwnerBackup() {}

    override suspend fun createApiWallet(prompt: VaultPrompt): String = "0xapi"

    override suspend fun importApiWallet(
      key: String,
      prompt: VaultPrompt,
      verify: suspend (address: String) -> Unit,
    ): String = "0xapi"

    override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String = ""

    override suspend fun exportApiWallet(prompt: VaultPrompt): String = ""

    override suspend fun removeOwner(prompt: VaultPrompt) {}

    override suspend fun removeApiWallet(prompt: VaultPrompt) {}

    override fun lock() {}
  }

  private class FakeTradingRepository(
    private val spotBalances: Map<String, Double> = emptyMap(),
    private val outcome: ActionState? = null,
  ) : TradingRepository {
    val executed = mutableListOf<TradeAction>()

    override val pendingTransactions: Flow<List<PendingTransaction>> = MutableStateFlow(emptyList())

    override suspend fun reconcilePending() = ReconciliationResult(0, 0)

    override suspend fun transactionStatus(hash: String): ActionState =
      ActionState.Committed(hash)

    override fun execute(
      action: TradeAction,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      onPrepared: suspend (String) -> Unit,
    ): Flow<ActionState> {
      executed += action
      return outcome?.let { flowOf(it) } ?: emptyFlow()
    }

    override suspend fun tradingKeyFeeBalance(): ULong = 100_000_000uL

    override suspend fun baseAssetBalance(accountAddress: String, symbol: String): Double =
      spotBalances[symbol] ?: 0.0

    override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt): Flow<ActionState> =
      emptyFlow()
  }

  private class FakeMarketsRepository(initialQuotes: List<MarketQuote>) : MarketsRepository {
    override val catalog: StateFlow<MarketCatalog> =
      MutableStateFlow(MarketCatalog(loading = false, quotes = initialQuotes, stale = false))

    override suspend fun refresh() {}

    override suspend fun connectLive() {}

    override suspend fun toggleFavorite(marketAddress: String) {}
  }

  private class FakeMarketDetailsRepository : MarketDetailsRepository {
    override val details: StateFlow<MarketDetails> = MutableStateFlow(MarketDetails(stale = false))

    override suspend fun refresh(marketAddress: String) {}

    override suspend fun connectLive(marketAddress: String) {}
  }

  private class FakeAssetCatalogRepository : AssetCatalogRepository {
    override val assets: StateFlow<Map<String, AssetMetadata>> =
      MutableStateFlow(
        mapOf(
          "apt" to AssetMetadata("APT", "Aptos", "crypto", null, null),
          "usdc" to AssetMetadata("USDC", "USD Coin", "crypto", null, null),
        )
      )

    override suspend fun refresh() {}
  }

  private class MemoryPreferences(initial: Preferences = emptyPreferences()) :
    DataStore<Preferences> {
    override val data = MutableStateFlow(initial)

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
      transform(data.value).also { data.value = it }
  }

  @Test
  fun usdcMovesBetweenPerpsAndSpotWhereTheAccountKeepsThemApart() = runTest {
    val overview =
      AccountOverview(
        equity = 20.0,
        unrealizedPnl = 0.0,
        marginRatio = 0.0,
        availableToTrade = 20.0,
        withdrawable = 12.0,
        balances = Balances(spot = SpotBalances(listOf(SpotBalance("0", "USDC", 7.5, 7.5)), totalUsd = 7.5)),
        perpSpotSeparate = true,
      )
    val snapshot = AccountSnapshot(account = "0xaccount", overview = overview, stale = false)
    val trading = FakeTradingRepository(outcome = ActionState.Committed("0xhash"))
    val vm =
      PortfolioViewModel(FakeAccountRepository(snapshot), FakeWalletRepository(), AppPreferences(MemoryPreferences()),
        trading, FakeMarketsRepository(quotes), FakeMarketDetailsRepository(),
        testVenue(BareCapabilities.copy(perpSpotTransfer = true)))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

    vm.onIntent(PortfolioIntent.OpenFunding(FundingMode.WITHDRAW))
    vm.onIntent(PortfolioIntent.SelectFundingMode(FundingMode.TO_PERPS))
    assertTrue(vm.uiState.value.canMovePerpSpot)
    assertEquals(7.5, vm.uiState.value.fundingAvailable(FundingMode.TO_PERPS))
    assertEquals(12.0, vm.uiState.value.fundingAvailable(FundingMode.TO_SPOT))
    vm.onIntent(PortfolioIntent.ChangeFundingAmount("8"))
    vm.onIntent(PortfolioIntent.SubmitFunding)
    assertTrue(trading.executed.isEmpty(), "more than spot holds is refused before signing")

    vm.onIntent(PortfolioIntent.ChangeFundingAmount("7.5"))
    vm.onIntent(PortfolioIntent.SubmitFunding)

    assertEquals(listOf<TradeAction>(TradeAction.MovePerpSpot("0xaccount", "7.5", toSpot = false)), trading.executed)
    assertEquals(ActionState.Committed("0xhash"), vm.uiState.value.fundingTransaction)
    val unified = PortfolioUiState(capabilities = BareCapabilities.copy(perpSpotTransfer = true),
      account = AccountSnapshot(overview = overview.copy(perpSpotSeparate = false)))
    assertFalse(unified.canMovePerpSpot)
  }

  @Test
  fun actionsWaitWhileSetupIsLeftForLaterAndTheVenuesOwnReasonsStay() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("owner_0x1", ownerAddress = "0x1", selectedSubaccount = "0x1"))
    preferences.deferSetup()
    val accounts = FakeAccountRepository(AccountSnapshot(account = "0x1"))
    val venue = testVenue(VenueCapabilities.All.copy(unavailableActions = mapOf(ActionKind.TWAP to "Not here")))
    val vm =
      PortfolioViewModel(accounts, FakeWalletRepository(), preferences, FakeTradingRepository(),
        FakeMarketsRepository(emptyList()), FakeMarketDetailsRepository(), venue,
        setupGate = AccountSetupGate(preferences, accounts))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

    assertEquals(SetupNeed.TRADING, vm.uiState.value.setupNeed)
    assertEquals(SetupNeed.TRADING.reason, vm.uiState.value.capabilities.unavailableReason(ActionKind.TRADING))
    assertEquals(SetupNeed.TRADING.reason, vm.uiState.value.capabilities.unavailableReason(ActionKind.FUNDS))
    assertEquals("Not here", vm.uiState.value.capabilities.unavailableReason(ActionKind.TWAP))

    preferences.setOnboardingComplete(true)
    assertNull(vm.uiState.value.setupNeed)
    assertNull(vm.uiState.value.capabilities.unavailableReason(ActionKind.TRADING))
    assertEquals("Not here", vm.uiState.value.capabilities.unavailableReason(ActionKind.TWAP))
  }

  @Test
  fun totalBalanceAggregatesCollateralAndSpotHoldings() = runTest {
    val overview =
      AccountOverview(
        equity = 9.51,
        unrealizedPnl = 0.0,
        marginRatio = 0.0,
        availableToTrade = 9.51,
        withdrawable = 5.0,
        balances = Balances(collateral = 9.51),
      )
    val snapshot = AccountSnapshot(account = "0xsubaccount", overview = overview, stale = false)
    val trading = FakeTradingRepository(spotBalances = mapOf("APT" to 10.979))
    val vm =
      PortfolioViewModel(
        accounts = FakeAccountRepository(snapshot),
        wallets = FakeWalletRepository(),
        preferences = AppPreferences(MemoryPreferences()),
        trading = trading,
        markets = FakeMarketsRepository(quotes),
        marketDetails = FakeMarketDetailsRepository(),
        venue = testVenue(),
        assetCatalog = FakeAssetCatalogRepository(),
      )

    val state = vm.uiState.first { it.spotHoldings.size >= 2 }

    // Collateral (USDC: 9.51) and Spot (APT: 10.979 * 0.5866 = 6.44028)
    assertEquals(2, state.spotHoldings.size)
    val usdcHolding = state.spotHoldings.first { it.symbol == "USDC" }
    val aptHolding = state.spotHoldings.first { it.symbol == "APT" }

    assertTrue(usdcHolding.isCollateral)
    assertEquals(9.51, usdcHolding.quantity)
    assertEquals("MARGIN", usdcHolding.badge)

    assertFalse(aptHolding.isCollateral)
    assertEquals(10.979, aptHolding.quantity)
    assertEquals("SPOT", aptHolding.badge)
    assertEquals("0xapt_spot", aptHolding.marketAddress)
    assertEquals("Aptos", aptHolding.name)

    // Spot value: ~6.44
    assertEquals(6.44, (aptHolding.valueUsd * 100).toInt() / 100.0)
    // Total balance: 9.51 + 6.44 = ~15.95
    assertEquals(15.95, (state.totalBalance * 100).toInt() / 100.0)
  }

  @Test
  fun historyRecoversAutomaticallyAndRetainsCachedPoints() = runTest {
    var unavailable = true
    var reads = 0
    val point = PortfolioPoint(timeMs = 1, value = 25.0)
    val accounts = object : FakeAccountRepository(AccountSnapshot(account = "0xsubaccount", stale = false)) {
      override suspend fun portfolioChart(range: PortfolioChartRange, metric: PortfolioMetric): List<PortfolioPoint> {
        reads++
        check(!unavailable) { "Offline" }
        return listOf(point)
      }
    }
    val vm = PortfolioViewModel(accounts, FakeWalletRepository(), AppPreferences(MemoryPreferences()),
      FakeTradingRepository(), FakeMarketsRepository(quotes), FakeMarketDetailsRepository(), testVenue())
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
    vm.onIntent(PortfolioIntent.SetHistoryVisible(true))
    assertTrue(vm.uiState.value.chartError != null)
    val recovery = backgroundScope.launch { vm.refreshWhileVisible() }
    runCurrent()
    unavailable = false
    advanceTimeBy(2_001)
    assertEquals(listOf(point), vm.uiState.value.chartPoints)
    assertEquals(null, vm.uiState.value.chartError)

    unavailable = true
    vm.onIntent(PortfolioIntent.SelectChartRange(PortfolioChartRange.DAY_1))
    assertTrue(vm.uiState.value.chartError != null)
    assertEquals(listOf(point), vm.uiState.value.chartPoints)
    vm.onIntent(PortfolioIntent.SetHistoryVisible(false))
    val readsWhenHidden = reads
    advanceTimeBy(30_001)
    assertEquals(readsWhenHidden, reads)
    recovery.cancel()
  }

  @Test
  fun spotMarketsAreFilteredOutOfPerpPositions() = runTest {
    val perpPos =
      Position("0xbtc_perp", "0.1", 10, 68000.0, isolated = false, liquidationPrice = 60000.0)
    val spotPos = Position("0xapt_spot", "10", 1, 0.58, isolated = false, liquidationPrice = 0.0)
    val snapshot =
      AccountSnapshot(account = "0xsubaccount", positions = listOf(perpPos, spotPos), stale = false)

    val vm =
      PortfolioViewModel(
        accounts = FakeAccountRepository(snapshot),
        wallets = FakeWalletRepository(),
        preferences = AppPreferences(MemoryPreferences()),
        trading = FakeTradingRepository(),
        markets = FakeMarketsRepository(quotes),
        marketDetails = FakeMarketDetailsRepository(),
        venue = testVenue(),
        assetCatalog = FakeAssetCatalogRepository(),
      )

    val state = vm.uiState.first { it.account.positions.isNotEmpty() }
    assertEquals(1, state.account.positions.size)
    assertEquals("0xbtc_perp", state.account.positions.first().marketId)
  }

  @Test
  fun vaultsAndActionFlow() = runTest {
    val snapshot = AccountSnapshot(account = "0xsubaccount", stale = false)
    val testVault =
      VaultSummary(
        address = "0xvault1",
        name = "Decibel Liquidity Pool",
        totalValue = 500_000.0,
        sharePrice = 1.05,
      )
    val fakeAccounts =
      object : FakeAccountRepository(snapshot) {
        override suspend fun vaults(limit: Int): List<VaultSummary> = listOf(testVault)

        override suspend fun vaultPositions(): List<VaultPosition> =
          listOf(VaultPosition(vault = testVault, shares = 100.0, value = 105.0, returnPercent = 5.0))
      }

    val vm =
      PortfolioViewModel(
        accounts = fakeAccounts,
        wallets = FakeWalletRepository(),
        preferences = AppPreferences(MemoryPreferences()),
        trading = FakeTradingRepository(),
        markets = FakeMarketsRepository(quotes),
        marketDetails = FakeMarketDetailsRepository(),
        venue = testVenue(),
        assetCatalog = FakeAssetCatalogRepository(),
      )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

    val vaultsState =
      vm.uiState.first { it.vaults.isNotEmpty() }
    assertEquals(1, vaultsState.vaults.size)
    assertEquals("Decibel Liquidity Pool", vaultsState.vaults.first().name)
    assertEquals(1, vaultsState.accountVaults.size)
    assertEquals(105.0, vaultsState.accountVaults.first().value)

    // Test opening deposit action
    vm.onIntent(PortfolioIntent.OpenVaultAction(testVault, VaultActionMode.DEPOSIT))
    val depositSheetState = vm.uiState.first { it.selectedVault != null }
    assertEquals("0xvault1", depositSheetState.selectedVault?.address)
    assertEquals(VaultActionMode.DEPOSIT, depositSheetState.vaultAction)

    vm.onIntent(PortfolioIntent.ChangeVaultAmount("5.0"))
    vm.onIntent(PortfolioIntent.SubmitVaultAction)
    assertEquals(
      "The minimum deposit for DLP vaults is 10 USDC.",
      vm.uiState.first().actionError,
    )

    vm.onIntent(PortfolioIntent.ChangeVaultAmount("50.0"))
    assertEquals("50.0", vm.uiState.first().vaultAmountInput)

    vm.onIntent(PortfolioIntent.DismissVaultAction)
    val dismissedState = vm.uiState.first { it.selectedVault == null }
    kotlin.test.assertNull(dismissedState.selectedVault)
    kotlin.test.assertNull(dismissedState.vaultAction)

    // Test opening redeem action
    vm.onIntent(PortfolioIntent.OpenVaultAction(testVault, VaultActionMode.REDEEM))
    val redeemSheetState = vm.uiState.first { it.selectedVault != null }
    assertEquals("0xvault1", redeemSheetState.selectedVault?.address)
    assertEquals(VaultActionMode.REDEEM, redeemSheetState.vaultAction)

    // Below 5 shares minimum
    vm.onIntent(PortfolioIntent.ChangeVaultAmount("2.0"))
    vm.onIntent(PortfolioIntent.SubmitVaultAction)
    assertEquals(
      "The minimum redemption for DLP vaults is 5 shares.",
      vm.uiState.first().actionError,
    )

    // Exceeds available shares (100)
    vm.onIntent(PortfolioIntent.ChangeVaultAmount("150.0"))
    vm.onIntent(PortfolioIntent.SubmitVaultAction)
    assertEquals(
      "You cannot redeem more than your balance of 100 shares.",
      vm.uiState.first().actionError,
    )

    // Valid share amount
    vm.onIntent(PortfolioIntent.ChangeVaultAmount("50.0"))
    assertEquals("50.0", vm.uiState.first().vaultAmountInput)

    vm.onIntent(PortfolioIntent.DismissVaultAction)
  }

  @Test
  fun transferFundingModeAndDirectionToggle() = runTest {
    val fakeAccounts = FakeAccountRepository(AccountSnapshot())
    val vm =
      PortfolioViewModel(
        accounts = fakeAccounts,
        wallets = FakeWalletRepository(),
        preferences = AppPreferences(MemoryPreferences()),
        trading = FakeTradingRepository(spotBalances = mapOf("USDC" to 42.5)),
        markets = FakeMarketsRepository(quotes),
        marketDetails = FakeMarketDetailsRepository(),
        venue = testVenue(),
        assetCatalog = FakeAssetCatalogRepository(),
      )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

    // Open transfer (defaults to deposit into subaccount)
    vm.onIntent(PortfolioIntent.OpenFunding(FundingMode.DEPOSIT))
    val depositState = vm.uiState.first { it.fundingMode != null && it.primaryUsdcBalance > 0.0 }
    assertEquals(FundingMode.DEPOSIT, depositState.fundingMode)
    assertEquals(42.5, depositState.primaryUsdcBalance)

    // Switch to withdraw (to primary)
    vm.onIntent(PortfolioIntent.OpenFunding(FundingMode.WITHDRAW))
    val withdrawState = vm.uiState.first { it.fundingMode == FundingMode.WITHDRAW }
    assertEquals(FundingMode.WITHDRAW, withdrawState.fundingMode)

    // Change amount
    vm.onIntent(PortfolioIntent.ChangeFundingAmount("100"))
    assertEquals("100", vm.uiState.first().fundingAmount)

    // Close funding
    vm.onIntent(PortfolioIntent.CloseFunding)
    val closedState = vm.uiState.first { it.fundingMode == null }
    kotlin.test.assertNull(closedState.fundingMode)
  }

  private fun portfolio(
    accounts: AccountRepository,
    capabilities: VenueCapabilities = VenueCapabilities.All,
    trading: TradingRepository = FakeTradingRepository(),
  ) =
    PortfolioViewModel(
      accounts = accounts,
      wallets = FakeWalletRepository(),
      preferences = AppPreferences(MemoryPreferences()),
      trading = trading,
      markets = FakeMarketsRepository(quotes),
      marketDetails = FakeMarketDetailsRepository(),
      venue = testVenue(capabilities),
    )

  @Test
  fun featuresTheVenueLacksAreNeitherLoadedNorOffered() = runTest {
    val accounts = FakeAccountRepository(AccountSnapshot(account = "0xsubaccount", stale = false))
    val vm = portfolio(accounts, BareCapabilities)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

    vm.onIntent(PortfolioIntent.Refresh)
    vm.onIntent(PortfolioIntent.RefreshVaults)
    val state = vm.uiState.value
    assertEquals(BareCapabilities, state.capabilities)
    assertEquals(0, accounts.rewardReads)
    assertEquals(0, accounts.vaultReads)
    assertFalse(state.rewardsLoaded)
    assertFalse(state.vaultsLoaded)
    assertNull(
      state.selfPayEstimate(ActionState.Failed("Not sponsored", selfPay = SelfPayOffer(5_000uL)))
    )

    // Without in-app deposits, a transfer can only go out to the wallet.
    vm.onIntent(PortfolioIntent.OpenFunding(FundingMode.DEPOSIT))
    assertEquals(FundingMode.WITHDRAW, vm.uiState.value.fundingMode)
  }

  @Test
  fun aVenueWithEveryFeatureLoadsAndOffersThemAll() = runTest {
    val accounts = FakeAccountRepository(AccountSnapshot(account = "0xsubaccount", stale = false))
    val vm = portfolio(accounts)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

    val state = vm.uiState.value
    assertEquals(1, accounts.rewardReads)
    assertEquals(1, accounts.vaultReads)
    assertTrue(state.rewardsLoaded)
    assertTrue(state.vaultsLoaded)
    assertEquals("Amps", state.rewardPointsName)
    assertEquals(
      5_000uL,
      state.selfPayEstimate(ActionState.Failed("Not sponsored", selfPay = SelfPayOffer(5_000uL))),
    )
    vm.onIntent(PortfolioIntent.OpenFunding(FundingMode.DEPOSIT))
    assertEquals(FundingMode.DEPOSIT, vm.uiState.value.fundingMode)
  }

  @Test
  fun aNetworkFeeTopUpIsOfferedOnlyWhereTheVenueHasOne() = runTest {
    suspend fun topUpOffered(capabilities: VenueCapabilities): Boolean {
      val position = Position("0xbtc_perp", "0.1", 10, 68_000.0, isolated = false)
      val accounts =
        FakeAccountRepository(
          AccountSnapshot(account = "0xsubaccount", positions = listOf(position), stale = false)
        )
      // Flare couldn't sponsor the fee, and the device holds less than the wallet would pay.
      val failure = ActionState.Failed("Not sponsored", selfPay = SelfPayOffer(200_000_000uL))
      val vm = portfolio(accounts, capabilities, FakeTradingRepository(outcome = failure))
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
      vm.onIntent(PortfolioIntent.ManagePosition("0xbtc_perp"))
      vm.onIntent(PortfolioIntent.ChangeTakeProfit("80000"))
      vm.onIntent(PortfolioIntent.SubmitTpSl)
      val state = vm.uiState.value
      assertIs<ActionState.Failed>(state.positionTransaction)
      return state.apiWalletNeedsTopUp
    }

    assertTrue(topUpOffered(VenueCapabilities.All))
    assertFalse(topUpOffered(VenueCapabilities.All.copy(networkFeeTopUp = false)))
  }

  @Test
  fun rewardsThatFailToLoadKeepWhatWasShown() = runTest {
    var reads = 0
    val accounts =
      object : FakeAccountRepository(AccountSnapshot(account = "0xsubaccount", stale = false)) {
        override suspend fun rewards(): Rewards? {
          reads++
          return if (reads == 1) {
            Rewards(points = RewardPoints(total = 1_500.0), tier = RewardTier("Silver"))
          } else {
            Rewards(tier = RewardTier("Gold"), unavailable = setOf(RewardPart.POINTS))
          }
        }
      }
    val vm = portfolio(accounts)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
    assertEquals(1_500.0, vm.uiState.value.rewards?.points?.total)
    assertFalse(vm.uiState.value.rewardsError)

    vm.onIntent(PortfolioIntent.Refresh)

    val state = vm.uiState.value
    assertEquals(2, reads)
    assertEquals(1_500.0, state.rewards?.points?.total)
    assertEquals("Gold", state.rewards?.tier?.name)
    assertTrue(state.rewardsError)
  }
}
