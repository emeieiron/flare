package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPercent
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareColors

/** "Buy 0.01 BTC" and "Market · Long · 1× cross": the order in two lines, shared by review and result. */
internal fun TradeUiState.orderSummary(side: OrderSide): Pair<String, String> {
  val market = quote?.market
  val isSpot = market?.assetType == AssetType.SPOT
  val title = "${if (side == OrderSide.BUY) "Buy" else "Sell"} $sizeInput ${market?.symbol.orEmpty()}".trim()
  val typeLabel =
    if (orderType == OrderType.TWAP) "TWAP"
    else orderType.name.lowercase().replaceFirstChar(Char::uppercase)
  val margin = if (positionIsolated ?: market?.isIsolatedOnly == true) "isolated" else "cross"
  val subtitle =
    if (isSpot) "$typeLabel · Spot"
    else "$typeLabel · ${if (side == OrderSide.BUY) "Long" else "Short"} · $leverage× $margin"
  return title to subtitle
}

@Composable
internal fun ReviewHeading(state: TradeUiState, side: OrderSide) {
  val (title, subtitle) = state.orderSummary(side)
  Column {
    Text(
      title,
      style = MaterialTheme.typography.titleLarge,
      color = if (side == OrderSide.BUY) FlareColors.Positive else FlareColors.Negative,
    )
    Text(subtitle, Modifier.padding(top = 4.dp), color = FlareColors.TextSecondary)
  }
}

@Composable
internal fun ReviewDetails(state: TradeUiState, side: OrderSide) {
  val quote = state.quote ?: return
  val estimate = state.orderEstimate(side)
  val isSpot = quote.market.assetType == AssetType.SPOT
  Column {
    DetailRow(
      if (state.orderType == OrderType.LIMIT) "Limit price" else "Estimated entry",
      estimate?.entryPrice?.let(::formatPrice) ?: "—",
    )
    DetailRow("Order value", estimate?.value?.let(::formatBalance) ?: "—")
    if (!isSpot) {
      DetailRow("Estimated margin", estimate?.margin?.let(::formatBalance) ?: "—")
    }
    if (state.orderType == OrderType.MARKET) {
      DetailRow("Maximum entry slippage", "${state.slippageBps / 100.0}%")
    }
    if (state.orderType == OrderType.TWAP) {
      DetailRow("TWAP duration", "${state.twapDurationMinutesInput} min")
      DetailRow("Slice interval", "${state.twapFrequencyMinutesInput} min")
    }
    val productFees = if (isSpot) state.fees?.spot else state.fees?.perp
    if (productFees != null && estimate != null) {
      val takerFee = estimate.value * productFees.takerRate
      val makerFee = estimate.value * productFees.makerRate
      val feeText = if (state.orderType == OrderType.MARKET) formatBalance(takerFee)
        else "${formatBalance(minOf(makerFee, takerFee))}–${formatBalance(maxOf(makerFee, takerFee))}"
      DetailRow(if (isSpot) "Trading fee (USD estimate)" else "Estimated trading fee", feeText)
    } else {
      DetailRow("Trading fee", "Unavailable")
    }
    if (estimate?.builderFeeAmount != null && estimate.builderFeeAmount > 0) {
      val bps = estimate.builderFeeBps ?: 5
      val amount = formatBalance(estimate.builderFeeAmount)
      // "<$0.01" already reads as a small addition; a leading plus only clutters it.
      DetailRow("Builder fee (${bps / 100.0}%)", if (amount.startsWith("<")) amount else "+$amount")
    }
  }
}

/** Only exits the person actually set are worth a row; the rest is noise on a confirmation screen. */
@Composable
internal fun ReviewExits(state: TradeUiState, side: OrderSide) {
  val isSpot = state.quote?.market?.assetType == AssetType.SPOT
  if (isSpot || (state.takeProfitInput.isBlank() && state.stopLossInput.isBlank())) return
  val estimate = state.orderEstimate(side)
  Column {
    OutcomeRow("Take profit", state.takeProfitInput, estimate?.profit, FlareColors.Positive)
    OutcomeRow("Stop loss", state.stopLossInput, estimate?.loss, FlareColors.Negative)
  }
}

@Composable
internal fun ReviewFootnote(state: TradeUiState, side: OrderSide) {
  val isSpot = state.quote?.market?.assetType == AssetType.SPOT
  val exits = !isSpot && (state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank())
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    state.limitDistanceFromMark(side)?.let { distance ->
      Text(
        "This limit price is ${formatPercent(distance * 100)} " +
          "${if (distance < 0) "below" else "above"} the current price, so the order may rest " +
          "unfilled.",
        color = FlareColors.Negative,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    Text(
      when {
        isSpot -> "Fees are deducted from the asset you receive. Final fees depend on execution."
        exits ->
          "Estimates assume a full fill at the price shown. Exits place a limit order when " +
            "triggered, so execution isn’t guaranteed."
        else -> "Estimates assume a full fill at the price shown, before fees and funding."
      },
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

/** How far a resting limit price sits from the mark, once it is far enough to be worth saying. */
private fun TradeUiState.limitDistanceFromMark(side: OrderSide): Double? {
  if (orderType != OrderType.LIMIT) return null
  val mark = quote?.markPrice?.takeIf { it > 0 } ?: return null
  val limit = limitPriceInput.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
  val distance = (limit - mark) / mark
  val restsUnfilled = if (side == OrderSide.BUY) distance < 0 else distance > 0
  return distance.takeIf { restsUnfilled && abs(it) >= FAR_FROM_MARK }
}

private const val FAR_FROM_MARK = 0.1

@Composable
private fun OutcomeRow(label: String, price: String, pnl: Double?, color: Color) {
  if (price.isBlank()) return
  Row(
    Modifier.fillMaxWidth().padding(vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(
        "At ${price.toDoubleOrNull()?.let(::formatPrice) ?: price}",
        style = MaterialTheme.typography.labelSmall,
        color = FlareColors.TextSecondary,
      )
    }
    Text(
      pnl?.let { (if (it > 0) "+" else "") + formatBalance(it) } ?: "—",
      style = MaterialTheme.typography.titleLarge,
      color = if (pnl == null) FlareColors.TextSecondary else color,
    )
  }
}
