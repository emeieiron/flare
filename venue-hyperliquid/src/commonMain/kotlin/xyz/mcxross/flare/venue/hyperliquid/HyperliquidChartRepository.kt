package xyz.mcxross.flare.venue.hyperliquid

import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.ChartSnapshot
import xyz.mcxross.flare.data.LiveCandles
import xyz.mcxross.flare.data.candlePage
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.api.StreamData
import xyz.mcxross.flare.hyperliquid.api.StreamEvent
import xyz.mcxross.flare.hyperliquid.api.Subscription
import xyz.mcxross.flare.store.CandleEntity
import xyz.mcxross.flare.store.MarketCacheDao
import xyz.mcxross.flare.store.SelectedMarketEntity
import xyz.mcxross.flare.venue.model.Candle
import xyz.mcxross.flare.venue.model.ChartTimeframe

/** Candles from `candleSnapshot`, cached for offline use, and the forming candle from the stream. */
class HyperliquidChartRepository(
  private val client: HyperliquidClient,
  private val markets: HyperliquidMarketsRepository,
  private val cache: MarketCacheDao,
) : ChartRepository {
  override suspend fun candles(market: String, timeframe: ChartTimeframe): ChartSnapshot {
    val now = Clock.System.now().toEpochMilliseconds()
    val (startTimeMs, endTimeMs) = candlePage(now, timeframe)
    cache.selectMarket(SelectedMarketEntity(market, now))
    return runSuspendCatching { fetch(market, timeframe, startTimeMs, endTimeMs) }
      .fold(
        onSuccess = { ChartSnapshot(candles = it, stale = false) },
        onFailure = { error ->
          val cached =
            cache
              .candles(market, timeframe.toCandleInterval().wireValue, startTimeMs, endTimeMs)
              .map(CandleEntity::toCandle)
          if (cached.isEmpty()) throw error
          ChartSnapshot(cached, stale = true, error = error.message ?: "Using cached candle history")
        },
      )
  }

  override suspend fun candlesBefore(
    market: String,
    timeframe: ChartTimeframe,
    beforeMs: Long,
  ): List<Candle> {
    val (startTimeMs, endTimeMs) = candlePage(beforeMs - 1, timeframe)
    return fetch(market, timeframe, startTimeMs, endTimeMs).filter { it.openTimeMs < beforeMs }
  }

  override fun liveCandles(market: String, timeframe: ChartTimeframe): Flow<LiveCandles> = flow {
    if (!markets.isListed(market)) return@flow
    val interval = timeframe.toCandleInterval()
    emitAll(
      client.stream.subscribe(setOf(Subscription.Candles(market, interval))).mapNotNull { event ->
        when (event) {
          is StreamEvent.Connected -> LiveCandles.Connected
          is StreamEvent.Message ->
            (event.data as? StreamData.CandleUpdate)
              ?.candle
              ?.takeIf { it.coin == market && it.interval == interval.wireValue }
              ?.let { LiveCandles.Update(it.toCandle()) }
          is StreamEvent.Rejected,
          is StreamEvent.Disconnected -> null
        }
      }
    )
  }

  private suspend fun fetch(
    market: String,
    timeframe: ChartTimeframe,
    startTimeMs: Long,
    endTimeMs: Long,
  ): List<Candle> {
    val candles =
      client.info
        .candles(market, timeframe.toCandleInterval(), startTimeMs, endTimeMs)
        .distinctBy { it.openTimeMs }
        .sortedBy { it.openTimeMs }
    cache.upsertCandles(candles.map { it.toCandleEntity() })
    cache.deleteCandlesBefore(Clock.System.now().toEpochMilliseconds() - CANDLE_RETENTION_MS)
    return candles.map { it.toCandle() }
  }
}

private const val CANDLE_RETENTION_MS = 5L * 366 * 24 * 60 * 60 * 1_000
