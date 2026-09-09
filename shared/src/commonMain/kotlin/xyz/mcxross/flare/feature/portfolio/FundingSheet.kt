package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.TransactionReceipt

@Composable
fun FundingSheet(state: PortfolioUiState, onIntent: (PortfolioIntent) -> Unit) {
  val mode = state.fundingMode ?: return
  val committed = state.fundingTransaction as? TransactionState.Committed
  FlareSheet(
    if (committed != null) {
      if (mode == FundingMode.DEPOSIT) "Funds added" else "Withdrawal complete"
    } else if (mode == FundingMode.DEPOSIT) "Add funds" else "Withdraw funds",
    { if (!state.busy) onIntent(PortfolioIntent.CloseFunding) },
  ) {
    if (committed != null) {
      TransactionReceipt(
        if (mode == FundingMode.DEPOSIT) "Your USDC is now in your trading account."
        else "Your USDC has been returned to your owner wallet.",
        committed.hash,
        { onIntent(PortfolioIntent.CloseFunding) },
        enabled = !state.busy,
      )
      return@FlareSheet
    }
    Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
      Text("USDC on Aptos", style = MaterialTheme.typography.titleMedium)
      Text(
        "You’ll approve this transfer with your wallet.",
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = FlareColors.TextSecondary,
      )
      if (mode == FundingMode.WITHDRAW) {
        state.account.overview?.let {
          DetailRow(
            if (state.account.stale) "Last available balance" else "Available to withdraw",
            "${formatQuantity(it.crossWithdrawableBalance, 6)} USDC",
          )
        }
      }
      OutlinedTextField(
        value = state.fundingAmount,
        onValueChange = { onIntent(PortfolioIntent.ChangeFundingAmount(it)) },
        label = { Text("Amount in USDC") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      )
      state.fundingTransaction?.let { transaction ->
        Text(
          transaction.label(),
          Modifier.padding(top = 8.dp),
          color =
            if (transaction is TransactionState.Failed) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          style = MaterialTheme.typography.labelMedium,
        )
        if (transaction is TransactionState.Failed)
          transaction.selfPayEstimateOctas?.let { estimate ->
            Text(
              "Sponsorship was rejected. Self-payment is estimated at " +
                "${estimate.toDecimalString(8)} APT.",
              Modifier.padding(top = 8.dp),
              color = MaterialTheme.colorScheme.tertiary,
              style = MaterialTheme.typography.bodyMedium,
            )
            FlareButton(
              "Confirm and self-pay",
              { onIntent(PortfolioIntent.ConfirmSelfPay) },
              Modifier.fillMaxWidth().padding(top = 8.dp),
              enabled = !state.busy,
              style = FlareButtonStyle.OUTLINE,
            )
          }
      }
      FlareButton(
        if (state.busy) "Authorizing…"
        else if (mode == FundingMode.DEPOSIT) "Deposit USDC" else "Withdraw USDC",
        { onIntent(PortfolioIntent.SubmitFunding) },
        Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = !state.busy && state.fundingAmount.isNotBlank(),
      )
      FlareButton(
        "Close",
        { onIntent(PortfolioIntent.CloseFunding) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
  }
}
