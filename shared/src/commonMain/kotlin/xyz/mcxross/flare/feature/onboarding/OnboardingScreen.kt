package xyz.mcxross.flare.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.CredentialFormat
import xyz.mcxross.flare.data.WalletCredential
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareLogo
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.shortAddress

@Composable
fun OnboardingRoute(
  onCompleted: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: OnboardingViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) {
    viewModel.effects.collect { effect -> if (effect == OnboardingEffect.Completed) onCompleted() }
  }
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
    viewModel.onIntent(OnboardingIntent.ClearSensitiveState)
  }
  OnboardingScreen(state, viewModel::onIntent, modifier)
}

@Composable
fun OnboardingScreen(
  state: OnboardingUiState,
  onIntent: (OnboardingIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = state.step != OnboardingStep.WELCOME,
    onBackCompleted = { if (!state.busy) onIntent(OnboardingIntent.Back) },
  )
  BoxWithConstraints(
    modifier.fillMaxSize().background(FlareColors.Canvas).safeDrawingPadding().imePadding()
  ) {
    val pageHeight = maxHeight
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .heightIn(min = pageHeight)
        .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
      if (state.step == OnboardingStep.WELCOME) {
        WelcomeStep(state, onIntent)
      } else {
        BackBar("Your account", { if (!state.busy) onIntent(OnboardingIntent.Back) })
        Spacer(Modifier.height(32.dp))
        when (state.step) {
          OnboardingStep.IMPORT -> UnifiedImportStep(state, onIntent)
          OnboardingStep.SHOW_BACKUP -> BackupStep(state, onIntent)
          OnboardingStep.CONFIRM_BACKUP -> ConfirmBackupStep(state, onIntent)
          OnboardingStep.SUBACCOUNT -> SubaccountStep(state, onIntent)
          OnboardingStep.ENABLE_TRADING -> EnableTradingStep(state, onIntent)
          OnboardingStep.WELCOME -> Unit
        }
      }
      (state.setupTransaction as? TransactionState.Failed)?.selfPayEstimateOctas?.let { estimate ->
        ActionNotice(
          "Flare can’t cover the network fee for this step. Your wallet would pay about " +
            "${estimate.toDecimalString(8)} APT.",
          Modifier.padding(top = 16.dp),
        )
        FlareButton(
          "Pay the fee and continue",
          { onIntent(OnboardingIntent.ConfirmSetupSelfPay) },
          Modifier.fillMaxWidth().padding(top = 12.dp),
          enabled = !state.busy,
        )
      }
      state.error?.let { ActionNotice(it, Modifier.padding(top = 16.dp), NoticeTone.ALERT) }
      if (state.busy) {
        CircularProgressIndicator(
          Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp).size(24.dp),
          strokeWidth = 2.dp,
        )
      }
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WelcomeStep(
  state: OnboardingUiState,
  onIntent: (OnboardingIntent) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(top = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("flare", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.weight(1f))
    Text(
      "ON DECIBEL",
      style = MaterialTheme.typography.labelSmall,
      color = FlareColors.TextSecondary,
    )
  }
  Spacer(Modifier.weight(1f))
  Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
    Box(
      Modifier.size(168.dp).background(FlareColors.PositiveMuted, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      FlareLogo(Modifier.size(88.dp), color = FlareColors.Positive)
    }
  }
  Text("A clearer way\nto trade.", style = MaterialTheme.typography.displaySmall)
  Text(
    "Trade Decibel markets with\na wallet you control.",
    Modifier.padding(top = 16.dp),
    style = MaterialTheme.typography.bodyLarge,
    color = FlareColors.TextSecondary,
  )
  if (state.profile.ownerAddress != null || state.profile.apiWalletAddress != null)
    WalletSummary(state)
  state.profiles
    .filter { it.id != state.activeProfileId }
    .forEach { profile ->
      FlareButton(
        "Use " + shortAddress((profile.ownerAddress ?: profile.apiWalletAddress).orEmpty()),
        { onIntent(OnboardingIntent.SelectProfile(profile.id)) },
        Modifier.fillMaxWidth().padding(top = 12.dp),
        !state.busy,
        FlareButtonStyle.OUTLINE,
      )
    }
  Spacer(Modifier.weight(1f))
  Spacer(Modifier.height(32.dp))
  FlareButton(
    if (state.profile.ownerAddress == null && state.profile.apiWalletAddress == null)
      "Create account"
    else "Continue account setup",
    {
      onIntent(
        if (state.profile.ownerAddress == null && state.profile.apiWalletAddress == null)
          OnboardingIntent.CreateOwner
        else OnboardingIntent.ContinueSetup
      )
    },
    Modifier.fillMaxWidth(),
    enabled = !state.busy,
  )
  Spacer(Modifier.height(12.dp))
  FlareButton(
    "Import account",
    { onIntent(OnboardingIntent.ShowImport) },
    Modifier.fillMaxWidth(),
    !state.busy,
    FlareButtonStyle.OUTLINE,
  )
  if (state.profile.ownerAddress != null || state.profile.apiWalletAddress != null) {
    FlareButton(
      "Create another account",
      { onIntent(OnboardingIntent.CreateOwner) },
      Modifier.fillMaxWidth().padding(top = 12.dp),
      !state.busy,
      FlareButtonStyle.OUTLINE,
    )
  }
}

@Composable
private fun UnifiedImportStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  val format = remember(state.input) { WalletCredential.detect(state.input) }
  Text("Welcome back.", style = MaterialTheme.typography.headlineLarge)
  Text(
    "Paste a recovery phrase or private key.",
    Modifier.padding(top = 12.dp),
    style = MaterialTheme.typography.bodyLarge,
    color = FlareColors.TextSecondary,
  )
  OutlinedTextField(
    value = state.input,
    onValueChange = { onIntent(OnboardingIntent.ChangeInput(it)) },
    modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
    label = { Text("Recovery phrase or private key") },
    supportingText = {
      Text(
        when (format) {
          CredentialFormat.RECOVERY_PHRASE ->
            if (state.apiImport) "A trading key is a private key, not a phrase."
            else "Recovery phrase recognized"
          CredentialFormat.PRIVATE_KEY -> "Private key recognized"
          CredentialFormat.UNKNOWN -> "12–24 words, hex, or ed25519-priv-…"
        }
      )
    },
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions =
      KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    minLines = 4,
    enabled = !state.busy,
  )
  if (format == CredentialFormat.PRIVATE_KEY || state.apiImport) {
    Row(
      Modifier.fillMaxWidth().padding(top = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        if (state.apiImport) "Trading key" else "Owner key",
        Modifier.weight(1f),
        style = MaterialTheme.typography.bodyLarge,
      )
      Switch(
        state.apiImport,
        { onIntent(OnboardingIntent.SetApiImport(it)) },
        enabled = !state.busy,
      )
    }
  }
  if (state.apiImport) {
    OutlinedTextField(
      state.tradingAccountInput,
      { onIntent(OnboardingIntent.ChangeTradingAccount(it)) },
      Modifier.fillMaxWidth().padding(top = 16.dp),
      label = { Text("Trading-account address") },
      singleLine = true,
      enabled = !state.busy,
    )
    Text(
      "A trading key can trade only. Deposits and withdrawals need the owner key.",
      Modifier.padding(top = 8.dp),
      style = MaterialTheme.typography.bodySmall,
      color = FlareColors.TextSecondary,
    )
  }
  FlareButton(
    "Continue",
    { onIntent(OnboardingIntent.ImportCredential) },
    Modifier.fillMaxWidth().padding(top = 32.dp),
    enabled =
      !state.busy &&
        format != CredentialFormat.UNKNOWN &&
        (!state.apiImport || format == CredentialFormat.PRIVATE_KEY),
  )
}

