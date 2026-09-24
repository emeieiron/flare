package xyz.mcxross.flare.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
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
import xyz.mcxross.flare.store.AccountProfile

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
  var showDepositSheet by remember { mutableStateOf(false) }
  var showWithdrawSheet by remember { mutableStateOf(false) }
  var depositCopied by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current

  val completedProfiles = state.preferences.profiles.filter { it.onboardingComplete }
  val activeProfile =
    completedProfiles.firstOrNull { it.id == state.preferences.activeProfileId }
      ?: completedProfiles.firstOrNull()
  val activeIndex =
    completedProfiles.indexOfFirst { it.id == activeProfile?.id }.coerceAtLeast(0)
  val activeLabel = "Account ${activeIndex + 1}"
  val activeAddress =
    (activeProfile?.ownerAddress ?: activeProfile?.apiWalletAddress).orEmpty()
  val activeSubaccounts =
    activeProfile?.ownerAddress?.let { state.subaccountsByOwner[it] }.orEmpty()

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
    FlareTopBar(
      title = "Account",
      action = {
        IconButton(
          onClick = onOpenSettings,
          modifier = Modifier.size(40.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = "Settings",
            tint = FlareColors.TextPrimary,
          )
        }
      },
    )

    if (activeProfile == null) {
      Text("Your account,\nyour control.", style = MaterialTheme.typography.headlineLarge)
      Text(
        "Create an account or bring the one you already use.",
        Modifier.padding(top = 12.dp),
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyLarge,
      )
      FlareButton(
        "Create or import account",
        onCreateAccount,
        Modifier.fillMaxWidth().padding(top = 24.dp),
      )
    } else {
      state.error?.let {
        ActionNotice(
          message = it,
          modifier = Modifier.padding(bottom = 16.dp),
          tone = NoticeTone.ALERT,
        )
      }

      ActiveAccountCard(
        label = activeLabel,
        profile = activeProfile,
        address = activeAddress,
        subaccounts = activeSubaccounts,
        creatingSubaccount = state.creatingSubaccount,
        onDeposit = { showDepositSheet = true },
        onWithdraw = { showWithdrawSheet = true },
        onSelectSubaccount = { subaccountAddr ->
          onIntent(SettingsIntent.SelectSubaccount(activeProfile.id, subaccountAddr))
        },
        onCreateSubaccount = { onIntent(SettingsIntent.CreateSubaccountForActiveProfile) },
        enabled = !state.busy,
      )

      val otherProfiles = completedProfiles.filter { it.id != activeProfile.id }
      if (otherProfiles.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Other Wallets (${otherProfiles.size})")
        otherProfiles.forEach { profile ->
          val idx = completedProfiles.indexOfFirst { it.id == profile.id }
          val label = "Account ${idx + 1}"
          val addr = (profile.ownerAddress ?: profile.apiWalletAddress).orEmpty()
          val subs = profile.ownerAddress?.let { state.subaccountsByOwner[it] }.orEmpty()
          OtherAccountCard(
            label = label,
            profile = profile,
            address = addr,
            subaccountCount = subs.size,
            onSwitch = { onIntent(SettingsIntent.SelectProfile(profile.id)) },
            onContinueSetup = { onContinueSetup(profile.id) },
            enabled = !state.busy,
          )
          Spacer(Modifier.height(12.dp))
        }
      }

      Spacer(Modifier.height(8.dp))
      SectionLabel("Add or Import")

      ActionRow(
        title = "Create a new account",
        subtitle = "Generate a new 12-word recovery phrase and wallet",
        icon = Icons.Outlined.Key,
        onClick = onCreateAccount,
        enabled = !state.busy,
      )
      ActionRow(
        title = "Import an account",
        subtitle = "Bring an existing account with a recovery phrase or private key",
        icon = Icons.Outlined.AccountBalanceWallet,
        onClick = onImportAccount,
        enabled = !state.busy,
      )
      Spacer(Modifier.height(24.dp))
    }
  }

  if (showDepositSheet && activeAddress.isNotBlank()) {
    DepositSheet(
      accountLabel = activeLabel,
      address = activeAddress,
      copied = depositCopied,
      onCopy = {
        clipboard.setText(AnnotatedString(activeAddress))
        depositCopied = true
      },
      onDismiss = { showDepositSheet = false },
    )
  }

  if (showWithdrawSheet && activeAddress.isNotBlank()) {
    WithdrawSheet(
      state = state,
      address = activeAddress,
      onIntent = onIntent,
      onDismiss = {
        showWithdrawSheet = false
        onIntent(SettingsIntent.DismissWithdraw)
      },
    )
  }
}

