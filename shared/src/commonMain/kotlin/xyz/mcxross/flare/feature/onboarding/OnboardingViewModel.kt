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
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.LEGACY_PROFILE_ID
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingAccountStatus

enum class OnboardingStep {
  WELCOME,
  IMPORT,
  SHOW_BACKUP,
  CONFIRM_BACKUP,
  SUBACCOUNT,
  /** A wallet Flare created opens its trading account and enables trading in one go. */
  OPEN_ACCOUNT,
  /** The account needs its first deposit before the venue lets it enable trading. */
  FUND_ACCOUNT,
  ENABLE_TRADING,
}

/**
 * What happens to a setup step's network fee after Flare couldn't cover it. Nothing is charged to the
 * wallet unless the person agrees to it here.
 */
sealed interface SetupFee {
  /** About what the fee itself comes to, in the fee token's smallest unit, as are the others. */
  val estimate: ULong

  /** The wallet can pay the fee, from its [balance]. */
  data class WalletCanPay(override val estimate: ULong, val balance: ULong) : SetupFee

  /**
   * The wallet can't pay yet: the network sets aside [reserve] before running the transaction, and
   * [address] holds only [balance].
   */
  data class WalletNeedsFunds(
    override val estimate: ULong,
    val reserve: ULong,
    val balance: ULong,
    val address: String,
  ) : SetupFee

  /** The wallet's balance couldn't be checked, so the step can only be tried again. */
  data class Unchecked(override val estimate: ULong) : SetupFee

  /**
   * A wallet Flare just created holds no fee token, so only Flare can pay; the step waits, saved,
   * until Flare can cover it.
   */
  data class AwaitingSponsor(override val estimate: ULong) : SetupFee
}

/** What the chain says about the trading account entered with a trading key. */
enum class TradingAccountCheck {
  UNKNOWN,
  TRADING_ACCOUNT,
  CLOSED,
  /** The key's own address, not the account it trades for. */
  KEY_ADDRESS,
  /** An address that isn't a trading account, most likely the owner's wallet. */
  WALLET,
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
  val tradingAccountCheck: TradingAccountCheck = TradingAccountCheck.UNKNOWN,
  val backupWords: List<String> = emptyList(),
  /** The phrase stays covered until the person asks to see it. */
  val backupRevealed: Boolean = false,
  val confirmationIndices: List<Int> = emptyList(),
  /** For each position to confirm, the right word and two others from the phrase, shuffled. */
  val confirmationOptions: Map<Int, List<String>> = emptyMap(),
  val confirmations: Map<Int, String> = emptyMap(),
  val subaccounts: List<TradingAccount> = emptyList(),
  val subaccountsLoaded: Boolean = false,
  val selectedSubaccount: String? = null,
  /** A new wallet's trading account is open, whether or not trading is enabled yet. */
  val accountOpened: Boolean = false,
  val setupTransaction: ActionState? = null,
  val setupOperation: SetupOperation? = null,
  /** Set when Flare couldn't cover the current step's network fee. */
  val setupFee: SetupFee? = null,
  val builderOptIn: Boolean = true,
  /** Why builder support didn't turn on; setup is otherwise done and waits for the person to go on. */
  val builderNotice: String? = null,
  /** What the last check for the first deposit found, when the person asked for it. */
  val fundingNotice: String? = null,
  /** Setup was opened from inside the app, rather than as the first thing it shows. */
  val inApp: Boolean = false,
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

  data object OpenAccount : OnboardingIntent

  data object EnableTrading : OnboardingIntent

  data object ContinueSetup : OnboardingIntent

  /** Leaves setup after reading why builder support didn't turn on. */
  data object FinishSetup : OnboardingIntent

  /** Checks whether the first deposit arrived, moving on to enabling trading once it has. */
  data object CheckFunding : OnboardingIntent

  /** The same check, asked for by the person, who sees it run and what it found. */
  data object CheckFundingNow : OnboardingIntent

