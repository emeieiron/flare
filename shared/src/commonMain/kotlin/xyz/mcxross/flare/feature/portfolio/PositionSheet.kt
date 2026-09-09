package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareSheet

@Composable
fun PositionSheet(state: PortfolioUiState, onIntent: (PortfolioIntent) -> Unit) {
  val managedPosition =
    state.account.positions.firstOrNull { it.market == state.managedPositionMarket }
  managedPosition?.let { position ->
    FlareSheet(
      "Manage position",
      { if (!state.busy) onIntent(PortfolioIntent.DismissPositionManagement) },
    ) {
      Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(28.dp))

        Text(
          "${(state.marketSymbols[position.market] ?: shortAddress(position.market))} · exact size ${position.size}",
          Modifier.padding(top = 4.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelMedium,
        )
        position.takeProfitTriggerPrice?.let { DetailRow("Take profit", formatPrice(it)) }
        position.stopLossTriggerPrice?.let { DetailRow("Stop loss", formatPrice(it)) }
        Text(
          "Leverage · ${position.leverage}×",
          Modifier.padding(top = 16.dp),
          style = MaterialTheme.typography.labelMedium,
        )
        Text(
          "Decibel requires this market’s position to be closed before changing leverage or margin mode.",
          Modifier.padding(top = 8.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
          value = state.takeProfitInput,
          onValueChange = { onIntent(PortfolioIntent.ChangeTakeProfit(it)) },
          label = { Text("Take-profit trigger / limit") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        OutlinedTextField(
          value = state.stopLossInput,
          onValueChange = { onIntent(PortfolioIntent.ChangeStopLoss(it)) },
          label = { Text("Stop-loss trigger / limit") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        FlareButton(
          "Set TP / SL",
          { onIntent(PortfolioIntent.SubmitTpSl) },
          Modifier.fillMaxWidth().padding(top = 12.dp),
          enabled =
            !state.busy && (state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank()),
        )
        FlareButton(
          "Close full position",
          { onIntent(PortfolioIntent.ClosePosition) },
          Modifier.fillMaxWidth().padding(top = 8.dp),
          enabled = !state.busy,
          style = FlareButtonStyle.SELL,
        )
        state.positionTransaction?.let { transaction ->
          Text(
            transaction.label(),
            Modifier.padding(top = 12.dp),
            color =
              if (transaction is TransactionState.Failed) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
          )
          if (transaction is TransactionState.Failed) {
            transaction.selfPayEstimateOctas?.let { estimate ->
              Text(
                "Sponsorship was rejected. Estimated self-pay cost: " +
                  "${estimate.toDecimalString(8)} APT.",
                Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodyMedium,
              )
              FlareButton(
                "Confirm and self-pay",
                { onIntent(PortfolioIntent.ConfirmPositionSelfPay) },
                Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = !state.busy,
                style = FlareButtonStyle.OUTLINE,
              )
              if (state.apiWalletNeedsTopUp)
                state.suggestedTopUpOctas?.let { amount ->
                  Text(
                    "The API wallet needs APT for self-payment. Transfer " +
                      "${amount.toDecimalString(8)} APT from the owner wallet, then unlock the API wallet again.",
                    Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                  )
                  FlareButton(
                    "Top up API wallet",
                    { onIntent(PortfolioIntent.TopUpApiWallet) },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !state.busy,
                    style = FlareButtonStyle.OUTLINE,
                  )
                }
            }
          }
        }
        state.topUpTransaction?.let { transaction ->
          Text(
            "API-wallet top-up · ${transaction.label()}",
            Modifier.padding(top = 12.dp),
            color =
              if (transaction is TransactionState.Failed) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
          )
        }
        FlareButton(
          "Done",
          { onIntent(PortfolioIntent.DismissPositionManagement) },
          Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
          enabled = !state.busy,
          style = FlareButtonStyle.OUTLINE,
        )
      }
    }
  }
}
