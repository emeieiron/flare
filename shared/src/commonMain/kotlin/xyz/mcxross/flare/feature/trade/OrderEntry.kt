package xyz.mcxross.flare.feature.trade

import kotlin.math.abs
import xyz.mcxross.flare.core.decimalUnits
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TwapSchedule

internal fun TradeUiState.entryPrice(side: OrderSide): String? =
  if (orderType == OrderType.LIMIT) limitPriceInput.takeIf(String::isNotBlank)
  else if (side == OrderSide.BUY) marketDetails.orderBook?.bestAsk
  else marketDetails.orderBook?.bestBid

/** Entry and attached exit prices remain decimal strings until the market's rules check them. */
internal fun TradeUiState.orderRequest(side: OrderSide): OrderRequest {
  val market = quote?.market ?: error("Select a market first")
  val isSpot = market.kind == MarketKind.SPOT
  val takeProfit = if (isSpot) null else takeProfitInput.takeIf(String::isNotBlank)
  val stopLoss = if (isSpot) null else stopLossInput.takeIf(String::isNotBlank)
  if (takeProfit != null || stopLoss != null) {
    val entry = entryPrice(side) ?: error("An entry price is needed to check your exits")
    val entryUnits = decimalUnits(entry, "Entry price", market.priceDecimals).getOrThrow()
    fun checkExit(input: String?, label: String, above: Boolean) {
      if (input == null) return
      val units = decimalUnits(input, label, market.priceDecimals).getOrThrow()
      require(units > 0uL) { "$label must be greater than zero" }
      require(if (above) units > entryUnits else units < entryUnits) {
        "$label must be ${if (above) "above" else "below"} the entry price"
      }
    }
    checkExit(takeProfit, "Take profit", side == OrderSide.BUY)
    checkExit(stopLoss, "Stop loss", side == OrderSide.SELL)
  }
  val activeBuilderFeeBps = builderFeeFor(market.kind == MarketKind.SPOT)
  val activeBuilderAddress = if (activeBuilderFeeBps != null) builderAddress else null
  return OrderRequest(
    marketId = market.id,
    side = side,
    type = orderType,
    size = sizeInput,
    price = limitPriceInput.takeIf(String::isNotBlank),
    slippageBps = slippageBps,
    takeProfitTrigger = takeProfit,
    takeProfitLimit = takeProfit,
    stopLossTrigger = stopLoss,
    stopLossLimit = stopLoss,
    builderAddress = activeBuilderAddress,
    builderFeeBps = activeBuilderFeeBps,
    twap = if (orderType == OrderType.TWAP) twapSchedule() else null,
  )
}

/** The TWAP schedule as entered, falling back to one slice a minute for an hour. */
internal fun TradeUiState.twapSchedule(): TwapSchedule =
  TwapSchedule(
    frequencySeconds = (twapFrequencyMinutesInput.toULongOrNull()?.times(60uL) ?: 60uL).toLong(),
    durationSeconds = (twapDurationMinutesInput.toULongOrNull()?.times(60uL) ?: 3600uL).toLong(),
  )

/** The order the market's rules accept for [side], or the reasons they don't, one per line. */
internal fun TradeUiState.checkedOrder(side: OrderSide): OrderRequest {
  val market = quote?.market ?: error("Select a market first")
  return when (val check = market.rules.check(orderRequest(side), marketDetails.orderBook)) {
    is OrderCheck.Valid -> check.order
    is OrderCheck.Invalid -> error(check.errors.joinToString("\n"))
  }
}

internal fun TradeUiState.orderInputError(side: OrderSide): String? = runCatching {
  require(sizeInput.isNotBlank()) { "Enter an order size" }
  val market = quote?.market ?: error("Select a market first")
  if (market.kind != MarketKind.SPOT) {
    require(leverage in 1..market.maxLeverage.coerceAtMost(100)) {
      "Choose leverage within this market’s limit"
    }
  }
  if (orderType == OrderType.TWAP) {
    val duration = twapDurationMinutesInput.toULongOrNull()?.times(60uL) ?: 3600uL
    val frequency = twapFrequencyMinutesInput.toULongOrNull()?.times(60uL) ?: 60uL
    require(duration in 120uL..86400uL) { "Duration must be between 2 minutes and 24 hours" }
    if (capabilities.twapInterval) {
      require(frequency >= 60uL) { "Frequency must be at least 1 minute" }
      require(frequency <= duration) { "Frequency cannot exceed duration" }
    }
  }
  checkedOrder(side)
  balanceError(side)?.let { error(it) }
}
  .exceptionOrNull()
  ?.message

