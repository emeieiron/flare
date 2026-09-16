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
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.LEGACY_PROFILE_ID

enum class OnboardingStep {
  WELCOME,
  IMPORT,
  SHOW_BACKUP,
  CONFIRM_BACKUP,
  SUBACCOUNT,
  ENABLE_TRADING,
}

/** The two on-chain steps of setup, so a fee fallback retries the step that failed. */
enum class SetupOperation {
  CREATE_ACCOUNT,
  ENABLE_TRADING,
}

data class OnboardingUiState(
  val step: OnboardingStep = OnboardingStep.WELCOME,
  val profile: WalletProfile = WalletProfile(),
  val profiles: List<AccountProfile> = emptyList(),
  val activeProfileId: String = LEGACY_PROFILE_ID,
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
  val setupOperation: SetupOperation? = null,
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

  data object EnableTrading : OnboardingIntent

  data object ContinueSetup : OnboardingIntent

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
  private val setupPrompt = VaultPrompt("Continue setup", "Confirm your identity")
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
        launchAction("That account couldn’t be opened.") {
          clearSensitiveState()
          preferences.activateProfile(intent.id)
          if (preferences.values.first().onboardingComplete)
            effectChannel.send(OnboardingEffect.Completed)
        }
      is OnboardingIntent.ChangeTradingAccount ->
        local.value = local.value.copy(tradingAccountInput = intent.value, error = null)
      OnboardingIntent.ConfirmSetupSelfPay ->
        when (local.value.setupOperation) {
          SetupOperation.CREATE_ACCOUNT -> createSubaccount(FeePayment.SELF_PAY)
          SetupOperation.ENABLE_TRADING,
          null -> enableTrading(FeePayment.SELF_PAY)
        }
      OnboardingIntent.CreateOwner -> createOwner()
      OnboardingIntent.ShowImport -> show(OnboardingStep.IMPORT)
      OnboardingIntent.ImportCredential ->
        if (local.value.apiImport) importTradingKey() else importOwner()
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
      OnboardingIntent.EnableTrading -> enableTrading()
      OnboardingIntent.ContinueSetup -> prepareOwnerAccount()
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

  private fun createOwner() =
    launchAction("Your account wasn’t created.") {
      val backup =
        wallets.createOwner(
          VaultPrompt(title = "Create account", subtitle = "Confirm your identity")
        )
      showBackup(backup)
    }

  private fun importOwner() =
    launchAction("That recovery phrase wasn’t imported.") {
      wallets.importOwner(
        phrase = local.value.input.trim(),
        prompt = VaultPrompt(title = "Import account", subtitle = "Confirm your identity"),
      )
      local.value = local.value.copy(input = "", step = OnboardingStep.SUBACCOUNT)
      discoverSubaccountsInternal()
    }

  private fun confirmBackup() =
    launchAction("Your backup wasn’t confirmed.") {
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

  private fun prepareOwnerAccount() =
    launchAction("Setup couldn’t continue.") {
      val profile = wallets.profile.first()
      when {
        profile.apiOnly -> show(OnboardingStep.SUBACCOUNT)
        profile.ownerAddress != null && !profile.ownerBackupConfirmed -> {
          val phrase =
            wallets.exportOwnerMnemonic(
              VaultPrompt("Back up your account", "Confirm your identity")
            )
          showBackup(OwnerBackup(profile.ownerAddress, phrase.split(' ')))
        }
        else -> discoverSubaccountsInternal()
      }
    }

  private fun discoverSubaccounts() =
    launchAction("Flare couldn’t check for your accounts.") { discoverSubaccountsInternal() }

  private suspend fun discoverSubaccountsInternal() {
    val subaccounts =
      accounts.discoverOwnerSubaccounts(
        VaultPrompt(title = "Find your accounts", subtitle = "Confirm your identity")
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
    if (subaccounts.size != 1 || selected == null) return
    accounts.selectTradingAccount(selected, setupPrompt)
    local.value = local.value.copy(step = OnboardingStep.ENABLE_TRADING)
    // A device that can already trade for this account needs no further transaction. A failure
    // here leaves the review step in place, so a retry reuses the same key.
    if (wallets.profile.first().apiWalletAddress != null) {
      runSuspendCatching { delegateApiAndFinish() }
    }
  }

  private fun createSubaccount(feePayment: FeePayment = FeePayment.SPONSORED) =
    launchAction("Your trading account wasn’t created.") {
      local.value =
        local.value.copy(setupOperation = SetupOperation.CREATE_ACCOUNT, setupTransaction = null)
      check(local.value.subaccountsLoaded && local.value.subaccounts.isEmpty()) {
        "Check for existing accounts before creating one."
      }
      val result =
        accounts.createSubaccount(
          VaultPrompt(title = "Create trading account", subtitle = "Confirm your identity"),
          feePayment = feePayment,
        )
      local.value = local.value.copy(setupTransaction = result)
      if (result !is TransactionState.Committed) {
        if ((result as? TransactionState.Failed)?.selfPayEstimateOctas == null)
          error((result as? TransactionState.Failed)?.message.orEmpty())
        return@launchAction
      }
      // Creating the account and enabling trading are one reviewed operation.
      discoverSubaccountsInternal()
      if (local.value.step == OnboardingStep.ENABLE_TRADING) delegateApiAndFinish()
    }

  private fun continueSubaccount() =
    launchAction("That account couldn’t be opened.") {
      val address = local.value.selectedSubaccount ?: local.value.input.trim()
      require(address.isNotBlank()) { "Select a trading account" }
      accounts.selectTradingAccount(address, setupPrompt)
      if (wallets.profile.first().ownerAddress != null) {
        local.value = local.value.copy(step = OnboardingStep.ENABLE_TRADING, input = "")
      } else {
        finishSetupInternal()
      }
    }

  private fun enableTrading(feePayment: FeePayment = FeePayment.SPONSORED) =
    launchAction("Trading wasn’t enabled on this device.") { delegateApiAndFinish(feePayment) }

  private fun importTradingKey() =
    launchAction("That trading key wasn’t imported.") {
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
    local.value =
      local.value.copy(setupOperation = SetupOperation.ENABLE_TRADING, setupTransaction = null)
    accounts.prepareTradingWallet(
      VaultPrompt(title = "Enable trading", subtitle = "Confirm your identity"),
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

  /** [outcome] states what did not happen, so a failure reads as a result instead of a log line. */
  private fun launchAction(outcome: String, block: suspend () -> Unit) {
    if (local.value.busy) return
    actionJob = viewModelScope.launch {
      local.value = local.value.copy(busy = true, error = null)
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: SetupTransactionException) {
        local.value =
          local.value.copy(
            setupTransaction = error.transaction,
            error =
              actionFailure(
                (error.transaction as? TransactionState.Failed)?.message,
                "Trading wasn’t enabled on this device.",
              ),
          )
      } catch (error: Throwable) {
        local.value = local.value.copy(error = actionFailure(error.message, outcome))
      } finally {
        local.value = local.value.copy(busy = false)
      }
    }
  }
}
