package xyz.mcxross.flare.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences

enum class OnboardingStep {
  WELCOME,
  IMPORT_OWNER,
  CONFIRM_BACKUP,
  SUBACCOUNT,
  FUNDING,
  API_WALLET,
  IMPORT_API,
}

data class OnboardingUiState(
  val step: OnboardingStep = OnboardingStep.WELCOME,
  val profile: WalletProfile = WalletProfile(),
  val input: String = "",
  val backupWords: List<String> = emptyList(),
  val confirmationIndices: List<Int> = emptyList(),
  val confirmations: Map<Int, String> = emptyMap(),
  val subaccounts: List<Subaccount> = emptyList(),
  val selectedSubaccount: String? = null,
  val fundingAmount: String = "",
  val fundingTransaction: TransactionState? = null,
  val busy: Boolean = false,
  val error: String? = null,
)

sealed interface OnboardingIntent {
  data object ExploreAnonymously : OnboardingIntent

  data object CreateOwner : OnboardingIntent

  data object ShowOwnerImport : OnboardingIntent

  data object ImportOwner : OnboardingIntent

  data object ConfirmBackup : OnboardingIntent

  data object DiscoverSubaccounts : OnboardingIntent

  data object CreateSubaccount : OnboardingIntent

  data class SelectSubaccount(val address: String) : OnboardingIntent

  data object ContinueSubaccount : OnboardingIntent

  data class ChangeFundingAmount(val value: String) : OnboardingIntent

  data object DepositUsdc : OnboardingIntent

  data object ConfirmFundingSelfPay : OnboardingIntent

  data object SkipFunding : OnboardingIntent

  data object CreateApiWallet : OnboardingIntent

  data object ShowApiSetup : OnboardingIntent

  data object ShowApiImport : OnboardingIntent

  data object ImportApiWallet : OnboardingIntent

  data object SkipApiWallet : OnboardingIntent

  data object ClearSensitiveState : OnboardingIntent

  data object Back : OnboardingIntent

  data class ChangeInput(val value: String) : OnboardingIntent

  data class ChangeConfirmation(val index: Int, val value: String) : OnboardingIntent
}

sealed interface OnboardingEffect {
  data object Completed : OnboardingEffect
}

