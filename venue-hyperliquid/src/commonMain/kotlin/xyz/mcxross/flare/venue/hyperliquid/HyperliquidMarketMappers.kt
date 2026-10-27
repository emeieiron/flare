package xyz.mcxross.flare.venue.hyperliquid

import kotlinx.serialization.Serializable
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.hyperliquid.model.AssetDirectory
import xyz.mcxross.flare.hyperliquid.model.CandleInterval
import xyz.mcxross.flare.hyperliquid.model.L2Book
import xyz.mcxross.flare.hyperliquid.model.PerpMetaAndContexts
import xyz.mcxross.flare.hyperliquid.model.Side
import xyz.mcxross.flare.hyperliquid.model.SpotMetaAndContexts
import xyz.mcxross.flare.store.CandleEntity
import xyz.mcxross.flare.venue.model.Candle
import xyz.mcxross.flare.venue.model.ChartTimeframe
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.MarketTrade
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderBookLevel
import xyz.mcxross.flare.hyperliquid.model.Candle as HyperliquidCandle
import xyz.mcxross.flare.hyperliquid.model.MarketTrade as HyperliquidTrade

// Hyperliquid's market data as the app's exchange-neutral models. Market.id is the coin: `BTC`,
// `PURR/USDC`, `@107`.

/**
 * A market as the cache stores it: what [Market] needs to be rebuilt, rules included, plus the
 * quote. Prices are display values.
 */
@Serializable
internal data class CachedMarket(
  val coin: String,
  val assetId: Int,
  val spot: Boolean,
  val symbol: String,
  val displayName: String,
  val szDecimals: Int,
  val maxLeverage: Int,
  val isolatedOnly: Boolean,
  val markPrice: Double,
  val previousDayPrice: Double,
  val volume24h: Double,
  val openInterest: Double,
  val fundingRateBps: Double? = null,
  /** A spot pair's quote token. */
  val quote: String? = null,
) {
  fun toMarket(): Market {
    val kind = if (spot) MarketKind.SPOT else MarketKind.PERP
    val rules = HyperliquidOrderRules(coin, symbol, kind, szDecimals)
    return Market(
      id = coin,
      symbol = symbol,
      displayName = displayName,
      kind = kind,
      category = CATEGORY,
      priceDecimals = rules.maxPriceDecimals,
      sizeDecimals = szDecimals,
      maxLeverage = maxLeverage,
      isolatedOnly = isolatedOnly,
      rules = rules,
      quoteAsset = quote?.takeIf { it != USDC },
    )
  }

  fun toQuote(favorite: Boolean = false): MarketQuote =
    MarketQuote(
      market = toMarket(),
      markPrice = markPrice,
      changePercent24h = changePercent(previousDayPrice, markPrice),
      volume24h = volume24h,
      openInterest = openInterest,
      favorite = favorite,
      fundingRateBps = fundingRateBps,
    )
}

/** Hyperliquid files nothing under categories; its markets are crypto. */
private const val CATEGORY = "crypto"

internal fun changePercent(previous: Double, current: Double): Double =
  if (previous > 0.0 && current > 0.0) (current - previous) / previous * 100.0 else 0.0

/**
 * Every listed perp, with its context aligned by position; delisted perps are left out. Open
 * interest is reported in the asset and shown in dollars at the mark price.
 */
internal fun PerpMetaAndContexts.toCachedMarkets(): List<CachedMarket> =
  meta.universe.mapIndexedNotNull { index, info ->
    if (info.isDelisted) return@mapIndexedNotNull null
    val context = contexts.getOrNull(index) ?: return@mapIndexedNotNull null
    val mark = context.markPx.toDoubleOrZero()
    CachedMarket(
      coin = info.name,
      assetId = index,
      spot = false,
      symbol = info.name,
      displayName = "${info.name}/USD",
      szDecimals = info.szDecimals,
      maxLeverage = info.maxLeverage,
      isolatedOnly = info.isIsolatedOnly,
      markPrice = mark,
      previousDayPrice = context.prevDayPx.toDoubleOrZero(),
      volume24h = context.dayNtlVlm.toDoubleOrZero(),
      openInterest = context.openInterest.toDoubleOrZero() * mark,
      // Hyperliquid's funding rate is hourly.
      fundingRateBps = context.funding.toDoubleOrNull()?.times(10_000.0),
    )
  }

/**
 * Spot pairs with a context, matched by coin. Most of Hyperliquid's spot pairs are tests or barely
 * trade, so only pairs quoted in a dollar stablecoin are listed, and only if they're canonical or
 * traded at least [minDayVolume] dollars in the last day.
 */