@Composable
private fun WalletSummary(state: OnboardingUiState) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .padding(top = 20.dp)
        .background(FlareColors.Surface, MaterialTheme.shapes.medium)
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    state.profile.ownerAddress?.let { AddressRow("Account", it) }
    state.profile.apiWalletAddress?.let { AddressRow("This device", it) }
  }
}

@Composable
private fun AddressRow(label: String, address: String) {
  Text(label, style = MaterialTheme.typography.labelSmall, color = FlareColors.TextSecondary)
  Text(shortAddress(address), style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun BackupStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  Text("Save your recovery phrase", style = MaterialTheme.typography.headlineLarge)
  Text(
    "Write these words down in order and keep them somewhere safe. You’ll need them to recover your account.",
    modifier = Modifier.padding(top = 12.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
  )
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .padding(top = 20.dp)
        .background(FlareColors.Surface, MaterialTheme.shapes.medium)
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    state.backupWords.chunked(3).forEachIndexed { rowIndex, words ->
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        words.forEachIndexed { columnIndex, word ->
          val index = rowIndex * 3 + columnIndex
          Text(
            "${index + 1}. $word",
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
          )
        }
      }
    }
  }
  FlareButton(
    "I’ve saved these words",
    { onIntent(OnboardingIntent.ReviewBackup) },
    Modifier.fillMaxWidth().padding(top = 24.dp),
    !state.busy,
  )
}

