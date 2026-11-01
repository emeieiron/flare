package xyz.mcxross.flare.feature.onboarding

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.LaunchStage
import xyz.mcxross.flare.launchStage
import xyz.mcxross.flare.resumedSetup
import xyz.mcxross.flare.shownWallet
import xyz.mcxross.flare.feature.referrals.ReferralRepository
import xyz.mcxross.flare.feature.referrals.ReferralProgram
import xyz.mcxross.flare.feature.referrals.ReferralEligibility
import xyz.mcxross.flare.feature.referrals.ReferralStatus
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.setupPending
import xyz.mcxross.flare.testing.SetupBackupWords
import xyz.mcxross.flare.testing.MemoryPreferences
import xyz.mcxross.flare.testing.SETUP_OWNER
import xyz.mcxross.flare.testing.SETUP_PHRASE
import xyz.mcxross.flare.testing.SetupAccounts
import xyz.mcxross.flare.testing.SetupCredentials
import xyz.mcxross.flare.testing.SetupTrading
import xyz.mcxross.flare.testing.SetupWallets
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.venue.VenueSetup

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun anImportedOwnerPicksFromItsTradingAccountsWhereTheVenueHasThem() = runTest {
    val accounts = SetupAccounts()
    val vm = viewModel(accounts, VenueSetup())
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)

    assertEquals(OnboardingStep.SUBACCOUNT, vm.uiState.value.step)
    assertEquals(accounts.listed, vm.uiState.value.subaccounts)
    assertEquals(listOf("discover"), accounts.events)
  }

  @Test
  fun aVenueWithoutTradingAccountsTradesFromTheOwnerAndSkipsTheBuilderFee() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val id = deferredWallet(preferences)
    val accounts = SetupAccounts(preferences)
    val vm = viewModel(accounts, VenueSetup(tradingAccounts = false, builderFee = false), preferences, ownerWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(OnboardingIntent.InitializeMode("CONTINUE", id))

    assertEquals(OnboardingStep.ENABLE_TRADING, vm.uiState.value.step)
    assertEquals(OWNER, vm.uiState.value.selectedSubaccount)
    assertEquals(listOf("select $OWNER"), accounts.events)

    vm.onIntent(OnboardingIntent.EnableTrading)
    assertEquals(listOf("select $OWNER", "prepare"), accounts.events)
    assertTrue(vm.uiState.value.error == null)
  }

  @Test
  fun aFailedBuilderApprovalIsExplainedBeforeSetupFinishes() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val id = deferredWallet(preferences)
    val accounts =
      SetupAccounts(preferences).apply {
        approveResult =
          ActionState.Failed("Move abort in 0x1::dex_accounts_entry: EBUILDER_SUBACCOUNT_NOT_FOUND(0x1):")
      }
    val vm = viewModel(accounts, VenueSetup(tradingAccounts = false, builderFee = true), preferences, ownerWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val completed = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }

    vm.onIntent(OnboardingIntent.InitializeMode("CONTINUE", id))
    vm.onIntent(OnboardingIntent.EnableTrading)

    assertEquals(listOf("select $OWNER", "prepare", "approve builder"), accounts.events)
    assertEquals(
      "Trading is enabled, but builder support wasn’t turned on. Flare’s builder account isn’t set " +
        "up on the exchange yet. You can turn it on later in Settings.",
      vm.uiState.value.builderNotice,
    )
    assertTrue(completed.isActive)

    vm.onIntent(OnboardingIntent.FinishSetup)
    assertEquals(OnboardingEffect.Completed, completed.await())
    assertNull(vm.uiState.value.builderNotice)
  }

  @Test
  fun anImportOpensTheMarketsWithoutAskingForADepositFundedOrNot() = runTest {
    for (awaiting in listOf(true, false)) {
      val preferences = AppPreferences(MemoryPreferences())
      val accounts = SetupAccounts(preferences).apply { awaitingDeposit = awaiting }
      val vm = viewModel(accounts, HyperliquidSetup, preferences, SetupWallets(preferences))
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
      val completed = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }

      vm.onIntent(OnboardingIntent.ShowImport)
      vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
      vm.onIntent(OnboardingIntent.ImportCredential)

      assertEquals(OnboardingEffect.Completed, completed.await())
      assertEquals(listOf("select $OWNER"), accounts.events)
      assertEquals(0, accounts.depositChecks)
      val profile = preferences.values.first().profiles.single()
      assertTrue(profile.setupDeferred)
      assertFalse(profile.onboardingComplete)
    }
  }

  @Test
  fun aVenueWithoutTradingKeysFinishesSetupOnceTheWalletExists() = runTest {
    val accounts = SetupAccounts()
    val vm =
      viewModel(
        accounts,
        VenueSetup(
          tradingAccounts = false,
          openTradingAccount = false,
          builderFee = false,
          enableTrading = false,
        ),
      )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val completed = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }

    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)

    assertEquals(OnboardingEffect.Completed, completed.await())
    assertEquals(listOf("select $OWNER"), accounts.events)
    assertEquals(null, vm.uiState.value.profile.apiWalletAddress)
  }

  @Test
  fun createdAndBackedUpGoesStraightToTheMarketsAndRelaunchesIntoTheApp() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val accounts = SetupAccounts(preferences).apply { awaitingDeposit = true }
    val vm = viewModel(accounts, HyperliquidSetup, preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val completed = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }

    createAndConfirmBackup(vm)

    assertEquals(OnboardingEffect.Completed, completed.await())
    val saved = preferences.values.first()
    val profile = saved.profiles.single()
    assertTrue(profile.setupDeferred)
    assertFalse(profile.onboardingComplete)
    assertEquals(OWNER, profile.selectedSubaccount)
    // The next launch unlocks straight into the app rather than setup.
    assertEquals(LaunchStage.LOCKED, launchStage(saved, walletLoaded = true, unlocked = false))
    assertEquals(LaunchStage.READY, launchStage(saved, walletLoaded = true, unlocked = true))
    assertNull(resumedSetup(saved))
    assertEquals(profile.id, shownWallet(saved, null))
  }

  @Test
  fun decibelSetupIsUnchangedAndNeverChecksForADeposit() = runTest {
    val decibel = AppPreferences(MemoryPreferences())
    val accounts = SetupAccounts()
    val vm = viewModel(accounts, VenueSetup(), decibel, SetupWallets(decibel))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    assertEquals(OnboardingStep.SUBACCOUNT, vm.uiState.value.step)
    assertFalse(decibel.values.first().profiles.single().setupDeferred)
    assertEquals(0, accounts.depositChecks)
  }

  @Test
  fun theInAppPromptEnablesTradingOnceFundsArrive() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val id = deferredWallet(preferences)
    val accounts = SetupAccounts(preferences).apply { awaitingDeposit = true }
    val unfunded = viewModel(accounts, HyperliquidSetup, preferences, ownerWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { unfunded.uiState.collect {} }
    val back = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { unfunded.effects.first() }

    // Still waiting for its deposit, it goes back to the app instead of a step it can't finish.
    unfunded.onIntent(OnboardingIntent.InitializeMode("CONTINUE", id))
    assertEquals(OnboardingEffect.Completed, back.await())
    assertTrue(preferences.values.first().profiles.single().setupPending)

    accounts.awaitingDeposit = false
    val vm = viewModel(accounts, HyperliquidSetup, preferences, ownerWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val completed = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }
    vm.onIntent(OnboardingIntent.InitializeMode("CONTINUE", id))
    assertEquals(OnboardingStep.ENABLE_TRADING, vm.uiState.value.step)
    assertTrue(vm.uiState.value.inApp)
    vm.onIntent(OnboardingIntent.EnableTrading)

    assertEquals(OnboardingEffect.Completed, completed.await())
    val profile = preferences.values.first().profiles.single()
    assertTrue(profile.onboardingComplete)
    assertFalse(profile.setupDeferred)
  }

  @Test
  fun backAtTheBackupQuizMatchesWhetherOrNotTheAppWasLeft() = runTest {
    suspend fun backFromQuiz(relock: Boolean): Pair<OnboardingUiState, AppPreferences> {
      val preferences = AppPreferences(MemoryPreferences())
      val vm = viewModel(SetupAccounts(preferences), HyperliquidSetup, preferences, SetupWallets(preferences))
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
      vm.onIntent(OnboardingIntent.CreateOwner)
      vm.onIntent(OnboardingIntent.ReviewBackup)
      if (relock) {
        val id = assertNotNull(resumedSetup(preferences.values.first()))
        vm.onIntent(OnboardingIntent.ClearSensitiveState)
        vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))
        // Unlocking twice, as when the app comes back again, keeps the place.
        vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))
        assertEquals(OnboardingStep.SHOW_BACKUP, vm.uiState.value.step)
      } else {
        vm.onIntent(OnboardingIntent.Back)
        assertEquals(OnboardingStep.SHOW_BACKUP, vm.uiState.value.step)
      }
      vm.onIntent(OnboardingIntent.Back)
      return vm.uiState.value to preferences
    }

    val (stayed, stayedPreferences) = backFromQuiz(relock = false)
    val (left, leftPreferences) = backFromQuiz(relock = true)

    assertTrue(stayed.showsWelcome(initialMode = null))
    assertTrue(left.showsWelcome(initialMode = RESUME))
    assertEquals(stayed, left)
    assertTrue(stayedPreferences.values.first().profiles.isEmpty())
    assertTrue(leftPreferences.values.first().profiles.isEmpty())
  }

  @Test
  fun backAtOpenAccountMatchesWhetherOrNotTheAppWasLeft() = runTest {
    suspend fun backFromOpenAccount(relock: Boolean, failedOpen: Boolean): Pair<OnboardingUiState, AppPreferences> {
      val preferences = AppPreferences(MemoryPreferences())
      val accounts =
        object : SetupAccounts(preferences) {
          override suspend fun openTradingAccount(prompt: VaultPrompt): String =
            if (failedOpen) throw SetupTransactionException(ActionState.Failed("Sponsorship unavailable"))
            else super.openTradingAccount(prompt)
        }
      val vm = viewModel(accounts, VenueSetup(), preferences, SetupWallets(preferences))
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
      createAndConfirmBackup(vm)
      assertEquals(OnboardingStep.OPEN_ACCOUNT, vm.uiState.value.step)
      if (failedOpen) {
        vm.onIntent(OnboardingIntent.OpenAccount)
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.accountOpened)
      }
      if (relock) {
        val id = assertNotNull(resumedSetup(preferences.values.first()))
        vm.onIntent(OnboardingIntent.ClearSensitiveState)
        vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))
        vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))
        assertEquals(OnboardingStep.OPEN_ACCOUNT, vm.uiState.value.step)
        assertFalse(vm.uiState.value.busy)
      }
      vm.onIntent(OnboardingIntent.Back)
      return vm.uiState.value to preferences
    }

    for (failedOpen in listOf(false, true)) {
      val (stayed, stayedPreferences) = backFromOpenAccount(relock = false, failedOpen = failedOpen)
      val (left, leftPreferences) = backFromOpenAccount(relock = true, failedOpen = failedOpen)

      assertTrue(stayed.showsWelcome(initialMode = null))
      assertTrue(left.showsWelcome(initialMode = RESUME))
      assertEquals(stayed, left)
      assertTrue(stayedPreferences.values.first().profiles.isEmpty())
      assertTrue(leftPreferences.values.first().profiles.isEmpty())
    }
  }

  @Test
  fun backAtTheBackupAfterTheAppUnlocksAgainReturnsToTheStart() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val vm = viewModel(SetupAccounts(preferences), VenueSetup(), preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.CreateOwner)
    vm.onIntent(OnboardingIntent.ReviewBackup)
    val id = assertNotNull(resumedSetup(preferences.values.first()))

    vm.onIntent(OnboardingIntent.ClearSensitiveState)
    vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))
    assertEquals(OnboardingStep.SHOW_BACKUP, vm.uiState.value.step)
    vm.onIntent(OnboardingIntent.Back)

    assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
    assertTrue(vm.uiState.value.showsWelcome(RESUME))
    assertTrue(preferences.values.first().profiles.isEmpty())
  }

  @Test
  fun failedLookupRecoversWithoutSelectingOrDelegatingTheOnlyAccount() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    var reads = 0
    val accounts = object : SetupAccounts(preferences) {
      override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount> {
        reads++
        if (reads < 3) error("Connection lost")
        return listed.take(1)
      }
    }
    val vm = viewModel(accounts, VenueSetup(), preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    assertFalse(vm.uiState.value.busy)
    assertFalse(vm.uiState.value.subaccountsLoaded)

    advanceTimeBy(3_001); runCurrent()

    assertEquals(3, reads)
    assertTrue(vm.uiState.value.subaccountsLoaded)
    assertEquals(accounts.listed.take(1), vm.uiState.value.subaccounts)
    assertEquals(OnboardingStep.SUBACCOUNT, vm.uiState.value.step)
    assertTrue(accounts.events.isEmpty())
    assertNull(vm.uiState.value.accountLookupError)
  }

  @Test
  fun lookupRecoveryStopsWhenHiddenAndResumesOnlyReads() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    var reads = 0
    var available = false
    val accounts = object : SetupAccounts(preferences) {
      override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount> {
        reads++
        check(available) { "Connection lost" }
        return listed
      }
    }
    val vm = viewModel(accounts, VenueSetup(), preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    vm.onIntent(OnboardingIntent.ClearSensitiveState)
    advanceTimeBy(31_000); runCurrent()
    assertEquals(1, reads)

    available = true
    vm.onIntent(OnboardingIntent.ResumeReads)
    advanceTimeBy(1_001); runCurrent()
    assertEquals(2, reads)
    assertTrue(vm.uiState.value.subaccountsLoaded)
    assertTrue(accounts.events.isEmpty())
    assertFalse(vm.uiState.value.busy)
  }

  @Test
  fun backingOutDiscardsALateLookupResult() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val response = CompletableDeferred<Unit>()
    var reads = 0
    val accounts = object : SetupAccounts(preferences) {
      override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount> {
        reads++
        if (reads == 1) error("Connection lost")
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { response.await() }
        return listed.take(1)
      }
    }
    val vm = viewModel(accounts, VenueSetup(), preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    advanceTimeBy(1_001); runCurrent()
    vm.onIntent(OnboardingIntent.Back)
    response.complete(Unit); runCurrent()

    assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
    assertFalse(vm.uiState.value.subaccountsLoaded)
    assertTrue(accounts.events.isEmpty())
  }

  @Test
  fun referralAccountCreationNeverFallsThroughToNormalCreation() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    var redeemed = false
    var normalCreates = 0
    var applies = 0
    val accounts = object : SetupAccounts(preferences) {
      override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt) = if (redeemed) listed.take(1) else emptyList()
      override suspend fun subaccounts(owner: String) = if (redeemed) listed.take(1) else emptyList()
      override suspend fun createSubaccount(prompt: VaultPrompt, feePayment: FeePayment): ActionState {
        normalCreates++
        error("The referral endpoint already created the account")
      }
    }
    val referrals = object : ReferralRepository {
      override suspend fun program() = ReferralProgram(true, "OC9E6E")
      override suspend fun status(owner: String) = ReferralStatus(owner, if (redeemed) ReferralEligibility.BOUND else ReferralEligibility.ELIGIBLE,
        if (redeemed) "OC9E6E" else null, if (redeemed) "0xa1" else null)
      override suspend fun validate(code: String) = true
      override suspend fun apply(owner: String, code: String, prompt: VaultPrompt): ReferralStatus {
        applies++
        redeemed = true
        return ReferralStatus(owner, ReferralEligibility.BOUND, code, "0xa1")
      }
    }
    val vm = viewModel(accounts, VenueSetup(), preferences, SetupWallets(preferences), referrals)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.ResumeReads)
    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    advanceTimeBy(301); runCurrent()
    assertTrue(vm.uiState.value.referral.canApply)

    vm.onIntent(OnboardingIntent.CreateSubaccount)

    assertEquals(1, applies)
    assertEquals(0, normalCreates)
    assertTrue(preferences.values.first().profiles.single().tradingAccountOpened)
    assertTrue(accounts.events.contains("select 0xa1"))
    assertTrue(accounts.events.contains("prepare"))
    vm.onIntent(OnboardingIntent.ClearSensitiveState)
  }

  @Test
  fun clearingTheReferralKeepsTheNormalAccountCreationPath() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    var normalCreates = 0
    var applies = 0
    val accounts = object : SetupAccounts(preferences) {
      override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt) = emptyList<TradingAccount>()
      override suspend fun createSubaccount(prompt: VaultPrompt, feePayment: FeePayment): ActionState {
        normalCreates++
        return ActionState.Failed("Normal creation requested", definitelyNotSubmitted = true)
      }
    }
    val referrals = object : ReferralRepository {
      override suspend fun program() = ReferralProgram(true, "OC9E6E")
      override suspend fun status(owner: String) = ReferralStatus(owner, ReferralEligibility.ELIGIBLE)
      override suspend fun validate(code: String) = true
      override suspend fun apply(owner: String, code: String, prompt: VaultPrompt): ReferralStatus {
        applies++
        error("A cleared code must never be applied")
      }
    }
    val vm = viewModel(accounts, VenueSetup(), preferences, SetupWallets(preferences), referrals)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.ResumeReads)
    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    advanceTimeBy(301); runCurrent()
    vm.onIntent(OnboardingIntent.ChangeReferralCode(""))

    vm.onIntent(OnboardingIntent.CreateSubaccount)

    assertEquals(0, applies)
    assertEquals(1, normalCreates)
    assertEquals("", vm.uiState.value.referral.input)
    vm.onIntent(OnboardingIntent.ClearSensitiveState)
  }

  @Test
  fun pendingReferralReusesOnlyItsVerifiedAccountWithoutResubmitting() = runTest {
    for (preferred in listOf("0xa1", "0xf")) {
      val preferences = AppPreferences(MemoryPreferences())
      var creates = 0
      var applies = 0
      val accounts = object : SetupAccounts(preferences) {
        override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt) = emptyList<TradingAccount>()
        override suspend fun subaccounts(owner: String) = listed.take(1)
        override suspend fun createSubaccount(prompt: VaultPrompt, feePayment: FeePayment): ActionState {
          creates++
          error("An uncertain referral must not create another account")
        }
      }
      val referrals = object : ReferralRepository {
        override suspend fun program() = ReferralProgram(true, "OC9E6E")
        override suspend fun status(owner: String) = ReferralStatus(owner, ReferralEligibility.PENDING, "OC9E6E", preferred)
        override suspend fun pendingCode(owner: String) = "OC9E6E"
        override suspend fun validate(code: String) = true
        override suspend fun apply(owner: String, code: String, prompt: VaultPrompt): ReferralStatus {
          applies++
          error("Reconciliation only reads")
        }
      }
      val vm = viewModel(accounts, VenueSetup(), preferences, SetupWallets(preferences), referrals)
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
      try {
        vm.onIntent(OnboardingIntent.ResumeReads)
        vm.onIntent(OnboardingIntent.ShowImport)
        vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
        vm.onIntent(OnboardingIntent.ImportCredential)
        vm.onIntent(OnboardingIntent.CreateSubaccount)

        assertEquals(0, creates)
        assertEquals(0, applies)
        assertEquals(preferred == "0xa1", accounts.events.contains("select 0xa1"))
        assertEquals(preferred == "0xa1", accounts.events.contains("prepare"))
        if (preferred == "0xf") assertEquals("Your trading account isn’t available yet", vm.uiState.value.error)
      } finally {
        vm.onIntent(OnboardingIntent.ClearSensitiveState)
      }
    }
  }

  private fun createAndConfirmBackup(vm: OnboardingViewModel) {
    vm.onIntent(OnboardingIntent.CreateOwner)
    vm.onIntent(OnboardingIntent.ReviewBackup)
    vm.uiState.value.confirmationIndices.forEach {
      vm.onIntent(OnboardingIntent.ChangeConfirmation(it, SetupBackupWords[it]))
    }
    vm.onIntent(OnboardingIntent.ConfirmBackup)
  }

  @Test
  fun anInstallLeftOnTheOldFundingStepOpensTheApp() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(
      AccountProfile("owner_$OWNER", ownerAddress = OWNER, ownerBackupConfirmed = true, selectedSubaccount = OWNER)
    )
    val saved = preferences.values.first()
    assertEquals(LaunchStage.ONBOARDING, launchStage(saved, walletLoaded = true, unlocked = true))
    val id = assertNotNull(resumedSetup(saved))
    val vm = viewModel(SetupAccounts(preferences).apply { awaitingDeposit = true }, HyperliquidSetup, preferences, ownerWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val completed = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }

    vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))

    assertEquals(OnboardingEffect.Completed, completed.await())
    assertEquals(LaunchStage.READY, launchStage(preferences.values.first(), walletLoaded = true, unlocked = true))
    assertTrue(preferences.values.first().profiles.single().setupPending)
  }

  @Test
  fun resumingAWalletThatIsGoneShowsTheWelcomePage() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val vm = viewModel(SetupAccounts(), HyperliquidSetup, preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    assertFalse(vm.uiState.value.showsWelcome(RESUME))

    vm.onIntent(OnboardingIntent.InitializeMode(RESUME, "owner_$OWNER"))

    assertTrue(vm.uiState.value.showsWelcome(RESUME))
  }

  @Test
  fun leavingAnInAppSetupWhoseWalletIsGoneDoesNotCrash() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val id = deferredWallet(preferences)
    val vm = viewModel(SetupAccounts(), HyperliquidSetup, preferences, ownerWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val cancelled = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }
    vm.onIntent(OnboardingIntent.InitializeMode("CONTINUE", id))
    assertEquals(OnboardingStep.ENABLE_TRADING, vm.uiState.value.step)
    preferences.removeProfile(id)

    vm.onIntent(OnboardingIntent.Back)

    assertEquals(OnboardingEffect.Cancelled, cancelled.await())
  }

  @Test
  fun aWrongWordInTheBackupQuizSaysSo() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val vm = viewModel(SetupAccounts(), VenueSetup(), preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.CreateOwner)
    vm.onIntent(OnboardingIntent.ReviewBackup)
    assertEquals(OnboardingStep.CONFIRM_BACKUP, vm.uiState.value.step)
    val index = vm.uiState.value.confirmationIndices.first()
    val right = SetupBackupWords[index]
    val wrong = vm.uiState.value.confirmationOptions.getValue(index).first { it != right }

    vm.onIntent(OnboardingIntent.ChangeConfirmation(index, wrong))
    assertEquals("That’s not word ${index + 1}. Try another.", vm.uiState.value.error)
    assertTrue(vm.uiState.value.confirmations.isEmpty())

    vm.onIntent(OnboardingIntent.ChangeConfirmation(index, right))
    assertNull(vm.uiState.value.error)
    assertEquals(right, vm.uiState.value.confirmations[index])
  }

  private fun ownerWallets(preferences: AppPreferences) =
    SetupWallets(preferences).apply {
      profile.value = profile.value.copy(ownerAddress = OWNER, ownerBackupConfirmed = true)
    }

  /** A wallet whose setup was left for later, as the app finds it. */
  private suspend fun deferredWallet(preferences: AppPreferences): String {
    val id = "owner_$OWNER"
    preferences.registerProfile(
      AccountProfile(id, ownerAddress = OWNER, ownerBackupConfirmed = true, selectedSubaccount = OWNER)
    )
    preferences.deferSetup()
    return id
  }

  private fun viewModel(
    accounts: SetupAccounts,
    setup: VenueSetup,
    preferences: AppPreferences = AppPreferences(MemoryPreferences()),
    wallets: SetupWallets = SetupWallets(),
    referrals: ReferralRepository? = null,
  ) =
    OnboardingViewModel(
      wallets = wallets,
      credentials = SetupCredentials,
      accounts = accounts,
      preferences = preferences,
      venue = testVenue(setup = setup),
      trading = SetupTrading,
      referrals = referrals,
    )

  private companion object {
    val HyperliquidSetup = VenueSetup(tradingAccounts = false, openTradingAccount = false, builderFee = false)
    const val OWNER = SETUP_OWNER
    const val PHRASE = SETUP_PHRASE
  }
}
