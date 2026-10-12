package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.feature.orders.openOrderConditions
import xyz.mcxross.flare.feature.orders.openOrderSummary
import xyz.mcxross.flare.feature.portfolio.PortfolioUiState
import xyz.mcxross.flare.feature.trade.spotQuoteBalance
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.AccountAbstraction
import xyz.mcxross.flare.hyperliquid.model.ClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.DailyVolume
import xyz.mcxross.flare.hyperliquid.model.FrontendOrder
import xyz.mcxross.flare.hyperliquid.model.FundingUpdate
import xyz.mcxross.flare.hyperliquid.model.LedgerUpdate
import xyz.mcxross.flare.hyperliquid.model.OrderWithStatus
import xyz.mcxross.flare.hyperliquid.model.Portfolio
import xyz.mcxross.flare.hyperliquid.model.Referral
import xyz.mcxross.flare.hyperliquid.model.SpotClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.StakingDiscount
import xyz.mcxross.flare.hyperliquid.model.TwapHistoryEntry
import xyz.mcxross.flare.hyperliquid.model.UserFees
import xyz.mcxross.flare.hyperliquid.model.VaultDetails
import xyz.mcxross.flare.hyperliquid.model.VaultEquity
import xyz.mcxross.flare.venue.model.FeeRates
import xyz.mcxross.flare.venue.model.FillDirection
import xyz.mcxross.flare.venue.model.FundMovementKind
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderTrigger
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.ReferralSummary
import xyz.mcxross.flare.venue.model.RewardPart
import xyz.mcxross.flare.hyperliquid.model.Fill as HyperliquidFill

class HyperliquidAccountMappersTest {
  private val json = HyperliquidClient.DefaultJson

  private inline fun <reified T> decode(fixture: String): T = json.decodeFromString(fixture)

  private val standard = AccountAbstraction(AccountAbstraction.DEFAULT)
  private val unified = AccountAbstraction(AccountAbstraction.UNIFIED)

  @Test
  fun keepsPerpsAndSpotApartOnAStandardAccount() {
    val overview =
      accountOverview(
        decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE),
        decode<SpotClearinghouseState>(Fixtures.SPOT_CLEARINGHOUSE_STATE),
        standard,
        mapOf("USDC" to 1.0, "HYPE" to 40.0),
        vaultEquities = null,
        nowMs = 0L,
      )

