package xyz.mcxross.flare.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    FlareAmountField(takeProfit, onTakeProfit, label = "Take-profit price", unit = "USDC",
      placeholder = "Optional", enabled = enabled, modifier = Modifier.fillMaxWidth())
    FlareAmountField(stopLoss, onStopLoss, label = "Stop-loss price", unit = "USDC",
      placeholder = "Optional", enabled = enabled, modifier = Modifier.fillMaxWidth())
    Text("Exit prices trigger a closing order. Execution price can vary.",
      style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
  }
}
