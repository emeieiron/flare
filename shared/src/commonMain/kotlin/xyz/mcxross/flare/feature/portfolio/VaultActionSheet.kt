package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatCalendarDateTime
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareAmountField
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.LocalVenue
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.TransactionReceipt
import xyz.mcxross.flare.design.shortAddress
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.VaultPosition

@Composable
fun VaultActionSheet(state: PortfolioUiState, onIntent: (PortfolioIntent) -> Unit) {
  val vault = state.selectedVault ?: return
  val mode = state.vaultAction ?: return
  val committed = state.vaultTransaction as? ActionState.Committed

  val name = vault.name.ifBlank { "Vault" }
  val title =
    if (committed != null) {
      when (mode) {
        VaultActionMode.DEPOSIT -> "Contribution complete"
        VaultActionMode.REDEEM -> "Redemption complete"
        VaultActionMode.PLEDGE -> "Shares are now margin"
        VaultActionMode.RELEASE -> "Shares released"
      }
    } else {
      when (mode) {
        VaultActionMode.DEPOSIT -> "Contribute to $name"
        VaultActionMode.REDEEM -> "Redeem from $name"
        VaultActionMode.PLEDGE -> "Use $name shares as margin"
        VaultActionMode.RELEASE -> "Release $name shares"
      }
    }

  FlareSheet(
    title = title,
    onDismiss = { if (!state.busy) onIntent(PortfolioIntent.DismissVaultAction) },
  ) {
    if (committed != null) {
      TransactionReceipt(
        message =
          if (mode == VaultActionMode.PLEDGE)
            "Your shares now count toward your margin."
          else if (mode == VaultActionMode.RELEASE)
            "Your shares no longer back any position."
          else if (mode == VaultActionMode.DEPOSIT)
            "Your USDC has been contributed to ${vault.name.ifBlank { "the vault" }}."
          else if (LocalVenue.current.vaultTerms.stakeInUsdc)
            "Your USDC has been redeemed from ${vault.name.ifBlank { "the vault" }}."
          else "Your shares have been redeemed from ${vault.name.ifBlank { "the vault" }}.",
        hash = committed.reference,
        onDone = { onIntent(PortfolioIntent.DismissVaultAction) },
        enabled = !state.busy,
      )
      return@FlareSheet
    }

    val terms = LocalVenue.current.vaultTerms
    val inUsdc = terms.stakeInUsdc
    Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
      DetailRow("Vault", vault.name.ifBlank { "DLP Vault" })
      if (vault.manager.isNotBlank()) {
        DetailRow("Manager", shortAddress(vault.manager))
      }
      DetailRow("Total AUM", formatBalance(vault.totalValue))
      DetailRow("Share price", formatPrice(vault.sharePrice))
      DetailRow("Performance fee", managerFeeRate(vault.fee))
      // How long the lockup is doesn't depend on whether each deposit re-locks the whole stake.
      DetailRow("Deposit lockup", vault.lockupSeconds?.let { lockup ->
        if (lockup <= 0) "None" else lockupText(lockup)
      } ?: "Not available")

      val userPerformance = state.accountVaults.firstOrNull { it.vault.address == vault.address }
      val availableShares = userPerformance?.shares ?: 0.0
      val effectivePrice = userPerformance?.sharePrice ?: vault.sharePrice
      val parsedAmount = state.vaultAmountInput.toDoubleOrNull() ?: 0.0

      val lockedUntil = userPerformance?.lockedUntil()
      if (mode == VaultActionMode.DEPOSIT) {
        DetailRow("Available to deposit", formatBalance(state.collateralBalance))
        managerFeeNote(vault.fee)?.let {
          ActionNotice(it, Modifier.padding(top = 12.dp))
        }
        vault.lockupSeconds?.takeIf { terms.lockupPerDeposit && it > 0 }?.let { lockup ->
          ActionNotice(
            "This deposit locks your whole stake for ${lockupText(lockup)}: you can redeem from " +
              "${formatCalendarDateTime(currentTimeMs() + lockup * 1_000)}.",
            Modifier.padding(top = 12.dp),
          )
        }
      } else if (inUsdc) {
        DetailRow("Current value", formatBalance(userPerformance?.value ?: 0.0))
      } else {
        DetailRow(
          "Shares held",
          "${formatQuantity(availableShares, 4)} shares",
        )
        DetailRow("Current value", formatBalance(userPerformance?.value ?: 0.0))
        Text("Redemption depends on unlocked shares. Locked or pledged shares may be unavailable.",
          style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
      }
      if (mode == VaultActionMode.REDEEM && lockedUntil != null) {
        ActionNotice(
          "Your latest deposit is locked until ${formatCalendarDateTime(lockedUntil)}.",
          Modifier.padding(top = 12.dp),
          NoticeTone.ALERT,
        )
      }

      if (mode == VaultActionMode.PLEDGE || mode == VaultActionMode.RELEASE) {
        val collateral = state.vaultCollateral
        DetailRow("Free to pledge", "${formatQuantity(collateral?.pledgeable ?: 0.0, 4)} shares")
        DetailRow("Already margin", "${formatQuantity(collateral?.pledged ?: 0.0, 4)} shares")
        collateral?.creditPercent?.let {
          DetailRow("Counts toward margin at", "${formatQuantity(it, 2)}%")
        }
        // Said before it is signed, not after.
        if (mode == VaultActionMode.PLEDGE) {
          terms.collateralRisk?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
        }
      }

      val maxClick: (() -> Unit)? =
        when (mode) {
          VaultActionMode.DEPOSIT ->
            if (state.collateralBalance > 0.0) {
              {
                val maxStr =
                  if (state.collateralBalance % 1.0 == 0.0) state.collateralBalance.toLong().toString()
                  else formatQuantity(state.collateralBalance, 2)
                onIntent(PortfolioIntent.ChangeVaultAmount(maxStr))
              }
            } else null
          VaultActionMode.PLEDGE -> {
            val free = state.vaultCollateral?.pledgeable ?: 0.0
            if (free > 0.0) {
              { onIntent(PortfolioIntent.ChangeVaultAmount(formatQuantity(free, 4))) }
            } else null
          }
          VaultActionMode.RELEASE -> {
            val held = state.vaultCollateral?.pledged ?: 0.0
            if (held > 0.0) {
              { onIntent(PortfolioIntent.ChangeVaultAmount(formatQuantity(held, 4))) }
            } else null
          }
          VaultActionMode.REDEEM ->
            if (availableShares > 0.0) {
              {
                val maxStr =
                  if (availableShares % 1.0 == 0.0) availableShares.toLong().toString()
                  else formatQuantity(availableShares, 4)
                onIntent(PortfolioIntent.ChangeVaultAmount(maxStr))
              }
            } else null
        }

      val venue = LocalVenue.current
      val minimums = venue.vaultMinimums
      val minimumDeposit = minimums?.let { formatQuantity(it.deposit, 2) }
      val minimumShares = minimums?.let { formatQuantity(it.redemptionShares, 4) }
      val supportingText =
        if (mode == VaultActionMode.DEPOSIT) {
          minimumDeposit?.let {
            "${venue.displayName} protocol requires a minimum deposit of $it USDC."
          }
        } else {
          when {
            inUsdc -> null
            parsedAmount > 0.0 ->
              "≈ ${formatBalance(parsedAmount * effectivePrice)} USDC" +
                minimumShares?.let { " · Min. $it shares" }.orEmpty()
            else ->
              minimumShares?.let {
                "${venue.displayName} protocol requires a minimum redemption of $it shares."
              }
          }
        }

      FlareAmountField(
        value = state.vaultAmountInput,
        onValueChange = { onIntent(PortfolioIntent.ChangeVaultAmount(it)) },
        label =
          when {
            mode == VaultActionMode.PLEDGE || mode == VaultActionMode.RELEASE -> "Shares"
            mode == VaultActionMode.DEPOSIT || inUsdc -> "Amount"
            else -> "Shares to redeem"
          },
        unit = if (mode == VaultActionMode.DEPOSIT || inUsdc) "USDC" else "shares",
        availableText =
          when {
            mode == VaultActionMode.PLEDGE ->
              "Free: ${formatQuantity(state.vaultCollateral?.pledgeable ?: 0.0, 4)} shares"
            mode == VaultActionMode.RELEASE ->
              "Pledged: ${formatQuantity(state.vaultCollateral?.pledged ?: 0.0, 4)} shares"
            mode == VaultActionMode.DEPOSIT -> "Available: ${formatBalance(state.collateralBalance)}"
            inUsdc -> "Held: ${formatBalance(availableShares)}"
            else -> "Held: ${formatQuantity(availableShares, 4)} shares"
          },
        placeholder =
          if (mode == VaultActionMode.DEPOSIT) {
            minimumDeposit?.let { if ('.' in it) it else "$it.00" } ?: "0.00"
          } else minimumShares ?: "0",
        onMaxClick = maxClick,
        supportingText = supportingText,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
      )

      state.actionError?.let { error ->
        ActionNotice(error, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
      }

      FlareButton(
        text =
          when {
            state.busy && mode == VaultActionMode.DEPOSIT -> "Contributing…"
            state.busy && mode == VaultActionMode.PLEDGE -> "Pledging…"
            state.busy && mode == VaultActionMode.RELEASE -> "Releasing…"
            state.busy -> "Redeeming…"
            mode == VaultActionMode.DEPOSIT -> "Deposit USDC"
            mode == VaultActionMode.PLEDGE -> "Use as margin"
            mode == VaultActionMode.RELEASE -> "Release shares"
            inUsdc -> "Redeem USDC"
            else -> "Redeem shares"
          },
        onClick = { onIntent(PortfolioIntent.SubmitVaultAction) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled =
          state.vaultAmountInput.isNotBlank() && !state.busy &&
            (mode != VaultActionMode.REDEEM || lockedUntil == null),
        working = state.busy,
      )
    }
  }
}

/** Until when the stake can't be redeemed; null once it can. */
internal fun VaultPosition.lockedUntil(nowMs: Long = currentTimeMs()): Long? =
  lockedUntilMs?.takeIf { it > nowMs }

/** A lockup in whole days where it is one, otherwise in hours. */
private fun lockupText(seconds: Long): String =
  if (seconds % 86_400 == 0L) "${seconds / 86_400} day${if (seconds == 86_400L) "" else "s"}"
  else "${formatQuantity(seconds / 3600.0, 1)} hours"

private fun currentTimeMs(): Long = Clock.System.now().toEpochMilliseconds()
