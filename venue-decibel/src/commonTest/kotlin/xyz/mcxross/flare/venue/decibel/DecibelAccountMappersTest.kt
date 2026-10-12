package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.model.AccountFees
import xyz.mcxross.flare.decibel.model.AccountVaultPerformance
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.Page
import xyz.mcxross.flare.decibel.model.PortfolioChartPoint
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.VaultInfo
import xyz.mcxross.flare.venue.model.AccountOverview
import xyz.mcxross.flare.venue.model.Balances
import xyz.mcxross.flare.venue.model.FeeRates
import xyz.mcxross.flare.venue.model.Fill
import xyz.mcxross.flare.venue.model.FillDirection
import xyz.mcxross.flare.venue.model.FundMovement
import xyz.mcxross.flare.venue.model.FundMovementKind
import xyz.mcxross.flare.venue.model.FundingPayment
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OpenOrder
import xyz.mcxross.flare.venue.model.OrderTrigger
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.RewardPart
import xyz.mcxross.flare.venue.model.RewardPointsSource
import xyz.mcxross.flare.venue.model.SpotBalance
import xyz.mcxross.flare.venue.model.SpotBalances
import xyz.mcxross.flare.venue.model.SpotReservation
import xyz.mcxross.flare.venue.model.TradingFees
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TradingKeyScope
import xyz.mcxross.flare.venue.model.TradingStreak
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.decibel.model.AccountOverview as DecibelAccountOverview
import xyz.mcxross.flare.decibel.model.FundMovement as DecibelFundMovement
import xyz.mcxross.flare.decibel.model.FundingPayment as DecibelFundingPayment
import xyz.mcxross.flare.decibel.model.MarketTrade as DecibelMarketTrade
import xyz.mcxross.flare.decibel.model.Position as DecibelPosition
import xyz.mcxross.flare.decibel.model.TradingStreak as DecibelTradingStreak
import xyz.mcxross.flare.decibel.model.TwapOrder as DecibelTwapOrder

// Shapes from :decibel's own fixtures where it has them (ServiceContractTest, DecibelJsonTest), and
// otherwise in the field names its account models declare.
class DecibelAccountMappersTest {
  private val json = DecibelClient.DefaultJson

  @Test
  fun anOverviewKeepsPerpAndSpotBalancesApart() {
    val overview =
      json
        .decodeFromString<DecibelAccountOverview>(
          """{"perp_equity_balance":1250.5,"unrealized_pnl":-12.25,""" +
            """"unrealized_funding_cost":0.5,""" +
            """"cross_margin_ratio":0.18,"maintenance_margin":40.0,"total_margin":200.0,""" +
            """"usdc_cross_withdrawable_balance":900.0,""" +
            """"usdc_isolated_withdrawable_balance":0.0,""" +
            """"cross_available_to_trade":950.0,"usdc_cross_balance":1262.75,""" +
            """"free_vault_equity":105.0,""" +
            """"spot":{"positions":[{"asset_addr":"0xa","asset_symbol":"APT","amount":10.0,""" +
            """"usd_value":58.66}],"in_flight_orders":[{"market_addr":"0x26f","order_id":"7",""" +
            """"is_bid":true,"reserved_asset":"0xusdc","reserved_amount":5.0,""" +
            """"reserved_usd_value":5.0}],"total_usd":63.66}}"""
        )
        .toAccountOverview()

    assertEquals(
      AccountOverview(
        equity = 1250.5,
        unrealizedPnl = -12.25,
        marginRatio = 0.18,
        availableToTrade = 950.0,
        withdrawable = 900.0,
        vaultEquity = 105.0,
        balances =
          Balances(
            collateral = 1262.75,
            spot =
              SpotBalances(
                assets = listOf(SpotBalance("0xa", "APT", 10.0, 58.66)),
                reserved = listOf(SpotReservation("0x26f", "7", true, "0xusdc", 5.0, 5.0)),
                totalUsd = 63.66,
              ),
            // Decibel's spot balances never back perpetuals, so nothing is counted twice.
            sharedWithEquity = 0.0,
          ),
      ),
      overview,
    )
  }

  @Test
  fun anOverviewWithoutSpotOrVaultsSaysSo() {
    // The minimal shape the account endpoint returns (as in WithdrawalFlowTest).
    val overview =
      json
        .decodeFromString<DecibelAccountOverview>(
          """{"perp_equity_balance":100,"unrealized_pnl":0,"unrealized_funding_cost":0,""" +
            """"cross_margin_ratio":0,"maintenance_margin":0,"total_margin":0,""" +
            """"usdc_cross_withdrawable_balance":100,"usdc_isolated_withdrawable_balance":0}"""
        )
        .toAccountOverview()

    assertNull(overview.balances.spot)
    assertNull(overview.balances.collateral)
    assertNull(overview.vaultEquity)
    assertEquals(100.0, overview.withdrawable)
  }