internal fun SpotMetaAndContexts.toCachedMarkets(minDayVolume: Double): List<CachedMarket> {
  val contexts = contextByCoin
  val canonical = meta.universe.filter { it.isCanonical }.mapTo(mutableSetOf()) { it.name }
  return AssetDirectory(EmptyPerpMeta, meta).spots.mapNotNull { asset ->
    val context = contexts[asset.coin] ?: return@mapNotNull null
    val quote = asset.quoteToken?.name?.trim().orEmpty()
    if (quote !in StableQuotes) return@mapNotNull null
    val volume = context.dayNtlVlm.toDoubleOrZero()
    if (volume < minDayVolume && asset.coin !in canonical) return@mapNotNull null
    val base = asset.baseToken?.name?.trim().orEmpty()
    CachedMarket(
      coin = asset.coin,
      assetId = asset.id,
      spot = true,
      symbol = base,
      displayName = "$base/$quote",
      szDecimals = asset.szDecimals,
      maxLeverage = 1,
      isolatedOnly = false,
      markPrice = (context.midPx ?: context.markPx).toDoubleOrZero(),
      previousDayPrice = context.prevDayPx.toDoubleOrZero(),
      volume24h = volume,
      openInterest = 0.0,
      quote = quote,
    )
  }
}

/** The least a spot pair trades in a day, in USDC, to be listed. */
internal const val MIN_SPOT_DAY_VOLUME = 1_000.0

private val StableQuotes = setOf(USDC, "USDT0", "USDH", "USDE")

private val EmptyPerpMeta = xyz.mcxross.flare.hyperliquid.model.PerpMeta(universe = emptyList())

internal fun ChartTimeframe.toCandleInterval(): CandleInterval =
  when (this) {
    ChartTimeframe.ONE_MINUTE -> CandleInterval.ONE_MINUTE
    ChartTimeframe.FIVE_MINUTES -> CandleInterval.FIVE_MINUTES
    ChartTimeframe.FIFTEEN_MINUTES -> CandleInterval.FIFTEEN_MINUTES
    ChartTimeframe.THIRTY_MINUTES -> CandleInterval.THIRTY_MINUTES
    ChartTimeframe.ONE_HOUR -> CandleInterval.ONE_HOUR
    ChartTimeframe.TWO_HOURS -> CandleInterval.TWO_HOURS
    ChartTimeframe.FOUR_HOURS -> CandleInterval.FOUR_HOURS
    ChartTimeframe.EIGHT_HOURS -> CandleInterval.EIGHT_HOURS
    ChartTimeframe.TWELVE_HOURS -> CandleInterval.TWELVE_HOURS
    ChartTimeframe.ONE_DAY -> CandleInterval.ONE_DAY
    ChartTimeframe.ONE_WEEK -> CandleInterval.ONE_WEEK
    ChartTimeframe.ONE_MONTH -> CandleInterval.ONE_MONTH
  }

/** The timeframe of a Hyperliquid wire interval such as `1M`, or null for one Flare doesn't show. */
internal fun chartTimeframeOf(wireInterval: String): ChartTimeframe? =
  ChartTimeframe.entries.firstOrNull { it.toCandleInterval().wireValue == wireInterval }

internal fun HyperliquidCandle.toCandle(): Candle =
  Candle(
    openTimeMs = openTimeMs,
    closeTimeMs = closeTimeMs,
    open = open.toDoubleOrZero(),
    high = high.toDoubleOrZero(),
    low = low.toDoubleOrZero(),
    close = close.toDoubleOrZero(),
    volume = volume.toDoubleOrZero(),
    timeframe = chartTimeframeOf(interval),
  )

internal fun HyperliquidCandle.toCandleEntity() =
  CandleEntity(
    marketAddress = coin,
    interval = interval,
    openTimeMs = openTimeMs,
    closeTimeMs = closeTimeMs,
    open = open.toDoubleOrZero(),
    high = high.toDoubleOrZero(),
    low = low.toDoubleOrZero(),
    close = close.toDoubleOrZero(),
    volume = volume.toDoubleOrZero(),
  )

internal fun CandleEntity.toCandle(): Candle =
  Candle(
    openTimeMs = openTimeMs,
    closeTimeMs = closeTimeMs,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
    timeframe = chartTimeframeOf(interval),
  )

/** Levels stay decimal text: the best ones price market orders. */
internal fun L2Book.toOrderBook(): OrderBook =
  OrderBook(
    marketId = coin,
    bids = bids.map { OrderBookLevel(it.px, it.sz) },
    asks = asks.map { OrderBookLevel(it.px, it.sz) },
  )

internal fun HyperliquidTrade.toMarketTrade(): MarketTrade =
  MarketTrade(
    marketId = coin,
    isBuy = side == Side.BUY,
    price = px.toDoubleOrZero(),
    size = sz.toDoubleOrZero(),
    timeMs = time,
  )

/** Newest first, without repeats, at most [limit]. */
internal fun List<HyperliquidTrade>.toRecentTrades(
  limit: Int = RECENT_TRADES,
  earlier: List<MarketTrade> = emptyList(),
): List<MarketTrade> =
  (sortedByDescending { it.time }.distinctBy { it.tid }.map { it.toMarketTrade() } + earlier)
    .take(limit)

internal const val RECENT_TRADES = 40

