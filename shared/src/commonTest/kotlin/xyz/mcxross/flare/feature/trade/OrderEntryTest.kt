package xyz.mcxross.flare.feature.trade

import kotlin.test.*
import xyz.mcxross.flare.data.MarketDetails
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.decibel.model.*

class OrderEntryTest {
  private val market =
    Market(
      AssetType.PERP,
      "0x123",
      "BTC/USD",
      8,
      40,
      100uL,
      2000uL,
      1000uL,
      1_000_000.0,
      2,
      "Open",
      0,
      "crypto",
      1uL,
      100_000_000uL,
      false,
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
    val order = assertNotNull(state.orderDraft(OrderSide.BUY).validate(market).value)
    assertEquals(7_200_000uL, order.takeProfitTriggerPrice)
    assertEquals(order.takeProfitTriggerPrice, order.takeProfitLimitPrice)
    assertEquals(6_900_000uL, order.stopLossTriggerPrice)
    assertEquals(order.stopLossTriggerPrice, order.stopLossLimitPrice)
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
    assertNotNull(state().copy(takeProfitInput = "70000").orderInputError(OrderSide.BUY))
    assertNotNull(state().copy(stopLossInput = "71000").orderInputError(OrderSide.BUY))
    assertNotNull(state().copy(stopLossInput = "0").orderInputError(OrderSide.BUY))
    assertNotNull(state().copy(takeProfitInput = "72000.001").orderInputError(OrderSide.BUY))
    assertNotNull(state().copy(takeProfitInput = "72000.50").orderInputError(OrderSide.BUY))
    assertNotNull(state().copy(sizeInput = "0.00001").orderInputError(OrderSide.BUY))
  }

  @Test
  fun optionalExitsStayAbsentAndMarketEstimatesUseTheCorrectBookSide() {
    val book = OrderBook("0x123", "0", listOf(listOf("69990", "1")), listOf(listOf("70010", "1")))
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
    val order = assertNotNull(state.orderDraft(OrderSide.BUY).validate(market, book).value)
    assertNull(order.takeProfitTriggerPrice)
    assertNull(order.stopLossTriggerPrice)
    assertNull(state.orderInputError(OrderSide.BUY))
  }

  @Test
  fun marketEntryKeepsAttachedExitsAndInvalidEstimatesNeverBecomeZero() {
    val book = OrderBook("0x123", "0", listOf(listOf("69990", "1")), listOf(listOf("70010", "1")))
    val state =
      state().copy(orderType = OrderType.MARKET, marketDetails = MarketDetails(orderBook = book))
    val order = assertNotNull(state.orderDraft(OrderSide.BUY).validate(market, book).value)
    assertEquals(7_200_000uL, order.takeProfitTriggerPrice)
    assertEquals(6_900_000uL, order.stopLossTriggerPrice)
    assertNull(state.copy(sizeInput = "").orderEstimate(OrderSide.BUY))
    assertNull(state.copy(marketDetails = MarketDetails()).orderEstimate(OrderSide.BUY))
  }
}
