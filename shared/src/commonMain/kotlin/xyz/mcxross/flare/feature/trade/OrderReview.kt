package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareColors

@Composable
internal fun OrderReview(state: TradeUiState, side: OrderSide) {
  val quote = state.quote ?: return
  val estimate = state.orderEstimate(side)
  Text(
    "${if (side == OrderSide.BUY) "Buy" else "Sell"} ${state.sizeInput} ${quote.market.symbol}",
    style = MaterialTheme.typography.titleLarge,
    color = if (side == OrderSide.BUY) FlareColors.Positive else FlareColors.Negative,
  )
  Text(
    "${state.orderType.name.lowercase().replaceFirstChar(Char::uppercase)} · ${if (side == OrderSide.BUY) "Long" else "Short"} · ${state.leverage}× ${if (state.positionIsolated ?: quote.market.isIsolatedOnly) "isolated" else "cross"}",
    color = FlareColors.TextSecondary,
    modifier = Modifier.padding(top = 4.dp),
  )
  DetailRow(
    if (state.orderType == OrderType.LIMIT) "Limit price" else "Estimated entry",
    estimate?.entryPrice?.let(::formatPrice) ?: "—",
  )
  DetailRow("Order value", estimate?.value?.let(::formatBalance) ?: "—")
  DetailRow("Estimated margin", estimate?.margin?.let(::formatBalance) ?: "—")
  if (state.orderType == OrderType.MARKET) {
    DetailRow("Maximum entry slippage", "${state.slippageBps / 100.0}%")
  }
  OutcomeRow("Estimated profit", state.takeProfitInput, estimate?.profit, FlareColors.Positive)
  OutcomeRow("Estimated loss", state.stopLossInput, estimate?.loss, FlareColors.Negative)
  Text(
    "Profit and loss assume a full fill at the shown entry and exit prices, before fees and funding." +
      if (state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank())
        " Exits trigger a limit order at the entered price; execution is not guaranteed."
      else " No take-profit or stop-loss exit is set.",
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodySmall,
    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
  )
}

@Composable
private fun OutcomeRow(label: String, price: String, pnl: Double?, color: Color) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(
        if (price.isBlank()) "No exit set"
        else "At ${price.toDoubleOrNull()?.let(::formatPrice) ?: price}",
        style = MaterialTheme.typography.labelSmall,
        color = FlareColors.TextSecondary,
      )
    }
    Text(
      pnl?.let { (if (it > 0) "+" else "") + formatBalance(it) } ?: "Not set",
      style = MaterialTheme.typography.titleLarge,
      color = if (pnl == null) FlareColors.TextSecondary else color,
    )
  }
}
