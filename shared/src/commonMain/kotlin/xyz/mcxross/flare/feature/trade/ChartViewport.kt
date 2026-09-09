package xyz.mcxross.flare.feature.trade

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Keep price movement readable without anchoring financial prices to zero. */
internal fun priceBounds(values: List<Double>): ClosedFloatingPointRange<Double> {
  val finite = values.filter(Double::isFinite)
  if (finite.isEmpty()) return 0.0..1.0
  val low = finite.min()
  val high = finite.max()
  val padding = max((high - low) * 0.12, max(abs(high) * 0.0001, 0.000001))
  return (low - padding)..(high + padding)
}

/** Include partially visible candles so their wicks remain inside the vertical viewport. */
internal fun visibleCandleIndices(firstX: Double, lastX: Double, count: Int): IntRange {
  if (count <= 0) return IntRange.EMPTY
  val first = floor(firstX).toInt().coerceIn(0, count - 1)
  val last = ceil(lastX).toInt().coerceIn(first, count - 1)
  return first..last
}
