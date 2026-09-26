package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.Outcome
import xyz.mcxross.flare.design.OutcomeIcon
import xyz.mcxross.flare.design.rememberOutcomeReveal


@Composable
internal fun OrderResult(
  state: TradeUiState,
  side: OrderSide,
  minHeight: Dp,
  onOpenActivity: (() -> Unit)?,
  onViewTransaction: () -> Unit,
) {
  OrderOutcome(
    state, side, minHeight, Outcome.SUCCESS,
    title = "Order submitted",
    message = "Your order is confirmed on-chain. Follow its status and fills in Activity.",
  ) {
    onOpenActivity?.let { ActionRow("View Activity", onClick = it) }
    ActionRow("View transaction", onClick = onViewTransaction)
  }
}

@Composable
internal fun OrderFailure(state: TradeUiState, side: OrderSide, minHeight: Dp, onEdit: () -> Unit) {
  OrderOutcome(
    state, side, minHeight, Outcome.FAILURE,
    title = "Order not placed",
    message = state.orderError ?: "Your order wasn’t placed.",
  ) {
    ActionRow("Edit order", onClick = onEdit)
  }
}

/** The outcome, what was placed, and where to go next. */
@Composable
private fun OrderOutcome(
  state: TradeUiState,
  side: OrderSide,
  minHeight: Dp,
  outcome: Outcome,
  title: String,
  message: String,
  links: @Composable ColumnScope.() -> Unit,
) {
  val (order, type) = state.orderSummary(side)
  val revealed = rememberOutcomeReveal()
  SpacedGroups(minHeight) {
    Column {
      OutcomeIcon(outcome)
      Column(revealed) {
        Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
          message,
          Modifier.padding(top = 6.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = FlareColors.TextSecondary,
        )
      }
    }
    Column(revealed) {
      DetailRow("Order", order)
      DetailRow("Type", type)
      state.orderEstimate(side)?.let { DetailRow("Order value", formatBalance(it.value)) }
    }
    Column(revealed, content = links)
  }
}