class OnboardingViewModel(
  private val wallets: WalletRepository,
  private val sessions: SessionRepository,
  private val accounts: AccountRepository,
  private val preferences: AppPreferences,
) : ViewModel() {
  private val local = MutableStateFlow(OnboardingUiState())
  private val effectChannel = Channel<OnboardingEffect>(Channel.BUFFERED)
  private var actionJob: Job? = null
  val effects = effectChannel.receiveAsFlow()

  val uiState: StateFlow<OnboardingUiState> =
    combine(local, wallets.profile) { state, profile -> state.copy(profile = profile) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OnboardingUiState(),
      )

  fun onIntent(intent: OnboardingIntent) {
    when (intent) {
      OnboardingIntent.ExploreAnonymously -> completeAnonymously()
      OnboardingIntent.CreateOwner -> createOwner()
      OnboardingIntent.ShowOwnerImport -> show(OnboardingStep.IMPORT_OWNER)
      OnboardingIntent.ImportOwner -> importOwner()
      OnboardingIntent.ConfirmBackup -> confirmBackup()
      OnboardingIntent.DiscoverSubaccounts -> discoverSubaccounts()
      OnboardingIntent.CreateSubaccount -> createSubaccount()
      is OnboardingIntent.SelectSubaccount ->
        local.value = local.value.copy(selectedSubaccount = intent.address, input = intent.address)
      OnboardingIntent.ContinueSubaccount -> continueSubaccount()
      is OnboardingIntent.ChangeFundingAmount ->
        local.value =
          local.value.copy(
            fundingAmount = decimalCharacters(intent.value),
            fundingTransaction = null,
            error = null,
          )
      OnboardingIntent.DepositUsdc -> depositUsdc(FeePayment.SPONSORED)
      OnboardingIntent.ConfirmFundingSelfPay -> depositUsdc(FeePayment.SELF_PAY)
      OnboardingIntent.SkipFunding -> show(OnboardingStep.API_WALLET)
      OnboardingIntent.CreateApiWallet -> createApiWallet()
      OnboardingIntent.ShowApiSetup -> prepareOwnerAccount()
      OnboardingIntent.ShowApiImport -> show(OnboardingStep.IMPORT_API)
      OnboardingIntent.ImportApiWallet -> importApiWallet()
      OnboardingIntent.SkipApiWallet -> finishSetup()
      OnboardingIntent.ClearSensitiveState -> {
        actionJob?.cancel()
        actionJob = null
        clearSensitiveState()
      }
      OnboardingIntent.Back -> show(OnboardingStep.WELCOME)
      is OnboardingIntent.ChangeInput ->
        local.value = local.value.copy(input = intent.value, error = null)
      is OnboardingIntent.ChangeConfirmation ->
        local.value =
          local.value.copy(
            confirmations = local.value.confirmations + (intent.index to intent.value),
            error = null,
          )
    }
  }

  private fun createOwner() = launchAction {
    val backup =
      wallets.createOwner(
        VaultPrompt(
          title = "Protect owner wallet",
          subtitle = "Confirm your identity to encrypt the recovery phrase on this device.",
        )
      )
    showBackup(backup)
  }

  private fun importOwner() = launchAction {
    wallets.importOwner(
      phrase = local.value.input.trim(),
      prompt =
        VaultPrompt(
          title = "Import owner wallet",
          subtitle = "Confirm your identity to encrypt this recovery phrase.",
        ),
    )
    discoverSubaccountsInternal()
  }

  private fun confirmBackup() = launchAction {
    val state = local.value
    val matches =
      state.confirmationIndices.all { index ->
        state.confirmations[index]?.trim()?.lowercase() == state.backupWords[index].lowercase()
      }
    require(matches) { "The confirmation words do not match the recovery phrase" }
    wallets.confirmOwnerBackup()
    local.value =
      state.copy(
        step = OnboardingStep.SUBACCOUNT,
        backupWords = emptyList(),
        confirmationIndices = emptyList(),
        confirmations = emptyMap(),
      )
    discoverSubaccountsInternal()
  }

  private fun prepareOwnerAccount() = launchAction { discoverSubaccountsInternal() }

  private fun discoverSubaccounts() = launchAction { discoverSubaccountsInternal() }

  private suspend fun discoverSubaccountsInternal() {
    val subaccounts =
      accounts.discoverOwnerSubaccounts(
        VaultPrompt(
          title = "Find Decibel subaccounts",
          subtitle = "Confirm the owner wallet to load its Decibel accounts.",
        )
      )
    val selected = subaccounts.firstOrNull()?.address
    local.value =
      local.value.copy(
        step = OnboardingStep.SUBACCOUNT,
        input = selected.orEmpty(),
        subaccounts = subaccounts,
        selectedSubaccount = selected,
      )
  }

  private fun createSubaccount() = launchAction {
    val result =
      accounts.createSubaccount(
        VaultPrompt(
          title = "Create Decibel subaccount",
          subtitle = "Confirm the owner transaction before it is submitted.",
        )
      )
    require(result is TransactionState.Committed) { result.failureMessage() }
    discoverSubaccountsInternal()
  }

  private fun continueSubaccount() = launchAction {
    val address = local.value.selectedSubaccount ?: local.value.input.trim()
    require(address.isNotBlank()) { "Enter or select a Decibel subaccount" }
    if (wallets.profile.first().ownerAddress != null) {
      accounts.connectOwner(
        subaccount = address,
        prompt =
          VaultPrompt(
            title = "Connect owner account",
            subtitle = "Authorize live account data for this Decibel subaccount.",
          ),
      )
      local.value =
        local.value.copy(
          step = OnboardingStep.FUNDING,
          input = "",
          fundingAmount = "",
          fundingTransaction = null,
        )
    } else {
      accounts.connectApi(
        subaccount = address,
        prompt =
          VaultPrompt(
            title = "Connect delegated API wallet",
            subtitle = "Decibel will verify the active delegation for this subaccount.",
          ),
      )
      finishSetupInternal()
    }
  }

  private fun createApiWallet() = launchAction {
    wallets.createApiWallet(
      VaultPrompt(
        title = "Create API wallet",
        subtitle = "Confirm your identity to protect the independent trading key.",
      )
    )
    delegateApiAndFinish()
  }

  private fun depositUsdc(feePayment: FeePayment) = launchAction {
    val amount = local.value.fundingAmount
    require(amount.isNotBlank()) { "Enter an Aptos USDC amount" }
    local.value = local.value.copy(fundingTransaction = null)
    accounts
      .depositUsdc(
        amount = amount,
        prompt =
          VaultPrompt(
            title = "Deposit Aptos USDC",
            subtitle = "Confirm owner authorization before moving collateral into Decibel.",
          ),
        feePayment = feePayment,
      )
      .collect { transaction ->
        local.value = local.value.copy(fundingTransaction = transaction)
      }
    when (val terminal = local.value.fundingTransaction) {
      is TransactionState.Committed -> show(OnboardingStep.API_WALLET)
      is TransactionState.Failed -> {
        if (terminal.selfPayEstimateOctas == null) error(terminal.message)
      }
      else -> error("The deposit did not reach a final transaction state")
    }
  }

  private fun importApiWallet() = launchAction {
    wallets.importApiWallet(
      aip80 = local.value.input.trim(),
      prompt =
        VaultPrompt(
          title = "Import API wallet",
          subtitle = "Confirm your identity to protect this AIP-80 trading key.",
        ),
    )
    if (wallets.profile.first().ownerAddress == null) {
      local.value = local.value.copy(step = OnboardingStep.SUBACCOUNT, input = "")
    } else {
      delegateApiAndFinish()
    }
  }

  private fun finishSetup() = launchAction { finishSetupInternal() }

  private fun completeAnonymously() = launchAction { finishSetupInternal() }

  private suspend fun finishSetupInternal() {
    if (sessions.status.value == null) sessions.useAnonymous()
    preferences.setOnboardingComplete(true)
    clearSensitiveState()
    effectChannel.send(OnboardingEffect.Completed)
  }

  private suspend fun delegateApiAndFinish() {
    val result =
      accounts.delegateApiWallet(
        VaultPrompt(
          title = "Delegate API trading wallet",
          subtitle = "Confirm the owner transaction that grants Decibel trading access.",
        )
      )
    require(result is TransactionState.Committed) { result.failureMessage() }
    val subaccount =
      accounts.snapshot.value.account ?: error("The selected Decibel subaccount is unavailable")
    verifyDelegationWithBackoff(subaccount)
    finishSetupInternal()
  }

  private suspend fun verifyDelegationWithBackoff(subaccount: String) {
    var lastError: Throwable? = null
    repeat(DELEGATION_VERIFICATION_ATTEMPTS) { attempt ->
      try {
        accounts.connectApi(
          subaccount = subaccount,
          prompt =
            VaultPrompt(
              title = "Verify API wallet delegation",
              subtitle = "Authorize the API key so Decibel can verify the new delegation.",
            ),
        )
        return
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        lastError = error
        if (attempt < DELEGATION_VERIFICATION_ATTEMPTS - 1) {
          delay((1L shl attempt.coerceAtMost(2)) * 1_000L)
        }
      }
    }
    throw IllegalStateException(
      "The delegation committed, but Decibel has not indexed it yet. Try connecting the API wallet again.",
      lastError,
    )
  }

  private fun showBackup(backup: OwnerBackup) {
    local.value =
      local.value.copy(
        step = OnboardingStep.CONFIRM_BACKUP,
        input = "",
        backupWords = backup.words,
        confirmationIndices = (backup.words.indices).shuffled(Random.Default).take(3).sorted(),
        confirmations = emptyMap(),
      )
  }

  private fun show(step: OnboardingStep) {
    clearSensitiveState(step)
  }

  private fun clearSensitiveState(step: OnboardingStep = OnboardingStep.WELCOME) {
    local.value =
      OnboardingUiState(
        step = step,
        profile = local.value.profile,
        busy = local.value.busy,
      )
  }

  private fun launchAction(block: suspend () -> Unit) {
    if (local.value.busy) return
    actionJob = viewModelScope.launch {
      local.value = local.value.copy(busy = true, error = null)
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        local.value =
          local.value.copy(error = error.message ?: "The wallet operation could not be completed")
      } finally {
        local.value = local.value.copy(busy = false)
      }
    }
  }

  private companion object {
    const val DELEGATION_VERIFICATION_ATTEMPTS = 5
  }
}

private fun TransactionState.failureMessage(): String =
  when (this) {
    is TransactionState.Failed -> message
    TransactionState.Simulating -> "Transaction simulation did not complete"
    TransactionState.AwaitingAuthorization -> "Transaction authorization did not complete"
    TransactionState.Submitting -> "Transaction submission did not complete"
    is TransactionState.Pending -> "Transaction is still pending: $hash"
    is TransactionState.Committed -> ""
  }

private fun decimalCharacters(value: String): String =
  value
    .filter { it.isDigit() || it == '.' }
    .let { filtered ->
      val firstDot = filtered.indexOf('.')
      if (firstDot < 0) filtered
      else filtered.take(firstDot + 1) + filtered.drop(firstDot + 1).replace(".", "")
    }
