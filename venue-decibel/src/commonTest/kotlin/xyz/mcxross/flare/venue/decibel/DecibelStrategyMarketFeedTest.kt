package xyz.mcxross.flare.venue.decibel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.strategies.StrategyMarketKey
import xyz.mcxross.kaptos.Aptos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import xyz.mcxross.flare.decibel.model.Candle

class DecibelStrategyMarketFeedTest {
  @Test
  fun isolatedCandleRequestKeepsOriginalObservationCutoffAfterSlowBook() = runTest {
    var clock = 12_000_000L
    val requested = mutableListOf<String>()
    val http = HttpClient(MockEngine { request ->
      val path = request.url.encodedPath.substringAfterLast('/')
      requested += path
      val response = when (path) {
        "markets" -> """[{"asset_type":"perp","market_addr":"0x1","market_name":"BTC/USD","sz_decimals":9,"max_leverage":50,"tick_size":1000000,"min_size":100000000,"lot_size":100000000,"max_open_interest":1000000.0,"px_decimals":9,"mode":"Open","unrealized_pnl_haircut_bps":1000,"category":"crypto","min_price":1,"max_price":90000000000000,"is_isolated_only":false}]"""
        "candlesticks" -> {
          assertEquals("0x1", request.url.parameters["market"])
          assertEquals("1m", request.url.parameters["interval"])
          assertEquals("0", request.url.parameters["startTime"])
          assertEquals("12000000", request.url.parameters["endTime"])
          """[{"t":11940000,"T":11999999,"o":100,"h":101,"l":99,"c":100.5,"v":20,"i":"1m"}]"""
        }
        "orderbook" -> {
          clock += 60_000
          """{"ticker_id":"0x1","timestamp":"11999","bids":[["100.00","0.0100"]],"asks":[["100.50","0.0200"]]}"""
        }
        else -> error("Unexpected request $path")
      }
      respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    val aptos = Aptos()
    try {
      val key = StrategyMarketKey("decibel", "mainnet", "0x1", 60_000)
      val result = DecibelStrategyMarketFeed(DecibelClient(http, aptos), DecibelVenue().descriptor, { clock }).snapshot(key)
      assertEquals(key, result.key)
      assertEquals(12_000_000L, result.fetchedAtMs)
      assertEquals(11_999_000L, result.book!!.observedAtMs)
      assertEquals("0.0100", result.book!!.bids.single().size)
      assertEquals(listOf("markets", "candlesticks", "orderbook"), requested)
    } finally { http.close(); aptos.close() }
  }

  @Test
  fun signalMapperPreservesCandleTimeBoundsAndIndicatorValues() {
    val candle = Candle(1_000, 60_999, 100.0, 101.0, 99.0, 100.5, 20.0, "1m")
    val signal = candle.strategyCandle()
    assertEquals(candle.openTimeMs, signal.openTimeMs)
    assertEquals(candle.closeTimeMs, signal.closeTimeMs)
    assertEquals(candle.close, signal.close)
    assertEquals(candle.volume, signal.volume)
  }

  @Test
  fun bookTimestampUsesSourceTimeAndRejectsInvalidValues() {
    assertEquals(1_790_518_620_000L, strategyBookTimestamp("1790518620000"))
    assertEquals(1_790_518_620_000L, strategyBookTimestamp("1790518620"))
    assertEquals(1_790_518_620_000L, strategyBookTimestamp("2026-09-27T14:17:00Z"))
    assertFailsWith<IllegalArgumentException> { strategyBookTimestamp("-1") }
    assertFailsWith<IllegalArgumentException> { strategyBookTimestamp("unavailable") }
    assertFailsWith<IllegalArgumentException> { strategyBookTimestamp("9999999999999999999999999") }
  }
}
