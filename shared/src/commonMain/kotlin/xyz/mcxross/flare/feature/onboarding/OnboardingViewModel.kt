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
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.data.TradingRepository
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

/**
 * What happens to a setup step's network fee after Flare couldn't cover it. Nothing is charged to the
 * wallet unless the person agrees to it here.
 */
sealed interface SetupFee {
  /** About what the fee itself comes to. */
  val estimateOctas: ULong

  /** The wallet can pay the fee, from its balance of [balanceOctas]. */
  data class WalletCanPay(override val estimateOctas: ULong, val balanceOctas: ULong) : SetupFee

  /**
   * The wallet can't pay yet: the network sets aside [reserveOctas] before running the transaction,
   * and [address] holds only [balanceOctas].
   */
  data class WalletNeedsFunds(
    override val estimateOctas: ULong,
    val reserveOctas: ULong,
    val balanceOctas: ULong,
    val address: String,
  ) : SetupFee

  /** The wallet's balance couldn't be checked, so the step can only be tried again. */
  data class Unchecked(override val estimateOctas: ULong) : SetupFee
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
  /** The phrase stays covered until the person asks to see it. */
  val backupRevealed: Boolean = false,
  val confirmationIndices: List<Int> = emptyList(),
  /** For each position to confirm, the right word and two others from the phrase, shuffled. */
  val confirmationOptions: Map<Int, List<String>> = emptyMap(),
  val confirmations: Map<Int, String> = emptyMap(),
  val subaccounts: List<Subaccount> = emptyList(),
  val subaccountsLoaded: Boolean = false,
  val selectedSubaccount: String? = null,
  val setupTransaction: TransactionState? = null,
  val setupOperation: SetupOperation? = null,
  /** Set when Flare couldn't cover the current step's network fee. */
  val setupFee: SetupFee? = null,
  val builderOptIn: Boolean = true,
  val busy: Boolean = false,
  val error: String? = null,
)

sealed interface OnboardingIntent {
  data class SetBuilderOptIn(val enabled: Boolean) : OnboardingIntent

  data class ChangeTradingAccount(val value: String) : OnboardingIntent

  data class SelectProfile(val id: String) : OnboardingIntent

  data object ConfirmSetupSelfPay : OnboardingIntent

  /** Runs the step whose fee couldn't be covered again, asking Flare to cover it once more. */
  data object RetrySetup : OnboardingIntent

  data object CreateOwner : OnboardingIntent

  data object ShowImport : OnboardingIntent

  data object ImportCredential : OnboardingIntent

  data class SetApiImport(val enabled: Boolean) : OnboardingIntent

  data object RevealBackup : OnboardingIntent

  data object ReviewBackup : OnboardingIntent

  data object ConfirmBackup : OnboardingIntent

  data object DiscoverSubaccounts : OnboardingIntent

  data object CreateSubaccount : OnboardingIntent

  data class SelectSubaccount(val address: String) : OnboardingIntent

  data object ContinueSubaccount : OnboardingIntent

  data object EnableTrading : OnboardingIntent

  data object ContinueSetup : OnboardingIntent

  data class InitializeMode(val mode: String, val profileId: String? = null) : OnboardingIntent

  data object ClearSensitiveState : OnboardingIntent

  data object Back : OnboardingIntent

  data class ChangeInput(val value: String) : OnboardingIntent

  data class ChangeConfirmation(val index: Int, val value: String) : OnboardingIntent
}

sealed interface OnboardingEffect {
  data object Completed : OnboardingEffect
  data object Cancelled : OnboardingEffect
}