@Composable
private fun ActiveAccountCard(
  label: String,
  profile: AccountProfile,
  address: String,
  subaccounts: List<Subaccount>,
  creatingSubaccount: Boolean,
  onDeposit: () -> Unit,
  onWithdraw: () -> Unit,
  onSelectSubaccount: (String) -> Unit,
  onCreateSubaccount: () -> Unit,
  enabled: Boolean,
) {
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }

  LaunchedEffect(copied) {
    if (copied) {
      delay(COPIED_CONFIRMATION_MS)
      copied = false
    }
  }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(FlareColors.Surface)
        .padding(18.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        Text(
          text = label,
          style = MaterialTheme.typography.titleLarge,
          color = FlareColors.TextPrimary,
        )
        if (profile.ownerAddress == null && profile.apiWalletAddress != null) {
          Text(
            text = "Trading key only",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextTertiary,
          )
        }
      }

      Box(
        modifier =
          Modifier.clip(RoundedCornerShape(12.dp))
            .background(FlareColors.PositiveMuted)
            .padding(horizontal = 10.dp, vertical = 4.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = FlareColors.Positive,
            modifier = Modifier.size(12.dp),
          )
          Text(
            "Active",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.Positive,
          )
        }
      }
    }

    Spacer(Modifier.height(6.dp))

    Row(
      modifier =
        Modifier.clickable(role = Role.Button) {
            if (address.isNotBlank()) {
              clipboard.setText(AnnotatedString(address))
              copied = true
            }
          }
          .padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = if (address.isNotBlank()) shortAddress(address) else "No address",
        style = MaterialTheme.typography.bodyMedium,
        color = FlareColors.TextSecondary,
      )
      Icon(
        imageVector = Icons.Outlined.ContentCopy,
        contentDescription = "Copy address",
        tint = FlareColors.TextTertiary,
        modifier = Modifier.size(14.dp),
      )
      if (copied) {
        Text(
          text = "Copied",
          color = FlareColors.Positive,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }

    if (profile.ownerAddress != null) {
      Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        FlareButton(
          "Deposit",
          onClick = onDeposit,
          modifier = Modifier.weight(1f),
        )
        FlareButton(
          "Withdraw",
          onClick = onWithdraw,
          modifier = Modifier.weight(1f),
          style = FlareButtonStyle.OUTLINE,
        )
      }
    }

    val displaySubaccounts =
      if (subaccounts.isNotEmpty()) {
        subaccounts
      } else if (profile.selectedSubaccount != null) {
        listOf(
          Subaccount(
            address = profile.selectedSubaccount,
            owner = profile.ownerAddress.orEmpty(),
            customLabel = null,
            isPrimary = true,
            isActive = true,
          )
        )
      } else {
        emptyList()
      }

    if (displaySubaccounts.isNotEmpty() || (profile.ownerAddress != null)) {
      Spacer(Modifier.height(16.dp))
      HorizontalDivider(color = FlareColors.BorderSubtle)
      Spacer(Modifier.height(14.dp))

      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Trading Subaccounts (${displaySubaccounts.size})",
          style = MaterialTheme.typography.labelMedium,
          color = FlareColors.TextSecondary,
        )
      }

      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        displaySubaccounts.forEachIndexed { subIndex, subaccount ->
          val isSubSelected =
            subaccount.address.equals(profile.selectedSubaccount, ignoreCase = true)
          val subLabel =
            if (!subaccount.customLabel.isNullOrBlank()) subaccount.customLabel
            else if (displaySubaccounts.size > 1) "Subaccount ${subIndex + 1}"
            else "Trading Subaccount"

          SubaccountRow(
            label = subLabel.orEmpty().ifBlank { "Trading Subaccount" },
            address = subaccount.address,
            isSelected = isSubSelected,
            canSelect = !isSubSelected && enabled,
            onSelect = { onSelectSubaccount(subaccount.address) },
          )
        }

        if (profile.ownerAddress != null) {
          if (creatingSubaccount) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = FlareColors.Positive,
              )
              Spacer(Modifier.width(8.dp))
              Text(
                "Creating trading subaccount…",
                style = MaterialTheme.typography.bodySmall,
                color = FlareColors.TextSecondary,
              )
            }
          } else {
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .clickable(enabled = enabled) { onCreateSubaccount() }
                  .padding(vertical = 8.dp, horizontal = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = FlareColors.Positive,
                modifier = Modifier.size(16.dp),
              )
              Text(
                text = "Add trading subaccount",
                style = MaterialTheme.typography.labelMedium,
                color = FlareColors.Positive,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OtherAccountCard(
  label: String,
  profile: AccountProfile,
  address: String,
  subaccountCount: Int,
  onSwitch: () -> Unit,
  onContinueSetup: () -> Unit,
  enabled: Boolean,
) {
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }

  LaunchedEffect(copied) {
    if (copied) {
      delay(COPIED_CONFIRMATION_MS)
      copied = false
    }
  }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(FlareColors.Surface)
        .padding(16.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        Text(
          text = label,
          style = MaterialTheme.typography.titleMedium,
          color = FlareColors.TextPrimary,
        )
        if (subaccountCount > 0) {
          Text(
            text = "$subaccountCount subaccount${if (subaccountCount > 1) "s" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextTertiary,
          )
        }
      }

      FlareButton(
        text = "Switch",
        onClick = onSwitch,
        enabled = enabled,
        style = FlareButtonStyle.OUTLINE,
        modifier = Modifier.height(34.dp),
      )
    }

    Spacer(Modifier.height(6.dp))

    Row(
      modifier =
        Modifier.clickable(role = Role.Button) {
            if (address.isNotBlank()) {
              clipboard.setText(AnnotatedString(address))
              copied = true
            }
          }
          .padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = if (address.isNotBlank()) shortAddress(address) else "No address",
        style = MaterialTheme.typography.bodySmall,
        color = FlareColors.TextSecondary,
      )
      Icon(
        imageVector = Icons.Outlined.ContentCopy,
        contentDescription = "Copy address",
        tint = FlareColors.TextTertiary,
        modifier = Modifier.size(12.dp),
      )
      if (copied) {
        Text(
          text = "Copied",
          color = FlareColors.Positive,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }

    if (!profile.onboardingComplete) {
      Spacer(Modifier.height(8.dp))
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FlareColors.Warning.copy(alpha = 0.12f))
            .padding(10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "Setup incomplete",
            style = MaterialTheme.typography.bodySmall,
            color = FlareColors.Warning,
          )
          FlareButton(
            text = "Finish setup",
            onClick = onContinueSetup,
            enabled = enabled,
            modifier = Modifier.height(28.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun SubaccountRow(
  label: String,
  address: String,
  isSelected: Boolean,
  canSelect: Boolean,
  onSelect: () -> Unit,
) {
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }

  LaunchedEffect(copied) {
    if (copied) {
      delay(COPIED_CONFIRMATION_MS)
      copied = false
    }
  }

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(FlareColors.Elevated, RoundedCornerShape(8.dp))
        .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      imageVector = Icons.Outlined.Tune,
      contentDescription = null,
      tint = if (isSelected) FlareColors.Positive else FlareColors.TextTertiary,
      modifier = Modifier.size(16.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = FlareColors.TextSecondary,
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
          Modifier.clickable(role = Role.Button) {
            clipboard.setText(AnnotatedString(address))
            copied = true
          },
      ) {
        Text(
          text = shortAddress(address),
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextPrimary,
        )
        Icon(
          imageVector = Icons.Outlined.ContentCopy,
          contentDescription = "Copy subaccount address",
          tint = FlareColors.TextTertiary,
          modifier = Modifier.size(12.dp),
        )
        if (copied) {
          Text(
            text = "Copied",
            color = FlareColors.Positive,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
    if (isSelected) {
      Box(
        modifier =
          Modifier.clip(RoundedCornerShape(8.dp))
            .background(FlareColors.PositiveMuted)
            .padding(horizontal = 8.dp, vertical = 3.dp)
      ) {
        Text(
          text = "● Selected",
          style = MaterialTheme.typography.labelSmall,
          color = FlareColors.Positive,
        )
      }
    } else if (canSelect) {
      FlareButton(
        text = "Select",
        onClick = onSelect,
        style = FlareButtonStyle.OUTLINE,
        modifier = Modifier.height(30.dp),
      )
    }
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
      enabled =
        !state.withdrawing &&
          state.withdrawDestination.isNotBlank() &&
          state.withdrawAmount.isNotBlank(),
      working = state.withdrawing,
    )
  }
}
