package xyz.mcxross.flare.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.toDecimalString
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
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(FlareColors.Canvas)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 32.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text("FLARE", style = MaterialTheme.typography.labelMedium, color = FlareColors.Positive)
    Spacer(Modifier.height(12.dp))
    when (state.step) {
      OnboardingStep.WELCOME -> WelcomeStep(state, onIntent)
      OnboardingStep.IMPORT_OWNER ->
        SecretImportStep(
          title = "Import owner wallet",
          message =
            "Enter a Kaptos-supported BIP-39 phrase. It is encrypted locally and is never sent to Flare's Worker.",
          placeholder = "12-word recovery phrase",
          value = state.input,
          busy = state.busy,
          onValue = { onIntent(OnboardingIntent.ChangeInput(it)) },
          onSubmit = { onIntent(OnboardingIntent.ImportOwner) },
          onBack = { onIntent(OnboardingIntent.Back) },
        )
      OnboardingStep.CONFIRM_BACKUP -> BackupStep(state, onIntent)
      OnboardingStep.SUBACCOUNT -> SubaccountStep(state, onIntent)
      OnboardingStep.FUNDING -> FundingStep(state, onIntent)
      OnboardingStep.API_WALLET -> ApiWalletStep(state, onIntent)
      OnboardingStep.IMPORT_API ->
        SecretImportStep(
          title = "Import API wallet",
          message =
            "Use an AIP-80 Ed25519 private key. This profile can trade after its active Decibel delegation is verified, but owner-only actions remain unavailable.",
          placeholder = "ed25519-priv-…",
          value = state.input,
          busy = state.busy,
          onValue = { onIntent(OnboardingIntent.ChangeInput(it)) },
          onSubmit = { onIntent(OnboardingIntent.ImportApiWallet) },
          onBack = { onIntent(OnboardingIntent.Back) },
        )
    }
    state.error?.let {
      Text(
        text = it,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
      )
    }
    if (state.busy) {
      CircularProgressIndicator(
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp),
        color = FlareColors.Positive,
      )
    }
  }
}

