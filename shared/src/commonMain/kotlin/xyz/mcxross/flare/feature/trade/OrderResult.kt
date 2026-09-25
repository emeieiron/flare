package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareColors

/** The confirmed order, what was placed, and where to follow it next. */
@Composable
internal fun OrderResult(
  state: TradeUiState,
  side: OrderSide,
  minHeight: Dp,
  onOpenActivity: (() -> Unit)?,
  onViewTransaction: () -> Unit,
) {
  val (title, subtitle) = state.orderSummary(side)
  SpacedGroups(minHeight) {
    Column {
      Box(
        Modifier.size(40.dp).background(FlareColors.Elevated, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = FlareColors.Positive)
      }
      Text(
        "Order submitted",
        Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.headlineSmall,
      )
      Text(
        "Your order is confirmed on-chain. Follow its status and fills in Activity.",
        Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = FlareColors.TextSecondary,
      )
    }
    Column {
      DetailRow("Order", title)
      DetailRow("Type", subtitle)
      state.orderEstimate(side)?.let { DetailRow("Order value", formatBalance(it.value)) }
    }
    Column {
      onOpenActivity?.let { ActionRow("View Activity", onClick = it) }
      ActionRow("View transaction", onClick = onViewTransaction)
    }
  }
}