/**
 * Why the account can't pay for the order, judged from the balance the ticket shows: margin for
 * perpetuals, holdings for spot. Null when it can, or while that balance isn't known.
 */
internal fun TradeUiState.balanceError(side: OrderSide): String? {
  val market = quote?.market ?: return null
  val size = sizeInput.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
  if (market.kind == MarketKind.SPOT && side == OrderSide.SELL) {
    val held = baseBalance ?: return null
    return if (exceeds(size, held)) "You have ${formatQuantity(held, 4)} ${market.symbol} to sell" else null
  }
  val available = quoteBalance ?: return null
  val estimate = orderEstimate(side) ?: return null
  val isSpot = market.kind == MarketKind.SPOT
  val feeRate = (if (isSpot) fees?.spot else fees?.perp)?.takerRate ?: 0.0
  val fee = estimate.value * feeRate + (estimate.builderFeeAmount ?: 0.0)
  if (isSpot) {
    val asset = market.quoteAsset ?: "USDC"
    return if (exceeds(estimate.value + fee, available)) {
      "Not enough $asset for this order. Available ${formatBalance(available)}"
    } else null
  }
  // Closing part of an opposite position releases margin instead of using it.
  val position = positionSize?.toDoubleOrNull() ?: 0.0
  val opposite = if (side == OrderSide.BUY) position < 0 else position > 0
  val opening = if (opposite) (size - abs(position)).coerceAtLeast(0.0) else size
  if (opening == 0.0) return null
  val needed = estimate.margin * (opening / size) + fee
  return if (exceeds(needed, available)) {
    "Not enough margin for this order. Available ${formatBalance(available)}"
  } else null
}

private fun exceeds(needed: Double, available: Double): Boolean =
  needed > available + maxOf(abs(available), 1.0) * 1e-9

internal data class OrderEstimate(
  val entryPrice: Double,
  val value: Double,
  val profit: Double?,
  val loss: Double?,
  val margin: Double,
  val builderFeeAmount: Double? = null,
  val builderFeeBps: Int? = null,
)

/** Gross P&L for the entered base-asset size; leverage must not multiply this value again. */
internal fun TradeUiState.orderEstimate(side: OrderSide): OrderEstimate? {
  val entry = entryPrice(side)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
  val size = sizeInput.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
  val value = (entry * size).takeIf(Double::isFinite) ?: return null
  val isSpot = quote?.market?.kind == MarketKind.SPOT
  val effLeverage = if (isSpot) 1 else leverage
  if (effLeverage < 1) return null
  val direction = if (side == OrderSide.BUY) 1 else -1
  fun pnl(input: String): Double? =
    if (isSpot) null
    else
      input
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0 }
        ?.let { (it - entry) * size * direction }
        ?.takeIf(Double::isFinite)
  val activeFeeBps = builderFeeFor(isSpot)
  val builderFeeAmount = activeFeeBps?.let { bps -> value * (bps.toDouble() / 10_000.0) }
  return OrderEstimate(
    entryPrice = entry,
    value = value,
    profit = pnl(takeProfitInput),
    loss = pnl(stopLossInput),
    margin = value / effLeverage,
    builderFeeAmount = builderFeeAmount,
    builderFeeBps = activeFeeBps,
  )
}

/**
 * The builder fee an order carries, in bps. Spot and perpetuals keep separate on-chain approvals
 * and their own rates, and an order naming a builder its product hasn't approved is rejected
 * outright, so each product carries its fee only once approved.
 */
internal fun TradeUiState.builderFeeFor(isSpot: Boolean): Int? {
  val approved = if (isSpot) spotBuilderApproved else builderApproved
  val bps = if (isSpot) spotBuilderFeeBps else builderFeeBps
  return bps?.takeIf { approved && it > 0 }
}
