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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareAmountField
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareTextField
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.TransactionReceipt
import xyz.mcxross.flare.design.shortAddress

private const val COPIED_CONFIRMATION_MS = 1_500L

@Composable
fun AccountRoute(
  onOpenSettings: () -> Unit,
  onCreateAccount: () -> Unit,
  onImportAccount: () -> Unit,
  onContinueSetup: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) {
    viewModel.onIntent(SettingsIntent.LoadSubaccounts)
  }
  AccountScreen(
    state = state,
    onIntent = viewModel::onIntent,
    onOpenSettings = onOpenSettings,
    onCreateAccount = onCreateAccount,
    onImportAccount = onImportAccount,
    onContinueSetup = onContinueSetup,
    modifier = modifier,
  )
}

@Composable
fun AccountScreen(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onOpenSettings: () -> Unit,
  onCreateAccount: () -> Unit,
  onImportAccount: () -> Unit,
  onContinueSetup: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showWallets by remember { mutableStateOf(false) }
  var showAddWallet by remember { mutableStateOf(false) }
  var showDeposit by remember { mutableStateOf(false) }
  var showWithdraw by remember { mutableStateOf(false) }
  var copied by remember { mutableStateOf(false) }
  var detailTitle by remember { mutableStateOf("Trading account address") }
  var detailAddress by remember { mutableStateOf<String?>(null) }
  val clipboard = LocalClipboardManager.current
  val profiles = state.preferences.profiles
  val active = profiles.firstOrNull { it.id == state.preferences.activeProfileId }
  val walletLabel = "Wallet ${profiles.indexOf(active).coerceAtLeast(0) + 1}"
  val walletAddress = (active?.ownerAddress ?: active?.apiWalletAddress).orEmpty()
  val subaccounts = active?.ownerAddress?.let { state.subaccountsByOwner[it] }.orEmpty()
    .ifEmpty {
      active?.selectedSubaccount?.let {
        listOf(Subaccount(address = it, owner = active.ownerAddress.orEmpty(),
          customLabel = null, isPrimary = true, isActive = true))
      }.orEmpty()
    }
  LaunchedEffect(copied) {
    if (copied) { delay(COPIED_CONFIRMATION_MS); copied = false }
  }
  Column(modifier.fillMaxSize().background(FlareColors.Canvas)
    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
    FlareTopBar("Account", action = {
      IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.Settings, "Settings", tint = FlareColors.TextPrimary)
      }
    })
    state.error?.let { ActionNotice(it, Modifier.padding(bottom = 16.dp), NoticeTone.ALERT) }
    if (active == null) {
      Text("Your wallet", style = MaterialTheme.typography.headlineLarge)
      FlareButton("Add wallet", { showAddWallet = true }, Modifier.fillMaxWidth().padding(top = 24.dp))
    } else {
      ActionRow(walletLabel,
        subtitle = if (active.ownerAddress == null) "Trading key · ${shortAddress(walletAddress)}"
          else shortAddress(walletAddress),
        icon = Icons.Outlined.AccountBalanceWallet,
        onClick = { showWallets = true }, enabled = !state.busy)
      if (!active.onboardingComplete) {
        FlareButton("Finish setup", { onContinueSetup(active.id) }, Modifier.fillMaxWidth())
      }
      if (active.ownerAddress != null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          FlareButton("Receive", { showDeposit = true }, Modifier.weight(1f))
          FlareButton("Withdraw", { showWithdraw = true }, Modifier.weight(1f),
            style = FlareButtonStyle.OUTLINE, enabled = active.selectedSubaccount != null && !state.busy)
        }
      }
      SectionLabel("Trading accounts")
      subaccounts.forEachIndexed { index, account ->
        val selected = account.address.equals(active.selectedSubaccount, ignoreCase = true)
        Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = !state.busy, role = Role.RadioButton) {
          onIntent(SettingsIntent.SelectSubaccount(active.id, account.address))
        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(account.customLabel?.takeIf { it.isNotBlank() } ?: "Trading account ${index + 1}",
              style = MaterialTheme.typography.bodyLarge)
            Text(shortAddress(account.address), style = MaterialTheme.typography.bodySmall,
              color = FlareColors.TextSecondary)
          }
          if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", tint = FlareColors.TextPrimary,
            modifier = Modifier.size(20.dp))
          IconButton(onClick = { detailTitle = "Trading account address"; detailAddress = account.address }) {
            Icon(Icons.Outlined.ContentCopy, "Show trading account address", tint = FlareColors.TextSecondary,
              modifier = Modifier.size(18.dp))
          }
        }
        HorizontalDivider(color = FlareColors.BorderSubtle)
      }
      if (active.ownerAddress != null) {
        ActionRow(if (state.creatingSubaccount) "Creating trading account…" else "Add trading account",
          icon = Icons.Outlined.Add, enabled = !state.busy && !state.creatingSubaccount,
          onClick = { onIntent(SettingsIntent.CreateSubaccountForActiveProfile) })
      }
    }
    Spacer(Modifier.height(32.dp))
  }
  if (showWallets) FlareSheet("Wallets", { showWallets = false }) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
      profiles.forEachIndexed { index, profile ->
        ActionRow("Wallet ${index + 1}${if (profile.id == active?.id) " · Selected" else ""}",
          subtitle = if (!profile.onboardingComplete) "Finish setup" else
            shortAddress((profile.ownerAddress ?: profile.apiWalletAddress).orEmpty()),
          enabled = !state.busy,
          onClick = {
            showWallets = false
            if (profile.onboardingComplete) onIntent(SettingsIntent.SelectProfile(profile.id))
            else onContinueSetup(profile.id)
          })
      }
      if (walletAddress.isNotBlank()) ActionRow("Wallet address", subtitle = shortAddress(walletAddress),
        icon = Icons.Outlined.ContentCopy, onClick = {
          showWallets = false; detailTitle = "Wallet address"; detailAddress = walletAddress
        })
      ActionRow("Add wallet", icon = Icons.Outlined.Add, onClick = { showWallets = false; showAddWallet = true })
    }
  }
  if (showAddWallet) FlareSheet("Add wallet", { showAddWallet = false }) {
    ActionRow("Create wallet", subtitle = "Get a new recovery phrase", icon = Icons.Outlined.Key,
      onClick = { showAddWallet = false; onCreateAccount() })
    ActionRow("Import wallet", subtitle = "Use a recovery phrase or private key",
      icon = Icons.Outlined.AccountBalanceWallet, onClick = { showAddWallet = false; onImportAccount() })
  }
  detailAddress?.let { address ->
    FlareSheet(detailTitle, { detailAddress = null }) {
      SelectionContainer { Text(address, style = MaterialTheme.typography.bodyMedium) }
      Spacer(Modifier.height(24.dp))
      FlareButton(if (copied) "Copied" else "Copy address", {
        clipboard.setText(AnnotatedString(address)); copied = true
      }, Modifier.fillMaxWidth())
    }
  }
  if (showDeposit && walletAddress.isNotBlank()) DepositSheet(walletLabel, walletAddress, copied, {
    clipboard.setText(AnnotatedString(walletAddress)); copied = true
  }, { showDeposit = false })
  if (showWithdraw && active?.selectedSubaccount != null) WithdrawSheet(state, active.selectedSubaccount,
    onIntent, { if (!state.withdrawing) { showWithdraw = false; onIntent(SettingsIntent.DismissWithdraw) } })
}