  @Test
  fun aPositionKeepsItsExactSignedSize() {
    val position =
      json
        .decodeFromString<DecibelPosition>(
          """{"market":"0x1","user":"0x2","size":-0.000000001,"user_leverage":10,""" +
            """"entry_price":100.0,"is_isolated":false,"is_deleted":false,""" +
            """"unrealized_funding":0.0,""" +
            """"estimated_liquidation_price":50.0,"transaction_version":1,""" +
            """"has_fixed_sized_tpsls":false,"tp_trigger_price":120.0,"sl_trigger_price":90.0}"""
        )
        .toPosition()

    assertEquals(
      Position(
        marketId = "0x1",
        size = "-0.000000001",
        leverage = 10,
        entryPrice = 100.0,
        isolated = false,
        unrealizedFunding = 0.0,
        liquidationPrice = 50.0,
        takeProfitTriggerPrice = 120.0,
        stopLossTriggerPrice = 90.0,
      ),
      position,
    )
    assertTrue(!position.isLong)
    assertEquals("0.000000001", position.absoluteSize)
  }

  @Test
  fun anOrderKeepsItsTriggersAndHistoryItsStatus() {
    val order =
      json.decodeFromString<Order>(
        """{"asset_type":"spot","parent":"","market":"0x26f","client_order_id":"c1",""" +
          """"order_id":"42","status":"Cancelled","order_type":"STOP_LIMIT",""" +
          """"trigger_condition":"mark >= 101","order_direction":"Open Long","is_buy":true,""" +
          """"is_reduce_only":true,"is_tpsl":true,"transaction_version":9,""" +
          """"unix_ms":1735758000000,""" +
          """"price":100.5,"orig_size":2.0,"remaining_size":1.5,"tp_trigger_price":110.0,""" +
          """"tp_limit_price":109.5,"sl_trigger_price":95.0}"""
      )

    val open = order.toOpenOrder()
    assertEquals(
      OpenOrder(
        marketId = "0x26f",
        orderId = "42",
        clientOrderId = "c1",
        kind = MarketKind.SPOT,
        isBuy = true,
        orderType = "STOP_LIMIT",
        direction = "Open Long",
        price = 100.5,
        originalSize = 2.0,
        remainingSize = 1.5,
        reduceOnly = true,
        isPositionTpSl = true,
        triggerCondition = "mark >= 101",
        takeProfit = OrderTrigger(110.0, 109.5),
        stopLoss = OrderTrigger(95.0, null),
        timeMs = 1735758000000,
      ),
      open,
    )
    val history = order.toOrderHistoryEntry()
    assertEquals(open, history.order)
    assertEquals("Cancelled", history.status)
  }

  @Test
  fun aPerpOrderWithoutExitsHasNone() {
    val order =
      json
        .decodeFromString<Order>(
          """{"market":"0x1","order_id":"1","is_buy":false,"transaction_version":1,"unix_ms":5}"""
        )
        .toOpenOrder()

    assertEquals(MarketKind.PERP, order.kind)
    assertNull(order.takeProfit)
    assertNull(order.stopLoss)
    assertNull(order.price)
  }

  @Test
  fun tradeHistoryBecomesFillsWithStableIds() {
    val page =
      json.decodeFromString<Page<DecibelMarketTrade>>(
        """{"items":[{"asset_type":"perp","account":"0x1","market":"0x2","action":"OpenLong",""" +
          """"source":"OrderFill","trade_id":"7","size":0.25,"price":50000.0,"is_profit":false,""" +
          """"realized_pnl_amount":0.0,"realized_funding_amount":0.0,"is_rebate":false,""" +
          """"fee_amount":0.1,"order_id":"9","client_order_id":"",""" +
          """"transaction_unix_ms":1735758000000,""" +
          """"transaction_version":42,"counter_party_account":"0x3"}],"total_count":1}"""
      )

    assertEquals(
      Fill(
        id = "7",
        marketId = "0x2",
        kind = MarketKind.PERP,
        direction = FillDirection.OPEN_LONG,
        action = "OpenLong",
        size = 0.25,
        price = 50000.0,
        realizedPnl = 0.0,
        fee = 0.1,
        timeMs = 1735758000000,
      ),
      page.items.single().toFill(),
    )
    // A fill without a trade id is told apart by its version, market, account, price and size.
    val untitled = page.items.single().copy(tradeId = "", action = "Liquidation").toFill()
    assertEquals("42:0x2:0x1:50000.0:0.25", untitled.id)
    assertEquals(FillDirection.OTHER, untitled.direction)
    assertEquals("Liquidation", untitled.action)
  }

