package xyz.mcxross.flare.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.SectionLabel

@Composable
fun SettingsRoute(
  onOpenSetup: () -> Unit = {},
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(SettingsIntent.HideSecret) }
  DisposableEffect(viewModel) { onDispose { viewModel.onIntent(SettingsIntent.HideSecret) } }
  SettingsScreen(state, viewModel::onIntent, onOpenSetup, modifier)
}

@Composable
fun SettingsScreen(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onOpenSetup: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var showAccess by remember { mutableStateOf(false) }
  var revokeAddress by remember { mutableStateOf<String?>(null) }
  var showSecurity by remember { mutableStateOf(false) }
  var showConnection by remember { mutableStateOf(false) }
  var removal by remember { mutableStateOf<SettingsIntent?>(null) }
  val connected = state.profile.ownerAddress != null || state.profile.apiWalletAddress != null
  Column(
    modifier
      .fillMaxSize()
      .background(FlareColors.Canvas)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp)
  ) {
    FlareTopBar("Account", subtitle = "Wallet & preferences")
    if (connected) {
      Text(
        if (state.profile.apiOnly) "Trading account" else "Your wallet",
        style = MaterialTheme.typography.headlineMedium,
      )
      Text(
        (state.profile.ownerAddress ?: state.profile.apiWalletAddress)
          ?.let(::shortAddress)
          .orEmpty(),
        Modifier.padding(top = 8.dp),
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyLarge,
      )
    } else {
      Text("Your account,\nyour control.", style = MaterialTheme.typography.headlineLarge)
      Text(
        "Create an account or bring the one you already use.",
        Modifier.padding(top = 12.dp),
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyLarge,
      )
      FlareButton(
        "Create or import account",
        onOpenSetup,
        Modifier.fillMaxWidth().padding(top = 24.dp),
      )
    }
    if (state.preferences.profiles.size > 1) {
      SectionLabel("Accounts")
      state.preferences.profiles.forEachIndexed { index, profile ->
        ActionRow(
          "Account ${index + 1}" +
            if (profile.id == state.preferences.activeProfileId) " · Selected" else "",
          (profile.ownerAddress ?: profile.apiWalletAddress).orEmpty().let(::shortAddress),
          enabled = !state.busy && profile.id != state.preferences.activeProfileId,
          onClick = { onIntent(SettingsIntent.SelectProfile(profile.id)) },
        )
      }
    }
    SectionLabel("Preferences")
    DetailRow("Network", "Decibel ${state.preferences.network.name.lowercase()}")
    Text(
      "Maximum slippage",
      Modifier.padding(top = 20.dp),
      style = MaterialTheme.typography.bodyLarge,
    )
    Text(
      "The most a market order’s price may move before it fills.",
      Modifier.padding(top = 6.dp),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodySmall,
    )
    Row(
      Modifier.fillMaxWidth().padding(top = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      listOf(25, 50, 100).forEach { bps ->
        FlareChip(
          "${bps / 100.0}%",
          state.preferences.slippageBps == bps,
          { onIntent(SettingsIntent.SetSlippage(bps)) },
          Modifier.weight(1f),
        )
      }
    }
    if (connected) {
      SectionLabel("Wallet")
      if (state.profile.ownerAddress != null)
        ActionRow(
          "Trading access",
          "Manage authorized devices and keys",
          onClick = {
            showAccess = true
            onIntent(SettingsIntent.LoadDelegations)
          },
        )
      ActionRow(
        "Account setup",
        "Add an account or finish setup",
        Icons.Outlined.AccountBalanceWallet,
        onClick = onOpenSetup,
      )
      ActionRow(
        "Security & recovery",
        "Back up keys and manage this device",
        Icons.Outlined.Key,
        onClick = { showSecurity = true },
      )
    }
    SectionLabel("About")
    ActionRow(
      "Connection details",
      "Network and service information",
      onClick = { showConnection = true },
    )
    Text("Flare", Modifier.padding(top = 32.dp), style = MaterialTheme.typography.titleLarge)
    Text(
      "An independent, open-source client for Decibel.",
      Modifier.padding(top = 8.dp, bottom = 32.dp),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodySmall,
    )
    state.error?.let {
      Text(it, Modifier.padding(bottom = 24.dp), color = MaterialTheme.colorScheme.error)
    }
  }
  if (showAccess)
    FlareSheet("Trading access", { showAccess = false }) {
      state.delegations.forEach { delegation ->
        ActionRow(
          if (delegation.delegate == state.profile.apiWalletAddress) "This device"
          else shortAddress(delegation.delegate),
          delegation.delegate,
          enabled = !state.busy,
          onClick = { revokeAddress = delegation.delegate },
        )
      }
      if (state.delegationsLoaded && state.delegations.isEmpty()) Text("No trading access granted")
      if (state.busy) Text("Updating…")
      state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      TextButton(onClick = { onIntent(SettingsIntent.LoadDelegations) }, enabled = !state.busy) {
        Text("Refresh")
      }
    }
  revokeAddress?.let { address ->
    AlertDialog(
      onDismissRequest = { revokeAddress = null },
      title = { Text("Revoke trading access?") },
      text = { Text("$address will no longer be able to trade for this account.") },
      confirmButton = {
        TextButton(
          onClick = {
            revokeAddress = null
            onIntent(SettingsIntent.RevokeDelegate(address))
          }
        ) {
          Text("Revoke")
        }
      },
      dismissButton = { TextButton(onClick = { revokeAddress = null }) { Text("Cancel") } },
    )
  }
  if (showConnection)
    FlareSheet("Connection", { showConnection = false }) {
      DetailRow("Network", state.preferences.network.name.lowercase())
      Text("Service", style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
      Text(
        state.proxyUrl,
        Modifier.padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  if (showSecurity && state.revealedSecret == null)
    FlareSheet("Security & recovery", { showSecurity = false }) {
      if (state.profile.ownerAddress != null) {
        ActionRow(
          "Show recovery details",
          "Requires device authorization",
          enabled = !state.busy,
          onClick = { onIntent(SettingsIntent.ExportOwner) },
        )
      }
      if (state.profile.apiWalletAddress != null) {
        ActionRow(
          "Show trading key",
          enabled = !state.busy,
          onClick = { onIntent(SettingsIntent.ExportApi) },
        )
        ActionRow(
          "Remove trading key",
          "Remove this key from this device",
          enabled = !state.busy,
          onClick = { removal = SettingsIntent.RemoveApi },
        )
      }
      if (state.profile.ownerAddress != null) {
        ActionRow(
          "Remove owner key",
          "Remove this wallet from this device",
          enabled = !state.busy,
          onClick = { removal = SettingsIntent.RemoveOwner },
        )
      }
      if (state.profile.apiOnly)
        Text(
          "Deposits and withdrawals require your main wallet.",
          Modifier.padding(top = 16.dp),
          color = FlareColors.TextSecondary,
          style = MaterialTheme.typography.bodyMedium,
        )
    }
  state.revealedSecret?.let { secret ->
    FlareSheet("Recovery details", { onIntent(SettingsIntent.HideSecret) }) {
      Text(
        "Keep this private. Anyone with these details can access your wallet.",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
      )
      SelectionContainer {
        Text(secret, Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge)
      }
      FlareButton("Done", { onIntent(SettingsIntent.HideSecret) }, Modifier.fillMaxWidth())
    }
  }
  removal?.let { intent ->
    AlertDialog(
      onDismissRequest = { removal = null },
      title = { Text("Remove from this device?") },
      text = {
        Text(
          "Make sure you have saved your recovery phrase or private key. You’ll need it to reconnect."
        )
      },
      confirmButton = {
        TextButton({
          removal = null
          showSecurity = false
          onIntent(intent)
        }) {
          Text("Remove", color = FlareColors.Negative)
        }
      },
      dismissButton = { TextButton({ removal = null }) { Text("Cancel") } },
      containerColor = FlareColors.Surface,
    )
  }
}

private fun shortAddress(address: String): String =
  if (address.length <= 18) address else address.take(10) + "…" + address.takeLast(6)
