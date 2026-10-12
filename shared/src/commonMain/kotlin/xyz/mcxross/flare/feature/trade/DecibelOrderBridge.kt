package xyz.mcxross.flare.feature.trade

import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.OrderDraft
import xyz.mcxross.flare.decibel.model.OrderValidationResult
import xyz.mcxross.flare.decibel.model.TwapValidationResult
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.decibel.model.Market as DecibelMarket
import xyz.mcxross.flare.decibel.model.OrderBook as DecibelOrderBook
import xyz.mcxross.flare.decibel.model.validate as decibelValidate
import xyz.mcxross.flare.decibel.model.validateTwap as decibelValidateTwap

// Orders are still checked by Decibel's validation until each exchange supplies its own order
// rules. Until then these hand it the neutral market and book, rebuilt as the Decibel types it
// reads: the market's id, decimals, steps and limits, and the book's market and price levels.

/** Checks an order against [market] and, for a market order, the best price in [orderBook]. */
internal fun OrderDraft.validate(
  market: Market,
  orderBook: OrderBook? = null,
): OrderValidationResult = decibelValidate(market.toDecibelMarket(), orderBook?.toDecibelOrderBook())

internal fun OrderDraft.validateTwap(
  market: Market,
  frequencySeconds: ULong,
  durationSeconds: ULong,
): TwapValidationResult =
  decibelValidateTwap(market.toDecibelMarket(), frequencySeconds, durationSeconds)

/** Validation never reads the fields left at zero or blank here. */
private fun Market.toDecibelMarket(): DecibelMarket =
  DecibelMarket(
    assetType = if (kind == MarketKind.SPOT) AssetType.SPOT else AssetType.PERP,
    address = id,
    name = displayName,
    sizeDecimals = sizeDecimals,
    maxLeverage = maxLeverage,
    tickSize = tickSize,
    minSize = minSize,
    lotSize = lotSize,
    maxOpenInterest = 0.0,
    priceDecimals = priceDecimals,
    mode = "",
    unrealizedPnlHaircutBps = 0,
    category = category,
    minPrice = minPrice,
    maxPrice = maxPrice,
    isIsolatedOnly = isolatedOnly,
  )

private fun OrderBook.toDecibelOrderBook(): DecibelOrderBook =
  DecibelOrderBook(
    market = marketId,
    timestamp = "",
    bids = bids.map { listOf(it.price, it.size) },
    asks = asks.map { listOf(it.price, it.size) },
  )
