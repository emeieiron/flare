package xyz.mcxross.flare.strategies

import xyz.mcxross.flare.domain.TradingIndicators

data class StrategyMarketKey(
  val venueId: String,
  val network: String,
  val marketId: String,
  val timeframeMs: Long,
)

/** Floating point values are indicator inputs only; they must never construct orders. */
data class StrategyCandle(
  val openTimeMs: Long,
  val closeTimeMs: Long,
  val open: Double,
  val high: Double,
  val low: Double,
  val close: Double,
  val volume: Double,
)

data class StrategyBookLevel(val price: String, val size: String)

data class StrategyBook(
  val bids: List<StrategyBookLevel>,
  val asks: List<StrategyBookLevel>,
  val observedAtMs: Long,
)

data class StrategyMarketSnapshot(
  val key: StrategyMarketKey,
  /** When the candle request began, never when it finished. */
  val fetchedAtMs: Long,
  val candles: List<StrategyCandle>,
  val book: StrategyBook? = null,
)

/**
 * Candles whose whole interval ended by [StrategyMarketSnapshot.fetchedAtMs], the moment the request
 * began. A candle still open when it was requested stays excluded, however late the answer arrives.
 */
fun StrategyMarketSnapshot.closedCandles(): List<StrategyCandle> =
  candles.filter { it.openTimeMs + key.timeframeMs <= fetchedAtMs }

fun interface StrategyMarketFeed {
  suspend fun snapshot(key: StrategyMarketKey): StrategyMarketSnapshot
}

enum class StrategyKind(val label: String) {
  MACD_CROSSOVER("MACD crossover"),
  RSI_REVERSAL("RSI reversal"),
  MOMENTUM_PULLBACK("Momentum pullback"),
}

enum class StrategyDirection {
  BUY,
  SELL,
  WAIT,
}

data class StrategySignal(
  val kind: StrategyKind,
  val direction: StrategyDirection,
  val reason: String,
)

data class StrategyEvaluation(
  val key: StrategyMarketKey,
  val evaluatedAtMs: Long,
  val closedAtMs: Long?,
  val signals: List<StrategySignal>,
  val features: Map<String, Double>,
  val rejection: String? = null,
) {
  val aggregate: StrategyDirection
    get() {
      val active = signals.map { it.direction }.filter { it != StrategyDirection.WAIT }.distinct()
      return if (rejection == null && active.size == 1) active.single() else StrategyDirection.WAIT
    }
}

