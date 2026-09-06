package xyz.mcxross.flare.decibel.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import xyz.mcxross.flare.decibel.model.CandleInterval
import xyz.mcxross.kaptos.model.AccountAddress

class StreamTopicsTest {
  private val address = AccountAddress.fromString("0x1").toString()

  @Test
  fun constructsCanonicalMarketTopics() {
    assertEquals("depth:$address:10", MarketDepth("0x1", DepthAggregation.TEN).wireValue)
    assertEquals("trades:$address", MarketTrades("0x1").wireValue)
    assertEquals("market_price:$address", MarketPriceTopic("0x1").wireValue)
    assertEquals(
      "market_candlestick:$address:4h",
      MarketCandlestick("0x1", CandleInterval.FOUR_HOURS).wireValue,
    )
  }

  @Test
  fun constructsAccountTopicsBoundToCanonicalAddress() {
    assertEquals("account_overview:$address", AccountOverviewTopic("0x1").wireValue)
    assertEquals("account_positions:$address", AccountPositions("0x1").wireValue)
    assertEquals("account_open_orders:$address", AccountOpenOrders("0x1").wireValue)
    assertEquals("order_updates:$address", OrderUpdates("0x1").wireValue)
    assertEquals("user_trades:$address", UserTrades("0x1").wireValue)
  }

  @Test
  fun rejectsMalformedTopicAddressAtConstruction() {
    assertFailsWith<IllegalArgumentException> { MarketTrades("not-an-address") }
  }
}
