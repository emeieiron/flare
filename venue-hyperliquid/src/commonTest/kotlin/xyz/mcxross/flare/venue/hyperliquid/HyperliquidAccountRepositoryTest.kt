package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.api.StreamData
import xyz.mcxross.flare.hyperliquid.model.ClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.FrontendOrder
import xyz.mcxross.flare.hyperliquid.model.LedgerUpdate
import xyz.mcxross.flare.hyperliquid.model.SpotClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.TwapHistoryEntry
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.venue.model.FundMovementKind
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.hyperliquid.model.Fill as HyperliquidFill

class HyperliquidAccountRepositoryTest {
  private val json = HyperliquidClient.DefaultJson

  private inline fun <reified T> decode(fixture: String): T = json.decodeFromString(fixture)

  private val standardResponses =
    MarketResponses +
      mapOf(
        "clearinghouseState" to Fixtures.CLEARINGHOUSE_STATE,
        "spotClearinghouseState" to Fixtures.SPOT_CLEARINGHOUSE_STATE,
        "frontendOpenOrders" to Fixtures.FRONTEND_OPEN_ORDERS,
        "userAbstraction" to Fixtures.USER_ABSTRACTION,
        "userVaultEquities" to Fixtures.USER_VAULT_EQUITIES,
        "historicalOrders" to Fixtures.HISTORICAL_ORDERS,
        "userFillsByTime" to Fixtures.USER_FILLS_BY_TIME,
        "userFunding" to Fixtures.USER_FUNDING,
        "userNonFundingLedgerUpdates" to Fixtures.LEDGER_UPDATES_KINDS,
        "portfolio" to Fixtures.PORTFOLIO,
        "userFees" to Fixtures.USER_FEES,
        "vaultDetails" to Fixtures.VAULT_DETAILS_FOLLOWER,
        "twapHistory" to Fixtures.TWAP_HISTORY_ENTRIES,
      )

  private suspend fun repository(
    responses: Map<String, String> = standardResponses,
    user: String = Fixtures.USER,
  ): Pair<HyperliquidAccountRepository, AppPreferences> {
    val preferences = memoryPreferences()
    preferences.registerProfile(AccountProfile("owner_$user", ownerAddress = user))
    val client = testClient(responses)
    return testAccounts(client, preferences) to preferences
  }

  @Test
  fun readsTheWalletsOwnAccount() = runTest {
    val (accounts, _) = repository()

    accounts.refresh()

    val snapshot = accounts.snapshot.value
    assertEquals(Fixtures.USER, snapshot.account)
    assertFalse(snapshot.stale)
    assertNull(snapshot.error)
    assertEquals(listOf("BTC", "ETH"), snapshot.positions.map { it.marketId })
    assertEquals(4, snapshot.openOrders.size)
    val overview = assertNotNull(snapshot.overview)
    assertEquals(105084.741241, overview.equity, 1e-6)
    assertEquals(0.0, overview.balances.sharedWithEquity)
    // HYPE is valued at its USDC pair (`@1035`, mid 39.141).
    val hype = overview.balances.spot!!.assets.single { it.symbol == "HYPE" }
    assertEquals(0.02164464 * 39.141, hype.valueUsd, 1e-9)
  }

  @Test
  fun readsAUnifiedAccountWithoutDoubleCounting() = runTest {
    val (accounts, _) =
      repository(
        standardResponses +
          mapOf(
            "clearinghouseState" to Fixtures.CLEARINGHOUSE_STATE_UNIFIED,
            "spotClearinghouseState" to Fixtures.SPOT_CLEARINGHOUSE_STATE_UNIFIED,
            "frontendOpenOrders" to "[]",
            "userAbstraction" to Fixtures.USER_ABSTRACTION_UNIFIED,
          ),
        user = Fixtures.UNIFIED_USER,
      )

    accounts.refresh()

    val overview = assertNotNull(accounts.snapshot.value.overview)
    assertEquals(716.428232, overview.balances.sharedWithEquity, 1e-9)
    assertNull(overview.balances.collateral)
  }

  @Test
  fun reportsAFailedReadAsStale() = runTest {
    val (accounts, _) = repository(MarketResponses)

    accounts.refresh()

    val snapshot = accounts.snapshot.value
    assertEquals(Fixtures.USER, snapshot.account)
    assertTrue(snapshot.stale)
    assertNotNull(snapshot.error)
    assertNull(snapshot.overview)
  }

  @Test
  fun mergesAccountStreamsIntoTheSnapshot() = runTest {
    val (accounts, _) = repository()
    accounts.refresh()
    val user = Fixtures.USER.lowercase()

    assertTrue(
      accounts.applyStreamData(
        Fixtures.USER,
        StreamData.Clearinghouse(user, decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE_EMPTY)),
      )
    )
    assertTrue(accounts.snapshot.value.positions.isEmpty())
    assertEquals(0.0, accounts.snapshot.value.overview!!.equity)

