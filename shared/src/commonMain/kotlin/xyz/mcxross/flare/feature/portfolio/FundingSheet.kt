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
  FlareSheet(
    title = if (committed != null) "Transfer complete" else "Transfer",
    onDismiss = { if (!state.busy) onIntent(PortfolioIntent.CloseFunding) },
  ) {
    if (committed != null) {
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
    val availableText = "Available: ${formatQuantity(availableUsdc, 2)} USDC"
    val amount = state.fundingAmount.toDoubleOrNull()
    val fee = withdrawal?.feeUsdc?.toDoubleOrNull()?.takeIf { mode == FundingMode.WITHDRAW && it > 0.0 }
    // Guard at the field: an amount above the balance is flagged before anything is signed.
    val exceedsAvailable = amount != null && amount > availableUsdc + FUNDING_EPSILON
    val belowFee = fee != null && amount != null && amount > 0.0 && amount <= fee
    val canTransfer = amount != null && amount > 0.0 && !exceedsAvailable && !belowFee
    val maxClick: (() -> Unit)? =
      if (availableUsdc > 0.0) {
        {
          val maxStr =
            if (availableUsdc % 1.0 == 0.0) availableUsdc.toLong().toString()
            else formatQuantity(availableUsdc, 6)
          onIntent(PortfolioIntent.ChangeFundingAmount(maxStr))
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
        enabled = !state.busy && state.pendingWithdrawal == null,
        isError = exceedsAvailable || belowFee,
        supportingText =
          when {
            exceedsAvailable -> "You can transfer up to ${formatQuantity(availableUsdc, 2)} USDC."
            fee != null -> "${formatQuantity(fee, 6)} USDC of it pays the withdrawal fee."
            else -> null
          },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      )
      state.actionError?.let { error ->
        ActionNotice(error, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
      }
      state.selfPayEstimate(state.fundingTransaction)?.let { estimate ->
        ActionNotice(
          "Flare can’t cover the network fee right now. Your wallet would pay about " +
            "${LocalVenue.current.chain.feeAmount(estimate)}.",
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
            mode == FundingMode.DEPOSIT -> "Transfer to trading account"
            mode == FundingMode.TO_SPOT -> "Move to spot"
            mode == FundingMode.TO_PERPS -> "Move to perps"
            else -> "Transfer to wallet"
          },
        onClick = { onIntent(PortfolioIntent.SubmitFunding) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = canTransfer && !state.busy,
        working = state.busy,
      )
    }
  }
}

/** Tolerance for comparing a typed amount with a balance read as a double. */
private const val FUNDING_EPSILON = 1e-9
