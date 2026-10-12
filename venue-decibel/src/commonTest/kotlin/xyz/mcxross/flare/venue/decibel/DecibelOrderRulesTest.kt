package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.MarketPrecision
import xyz.mcxross.flare.decibel.model.OrderDraft
import xyz.mcxross.flare.decibel.model.SlippageBps
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderBookLevel
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TimeInForce
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.TwapSchedule
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.flare.decibel.model.Market as DecibelMarket
import xyz.mcxross.flare.decibel.model.OrderBook as DecibelOrderBook
import xyz.mcxross.flare.decibel.model.OrderSide as DecibelOrderSide
import xyz.mcxross.flare.decibel.model.OrderType as DecibelOrderType
import xyz.mcxross.flare.decibel.model.TimeInForce as DecibelTimeInForce
import xyz.mcxross.flare.decibel.model.validate as decibelValidate

class DecibelOrderRulesTest {
  private val rules =
    DecibelOrderRules(
      marketId = MARKET,
      name = "BTC/USD",
      kind = AssetType.PERP,
      precision =
        MarketPrecision(
          priceDecimals = 2,
          sizeDecimals = 8,
          tickSize = 100uL,
          lotSize = 1000uL,
          minimumSize = 2000uL,
          minimumPrice = 1uL,
          maximumPrice = 100_000_000uL,
        ),
    )
  private val market =
    Market(
      id = MARKET,
      symbol = "BTC",
      displayName = "BTC/USD",
      kind = MarketKind.PERP,
      category = "crypto",
      priceDecimals = 2,
      sizeDecimals = 8,
      maxLeverage = 40,
      isolatedOnly = false,
      rules = rules,
    )
  private val book =
    OrderBook(MARKET, listOf(OrderBookLevel("69990", "1")), listOf(OrderBookLevel("70010", "1")))
  private val limit =
    OrderRequest(
      marketId = MARKET,
      side = OrderSide.BUY,
      type = OrderType.LIMIT,
      size = "0.001",
      price = "70000",
      takeProfitTrigger = "72000",
      takeProfitLimit = "72000",
      stopLossTrigger = "69000",
      stopLossLimit = "69000",
    )

  @Test
  fun attachedExitsReachTheChainInExactUnits() {
    val checked = assertIs<OrderCheck.Valid>(rules.check(limit)).order
    val order = rules.validated(checked)
    assertEquals(7_000_000uL, order.price)
    assertEquals(100_000uL, order.size)
    assertEquals(7_200_000uL, order.takeProfitTriggerPrice)
    assertEquals(order.takeProfitTriggerPrice, order.takeProfitLimitPrice)
    assertEquals(6_900_000uL, order.stopLossTriggerPrice)
    assertEquals(order.stopLossTriggerPrice, order.stopLossLimitPrice)
    assertEquals(DecibelTimeInForce.GOOD_TILL_CANCELLED, order.timeInForce)
  }

  @Test
  fun stepsAndMinimumsReadInTheMarketsOwnUnits() {
    assertEquals(
      listOf("Take profit must be in steps of $1"),
      assertIs<OrderCheck.Invalid>(rules.check(limit.copy(takeProfitTrigger = "72000.50"))).errors,
    )
    assertEquals(
      listOf("Minimum size is 0.00002 BTC", "Size must be in steps of 0.00001 BTC"),
      assertIs<OrderCheck.Invalid>(rules.check(limit.copy(size = "0.000015"))).errors,
    )
    assertEquals(
      listOf("Size can have at most 8 decimals"),
      assertIs<OrderCheck.Invalid>(rules.check(limit.copy(size = "0.000000001"))).errors,
    )
    assertEquals(
      listOf("A reliable best bid and ask are required"),
      assertIs<OrderCheck.Invalid>(rules.check(limit.copy(type = OrderType.MARKET, price = null)))
        .errors,
    )
    assertEquals("0.00002", rules.minimumSize)
  }

  @Test
  fun aCheckedMarketOrderPlacesExactlyWhatDecibelValidates() {
    val request =
      limit.copy(type = OrderType.MARKET, price = null, slippageBps = 50, reduceOnly = true)
    val checked = assertIs<OrderCheck.Valid>(rules.check(request, book)).order
    assertEquals(TimeInForce.IMMEDIATE_OR_CANCEL, checked.timeInForce)
    val direct =
      OrderDraft(
          marketAddress = MARKET,
          side = DecibelOrderSide.BUY,
          type = DecibelOrderType.MARKET,
          size = DecimalInput("0.001"),
          reduceOnly = true,
          slippage = SlippageBps(50u),
          takeProfitTriggerPrice = DecimalInput("72000"),
          takeProfitLimitPrice = DecimalInput("72000"),
          stopLossTriggerPrice = DecimalInput("69000"),
          stopLossLimitPrice = DecimalInput("69000"),
        )
        .decibelValidate(
          DecibelMarket(
            assetType = AssetType.PERP,
            address = MARKET,
            name = "BTC/USD",
            sizeDecimals = 8,
            maxLeverage = 40,
            tickSize = 100uL,
            minSize = 2000uL,
            lotSize = 1000uL,
            maxOpenInterest = 0.0,
            priceDecimals = 2,
            mode = "",
            unrealizedPnlHaircutBps = 0,
            category = "crypto",
            minPrice = 1uL,
            maxPrice = 100_000_000uL,
            isIsolatedOnly = false,
          ),
          DecibelOrderBook(MARKET, "", listOf(listOf("69990", "1")), listOf(listOf("70010", "1"))),
        )
        .value
    assertEquals(direct, rules.validated(checked))
    // 70010 plus 0.5%, rounded up to the $1 tick.
    assertEquals("70361", checked.price)
    val placed =
      assertIs<DecibelCommand.PlaceOrder>(
        TradeAction.PlaceOrder(ACCOUNT, market, checked).toDecibelCommand(USDC)
      )
    assertEquals(direct, placed.order)
  }

