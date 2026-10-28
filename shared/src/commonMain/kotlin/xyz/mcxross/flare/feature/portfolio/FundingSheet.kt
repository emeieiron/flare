package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.core.decimalUnits
import xyz.mcxross.flare.core.toDecimalString
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.FlareAmountField
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareIcons
import xyz.mcxross.flare.design.FlareSegmentedControl
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.LocalVenue
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.TransactionReceipt
import xyz.mcxross.flare.design.shortAddress
import xyz.mcxross.flare.venue.model.ActionState

@Composable
fun FundingSheet(state: PortfolioUiState, onIntent: (PortfolioIntent) -> Unit) {
  val mode = state.fundingMode ?: return
  val committed = state.fundingTransaction as? ActionState.Committed
  val withdrawal = LocalVenue.current.withdrawal
  val swap = state.fundingSwap
  FlareSheet(
    title = when {
      swap.step != null -> null
      committed != null -> "Transfer complete"
      else -> "Transfer"
    },
    onDismiss = { if (!state.busy) onIntent(PortfolioIntent.CloseFunding) },
    dismissible = !state.busy,
  ) {
    if (swap.step != null) {
      FundingSwapContent(swap, onIntent)
      return@FlareSheet
    }
    if (committed != null) {
      state.fundingNetworkFee?.let { fee ->
        DetailRow("Fees", if (fee.covered) "Covered by Flare" else "≈ ${LocalVenue.current.chain.feeAmount(fee.estimate)}")
      }
      TransactionReceipt(
        when (mode) {
          FundingMode.DEPOSIT -> "Your USDC is now in your trading account."
          FundingMode.TO_SPOT -> "Your USDC is now in spot."
          FundingMode.TO_PERPS -> "Your USDC is now in perps."
          FundingMode.WITHDRAW ->
            withdrawal?.let { "Your USDC is on its way to your wallet on ${it.network}." }
              ?: "Your USDC is now in your wallet."
        },
        committed.reference,
        { onIntent(PortfolioIntent.CloseFunding) },
        enabled = !state.busy,
      )
      return@FlareSheet
    }

    val isDeposit = mode == FundingMode.DEPOSIT
    val account = state.account.account.orEmpty()
    val owner = state.profile.ownerAddress.orEmpty()
    val walletLabel = withdrawal?.let { "Wallet on ${it.network}" } ?: "Wallet"
    val (fromLabel, fromAddress) =
      when (mode) {
        FundingMode.DEPOSIT -> "Wallet" to owner
        FundingMode.WITHDRAW -> "Trading account" to account
        FundingMode.TO_SPOT -> "Perps" to account
        FundingMode.TO_PERPS -> "Spot" to account
      }
    val (toLabel, toAddress) =
      when (mode) {
        FundingMode.DEPOSIT -> "Trading account" to account
        FundingMode.WITHDRAW -> walletLabel to owner
        FundingMode.TO_SPOT -> "Spot" to account
        FundingMode.TO_PERPS -> "Perps" to account
      }
    val availableUsdc = state.fundingAvailable(mode)
    val walletKnown = !isDeposit || !swap.supported || swap.balanceCurrent && swap.capacity != null && swap.balanceError == null
    val availableText = when {
      !walletKnown -> "Available: —"
      isDeposit && swap.capacity != null -> "Available: ${swap.capacity.outputBalance.toDecimalString(swap.capacity.output.decimals)} USDC"
      else -> "Available: ${formatQuantity(availableUsdc, 2)} USDC"
    }
    val amount = state.fundingAmount.toDoubleOrNull()
    val fee = withdrawal?.feeUsdc?.toDoubleOrNull()?.takeIf { mode == FundingMode.WITHDRAW && it > 0.0 }
    // Guard at the field: an amount above the balance is flagged before anything is signed.
    val units = decimalUnits(state.fundingAmount, "USDC amount", 6).getOrNull()
    val exceedsAvailable = if (isDeposit && swap.capacity != null) units != null && units > swap.capacity.outputBalance
      else amount != null && amount > availableUsdc + FUNDING_EPSILON
    val belowFee = fee != null && amount != null && amount > 0.0 && amount <= fee
    val transferPending = state.fundingTransaction is ActionState.Pending || (state.fundingTransaction as? ActionState.Failed)?.let {
      it.reference != null && !it.committed && !it.definitelyNotSubmitted
    } == true
    val canTransfer = units != null && units > 0uL && walletKnown && !exceedsAvailable && !belowFee && !transferPending &&
      (!state.capabilities.sponsoredFees || state.fundingNetworkFee != null)
    val needsUsdc = isDeposit && (swap.capacity?.outputBalance == 0uL || (swap.shortfall(state.fundingAmount) ?: 0uL) > 0uL)
    val offerSwap = needsUsdc && walletKnown && swap.canSwap && !transferPending
    val maxClick: (() -> Unit)? =
      if (availableUsdc > 0.0 && walletKnown) {
        {
          val maxStr =
            if (isDeposit && swap.capacity != null) swap.capacity.outputBalance.toDecimalString(swap.capacity.output.decimals)
            else if (availableUsdc % 1.0 == 0.0) availableUsdc.toLong().toString()
            else formatQuantity(availableUsdc, 6)
          onIntent(PortfolioIntent.ChangeFundingAmount(maxStr, immediate = true))
        }
      } else null

    Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
      if (state.canMovePerpSpot && !isDeposit) {
        FlareSegmentedControl(
          listOf(FundingMode.WITHDRAW, FundingMode.TO_SPOT, FundingMode.TO_PERPS),
          mode,
          { onIntent(PortfolioIntent.SelectFundingMode(it)) },
          {
            when (it) {
              FundingMode.TO_SPOT -> "To spot"
              FundingMode.TO_PERPS -> "To perps"
              else -> "Withdraw"
            }
          },
          Modifier.fillMaxWidth().padding(bottom = 16.dp),
          enabled = !state.busy,
        )
      }
      // Intuitive From -> To direction card with switcher button
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FlareColors.Elevated)
            .padding(horizontal = 16.dp, vertical = 14.dp),
      ) {
        // From section
        Column(modifier = Modifier.fillMaxWidth()) {
          Text(
            text = "From",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextSecondary,
          )
          Spacer(Modifier.height(4.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = fromLabel,
              style = MaterialTheme.typography.titleMedium,
              color = FlareColors.TextPrimary,
            )
            Text(
              text = shortAddress(fromAddress),
              style = MaterialTheme.typography.bodySmall,
              color = FlareColors.TextTertiary,
            )
          }
        }

        // Switcher button centered on divider
        Box(
          modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
          contentAlignment = Alignment.Center,
        ) {
          HorizontalDivider(color = FlareColors.BorderSubtle)
          // Without in-app deposits there is only one direction to go.
          if (state.capabilities.inAppDeposit) Box(
            modifier =
              Modifier.size(40.dp)
                .clip(CircleShape)
                .background(FlareColors.Surface)
                .border(1.dp, FlareColors.BorderDefault, CircleShape)
                .clickable(
                  enabled = !state.busy,
                  role = Role.Button,
                  onClick = {
                    val next = if (isDeposit) FundingMode.WITHDRAW else FundingMode.DEPOSIT
                    onIntent(PortfolioIntent.OpenFunding(next))
                  },
                ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = FlareIcons.Swap,
              contentDescription = "Switch transfer direction",
              tint = FlareColors.Positive,
              modifier = Modifier.size(22.dp),
            )
          }
        }

        // To section
        Column(modifier = Modifier.fillMaxWidth()) {
          Text(
            text = "To",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextSecondary,
          )
          Spacer(Modifier.height(4.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = toLabel,
              style = MaterialTheme.typography.titleMedium,
              color = FlareColors.TextPrimary,
            )
            Text(
              text = shortAddress(toAddress),
              style = MaterialTheme.typography.bodySmall,
              color = FlareColors.TextTertiary,
            )
          }
        }
      }

      FlareAmountField(
        value = state.fundingAmount,
        onValueChange = { onIntent(PortfolioIntent.ChangeFundingAmount(it)) },
        label = "Amount",
        unit = "USDC",
        availableText = availableText,
        onMaxClick = maxClick,
        placeholder = "0.00",
        enabled = !state.busy && state.pendingWithdrawal == null && !transferPending,
        isError = walletKnown && exceedsAvailable && !offerSwap || belowFee,
        supportingText =
          when {
            walletKnown && exceedsAvailable && !offerSwap -> "Available: ${if (isDeposit && swap.capacity != null) swap.capacity.outputBalance.toDecimalString(6) else formatQuantity(availableUsdc, 6)} USDC."
            fee != null -> "${formatQuantity(fee, 6)} USDC of it pays the withdrawal fee."
            else -> null
          },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      )
      if (offerSwap) {
        val shortfall = swap.shortfall(state.fundingAmount)?.takeIf { it > 0uL }
        Text(if (shortfall != null) "${shortfall.toDecimalString(6)} USDC needed"
          else "${swap.capacity!!.maximumInput.toDecimalString(swap.capacity.input.decimals)} APT available to swap",
          Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
      } else if (needsUsdc && walletKnown && swap.capacity != null && !swap.canSwap) {
        ActionNotice("Available to swap: ${swap.capacity.maximumInput.toDecimalString(swap.capacity.input.decimals)} APT after fees.", Modifier.padding(top = 12.dp))
      } else if (isDeposit && walletKnown && swap.canSwap && !transferPending) {
        Text("Swap APT for USDC", Modifier
          .clickable(enabled = !state.busy, role = Role.Button) { onIntent(PortfolioIntent.BeginFundingSwap) }
          .padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = FlareColors.Positive)
      }
      if (transferPending) ActionNotice("Transfer pending", Modifier.padding(top = 12.dp))
      swap.balanceError?.let {
        ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
        FlareButton("Refresh wallet balance", { onIntent(PortfolioIntent.RefreshFundingWallet) },
          Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !state.busy && !swap.loadingBalance, style = FlareButtonStyle.OUTLINE)
      }
      swap.error?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
      (state.actionError ?: state.fundingFeeError)?.let { error ->
        ActionNotice(error, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
      }
      if (state.capabilities.sponsoredFees && !offerSwap && units != null && units > 0uL) {
        DetailRow("Fees", state.fundingNetworkFee?.let { fee ->
          if (fee.covered) "Covered by Flare" else "≈ ${LocalVenue.current.chain.feeAmount(fee.estimate)}"
        } ?: "—")
      }
      FlareButton(
        text =
          when {
            state.busy -> "Transferring…"
            offerSwap -> "Swap APT for USDC"
            transferPending -> "Transfer pending"
            mode == FundingMode.DEPOSIT -> "Transfer to trading account"
            mode == FundingMode.TO_SPOT -> "Move to spot"
            mode == FundingMode.TO_PERPS -> "Move to perps"
            else -> "Transfer to wallet"
          },
        onClick = { onIntent(if (offerSwap) PortfolioIntent.BeginFundingSwap else PortfolioIntent.SubmitFunding) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = (canTransfer || offerSwap) && !state.busy,
        working = state.busy,
      )
    }
  }
}

/** Tolerance for comparing a typed amount with a balance read as a double. */
private const val FUNDING_EPSILON = 1e-9
