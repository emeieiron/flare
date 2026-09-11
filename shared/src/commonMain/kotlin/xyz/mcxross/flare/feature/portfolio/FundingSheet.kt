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
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.TransactionReceipt
import xyz.mcxross.flare.design.shortAddress

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
        else "Your USDC has been sent.",
        committed.hash,
        { onIntent(PortfolioIntent.CloseFunding) },
        enabled = !state.busy,
      )
      return@FlareSheet
    }
    Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
      Text("USDC on Aptos", style = MaterialTheme.typography.titleMedium)
      DetailRow("Account", state.account.account?.let(::shortAddress).orEmpty())
      if (mode == FundingMode.WITHDRAW) {
        val destination =
          state.withdrawalDestination.ifBlank { state.profile.ownerAddress.orEmpty() }
        DetailRow("To", shortAddress(destination))
        OutlinedTextField(
          state.withdrawalDestination,
          { onIntent(PortfolioIntent.ChangeWithdrawalDestination(it)) },
          label = { Text("Different address (optional)") },
          placeholder = { Text("Your wallet") },
          singleLine = true,
          enabled = !state.busy && state.pendingWithdrawal == null,
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        if (
          state.withdrawalDestination.isNotBlank() &&
            state.withdrawalDestination != state.profile.ownerAddress
        ) {
          Text(
            "Another address takes two transactions: a withdrawal to your wallet, then a transfer.",
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = FlareColors.TextSecondary,
          )
        }
        if (state.pendingWithdrawal?.withdrawalCommitted == true) {
          Text(
            "The withdrawal is done. Continue to send it to the address you chose.",
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
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
        enabled = !state.busy && state.pendingWithdrawal == null,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      )
      state.actionError?.let { error ->
        ActionNotice(error, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
      }
      (state.fundingTransaction as? TransactionState.Failed)?.selfPayEstimateOctas?.let { estimate ->
        ActionNotice(
          "Flare can’t cover the network fee right now. Your wallet would pay about " +
            "${estimate.toDecimalString(8)} APT.",
          Modifier.padding(top = 12.dp),
        )
        FlareButton(
          "Pay the fee and continue",
          { onIntent(PortfolioIntent.ConfirmSelfPay) },
          Modifier.fillMaxWidth().padding(top = 8.dp),
          enabled = !state.busy,
          style = FlareButtonStyle.OUTLINE,
        )
      }
      FlareButton(
        text =
          when {
            state.busy && mode == FundingMode.DEPOSIT -> "Adding funds…"
            state.busy -> "Sending…"
            state.pendingWithdrawal?.withdrawalCommitted == true -> "Continue transfer"
            mode == FundingMode.DEPOSIT -> "Deposit USDC"
            else -> "Confirm withdrawal"
          },
        onClick = { onIntent(PortfolioIntent.SubmitFunding) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = state.fundingAmount.isNotBlank(),
        working = state.busy,
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
