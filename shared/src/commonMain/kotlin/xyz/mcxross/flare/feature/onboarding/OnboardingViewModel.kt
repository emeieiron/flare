package xyz.mcxross.flare.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences

enum class OnboardingStep {
  WELCOME,
  IMPORT,
  SHOW_BACKUP,
  CONFIRM_BACKUP,
  SUBACCOUNT,
  API_WALLET,
}

data class OnboardingUiState(
  val step: OnboardingStep = OnboardingStep.WELCOME,
  val profile: WalletProfile = WalletProfile(),
  val profiles: List<xyz.mcxross.flare.store.AccountProfile> = emptyList(),
  val activeProfileId: String = "legacy",
  val input: String = "",
  val apiImport: Boolean = false,
  val tradingAccountInput: String = "",
  val backupWords: List<String> = emptyList(),
  val confirmationIndices: List<Int> = emptyList(),
  val confirmations: Map<Int, String> = emptyMap(),
  val subaccounts: List<Subaccount> = emptyList(),
  val subaccountsLoaded: Boolean = false,
  val selectedSubaccount: String? = null,
  val setupTransaction: TransactionState? = null,
  val setupOperation: String? = null,
  val busy: Boolean = false,
  val error: String? = null,
)

sealed interface OnboardingIntent {
  data class ChangeTradingAccount(val value: String) : OnboardingIntent

  data class SelectProfile(val id: String) : OnboardingIntent

  data object ConfirmSetupSelfPay : OnboardingIntent

  data object CreateOwner : OnboardingIntent

  data object ShowImport : OnboardingIntent

  data object ImportCredential : OnboardingIntent

  data class SetApiImport(val enabled: Boolean) : OnboardingIntent

  data object ReviewBackup : OnboardingIntent

  data object ConfirmBackup : OnboardingIntent

  data object DiscoverSubaccounts : OnboardingIntent

  data object CreateSubaccount : OnboardingIntent

  data class SelectSubaccount(val address: String) : OnboardingIntent

  data object ContinueSubaccount : OnboardingIntent

  data object CreateApiWallet : OnboardingIntent

