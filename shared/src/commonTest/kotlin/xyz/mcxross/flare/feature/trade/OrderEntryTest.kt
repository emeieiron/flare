package xyz.mcxross.flare.feature.trade

import kotlin.test.*
import xyz.mcxross.flare.data.MarketDetails
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.testing.AcceptingOrderRules
import xyz.mcxross.flare.venue.model.FeeRates
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderBookLevel
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TradingFees

class OrderEntryTest {
  private val market =
    Market(
      id = "0x123",
      symbol = "BTC",
      displayName = "BTC/USD",
      kind = MarketKind.PERP,
      category = "crypto",
      priceDecimals = 2,
      sizeDecimals = 8,
      maxLeverage = 40,
      isolatedOnly = false,
      rules = AcceptingOrderRules,
    )

  private fun state() =
    TradeUiState(
      quote = MarketQuote(market, 70_000.0, 0.0, 0.0, 0.0),
      orderType = OrderType.LIMIT,
      sizeInput = "0.001",
      limitPriceInput = "70000",
      takeProfitInput = "72000",
      stopLossInput = "69000",
    )

  @Test
  fun longReviewShowsProfitAndLossAndIncludesBothExitsInTheEntry() {
    val state = state()
    val estimate = assertNotNull(state.orderEstimate(OrderSide.BUY))
    assertEquals(70.0, estimate.value)
    assertEquals(2.0, estimate.profit)
    assertEquals(-1.0, estimate.loss)
    val order = state.checkedOrder(OrderSide.BUY)
    assertEquals("72000", order.takeProfitTrigger)
    assertEquals(order.takeProfitTrigger, order.takeProfitLimit)
    assertEquals("69000", order.stopLossTrigger)
    assertEquals(order.stopLossTrigger, order.stopLossLimit)
  }

  @Test
  fun shortsReverseThePriceDirectionWithoutMultiplyingByLeverage() {
    val state = state().copy(takeProfitInput = "68000", stopLossInput = "71000")
    assertNull(state.orderInputError(OrderSide.SELL))
    val estimate = assertNotNull(state.orderEstimate(OrderSide.SELL))
    assertEquals(2.0, estimate.profit)
    assertEquals(-1.0, estimate.loss)
    assertNotNull(state.orderInputError(OrderSide.BUY))
  }

  @Test
  fun wrongSideEqualEntryAndInvalidPrecisionAreRejectedBeforeReview() {
    assertEquals(
      "Take profit must be above the entry price",
      state().copy(takeProfitInput = "70000").orderInputError(OrderSide.BUY),
    )
    assertNotNull(state().copy(stopLossInput = "71000").orderInputError(OrderSide.BUY))
    assertEquals(
      "Stop loss must be greater than zero",
      state().copy(stopLossInput = "0").orderInputError(OrderSide.BUY),
    )
    assertEquals(
      "Take profit supports at most 2 decimal places",
      state().copy(takeProfitInput = "72000.001").orderInputError(OrderSide.BUY),
    )
    assertEquals("Enter an order size", state().copy(sizeInput = "").orderInputError(OrderSide.BUY))
  }

  @Test
  fun optionalExitsStayAbsentAndMarketEstimatesUseTheCorrectBookSide() {
    val book = OrderBook("0x123", listOf(OrderBookLevel("69990", "1")), listOf(OrderBookLevel("70010", "1")))
    val state =
      state()
        .copy(
          orderType = OrderType.MARKET,
          takeProfitInput = "",
          stopLossInput = "",
          marketDetails = MarketDetails(orderBook = book, stale = false),
        )
    assertEquals(70010.0, state.orderEstimate(OrderSide.BUY)?.entryPrice)
    assertEquals(69990.0, state.orderEstimate(OrderSide.SELL)?.entryPrice)
    assertNull(state.orderEstimate(OrderSide.BUY)?.profit)
    assertNull(state.orderEstimate(OrderSide.BUY)?.loss)
    val order = state.checkedOrder(OrderSide.BUY)
    assertEquals("70010", order.price)
    assertNull(order.takeProfitTrigger)
    assertNull(order.stopLossTrigger)
    assertNull(state.orderInputError(OrderSide.BUY))
  }

