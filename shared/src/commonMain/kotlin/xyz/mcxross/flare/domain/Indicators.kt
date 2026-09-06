package xyz.mcxross.flare.domain

data class MacdPoint(
  val macd: Double,
  val signal: Double,
  val histogram: Double,
)

object TradingIndicators {
  /** Wilder RSI. Values before the first complete period are null. */
  fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
    require(period > 1) { "RSI period must be greater than one" }
    if (closes.size <= period) return List(closes.size) { null }

    val output = MutableList<Double?>(closes.size) { null }
    var averageGain = 0.0
    var averageLoss = 0.0
    for (index in 1..period) {
      val delta = closes[index] - closes[index - 1]
      if (delta >= 0.0) averageGain += delta else averageLoss -= delta
    }
    averageGain /= period
    averageLoss /= period
    output[period] = rsiValue(averageGain, averageLoss)

    for (index in period + 1 until closes.size) {
      val delta = closes[index] - closes[index - 1]
      val gain = if (delta > 0.0) delta else 0.0
      val loss = if (delta < 0.0) -delta else 0.0
      averageGain = ((averageGain * (period - 1)) + gain) / period
      averageLoss = ((averageLoss * (period - 1)) + loss) / period
      output[index] = rsiValue(averageGain, averageLoss)
    }
    return output
  }

  fun macd(
    closes: List<Double>,
    fastPeriod: Int = 12,
    slowPeriod: Int = 26,
    signalPeriod: Int = 9,
  ): List<MacdPoint> {
    require(fastPeriod > 0 && slowPeriod > fastPeriod && signalPeriod > 0) {
      "MACD periods must satisfy 0 < fast < slow and signal > 0"
    }
    if (closes.isEmpty()) return emptyList()
    val fast = ema(closes, fastPeriod)
    val slow = ema(closes, slowPeriod)
    val macd = fast.zip(slow) { fastValue, slowValue -> fastValue - slowValue }
    val signal = ema(macd, signalPeriod)
    return macd.indices.map { index ->
      MacdPoint(
        macd = macd[index],
        signal = signal[index],
        histogram = macd[index] - signal[index],
      )
    }
  }

  private fun rsiValue(averageGain: Double, averageLoss: Double): Double =
    when {
      averageLoss == 0.0 && averageGain == 0.0 -> 50.0
      averageLoss == 0.0 -> 100.0
      else -> 100.0 - (100.0 / (1.0 + averageGain / averageLoss))
    }

  private fun ema(values: List<Double>, period: Int): List<Double> {
    if (values.isEmpty()) return emptyList()
    val multiplier = 2.0 / (period + 1.0)
    val output = MutableList(values.size) { 0.0 }
    output[0] = values[0]
    for (index in 1 until values.size) {
      output[index] = ((values[index] - output[index - 1]) * multiplier) + output[index - 1]
    }
    return output
  }
}
