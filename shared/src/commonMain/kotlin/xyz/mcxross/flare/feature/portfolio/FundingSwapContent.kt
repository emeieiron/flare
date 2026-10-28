package xyz.mcxross.flare.feature.portfolio

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
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
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.formatTokenBalance
import xyz.mcxross.flare.design.*
import xyz.mcxross.flare.venue.model.ActionState

/** The input stays visible while debounced quotes update beneath it. */
@Composable
internal fun FundingSwapContent(state: FundingSwapState, onIntent: (PortfolioIntent) -> Unit) {
  val capacity = state.capacity
  val quote = state.quote
  val pending = state.step == FundingSwapStep.PENDING
  val exactInput = state.amountKind == SwapAmountKind.EXACT_INPUT
  val inputDecimals = capacity?.input?.decimals ?: 8
  val outputDecimals = capacity?.output?.decimals ?: 6
  val decimals = if (exactInput) inputDecimals else outputDecimals
  val pay = if (exactInput) state.inputAmount else quote?.expectedInput?.toULong()?.toDecimalString(inputDecimals).orEmpty()
  val receive = if (!exactInput) state.inputAmount else quote?.expectedOutput?.toULong()?.toDecimalString(outputDecimals).orEmpty()
  val units = decimalUnits(state.inputAmount, "Amount", decimals).getOrNull()
  val tooMuch = exactInput && capacity != null && units != null && units > capacity.maximumInput
  val invalid = state.inputAmount.isNotBlank() && units == null
  BackBar(if (pending) "Swap pending" else "Swap for USDC", {
    onIntent(if (pending) PortfolioIntent.CloseFunding else PortfolioIntent.BackFromSwap)
  }, Modifier.padding(bottom = 12.dp).semantics {
    if (pending) { stateDescription = "Swap pending"; liveRegion = LiveRegionMode.Polite }
  }, backEnabled = !state.working)
  Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
    if (pending) {
      state.pending?.reference?.let { reference ->
        val explorer = xyz.mcxross.flare.design.LocalTransactionExplorer.current
        val browser = androidx.compose.ui.platform.LocalUriHandler.current
        explorer.url(reference)?.let { url ->
          xyz.mcxross.flare.design.ActionRow(explorer.label, icon = xyz.mcxross.flare.design.FlareIcons.External,
            onClick = { runCatching { browser.openUri(url) } })
        }
      }
    } else {
      FlareAmountField(
        value = pay,
        onValueChange = { onIntent(PortfolioIntent.ChangeSwapAmount(it, SwapAmountKind.EXACT_INPUT)) },
        label = "You pay", unit = "APT", placeholder = "0.00",
        availableText = capacity?.let { "Available: ${formatTokenBalance(it.maximumInput.toDecimalString(inputDecimals))} APT" } ?: "Available: —",
        onMaxClick = if (state.canSwap) ({ onIntent(PortfolioIntent.UseMaximumSwap) }) else null,
        enabled = !state.working, isError = tooMuch || exactInput && invalid,
        supportingText = when {
          tooMuch -> "Available to swap: ${formatTokenBalance(capacity!!.maximumInput.toDecimalString(inputDecimals))} APT after fees."
          exactInput && invalid -> "Use up to $inputDecimals decimal places."
          else -> null
        },
        loading = !exactInput && state.quoting,
      )
      Spacer(Modifier.height(8.dp))
      FlareAmountField(
        value = receive,
        onValueChange = { onIntent(PortfolioIntent.ChangeSwapAmount(it, SwapAmountKind.EXACT_OUTPUT)) },
        label = "You receive", unit = "USDC", placeholder = "0.00",
        enabled = !state.working, isError = !exactInput && invalid,
        supportingText = if (!exactInput && invalid) "Use up to $outputDecimals decimal places." else null,
        loading = exactInput && state.quoting,
      )
      Spacer(Modifier.height(8.dp))
      if (exactInput) QuoteValueRow("Receive at least", quote?.let {
        "${it.minimumOutput.toULong().toDecimalString(outputDecimals)} USDC"
      } ?: "—", loading = state.quoting)
      else QuoteValueRow("Pay up to", quote?.let {
        "${it.maximumInput.toULong().toDecimalString(inputDecimals)} APT"
      } ?: "—", loading = state.quoting)
      DetailRow("Slippage", "${(quote?.request?.slippageBps ?: 50).toULong().toDecimalString(2)}%")
      if (state.selfPay) {
        val fee = checkNotNull(state.selfPayOffer)
        DetailRow("Fees · paid by you", "≈ ${LocalVenue.current.chain.feeAmount(fee.estimate)}")
      } else {
        QuoteValueRow("Fees", quote?.networkFee?.let { fee ->
          if (fee.payment == FeePayment.SPONSORED) "Covered by Flare"
          else "≈ ${LocalVenue.current.chain.feeAmount(fee.estimate)}"
        } ?: "—", loading = state.quoting)
      }
    }
    state.error?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
  }
  if (pending) return
  Spacer(Modifier.height(16.dp))
  val text = when {
    state.working -> when (state.transaction) {
      ActionState.Simulating -> "Checking…"
      ActionState.AwaitingAuthorization -> "Confirm swap…"
      ActionState.Submitting -> "Swapping…"
      is ActionState.Pending -> "Confirming…"
      else -> "Checking…"
    }
    state.selfPay -> "Pay fees and swap"
    else -> "Swap"
  }
  FlareButton(text, {
    onIntent(when {
      state.selfPay -> PortfolioIntent.ConfirmSwapSelfPay
      else -> PortfolioIntent.ConfirmSwap
    })
  }, Modifier.fillMaxWidth(),
    enabled = !state.working && (pending || capacity != null && state.balanceError == null &&
      quote?.networkFee != null && !state.quoting && !state.quoteExpired && !tooMuch && !invalid),
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
