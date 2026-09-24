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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.api.TransactionState
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
fun VaultActionSheet(state: PortfolioUiState, onIntent: (PortfolioIntent) -> Unit) {
  val vault = state.selectedVault ?: return
  val mode = state.vaultAction ?: return
  val committed = state.vaultTransaction as? TransactionState.Committed

  val title =
    if (committed != null) {
      if (mode == VaultActionMode.DEPOSIT) "Contribution complete" else "Redemption complete"
    } else {
      if (mode == VaultActionMode.DEPOSIT) "Contribute to ${vault.name.ifBlank { "Vault" }}"
      else "Redeem from ${vault.name.ifBlank { "Vault" }}"
    }

  FlareSheet(
    title = title,
    onDismiss = { if (!state.busy) onIntent(PortfolioIntent.DismissVaultAction) },
  ) {
    if (committed != null) {
      TransactionReceipt(
        message =
          if (mode == VaultActionMode.DEPOSIT)
            "Your USDC has been contributed to ${vault.name.ifBlank { "the vault" }}."
          else "Your shares have been redeemed from ${vault.name.ifBlank { "the vault" }}.",
        hash = committed.hash,
        onDone = { onIntent(PortfolioIntent.DismissVaultAction) },
        enabled = !state.busy,
      )
      return@FlareSheet
    }

    Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
      DetailRow("Vault", vault.name.ifBlank { "DLP Vault" })
      if (vault.manager.isNotBlank()) {
        DetailRow("Manager", shortAddress(vault.manager))
      }
      DetailRow("Total AUM", formatBalance(vault.totalAum))
      DetailRow("Share price", formatPrice(vault.sharePrice))
      DetailRow("Performance fee", "${vault.performanceFeeBps / 100.0}%")

      val userPerformance = state.accountVaults.firstOrNull { it.vault.address == vault.address }
      val availableShares = userPerformance?.currentNumShares ?: 0.0
      val effectivePrice = userPerformance?.effectiveSharePrice ?: vault.sharePrice
      val parsedAmount = state.vaultAmountInput.toDoubleOrNull() ?: 0.0

      if (mode == VaultActionMode.DEPOSIT) {
        DetailRow("Available to deposit", formatBalance(state.collateralBalance))
      } else {
        DetailRow(
          "Shares held",
          "${formatQuantity(availableShares, 4)} shares",
        )
        DetailRow("Current value", formatBalance(userPerformance?.currentValue ?: 0.0))
      }

      OutlinedTextField(
        value = state.vaultAmountInput,
        onValueChange = { onIntent(PortfolioIntent.ChangeVaultAmount(it)) },
        label = {
          Text(if (mode == VaultActionMode.DEPOSIT) "Amount in USDC (min. 10)" else "Shares to redeem (min. 5)")
        },
        trailingIcon =
          if (mode == VaultActionMode.REDEEM && availableShares > 0.0) {
            {
              TextButton(
                onClick = {
                  val maxStr =
                    if (availableShares % 1.0 == 0.0) availableShares.toLong().toString()
                    else formatQuantity(availableShares, 4)
                  onIntent(PortfolioIntent.ChangeVaultAmount(maxStr))
                },
                enabled = !state.busy,
              ) {
                Text("MAX", color = FlareColors.Positive, style = MaterialTheme.typography.labelMedium)
              }
            }
          } else null,
        supportingText = {
          if (mode == VaultActionMode.DEPOSIT) {
            Text(
              "Decibel protocol requires a minimum deposit of 10 USDC.",
              style = MaterialTheme.typography.labelSmall,
              color = FlareColors.TextTertiary,
            )
          } else {
            val helper =
              when {
                parsedAmount > 0.0 ->
                  "≈ ${formatBalance(parsedAmount * effectivePrice)} USDC · Min. 5 shares"
                else -> "Decibel protocol requires a minimum redemption of 5 shares."
              }
            Text(
              helper,
              style = MaterialTheme.typography.labelSmall,
              color = FlareColors.TextTertiary,
            )
          }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      )

      state.actionError?.let { error ->
        ActionNotice(error, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
      }

      FlareButton(
        text =
          when {
            state.busy && mode == VaultActionMode.DEPOSIT -> "Contributing…"
            state.busy -> "Redeeming…"
            mode == VaultActionMode.DEPOSIT -> "Deposit USDC"
            else -> "Redeem shares"
          },
        onClick = { onIntent(PortfolioIntent.SubmitVaultAction) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = state.vaultAmountInput.isNotBlank() && !state.busy,
        working = state.busy,
      )
    }
  }
}
