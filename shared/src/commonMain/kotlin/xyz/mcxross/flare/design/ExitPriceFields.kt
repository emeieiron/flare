package xyz.mcxross.flare.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExitPriceFields(
  takeProfit: String,
  stopLoss: String,
  onTakeProfit: (String) -> Unit,
  onStopLoss: (String) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FlareAmountField(takeProfit, onTakeProfit, Modifier.weight(1f), label = "Take profit",
        unit = "USDC", enabled = enabled, compact = true)
      FlareAmountField(stopLoss, onStopLoss, Modifier.weight(1f), label = "Stop loss",
        unit = "USDC", enabled = enabled, compact = true)
    }
    Text("Exit prices trigger a closing order. Execution price can vary.",
      style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
  }
}