  @Test
  fun fillDirectionsCoverDecibelsTradeActions() {
    val trade =
      DecibelMarketTrade(
        market = "0x2",
        action = "",
        size = 1.0,
        price = 1.0,
        transactionUnixMs = 1,
      )
    assertEquals(FillDirection.CLOSE_LONG, trade.copy(action = "CloseLong").toFill().direction)
    assertEquals(FillDirection.OPEN_SHORT, trade.copy(action = "OpenShort").toFill().direction)
    assertEquals(FillDirection.CLOSE_SHORT, trade.copy(action = "CloseShort").toFill().direction)
  }

  @Test
  fun fundingAndTransfersBecomeNeutralHistory() {
    val funding =
      json.decodeFromString<Page<DecibelFundingPayment>>(
        """{"items":[{"market":"0x1","action":"Close Long","size":1.0,""" +
          """"realized_funding_amount":-15.5,"is_rebate":false,"fee_amount":0.01,""" +
          """"transaction_unix_ms":1735758000000}],"total_count":7}"""
      )
    assertEquals(
      FundingPayment("0x1", "Close Long", 1.0, -15.5, 0.01, 1735758000000),
      funding.items.single().toFundingPayment(),
    )

    val transfers =
      json.decodeFromString<Page<DecibelFundMovement>>(
        """{"items":[{"timestamp":1735758000000,"type":"deposit","amount":500.0,""" +
          """"asset_symbol":"USDC","transaction_hash":"0xabc","status":"confirmed"}],""" +
          """"total_count":1}"""
      )
    val deposit = transfers.items.single()
    assertEquals(
      FundMovement(FundMovementKind.DEPOSIT, 500.0, "USDC", "confirmed", "0xabc", 1735758000000),
      deposit.toFundMovement(),
    )
    assertEquals(
      FundMovementKind.WITHDRAWAL,
      deposit.copy(type = "withdraw").toFundMovement().kind,
    )
  }

  @Test
  fun portfolioPointsUseWhicheverValueDecibelSent() {
    val documented =
      json.decodeFromString<List<PortfolioChartPoint>>(
        """[{"timestamp":1735758000000,"data_points":1250.5}]"""
      )
    assertEquals(
      PortfolioPoint(1735758000000, 1250.5),
      documented.single().toPortfolioPoint(PortfolioMetric.EQUITY),
    )
    val named =
      PortfolioChartPoint(timestamp = 1, value = 3.0, accountValue = 10.0, realizedPnl = 2.0)
    assertEquals(10.0, named.toPortfolioPoint(PortfolioMetric.EQUITY).value)
    assertEquals(2.0, named.toPortfolioPoint(PortfolioMetric.PNL).value)
    val plain = PortfolioChartPoint(timestamp = 1, value = 3.0)
    assertEquals(3.0, plain.toPortfolioPoint(PortfolioMetric.PNL).value)
    assertEquals("account_value", PortfolioMetric.EQUITY.decibelWireValue)
    assertEquals("pnl", PortfolioMetric.PNL.decibelWireValue)
  }

  @Test
  fun feesTwapsAndKeysBecomeNeutral() {
    val fees =
      json.decodeFromString<AccountFees>(
        """{"perp":{"user_maker_rate":0.0001,"user_taker_rate":0.00035}}"""
      )
    assertEquals(TradingFees(perp = FeeRates(0.0001, 0.00035)), fees.toTradingFees())

    val twap =
      json.decodeFromString<DecibelTwapOrder>(
        """{"market":"0x2","is_buy":false,"order_id":"t1","is_reduce_only":false,""" +
          """"start_unix_ms":1735758000000,"frequency_s":60,"duration_s":3600,""" +
          """"orig_size":10.0,"remaining_size":7.5,"status":"Activated",""" +
          """"client_order_id":"","transaction_unix_ms":1735758000000,""" +
          """"transaction_version":123}"""
      )
    assertEquals(TwapOrder("t1", "0x2", false, 10.0, 2.5, "Activated", 3600, 60), twap.toTwapOrder())

    val delegation =
      json.decodeFromString<Delegation>(
        """{"delegated_account":"0x33","expiration_time_s":1736326800,""" +
          """"permission_type":"TradePerpsAllMarkets","permission_market":null}"""
      )
    assertEquals(
      TradingKey("0x33", TradingKeyScope.PERP_MARKETS, null, 1736326800000),
      delegation.toTradingKey(),
    )
    assertEquals(
      TradingKeyScope.SPOT_MARKETS,
      delegation.copy(permissionType = "TradeSpotAllMarkets").toTradingKey().scope,
    )
    val oneMarket =
      delegation.copy(permissionType = "TradePerpMarket", permissionMarket = "0x1").toTradingKey()
    assertEquals(TradingKeyScope.ONE_MARKET, oneMarket.scope)
    assertEquals("0x1", oneMarket.marketId)
  }