@Composable
private fun DepositSheet(
  accountLabel: String,
  address: String,
  copied: Boolean,
  onCopy: () -> Unit,
  onDismiss: () -> Unit,
) {
  FlareSheet("Receive funds", onDismiss) {
    Text(
      "Receive funds in your wallet, then use Portfolio to transfer USDC to a trading account.",
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    DetailRow("Network", "Aptos")
    DetailRow("Wallet", accountLabel)
    DetailRow("Supported assets", "USDC, APT")
    Spacer(Modifier.height(16.dp))
    Text("Your Aptos address", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Column(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(FlareColors.Elevated)
        .clickable(role = Role.Button, onClick = onCopy)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SelectionContainer {
        Text(
          address,
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextPrimary,
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          Icons.Outlined.ContentCopy,
          contentDescription = null,
          tint = if (copied) FlareColors.Positive else FlareColors.TextSecondary,
          modifier = Modifier.size(14.dp),
        )
        Text(
          if (copied) "Copied" else "Tap to copy",
          style = MaterialTheme.typography.labelSmall,
          color = if (copied) FlareColors.Positive else FlareColors.TextSecondary,
        )
      }
    }
    Spacer(Modifier.height(16.dp))
    ActionNotice(
      "Only send Aptos-native USDC and APT to this address. Sending assets from other networks may result in permanent loss.",
      tone = NoticeTone.INFO,
    )
    Spacer(Modifier.height(20.dp))
    FlareButton(
      text = if (copied) "Copied" else "Copy address",
      onClick = onCopy,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun WithdrawSheet(
  state: SettingsUiState,
  address: String,
  onIntent: (SettingsIntent) -> Unit,
  onDismiss: () -> Unit,
) {
  var reviewing by remember { mutableStateOf(false) }
  val committed = state.withdrawTransaction as? TransactionState.Committed
  FlareSheet(
    title = if (committed != null) "Withdrawal complete" else if (reviewing) "Review withdrawal" else "Withdraw",
    onDismiss = onDismiss,
  ) {
    if (committed != null) {
      TransactionReceipt(
        message =
          "Successfully withdrew ${state.withdrawAmount} USDC to ${shortAddress(state.withdrawDestination)}.",
        hash = committed.hash,
        onDone = onDismiss,
        enabled = !state.withdrawing,
      )
      return@FlareSheet
    }
    Text(
      "Withdraw USDC from your account to an external Aptos address.",
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    DetailRow("From", "Trading account · ${shortAddress(address)}")
    DetailRow("Asset", "USDC (Aptos)")
    DetailRow("Network fee", "Sponsored by Flare")
    Spacer(Modifier.height(12.dp))
    if (reviewing) {
      DetailRow("Amount", "${state.withdrawAmount} USDC")
      Text("Recipient", style = MaterialTheme.typography.labelMedium)
      SelectionContainer { Text(state.withdrawDestination, style = MaterialTheme.typography.bodySmall) }
      androidx.compose.material3.TextButton(onClick = { reviewing = false }, enabled = !state.withdrawing) {
        Text("Edit withdrawal")
      }
    } else {
    FlareTextField(
      value = state.withdrawDestination,
      onValueChange = { onIntent(SettingsIntent.ChangeWithdrawDestination(it)) },
      label = "Recipient Aptos address",
      placeholder = "0x...",
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      enabled = !state.withdrawing,
    )
    Spacer(Modifier.height(12.dp))
    FlareAmountField(
      value = state.withdrawAmount,
      onValueChange = { onIntent(SettingsIntent.ChangeWithdrawAmount(it)) },
      label = "Amount",
      unit = "USDC",
      placeholder = "0.00",
      modifier = Modifier.fillMaxWidth(),
      enabled = !state.withdrawing,
    )
    }
    state.withdrawError?.let { err ->
      ActionNotice(err, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
    }
    if (state.withdrawing) {
      ActionNotice("Withdrawing USDC…", Modifier.padding(top = 12.dp), NoticeTone.PROGRESS)
    }
    Spacer(Modifier.height(20.dp))
    FlareButton(
      text = if (state.withdrawing) "Withdrawing…" else if (reviewing) "Confirm withdrawal" else "Review withdrawal",
      onClick = { if (reviewing) onIntent(SettingsIntent.SubmitWithdraw) else reviewing = true },
      modifier = Modifier.fillMaxWidth(),
      enabled =
        !state.withdrawing &&
          state.withdrawDestination.isNotBlank() &&
          (state.withdrawAmount.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true),
      working = state.withdrawing,
    )
  }
}