class StrategyEngine(
  private val minimumCandles: Int = 35,
  private val maxFetchAgeMs: Long = 60_000,
) {
  init {
    require(minimumCandles >= 35 && maxFetchAgeMs > 0)
  }

  fun evaluate(snapshot: StrategyMarketSnapshot, nowMs: Long): StrategyEvaluation {
    val key = snapshot.key
    fun rejected(reason: String) =
      StrategyEvaluation(
        key,
        nowMs,
        null,
        StrategyKind.entries.map { StrategySignal(it, StrategyDirection.WAIT, reason) },
        emptyMap(),
        reason,
      )
    if (
      key.timeframeMs <= 0 ||
        key.venueId.isBlank() ||
        key.network.isBlank() ||
        key.marketId.isBlank()
    )
      return rejected("Invalid market key")
    if (
      nowMs < 0 ||
        snapshot.fetchedAtMs < 0 ||
        snapshot.fetchedAtMs > nowMs ||
        nowMs - snapshot.fetchedAtMs > maxFetchAgeMs
    )
      return rejected("Stale or invalid snapshot")
    val candles = snapshot.closedCandles()
    if (candles.size < minimumCandles) return rejected("Insufficient closed candles")
    for ((i, c) in candles.withIndex()) {
      if (
        c.openTimeMs < 0 ||
          c.closeTimeMs <= c.openTimeMs ||
          c.closeTimeMs - c.openTimeMs !in (key.timeframeMs - 1)..key.timeframeMs ||
          listOf(c.open, c.high, c.low, c.close, c.volume).any { !it.isFinite() } ||
          c.low <= 0 ||
          c.volume < 0 ||
          c.high < maxOf(c.open, c.close, c.low) ||
          c.low > minOf(c.open, c.close)
      )
        return rejected("Invalid candle")
      if (i > 0 && c.openTimeMs - candles[i - 1].openTimeMs != key.timeframeMs)
        return rejected("Gapped or unordered candles")
    }
    val latest = candles.last()
    if (nowMs - latest.closeTimeMs > key.timeframeMs) return rejected("Stale closed candles")
    val closes = candles.map { it.close }
    val macd = TradingIndicators.macd(closes)
    val previous = macd[macd.lastIndex - 1].histogram
    val current = macd.last().histogram
    val rsi = TradingIndicators.rsi(closes)
    val previousRsi = rsi[rsi.lastIndex - 1]!!
    val currentRsi = rsi.last()!!
    val trend = closes.takeLast(20).average()
    val priorTrend = closes.dropLast(1).takeLast(20).average()
    val priorClose = closes[closes.lastIndex - 1]
    fun signal(kind: StrategyKind, buy: Boolean, sell: Boolean, reason: String) =
      StrategySignal(
        kind,
        when {
          buy -> StrategyDirection.BUY
          sell -> StrategyDirection.SELL
          else -> StrategyDirection.WAIT
        },
        reason,
      )
    val features =
      mapOf(
        "close" to latest.close,
        "macd" to macd.last().macd,
        "macdSignal" to macd.last().signal,
        "macdHistogram" to current,
        "previousMacdHistogram" to previous,
        "rsi" to currentRsi,
        "previousRsi" to previousRsi,
        "sma20" to trend,
        "previousSma20" to priorTrend,
        "return1" to (latest.close / priorClose - 1),
        "volume" to latest.volume,
        "return20" to (latest.close / closes[closes.lastIndex - 20] - 1),
        "range20" to
          ((candles.takeLast(20).maxOf { it.high } - candles.takeLast(20).minOf { it.low }) /
            latest.close),
      ) + bookFeatures(snapshot.book, nowMs)
    if (features.values.any { !it.isFinite() }) return rejected("Non-finite indicators")
    return StrategyEvaluation(
      key,
      nowMs,
      latest.closeTimeMs,
      listOf(
        signal(
          StrategyKind.MACD_CROSSOVER,
          previous <= 0 && current > 0,
          previous >= 0 && current < 0,
          "Closed-candle MACD histogram zero crossing",
        ),
        signal(
          StrategyKind.RSI_REVERSAL,
          previousRsi <= 30 && currentRsi > 30,
          previousRsi >= 70 && currentRsi < 70,
          "Closed-candle RSI exit from 30/70 zone",
        ),
        signal(
          StrategyKind.MOMENTUM_PULLBACK,
          trend > priorTrend && priorClose <= priorTrend && latest.close > trend,
          trend < priorTrend && priorClose >= priorTrend && latest.close < trend,
          "Closed-candle recovery through SMA20 in trend direction",
        ),
      ),
      features,
    )
  }

  private fun bookFeatures(book: StrategyBook?, nowMs: Long): Map<String, Double> {
    val absent = mapOf("bookAvailable" to 0.0, "bookFresh" to 0.0)
    if (book == null) return absent
    val fresh =
      book.observedAtMs >= 0 &&
        book.observedAtMs <= nowMs &&
        nowMs - book.observedAtMs <= maxFetchAgeMs
    if (!fresh) return mapOf("bookAvailable" to 1.0, "bookFresh" to 0.0)
    fun levels(input: List<StrategyBookLevel>): List<Pair<Double, Double>>? {
      if (input.isEmpty()) return null
      return input.map { level ->
        val price = level.price.toDoubleOrNull() ?: return null
        val size = level.size.toDoubleOrNull() ?: return null
        if (!price.isFinite() || !size.isFinite() || price <= 0 || size <= 0) return null
        price to size
      }
    }
    val bids = levels(book.bids) ?: return absent
    val asks = levels(book.asks) ?: return absent
    if (
      bids.zipWithNext().any { (a, b) -> a.first <= b.first } ||
        asks.zipWithNext().any { (a, b) -> a.first >= b.first } ||
        bids.first().first >= asks.first().first
    )
      return absent
    val bidDepth = bids.take(10).sumOf { it.second }
    val askDepth = asks.take(10).sumOf { it.second }
    val total = bidDepth + askDepth
    if (!total.isFinite() || total <= 0) return absent
    val spread = (asks.first().first / bids.first().first - 1)
    if (!spread.isFinite()) return absent
    return mapOf(
      "bookAvailable" to 1.0,
      "bookFresh" to 1.0,
      "spreadFraction" to spread,
      "bidDepth10" to bidDepth,
      "askDepth10" to askDepth,
      "depthImbalance10" to ((bidDepth - askDepth) / total),
    )
  }

  /** Each prefix is evaluated at its own close; later candles and books cannot leak into replay. */
  fun replay(key: StrategyMarketKey, candles: List<StrategyCandle>): List<StrategyEvaluation> =
    candles.indices.map { index ->
      val time = candles[index].closeTimeMs
      evaluate(StrategyMarketSnapshot(key, time, candles.take(index + 1)), time)
    }
}