  @Test
  fun anApprovedBuilderFeeIsSentInDecibelUnitsAndAZeroFeeIsDropped() {
    val checked =
      assertIs<OrderCheck.Valid>(rules.check(limit.copy(builderAddress = "0x987", builderFeeBps = 5)))
        .order
    val order = rules.validated(checked)
    assertEquals(AccountAddress.fromString("0x987").toStringLong(), order.builderAddress)
    assertEquals(500uL, order.builderFeeUnits)
    val unpaid = rules.validated(limit.copy(builderAddress = "0x987", builderFeeBps = 0))
    assertNull(unpaid.builderAddress)
    assertNull(unpaid.builderFeeUnits)
    assertEquals(
      listOf("Builder fee: Builder fee cannot exceed 10 bps (0.10%)"),
      assertIs<OrderCheck.Invalid>(rules.check(limit.copy(builderAddress = "0x987", builderFeeBps = 11)))
        .errors,
    )
  }

  @Test
  fun twapOrdersKeepTheirScheduleAndSpotOrdersUseTheSpotEntry() {
    val twap =
      limit.copy(
        type = OrderType.TWAP,
        price = null,
        takeProfitTrigger = null,
        takeProfitLimit = null,
        stopLossTrigger = null,
        stopLossLimit = null,
        twap = TwapSchedule(frequencySeconds = 120, durationSeconds = 1800),
      )
    val checked = assertIs<OrderCheck.Valid>(rules.check(twap)).order
    val placed =
      assertIs<DecibelCommand.PlaceTwapOrder>(
        TradeAction.PlaceOrder(ACCOUNT, market, checked).toDecibelCommand(USDC)
      )
    assertEquals(100_000uL, placed.order.size)
    assertEquals(120uL, placed.order.frequencySeconds)
    assertEquals(1800uL, placed.order.durationSeconds)

    val spotRules = rules.copy(kind = AssetType.SPOT)
    val spot = market.copy(kind = MarketKind.SPOT, rules = spotRules)
    val spotOrder = assertIs<OrderCheck.Valid>(spotRules.check(limit)).order
    assertIs<DecibelCommand.PlaceSpotOrder>(
      TradeAction.PlaceOrder(ACCOUNT, spot, spotOrder).toDecibelCommand(USDC)
    )
  }

  @Test
  fun aTwapOnSpotChecksTheScheduleAndPlacesAtTheBooksPrice() {
    val spotRules = rules.copy(kind = AssetType.SPOT)
    val spot = market.copy(kind = MarketKind.SPOT, rules = spotRules)
    val twap =
      OrderRequest(
        marketId = MARKET,
        side = OrderSide.BUY,
        type = OrderType.TWAP,
        size = "0.001",
        twap = TwapSchedule(frequencySeconds = 60, durationSeconds = 3600),
      )

    val checked = assertIs<OrderCheck.Valid>(spotRules.check(twap, book)).order
    assertEquals("70010", checked.price)
    val placed =
      assertIs<DecibelCommand.PlaceSpotOrder>(
        TradeAction.PlaceOrder(ACCOUNT, spot, checked).toDecibelCommand(USDC)
      )
    assertEquals(7_001_000uL, placed.order.price)
    assertEquals(100_000uL, placed.order.size)
    assertEquals(
      listOf("Minimum size is 0.00002 BTC"),
      assertIs<OrderCheck.Invalid>(spotRules.check(twap.copy(size = "0.00001"), book)).errors,
    )
  }

  @Test
  fun positionExitsAreCheckedAgainstTheTick() {
    assertNull(rules.checkExitPrices("72000", null))
    assertEquals("TP/SL prices must align to tick size 100", rules.checkExitPrices("72000.5", null))
    assertEquals(
      "TP/SL price supports at most 2 decimal places",
      rules.checkExitPrices(null, "69000.001"),
    )
    val command =
      assertIs<DecibelCommand.SetPositionTpSl>(
        TradeAction.SetPositionTpSl(
            ACCOUNT,
            market,
            takeProfitTrigger = "72000",
            takeProfitLimit = "72000",
          )
          .toDecibelCommand(USDC)
      )
    assertEquals(7_200_000uL, command.takeProfitTrigger)
    assertEquals(7_200_000uL, command.takeProfitLimit)
    assertNull(command.stopLossTrigger)
  }

  @Test
  fun fundsMoveInUsdcUnitsAndPerpSpotMovesAreUnsupported() {
    assertEquals(
      DecibelCommand.Withdraw(ACCOUNT, USDC, 1_500_000uL),
      TradeAction.Withdraw(ACCOUNT, "1.5").toDecibelCommand(USDC),
    )
    assertEquals(
      DecibelCommand.ApproveMaxSpotBuilderFee(ACCOUNT, "0xb", 1_000uL),
      TradeAction.ApproveBuilderFee(ACCOUNT, "0xb", 10, MarketKind.SPOT).toDecibelCommand(USDC),
    )
    assertFailsWith<IllegalArgumentException> {
      TradeAction.Deposit(ACCOUNT, "0").toDecibelCommand(USDC)
    }
    assertFailsWith<IllegalStateException> {
      TradeAction.MovePerpSpot(ACCOUNT, "1", toSpot = true).toDecibelCommand(USDC)
    }
  }

  private companion object {
    const val MARKET = "0x123"
    const val ACCOUNT = "0xabc"
    const val USDC = "0xusdc"
  }
}
