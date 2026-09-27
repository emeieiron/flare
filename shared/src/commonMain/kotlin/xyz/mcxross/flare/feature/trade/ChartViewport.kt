package xyz.mcxross.flare.feature.trade

import kotlin.math.abs
import kotlin.math.max
import xyz.mcxross.flare.data.candleDurationMs
import xyz.mcxross.flare.decibel.model.Candle

/** Keep price movement readable without anchoring financial prices to zero. */
internal fun priceBounds(values: List<Double>): ClosedFloatingPointRange<Double> {
  val finite = values.filter(Double::isFinite)
  if (finite.isEmpty()) return 0.0..1.0
  val low = finite.min()
  val high = finite.max()
  val padding = max((high - low) * 0.12, max(abs(high) * 0.0001, 0.000001))
  return (low - padding)..(high + padding)
}

/**
 * Each candle's place on the time axis, counted in its own interval. It never moves as history is
 * added at either end.
 */
internal fun candleSlots(candles: List<Candle>): List<Long> {
  val intervalMs = candles.firstOrNull()?.let { candleDurationMs(it.interval) } ?: 60_000L
  return candles.map { it.openTimeMs / intervalMs }
}

/**
 * The candles whose slots fall in [visible], including partly visible ones so their wicks stay
 * inside the vertical viewport. Before the chart first draws, the latest [initialCount].
 */
internal fun visibleCandleIndices(slots: List<Long>, visible: LongRange?, initialCount: Int): IntRange {
  if (slots.isEmpty()) return IntRange.EMPTY
  if (visible == null) return (slots.size - initialCount).coerceAtLeast(0)..slots.lastIndex
  val first = slots.binarySearch(visible.first).let { if (it >= 0) it else -it - 1 }
  val last = slots.binarySearch(visible.last).let { if (it >= 0) it else -it - 2 }
  val start = first.coerceIn(0, slots.lastIndex)
  return start..last.coerceIn(start, slots.lastIndex)
}
