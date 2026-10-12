package xyz.mcxross.flare.venue.hyperliquid

import kotlin.time.Clock
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.Candle
import xyz.mcxross.flare.strategies.StrategyBook
import xyz.mcxross.flare.strategies.StrategyBookLevel
import xyz.mcxross.flare.strategies.StrategyCandle
import xyz.mcxross.flare.strategies.StrategyMarketFeed
import xyz.mcxross.flare.strategies.StrategyMarketKey
import xyz.mcxross.flare.strategies.StrategyMarketSnapshot
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.model.ChartTimeframe

/** Direct SDK reads isolated from selected market and chart history. */
class HyperliquidStrategyMarketFeed(
  private val client: HyperliquidClient,
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
    val listed = client.info.perpMeta().universe.any { !it.isDelisted && it.name == key.marketId } ||
      client.info.spotMeta().universe.any { it.name == key.marketId }
    require(listed) { "Market is not listed" }
    val now = nowMs()
    val start = (now - key.timeframeMs * 200).coerceAtLeast(0)
    val interval = timeframe.toCandleInterval()
    val candles = client.info.candles(key.marketId, interval, start, now)
    require(candles.all { it.coin == key.marketId && it.interval == interval.wireValue }) {
      "Candle market or interval mismatch"
    }
    val book = runSuspendCatching {
      val raw = client.info.l2Book(key.marketId)
      require(raw.coin == key.marketId) { "Order book market mismatch" }
      StrategyBook(raw.bids.map { StrategyBookLevel(it.px, it.sz) },
        raw.asks.map { StrategyBookLevel(it.px, it.sz) }, raw.time)
    }.getOrNull()
    return StrategyMarketSnapshot(key, now,
      candles.filter { it.openTimeMs >= start && it.openTimeMs <= now }
        .sortedBy { it.openTimeMs }.map { it.strategyCandle() }, book)
  }
}

internal fun Candle.strategyCandle() = StrategyCandle(
  openTimeMs, closeTimeMs, open.toDouble(), high.toDouble(), low.toDouble(), close.toDouble(), volume.toDouble(),
)
