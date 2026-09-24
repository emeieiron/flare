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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CardGiftcard
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatCalendarDate
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareAmountField
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareTextField
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.TransactionReceipt
import xyz.mcxross.flare.design.shortAddress

@Composable
fun SettingsRoute(
  onOpenAccounts: () -> Unit = {},
  onOpenSetup: () -> Unit = {},
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(SettingsIntent.HideSecret) }
  DisposableEffect(viewModel) { onDispose { viewModel.onIntent(SettingsIntent.HideSecret) } }
  SettingsScreen(state, viewModel::onIntent, onOpenAccounts, onOpenSetup, modifier)
}

@Composable
fun SettingsScreen(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onOpenAccounts: () -> Unit = {},
  onOpenSetup: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var showAccess by remember { mutableStateOf(false) }
  var showBuilderSheet by remember { mutableStateOf(false) }
  var showReferralSheet by remember { mutableStateOf(false) }
  var showRevokeConfirm by remember { mutableStateOf(false) }
  var revokeAddress by remember { mutableStateOf<String?>(null) }
  var showSecurity by remember { mutableStateOf(false) }
  var removal by remember { mutableStateOf<SettingsIntent?>(null) }
  var copied by remember { mutableStateOf(false) }
  var showDepositSheet by remember { mutableStateOf(false) }
  var showWithdrawSheet by remember { mutableStateOf(false) }
  var depositCopied by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val connected = state.profile.ownerAddress != null || state.profile.apiWalletAddress != null
  val address = (state.profile.ownerAddress ?: state.profile.apiWalletAddress).orEmpty()
  val completedProfiles = state.preferences.profiles.filter { it.onboardingComplete }
  val activeIndex =
    completedProfiles
      .indexOfFirst { it.id == state.preferences.activeProfileId }
      .takeIf { it >= 0 } ?: 0
  val accountLabel = "Account ${activeIndex + 1}"

  LaunchedEffect(copied) {
    if (copied) {
      delay(COPIED_CONFIRMATION_MS)
      copied = false
    }
  }
  LaunchedEffect(depositCopied) {
    if (depositCopied) {
      delay(COPIED_CONFIRMATION_MS)
      depositCopied = false
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
      Text(
        accountLabel,
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
      if (state.profile.ownerAddress != null) {
        Row(
          Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FlareButton(
            "Deposit",
            { showDepositSheet = true },
            Modifier.weight(1f),
          )
          FlareButton(
            "Withdraw",
            { showWithdrawSheet = true },
            Modifier.weight(1f),
            style = FlareButtonStyle.OUTLINE,
          )
        }
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
        onOpenAccounts,
        Modifier.fillMaxWidth().padding(top = 24.dp),
      )
    }
    if (connected) {
      val completedCount = state.preferences.profiles.count { it.onboardingComplete }
      SectionLabel("Manage")
      ActionRow(
        "Accounts",
        "$completedCount connected · Switch, add, or import",
        Icons.Outlined.AccountBalanceWallet,
        onClick = onOpenAccounts,
      )
      if (state.profile.ownerAddress != null) {
        ActionRow(
          "Trading access",
          "Devices and keys that can place orders",
          Icons.Outlined.PhonelinkLock,
          onClick = {
            showAccess = true
            onIntent(SettingsIntent.LoadDelegations)
          },
        )
        val builderStatus =
          if (state.preferences.builderApproved && state.preferences.builderFeeBps > 0) {
            val bpsDouble = state.preferences.builderFeeBps / 100.0
            "$bpsDouble% active"
          } else {
            "Off"
          }
        ActionRow(
          "Builder support",
          builderStatus,
          Icons.Outlined.CardGiftcard,
          onClick = { showBuilderSheet = true },
        )
        ActionRow(
          "Referral code",
          if (state.referralRedeemed) "Code active" else "Enter a referral code",
          Icons.Outlined.CardGiftcard,
          onClick = { showReferralSheet = true },
        )
      }
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
        ActionNotice(
          "Checking authorized keys…",
          Modifier.padding(top = 12.dp),
          NoticeTone.PROGRESS,
        )
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
  if (showBuilderSheet)
    FlareSheet("Builder support", { showBuilderSheet = false }) {
      Text(
        "Support Flare development with a small contribution on orders " +
          "(capped at 0.10% by Decibel protocol rule). You can adjust your rate or revoke approval on-chain at any time.",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(16.dp))
      Text("Support rate", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(8.dp))
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        listOf(0 to "Off", 2 to "0.02%", 5 to "0.05%", 10 to "0.10%").forEach { (bps, label) ->
          val selected =
            (state.preferences.builderFeeBps == bps) &&
              (bps == 0 || state.preferences.builderApproved)
          FlareChip(
            label,
            selected,
            { onIntent(SettingsIntent.SetBuilderFeeBps(bps)) },
            Modifier.weight(1f),
            enabled = !state.busy,
          )
        }
      }
      Spacer(Modifier.height(20.dp))
      Text("Builder address", style = MaterialTheme.typography.titleSmall)
      Text(
        "Contributions are directed to Flare’s builder address configured for this build.",
        Modifier.padding(top = 4.dp, bottom = 6.dp),
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        shortAddress(state.defaultBuilderAddress),
        style = MaterialTheme.typography.bodyMedium,
        color = FlareColors.TextPrimary,
      )
      Spacer(Modifier.height(20.dp))
      Text("On-chain approval", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(6.dp))
      if (state.preferences.builderApproved) {
        ActionNotice(
          "Approved on-chain. Decibel allows builder fees up to 0.10% for your subaccount.",
          tone = NoticeTone.INFO,
        )
        Spacer(Modifier.height(12.dp))
        FlareButton(
          "Revoke on-chain approval",
          { showRevokeConfirm = true },
          Modifier.fillMaxWidth(),
          enabled = !state.busy && state.profile.ownerAddress != null,
          style = FlareButtonStyle.OUTLINE,
        )
      } else {
        ActionNotice(
          "Not approved on-chain. Selecting a rate above will ask your wallet to approve on-chain builder support.",
          tone = NoticeTone.INFO,
        )
      }
      if (state.busy) {
        Spacer(Modifier.height(12.dp))
        ActionNotice("Updating builder support…", tone = NoticeTone.PROGRESS)
      }
      state.error?.let {
        Spacer(Modifier.height(12.dp))
        ActionNotice(it, tone = NoticeTone.ALERT)
      }
    }
  if (showReferralSheet)
    FlareSheet("Referral code", { showReferralSheet = false }) {
      Text(
        "Enter a Decibel referral code to link your account and earn trading fee discounts.",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(16.dp))
      FlareTextField(
        value = state.referralCodeInput,
        onValueChange = { onIntent(SettingsIntent.ChangeReferralCode(it)) },
        placeholder = "e.g. FLARE2026",
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )
      state.referralMessage?.let { msg ->
        ActionNotice(
          msg,
          Modifier.padding(top = 12.dp),
          if (state.referralRedeemed) NoticeTone.INFO else NoticeTone.ALERT,
        )
      }
      Spacer(Modifier.height(20.dp))
      FlareButton(
        text = if (state.busy) "Redeeming…" else "Redeem code",
        onClick = { onIntent(SettingsIntent.RedeemReferralCode) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy && state.referralCodeInput.isNotBlank() && !state.referralRedeemed,
      )
    }
  if (showRevokeConfirm) {
    AlertDialog(
      onDismissRequest = { showRevokeConfirm = false },
      title = { Text("Revoke builder support?") },
      text = {
        Text(
          "This submits an on-chain transaction revoking maximum builder fee approval for your subaccount. " +
            "Your future orders will pay 0% builder fee."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showRevokeConfirm = false
            onIntent(SettingsIntent.RevokeBuilderFee)
          }
        ) {
          Text("Revoke", color = FlareColors.Negative)
        }
      },
      dismissButton = { TextButton(onClick = { showRevokeConfirm = false }) { Text("Cancel") } },
      containerColor = FlareColors.Surface,
    )
  }
  if (showDepositSheet) {
    DepositSheet(
      accountLabel = accountLabel,
      address = address,
      copied = depositCopied,
      onCopy = {
        clipboard.setText(AnnotatedString(address))
        depositCopied = true
      },
      onDismiss = { showDepositSheet = false },
    )
  }
  if (showWithdrawSheet) {
    WithdrawSheet(
      state = state,
      address = address,
      onIntent = onIntent,
      onDismiss = {
        showWithdrawSheet = false
        onIntent(SettingsIntent.DismissWithdraw)
      },
    )
  }
}

@Composable
private fun DepositSheet(
  accountLabel: String,
  address: String,
  copied: Boolean,
  onCopy: () -> Unit,
  onDismiss: () -> Unit,
) {
  FlareSheet("Deposit", onDismiss) {
    Text(
      "Deposit funds into your primary account on Aptos. Once deposited, you can transfer funds to your trading subaccounts.",
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    DetailRow("Network", "Aptos")
    DetailRow("Account", accountLabel)
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
          if (copied) "Address copied!" else "Tap to copy",
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
      text = if (copied) "Copied!" else "Copy address",
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
  val committed = state.withdrawTransaction as? TransactionState.Committed
  FlareSheet(
    title = if (committed != null) "Withdraw complete" else "Withdraw",
    onDismiss = onDismiss,
  ) {
    if (committed != null) {
      TransactionReceipt(
        message = "Successfully withdrew ${state.withdrawAmount} USDC to ${shortAddress(state.withdrawDestination)}.",
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
    DetailRow("From", "Primary (${shortAddress(address)})")
    DetailRow("Asset", "USDC (Aptos)")
    DetailRow("Network fee", "Sponsored by Flare")
    Spacer(Modifier.height(12.dp))
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
    state.withdrawError?.let { err ->
      ActionNotice(err, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
    }
    if (state.withdrawing) {
      ActionNotice("Withdrawing USDC…", Modifier.padding(top = 12.dp), NoticeTone.PROGRESS)
    }
    Spacer(Modifier.height(20.dp))
    FlareButton(
      text = if (state.withdrawing) "Withdrawing…" else "Withdraw USDC",
      onClick = { onIntent(SettingsIntent.SubmitWithdraw) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !state.withdrawing && state.withdrawDestination.isNotBlank() && state.withdrawAmount.isNotBlank(),
      working = state.withdrawing,
    )
  }
}

/** What a delegation actually permits, in place of the raw permission type. */
private fun delegationSummary(delegation: Delegation): String {
  val scope =
    if (delegation.canTradeAllPerpMarkets) "Can trade perp markets"
    else if (delegation.canTradeAllSpotMarkets) "Can trade spot markets"
    else "Can trade ${delegation.permissionMarket ?: "one market"}"
  val expiry =
    delegation.expirationTimeSeconds?.let { " · expires ${formatCalendarDate(it * 1_000L)}" }
  return scope + expiry.orEmpty()
}

private const val COPIED_CONFIRMATION_MS = 2_000L