@Composable
private fun WelcomeStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  Text("Native Decibel trading", style = MaterialTheme.typography.headlineLarge)
  Text(
    "Browse perpetual markets without a wallet, or protect local owner and API keys with your device credentials.",
    modifier = Modifier.padding(top = 12.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
  )
  if (state.profile.ownerAddress != null || state.profile.apiWalletAddress != null) {
    WalletSummary(state)
  }
  Spacer(Modifier.height(28.dp))
  if (state.profile.ownerAddress == null) {
    FlareButton(
      text = "Create owner wallet",
      onClick = { onIntent(OnboardingIntent.CreateOwner) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !state.busy,
    )
    Spacer(Modifier.height(10.dp))
    FlareButton(
      text = "Import owner wallet",
      onClick = { onIntent(OnboardingIntent.ShowOwnerImport) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !state.busy,
      style = FlareButtonStyle.OUTLINE,
    )
  } else {
    FlareButton(
      text = "Continue API wallet setup",
      onClick = { onIntent(OnboardingIntent.ShowApiSetup) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !state.busy,
    )
  }
  Spacer(Modifier.height(10.dp))
  FlareButton(
    text = "Import API wallet only",
    onClick = { onIntent(OnboardingIntent.ShowApiImport) },
    modifier = Modifier.fillMaxWidth(),
    enabled = !state.busy && state.profile.apiWalletAddress == null,
    style = FlareButtonStyle.OUTLINE,
  )
  Spacer(Modifier.height(10.dp))
  FlareButton(
    text = "Explore anonymously",
    onClick = { onIntent(OnboardingIntent.ExploreAnonymously) },
    modifier = Modifier.fillMaxWidth(),
    enabled = !state.busy,
    style = FlareButtonStyle.OUTLINE,
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
private fun SecretImportStep(
  title: String,
  message: String,
  placeholder: String,
  value: String,
  busy: Boolean,
  onValue: (String) -> Unit,
  onSubmit: () -> Unit,
  onBack: () -> Unit,
) {
  Text(title, style = MaterialTheme.typography.headlineLarge)
  Text(
    message,
    modifier = Modifier.padding(top = 12.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
  )
  OutlinedTextField(
    value = value,
    onValueChange = onValue,
    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    placeholder = { Text(placeholder) },
    visualTransformation = PasswordVisualTransformation(),
    minLines = 3,
    enabled = !busy,
  )
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    FlareButton("Back", onBack, Modifier.weight(1f), !busy, FlareButtonStyle.OUTLINE)
    FlareButton("Import", onSubmit, Modifier.weight(1f), !busy && value.isNotBlank())
  }
}

@Composable
private fun BackupStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  Text("Back up the owner wallet", style = MaterialTheme.typography.headlineLarge)
  Text(
    "Write these words down in order. Flare shows a newly created phrase once and never stores it in preferences or the database.",
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
  Text("Set up API trading wallet", style = MaterialTheme.typography.headlineLarge)
  Text(
    "The API wallet is generated independently from the owner phrase. Decibel delegation is verified before an authenticated trading session is issued.",
    modifier = Modifier.padding(top = 12.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodyMedium,
  )
  Spacer(Modifier.height(28.dp))
  FlareButton(
    text = "Create API wallet",
    onClick = { onIntent(OnboardingIntent.CreateApiWallet) },
    modifier = Modifier.fillMaxWidth(),
    enabled = !state.busy && state.profile.apiWalletAddress == null,
  )
  Spacer(Modifier.height(10.dp))
  FlareButton(
    text = "Import AIP-80 key",
    onClick = { onIntent(OnboardingIntent.ShowApiImport) },
    modifier = Modifier.fillMaxWidth(),
    enabled = !state.busy && state.profile.apiWalletAddress == null,
    style = FlareButtonStyle.OUTLINE,
  )
  Spacer(Modifier.height(10.dp))
  FlareButton(
    text = "Continue without API wallet",
    onClick = { onIntent(OnboardingIntent.SkipApiWallet) },
    modifier = Modifier.fillMaxWidth(),
    enabled = !state.busy,
    style = FlareButtonStyle.OUTLINE,
  )
}

@Composable
private fun FundingStep(state: OnboardingUiState, onIntent: (OnboardingIntent) -> Unit) {
  Text("Fund Decibel collateral", style = MaterialTheme.typography.headlineLarge)
  Text(
    "Deposit Aptos USDC now, or continue and fund the subaccount later from Portfolio. Flare does not bridge assets or substitute another token.",
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
    text = "Continue without deposit",
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
    if (ownerMode) "Choose Decibel subaccount" else "Connect delegated subaccount",
    style = MaterialTheme.typography.headlineLarge,
  )
  Text(
    if (ownerMode) {
      "Account data and trading sessions are scoped to one subaccount. You can switch accounts later in Settings."
    } else {
      "Enter the Decibel subaccount that has delegated perpetual trading to this API wallet. Owner-only actions remain unavailable."
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
  } else {
    Text(
      "No active subaccount was found. Creating one submits an owner transaction through Kaptos.",
      modifier = Modifier.padding(top = 20.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  Spacer(Modifier.height(20.dp))
  if (ownerMode && state.subaccounts.isEmpty()) {
    FlareButton(
      text = "Create subaccount",
      onClick = { onIntent(OnboardingIntent.CreateSubaccount) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !state.busy,
    )
    Spacer(Modifier.height(10.dp))
    FlareButton(
      text = "Check again",
      onClick = { onIntent(OnboardingIntent.DiscoverSubaccounts) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !state.busy,
      style = FlareButtonStyle.OUTLINE,
    )
  }
  FlareButton(
    text = if (ownerMode) "Use this subaccount" else "Verify delegation",
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
