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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.shortAddress
import xyz.mcxross.flare.store.AccountProfile

private const val COPIED_CONFIRMATION_MS = 1_500L

@Composable
fun AccountsRoute(
  onBack: () -> Unit,
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
  AccountsScreen(
    state = state,
    onIntent = viewModel::onIntent,
    onBack = onBack,
    onCreateAccount = onCreateAccount,
    onImportAccount = onImportAccount,
    onContinueSetup = onContinueSetup,
    modifier = modifier,
  )
}

@Composable
fun AccountsScreen(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onBack: () -> Unit,
  onCreateAccount: () -> Unit,
  onImportAccount: () -> Unit,
  onContinueSetup: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier
      .fillMaxSize()
      .background(FlareColors.Canvas)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp, vertical = 16.dp)
  ) {
    BackBar(title = "Accounts", onBack = onBack)
    Text(
      "Manage your primary wallets and trading subaccounts.",
      Modifier.padding(top = 8.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = FlareColors.TextSecondary,
    )

    if (state.error != null) {
      Spacer(Modifier.height(12.dp))
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FlareColors.Negative.copy(alpha = 0.12f))
            .padding(12.dp)
      ) {
        Text(
          text = state.error,
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.Negative,
        )
      }
    }

    SectionLabel("Connected Wallets")

    val profiles = state.preferences.profiles.filter { it.onboardingComplete }
    if (profiles.isEmpty()) {
      Text(
        "No accounts connected.",
        style = MaterialTheme.typography.bodyMedium,
        color = FlareColors.TextSecondary,
        modifier = Modifier.padding(vertical = 16.dp),
      )
    } else {
      profiles.forEachIndexed { index, profile ->
        val isActive = profile.id == state.preferences.activeProfileId
        val ownerSubaccounts = profile.ownerAddress?.let { state.subaccountsByOwner[it] }.orEmpty()
        AccountCard(
          index = index,
          profile = profile,
          isActive = isActive,
          subaccounts = ownerSubaccounts,
          creatingSubaccount = state.creatingSubaccount && isActive,
          onSwitch = { onIntent(SettingsIntent.SelectProfile(profile.id)) },
          onSelectSubaccount = { subaccountAddr ->
            onIntent(SettingsIntent.SelectSubaccount(profile.id, subaccountAddr))
          },
          onCreateSubaccount = { onIntent(SettingsIntent.CreateSubaccountForActiveProfile) },
          onContinueSetup = { onContinueSetup(profile.id) },
          enabled = !state.busy,
        )
        Spacer(Modifier.height(16.dp))
      }
    }

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
  }
}

@Composable
private fun AccountCard(
  index: Int,
  profile: AccountProfile,
  isActive: Boolean,
  subaccounts: List<Subaccount>,
  creatingSubaccount: Boolean,
  onSwitch: () -> Unit,
  onSelectSubaccount: (String) -> Unit,
  onCreateSubaccount: () -> Unit,
  onContinueSetup: () -> Unit,
  enabled: Boolean,
) {
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }
  val address = (profile.ownerAddress ?: profile.apiWalletAddress).orEmpty()

  LaunchedEffect(copied) {
    if (copied) {
      delay(COPIED_CONFIRMATION_MS)
      copied = false
    }
  }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(FlareColors.Surface, MaterialTheme.shapes.medium)
        .padding(16.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column {
        Text(
          text = "Account ${index + 1}",
          style = MaterialTheme.typography.titleMedium,
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

      if (isActive) {
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
      } else {
        FlareButton(
          text = "Switch",
          onClick = onSwitch,
          enabled = enabled,
          style = FlareButtonStyle.OUTLINE,
          modifier = Modifier.height(36.dp),
        )
      }
    }

    Spacer(Modifier.height(8.dp))

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

    if (!profile.onboardingComplete) {
      Spacer(Modifier.height(12.dp))
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
            modifier = Modifier.height(32.dp),
          )
        }
      }
    }

    // Resolve subaccounts list: from indexer query or fallback to profile.selectedSubaccount
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

    if (displaySubaccounts.isNotEmpty() || (isActive && profile.ownerAddress != null)) {
      Spacer(Modifier.height(14.dp))
      HorizontalDivider(color = FlareColors.BorderSubtle)
      Spacer(Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Trading Subaccounts (${displaySubaccounts.size})",
          style = MaterialTheme.typography.labelSmall,
          color = FlareColors.TextSecondary,
        )
      }

      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        displaySubaccounts.forEachIndexed { subIndex, subaccount ->
          val isSubSelected =
            isActive && subaccount.address.equals(profile.selectedSubaccount, ignoreCase = true)
          val label =
            if (!subaccount.customLabel.isNullOrBlank()) subaccount.customLabel
            else if (displaySubaccounts.size > 1) "Subaccount ${subIndex + 1}"
            else "Trading Subaccount"

          SubaccountRow(
            label = label.orEmpty().ifBlank { "Trading Subaccount" },
            address = subaccount.address,
            isSelected = isSubSelected,
            canSelect = !isSubSelected && enabled,
            onSelect = { onSelectSubaccount(subaccount.address) },
          )
        }

        if (isActive && profile.ownerAddress != null) {
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
