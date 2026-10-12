package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.store.CandleEntity
import xyz.mcxross.flare.venue.model.ChartTimeframe

class HyperliquidChartAndDetailsTest {
  @Test
  fun everyTimeframeHasItsOwnIntervalAndAMonthIsACapitalM() {
    val wires = ChartTimeframe.entries.map { it.toCandleInterval().wireValue }

    assertEquals(wires.size, wires.toSet().size)
    assertEquals("1M", ChartTimeframe.ONE_MONTH.toCandleInterval().wireValue)
    assertEquals(ChartTimeframe.ONE_MONTH, chartTimeframeOf("1M"))
    assertEquals(ChartTimeframe.ONE_MINUTE, chartTimeframeOf("1m"))
    assertEquals(null, chartTimeframeOf("3d"))
  }

  @Test
  fun readsCandlesOldestFirstAndCachesThem() = runTest {
    val cache = MemoryMarketCache()
    val client = testClient(MarketResponses + ("candleSnapshot" to Fixtures.CANDLE_SNAPSHOT))
    val markets = HyperliquidMarketsRepository(client, memoryPreferences(), cache)
    val charts = HyperliquidChartRepository(client, markets, cache)

    val snapshot = charts.candles("BTC", ChartTimeframe.ONE_HOUR)

    assertFalse(snapshot.stale)
    assertEquals(3, snapshot.candles.size)
    val first = snapshot.candles.first()
    assertEquals(1791745200000L, first.openTimeMs)
    assertEquals(82607.0, first.open)
    assertEquals(82723.0, first.high)
    assertEquals(82533.0, first.low)
    assertEquals(82653.0, first.close)
    assertEquals(0.97791, first.volume)
    assertEquals(ChartTimeframe.ONE_HOUR, first.timeframe)
    assertEquals(3, cache.candles.count { it.marketAddress == "BTC" && it.interval == "1h" })
  }

  @Test
  fun fallsBackToCachedCandlesOffline() = runTest {
    val cache = MemoryMarketCache()
    val client = testClient(emptyMap())
    val charts = HyperliquidChartRepository(client, HyperliquidMarketsRepository(client, memoryPreferences(), cache), cache)
    val hourAgo = Clock.System.now().toEpochMilliseconds() - 3_600_000L
    cache.upsertCandles(listOf(CandleEntity("BTC", "1h", hourAgo, hourAgo + 3_599_999L, 1.0, 2.0, 0.5, 1.5, 3.0)))

    val snapshot = charts.candles("BTC", ChartTimeframe.ONE_HOUR)

    assertTrue(snapshot.stale)
    assertEquals(1, snapshot.candles.size)
    assertFailsWith<Exception> { charts.candles("ETH", ChartTimeframe.ONE_HOUR) }
  }

  @Test
  fun readsTheBookAsDecimalTextAndTradesNewestFirst() = runTest {
    val client =
      testClient(
        MarketResponses + mapOf("l2Book" to Fixtures.L2_BOOK, "recentTrades" to Fixtures.RECENT_TRADES)
      )
    val details =
      HyperliquidMarketDetailsRepository(client, HyperliquidMarketsRepository(client, memoryPreferences(), MemoryMarketCache()))

    details.refresh("BTC")

    val state = details.details.value
    assertEquals("BTC", state.market)
    assertFalse(state.stale)
    val book = state.orderBook!!
    assertEquals("83108.0", book.bestBid)
    assertEquals("83109.0", book.bestAsk)
    assertEquals(3, book.bids.size)
    assertEquals("0.3", book.asks.first().size)
    assertEquals(listOf(1791768444610L, 1791768443071L), state.recentTrades.map { it.timeMs })
    assertTrue(state.recentTrades.all { it.isBuy && it.marketId == "BTC" })
    assertEquals(83109.0, state.recentTrades.first().price)
    assertEquals(0.0002, state.recentTrades.first().size)
  }

  @Test
  fun aFailedRefreshKeepsTheMarketAndSaysSo() = runTest {
    val client = testClient(emptyMap())
    val details =
      HyperliquidMarketDetailsRepository(client, HyperliquidMarketsRepository(client, memoryPreferences(), MemoryMarketCache()))

    details.refresh("BTC")

    assertEquals("BTC", details.details.value.market)
    assertTrue(details.details.value.stale)
    assertTrue(details.details.value.error != null)
  }

  @Test
  fun neverStreamsACoinHyperliquidDoesNotList() = runTest {
    val client = testClient(MarketResponses)
    val details =
      HyperliquidMarketDetailsRepository(client, HyperliquidMarketsRepository(client, memoryPreferences(), MemoryMarketCache()))
    details.refresh("NOPE")

    details.connectLive("NOPE")

    assertEquals("Hyperliquid doesn’t list this market", details.details.value.error)
  }
}
