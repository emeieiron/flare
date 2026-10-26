package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.strategies.StrategyMarketKey

class HyperliquidStrategyMarketFeedTest {
  private val now = 12_000_000L
  private val key = StrategyMarketKey("hyperliquid", "mainnet", "BTC", 60_000)

  @Test
  fun independentRequestBindsCoinIntervalTimeBoundsAndSourceBookTime() = runTest {
    val requested = mutableListOf<String>()
    val client = dynamicClient { type, body ->
      requested += type
      when (type) {
        "meta" -> """{"universe":[{"name":"BTC","szDecimals":5,"maxLeverage":40}]}"""
        "candleSnapshot" -> {
          val request = body.getValue("req").jsonObject
          assertEquals("BTC", request.getValue("coin").jsonPrimitive.content)
          assertEquals("1m", request.getValue("interval").jsonPrimitive.content)
          assertEquals("0", request.getValue("startTime").jsonPrimitive.content)
          assertEquals(now.toString(), request.getValue("endTime").jsonPrimitive.content)
          """[{"t":11940000,"T":11999999,"s":"BTC","i":"1m","o":"100.00","h":"101.00","l":"99.00","c":"100.50","v":"20.00"}]"""
        }
        "l2Book" -> """{"coin":"BTC","time":11999900,"levels":[[{"px":"100.00","sz":"0.0100","n":1}],[{"px":"100.50","sz":"0.0200","n":1}]]}"""
        else -> error("Unexpected request $type")
      }
    }
    val result = HyperliquidStrategyMarketFeed(client, HyperliquidVenue().descriptor, { now }).snapshot(key)
    assertEquals(key, result.key)
    assertEquals(now, result.fetchedAtMs)
    assertEquals(100.5, result.candles.single().close)
    assertEquals(11_999_900, result.book!!.observedAtMs)
    assertEquals("0.0100", result.book!!.bids.single().size)
    assertEquals(listOf("meta", "candleSnapshot", "l2Book"), requested)
  }

  @Test
  fun rejectsWrongBindingAndResponseCoinWhileUnavailableBookIsOptional() = runTest {
    val client = testClient(mapOf(
      "meta" to """{"universe":[{"name":"BTC","szDecimals":5,"maxLeverage":40}]}""",
      "candleSnapshot" to "[]",
    ))
    val feed = HyperliquidStrategyMarketFeed(client, HyperliquidVenue().descriptor, { now })
    assertFailsWith<IllegalArgumentException> { feed.snapshot(key.copy(network = "testnet")) }
    assertFailsWith<IllegalArgumentException> { feed.snapshot(key.copy(venueId = "decibel")) }
    assertFailsWith<IllegalStateException> { feed.snapshot(key.copy(timeframeMs = 7)) }
    assertNull(feed.snapshot(key).book)
    val wrong = testClient(mapOf(
      "meta" to """{"universe":[{"name":"BTC","szDecimals":5,"maxLeverage":40}]}""",
      "candleSnapshot" to """[{"t":11940000,"T":11999999,"s":"ETH","i":"1m","o":"100","h":"101","l":"99","c":"100","v":"20"}]""",
    ))
    assertFailsWith<IllegalArgumentException> {
      HyperliquidStrategyMarketFeed(wrong, HyperliquidVenue().descriptor, { now }).snapshot(key)
    }
  }
}