  @Test
  fun marketEntryKeepsAttachedExitsAndInvalidEstimatesNeverBecomeZero() {
    val book = OrderBook("0x123", listOf(OrderBookLevel("69990", "1")), listOf(OrderBookLevel("70010", "1")))
    val state =
      state().copy(orderType = OrderType.MARKET, marketDetails = MarketDetails(orderBook = book))
    val order = state.orderRequest(OrderSide.BUY)
    assertEquals("72000", order.takeProfitTrigger)
    assertEquals("69000", order.stopLossTrigger)
    assertNull(state.copy(sizeInput = "").orderEstimate(OrderSide.BUY))
    assertNull(state.copy(marketDetails = MarketDetails()).orderEstimate(OrderSide.BUY))
  }

  @Test
  fun builderFeeCalculatedWhenApprovedAndPositive() {
    val state =
      state()
        .copy(
          builderApproved = true,
          builderFeeBps = 5,
          builderAddress = "0x987",
        )
    val estimate = assertNotNull(state.orderEstimate(OrderSide.BUY))
    assertEquals(5, estimate.builderFeeBps)
    assertEquals(0.035, estimate.builderFeeAmount)

    val order = state.orderRequest(OrderSide.BUY)
    assertEquals("0x987", order.builderAddress)
    assertEquals(5, order.builderFeeBps)
  }

  @Test
  fun builderFeeOmittedWhenNotApprovedOrZero() {
    val unapproved =
      state()
        .copy(
          builderApproved = false,
          builderFeeBps = 5,
          builderAddress = "0x987",
        )
    val unapprovedEstimate = assertNotNull(unapproved.orderEstimate(OrderSide.BUY))
    assertNull(unapprovedEstimate.builderFeeBps)
    assertNull(unapprovedEstimate.builderFeeAmount)
    val unapprovedOrder = unapproved.orderRequest(OrderSide.BUY)
    assertNull(unapprovedOrder.builderAddress)
    assertNull(unapprovedOrder.builderFeeBps)

    val zeroFee =
      state()
        .copy(
          builderApproved = true,
          builderFeeBps = 0,
          builderAddress = "0x987",
        )
    val zeroEstimate = assertNotNull(zeroFee.orderEstimate(OrderSide.BUY))
    assertNull(zeroEstimate.builderFeeBps)
    assertNull(zeroEstimate.builderFeeAmount)
    val zeroOrder = zeroFee.orderRequest(OrderSide.BUY)
    assertNull(zeroOrder.builderAddress)
    assertNull(zeroOrder.builderFeeBps)
  }

  @Test
  fun aPerpOrderNeedsTheMarginItUsesBeforeReview() {
    // 0.001 BTC at 70,000 and 1× needs $70 of margin.
    val unfunded = state().copy(quoteBalance = 0.0)
    assertEquals(
      "Not enough margin for this order. Available \$0.00",
      unfunded.orderInputError(OrderSide.BUY),
    )
    assertNull(state().copy(quoteBalance = 70.0).orderInputError(OrderSide.BUY))
    assertNull(state().copy(quoteBalance = 20.0, leverage = 4).orderInputError(OrderSide.BUY))
    assertNotNull(
      state().copy(quoteBalance = 70.0, fees = TradingFees(perp = FeeRates(0.0, 0.001)))
        .orderInputError(OrderSide.BUY)
    )
    // An unknown balance never blocks the ticket.
    assertNull(state().orderInputError(OrderSide.BUY))
  }

  @Test
  fun closingAnOppositePositionNeedsNoNewMargin() {
    val short = state().copy(quoteBalance = 0.0, positionSize = "-0.001", takeProfitInput = "", stopLossInput = "")
    assertNull(short.orderInputError(OrderSide.BUY))
    assertNotNull(short.copy(sizeInput = "0.002").orderInputError(OrderSide.BUY))
    assertNotNull(short.orderInputError(OrderSide.SELL))
  }

  @Test
  fun aSpotOrderStaysWithinWhatTheAccountHolds() {
    val apt =
      market.copy(id = "0xapt", symbol = "APT", displayName = "APT/USDC", kind = MarketKind.SPOT, maxLeverage = 1)
    val spot =
      TradeUiState(
        quote = MarketQuote(apt, 0.8415, 0.0, 0.0, 0.0),
        orderType = OrderType.LIMIT,
        sizeInput = "10",
        limitPriceInput = "0.8415",
        quoteBalance = 5.0,
        baseBalance = 9.993,
      )

    assertEquals("You have 9.993 APT to sell", spot.orderInputError(OrderSide.SELL))
    assertNull(spot.copy(sizeInput = "9.993").orderInputError(OrderSide.SELL))
    assertEquals(
      "Not enough USDC for this order. Available \$5.00",
      spot.orderInputError(OrderSide.BUY),
    )
    assertNull(spot.copy(quoteBalance = 8.415).orderInputError(OrderSide.BUY))
  }
}
