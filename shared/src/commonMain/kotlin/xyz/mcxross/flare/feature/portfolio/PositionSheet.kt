package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.shortAddress

@Composable
fun PositionSheet(state: PortfolioUiState, onIntent: (PortfolioIntent) -> Unit) {
  val position =
    state.account.positions.firstOrNull { it.market == state.managedPositionMarket } ?: return
  val symbol = state.marketSymbols[position.market] ?: shortAddress(position.market)
  val mark = state.markPrices[position.market]
  val size = position.size.toDoubleOrNull()
  val pnl = mark?.let { price -> size?.let { (price - position.entryPrice) * it } }
  var confirmClose by remember { mutableStateOf(false) }
  FlareSheet(symbol, { if (!state.busy) onIntent(PortfolioIntent.DismissPositionManagement) }) {
    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
      Text(
        "${if (position.isLong) "Long" else "Short"} ${formatQuantity(abs(size ?: 0.0))} · " +
          "${position.leverage}× · ${if (position.isIsolated) "isolated" else "cross"}",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
      )
      DetailRow(
        "Unrealized P&L",
        pnl?.let { (if (it >= 0) "+" else "") + formatBalance(it) } ?: "—",
      )
      DetailRow("Entry price", formatPrice(position.entryPrice))
      DetailRow("Mark price", mark?.let(::formatPrice) ?: "—")
      if (position.unrealizedFunding != 0.0)
        DetailRow(
          "Unrealized funding",
          (if (position.unrealizedFunding >= 0) "+" else "") +
            formatBalance(position.unrealizedFunding),
        )
      DetailRow("Liquidation price", formatPrice(position.estimatedLiquidationPrice))
      position.takeProfitTriggerPrice?.let { DetailRow("Take profit", formatPrice(it)) }
      position.stopLossTriggerPrice?.let { DetailRow("Stop loss", formatPrice(it)) }
      Text(
        "Set an exit",
        Modifier.padding(top = 24.dp),
        style = MaterialTheme.typography.titleMedium,
      )
      OutlinedTextField(
        value = state.takeProfitInput,
        onValueChange = { onIntent(PortfolioIntent.ChangeTakeProfit(it)) },
        label = { Text("Take-profit price") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      )
      OutlinedTextField(
        value = state.stopLossInput,
        onValueChange = { onIntent(PortfolioIntent.ChangeStopLoss(it)) },
        label = { Text("Stop-loss price") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      )
      Text(
        "Leverage and margin mode can only change once this position is closed.",
        Modifier.padding(top = 12.dp),
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    state.actionError?.let { error ->
      ActionNotice(error, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
    }
    (state.positionTransaction as? TransactionState.Failed)?.selfPayEstimateOctas?.let { estimate ->
      ActionNotice(
        "Flare can’t cover the network fee right now. Your wallet would pay about " +
          "${estimate.toDecimalString(8)} APT.",
        Modifier.padding(top = 12.dp),
      )
      FlareButton(
        "Pay the fee and continue",
        { onIntent(PortfolioIntent.ConfirmPositionSelfPay) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
      if (state.apiWalletNeedsTopUp)
        state.suggestedTopUpOctas?.let { amount ->
          ActionNotice(
            "This device needs ${amount.toDecimalString(8)} APT to pay the fee itself.",
            Modifier.padding(top = 12.dp),
          )
          FlareButton(
            "Send APT from your wallet",
            { onIntent(PortfolioIntent.TopUpApiWallet) },
            Modifier.fillMaxWidth().padding(top = 8.dp),
            enabled = !state.busy,
            style = FlareButtonStyle.OUTLINE,
          )
        }
    }
    FlareButton(
      if (state.busy) "Working…" else "Save exits",
      { onIntent(PortfolioIntent.SubmitTpSl) },
      Modifier.fillMaxWidth().padding(top = 16.dp),
      enabled = state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank(),
      working = state.busy,
    )
    FlareButton(
      "Close position",
      { confirmClose = true },
      Modifier.fillMaxWidth().padding(top = 8.dp),
      enabled = !state.busy,
      style = FlareButtonStyle.SELL,
    )
  }
  if (confirmClose)
    AlertDialog(
      onDismissRequest = { confirmClose = false },
      title = { Text("Close this position?") },
      text = {
        Text(
          "Flare will ${if (position.isLong) "sell" else "buy"} " +
            "${formatQuantity(abs(size ?: 0.0))} $symbol at the market price."
        )
      },
      confirmButton = {
        TextButton({
          confirmClose = false
          onIntent(PortfolioIntent.ClosePosition)
        }) {
          Text("Close position")
        }
      },
      dismissButton = { TextButton({ confirmClose = false }) { Text("Keep it open") } },
      containerColor = FlareColors.Surface,
    )
}
