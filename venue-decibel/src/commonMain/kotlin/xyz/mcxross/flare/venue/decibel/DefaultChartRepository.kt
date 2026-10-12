package xyz.mcxross.flare.venue.decibel

import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.ChartSnapshot
import xyz.mcxross.flare.data.LiveCandles
import xyz.mcxross.flare.data.candlePage
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.DecibelStreamData
import xyz.mcxross.flare.decibel.api.MarketCandlestick
import xyz.mcxross.flare.decibel.api.StreamEvent
import xyz.mcxross.flare.store.CandleEntity
import xyz.mcxross.flare.store.MarketCacheDao
import xyz.mcxross.flare.store.SelectedMarketEntity
import xyz.mcxross.flare.venue.model.Candle
import xyz.mcxross.flare.venue.model.ChartTimeframe

class DefaultChartRepository(
  private val client: DecibelClient,
  private val cache: MarketCacheDao,
) : ChartRepository {
  override suspend fun candles(market: String, timeframe: ChartTimeframe): ChartSnapshot {
    val now = Clock.System.now().toEpochMilliseconds()
    val (startTimeMs, endTimeMs) = candlePage(now, timeframe)
    cache.selectMarket(SelectedMarketEntity(market, now))
    return runSuspendCatching { fetch(market, timeframe, startTimeMs, endTimeMs) }
      .fold(
        onSuccess = { candles -> ChartSnapshot(candles = candles, stale = false) },
        onFailure = { error ->
          val cached =
            cache
              .candles(market, timeframe.toCandleInterval().wireValue, startTimeMs, endTimeMs)
              .map(CandleEntity::toCandle)
          if (cached.isEmpty()) throw error
          ChartSnapshot(
            candles = cached,
            stale = true,
            error = error.message ?: "Using cached candle history",
          )
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

  override fun liveCandles(market: String, timeframe: ChartTimeframe): Flow<LiveCandles> {
    val interval = timeframe.toCandleInterval()
    return client.stream.subscribe(setOf(MarketCandlestick(market, interval))).mapNotNull { event ->
      when (event) {
        is StreamEvent.Connected,
        is StreamEvent.SequenceGap -> LiveCandles.Connected
        is StreamEvent.Message ->
          (event.data as? DecibelStreamData.CandleValue)
            ?.value
            ?.takeIf { it.interval == interval.wireValue }
            ?.let { LiveCandles.Update(it.toCandle()) }
        is StreamEvent.Rejected,
        is StreamEvent.Disconnected -> null
      }
    }
  }

  private suspend fun fetch(
    market: String,
    timeframe: ChartTimeframe,
    startTimeMs: Long,
    endTimeMs: Long,
  ): List<Candle> {
    val interval = timeframe.toCandleInterval()
    val candles =
      candleRequestWindows(startTimeMs, endTimeMs, timeframe)
        .flatMap { (windowStartMs, windowEndMs) ->
          client.markets.candles(
            market = market,
            interval = interval,
            startTimeMs = windowStartMs,
            endTimeMs = windowEndMs,
            filterWicks = false,
          )
        }
        .distinctBy { it.openTimeMs }
        .sortedBy { it.openTimeMs }
    cache.upsertCandles(candles.map { it.toCandleEntity(market, interval) })
    cache.deleteCandlesBefore(Clock.System.now().toEpochMilliseconds() - CANDLE_RETENTION_MS)
    return candles.map { it.toCandle() }
  }
}

/** Splits a span of history into requests Decibel answers in full: at most 1000 candles each. */
internal fun candleRequestWindows(
  startTimeMs: Long,
  endTimeMs: Long,
  timeframe: ChartTimeframe,
): List<Pair<Long, Long>> {
  require(startTimeMs < endTimeMs) { "Candle start time must be before end time" }
  // Overlap one boundary so this stays safe whether Decibel treats endTime as inclusive or
  // exclusive. At most 999 intervals can yield at most 1000 inclusive candle timestamps.
  val windowDuration = timeframe.durationMs * (MAX_CANDLES_PER_REQUEST - 1)
  val windows = mutableListOf<Pair<Long, Long>>()
  var cursor = startTimeMs
  while (cursor < endTimeMs) {
    val windowEnd = minOf(endTimeMs, cursor + windowDuration)
    windows += cursor to windowEnd
    if (windowEnd == endTimeMs) break
    cursor = windowEnd
  }
  return windows
}

private const val MAX_CANDLES_PER_REQUEST = 1_000L
private const val CANDLE_RETENTION_MS = 5L * 366 * 24 * 60 * 60 * 1_000
