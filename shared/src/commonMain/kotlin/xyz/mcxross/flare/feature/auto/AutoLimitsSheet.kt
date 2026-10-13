package xyz.mcxross.flare.feature.auto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareTextField
import xyz.mcxross.flare.strategies.AutoRisk
import xyz.mcxross.flare.strategies.AutoRunConfig
import xyz.mcxross.flare.strategies.AutoRunMode

@Composable
internal fun AutoLimitsSheet(
  config: AutoRunConfig,
  symbol: String,
  quoteAsset: String,
  onSave: (AutoRunConfig) -> Unit,
  onDismiss: () -> Unit,
) {
  var orderSize by remember { mutableStateOf(config.orderSize) }
  var maxPosition by remember { mutableStateOf(config.maxPositionSize) }
  var maxNotional by remember { mutableStateOf(config.maxTotalNotional) }
  var maxOrders by remember { mutableStateOf(config.maxOrders.toString()) }
  var duration by remember { mutableStateOf(config.durationMinutes.toString()) }
  var cooldown by remember { mutableStateOf(config.cooldownSeconds.toString()) }
  var slippage by remember { mutableStateOf(config.slippageBps.toString()) }
  var leverage by remember { mutableStateOf(config.leverage.toString()) }
  var confidence by remember { mutableStateOf((config.minConfidence * 100).toString()) }
  val candidate = runCatching {
    config.copy(orderSize = orderSize, maxPositionSize = maxPosition, maxTotalNotional = maxNotional,
      maxOrders = maxOrders.toInt(), durationMinutes = duration.toInt(), cooldownSeconds = cooldown.toInt(),
      slippageBps = slippage.toInt(), leverage = leverage.toInt(), minConfidence = confidence.toDouble() / 100)
  }.getOrNull()
  val validation = if (candidate == null) "Enter valid amounts and whole-number limits." else AutoRisk.validate(candidate)
  val valid = candidate != null && validation == null
  FlareSheet("Auto trading limits", onDismiss, heightFraction = 0.94f) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text("Limits apply to this run. The order-value budget reserves each submitted order’s value and excludes fees.",
        color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      LimitInput("Order size ($symbol)", orderSize, { orderSize = it })
      LimitInput("Maximum position ($symbol)", maxPosition, { maxPosition = it })
      LimitInput("Order-value budget ($quoteAsset)", maxNotional, { maxNotional = it })
      LimitInput("Maximum order attempts", maxOrders, { maxOrders = it }, integer = true)
      LimitInput("Duration (minutes)", duration, { duration = it }, integer = true)
      LimitInput("Cooldown (seconds)", cooldown, { cooldown = it }, integer = true)
      LimitInput("Slippage (basis points)", slippage, { slippage = it }, integer = true)
      LimitInput("Leverage", leverage, { leverage = it }, integer = true, enabled = config.mode == AutoRunMode.REAL)
      if (config.mode == AutoRunMode.SIMULATION) Text("Simulation uses 1× leverage.",
        color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
      LimitInput("Minimum Jev confidence (%)", confidence, { confidence = it })
      validation?.let { ActionNotice(it) }
    }
    FlareButton("Save limits", { candidate?.let(onSave) }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = valid)
  }
}

@Composable
private fun LimitInput(label: String, value: String, onChange: (String) -> Unit, integer: Boolean = false, enabled: Boolean = true) {
  FlareTextField(value, onChange, Modifier.fillMaxWidth(), label = label, enabled = enabled,
    keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal))
}
