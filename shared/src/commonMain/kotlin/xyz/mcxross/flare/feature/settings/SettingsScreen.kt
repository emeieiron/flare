package xyz.mcxross.flare.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatCalendarDate
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareTextField
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.SectionLabel
import xyz.mcxross.flare.design.SwitchRow
import xyz.mcxross.flare.design.shortAddress
import xyz.mcxross.flare.store.FlarePreferences

@Composable
fun SettingsRoute(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(SettingsIntent.HideSecret) }
  DisposableEffect(viewModel) { onDispose { viewModel.onIntent(SettingsIntent.HideSecret) } }
  SettingsScreen(state, viewModel::onIntent, onBack, modifier)
}

@Composable
fun SettingsScreen(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showAccess by remember { mutableStateOf(false) }
  var showBuilderSheet by remember { mutableStateOf(false) }
  var showReferralSheet by remember { mutableStateOf(false) }
  var revokeProduct by remember { mutableStateOf<AssetType?>(null) }
  var revokeAddress by remember { mutableStateOf<String?>(null) }
  var showSecurity by remember { mutableStateOf(false) }
  var removal by remember { mutableStateOf<SettingsIntent?>(null) }

  Column(
    modifier
      .fillMaxSize()
      .background(FlareColors.Canvas)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp)
  ) {
    BackBar(title = "Settings", onBack = onBack)

    SectionLabel("Trading")
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

    Spacer(Modifier.height(8.dp))

    SwitchRow(
      "Confirm transactions",
      "Ask for your fingerprint, face, or passcode before each order, cancellation, or position change.",
      state.preferences.confirmTransactions,
      { onIntent(SettingsIntent.SetConfirmTransactions(it)) },
      enabled = !state.busy,
    )

    ActionRow(
      "Trading access",
      "Devices and keys that can place orders",
      Icons.Outlined.PhonelinkLock,
      onClick = {
        showAccess = true
        onIntent(SettingsIntent.LoadDelegations)
      },
    )

    if (state.profile.ownerAddress != null) {
      val builderStatus = builderStatus(state.preferences)
      SectionLabel("Protocol & Rewards")
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

    SectionLabel("About")
    DetailRow("Network", "Aptos Testnet")
    DetailRow("DEX Protocol", "Decibel")
    DetailRow("Version", "1.0.0 (Flare)")

    state.error?.let { ActionNotice(it, Modifier.padding(top = 16.dp, bottom = 24.dp), NoticeTone.ALERT) }
    Spacer(Modifier.height(24.dp))
  }

  if (showAccess) {
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
      if (state.delegationsLoaded && state.delegations.isEmpty()) {
        Text("Nothing can trade for this account yet.")
      }
      if (!state.delegationsLoaded || state.busy) {
        ActionNotice(
          "Checking authorized keys…",
          Modifier.padding(top = 12.dp),
          NoticeTone.PROGRESS,
        )
      }
      state.error?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
    }
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

  if (showSecurity && state.revealedSecret == null) {
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
      if (state.profile.apiOnly) {
        Text(
          "Deposits and withdrawals require your main wallet.",
          Modifier.padding(top = 16.dp),
          color = FlareColors.TextSecondary,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
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

  if (showBuilderSheet) {
    val preferences = state.preferences
    FlareSheet("Builder support", { showBuilderSheet = false }) {
      Text(
        "Support Flare with a small share of each order. Perpetuals and spot each have their own " +
          "rate and on-chain approval, capped at 0.10% by Decibel.",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
      )
      listOf(
          Triple(AssetType.PERP, preferences.builderApproved, preferences.builderFeeBps),
          Triple(AssetType.SPOT, preferences.spotBuilderApproved, preferences.spotBuilderFeeBps),
        )
        .forEachIndexed { index, (product, approved, bps) ->
          if (index > 0) HorizontalDivider(Modifier.padding(top = 24.dp), color = FlareColors.BorderSubtle)
          BuilderProductSection(
            title = if (product == AssetType.PERP) "Perpetuals" else "Spot",
            approved = approved,
            basisPoints = bps,
            pending = state.builderPending == product,
            enabled = !state.busy && state.profile.ownerAddress != null,
            onRate = { onIntent(SettingsIntent.SetBuilderFeeBps(product, it)) },
            onRevoke = { revokeProduct = product },
          )
        }
      Spacer(Modifier.height(24.dp))
      Text(
        "Paid to Flare’s builder address, ${shortAddress(preferences.builderAddress ?: state.defaultBuilderAddress)}",
        color = FlareColors.TextTertiary,
        style = MaterialTheme.typography.bodySmall,
      )
      state.error?.let {
        Spacer(Modifier.height(16.dp))
        ActionNotice(it, tone = NoticeTone.ALERT)
      }
    }
  }

  if (showReferralSheet) {
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
  }

  revokeProduct?.let { product ->
    val name = if (product == AssetType.PERP) "perpetuals" else "spot"
    AlertDialog(
      onDismissRequest = { revokeProduct = null },
      title = { Text("Revoke $name approval?") },
      text = {
        Text(
          "This submits an on-chain transaction. Your $name orders will stop including the " +
            "builder fee."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            revokeProduct = null
            onIntent(SettingsIntent.RevokeBuilderFee(product))
          }
        ) {
          Text("Revoke", color = FlareColors.Negative)
        }
      },
      dismissButton = { TextButton(onClick = { revokeProduct = null }) { Text("Cancel") } },
      containerColor = FlareColors.Surface,
    )
  }
}

private val BuilderRates = listOf(0 to "Off", 2 to "0.02%", 5 to "0.05%", 10 to "0.10%")

private fun builderRateLabel(bps: Int): String =
  BuilderRates.firstOrNull { it.first == bps }?.second ?: "${bps / 100.0}%"

/** Each product's live rate, e.g. "Perpetuals 0.05% · Spot 0.02%". */
private fun builderStatus(preferences: FlarePreferences): String =
  listOfNotNull(
      "Perpetuals ${builderRateLabel(preferences.builderFeeBps)}".takeIf {
        preferences.builderApproved && preferences.builderFeeBps > 0
      },
      "Spot ${builderRateLabel(preferences.spotBuilderFeeBps)}".takeIf {
        preferences.spotBuilderApproved && preferences.spotBuilderFeeBps > 0
      },
    )
    .joinToString(" · ")
    .ifEmpty { "Off" }

/**
 * One product's builder support: its approval, its own rate, and a way to revoke. Picking a rate on
 * an unapproved product asks the wallet to approve it first.
 */
@Composable
private fun BuilderProductSection(
  title: String,
  approved: Boolean,
  basisPoints: Int,
  pending: Boolean,
  enabled: Boolean,
  onRate: (Int) -> Unit,
  onRevoke: () -> Unit,
) {
  Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(
        when {
          pending -> if (approved) "Revoking on-chain…" else "Approving on-chain…"
          approved -> "Approved on-chain up to 0.10%"
          else -> "Not approved. Picking a rate asks your wallet to approve it."
        },
        Modifier.padding(top = 4.dp),
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (approved) {
      // No trailing inset, so the label lines up with the chips' right edge.
      TextButton(onRevoke, enabled = enabled, contentPadding = PaddingValues(start = 12.dp)) {
        Text(
          "Revoke",
          color = if (enabled) FlareColors.TextSecondary else FlareColors.TextDisabled,
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
  Spacer(Modifier.height(12.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    // Nothing is charged without an approval, so the rate reads as off until one exists.
    val effective = if (approved) basisPoints else 0
    BuilderRates.forEach { (bps, label) ->
      FlareChip(label, effective == bps, { onRate(bps) }, Modifier.weight(1f), enabled = enabled)
    }
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