  @Test
  fun vaultsKeepTheirLockupAndStakesTheirValue() {
    val vault =
      json.decodeFromString<VaultInfo>(
        """{"address":"0xdlp","name":"DLP","manager":"0xm","description":"Liquidity",""" +
          """"total_shares":1000.0,"share_price":1.05,"tvl":500000.0,"profit_share":10.0,""" +
          """"lockdown_period_s":86400,"status":"active"}"""
      )
    val summary = vault.toVaultSummary()
    assertEquals("0xdlp", summary.address)
    assertEquals(500_000.0, summary.totalValue)
    assertEquals(1.05, summary.sharePrice)
    assertEquals(1000, summary.performanceFeeBps)
    assertEquals(10.0, summary.performanceFeePercent)
    assertEquals(86_400L, summary.lockupSeconds)
    assertTrue(summary.isActive)
    // Decibel's zero lockup means none; a missing one is unknown.
    assertEquals(0L, vault.copy(lockdownPeriodS = 0).toVaultSummary().lockupSeconds)
    assertNull(vault.copy(lockdownPeriodS = null).toVaultSummary().lockupSeconds)

    val stake =
      json
        .decodeFromString<AccountVaultPerformance>(
          """{"vault":{"address":"0xdlp","name":"DLP","share_price":1.05},""" +
            """"current_num_shares":10000000,"current_value_of_shares":10.6,""" +
            """"all_time_return":5.0}"""
        )
        .toVaultPosition()
    // Decibel reports raw share units; ten shares are 10,000,000 of them.
    assertEquals(10.0, stake.shares)
    assertEquals(10.6, stake.value)
    assertEquals(5.0, stake.returnPercent)
    assertEquals(1.05, stake.sharePrice)
    assertNull(stake.lockedUntilMs)
  }

  @Test
  fun rewardsMarkWhatFailedAndListAmpsSourcesInOrder() {
    val streak =
      json.decodeFromString<DecibelTradingStreak>(
        """{"streak_count":5,"longest_streak":10,"grace_days_remaining":2,""" +
          """"qualifying_dates":["2026-09-20","2026-09-21"]}"""
      )
    val amps =
      json.decodeFromString<AmpsBreakdown>(
        """{"total_amps":1500.0,"trading_amps":1000.0,"referral_amps":500.0,"daily_amps":50.0,""" +
          """"rank":42}"""
      )
    val rewards =
      decibelRewards(
        streak = Result.success(streak),
        amps = Result.success(amps),
        tier = Result.failure(IllegalStateException("Offline")),
      )

    assertEquals(TradingStreak(days = 5, longestDays = 10, graceDaysRemaining = 2), rewards.streak)
    assertEquals(1500.0, rewards.points?.total)
    assertEquals(42, rewards.points?.rank)
    assertEquals(
      listOf(
        RewardPointsSource("Trading", 1000.0),
        RewardPointsSource("Streaks", 0.0),
        RewardPointsSource("Vaults", 0.0),
        RewardPointsSource("Referrals", 500.0),
        RewardPointsSource("Bonuses", 0.0),
      ),
      rewards.points?.sources,
    )
    assertNull(rewards.tier)
    assertEquals(setOf(RewardPart.TIER), rewards.unavailable)

    val tiered =
      decibelRewards(
        Result.success(null),
        Result.success(null),
        Result.success(TierInfo(tier = "Gold", percentile = 92.5, feeDiscountBps = 2)),
      )
    assertEquals("Gold", tiered.tier?.name)
    assertEquals(2, tiered.tier?.feeDiscountBps)
    assertTrue(tiered.unavailable.isEmpty())
  }
}
