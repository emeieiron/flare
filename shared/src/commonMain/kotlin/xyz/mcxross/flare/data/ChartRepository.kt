package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import xyz.mcxross.flare.venue.model.Candle
import xyz.mcxross.flare.venue.model.ChartTimeframe

/** The span of the page of history that ends at [endTimeMs]. */
fun candlePage(endTimeMs: Long, timeframe: ChartTimeframe): Pair<Long, Long> =
  (endTimeMs - timeframe.durationMs * CANDLES_PER_PAGE) to endTimeMs

interface ChartRepository {
  /** The latest page of candles, from the cache when the network fails. */
  suspend fun candles(market: String, timeframe: ChartTimeframe): ChartSnapshot

  /** The page of candles before [beforeMs]; empty once the market's history runs out. */
  suspend fun candlesBefore(market: String, timeframe: ChartTimeframe, beforeMs: Long): List<Candle>

  /** The forming candle as it changes, and each new one, while collected. */
  fun liveCandles(market: String, timeframe: ChartTimeframe): Flow<LiveCandles>
}

sealed interface LiveCandles {
  /** The stream (re)started or missed updates, so history may need catching up. */
  data object Connected : LiveCandles

  data class Update(val candle: Candle) : LiveCandles
}

data class ChartSnapshot(
  val candles: List<Candle>,
  val stale: Boolean,
  val error: String? = null,
)

private const val CANDLES_PER_PAGE = 500L