  data object ShowApiSetup : OnboardingIntent

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
  private val accounts: AccountRepository,
  private val preferences: AppPreferences,
) : ViewModel() {
  private val local = MutableStateFlow(OnboardingUiState())
  private val effectChannel = Channel<OnboardingEffect>(Channel.BUFFERED)
  private var actionJob: Job? = null
  val effects = effectChannel.receiveAsFlow()

  val uiState: StateFlow<OnboardingUiState> =
    combine(local, wallets.profile, preferences.values) { state, profile, saved ->
        state.copy(
          profile = profile,
          profiles = saved.profiles,
          activeProfileId = saved.activeProfileId,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OnboardingUiState(),
      )

  fun onIntent(intent: OnboardingIntent) {
    when (intent) {
      is OnboardingIntent.SelectProfile ->
        launchAction {
          clearSensitiveState()
          preferences.activateProfile(intent.id)
          if (preferences.values.first().onboardingComplete)
            effectChannel.send(OnboardingEffect.Completed)
        }
      is OnboardingIntent.ChangeTradingAccount ->
        local.value = local.value.copy(tradingAccountInput = intent.value, error = null)
      OnboardingIntent.ConfirmSetupSelfPay -> {
        if (local.value.setupOperation == "create") createSubaccount(FeePayment.SELF_PAY)
        else createApiWallet(FeePayment.SELF_PAY)
      }
      OnboardingIntent.CreateOwner -> createOwner()
      OnboardingIntent.ShowImport -> show(OnboardingStep.IMPORT)
      OnboardingIntent.ImportCredential ->
        if (local.value.apiImport) importApiWallet() else importOwner()
      is OnboardingIntent.SetApiImport ->
        local.value = local.value.copy(apiImport = intent.enabled, error = null)
      OnboardingIntent.ReviewBackup ->
        local.value = local.value.copy(step = OnboardingStep.CONFIRM_BACKUP)
      OnboardingIntent.ConfirmBackup -> confirmBackup()
      OnboardingIntent.DiscoverSubaccounts -> discoverSubaccounts()
      OnboardingIntent.CreateSubaccount -> createSubaccount()
      is OnboardingIntent.SelectSubaccount ->
        local.value = local.value.copy(selectedSubaccount = intent.address, input = intent.address)
      OnboardingIntent.ContinueSubaccount -> continueSubaccount()
      OnboardingIntent.CreateApiWallet -> createApiWallet()
      OnboardingIntent.ShowApiSetup -> prepareOwnerAccount()
      OnboardingIntent.ClearSensitiveState -> {
        actionJob?.cancel()
        actionJob = null
        clearSensitiveState()
      }
      OnboardingIntent.Back -> {
        if (local.value.step == OnboardingStep.CONFIRM_BACKUP) {
          local.value =
            local.value.copy(
              step = OnboardingStep.SHOW_BACKUP,
              confirmations = emptyMap(),
              error = null,
            )
        } else show(OnboardingStep.WELCOME)
      }
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
      wallets.createOwner(VaultPrompt(title = "Create account", subtitle = "Confirm your identity"))
    showBackup(backup)
  }

  private fun importOwner() = launchAction {
    wallets.importOwner(
      phrase = local.value.input.trim(),
      prompt =
        VaultPrompt(
          title = "Import account",
          subtitle = "Confirm your identity to protect your account on this device.",
        ),
    )
    local.value = local.value.copy(input = "", step = OnboardingStep.SUBACCOUNT)
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

  private fun prepareOwnerAccount() = launchAction {
    val profile = wallets.profile.first()
    when {
      profile.apiOnly -> show(OnboardingStep.SUBACCOUNT)
      profile.ownerAddress != null && !profile.ownerBackupConfirmed -> {
        val phrase =
          wallets.exportOwnerMnemonic(
            VaultPrompt("Back up your account", "Authorize access to your recovery phrase.")
          )
        showBackup(OwnerBackup(profile.ownerAddress, phrase.split(' ')))
      }
      else -> discoverSubaccountsInternal()
    }
  }

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
        subaccountsLoaded = true,
        selectedSubaccount = selected,
      )
    if (subaccounts.size == 1) {
      accounts.connectOwner(selected!!, VaultPrompt("Continue setup", "Confirm your identity"))
      local.value = local.value.copy(step = OnboardingStep.API_WALLET)
      if (wallets.profile.first().apiWalletAddress != null) {
        // Verification failures leave the profile and key intact for a retry.
        try {
          accounts.connectApi(selected, VaultPrompt("Continue setup", "Confirm your identity"))
          finishSetupInternal()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          /* Keep the setup review available. */
        }
      }
    }
  }

  private fun createSubaccount(feePayment: FeePayment = FeePayment.SPONSORED) = launchAction {
    local.value = local.value.copy(setupOperation = "create", setupTransaction = null)
    check(local.value.subaccountsLoaded && local.value.subaccounts.isEmpty()) {
      "Check for existing Decibel accounts before creating one."
    }
    val result =
      accounts.createSubaccount(
        VaultPrompt(title = "Create Decibel subaccount", subtitle = "Confirm account creation"),
        feePayment = feePayment,
      )
    local.value = local.value.copy(setupTransaction = result)
    if (result !is TransactionState.Committed) {
      if ((result as? TransactionState.Failed)?.selfPayEstimateOctas == null)
        error("Account creation failed")
      return@launchAction
    }
    discoverSubaccountsInternal()
    if (local.value.selectedSubaccount != null) {
      local.value = local.value.copy(setupOperation = "delegate")
      delegateApiAndFinish()
    }
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
      local.value = local.value.copy(step = OnboardingStep.API_WALLET, input = "")
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

  private fun createApiWallet(feePayment: FeePayment = FeePayment.SPONSORED) = launchAction {
    local.value = local.value.copy(setupOperation = "delegate", setupTransaction = null)
    delegateApiAndFinish(feePayment)
  }

  private fun importApiWallet() = launchAction {
    accounts.importTradingKey(
      local.value.input.trim(),
      local.value.tradingAccountInput.trim(),
      VaultPrompt("Import trading account", "Confirm your identity"),
    )
    finishSetupInternal()
  }

  private suspend fun finishSetupInternal() {
    preferences.setOnboardingComplete(true)
    clearSensitiveState()
    effectChannel.send(OnboardingEffect.Completed)
  }

  private suspend fun delegateApiAndFinish(feePayment: FeePayment = FeePayment.SPONSORED) {
    accounts.prepareTradingWallet(
      VaultPrompt(title = "Enable trading", subtitle = "Allow trading on this device"),
      feePayment = feePayment,
    )
    finishSetupInternal()
  }

  private fun showBackup(backup: OwnerBackup) {
    local.value =
      local.value.copy(
        step = OnboardingStep.SHOW_BACKUP,
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
      OnboardingUiState(step = step, profile = local.value.profile, busy = local.value.busy)
  }

  private fun launchAction(block: suspend () -> Unit) {
    if (local.value.busy) return
    actionJob =
      viewModelScope.launch {
        local.value = local.value.copy(busy = true, error = null)
        try {
          block()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: SetupTransactionException) {
          local.value =
            local.value.copy(setupTransaction = error.transaction, error = "Delegation failed")
        } catch (error: Throwable) {
          local.value =
            local.value.copy(error = error.message ?: "The wallet operation could not be completed")
        } finally {
          local.value = local.value.copy(busy = false)
        }
      }
  }
}
