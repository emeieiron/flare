package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valentinilk.shimmer.shimmer
import xyz.mcxross.flare.core.decimalUnits
import xyz.mcxross.flare.core.toDecimalString
import xyz.mcxross.flare.data.SwapAmountKind
import xyz.mcxross.flare.design.*
import xyz.mcxross.flare.venue.model.ActionState

/** The input stays visible while debounced quotes update beneath it. */
@Composable
internal fun FundingSwapContent(state: FundingSwapState, onIntent: (PortfolioIntent) -> Unit) {
  val capacity = state.capacity
  val quote = state.quote
  val pending = state.step == FundingSwapStep.PENDING
  val exactInput = state.amountKind == SwapAmountKind.EXACT_INPUT
  val decimals = if (exactInput) capacity?.input?.decimals ?: 8 else capacity?.output?.decimals ?: 6
  val units = decimalUnits(state.inputAmount, "Amount", decimals).getOrNull()
  val tooMuch = exactInput && capacity != null && units != null && units > capacity.maximumInput
  val invalid = state.inputAmount.isNotBlank() && units == null
  BackBar(if (pending) "Swap pending" else "Swap for USDC", {
    onIntent(if (pending) PortfolioIntent.CloseFunding else PortfolioIntent.BackFromSwap)
  }, Modifier.padding(bottom = 12.dp), backEnabled = !state.working)
  Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
    if (pending) {
      ActionNotice("Swap pending")
    } else {
      FlareAmountField(
        value = state.inputAmount,
        onValueChange = { onIntent(PortfolioIntent.ChangeSwapAmount(it)) },
        label = if (exactInput) "You pay" else "Transfer amount",
        unit = if (exactInput) "APT" else "USDC", placeholder = "0.00",
        availableText = capacity?.let { "Available: ${it.maximumInput.toDecimalString(it.input.decimals)} APT" } ?: "Available: —",
        onMaxClick = if (exactInput && state.canSwap) ({ onIntent(PortfolioIntent.UseMaximumSwap) }) else null,
        enabled = !state.working, isError = tooMuch || invalid,
        supportingText = when {
          tooMuch -> "Not enough APT after gas."
          invalid -> "Use up to $decimals decimal places."
          else -> null
        },
      )
      Spacer(Modifier.height(8.dp))
      QuoteValueRow("Receive at least", quote?.let {
        "${it.minimumOutput.toULong().toDecimalString(it.request.output.decimals)} USDC"
      } ?: "—", loading = state.quoting)
      if (!exactInput) QuoteValueRow("Pay up to", quote?.let {
        "${it.maximumInput.toULong().toDecimalString(it.request.input.decimals)} APT"
      } ?: "—", loading = state.quoting)
      DetailRow("Slippage", "${(quote?.request?.slippageBps ?: 50).toULong().toDecimalString(2)}%")
      if (state.selfPay) {
        val fee = (state.transaction as ActionState.Failed).selfPay!!
        DetailRow("Gas · paid by you", "≈ ${LocalVenue.current.chain.feeAmount(fee.estimate)}")
      } else {
        DetailRow("Gas", "Sponsored when available")
      }
    }
    state.error?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
  }
  Spacer(Modifier.height(16.dp))
  val text = when {
    state.working -> when (state.transaction) {
      ActionState.Simulating -> "Checking…"
      ActionState.AwaitingAuthorization -> "Confirm swap…"
      ActionState.Submitting -> "Swapping…"
      is ActionState.Pending -> "Confirming…"
      else -> "Checking…"
    }
    pending -> "Check status"
    state.selfPay -> "Pay gas and swap"
    else -> "Swap"
  }
  FlareButton(text, {
    onIntent(when {
      pending -> PortfolioIntent.ReconcileSwap
      state.selfPay -> PortfolioIntent.ConfirmSwapSelfPay
      else -> PortfolioIntent.ConfirmSwap
    })
  }, Modifier.fillMaxWidth(),
    enabled = !state.working && (pending || capacity != null && state.balanceError == null &&
      quote != null && !state.quoting && !state.quoteExpired && !tooMuch && !invalid),
    working = state.working)
}

@Composable
private fun QuoteValueRow(label: String, value: String, loading: Boolean) {
  val shimmer = rememberFlareShimmer()
  val reducedMotion = rememberReducedMotion()
  Row(Modifier.fillMaxWidth().padding(vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = FlareColors.TextSecondary)
    if (loading) FlareSkeletonBox(Modifier.width(120.dp).height(20.dp)
      .then(if (reducedMotion) Modifier else Modifier.shimmer(shimmer))
      .semantics { contentDescription = "Loading quote" })
    else Text(value, style = MaterialTheme.typography.bodyMedium)
  }
  HorizontalDivider(color = FlareColors.BorderSubtle)
}
