package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.CandleInterval
import xyz.mcxross.flare.store.CandleEntity
import xyz.mcxross.flare.venue.model.Candle
import xyz.mcxross.flare.venue.model.ChartTimeframe
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.MarketTrade
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderBookLevel
import xyz.mcxross.flare.decibel.model.Candle as DecibelCandle
import xyz.mcxross.flare.decibel.model.Market as DecibelMarket
import xyz.mcxross.flare.decibel.model.MarketTrade as DecibelMarketTrade
import xyz.mcxross.flare.decibel.model.OrderBook as DecibelOrderBook

// Decibel's market data as the app's exchange-neutral models.

internal fun AssetType.toMarketKind(): MarketKind =
  when (this) {
    AssetType.PERP -> MarketKind.PERP
    AssetType.SPOT -> MarketKind.SPOT
  }

internal fun DecibelMarket.toMarket(): Market =
  Market(
    id = address,
    symbol = symbol,
    displayName = name,
    kind = assetType.toMarketKind(),
    category = category,
    priceDecimals = priceDecimals,
    sizeDecimals = sizeDecimals,
    maxLeverage = maxLeverage,
    isolatedOnly = isIsolatedOnly,
    rules = orderRules(),
  )

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

/** The timeframe of a Decibel wire interval such as `1mo`, or null for one Flare doesn't know. */
internal fun chartTimeframeOf(wireInterval: String): ChartTimeframe? =
  ChartTimeframe.entries.firstOrNull { it.toCandleInterval().wireValue == wireInterval }

internal fun DecibelCandle.toCandle(): Candle =
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

internal fun DecibelCandle.toCandleEntity(market: String, interval: CandleInterval) =
  CandleEntity(
    marketAddress = market,
    interval = interval.wireValue,
    openTimeMs = openTimeMs,
    closeTimeMs = closeTimeMs,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
  )

/** The market cache keeps Decibel's wire interval, as it always has. */
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

/**
 * Decibel sends each level as `[price, size]` decimal text, which stays text. A level missing
 * either is left out.
 */
internal fun DecibelOrderBook.toOrderBook(): OrderBook =
  OrderBook(
    marketId = market,
    bids = bids.mapNotNull(List<String>::toOrderBookLevel),
    asks = asks.mapNotNull(List<String>::toOrderBookLevel),
  )

private fun List<String>.toOrderBookLevel(): OrderBookLevel? {
  val price = getOrNull(0) ?: return null
  val size = getOrNull(1) ?: return null
  return OrderBookLevel(price, size)
}

/** A trade reads as a buy when Decibel's [DecibelMarketTrade.action] is `buy`, as it always has. */
internal fun DecibelMarketTrade.toMarketTrade(): MarketTrade =
  MarketTrade(
    marketId = market,
    isBuy = action.equals("buy", ignoreCase = true),
    price = price,
    size = size,
    timeMs = transactionUnixMs,
  )
