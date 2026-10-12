package xyz.mcxross.flare.venue.decibel

import kotlin.time.Clock
import kotlin.time.Instant
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.model.Candle
import xyz.mcxross.flare.strategies.StrategyBook
import xyz.mcxross.flare.strategies.StrategyBookLevel
import xyz.mcxross.flare.strategies.StrategyCandle
import xyz.mcxross.flare.strategies.StrategyMarketFeed
import xyz.mcxross.flare.strategies.StrategyMarketKey
import xyz.mcxross.flare.strategies.StrategyMarketSnapshot
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.model.ChartTimeframe

/** Independent public reads: evaluating a signal never selects or writes the chart cache. */
class DecibelStrategyMarketFeed(
  private val client: DecibelClient,
  private val descriptor: VenueDescriptor,
  private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : StrategyMarketFeed {
  override suspend fun snapshot(key: StrategyMarketKey): StrategyMarketSnapshot {
    require(key.venueId == descriptor.id && key.network == descriptor.network.name.lowercase()) {
      "Signal venue or network does not match this feed"
    }
    val timeframe = ChartTimeframe.entries.firstOrNull { it.durationMs == key.timeframeMs }
      ?: error("Unsupported signal timeframe")
    require(timeframe != ChartTimeframe.ONE_MONTH) { "Calendar months are not fixed signal intervals" }
    require(client.markets.markets().any { it.address == key.marketId }) { "Market is not listed" }
    val now = nowMs()
    val start = (now - key.timeframeMs * 200).coerceAtLeast(0)
    val interval = timeframe.toCandleInterval()
    val candles = client.markets.candles(key.marketId, interval, start, now, filterWicks = false)
    require(candles.all { it.interval == interval.wireValue }) { "Candle interval mismatch" }
    val book = runSuspendCatching {
      val raw = client.markets.orderBook(key.marketId)
      require(raw.market == key.marketId) { "Order book market mismatch" }
      StrategyBook(
        bids = raw.bids.mapNotNull { it.strategyLevel() },
        asks = raw.asks.mapNotNull { it.strategyLevel() },
        observedAtMs = strategyBookTimestamp(raw.timestamp),
      )
    }.getOrNull()
    return StrategyMarketSnapshot(key, now,
      candles.filter { it.openTimeMs >= start && it.openTimeMs <= now }
        .sortedBy { it.openTimeMs }.map { it.strategyCandle() }, book)
  }
}

internal fun Candle.strategyCandle() = StrategyCandle(openTimeMs, closeTimeMs, open, high, low, close, volume)

private fun List<String>.strategyLevel(): StrategyBookLevel? =
  if (size >= 2) StrategyBookLevel(this[0], this[1]) else null

/** Preserve source time; an unknown timestamp is unavailable, never replaced with fetch time. */
internal fun strategyBookTimestamp(value: String): Long {
  val numeric = value.toLongOrNull()
  if (numeric != null) {
    require(numeric >= 0) { "Invalid book timestamp" }
    return if (numeric < 100_000_000_000L) numeric * 1_000 else numeric
  }
  return Instant.parse(value).toEpochMilliseconds().also { require(it >= 0) }
}
