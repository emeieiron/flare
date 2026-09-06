package xyz.mcxross.flare.decibel.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixedPointTest {
  private val market =
    Market(
      assetType = AssetType.PERP,
      address = "0x1",
      name = "BTC/USD",
      sizeDecimals = 4,
      maxLeverage = 50,
      tickSize = 10u,
      minSize = 100u,
      lotSize = 10u,
      maxOpenInterest = 1_000_000.0,
      priceDecimals = 2,
      mode = "Open",
      unrealizedPnlHaircutBps = 1_000,
      category = "crypto",
      minPrice = 10u,
      maxPrice = 100_000_000u,
      isIsolatedOnly = false,
    )

  @Test
  fun decimalConversionNeverUsesFloatingPoint() {
    assertEquals(567_000uL, DecimalInput("5.67").toChainUnits("price", 5).getOrThrow())
    assertEquals("5.67", 567_000uL.toDecimalString(5))
  }

  @Test
  fun rejectsPrecisionAndLotViolations() {
    val result =
      OrderDraft(
          marketAddress = market.address,
          side = OrderSide.BUY,
          type = OrderType.LIMIT,
          size = DecimalInput("0.0101"),
          limitPrice = DecimalInput("100.01"),
        )
        .validate(market)

    assertFalse(result.isValid)
    assertTrue(result.errors.any { it is OrderValidationError.NotAligned })
  }

  @Test
  fun marketBuyRoundsSlippageOutwardToTick() {
    val result =
      OrderDraft(
          marketAddress = market.address,
          side = OrderSide.BUY,
          type = OrderType.MARKET,
          size = DecimalInput("0.0100"),
          slippage = SlippageBps(50u),
        )
        .validate(
          market,
          OrderBook(
            market.address,
            "0",
            bids = listOf(listOf("99.90", "1")),
            asks = listOf(listOf("100.00", "1")),
          ),
        )

    assertTrue(result.isValid)
    assertEquals(10_050uL, result.value?.price)
    assertEquals(TimeInForce.IMMEDIATE_OR_CANCEL, result.value?.timeInForce)
  }

  @Test
  fun marketOrderRequiresReliableBookSide() {
    val result =
      OrderDraft(
          marketAddress = market.address,
          side = OrderSide.SELL,
          type = OrderType.MARKET,
          size = DecimalInput("0.0100"),
        )
        .validate(market, null)

    assertFalse(result.isValid)
    assertIs<OrderValidationError.MissingMarketPrice>(result.errors.single())
  }

  @Test
  fun rejectsMetadataFromAnotherMarket() {
    val result =
      OrderDraft(
          marketAddress = "0x2",
          side = OrderSide.BUY,
          type = OrderType.LIMIT,
          size = DecimalInput("0.0100"),
          limitPrice = DecimalInput("100.00"),
        )
        .validate(market)

    assertFalse(result.isValid)
    assertTrue(result.errors.any { it is OrderValidationError.MarketMismatch })
  }

  @Test
  fun marketOrderRejectsBookFromAnotherMarket() {
    val result =
      OrderDraft(
          marketAddress = market.address,
          side = OrderSide.BUY,
          type = OrderType.MARKET,
          size = DecimalInput("0.0100"),
        )
        .validate(
          market,
          OrderBook(
            "0x2",
            "0",
            bids = listOf(listOf("99.90", "1")),
            asks = listOf(listOf("100.00", "1")),
          ),
        )

    assertFalse(result.isValid)
    assertTrue(result.errors.any { it is OrderValidationError.MarketMismatch })
  }

  @Test
  fun slippageOverflowIsReturnedAsTypedValidationError() {
    val maximalMarket =
      market.copy(
        sizeDecimals = 0,
        minSize = 1u,
        lotSize = 1u,
        priceDecimals = 0,
        tickSize = 1u,
        minPrice = 1u,
        maxPrice = ULong.MAX_VALUE,
      )
    val result =
      OrderDraft(
          marketAddress = maximalMarket.address,
          side = OrderSide.BUY,
          type = OrderType.MARKET,
          size = DecimalInput("1"),
        )
        .validate(
          maximalMarket,
          OrderBook(
            maximalMarket.address,
            "0",
            bids = listOf(listOf("1", "1")),
            asks = listOf(listOf(ULong.MAX_VALUE.toString(), "1")),
          ),
        )

    assertFalse(result.isValid)
    assertTrue(result.errors.any { it is OrderValidationError.Overflow })
  }

  @Test
  fun optionalTriggerPricesUseMarketBounds() {
    val result =
      OrderDraft(
          marketAddress = market.address,
          side = OrderSide.SELL,
          type = OrderType.LIMIT,
          size = DecimalInput("0.0100"),
          limitPrice = DecimalInput("100.00"),
          stopLossTriggerPrice = DecimalInput("0.00"),
        )
        .validate(market)

    assertFalse(result.isValid)
    assertTrue(
      result.errors.any {
        it is OrderValidationError.BelowMinimum && it.field == "stop-loss trigger"
      }
    )
  }
}
