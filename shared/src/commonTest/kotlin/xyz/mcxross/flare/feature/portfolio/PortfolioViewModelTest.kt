package xyz.mcxross.flare.feature.portfolio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AccountOverview
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.Market
import xyz.mcxross.flare.decibel.model.Position
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.kaptos.account.Ed25519Account

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
    assetType: AssetType,
    category: String,
  ): Market =
    Market(
      assetType = assetType,
      address = address,
      name = name,
      sizeDecimals = 2,
      maxLeverage = 20,
      tickSize = 1uL,
      minSize = 1uL,
      lotSize = 1000uL,
      maxOpenInterest = 1_000_000.0,
      priceDecimals = 2,
      mode = "Open",
      unrealizedPnlHaircutBps = 0,
      category = category,
      minPrice = 1uL,
      maxPrice = 100_000_000uL,
      isIsolatedOnly = false,
    )

  private val btcPerp = createMarket("BTC/USD", "0xbtc_perp", AssetType.PERP, "crypto")
  private val aptSpot = createMarket("APT/USDC", "0xapt_spot", AssetType.SPOT, "crypto")

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

  private class FakeAccountRepository(initialSnapshot: AccountSnapshot) : AccountRepository {
    override val snapshot: StateFlow<AccountSnapshot> = MutableStateFlow(initialSnapshot)
    override val history: StateFlow<AccountHistorySnapshot> =
      MutableStateFlow(AccountHistorySnapshot())

    override suspend fun delegations(): List<Delegation> = emptyList()

    override suspend fun revokeDelegation(address: String, prompt: VaultPrompt): TransactionState =
      TransactionState.Committed("0x")

    override suspend fun restoreTrading() {}

    override suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt) {}

    override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<Subaccount> =
      emptyList()

    override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) {}

    override suspend fun createSubaccount(
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): TransactionState = TransactionState.Committed("0x")

    override suspend fun delegateApiWallet(
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): TransactionState = TransactionState.Committed("0x")

    override suspend fun prepareTradingWallet(prompt: VaultPrompt, feePayment: FeePayment) {}

    override fun depositUsdc(
      amount: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<TransactionState> = emptyFlow()

    override fun withdrawUsdc(
      amount: String,
      destination: String?,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<TransactionState> = emptyFlow()

    override suspend fun refresh() {}

    override suspend fun refreshHistory() {}

    override suspend fun loadMoreHistory(kind: AccountHistoryKind) {}

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
      verify: suspend (Ed25519Account) -> Unit,
    ): String = "0xapi"

    override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String = ""

    override suspend fun exportApiWallet(prompt: VaultPrompt): String = ""

    override suspend fun removeOwner(prompt: VaultPrompt) {}

    override suspend fun removeApiWallet(prompt: VaultPrompt) {}

    override fun lock() {}

    override suspend fun <T> withOwnerAccount(
      prompt: VaultPrompt,
      block: suspend (Ed25519Account) -> T,
    ): T = error("Not supported")

    override suspend fun <T> withApiAccount(
      prompt: VaultPrompt,
      block: suspend (Ed25519Account) -> T,
    ): T = error("Not supported")
  }

  private class FakeTradingRepository(private val spotBalances: Map<String, Double> = emptyMap()) :
    TradingRepository {
    override val pendingTransactions: Flow<List<PendingTransaction>> = MutableStateFlow(emptyList())

    override suspend fun reconcilePending() = ReconciliationResult(0, 0)

    override suspend fun transactionStatus(hash: String): TransactionState =
      TransactionState.Committed(hash)

    override fun execute(
      command: DecibelCommand,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      onPrepared: suspend (String) -> Unit,
    ): Flow<TransactionState> = emptyFlow()

    override suspend fun apiWalletAptBalance(): ULong = 100_000_000uL

    override suspend fun baseAssetBalance(accountAddress: String, symbol: String): Double =
      spotBalances[symbol] ?: 0.0

    override fun topUpApiWallet(amountOctas: ULong, prompt: VaultPrompt): Flow<TransactionState> =
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
  fun totalBalanceAggregatesCollateralAndSpotHoldings() = runTest {
    val overview =
      AccountOverview(
        equityBalance = 9.51,
        unrealizedPnl = 0.0,
        unrealizedFundingCost = 0.0,
        crossMarginRatio = 0.0,
        maintenanceMargin = 0.0,
        totalMargin = 0.0,
        crossWithdrawableBalance = 9.51,
        isolatedWithdrawableBalance = 0.0,
        availableToTrade = 9.51,
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
        assetCatalog = FakeAssetCatalogRepository(),
      )

    val state = vm.uiState.first { it.spotHoldings.size >= 2 }

    // Collateral (USDC: 9.51) and Spot (APT: 10.979 * 0.5866 = 6.44028)
    assertEquals(2, state.spotHoldings.size)
    val usdcHolding = state.spotHoldings.first { it.symbol == "USDC" }
    val aptHolding = state.spotHoldings.first { it.symbol == "APT" }

    assertTrue(usdcHolding.isCollateral)
    assertEquals(9.51, usdcHolding.quantity)
    assertEquals("CASH", usdcHolding.badge)

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
  fun tabSwitchingUpdatesSelectedTab() = runTest {
    val snapshot = AccountSnapshot(account = "0xsubaccount", stale = false)
    val vm =
      PortfolioViewModel(
        accounts = FakeAccountRepository(snapshot),
        wallets = FakeWalletRepository(),
        preferences = AppPreferences(MemoryPreferences()),
        trading = FakeTradingRepository(),
        markets = FakeMarketsRepository(quotes),
        marketDetails = FakeMarketDetailsRepository(),
        assetCatalog = FakeAssetCatalogRepository(),
      )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

    assertEquals(PortfolioTab.POSITIONS, vm.uiState.first().selectedTab)

    vm.onIntent(PortfolioIntent.SelectTab(PortfolioTab.HOLDINGS))
    val holdingsState = vm.uiState.first { it.selectedTab == PortfolioTab.HOLDINGS }
    assertEquals(PortfolioTab.HOLDINGS, holdingsState.selectedTab)

    vm.onIntent(PortfolioIntent.SelectTab(PortfolioTab.POSITIONS))
    val positionsState = vm.uiState.first { it.selectedTab == PortfolioTab.POSITIONS }
    assertEquals(PortfolioTab.POSITIONS, positionsState.selectedTab)
  }

  @Test
  fun spotMarketsAreFilteredOutOfPerpPositions() = runTest {
    val perpPos =
      Position("0xbtc_perp", "0xsub", "0.1", 10, 68000.0, false, false, 0.0, 60000.0, 1L, false)
    val spotPos = Position("0xapt_spot", "0xsub", "10", 1, 0.58, false, false, 0.0, 0.0, 1L, false)
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
        assetCatalog = FakeAssetCatalogRepository(),
      )

    val state = vm.uiState.first { it.account.positions.isNotEmpty() }
    assertEquals(1, state.account.positions.size)
    assertEquals("0xbtc_perp", state.account.positions.first().market)
  }
}