@Composable
private fun ConfirmBackupStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  Text("One quick check.", style = MaterialTheme.typography.headlineLarge)
  Text(
    "Enter these words from your saved phrase.",
    Modifier.padding(top = 12.dp),
    style = MaterialTheme.typography.bodyLarge,
    color = FlareColors.TextSecondary,
  )
  Text(
    "Confirm three words",
    modifier = Modifier.padding(top = 24.dp),
    style = MaterialTheme.typography.titleLarge,
  )
  state.confirmationIndices.forEach { index ->
    OutlinedTextField(
      value = state.confirmations[index].orEmpty(),
      onValueChange = { onIntent(OnboardingIntent.ChangeConfirmation(index, it)) },
      modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
      label = { Text("Word ${index + 1}") },
      keyboardOptions =
        KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
      singleLine = true,
      enabled = !state.busy,
    )
  }
  FlareButton(
    text = "Confirm backup",
    onClick = { onIntent(OnboardingIntent.ConfirmBackup) },
    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    enabled =
      !state.busy &&
        state.confirmationIndices.all { state.confirmations[it].orEmpty().isNotBlank() },
  )
}

@Composable
private fun EnableTradingStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  Text("Enable trading", style = MaterialTheme.typography.headlineLarge)
  Text(
    "Allow this device to trade for ${state.selectedSubaccount?.let(::shortAddress).orEmpty()}.",
    Modifier.padding(top = 12.dp),
    color = FlareColors.TextSecondary,
  )
  Spacer(Modifier.height(28.dp))
  FlareButton(
    text = "Enable trading",
    onClick = { onIntent(OnboardingIntent.EnableTrading) },
    modifier = Modifier.fillMaxWidth(),
    enabled = !state.busy,
  )
}

@Composable
private fun SubaccountStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  val ownerMode = state.profile.ownerAddress != null
  Text(
    if (ownerMode) "Choose your account" else "Your trading account",
    style = MaterialTheme.typography.headlineLarge,
  )
  Text(
    if (ownerMode) "Choose the account you want to trade with."
    else "Enter the trading account this key is allowed to trade for.",
    modifier = Modifier.padding(top = 12.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
  )
  if (ownerMode && state.subaccounts.isNotEmpty()) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      state.subaccounts.forEach { subaccount ->
        FlareButton(
          text =
            if (subaccount.address == state.selectedSubaccount) {
              "Selected · ${shortAddress(subaccount.address)}"
            } else {
              subaccount.name.takeIf(String::isNotBlank) ?: shortAddress(subaccount.address)
            },
          onClick = { onIntent(OnboardingIntent.SelectSubaccount(subaccount.address)) },
          modifier = Modifier.fillMaxWidth(),
          enabled = !state.busy,
          style =
            if (subaccount.address == state.selectedSubaccount) {
              FlareButtonStyle.PRIMARY
            } else {
              FlareButtonStyle.OUTLINE
            },
        )
      }
    }
  } else if (!ownerMode) {
    OutlinedTextField(
      value = state.input,
      onValueChange = { onIntent(OnboardingIntent.ChangeInput(it)) },
      modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
      label = { Text("Trading-account address") },
      singleLine = true,
      enabled = !state.busy,
    )
  } else if (state.subaccountsLoaded) {
    Text(
      "Create a trading account and allow this device to trade. You’ll approve two transactions.",
      modifier = Modifier.padding(top = 20.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
  } else {
    Text(
      if (state.busy) "Looking for your accounts…" else "Check for existing accounts to continue.",
      Modifier.padding(top = 20.dp),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  Spacer(Modifier.height(20.dp))
  if (ownerMode && state.subaccounts.isEmpty()) {
    if (state.subaccountsLoaded)
      FlareButton(
        text = "Create account and enable trading",
        onClick = { onIntent(OnboardingIntent.CreateSubaccount) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy,
      )
    if (state.subaccountsLoaded) Spacer(Modifier.height(10.dp))
    FlareButton(
      text = "Check again",
      onClick = { onIntent(OnboardingIntent.DiscoverSubaccounts) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !state.busy,
      style = FlareButtonStyle.OUTLINE,
    )
  }
  FlareButton(
    text = "Continue",
    onClick = { onIntent(OnboardingIntent.ContinueSubaccount) },
    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    enabled =
      !state.busy && (state.selectedSubaccount != null || (!ownerMode && state.input.isNotBlank())),
  )
}