  /** Leaves setup waiting for funds and opens the app; actions wait until setup finishes. */
  data object DeferSetup : OnboardingIntent

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
  private val credentials: CredentialParser,
  private val accounts: AccountRepository,
  private val preferences: AppPreferences,
  venue: VenueDescriptor,
  private val trading: TradingRepository,
) : ViewModel() {
  private val setup = venue.setup
  private val builderAddress = venue.builderAddress.orEmpty()
  private val builderFeeBps = venue.builderFeeBps
  private val maxBuilderFeeBps = venue.maxBuilderFeeBps
  private val local = MutableStateFlow(OnboardingUiState())
  private val effectChannel = Channel<OnboardingEffect>(Channel.BUFFERED)
  private var actionJob: Job? = null
  private var tradingAccountCheckJob: Job? = null
  private var fundingCheckJob: Job? = null
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
        if (intent.mode.uppercase() == RESUME) {
          resume(intent.profileId)
        } else if (subflowMode == null) {
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
          local.value = local.value.copy(inApp = true)
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
      is OnboardingIntent.ChangeTradingAccount -> {
        local.value =
          local.value.copy(
            tradingAccountInput = intent.value,
            tradingAccountCheck = TradingAccountCheck.UNKNOWN,
            error = null,
          )
        checkTradingAccount()
      }
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
        if (local.value.step == OnboardingStep.OPEN_ACCOUNT) openAccount()
        else when (local.value.setupOperation) {
          SetupOperation.CREATE_ACCOUNT -> createSubaccount()
          SetupOperation.ENABLE_TRADING,
          null -> enableTrading()
        }
      OnboardingIntent.CreateOwner -> createOwner()
      OnboardingIntent.ShowImport -> show(OnboardingStep.IMPORT)
      OnboardingIntent.ImportCredential ->
        if (local.value.apiImport) importTradingKey() else importOwner()
      is OnboardingIntent.SetApiImport -> {
        local.value = local.value.copy(apiImport = intent.enabled, error = null)
        checkTradingAccount()
      }
      OnboardingIntent.RevealBackup -> revealBackup()
      OnboardingIntent.ReviewBackup ->
        local.value = local.value.copy(step = OnboardingStep.CONFIRM_BACKUP)
      OnboardingIntent.ConfirmBackup -> confirmBackup()
      OnboardingIntent.DiscoverSubaccounts -> discoverSubaccounts()
      OnboardingIntent.CreateSubaccount -> createSubaccount()
      is OnboardingIntent.SelectSubaccount ->
        local.value = local.value.copy(selectedSubaccount = intent.address, input = intent.address)
      OnboardingIntent.ContinueSubaccount -> continueSubaccount()
      OnboardingIntent.OpenAccount -> openAccount()
      OnboardingIntent.EnableTrading -> enableTrading()
      OnboardingIntent.ContinueSetup -> prepareOwnerAccount()
      OnboardingIntent.FinishSetup ->
        if (local.value.builderNotice != null) {
          launchAction("Setup couldn’t finish.") {
            local.value = local.value.copy(builderNotice = null)
            finishSetupInternal()
          }
        }
      OnboardingIntent.CheckFunding -> checkFunding()
      OnboardingIntent.CheckFundingNow -> checkFundingNow()
      OnboardingIntent.DeferSetup -> deferSetup()
      OnboardingIntent.ClearSensitiveState -> {
        actionJob?.cancel()
        actionJob = null
        clearSecrets()
      }
      OnboardingIntent.Back -> {
        if (local.value.step == OnboardingStep.OPEN_ACCOUNT && local.value.accountOpened) {
          // The account exists on chain; the only way from here is forward.
        } else if (
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
            val previous = previousProfileId
            if (previous != null && preferences.values.first().profiles.any { it.id == previous }) {
              preferences.activateProfile(previous)
            }
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
      is OnboardingIntent.ChangeInput -> {
        local.value = local.value.copy(input = intent.value, error = null)
        checkTradingAccount()
      }
      is OnboardingIntent.ChangeConfirmation -> {
        val state = local.value
        val word = state.backupWords.getOrNull(intent.index)
        local.value =
          if (word == null || intent.value.trim().equals(word, ignoreCase = true)) {
            state.copy(confirmations = state.confirmations + (intent.index to intent.value), error = null)
          } else {
            state.copy(error = "That’s not word ${intent.index + 1}. Try another.")
          }
      }
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
      local.value = local.value.copy(input = "")
      chooseTradingAccount(showListFirst = true)
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
      val cleared =
        state.copy(
          backupWords = emptyList(),
          confirmationIndices = emptyList(),
          confirmationOptions = emptyMap(),
          confirmations = emptyMap(),
        )
      // A wallet made here has nothing to look up: it goes straight to opening its trading account.
      if (activeProfile()?.createdInApp == true && setup.openTradingAccount) {
        local.value = cleared.copy(step = OnboardingStep.OPEN_ACCOUNT, accountOpened = false)
      } else {
        local.value = cleared
        chooseTradingAccount(showListFirst = true)
      }
    }

  private fun prepareOwnerAccount() =
    launchAction("Setup couldn’t continue.") {
      val profile = wallets.profile.first()
      when {
        profile.apiOnly -> show(OnboardingStep.SUBACCOUNT)
        // The phrase is fetched only when the person reveals it.
        profile.ownerAddress != null && !profile.ownerBackupConfirmed ->
          local.value = local.value.copy(step = OnboardingStep.SHOW_BACKUP, backupRevealed = false)
        // A wallet made here resumes at opening its account, with whatever already went through kept.
        activeProfile()?.createdInApp == true && setup.openTradingAccount -> {
          val saved = activeProfile()
          local.value =
            local.value.copy(
              step = OnboardingStep.OPEN_ACCOUNT,
              accountOpened = saved?.selectedSubaccount != null || saved?.tradingAccountOpened == true,
              selectedSubaccount = saved?.selectedSubaccount,
            )
        }
        else -> chooseTradingAccount()
      }
    }

  /**
   * Picks the account to trade from: one of the owner's trading accounts where the venue has them,
   * otherwise the owner's own address. [showListFirst] shows the account list while it loads.
   */
  private suspend fun chooseTradingAccount(showListFirst: Boolean = false) {
    if (setup.tradingAccounts) {
      if (showListFirst) local.value = local.value.copy(step = OnboardingStep.SUBACCOUNT)
      discoverSubaccountsInternal()
      return
    }
    val owner = checkNotNull(wallets.profile.first().ownerAddress) { "No account to set up" }
    accounts.selectTradingAccount(owner, setupPrompt)
    if (!setup.enableTrading) return finishSetupInternal()
    val step =
      if (accounts.awaitsFirstDeposit()) OnboardingStep.FUND_ACCOUNT else OnboardingStep.ENABLE_TRADING
    local.value = local.value.copy(step = step, selectedSubaccount = owner)
  }

  /** Quiet: a failed check leaves the step as it is, and the next one tries again. */
  private fun checkFunding() {
    if (local.value.step != OnboardingStep.FUND_ACCOUNT || fundingCheckJob?.isActive == true) return
    fundingCheckJob =
      viewModelScope.launch {
        val waiting = runSuspendCatching { accounts.awaitsFirstDeposit() }.getOrNull() ?: return@launch
        if (!waiting && local.value.step == OnboardingStep.FUND_ACCOUNT) {
          local.value = local.value.copy(step = OnboardingStep.ENABLE_TRADING, error = null)
        }
      }
  }

  private fun checkFundingNow() {
    if (local.value.step != OnboardingStep.FUND_ACCOUNT) return
    launchAction("Flare couldn’t check for your deposit.") {
      local.value = local.value.copy(fundingNotice = null)
      if (accounts.awaitsFirstDeposit()) {
        local.value =
          local.value.copy(fundingNotice = "No deposit has arrived yet. It can take a few minutes after you send it.")
      } else if (local.value.step == OnboardingStep.FUND_ACCOUNT) {
        local.value = local.value.copy(step = OnboardingStep.ENABLE_TRADING)
      }
    }
  }

  /**
   * Opens the app for a wallet waiting on its first deposit. Only a step that can't finish without
   * funds offers it, so the wallet and its backup are already in place.
   */
  private fun deferSetup() {
    if (local.value.step != OnboardingStep.FUND_ACCOUNT) return
    launchAction("Flare couldn’t open the app.") {
      preferences.deferSetup()
      createdProfileId = null
      clearSensitiveState()
      effectChannel.send(OnboardingEffect.Completed)
    }
  }

  /**
   * Picks up a first setup that stopped, such as after the app was closed. It stays the first setup:
   * stepping back from it returns to the start and leaves no half-made wallet behind. Asked again,
   * as when the app unlocks once more, it keeps the place it already has.
   */
  private fun resume(profileId: String?) {
    if (profileId == null) return
    createdProfileId = profileId
    if (local.value.step != OnboardingStep.WELCOME) return
    viewModelScope.launch {
      if (preferences.values.first().profiles.any { it.id == profileId }) {
        preferences.activateProfile(profileId)
        prepareOwnerAccount()
      }
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
      if (result !is ActionState.Committed) {
        val failed = result as? ActionState.Failed
        if (failed?.selfPay == null) error(failed?.message.orEmpty())
        local.value = local.value.copy(setupFee = assessFee(failed))
        return@launchAction
      }
      // Creating the account and enabling trading are one reviewed operation.
      discoverSubaccountsInternal()
      if (local.value.step == OnboardingStep.ENABLE_TRADING) delegateApiAndFinish()
    }

  /**
   * Opens a new wallet's trading account and enables trading, picking up after whatever part already
   * went through: the account is never opened twice, and enabling trading checks before it repeats.
   * Flare sponsors every fee; if it can't just now, the step stays saved for another try.
   */
  private fun openAccount() =
    launchAction("Your trading account isn’t ready yet.") {
      local.value =
        local.value.copy(
          setupOperation = SetupOperation.CREATE_ACCOUNT,
          setupTransaction = null,
          setupFee = null,
        )
      // One confirmation covers the whole action: the trading key is stored while it's fresh, and
      // opening the account, enabling trading and builder support sign without asking again.
      accounts.confirmedSetup(OpenAccountPrompt) {
        wallets.createApiWallet(setupPrompt)
        val subaccount = accounts.openTradingAccount(setupPrompt)
        accounts.selectTradingAccount(subaccount, setupPrompt)
        local.value = local.value.copy(accountOpened = true, selectedSubaccount = subaccount)
        delegateApiAndFinish(FeePayment.SPONSORED)
      }
    }

  private suspend fun activeProfile(): AccountProfile? {
    val saved = preferences.values.first()
    return saved.profiles.firstOrNull { it.id == saved.activeProfileId }
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
    launchAction("Trading wasn’t enabled on this device.") {
      // Enabling trading and builder support are separate transactions confirmed once, together.
      accounts.confirmedSetup(EnableTradingPrompt) {
        wallets.createApiWallet(setupPrompt)
        delegateApiAndFinish(feePayment)
      }
    }

  private fun importTradingKey() =
    launchAction("That trading key wasn’t imported.") {
      accounts.importTradingKey(
        local.value.input.trim(),
        local.value.tradingAccountInput.trim(),
        VaultPrompt("Import trading account", "Confirm your identity"),
      )
      preferences.setBuilderSupport(
        builderAddress = builderAddress.takeIf(String::isNotBlank),
        builderFeeBps = 0,
        builderApproved = false,
      )
      finishSetupInternal()
    }

  /** Looks the trading account up quietly after typing settles; the result only ever adds a hint. */
  private fun checkTradingAccount() {
    tradingAccountCheckJob?.cancel()
    val state = local.value
    val address = state.tradingAccountInput.trim()
    if (!state.apiImport || !credentials.isAddress(address)) return
    tradingAccountCheckJob =
      viewModelScope.launch {
        delay(TRADING_ACCOUNT_CHECK_DELAY_MS)
        val ownAddress = credentials.keyAddress(state.input)
        val check =
          if (ownAddress != null && credentials.sameAddress(ownAddress, address)) {
            TradingAccountCheck.KEY_ADDRESS
          } else {
            when (accounts.tradingAccountStatus(address)) {
              TradingAccountStatus.ACTIVE -> TradingAccountCheck.TRADING_ACCOUNT
              TradingAccountStatus.INACTIVE -> TradingAccountCheck.CLOSED
              TradingAccountStatus.NOT_A_TRADING_ACCOUNT -> TradingAccountCheck.WALLET
              null -> TradingAccountCheck.UNKNOWN
            }
          }
        if (local.value.tradingAccountInput.trim() == address) {
          local.value = local.value.copy(tradingAccountCheck = check)
        }
      }
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
    if (activeProfile()?.builderApproved == true) {
      // Approved by an earlier attempt; nothing to send again.
    } else if (setup.builderFee && local.value.builderOptIn && builderAddress.isNotBlank()) {
      val approval =
        runSuspendCatching {
          // Always sponsored: agreeing to pay for enabling trading isn't agreeing to pay for this too.
          accounts.approveBuilderFee(
            builderAddress = builderAddress,
            feeBps = maxBuilderFeeBps.toUInt(),
            prompt = VaultPrompt(title = "Approve builder support", subtitle = "Confirm your identity"),
            feePayment = FeePayment.SPONSORED,
          )
        }
      if (approval.getOrNull() is ActionState.Committed) {
        preferences.setBuilderFeeBps(builderFeeBps)
      } else {
        preferences.setBuilderSupport(
          builderAddress = builderAddress,
          builderFeeBps = 0,
          builderApproved = false,
        )
        val reason =
          (approval.getOrNull() as? ActionState.Failed)?.message
            ?: approval.exceptionOrNull()?.message
        val outcome = "Trading is enabled, but builder support wasn’t turned on."
        val explained = actionFailure(reason, outcome).takeIf { it.startsWith(outcome) } ?: outcome
        local.value =
          local.value.copy(builderNotice = "$explained You can turn it on later in Settings.")
        return
      }
    } else {
      preferences.setBuilderSupport(
        builderAddress = builderAddress.takeIf(String::isNotBlank),
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
        inApp = local.value.inApp,
      )
  }

  /**
   * Whether the wallet can pay a fee Flare couldn't cover. The network sets the whole gas limit aside
   * before running a transaction, so that reserve, not the smaller fee, is what the balance must meet.
   */
  private suspend fun assessFee(failed: ActionState.Failed): SetupFee {
    val offer = checkNotNull(failed.selfPay)
    val estimate = offer.estimate
    // A wallet made here has no fee token to pay with, so there's no balance to check or charge.
    if (activeProfile()?.createdInApp == true) return SetupFee.AwaitingSponsor(estimate)
    val reserve = maxOf(offer.reserve ?: estimate, estimate)
    val owner = wallets.profile.first().ownerAddress
    val balance = runSuspendCatching { trading.ownerFeeBalance() }.getOrNull()
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
        val failed = error.transaction as? ActionState.Failed
        local.value =
          if (failed?.selfPay != null) {
            local.value.copy(setupTransaction = failed, setupFee = assessFee(failed))
          } else {
            local.value.copy(
              setupTransaction = error.transaction,
              error =
                actionFailure(
                  failed?.message,
                  if (local.value.setupOperation == SetupOperation.CREATE_ACCOUNT) {
                    "Your trading account wasn’t opened."
                  } else {
                    "Trading wasn’t enabled on this device."
                  },
                ),
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

private const val TRADING_ACCOUNT_CHECK_DELAY_MS = 400L

/** A first setup picked up where it stopped, as opposed to one opened from inside the app. */
const val RESUME = "RESUME"

private val OpenAccountPrompt = VaultPrompt("Open trading account", "Confirm your identity")
private val EnableTradingPrompt = VaultPrompt("Enable trading", "Confirm your identity")