class OnboardingViewModel(
  private val wallets: WalletRepository,
  private val accounts: AccountRepository,
  private val preferences: AppPreferences,
  private val runtime: FlareRuntimeConfig,
  private val trading: TradingRepository,
) : ViewModel() {
  private val local = MutableStateFlow(OnboardingUiState())
  private val effectChannel = Channel<OnboardingEffect>(Channel.BUFFERED)
  private var actionJob: Job? = null
  private var subflowMode: String? = null
  private var previousProfileId: String? = null
  private var createdProfileId: String? = null
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
      is OnboardingIntent.InitializeMode -> {
        if (subflowMode == null) {
          subflowMode = intent.mode
          viewModelScope.launch {
            previousProfileId = preferences.values.first().activeProfileId
            when (intent.mode.uppercase()) {
              "CREATE" -> createOwner()
              "IMPORT" -> show(OnboardingStep.IMPORT)
              "CONTINUE" -> {
                intent.profileId?.let { preferences.activateProfile(it) }
                prepareOwnerAccount()
              }
            }
          }
        }
      }
      is OnboardingIntent.SetBuilderOptIn ->
        local.value = local.value.copy(builderOptIn = intent.enabled, error = null)
      is OnboardingIntent.SelectProfile ->
        launchAction("That account couldn’t be opened.") {
          clearSensitiveState()
          preferences.activateProfile(intent.id)
          if (preferences.values.first().onboardingComplete)
            effectChannel.send(OnboardingEffect.Completed)
        }
      is OnboardingIntent.ChangeTradingAccount ->
        local.value = local.value.copy(tradingAccountInput = intent.value, error = null)
      // The wallet pays only after the person has seen that it can, and agreed.
      OnboardingIntent.ConfirmSetupSelfPay ->
        if (local.value.setupFee is SetupFee.WalletCanPay) {
          when (local.value.setupOperation) {
            SetupOperation.CREATE_ACCOUNT -> createSubaccount(FeePayment.SELF_PAY)
            SetupOperation.ENABLE_TRADING,
            null -> enableTrading(FeePayment.SELF_PAY)
          }
        }
      OnboardingIntent.RetrySetup ->
        when (local.value.setupOperation) {
          SetupOperation.CREATE_ACCOUNT -> createSubaccount()
          SetupOperation.ENABLE_TRADING,
          null -> enableTrading()
        }
      OnboardingIntent.CreateOwner -> createOwner()
      OnboardingIntent.ShowImport -> show(OnboardingStep.IMPORT)
      OnboardingIntent.ImportCredential ->
        if (local.value.apiImport) importTradingKey() else importOwner()
      is OnboardingIntent.SetApiImport ->
        local.value = local.value.copy(apiImport = intent.enabled, error = null)
      OnboardingIntent.RevealBackup -> revealBackup()
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
        clearSecrets()
      }
      OnboardingIntent.Back -> {
        if (
          local.value.step == OnboardingStep.ENABLE_TRADING && local.value.subaccounts.size > 1
        ) {
          local.value = local.value.copy(step = OnboardingStep.SUBACCOUNT, error = null)
        } else if (local.value.step == OnboardingStep.CONFIRM_BACKUP) {
          local.value =
            local.value.copy(
              step = OnboardingStep.SHOW_BACKUP,
              confirmations = emptyMap(),
              error = null,
            )
        } else if (subflowMode != null) {
          val toClean = createdProfileId
          viewModelScope.launch {
            if (toClean != null) {
              val current = preferences.values.first()
              val profile = current.profiles.firstOrNull { it.id == toClean }
              if (profile?.onboardingComplete != true) {
                preferences.removeProfile(toClean)
              }
            }
            previousProfileId?.let { preferences.activateProfile(it) }
            effectChannel.send(OnboardingEffect.Cancelled)
          }
        } else {
          // Stepping back to the start of a first setup leaves nothing half made behind.
          val toClean = createdProfileId
          createdProfileId = null
          viewModelScope.launch {
            if (toClean != null) {
              val profile = preferences.values.first().profiles.firstOrNull { it.id == toClean }
              if (profile?.onboardingComplete != true) preferences.removeProfile(toClean)
            }
            show(OnboardingStep.WELCOME)
          }
        }
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
      createdProfileId = "owner_${backup.address}"
      showBackup(backup)
    }

  private fun importOwner() =
    launchAction("That recovery phrase wasn’t imported.") {
      val address =
        wallets.importOwner(
          phrase = local.value.input.trim(),
          prompt = VaultPrompt(title = "Import account", subtitle = "Confirm your identity"),
        )
      createdProfileId = "owner_$address"
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
        // The phrase is fetched only when the person reveals it.
        profile.ownerAddress != null && !profile.ownerBackupConfirmed ->
          local.value = local.value.copy(step = OnboardingStep.SHOW_BACKUP, backupRevealed = false)
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
        local.value.copy(
          setupOperation = SetupOperation.CREATE_ACCOUNT,
          setupTransaction = null,
          setupFee = null,
        )
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
        val failed = result as? TransactionState.Failed
        if (failed?.selfPayEstimateOctas == null) error(failed?.message.orEmpty())
        local.value = local.value.copy(setupFee = assessFee(failed))
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
      preferences.setBuilderSupport(
        builderAddress = runtime.defaultBuilderAddress.takeIf(String::isNotBlank),
        builderFeeBps = 0,
        builderApproved = false,
      )
      finishSetupInternal()
    }

  private suspend fun finishSetupInternal() {
    createdProfileId = null
    preferences.setOnboardingComplete(true)
    clearSensitiveState()
    effectChannel.send(OnboardingEffect.Completed)
  }

  private suspend fun delegateApiAndFinish(feePayment: FeePayment = FeePayment.SPONSORED) {
    local.value =
      local.value.copy(
        setupOperation = SetupOperation.ENABLE_TRADING,
        setupTransaction = null,
        setupFee = null,
      )
    accounts.prepareTradingWallet(
      VaultPrompt(title = "Enable trading", subtitle = "Confirm your identity"),
      feePayment = feePayment,
    )
    if (local.value.builderOptIn && runtime.defaultBuilderAddress.isNotBlank()) {
      runSuspendCatching {
        // Always sponsored: agreeing to pay for enabling trading isn't agreeing to pay for this too.
        accounts.approveBuilderFee(
          builderAddress = runtime.defaultBuilderAddress,
          feeBps = 10u,
          prompt = VaultPrompt(title = "Approve builder support", subtitle = "Confirm your identity"),
          feePayment = FeePayment.SPONSORED,
        )
      }.onSuccess {
        preferences.setBuilderFeeBps(runtime.defaultBuilderFeeBps.toInt())
      }.onFailure {
        preferences.setBuilderSupport(
          builderAddress = runtime.defaultBuilderAddress,
          builderFeeBps = 0,
          builderApproved = false,
        )
      }
    } else {
      preferences.setBuilderSupport(
        builderAddress = runtime.defaultBuilderAddress.takeIf(String::isNotBlank),
        builderFeeBps = 0,
        builderApproved = false,
      )
    }
    finishSetupInternal()
  }

  /**
   * Uncovers the recovery phrase. Once setup has been away from the screen the words are no longer
   * held, so they're read back from the vault, which asks the person to confirm it's them.
   */
  private fun revealBackup() {
    if (local.value.backupWords.isNotEmpty()) {
      local.value = local.value.copy(backupRevealed = true)
      return
    }
    launchAction("Your recovery phrase couldn’t be shown.") {
      val owner = checkNotNull(wallets.profile.first().ownerAddress) { "No account to back up" }
      val phrase = wallets.exportOwnerMnemonic(VaultPrompt("Show recovery phrase", "Confirm your identity"))
      showBackup(OwnerBackup(owner, phrase.split(' ')), revealed = true)
    }
  }

  private fun showBackup(backup: OwnerBackup, revealed: Boolean = false) {
    val indices = backup.words.indices.shuffled(Random.Default).take(3).sorted()
    local.value =
      local.value.copy(
        step = OnboardingStep.SHOW_BACKUP,
        input = "",
        backupWords = backup.words,
        backupRevealed = revealed,
        confirmationIndices = indices,
        confirmationOptions = indices.associateWith { choicesFor(it, backup.words) },
        confirmations = emptyMap(),
      )
  }

  /** The word at [index] and two other words from the same phrase, in random order. */
  private fun choicesFor(index: Int, words: List<String>): List<String> {
    val word = words[index]
    val others = words.filterIndexed { i, other -> i != index && other != word }.distinct()
    return (others.shuffled(Random.Default).take(2) + word).shuffled(Random.Default)
  }

  private fun show(step: OnboardingStep) {
    clearSensitiveState(step)
  }

  /**
   * Drops anything secret when setup leaves the screen, but keeps the person's place: an import keeps
   * its step without the pasted key, and a backup is covered again, to be read back when revealed.
   */
  private fun clearSecrets() {
    val state = local.value
    local.value =
      when (state.step) {
        OnboardingStep.IMPORT -> state.copy(input = "", error = null)
        OnboardingStep.SHOW_BACKUP,
        OnboardingStep.CONFIRM_BACKUP ->
          state.copy(
            step = OnboardingStep.SHOW_BACKUP,
            backupWords = emptyList(),
            backupRevealed = false,
            confirmationIndices = emptyList(),
            confirmationOptions = emptyMap(),
            confirmations = emptyMap(),
            error = null,
          )
        else -> state
      }
  }

  private fun clearSensitiveState(step: OnboardingStep = OnboardingStep.WELCOME) {
    local.value =
      OnboardingUiState(
        step = step,
        profile = local.value.profile,
        busy = local.value.busy,
        builderOptIn = local.value.builderOptIn,
      )
  }

  /**
   * Whether the wallet can pay a fee Flare couldn't cover. The network sets the whole gas limit aside
   * before running a transaction, so that reserve, not the smaller fee, is what the balance must meet.
   */
  private suspend fun assessFee(failed: TransactionState.Failed): SetupFee {
    val estimate = checkNotNull(failed.selfPayEstimateOctas)
    val reserve = maxOf(failed.selfPayReserveOctas ?: estimate, estimate)
    val owner = wallets.profile.first().ownerAddress
    val balance = runSuspendCatching { trading.ownerAptBalance() }.getOrNull()
    return when {
      owner == null || balance == null -> SetupFee.Unchecked(estimate)
      balance >= reserve -> SetupFee.WalletCanPay(estimate, balance)
      else -> SetupFee.WalletNeedsFunds(estimate, reserve, balance, owner)
    }
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
        val failed = error.transaction as? TransactionState.Failed
        local.value =
          if (failed?.selfPayEstimateOctas != null) {
            local.value.copy(setupTransaction = failed, setupFee = assessFee(failed))
          } else {
            local.value.copy(
              setupTransaction = error.transaction,
              error = actionFailure(failed?.message, "Trading wasn’t enabled on this device."),
            )
          }
      } catch (error: Throwable) {
        local.value = local.value.copy(error = actionFailure(error.message, outcome))
      } finally {
        local.value = local.value.copy(busy = false)
      }
    }
  }
}
