package xyz.mcxross.flare.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatCalendarDate
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.shortAddress

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
  var removal by remember { mutableStateOf<SettingsIntent?>(null) }
  var copied by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val connected = state.profile.ownerAddress != null || state.profile.apiWalletAddress != null
  LaunchedEffect(copied) {
    if (copied) {
      delay(COPIED_CONFIRMATION_MS)
      copied = false
    }
  }
  Column(
    modifier
      .fillMaxSize()
      .background(FlareColors.Canvas)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp)
  ) {
    FlareTopBar("Account")
    if (connected) {
      val address = (state.profile.ownerAddress ?: state.profile.apiWalletAddress).orEmpty()
      Text(
        if (state.profile.apiOnly) "Trading account" else "Your wallet",
        style = MaterialTheme.typography.headlineMedium,
      )
      Row(
        Modifier.fillMaxWidth()
          .clickable(role = Role.Button) {
            clipboard.setText(AnnotatedString(address))
            copied = true
          }
          .padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          shortAddress(address),
          color = FlareColors.TextSecondary,
          style = MaterialTheme.typography.bodyLarge,
        )
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = "Copy your address",
          tint = FlareColors.TextTertiary,
          modifier = Modifier.size(16.dp),
        )
        if (copied)
          Text("Copied", color = FlareColors.Positive, style = MaterialTheme.typography.labelSmall)
      }
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
      state.preferences.profiles.forEach { profile ->
        val selected = profile.id == state.preferences.activeProfileId
        ActionRow(
          shortAddress((profile.ownerAddress ?: profile.apiWalletAddress).orEmpty()),
          if (selected) "Selected"
          else if (profile.ownerAddress == null) "Trading only" else "Tap to switch",
          enabled = !state.busy && !selected,
          onClick = { onIntent(SettingsIntent.SelectProfile(profile.id)) },
        )
      }
    }
    if (connected) {
      SectionLabel("Manage")
      if (state.profile.ownerAddress != null)
        ActionRow(
          "Trading access",
          "Devices and keys that can place orders",
          Icons.Outlined.PhonelinkLock,
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
    SectionLabel("Preferences")
    Text(
      "Maximum slippage",
      Modifier.padding(top = 4.dp),
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
    state.error?.let { ActionNotice(it, Modifier.padding(bottom = 24.dp), NoticeTone.ALERT) }
  }
  if (showAccess)
    FlareSheet("Trading access", { showAccess = false }) {
      Text(
        "These keys can place and cancel orders for your trading account. Only your wallet can " +
          "move funds.",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(12.dp))
      state.delegations.forEach { delegation ->
        val thisDevice = delegation.delegate == state.profile.apiWalletAddress
        ActionRow(
          if (thisDevice) "This device" else shortAddress(delegation.delegate),
          delegationSummary(delegation),
          enabled = !state.busy,
          onClick = { revokeAddress = delegation.delegate },
        )
      }
      if (state.delegationsLoaded && state.delegations.isEmpty())
        Text("Nothing can trade for this account yet.")
      if (!state.delegationsLoaded || state.busy)
        ActionNotice("Checking authorized keys…", Modifier.padding(top = 12.dp), NoticeTone.PROGRESS)
      state.error?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
    }
  revokeAddress?.let { address ->
    val thisDevice = address == state.profile.apiWalletAddress
    AlertDialog(
      onDismissRequest = { revokeAddress = null },
      title = { Text(if (thisDevice) "Turn off trading here?" else "Revoke trading access?") },
      text = {
        Text(
          if (thisDevice) {
            "This device will stop placing orders until you enable trading again. Your funds stay " +
              "in your account."
          } else {
            "${shortAddress(address)} will no longer be able to trade for this account."
          }
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            revokeAddress = null
            onIntent(SettingsIntent.RevokeDelegate(address))
          }
        ) {
          Text(if (thisDevice) "Turn off" else "Revoke", color = FlareColors.Negative)
        }
      },
      dismissButton = { TextButton(onClick = { revokeAddress = null }) { Text("Cancel") } },
      containerColor = FlareColors.Surface,
    )
  }
  if (showSecurity && state.revealedSecret == null)
    FlareSheet("Security & recovery", { showSecurity = false }) {
      if (state.profile.ownerAddress != null) {
        ActionRow(
          "Show recovery phrase",
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
    FlareSheet(state.revealedSecretLabel.orEmpty(), { onIntent(SettingsIntent.HideSecret) }) {
      Text(
        "Keep this private. Anyone who has it can use your account.",
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
      text = { Text("Save your recovery phrase or private key first. You’ll need it to return.") },
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

/** What a delegation actually permits, in place of the raw permission type. */
private fun delegationSummary(delegation: Delegation): String {
  val scope =
    if (delegation.canTradeAllPerpMarkets) "Can trade every market"
    else "Can trade ${delegation.permissionMarket ?: "one market"}"
  val expiry =
    delegation.expirationTimeSeconds?.let { " · expires ${formatCalendarDate(it * 1_000L)}" }
  return scope + expiry.orEmpty()
}

private const val COPIED_CONFIRMATION_MS = 2_000L