    assertEquals(105084.741241, overview.equity, 1e-6)
    assertEquals(330.074442, overview.unrealizedPnl, 1e-6)
    assertEquals(105084.741241 - 330.074442, overview.balances.collateral!!, 1e-6)
    assertEquals(78440.500153, overview.withdrawable, 1e-6)
    assertEquals(2608.955272 / 105084.741241, overview.marginRatio, 1e-9)
    assertEquals(0.0, overview.balances.sharedWithEquity)
    val spot = overview.balances.spot!!
    assertEquals(listOf("USDC", "HYPE"), spot.assets.map { it.symbol })
    assertEquals(0.02164464 * 40.0, spot.assets.last().valueUsd, 1e-9)
    assertEquals(0.00825041 + 0.02164464 * 40.0, spot.totalUsd, 1e-9)
    assertNull(overview.vaultEquity)
  }

  @Test
  fun countsUnifiedSpotUsdcOnlyOnce() {
    val perp = decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE_UNIFIED)
    val spot = decode<SpotClearinghouseState>(Fixtures.SPOT_CLEARINGHOUSE_STATE_UNIFIED)
    val prices = mapOf("USDC" to 1.0)

    val overview = accountOverview(perp, spot, unified, prices, null, 0L)

    // Spot USDC (716.428232) already holds the perp account's value (716.428468).
    assertEquals(716.428232, overview.balances.sharedWithEquity, 1e-9)
    assertNull(overview.balances.collateral)
    assertEquals(0.0, overview.withdrawable)
    assertEquals(0.0, overview.availableToTrade)
    assertEquals(112.848642 / 716.428232, overview.marginRatio, 1e-9)
    val total = PortfolioUiState(account = AccountSnapshot(overview = overview)).totalBalance
    assertEquals(716.428468, total, 1e-6)

    assertFalse(overview.perpSpotSeparate, "a unified account has nothing to move between perps and spot")

    // Read as separate accounts, the same balances would count the USDC twice.
    val separate = accountOverview(perp, spot, standard, prices, null, 0L)
    assertTrue(separate.perpSpotSeparate)
    assertFalse(accountOverview(perp, spot, null, prices, null, 0L).perpSpotSeparate)
    val doubled = PortfolioUiState(account = AccountSnapshot(overview = separate)).totalBalance
    assertEquals(716.428468 + 716.428232, doubled, 1e-6)
  }

  @Test
  fun aSpotBuyCanSpendOnlyFreeUsdc() {
    val spot =
      decode<SpotClearinghouseState>(
        """{"balances":[{"coin":"USDC","token":0,"total":"983.489225","hold":"0.998232","entryNtl":"0.0"}]}"""
      )
    val perp = decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE)
    val free = 983.489225 - 0.998232

    // Unified: spot USDC is also the perps' margin, so the perps' withdrawable isn't added again.
    val unifiedOverview = accountOverview(perp, spot, unified, mapOf("USDC" to 1.0), null, 0L)
    assertEquals(free, spotQuoteBalance(unifiedOverview)!!, 1e-9)
    // Standard: the perps' USDC sits in another account, which spot can't spend from.
    val standardOverview = accountOverview(perp, spot, standard, mapOf("USDC" to 1.0), null, 0L)
    assertEquals(free, spotQuoteBalance(standardOverview)!!, 1e-9)
  }

  @Test
  fun unifiedFreeUsdcIsWhatMarginDoesNotHold() {
    val spot =
      decode<SpotClearinghouseState>(
        """{"balances":[{"coin":"USDC","token":0,"total":"983.489225","hold":"0.998232","entryNtl":"0.0"}]}"""
      )
    val perp = decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE_EMPTY)

    val overview = accountOverview(perp, spot, unified, mapOf("USDC" to 1.0), null, 0L)

    assertEquals(983.489225 - 0.998232, overview.withdrawable, 1e-9)
    assertEquals(0.0, overview.balances.sharedWithEquity)
  }

  @Test
  fun countsOnlyUnlockedVaultEquity() {
    val equities = decode<List<VaultEquity>>(Fixtures.USER_VAULT_EQUITIES)
    val perp = decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE)
    val lockedUntil = equities.single().lockedUntilTimestamp!!

    assertEquals(0.0, accountOverview(perp, null, standard, emptyMap(), equities, lockedUntil - 1).vaultEquity)
    assertEquals(
      1070.102048,
      accountOverview(perp, null, standard, emptyMap(), equities, lockedUntil).vaultEquity!!,
      1e-9,
    )
  }

  @Test
  fun valuesSpotTokensAtTheirUsdcPair() = runTest {
    val prices = testClient(MarketResponses).info.spotMetaAndContexts().tokenPrices()

    assertEquals(1.0, prices["USDC"])
    assertEquals(4.60235, prices["PURR"])
    assertEquals(39.141, prices["HYPE"])
    assertEquals(1.0936, prices["TEST"], "without a mid price, the mark price")
    assertNull(prices["JPL"], "a pair without a context values nothing")
  }

  @Test
  fun mapsPositionsWithTheirExits() {
    val state = decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE)
    // The captured position stop-loss is on SOL; here it guards BTC instead.
    val orders = decode<List<FrontendOrder>>(Fixtures.FRONTEND_OPEN_ORDERS_TRIGGERS.replace("\"SOL\"", "\"BTC\""))

    val positions = state.toPositions(orders)

    assertEquals(listOf("BTC", "ETH"), positions.map { it.marketId })
    val btc = positions.first()
    assertEquals("0.76666", btc.size)
    assertTrue(btc.isLong)
    assertEquals(5, btc.leverage)
    assertFalse(btc.isolated)
    assertEquals(82756.7, btc.entryPrice)
    assertNull(btc.liquidationPrice)
    assertEquals(112.31, btc.stopLossTriggerPrice)
    assertNull(btc.takeProfitTriggerPrice)
    assertNull(positions.last().stopLossTriggerPrice)
  }

  @Test
  fun showsExitsAttachedToTheOpeningOrder() {
    val state = decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE)
    // normalTpsl children of a filled order: plain reduce-only triggers, not position-level.
    val orders =
      decode<List<FrontendOrder>>(
        "[" + exitJson(1, "Take Profit Market", "120000.0", position = false) + "," +
          exitJson(2, "Stop Market", "40000.0", position = false) + "]"
      )

    val btc = state.toPositions(orders).first()

    assertEquals(120000.0, btc.takeProfitTriggerPrice)
    assertEquals(40000.0, btc.stopLossTriggerPrice)
  }

  @Test
  fun prefersThePositionsOwnExitsOverAttachedOnes() {
    val state = decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE)
    val orders =
      decode<List<FrontendOrder>>(
        "[" +
          listOf(
            exitJson(1, "Stop Market", "40000.0", position = false, time = 3),
            exitJson(2, "Stop Market", "50000.0", position = true, time = 1),
            exitJson(3, "Take Profit Market", "110000.0", position = false, time = 1),
            exitJson(4, "Take Profit Market", "120000.0", position = false, time = 2),
          ).joinToString(",") + "]"
      )

    val btc = state.toPositions(orders).first()

    assertEquals(50000.0, btc.stopLossTriggerPrice)
    assertEquals(120000.0, btc.takeProfitTriggerPrice, "without a position-level one, the latest")
  }

  @Test
  fun ignoresExitsThatDontGuardThePosition() {
    val state = decode<ClearinghouseState>(Fixtures.CLEARINGHOUSE_STATE)
    val waiting = exitJson(5, "Take Profit Market", "120000.0", position = false)
    val orders =
      decode<List<FrontendOrder>>(
        "[" +
          listOf(
            // Waiting on an entry that hasn't filled.
            waiting,
            """{"coin":"BTC","side":"B","limitPx":"80000.0","sz":"0.1","oid":6,"timestamp":1,"origSz":"0.1","children":[$waiting],"reduceOnly":false,"orderType":"Limit","tif":"Gtc"}""",
            // A buy can't close a long.
            exitJson(7, "Stop Market", "90000.0", position = false, side = "B"),
            // Not reduce-only.
            exitJson(8, "Stop Market", "41000.0", position = false, reduceOnly = false),
          ).joinToString(",") + "]"
      )

    val btc = state.toPositions(orders).first()

    assertNull(btc.takeProfitTriggerPrice)
    assertNull(btc.stopLossTriggerPrice)
  }

  private fun exitJson(
    oid: Long,
    type: String,
    trigger: String,
    position: Boolean,
    time: Long = 1,
    side: String = "A",
    reduceOnly: Boolean = true,
  ): String =
    """{"coin":"BTC","side":"$side","limitPx":"$trigger","sz":"${if (position) "0.0" else "0.76666"}","oid":$oid,"timestamp":$time,"triggerCondition":"N/A","isTrigger":true,"triggerPx":"$trigger","children":[],"isPositionTpsl":$position,"reduceOnly":$reduceOnly,"orderType":"$type","origSz":"0.76666","tif":null,"cloid":null}"""

  @Test
  fun mapsOpenOrdersWithTriggersAndAttachedExits() {
    val orders = decode<List<FrontendOrder>>(Fixtures.FRONTEND_OPEN_ORDERS_TRIGGERS).map { it.toOpenOrder() }

    val takeProfit = orders[0]
    assertEquals("62483867152", takeProfit.orderId)
    assertEquals("0x83d931840eaa067b6bca540927e8aabd", takeProfit.clientOrderId)
    assertNull(takeProfit.price, "a market trigger's limit price is only its slippage bound")
    assertEquals("Price above 2.447", takeProfit.triggerCondition)
    assertTrue(takeProfit.reduceOnly)
    assertFalse(takeProfit.isBuy)

    assertEquals(82190.0, orders[1].price)
    assertEquals("Stop Limit", orders[1].orderType)
    assertTrue(orders[2].isPositionTpSl)
    assertEquals(0.0, orders[2].remainingSize)

    val parent = orders[3]
    assertTrue(parent.isBuy)
    assertEquals("", parent.triggerCondition)
    assertEquals(OrderTrigger(2.447), parent.takeProfit)
    assertEquals(OrderTrigger(2.1933), parent.stopLoss)

    val resting = decode<List<FrontendOrder>>(Fixtures.FRONTEND_OPEN_ORDERS).first().toOpenOrder()
    assertEquals(2529.2, resting.price)
    assertEquals(MarketKind.PERP, resting.kind)
  }

  @Test
  fun exitRowsShowTheirKindTriggerAndSize() {
    fun rows(fixture: String) =
      decode<List<FrontendOrder>>(fixture).map { it.toOpenOrder() }
        .map { openOrderConditions(it) to openOrderSummary(it) }

    assertEquals(
      listOf(
        "stop market · Reduce only" to "Sell entire position · at market · trigger \$35,000.00",
        "take profit market · Reduce only" to "Sell entire position · at market · trigger \$130,000.00",
      ),
      rows(Fixtures.FRONTEND_OPEN_ORDERS_POSITION_EXITS),
    )
    assertEquals(
      listOf(
        "stop market · Reduce only" to "Sell 0.00016 · at market · trigger \$40,000.00",
        "take profit market · Reduce only" to "Sell 0.00016 · at market · trigger \$120,000.00",
      ),
      rows(Fixtures.FRONTEND_OPEN_ORDERS_ATTACHED_EXITS),
    )
    val triggers = rows(Fixtures.FRONTEND_OPEN_ORDERS_TRIGGERS).map { it.second }
    assertEquals("Sell 0.06917 · at \$82,190.00 · trigger \$83,011.00", triggers[1])
    // A resting order with exits attached has no trigger of its own.
    assertEquals("Buy 8.5 · at \$2.250", triggers[3])
  }

  @Test
  fun namesOrderHistoryStatuses() {
    val history = decode<List<OrderWithStatus>>(Fixtures.HISTORICAL_ORDERS).map { it.toOrderHistoryEntry() }

    assertEquals(
      listOf("Canceled", "Open", "Rejected: post-only would have crossed"),
      history.map { it.status },
    )
    assertEquals("Canceled: open interest cap", orderStatusLabel("openInterestCapCanceled"))
    assertEquals("Canceled: delisted", orderStatusLabel("delistedCanceled"))
  }

  @Test
  fun mapsFillsAndFunding() {
    val fills = decode<List<HyperliquidFill>>(Fixtures.USER_FILLS_BY_TIME).map { it.toFill() }

    assertEquals("679767149989109", fills[0].id)
    assertEquals(FillDirection.OPEN_LONG, fills[0].direction)
    assertEquals(-0.000349, fills[0].fee)
    assertEquals(FillDirection.CLOSE_LONG, fills[1].direction)
    assertEquals(0.044935, fills[1].realizedPnl)
    assertEquals(1791764948446L, fills[1].timeMs)

    val funding = decode<List<FundingUpdate>>(Fixtures.USER_FUNDING).first().toFundingPayment()
    assertEquals("BTC", funding.marketId)
    assertEquals("Long funding", funding.action)
    assertEquals(0.44474, funding.size)
    assertEquals(24.591007, funding.amount)
  }

  @Test
  fun mapsLedgerKindsToFundMovements() {
    val user = Fixtures.USER
    val movements =
      decode<List<LedgerUpdate>>(Fixtures.LEDGER_UPDATES_KINDS).map { it.toFundMovement(user) }

    assertEquals(
      listOf(
        FundMovementKind.DEPOSIT,
        FundMovementKind.WITHDRAWAL,
        FundMovementKind.TRANSFER_IN,
        FundMovementKind.TRANSFER_IN,
        FundMovementKind.VAULT_DEPOSIT,
        FundMovementKind.VAULT_WITHDRAWAL,
      ),
      movements.map { it!!.kind },
    )
    assertEquals(73995.28, movements[0]!!.amount)
    assertEquals(
      "0xb9fd6a8ae79d8a3683794a3db068f2c8b1fc6a1d5365b7776dc5033c88484bab",
      movements[0]!!.reference,
    )
    assertEquals(47921.944152, movements[1]!!.amount)
    assertEquals("to perps", movements[3]!!.status)
    assertEquals(5.0, movements[5]!!.amount)

    val others =
      decode<List<LedgerUpdate>>(Fixtures.USER_NON_FUNDING_LEDGER_UPDATES).map { it.toFundMovement(user) }
    assertEquals(FundMovementKind.TRANSFER_IN, others[0]!!.kind)
    assertEquals(1000.0, others[0]!!.amount)
    assertEquals(FundMovementKind.TRANSFER_IN, others[1]!!.kind)
    assertEquals(999.0, others[1]!!.amount)
    assertNull(others[2], "staking moves nothing in or out of the trading account")

    // The sender sees the same transfer going out.
    val sender = "0x1000000000000000000000000000000000000007"
    assertEquals(FundMovementKind.TRANSFER_OUT, decode<List<LedgerUpdate>>(Fixtures.LEDGER_UPDATES_KINDS)[2].toFundMovement(sender)!!.kind)
  }

  @Test
  fun chartsEachPeriodAndMetric() {
    val portfolio = decode<Portfolio>(Fixtures.PORTFOLIO)

    val day = portfolio.chart(PortfolioChartRange.DAY_1, PortfolioMetric.EQUITY)
    assertEquals(1791677160046L, day.first().timeMs)
    assertEquals(102605.807678, day.first().value)
    assertEquals(100.653305, portfolio.chart(PortfolioChartRange.DAY_1, PortfolioMetric.PNL)[1].value)
    assertEquals(8167.780658, portfolio.chart(PortfolioChartRange.ALL, PortfolioMetric.EQUITY)[1].value)
    assertEquals(3, portfolio.chart(PortfolioChartRange.WEEK_1, PortfolioMetric.EQUITY).size)
    assertEquals(3, portfolio.chart(PortfolioChartRange.MONTH_1, PortfolioMetric.PNL).size)
  }

  @Test
  fun mapsFeeRates() {
    val fees = decode<UserFees>(Fixtures.USER_FEES).toTradingFees()

    assertEquals(-0.00003, fees.perp!!.makerRate)
    assertEquals(0.00035, fees.perp!!.takerRate)
    assertEquals(0.0005, fees.spot!!.takerRate)
  }

  @Test
  fun listsEachTwapAtItsLatestStatus() {
    val twaps = decode<List<TwapHistoryEntry>>(Fixtures.TWAP_HISTORY_ENTRIES).latestTwaps()

    assertEquals(listOf("14656", "14654"), twaps.map { it.id })
    val latest = twaps.first()
    assertEquals("terminated", latest.status)
    assertEquals(658.6, latest.executedSize)
    assertEquals(2256.5, latest.totalSize)
    assertEquals(300L, latest.durationSeconds)
    assertEquals(30L, latest.frequencySeconds)
    assertFalse(latest.isBuy)
    assertEquals("@1035", twaps.last().marketId)
  }

  @Test
  fun mapsHlpAndAStakeInIt() {
    val details = decode<VaultDetails>(Fixtures.VAULT_DETAILS_FOLLOWER)
    val vault = details.toVaultSummary(isProtocolVault = true)

    assertEquals(Fixtures.HLP_VAULT, vault.address)
    assertEquals("Hyperliquidity Provider (HLP)", vault.name)
    assertEquals(4 * 24 * 60 * 60L, vault.lockupSeconds)
    assertEquals(0, vault.performanceFeeBps)
    assertEquals(116561392.6439010054, vault.totalValue, 1e-3)
    assertTrue(vault.isActive)
    assertEquals(24 * 60 * 60L, details.toVaultSummary(isProtocolVault = false).lockupSeconds)

    val stake = decode<List<VaultEquity>>(Fixtures.USER_VAULT_EQUITIES).single().toVaultPosition(vault, details)
    assertEquals(1070.102048, stake.value)
    assertEquals(stake.value, stake.shares)
    assertEquals(1765926140668L, stake.lockedUntilMs)
    assertEquals(71.1020487389 / (1070.102048 - 71.1020487389) * 100.0, stake.returnPercent, 1e-9)
  }

  @Test
  fun pagesTheLatestEntriesOfACappedHistory() = runTest {
    // One entry a minute for ten days, served like Hyperliquid: oldest first, at most 500.
    val now = 10L * 24 * 60 * 60 * 1000
    val times = (0 until 10 * 24 * 60).map { it * 60_000L }
    val fetch: suspend (Long, Long) -> List<Long> = { start, end ->
      times.filter { it in start..end }.take(500)
    }

    val first = latestPage(now, 50, 500, { it }, fetch)
    assertEquals(50, first.items.size)
    assertEquals(times.last(), first.items.first())
    assertTrue(first.hasMore)
    assertEquals(times[times.size - 50], first.oldestMs)

    val sparse = latestPage(now, 50, 500, { it }) { start, end -> listOf(0L, 60_000L).filter { it in start..end } }
    assertEquals(listOf(60_000L, 0L), sparse.items)
    assertFalse(sparse.hasMore)
  }

  @Test
  fun tellsSpotCoinsFromPerps() {
    assertEquals(MarketKind.SPOT, marketKindOf("@107"))
    assertEquals(MarketKind.SPOT, marketKindOf("PURR/USDC"))
    assertEquals(MarketKind.PERP, marketKindOf("BTC"))
    assertEquals(MarketKind.PERP, marketKindOf("osrs:DTEST"))
  }

  @Test
  fun theFeeTierIsSetByTheLastFourteenCompleteDays() {
    val fixture = decode<UserFees>(Fixtures.USER_FEES)
    val tier = fixture.toFeeTier()

    // Today (2026-09-29) is still running; only 2026-09-28 counts.
    assertEquals("Tier 0", tier.name)
    assertEquals(2301145.89, tier.volumeUsd, 1e-6)
    assertEquals(FeeRates(makerRate = -0.00003, takerRate = 0.00035), tier.perp)
    assertEquals(FeeRates(makerRate = -0.00003, takerRate = 0.0005), tier.spot)
    assertNull(tier.stakingDiscount)
    assertNull(tier.referralDiscount)

    val days = (1..16).map { DailyVolume("2026-09-${it.toString().padStart(2, '0')}", "600000.0", "400000.0", "0") }
    val busier =
      fixture.copy(
        dailyUserVlm = days,
        activeStakingDiscount = StakingDiscount("0.001", "0.1"),
        activeReferralDiscount = "0.04",
      )
    val tier1 = busier.toFeeTier()
    assertEquals(14_000_000.0, tier1.volumeUsd, 1e-6)
    assertEquals("Tier 1", tier1.name)
    assertEquals(0.1, tier1.stakingDiscount)
    assertEquals(0.04, tier1.referralDiscount)
  }

  @Test
  fun theReferralShowsTheOwnCodeTheReferrerAndWhatItEarned() {
    assertEquals(
      ReferralSummary(code = "FLARE", referredBy = "FRIEND", earnedUsd = 1.515443),
      decode<Referral>(Fixtures.REFERRAL).toReferralSummary(),
    )
    val unready =
      decode<Referral>("""{"referredBy":null,"cumVlm":"0","unclaimedRewards":"0","claimedRewards":"0","builderRewards":"0","referrerState":{"stage":"needToTrade","data":{"required":10000}}}""")
    assertEquals(ReferralSummary(earnedUsd = 0.0), unready.toReferralSummary())
  }

  @Test
  fun rewardsKeepWhatLoadedAndFailOnlyWhenNothingDid() {
    val tier = decode<UserFees>(Fixtures.USER_FEES).toFeeTier()
    val failed = Result.failure<ReferralSummary>(IllegalStateException("down"))

    val partial = hyperliquidRewards(Result.success(tier), failed)

    assertEquals(tier, partial.feeTier)
    assertEquals(setOf(RewardPart.REFERRAL), partial.unavailable)
    assertNull(partial.points)
    assertNull(partial.streak)
    assertFailsWith<IllegalStateException> {
      hyperliquidRewards(Result.failure(IllegalStateException("down")), failed)
    }
  }
}
