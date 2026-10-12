package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderBookLevel
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TimeInForce
import xyz.mcxross.flare.venue.model.TwapSchedule

class HyperliquidOrderRulesTest {
  private val btc = HyperliquidOrderRules("BTC", "BTC", MarketKind.PERP, szDecimals = 5)
  private val sol = HyperliquidOrderRules("SOL", "SOL", MarketKind.PERP, szDecimals = 2)
  private val hype = HyperliquidOrderRules("@1035", "HYPE", MarketKind.SPOT, szDecimals = 2)
  private val gmt = HyperliquidOrderRules("GMT", "GMT", MarketKind.PERP, szDecimals = 0)
  private val book =
    OrderBook("BTC", bids = listOf(OrderBookLevel("83108.0", "1")), asks = listOf(OrderBookLevel("83109.0", "1")))

  @Test
  fun pricesAllowFiveSignificantFiguresAndTheMarketsDecimals() {
    assertEquals(1, btc.maxPriceDecimals)
    assertEquals(4, sol.maxPriceDecimals)
    assertEquals(6, hype.maxPriceDecimals)
    assertValid(btc.check(limit("0.001", "83108")))
    // An integer price is always accepted, however many figures it has.
    assertValid(btc.check(limit("0.001", "123456")))
    assertInvalid(btc.check(limit("0.001", "83108.5")), "Price can have at most 5 significant figures and 1 decimals")
    assertValid(sol.check(limit("10", "1.2345")))
    assertInvalid(sol.check(limit("100000", "0.00012")), "Price can have at most 5 significant figures and 4 decimals")
    assertValid(hype.check(limit("100000", "0.000123")))
  }

  @Test
  fun sizesKeepTheAssetsDecimals() {
    assertInvalid(btc.check(limit("0.000001", "83108")), "Size can have at most 5 decimals")
    assertInvalid(gmt.check(limit("1.5", "10")), "Size must be a whole number of GMT")
    assertInvalid(btc.check(limit("0", "83108")), "Enter a size")
    assertInvalid(btc.check(limit("1e3", "83108")), "Enter a valid size")
    assertNull(btc.minimumSize)
  }

  @Test
  fun ordersAreWorthAtLeastTenDollarsUnlessTheyOnlyReduce() {
    assertInvalid(btc.check(limit("0.0001", "83108")), "Minimum order value is $10")
    assertValid(btc.check(limit("0.0001", "83108").copy(reduceOnly = true)))
    assertValid(btc.check(limit("0.00013", "83108")))
  }

  @Test
  fun aMarketOrderIsAnImmediateLimitAtTheSlippageBound() {
    val buy = assertValid(btc.check(market(OrderSide.BUY, "0.001"), book))
    // 83109 × 1.005 = 83524.545, rounded within the bound to five figures.
    assertEquals("83524", buy.price)
    assertEquals(TimeInForce.IMMEDIATE_OR_CANCEL, buy.timeInForce)
    val sell = assertValid(btc.check(market(OrderSide.SELL, "0.001"), book))
    // 83108 × 0.995 = 82692.46.
    assertEquals("82693", sell.price)
    assertInvalid(btc.check(market(OrderSide.BUY, "0.001"), null), "A reliable best bid and ask are required")
    assertInvalid(sol.check(market(OrderSide.BUY, "1"), book), "A reliable best bid and ask are required")
  }

  @Test
  fun aCheckedOrderCarriesItsCanonicalDecimals() {
    val order = assertValid(btc.check(limit("0.00100", "83108.0").copy(takeProfitTrigger = "90000.0")))

    assertEquals("0.001", order.size)
    assertEquals("83108", order.price)
    assertEquals("90000", order.takeProfitTrigger)
  }

  @Test
  fun exitPricesFollowThePriceRules() {
    assertNull(btc.checkExitPrices("90000", "80000"))
    assertEquals(
      "Take profit can have at most 5 significant figures and 1 decimals",
      btc.checkExitPrices("90000.55", null),
    )
    assertEquals("Stop loss: enter a valid price", btc.checkExitPrices(null, "-1"))
  }

  @Test
  fun aTwapRunsForWholeMinutesUpToADay() {
    val twap = OrderRequest("BTC", OrderSide.BUY, OrderType.TWAP, "0.5", twap = TwapSchedule(30, 1_800))

    assertEquals("0.5", assertValid(btc.check(twap)).size)
    assertInvalid(btc.check(twap.copy(twap = TwapSchedule(30, 180))), "A TWAP runs for 5 minutes to 24 hours, in whole minutes")
    assertInvalid(btc.check(twap.copy(twap = TwapSchedule(30, 90_000))), "A TWAP runs for 5 minutes to 24 hours, in whole minutes")
  }

  private fun limit(size: String, price: String) =
    OrderRequest("BTC", OrderSide.BUY, OrderType.LIMIT, size, price = price)

  private fun market(side: OrderSide, size: String) =
    OrderRequest("BTC", side, OrderType.MARKET, size, slippageBps = 50)

  private fun assertValid(check: OrderCheck): OrderRequest = assertIs<OrderCheck.Valid>(check).order

  private fun assertInvalid(check: OrderCheck, message: String) =
    assertEquals(listOf(message), assertIs<OrderCheck.Invalid>(check).errors)
}