    accounts.applyStreamData(
      Fixtures.USER,
      StreamData.OpenOrders(user, decode<List<FrontendOrder>>(Fixtures.FRONTEND_OPEN_ORDERS_TRIGGERS)),
    )
    assertEquals("62483867152", accounts.snapshot.value.openOrders.first().orderId)

    accounts.applyStreamData(
      Fixtures.USER,
      StreamData.SpotState(user, decode<SpotClearinghouseState>(Fixtures.SPOT_CLEARINGHOUSE_STATE_UNIFIED)),
    )
    val spot = accounts.snapshot.value.overview!!.balances.spot!!
    assertEquals(716.428232, spot.assets.single { it.symbol == "USDC" }.amount)

    // Another account's message changes nothing.
    assertFalse(
      accounts.applyStreamData(
        Fixtures.USER,
        StreamData.Clearinghouse(
          Fixtures.UNIFIED_USER,
          decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE),
        ),
      )
    )
    assertTrue(accounts.snapshot.value.positions.isEmpty())
  }

  @Test
  fun readsHistoryAndMergesNewEntriesFromTheStreams() = runTest {
    val (accounts, _) = repository()
    accounts.refreshHistory()

    val history = accounts.history.value
    assertFalse(history.stale)
    assertEquals(3, history.orders.size)
    assertEquals(3, history.trades.size)
    // Newest first.
    assertEquals(1791764969790L, history.trades.first().timeMs)
    assertFalse(history.tradesHasMore, "every fill since the account began fits on one page")
    assertEquals(3, history.funding.size)
    assertEquals(6, history.transfers.size)
    assertEquals(FundMovementKind.VAULT_WITHDRAWAL, history.transfers.first().kind)

    val fills = decode<List<HyperliquidFill>>(Fixtures.USER_FILLS_BY_TIME)
    val user = Fixtures.USER.lowercase()
    val newer = fills.first().copy(tid = 1L, time = 1791764999999L)
    // A snapshot repeats what was read; only live fills are added, each once.
    accounts.applyStreamData(Fixtures.USER, StreamData.UserFills(user, listOf(newer), isSnapshot = true))
    assertEquals(3, accounts.history.value.trades.size)
    accounts.applyStreamData(Fixtures.USER, StreamData.UserFills(user, listOf(newer, fills[0]), isSnapshot = false))
    assertEquals(listOf("1", "1057008188568842"), accounts.history.value.trades.take(2).map { it.id })
    assertEquals(4, accounts.history.value.trades.size)

    val deposit = decode<List<LedgerUpdate>>(Fixtures.LEDGER_UPDATES_KINDS).first().copy(time = 1791800000000L)
    accounts.applyStreamData(Fixtures.USER, StreamData.LedgerUpdates(user, listOf(deposit), isSnapshot = false))
    assertEquals(FundMovementKind.DEPOSIT, accounts.history.value.transfers.first().kind)
    assertEquals(7, accounts.history.value.transfers.size)
  }

  @Test
  fun pagesOrderHistoryFromTheLatestRead() = runTest {
    val (accounts, _) = repository()
    accounts.refreshHistory()

    assertFalse(accounts.history.value.ordersHasMore)
    accounts.loadMoreHistory(AccountHistoryKind.ORDERS)
    assertEquals(3, accounts.history.value.orders.size)
  }

  @Test
  fun readsChartsFeesVaultsAndTwaps() = runTest {
    val (accounts, _) = repository()

    assertEquals(
      102605.807678,
      accounts.portfolioChart(PortfolioChartRange.DAY_1, PortfolioMetric.EQUITY).first().value,
    )
    assertEquals(0.00035, accounts.fees()!!.perp!!.takerRate)

    val vaults = accounts.vaults()
    assertEquals(listOf(Fixtures.HLP_VAULT), vaults.map { it.address })
    val stake = accounts.vaultPositions().single()
    assertEquals(1070.102048, stake.value)
    assertEquals(1765926140668L, stake.lockedUntilMs)

    assertTrue(accounts.activeTwaps().isEmpty(), "both TWAPs in history ended")
    assertEquals(listOf("14656", "14654"), accounts.twapHistory().map { it.id })

    accounts.refresh()
    val running = decode<List<TwapHistoryEntry>>(Fixtures.TWAP_HISTORY_ENTRIES)[1]
    accounts.applyStreamData(
      Fixtures.USER,
      StreamData.TwapStates(Fixtures.USER.lowercase(), listOf(14656L to running.state)),
    )
    assertEquals(listOf("14656"), accounts.activeTwaps().map { it.id })
  }
}
