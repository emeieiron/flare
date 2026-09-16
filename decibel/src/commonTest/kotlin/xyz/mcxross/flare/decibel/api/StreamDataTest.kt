package xyz.mcxross.flare.decibel.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xyz.mcxross.flare.decibel.DecibelClient

class StreamDataTest {
  private val json = DecibelClient.DefaultJson

  @Test
  fun decodesAllMarketPriceEnvelope() {
    val payload =
      objectPayload(
        """{"topic":"all_market_prices","prices":[{"market":"0x1","oracle_px":100.0,"mark_px":101.0,"mid_px":100.5,"funding_rate_bps":0.01,"is_funding_positive":true,"funding_period_s":3600,"transaction_unix_ms":1000,"open_interest":500.0}]}"""
      )

    val data =
      assertIs<DecibelStreamData.MarketPrices>(decodeStreamData(AllMarketPrices, payload, json))
    assertEquals(101.0, data.values.single().markPrice)
  }

  @Test
  fun decodesAllSpotMidsEnvelope() {
    val payload =
      objectPayload(
        """{"topic":"all_spot_mids","mids":[{"market_addr":"0x26f","asset_type":"spot","mid":0.597,"last_trade_price":0.683,"transaction_unix_ms":1789358981393}]}"""
      )

    val data = assertIs<DecibelStreamData.SpotMids>(decodeStreamData(AllSpotMids, payload, json))
    assertEquals("0x26f", data.values.single().marketAddress)
    assertEquals(0.597, data.values.single().mid)
    assertEquals(0.683, data.values.single().lastTradePrice)
    assertEquals(0.683, data.values.single().price)
  }

  @Test
  fun preservesDepthDecimalsAsText() {
    val topic = MarketDepth("0x1", DepthAggregation.ONE)
    val payload =
      objectPayload(
        """{"topic":"${topic.wireValue}","market":"0x1","asks":[{"price":100.125,"size":1.25}],"bids":[{"price":100.0,"size":2}],"best_ask":100.125,"best_bid":100.0,"unix_ms":1000,"sequence":7}"""
      )

    val data = assertIs<DecibelStreamData.MarketDepthValue>(decodeStreamData(topic, payload, json))
    assertEquals("100.125", data.value.asks.single().price)
    assertEquals("2", data.value.bids.single().size)
    assertEquals("100.125", data.value.toOrderBook().bestAsk)
  }

  private fun objectPayload(value: String): JsonObject = json.parseToJsonElement(value).jsonObject
}
