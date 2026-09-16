package xyz.mcxross.flare.feature.trade

import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.OrderDraft
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.SlippageBps
import xyz.mcxross.flare.decibel.model.toChainUnits
import xyz.mcxross.flare.decibel.model.validate

internal fun TradeUiState.entryPrice(side: OrderSide): String? =
  if (orderType == OrderType.LIMIT) limitPriceInput.takeIf(String::isNotBlank)
  else if (side == OrderSide.BUY) marketDetails.orderBook?.bestAsk
  else marketDetails.orderBook?.bestBid

/** Entry and attached exit prices remain decimal strings until exact chain-unit validation. */
internal fun TradeUiState.orderDraft(side: OrderSide): OrderDraft {
  val market = quote?.market ?: error("Select a market first")
  val isSpot = market.assetType == AssetType.SPOT
  val takeProfit =
    if (isSpot) null else takeProfitInput.takeIf(String::isNotBlank)?.let(::DecimalInput)
  val stopLoss = if (isSpot) null else stopLossInput.takeIf(String::isNotBlank)?.let(::DecimalInput)
  if (takeProfit != null || stopLoss != null) {
    val entry = entryPrice(side) ?: error("An entry price is needed to check your exits")
    val entryUnits =
      DecimalInput(entry).toChainUnits("Entry price", market.priceDecimals).getOrThrow()
    fun checkExit(input: DecimalInput?, label: String, above: Boolean) {
      if (input == null) return
      val units = input.toChainUnits(label, market.priceDecimals).getOrThrow()
      require(units > 0uL) { "$label must be greater than zero" }
      require(if (above) units > entryUnits else units < entryUnits) {
        "$label must be ${if (above) "above" else "below"} the entry price"
      }
    }
    checkExit(takeProfit, "Take profit", side == OrderSide.BUY)
    checkExit(stopLoss, "Stop loss", side == OrderSide.SELL)
  }
  return OrderDraft(
    marketAddress = market.address,
    side = side,
    type = orderType,
    size = DecimalInput(sizeInput),
    limitPrice = limitPriceInput.takeIf(String::isNotBlank)?.let(::DecimalInput),
    slippage = SlippageBps(slippageBps.toUInt()),
    takeProfitTriggerPrice = takeProfit,
    takeProfitLimitPrice = takeProfit,
    stopLossTriggerPrice = stopLoss,
    stopLossLimitPrice = stopLoss,
  )
}

internal fun TradeUiState.orderInputError(side: OrderSide): String? = runCatching {
  require(sizeInput.isNotBlank()) { "Enter an order size" }
  val market = quote?.market ?: error("Select a market first")
  if (market.assetType != AssetType.SPOT) {
    require(leverage in 1..market.maxLeverage.coerceAtMost(100)) {
      "Choose leverage within this market’s limit"
    }
  }
  val result = orderDraft(side).validate(market, marketDetails.orderBook)
  require(result.isValid) { result.errors.joinToString("\n") { it.message() } }
}
  .exceptionOrNull()
  ?.message

internal data class OrderEstimate(
  val entryPrice: Double,
  val value: Double,
  val profit: Double?,
  val loss: Double?,
  val margin: Double,
)

/** Gross P&L for the entered base-asset size; leverage must not multiply this value again. */
internal fun TradeUiState.orderEstimate(side: OrderSide): OrderEstimate? {
  val entry = entryPrice(side)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
  val size = sizeInput.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
  val value = (entry * size).takeIf(Double::isFinite) ?: return null
  val isSpot = quote?.market?.assetType == AssetType.SPOT
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
  return OrderEstimate(entry, value, pnl(takeProfitInput), pnl(stopLossInput), value / effLeverage)
}
