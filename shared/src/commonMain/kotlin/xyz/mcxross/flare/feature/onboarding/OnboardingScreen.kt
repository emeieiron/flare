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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors

@Composable
fun OnboardingRoute(
  onCompleted: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: OnboardingViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(viewModel) {
    viewModel.effects.collect { effect ->
      if (effect == OnboardingEffect.Completed) onCompleted()
    }
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
          OnboardingStep.FUNDING -> FundingStep(state, onIntent)
          OnboardingStep.API_WALLET -> ApiWalletStep(state, onIntent)
          OnboardingStep.WELCOME -> Unit
        }
      }
      state.error?.let {
        Text(
          it,
          Modifier.fillMaxWidth().padding(top = 16.dp),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
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
    Canvas(
      Modifier.size(168.dp).background(FlareColors.PositiveMuted, CircleShape).padding(40.dp)
    ) {
      val stroke = 9.dp.toPx()
      for (i in 0..2) {
        val x = size.width * (0.15f + i * 0.26f)
        drawLine(
          FlareColors.Positive,
          Offset(x, size.height * 0.82f),
          Offset(x + size.width * 0.24f, size.height * (0.36f - i * 0.1f)),
          stroke,
          StrokeCap.Round,
        )
      }
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
        else OnboardingIntent.ShowApiSetup
      )
    },
    Modifier.fillMaxWidth(),
    enabled = !state.busy,
  )
  Spacer(Modifier.height(12.dp))
  if (state.profile.ownerAddress == null && state.profile.apiWalletAddress == null) {
    FlareButton(
      "Import account",
      { onIntent(OnboardingIntent.ShowImport) },
      Modifier.fillMaxWidth(),
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
    "Paste a recovery phrase, private key, or Decibel API wallet key.",
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
            if (state.apiImport) "API wallets use private keys. Paste your trading key."
            else "Recovery phrase recognized"
          CredentialFormat.PRIVATE_KEY -> "Ed25519 private key recognized"
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
      Column(Modifier.weight(1f)) {
        Text("Decibel API wallet", style = MaterialTheme.typography.bodyLarge)
        Text(
          "Trading access to a delegated account",
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextSecondary,
        )
      }
      Switch(
        state.apiImport,
        { onIntent(OnboardingIntent.SetApiImport(it)) },
        enabled = !state.busy && state.profile.ownerAddress == null,
      )
    }
  }
  Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = FlareColors.TextSecondary)
    Text(
      "Encrypted on this device. Your key stays with you.",
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
    state.profile.ownerAddress?.let { AddressRow("Owner", it) }
    state.profile.apiWalletAddress?.let { AddressRow("API wallet", it) }
  }
}

