package xyz.mcxross.flare.decibel.api

import io.ktor.client.request.parameter
import xyz.mcxross.flare.decibel.model.AssetContext
import xyz.mcxross.flare.decibel.model.Candle
import xyz.mcxross.flare.decibel.model.CandleInterval
import xyz.mcxross.flare.decibel.model.Market
import xyz.mcxross.flare.decibel.model.MarketPrice
import xyz.mcxross.flare.decibel.model.MarketTrade
import xyz.mcxross.flare.decibel.model.OrderBook
import xyz.mcxross.flare.decibel.model.Page
import xyz.mcxross.flare.decibel.model.SpotAssetContext

interface MarketDataService {
  suspend fun markets(): List<Market>

  suspend fun prices(market: String? = null): List<MarketPrice>

  suspend fun assetContexts(market: String? = null): List<AssetContext>

  suspend fun spotAssetContexts(): List<SpotAssetContext>

  suspend fun candles(
    market: String,
    interval: CandleInterval,
    startTimeMs: Long,
    endTimeMs: Long,
    filterWicks: Boolean = false,
  ): List<Candle>

  suspend fun orderBook(market: String): OrderBook

  suspend fun trades(market: String, limit: Int = 100, offset: Int = 0): List<MarketTrade>
}

internal class DefaultMarketDataService(private val api: DecibelApi) : MarketDataService {
  override suspend fun markets(): List<Market> = api.get("markets")

  override suspend fun prices(market: String?): List<MarketPrice> =
    api.get("prices") { market?.let { url.parameters.append("market", it) } }

  override suspend fun assetContexts(market: String?): List<AssetContext> =
    api.get("asset_contexts") { market?.let { url.parameters.append("market", it) } }

  override suspend fun spotAssetContexts(): List<SpotAssetContext> = api.get("spot/asset_contexts")

  override suspend fun candles(
    market: String,
    interval: CandleInterval,
    startTimeMs: Long,
    endTimeMs: Long,
    filterWicks: Boolean,
  ): List<Candle> {
    require(startTimeMs < endTimeMs) { "Candle start time must be before end time" }
    return api.get("candlesticks") {
      parameter("market", market)
      parameter("interval", interval.wireValue)
      parameter("startTime", startTimeMs)
      parameter("endTime", endTimeMs)
      parameter("filterWicks", filterWicks)
    }
  }

  override suspend fun orderBook(market: String): OrderBook =
    api.get("orderbook") { parameter("market", market) }

  override suspend fun trades(market: String, limit: Int, offset: Int): List<MarketTrade> =
    api
      .get<Page<MarketTrade>>("trades") {
        parameter("market", market)
        parameter("limit", limit.coerceIn(1, 200))
        parameter("offset", offset.coerceIn(0, 10_000))
      }
      .items
}
