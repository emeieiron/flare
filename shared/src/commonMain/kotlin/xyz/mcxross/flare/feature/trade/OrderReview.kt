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

@Composable
internal fun OrderReview(state: TradeUiState, side: OrderSide) {
  val quote = state.quote ?: return
  val estimate = state.orderEstimate(side)
  val isSpot = quote.market.assetType == AssetType.SPOT
  val exits = !isSpot && (state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank())
  Text(
    "${if (side == OrderSide.BUY) "Buy" else "Sell"} ${state.sizeInput} ${quote.market.symbol}",
    style = MaterialTheme.typography.titleLarge,
    color = if (side == OrderSide.BUY) FlareColors.Positive else FlareColors.Negative,
  )
  val subtitle =
    if (isSpot) "${state.orderType.name.lowercase().replaceFirstChar(Char::uppercase)} · Spot"
    else
      "${state.orderType.name.lowercase().replaceFirstChar(Char::uppercase)} · ${if (side == OrderSide.BUY) "Long" else "Short"} · ${state.leverage}× ${if (state.positionIsolated ?: quote.market.isIsolatedOnly) "isolated" else "cross"}"
  Text(
    subtitle,
    color = FlareColors.TextSecondary,
    modifier = Modifier.padding(top = 4.dp),
  )
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
  // Only exits the person actually set are worth a row; the rest is noise on a confirmation screen.
  if (!isSpot) {
    OutcomeRow("Take profit", state.takeProfitInput, estimate?.profit, FlareColors.Positive)
    OutcomeRow("Stop loss", state.stopLossInput, estimate?.loss, FlareColors.Negative)
  }
  state.limitDistanceFromMark(side)?.let { distance ->
    Text(
      "This limit price is ${formatPercent(distance * 100)} " +
        "${if (distance < 0) "below" else "above"} the current price, so the order may rest " +
        "unfilled.",
      color = FlareColors.Negative,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(top = 12.dp),
    )
  }
  Text(
    if (isSpot) {
      "Estimates assume a full fill at the price shown, before network and trading fees."
    } else if (exits) {
      "Estimates assume a full fill at the prices shown, before fees and funding. An exit places a " +
        "limit order when it triggers, so execution is not guaranteed."
    } else {
      "Estimates assume a full fill at the price shown, before fees and funding. You can add exits " +
        "later from Portfolio."
    },
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodySmall,
    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
  )
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
