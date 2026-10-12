package xyz.mcxross.flare.venue.hyperliquid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.CandleEntity
import xyz.mcxross.flare.store.MarketCacheDao
import xyz.mcxross.flare.store.MarketEntity
import xyz.mcxross.flare.store.SelectedMarketEntity

/** A client whose `/info` answers each request `type` from [responses]; anything else fails. */
internal fun testClient(responses: Map<String, String>): HyperliquidClient {
  val engine = MockEngine { request ->
    val body = (request.body as TextContent).text
    val type = Json.parseToJsonElement(body).jsonObject["type"]?.jsonPrimitive?.content
    val response = responses[type]
    if (response == null) respondError(HttpStatusCode.BadRequest, "Unexpected request")
    else respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
  }
  return HyperliquidClient(HttpClient(engine))
}

internal val MarketResponses =
  mapOf(
    "metaAndAssetCtxs" to Fixtures.META_AND_ASSET_CTXS,
    "spotMetaAndAssetCtxs" to Fixtures.SPOT_META_AND_ASSET_CTXS,
  )

internal fun memoryPreferences() = AppPreferences(MemoryPreferences())

private class MemoryPreferences : DataStore<Preferences> {
  override val data = MutableStateFlow(emptyPreferences())

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
    transform(data.value).also { data.value = it }
}

internal class MemoryMarketCache : MarketCacheDao {
  val markets = mutableMapOf<String, MarketEntity>()
  val candles = mutableListOf<CandleEntity>()

  override fun observeMarkets(): Flow<List<MarketEntity>> = flowOf(markets.values.toList())

  override suspend fun markets(): List<MarketEntity> = markets.values.sortedBy { it.marketAddress }

  override suspend fun upsertMarkets(markets: List<MarketEntity>) {
    markets.forEach { this.markets[it.marketAddress] = it }
  }

  override suspend fun clearMarkets() = markets.clear()

  override suspend fun candles(
    market: String,
    interval: String,
    startTimeMs: Long,
    endTimeMs: Long,
  ): List<CandleEntity> =
    candles.filter {
      it.marketAddress == market && it.interval == interval && it.openTimeMs in startTimeMs..endTimeMs
    }

  override suspend fun upsertCandles(candles: List<CandleEntity>) {
    this.candles.removeAll { old -> candles.any { it.marketAddress == old.marketAddress && it.interval == old.interval && it.openTimeMs == old.openTimeMs } }
    this.candles += candles
  }

  override suspend fun deleteCandlesBefore(cutoffMs: Long) {
    candles.removeAll { it.closeTimeMs < cutoffMs }
  }

  override suspend fun selectMarket(market: SelectedMarketEntity) {}

  override fun observeSelectedMarket(): Flow<SelectedMarketEntity?> = flowOf(null)
}