@Composable
private fun AddressRow(label: String, address: String) {
  Text(label, style = MaterialTheme.typography.labelSmall, color = FlareColors.TextSecondary)
  Text(
    address.take(10) + "…" + address.takeLast(6),
    style = MaterialTheme.typography.labelMedium,
  )
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
private fun ApiWalletStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  Text("Enable trading", style = MaterialTheme.typography.headlineLarge)
  Text(
    "Create a separate trading key for this account. Your main wallet stays in control of deposits and withdrawals.",
    modifier = Modifier.padding(top = 12.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
  )
  Spacer(Modifier.height(28.dp))
  FlareButton(
    text = "Enable trading",
    onClick = { onIntent(OnboardingIntent.CreateApiWallet) },
    modifier = Modifier.fillMaxWidth(),
    enabled = !state.busy,
  )
  Spacer(Modifier.height(10.dp))
  FlareButton(
    text = "Use an existing trading key",
    onClick = { onIntent(OnboardingIntent.ShowApiImport) },
    modifier = Modifier.fillMaxWidth(),
    enabled = !state.busy && state.profile.apiWalletAddress == null,
    style = FlareButtonStyle.OUTLINE,
  )
  Spacer(Modifier.height(10.dp))
  FlareButton(
    text = "Set up later",
    onClick = { onIntent(OnboardingIntent.SkipApiWallet) },
    modifier = Modifier.fillMaxWidth(),
    enabled = !state.busy,
    style = FlareButtonStyle.OUTLINE,
  )
}

@Composable
private fun FundingStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  Text("Add funds", style = MaterialTheme.typography.headlineLarge)
  Text(
    "Deposit USDC on Aptos to start trading. You can also do this later from Portfolio.",
    modifier = Modifier.padding(top = 12.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
  )
  OutlinedTextField(
    value = state.fundingAmount,
    onValueChange = { onIntent(OnboardingIntent.ChangeFundingAmount(it)) },
    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    label = { Text("Aptos USDC amount") },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    singleLine = true,
    enabled = !state.busy,
  )
  state.fundingTransaction?.let { transaction ->
    Text(
      transaction.onboardingLabel(),
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      color =
        if (transaction is TransactionState.Failed) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      style = MaterialTheme.typography.labelMedium,
    )
    if (transaction is TransactionState.Failed)
      transaction.selfPayEstimateOctas?.let { estimate ->
        Text(
          "Sponsorship was rejected. Estimated self-pay cost: ${estimate.toDecimalString(8)} APT.",
          modifier = Modifier.padding(top = 8.dp),
          color = MaterialTheme.colorScheme.tertiary,
          style = MaterialTheme.typography.bodyMedium,
        )
        FlareButton(
          text = "Confirm and self-pay",
          onClick = { onIntent(OnboardingIntent.ConfirmFundingSelfPay) },
          modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
          enabled = !state.busy,
          style = FlareButtonStyle.OUTLINE,
        )
      }
  }
  FlareButton(
    text = "Deposit Aptos USDC",
    onClick = { onIntent(OnboardingIntent.DepositUsdc) },
    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    enabled = !state.busy && state.fundingAmount.isNotBlank(),
  )
  FlareButton(
    text = "Do this later",
    onClick = { onIntent(OnboardingIntent.SkipFunding) },
    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    enabled = !state.busy,
    style = FlareButtonStyle.OUTLINE,
  )
}

@Composable
private fun SubaccountStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  val ownerMode = state.profile.ownerAddress != null
  Text(
    if (ownerMode) "Choose your account" else "Connect your trading account",
    style = MaterialTheme.typography.headlineLarge,
  )
  Text(
    if (ownerMode) {
      "Choose the Decibel account you want to use with Flare."
    } else {
      "Enter the Decibel subaccount linked to this trading key. This connection gives trading access only."
    },
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
      label = { Text("Decibel subaccount address") },
      singleLine = true,
      enabled = !state.busy,
    )
  } else if (state.subaccountsLoaded) {
    Text(
      "Create a Decibel trading account to continue. You’ll approve this with your wallet.",
      modifier = Modifier.padding(top = 20.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
  } else {
    Text(
      if (state.busy) "Looking for your Decibel accounts…"
      else "Check for existing accounts to continue.",
      Modifier.padding(top = 20.dp),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  Spacer(Modifier.height(20.dp))
  if (ownerMode && state.subaccounts.isEmpty()) {
    if (state.subaccountsLoaded)
      FlareButton(
        text = "Create trading account",
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
    text = if (ownerMode) "Continue" else "Connect account",
    onClick = { onIntent(OnboardingIntent.ContinueSubaccount) },
    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    enabled =
      !state.busy && (state.selectedSubaccount != null || (!ownerMode && state.input.isNotBlank())),
  )
}

private fun shortAddress(address: String): String =
  if (address.length <= 18) address else address.take(10) + "…" + address.takeLast(6)

private fun TransactionState.onboardingLabel(): String =
  when (this) {
    TransactionState.Simulating -> "Simulating deposit"
    TransactionState.AwaitingAuthorization -> "Awaiting owner authorization"
    TransactionState.Submitting -> "Submitting deposit"
    is TransactionState.Pending -> "Deposit pending · ${hash.take(12)}…"
    is TransactionState.Committed -> "Deposit committed · ${hash.take(12)}…"
    is TransactionState.Failed -> message
  }
