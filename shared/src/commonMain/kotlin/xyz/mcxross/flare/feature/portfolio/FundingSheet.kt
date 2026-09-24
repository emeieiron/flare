package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    title = if (committed != null) "Transfer complete" else "Transfer",
    onDismiss = { if (!state.busy) onIntent(PortfolioIntent.CloseFunding) },
  ) {
    if (committed != null) {
      TransactionReceipt(
        if (mode == FundingMode.DEPOSIT) "Your USDC is now in your trading subaccount."
        else "Your USDC is now in your primary account.",
        committed.hash,
        { onIntent(PortfolioIntent.CloseFunding) },
        enabled = !state.busy,
      )
      return@FlareSheet
    }
    Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
      // Direction selector: To subaccount | To primary
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FlareColors.Canvas)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Box(
          modifier =
            Modifier.weight(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(if (mode == FundingMode.DEPOSIT) FlareColors.Elevated else Color.Transparent)
              .clickable(enabled = !state.busy) {
                if (mode != FundingMode.DEPOSIT) onIntent(PortfolioIntent.OpenFunding(FundingMode.DEPOSIT))
              }
              .padding(vertical = 8.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "To subaccount",
            style = MaterialTheme.typography.labelMedium,
            color = if (mode == FundingMode.DEPOSIT) FlareColors.TextPrimary else FlareColors.TextSecondary,
          )
        }
        Box(
          modifier =
            Modifier.weight(1f)
              .clip(RoundedCornerShape(6.dp))
              .background(if (mode == FundingMode.WITHDRAW) FlareColors.Elevated else Color.Transparent)
              .clickable(enabled = !state.busy) {
                if (mode != FundingMode.WITHDRAW) onIntent(PortfolioIntent.OpenFunding(FundingMode.WITHDRAW))
              }
              .padding(vertical = 8.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "To primary",
            style = MaterialTheme.typography.labelMedium,
            color = if (mode == FundingMode.WITHDRAW) FlareColors.TextPrimary else FlareColors.TextSecondary,
          )
        }
      }

      Spacer(Modifier.height(16.dp))

      Text("USDC on Aptos", style = MaterialTheme.typography.titleMedium)

      if (mode == FundingMode.DEPOSIT) {
        DetailRow("From", "Primary (${shortAddress(state.profile.ownerAddress.orEmpty())})")
        DetailRow("To", "Trading (${shortAddress(state.account.account.orEmpty())})")
      } else {
        DetailRow("From", "Trading (${shortAddress(state.account.account.orEmpty())})")
        DetailRow("To", "Primary (${shortAddress(state.profile.ownerAddress.orEmpty())})")
        state.account.overview?.let {
          DetailRow(
            if (state.account.stale) "Last available balance" else "Available to transfer",
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
            state.busy -> "Transferring…"
            mode == FundingMode.DEPOSIT -> "Transfer to subaccount"
            else -> "Transfer to primary"
          },
        onClick = { onIntent(PortfolioIntent.SubmitFunding) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = state.fundingAmount.isNotBlank() && !state.busy,
        working = state.busy,
      )
    }
  }
}
