package xyz.mcxross.flare.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.floor
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.sameAptosAddress
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareAddressField
import xyz.mcxross.flare.design.FlareAmountField
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareQrCode
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.LocalTransactionExplorer
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.Outcome
import xyz.mcxross.flare.design.OutcomeIcon
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.design.emphasizedAddress
import xyz.mcxross.flare.design.fullBleed
import xyz.mcxross.flare.design.isAptosAddress
import xyz.mcxross.flare.design.rememberOutcomeReveal
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
          FlareButton("Withdraw", { showWithdraw = true; onIntent(SettingsIntent.OpenWithdraw) }, Modifier.weight(1f),
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
  if (showWithdraw && active?.selectedSubaccount != null) WithdrawSheet(state,
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
  FlareSheet("Receive funds", onDismiss, heightFraction = 0.9f) {
    Text(
      "Send USDC or APT on the Aptos network to $accountLabel.",
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
    // The code takes whatever room the sheet can spare, up to a size that scans from arm's length.
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      val codeSize = minOf(maxWidth * 0.84f, maxHeight - 112.dp, 320.dp).coerceAtLeast(168.dp)
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FlareQrCode(address, "QR code for your Aptos address", Modifier.size(codeSize))
        ReceiveAddress(address, onCopy, Modifier.padding(top = 20.dp))
      }
    }
    ActionNotice(
      "Only send Aptos-native USDC and APT. Assets sent from other networks can be lost for good.",
      tone = NoticeTone.INFO,
    )
    FlareButton(
      text = if (copied) "Copied" else "Copy address",
      onClick = onCopy,
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
  }
}

/** The address to share, emphasised at both ends. Tapping it copies. */
@Composable
private fun ReceiveAddress(address: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
  Text(
    emphasizedAddress(address),
    modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(role = Role.Button, onClickLabel = "Copy address", onClick = onCopy)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    style = MaterialTheme.typography.bodyMedium,
    textAlign = TextAlign.Center,
  )
}

/** Where a withdrawal is: being set up, checked, done, or stopped. */
private enum class WithdrawStage { FORM, REVIEW, SENT, FAILED }

/**
 * Withdrawing USDC from the trading account to any Aptos address. Every stage keeps its action at the
 * bottom. An interrupted withdrawal reopens at its review, and a failure says where the funds are.
 */
@Composable
fun WithdrawSheet(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onDismiss: () -> Unit,
  startInReview: Boolean = state.pendingWithdrawal != null,
) {
  var reviewing by rememberSaveable { mutableStateOf(startInReview) }
  // Progress is saved as soon as any withdrawal starts; only one found on opening is being resumed.
  val resuming = rememberSaveable { state.pendingWithdrawal != null }
  val transaction = state.withdrawTransaction
  // A fee Flare can't sponsor keeps the review open with the choice to pay it.
  val feeEstimate = (transaction as? TransactionState.Failed)?.selfPayEstimateOctas
  val stage =
    when {
      transaction is TransactionState.Committed -> WithdrawStage.SENT
      !state.withdrawing && feeEstimate == null && transaction != null &&
        (transaction is TransactionState.Failed || state.withdrawError != null) -> WithdrawStage.FAILED
      reviewing || state.withdrawing || feeEstimate != null -> WithdrawStage.REVIEW
      else -> WithdrawStage.FORM
    }
  FlareSheet(
    title =
      when (stage) {
        WithdrawStage.FORM -> "Withdraw"
        WithdrawStage.REVIEW -> "Review withdrawal"
        WithdrawStage.SENT, WithdrawStage.FAILED -> null
      },
    onDismiss = onDismiss,
    dismissible = !state.withdrawing,
  ) {
    // The height animates between stages. Its clip spans the full sheet, not just the text column, so
    // anything that draws into the gutter, like the outcome icon's halo, isn't cut at the margin.
    Column(Modifier.fullBleed().animateContentSize().padding(horizontal = 24.dp)) {
      when (stage) {
        WithdrawStage.FORM -> WithdrawForm(state, onIntent) { reviewing = true }
        WithdrawStage.REVIEW -> WithdrawReview(state, onIntent, feeEstimate, resuming) { reviewing = false }
        WithdrawStage.SENT -> WithdrawSent(state, (transaction as TransactionState.Committed).hash, onDismiss)
        WithdrawStage.FAILED ->
          WithdrawFailed(state, onIntent) {
            onIntent(SettingsIntent.EditWithdraw)
            reviewing = false
          }
      }
    }
  }
}

@Composable
private fun WithdrawForm(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onReview: () -> Unit,
) {
  val destination = state.withdrawDestination.trim()
  val addressInvalid = destination.isNotEmpty() && !destination.isAptosAddress()
  val amount = state.withdrawAmount.toDoubleOrNull()
  val available = state.withdrawable
  // Guard at the field: an amount above the balance is flagged before anything is signed.
  val exceeds = amount != null && available != null && amount > available + BALANCE_EPSILON
  val canReview = destination.isAptosAddress() && amount != null && amount > 0.0 && !exceeds
  Text(
    "Send USDC from your trading account to any Aptos address.",
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodyMedium,
  )
  FlareAddressField(
    state.withdrawDestination,
    { onIntent(SettingsIntent.ChangeWithdrawDestination(it)) },
    Modifier.fillMaxWidth().padding(top = 20.dp),
    label = "Recipient",
    placeholder = "Aptos address",
    isError = addressInvalid,
    supportingText = if (addressInvalid) "Enter a valid Aptos address." else null,
  )
  FlareAmountField(
    value = state.withdrawAmount,
    onValueChange = { onIntent(SettingsIntent.ChangeWithdrawAmount(it)) },
    label = "Amount",
    unit = "USDC",
    availableText = available?.let { "Available ${formatQuantity(it, 2)} USDC" },
    onMaxClick =
      available?.takeIf { it > 0.0 }?.let { max -> { onIntent(SettingsIntent.ChangeWithdrawAmount(maxAmountInput(max))) } },
    isError = exceeds,
    supportingText = if (exceeds && available != null) "You can withdraw up to ${formatQuantity(available, 2)} USDC." else null,
    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
  )
  state.withdrawError?.let {
    ActionNotice(actionFailure(it, WITHDRAWAL_FAILED), Modifier.padding(top = 12.dp), NoticeTone.ALERT)
  }
  FlareButton("Review withdrawal", onReview, Modifier.fillMaxWidth().padding(top = 24.dp), enabled = canReview)
}

@Composable
private fun WithdrawReview(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  feeEstimate: ULong?,
  resuming: Boolean,
  onEdit: () -> Unit,
) {
  // An unfinished withdrawal can only be completed as it was started, so it can't be edited.
  val pending = state.pendingWithdrawal?.takeIf { resuming || !state.withdrawing }
  // A withdrawal to the owner's own wallet is a single step, with nothing to send on.
  val sendsOn =
    state.profile.ownerAddress?.let { owner ->
      runCatching { !state.withdrawDestination.trim().sameAptosAddress(owner) }.getOrDefault(true)
    } ?: true
  pending?.let {
    ActionNotice(
      if (it.withdrawalCommitted) "Your USDC reached your wallet but wasn’t sent on yet. Confirm to finish sending it."
      else "Your last withdrawal didn’t finish. Confirm to pick it up where it stopped.",
      Modifier.padding(bottom = 20.dp),
    )
  }
  Text("${withdrawAmountText(state)} USDC", style = MaterialTheme.typography.displaySmall)
  Text(
    "From your trading account",
    Modifier.padding(top = 4.dp),
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodyMedium,
  )
  Text(
    "To",
    Modifier.padding(top = 24.dp),
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.labelMedium,
  )
  Text(
    emphasizedAddress(state.withdrawDestination.trim()),
    Modifier.padding(top = 6.dp),
    style = MaterialTheme.typography.bodyLarge,
  )
  HorizontalDivider(Modifier.padding(top = 16.dp), color = FlareColors.BorderSubtle)
  DetailRow("Network", "Aptos")
  DetailRow("Network fee", feeEstimate?.let { "About ${it.toDecimalString(8)} APT" } ?: "Sponsored by Flare")
  Text(
    "Withdrawals can’t be reversed. Check the address before you confirm.",
    Modifier.padding(top = 12.dp),
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodySmall,
  )
  feeEstimate?.let {
    ActionNotice("Flare can’t cover the network fee right now, so your wallet pays it.", Modifier.padding(top = 16.dp))
  }
  // Problems found before anything was sent stay here, next to what they're about.
  state.withdrawError?.takeIf { state.withdrawTransaction == null }?.let {
    ActionNotice(actionFailure(it, WITHDRAWAL_FAILED), Modifier.padding(top = 16.dp), NoticeTone.ALERT)
  }
  FlareButton(
    when {
      state.withdrawing && sendsOn && state.pendingWithdrawal?.withdrawalCommitted == true ->
        "Sending to recipient…"
      state.withdrawing -> "Withdrawing…"
      feeEstimate != null -> "Pay the fee and withdraw"
      pending != null -> "Finish withdrawal"
      else -> "Confirm withdrawal"
    },
    {
      onIntent(
        if (feeEstimate != null) SettingsIntent.ConfirmWithdrawSelfPay else SettingsIntent.SubmitWithdraw
      )
    },
    Modifier.fillMaxWidth().padding(top = 24.dp),
    working = state.withdrawing,
  )
  // An interrupted withdrawal can only be finished as it was started.
  if (pending == null) QuietAction("Edit", onEdit, enabled = !state.withdrawing)
}

@Composable
private fun WithdrawSent(state: SettingsUiState, hash: String, onDone: () -> Unit) {
  val explorer = LocalTransactionExplorer.current
  val browser = LocalUriHandler.current
  val revealed = rememberOutcomeReveal()
  val amount = withdrawAmountText(state)
  val recipient = shortAddress(state.withdrawDestination.trim())
  OutcomeIcon(Outcome.SUCCESS)
  Column(revealed) {
    Text("Withdrawal complete", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
    Text(
      "$amount USDC was sent to $recipient.",
      Modifier.padding(top = 6.dp),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  Column(Modifier.padding(top = 16.dp).then(revealed)) {
    DetailRow("Amount", "$amount USDC")
    DetailRow("To", recipient)
    ActionRow(
      "View on Aptos Explorer",
      icon = Icons.AutoMirrored.Outlined.OpenInNew,
      onClick = { runCatching { browser.openUri(explorer.url(hash)) } },
    )
  }
  FlareButton("Done", onDone, Modifier.fillMaxWidth().padding(top = 24.dp))
}

@Composable
private fun WithdrawFailed(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit, onEdit: () -> Unit) {
  val pending = state.pendingWithdrawal
  val revealed = rememberOutcomeReveal()
  val raw = state.withdrawError ?: (state.withdrawTransaction as? TransactionState.Failed)?.message
  val cause = actionFailure(raw, "").trim().takeIf { it.isNotEmpty() }?.let { if (it.endsWith('.')) it else "$it." }
  // Say where the money is: that is what someone needs to know after a failure.
  val funds =
    when {
      pending?.withdrawalCommitted == true -> "Your USDC is in your wallet and wasn’t sent on. Try again to finish."
      pending?.withdrawalReference != null ->
        "It isn’t clear yet whether it went through. Trying again checks first, so nothing is sent twice."
      else -> "Your USDC is still in your trading account."
    }
  OutcomeIcon(Outcome.FAILURE)
  Column(revealed) {
    Text("Withdrawal didn’t go through", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
    Text(
      listOfNotNull(cause, funds).joinToString(" "),
      Modifier.padding(top = 6.dp),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  Column(Modifier.padding(top = 16.dp).then(revealed)) {
    DetailRow("Amount", "${withdrawAmountText(state)} USDC")
    DetailRow("To", shortAddress(state.withdrawDestination.trim()))
  }
  FlareButton(
    "Try again",
    { onIntent(SettingsIntent.SubmitWithdraw) },
    Modifier.fillMaxWidth().padding(top = 24.dp),
    working = state.withdrawing,
  )
  if (pending == null) QuietAction("Edit withdrawal", onEdit, enabled = !state.withdrawing)
}

/** The quiet way out under a primary action, as in every confirmation. */
@Composable
private fun QuietAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
  TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 4.dp), enabled = enabled) {
    Text(
      label,
      color = if (enabled) FlareColors.TextSecondary else FlareColors.TextDisabled,
      style = MaterialTheme.typography.labelLarge,
    )
  }
}

private fun withdrawAmountText(state: SettingsUiState): String =
  state.withdrawAmount.toDoubleOrNull()?.let { formatQuantity(it, 6) } ?: state.withdrawAmount

/** The whole balance, rounded down to USDC's precision so it never reads as more than there is. */
private fun maxAmountInput(balance: Double): String {
  val micros = floor(balance * 1_000_000).toLong().coerceAtLeast(0)
  val fraction = (micros % 1_000_000).toString().padStart(6, '0').trimEnd('0')
  return (micros / 1_000_000).toString() + if (fraction.isEmpty()) "" else ".$fraction"
}

private const val WITHDRAWAL_FAILED = "Your withdrawal didn’t go through."

/** Tolerance for comparing a typed amount with a balance read as a double. */
private const val BALANCE_EPSILON = 1e-9
