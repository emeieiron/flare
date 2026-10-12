package xyz.mcxross.flare.venue.model

/** Whether a market trades a perpetual contract or the asset itself. */
enum class MarketKind {
  PERP,
  SPOT,
}

/** A market as the app shows and trades it, whichever exchange lists it. */
data class Market(
  /** The exchange's own market id, such as Decibel's market address. */
  val id: String,
  /** The base asset's ticker, such as `BTC`. */
  val symbol: String,
  /** The exchange's name for the market, such as `BTC/USD`. */
  val displayName: String,
  val kind: MarketKind,
  /** The asset class the exchange files it under, such as `crypto`; blank when it has none. */
  val category: String,
  val priceDecimals: Int,
  val sizeDecimals: Int,
  val maxLeverage: Int,
  /** Positions in this market are always isolated. */
  val isolatedOnly: Boolean,
  /** The exchange's checks for this market's orders. */
  val rules: OrderRules,
  /**
   * The quote asset, set only when it isn't the one the venue's markets usually trade against, so
   * pairs of the same asset can be told apart.
   */
  val quoteAsset: String? = null,
)

/**
 * A chart's candle size. The chart opens on the latest candles and loads older ones as it pans.
 * Each venue maps it to its own wire value. Preferences store it by [name].
 */
enum class ChartTimeframe(
  val label: String,
  val title: String,
  /** How long one candle lasts. A month counts as 30 days. */
  val durationMs: Long,
) {
  ONE_MINUTE("1m", "1 minute", MINUTE_MS),
  FIVE_MINUTES("5m", "5 minutes", 5 * MINUTE_MS),
  FIFTEEN_MINUTES("15m", "15 minutes", 15 * MINUTE_MS),
  THIRTY_MINUTES("30m", "30 minutes", 30 * MINUTE_MS),
  ONE_HOUR("1h", "1 hour", HOUR_MS),
  TWO_HOURS("2h", "2 hours", 2 * HOUR_MS),
  FOUR_HOURS("4h", "4 hours", 4 * HOUR_MS),
  EIGHT_HOURS("8h", "8 hours", 8 * HOUR_MS),
  TWELVE_HOURS("12h", "12 hours", 12 * HOUR_MS),
  ONE_DAY("1d", "1 day", DAY_MS),
  ONE_WEEK("1w", "1 week", 7 * DAY_MS),
  // A capital M, so a month never reads as a minute.
  ONE_MONTH("1M", "1 month", 30 * DAY_MS);

  companion object {
    val Default = FIVE_MINUTES
  }
}

private const val MINUTE_MS = 60L * 1_000
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** One candle. Display only: prices and volume never feed an order. */
data class Candle(
  val openTimeMs: Long,
  val closeTimeMs: Long,
  val open: Double,
  val high: Double,
  val low: Double,
  val close: Double,
  val volume: Double,
  /** The candle's size, or null when the exchange reports one Flare doesn't know. */
  val timeframe: ChartTimeframe?,
)

/** One price level of a book. Decimal strings, because the best levels price market orders. */
data class OrderBookLevel(val price: String, val size: String)

/** A market's resting orders, best levels first. */
data class OrderBook(
  val marketId: String,
  val bids: List<OrderBookLevel>,
  val asks: List<OrderBookLevel>,
) {
  val bestBid: String?
    get() = bids.firstOrNull()?.price

  val bestAsk: String?
    get() = asks.firstOrNull()?.price
}

/** A trade printed in a market. Display only. */
data class MarketTrade(
  val marketId: String,
  /** The exchange reports the trade as a buy. */
  val isBuy: Boolean,
  val price: Double,
  val size: Double,
  val timeMs: Long,
)
