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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.LaunchStage
import xyz.mcxross.flare.launchStage
import xyz.mcxross.flare.resumedSetup
import xyz.mcxross.flare.shownWallet
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.testing.SetupBackupWords
import xyz.mcxross.flare.testing.MemoryPreferences
import xyz.mcxross.flare.testing.SETUP_OWNER
import xyz.mcxross.flare.testing.SETUP_PHRASE
import xyz.mcxross.flare.testing.SetupAccounts
import xyz.mcxross.flare.testing.SetupCredentials
import xyz.mcxross.flare.testing.SetupTrading
import xyz.mcxross.flare.testing.SetupWallets
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
    val accounts = SetupAccounts()
    val vm = viewModel(accounts, VenueSetup(tradingAccounts = false, builderFee = false))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)

    assertEquals(OnboardingStep.ENABLE_TRADING, vm.uiState.value.step)
    assertEquals(OWNER, vm.uiState.value.selectedSubaccount)
    assertEquals(listOf("select $OWNER"), accounts.events)

    vm.onIntent(OnboardingIntent.EnableTrading)
    assertEquals(listOf("select $OWNER", "prepare"), accounts.events)
    assertTrue(vm.uiState.value.error == null)
  }

  @Test
  fun aFailedBuilderApprovalIsExplainedBeforeSetupFinishes() = runTest {
    val accounts =
      SetupAccounts().apply {
        approveResult =
          ActionState.Failed("Move abort in 0x1::dex_accounts_entry: EBUILDER_SUBACCOUNT_NOT_FOUND(0x1):")
      }
    val vm = viewModel(accounts, VenueSetup(tradingAccounts = false, builderFee = true))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val completed = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }

    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
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
  fun anUnfundedAccountWaitsForItsFirstDepositBeforeEnablingTrading() = runTest {
    val accounts = SetupAccounts().apply { awaitingDeposit = true }
    val vm = viewModel(accounts, VenueSetup(tradingAccounts = false, builderFee = false))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)
    assertEquals(OWNER, vm.uiState.value.selectedSubaccount)

    vm.onIntent(OnboardingIntent.CheckFunding)
    assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)

    accounts.depositCheckFails = true
    vm.onIntent(OnboardingIntent.CheckFunding)
    assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)
    assertNull(vm.uiState.value.error)

    accounts.depositCheckFails = false
    accounts.awaitingDeposit = false
    vm.onIntent(OnboardingIntent.CheckFunding)
    assertEquals(OnboardingStep.ENABLE_TRADING, vm.uiState.value.step)

    vm.onIntent(OnboardingIntent.EnableTrading)
    assertEquals(listOf("select $OWNER", "prepare"), accounts.events)
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
  fun aWalletWaitingForItsFirstDepositCanBrowseAndOpensTheAppOnRelaunch() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val accounts = SetupAccounts(preferences).apply { awaitingDeposit = true }
    val vm = viewModel(accounts, HyperliquidSetup, preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val completed = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }

    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)
    assertFalse(vm.uiState.value.inApp)

    vm.onIntent(OnboardingIntent.DeferSetup)

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
  fun onlyAStepWaitingForFundsOffersToBrowse() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    // A funded account goes straight to enabling trading, which needs nothing it lacks.
    val funded = viewModel(SetupAccounts(), HyperliquidSetup, preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { funded.uiState.collect {} }
    funded.onIntent(OnboardingIntent.ShowImport)
    funded.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    funded.onIntent(OnboardingIntent.ImportCredential)
    assertEquals(OnboardingStep.ENABLE_TRADING, funded.uiState.value.step)
    funded.onIntent(OnboardingIntent.DeferSetup)
    assertEquals(OnboardingStep.ENABLE_TRADING, funded.uiState.value.step)
    assertFalse(preferences.values.first().profiles.single().setupDeferred)

    // Decibel opens accounts unfunded: setup never waits for a deposit, so it never stops there.
    val decibel = AppPreferences(MemoryPreferences())
    val accounts = SetupAccounts()
    val vm = viewModel(accounts, VenueSetup(), decibel, SetupWallets(decibel))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    assertEquals(OnboardingStep.SUBACCOUNT, vm.uiState.value.step)
    vm.onIntent(OnboardingIntent.DeferSetup)
    assertEquals(OnboardingStep.SUBACCOUNT, vm.uiState.value.step)
    assertFalse(decibel.values.first().profiles.single().setupDeferred)
    assertEquals(0, accounts.depositChecks)
  }

  @Test
  fun theInAppPromptResumesSetupWhereItStopped() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val id = deferredWallet(preferences)
    val accounts = SetupAccounts().apply { awaitingDeposit = true }
    val wallets = SetupWallets(preferences).apply {
      profile.value = profile.value.copy(ownerAddress = OWNER, ownerBackupConfirmed = true)
    }
    val vm = viewModel(accounts, HyperliquidSetup, preferences, wallets)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val completed = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }

    vm.onIntent(OnboardingIntent.InitializeMode("CONTINUE", id))
    assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)
    assertTrue(vm.uiState.value.inApp)

    accounts.awaitingDeposit = false
    vm.onIntent(OnboardingIntent.CheckFundingNow)
    assertEquals(OnboardingStep.ENABLE_TRADING, vm.uiState.value.step)
    vm.onIntent(OnboardingIntent.EnableTrading)

    assertEquals(OnboardingEffect.Completed, completed.await())
    val profile = preferences.values.first().profiles.single()
    assertTrue(profile.onboardingComplete)
    assertFalse(profile.setupDeferred)
  }

  @Test
  fun backAfterTheAppUnlocksAgainReturnsToTheStartWithoutCrashing() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val accounts = SetupAccounts(preferences).apply { awaitingDeposit = true }
    val vm = viewModel(accounts, HyperliquidSetup, preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)
    val id = preferences.values.first().profiles.single().id

    // Backgrounding locks the app; unlocking shows setup again, which asks to resume this wallet.
    vm.onIntent(OnboardingIntent.ClearSensitiveState)
    vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))
    assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)

    vm.onIntent(OnboardingIntent.Back)

    assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
    assertTrue(vm.uiState.value.showsWelcome(RESUME))
    assertTrue(preferences.values.first().profiles.isEmpty())
  }

  @Test
  fun backAtAddFundsMatchesWhetherOrNotTheAppWasLeft() = runTest {
    suspend fun backFromAddFunds(relock: Boolean): Pair<OnboardingUiState, AppPreferences> {
      val preferences = AppPreferences(MemoryPreferences())
      val accounts = SetupAccounts(preferences).apply { awaitingDeposit = true }
      val vm = viewModel(accounts, HyperliquidSetup, preferences, SetupWallets(preferences))
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
      vm.onIntent(OnboardingIntent.ShowImport)
      vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
      vm.onIntent(OnboardingIntent.ImportCredential)
      assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)
      if (relock) {
        val id = preferences.values.first().profiles.single().id
        vm.onIntent(OnboardingIntent.ClearSensitiveState)
        vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))
        // Unlocking twice, as when the app comes back again, keeps the place.
        vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))
        assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)
      }
      vm.onIntent(OnboardingIntent.Back)
      return vm.uiState.value to preferences
    }

    val (stayed, stayedPreferences) = backFromAddFunds(relock = false)
    val (left, leftPreferences) = backFromAddFunds(relock = true)

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

  private fun createAndConfirmBackup(vm: OnboardingViewModel) {
    vm.onIntent(OnboardingIntent.CreateOwner)
    vm.onIntent(OnboardingIntent.ReviewBackup)
    vm.uiState.value.confirmationIndices.forEach {
      vm.onIntent(OnboardingIntent.ChangeConfirmation(it, SetupBackupWords[it]))
    }
    vm.onIntent(OnboardingIntent.ConfirmBackup)
  }

  @Test
  fun aColdLaunchResumesTheFirstSetupAndBackStillReturnsToTheStart() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(
      AccountProfile("owner_$OWNER", ownerAddress = OWNER, ownerBackupConfirmed = true, selectedSubaccount = OWNER)
    )
    val saved = preferences.values.first()
    assertEquals(LaunchStage.ONBOARDING, launchStage(saved, walletLoaded = true, unlocked = true))
    val id = assertNotNull(resumedSetup(saved))
    val wallets = SetupWallets(preferences).apply {
      profile.value = profile.value.copy(ownerAddress = OWNER, ownerBackupConfirmed = true)
    }
    val vm = viewModel(SetupAccounts().apply { awaitingDeposit = true }, HyperliquidSetup, preferences, wallets)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(OnboardingIntent.InitializeMode(RESUME, id))
    assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)
    assertFalse(vm.uiState.value.inApp)

    vm.onIntent(OnboardingIntent.Back)
    assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
    assertTrue(vm.uiState.value.showsWelcome(RESUME))
    assertTrue(preferences.values.first().profiles.isEmpty())
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
    val wallets = SetupWallets(preferences).apply {
      profile.value = profile.value.copy(ownerAddress = OWNER, ownerBackupConfirmed = true)
    }
    val vm = viewModel(SetupAccounts().apply { awaitingDeposit = true }, HyperliquidSetup, preferences, wallets)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val cancelled = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }
    vm.onIntent(OnboardingIntent.InitializeMode("CONTINUE", id))
    preferences.removeProfile(id)

    vm.onIntent(OnboardingIntent.Back)

    assertEquals(OnboardingEffect.Cancelled, cancelled.await())
  }

  @Test
  fun checkingForTheDepositShowsProgressAndWhatItFound() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    val accounts = SetupAccounts(preferences).apply { awaitingDeposit = true }
    val vm = viewModel(accounts, HyperliquidSetup, preferences, SetupWallets(preferences))
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OnboardingIntent.ShowImport)
    vm.onIntent(OnboardingIntent.ChangeInput(PHRASE))
    vm.onIntent(OnboardingIntent.ImportCredential)

    val gate = CompletableDeferred<Unit>()
    accounts.depositCheckGate = gate
    vm.onIntent(OnboardingIntent.CheckFundingNow)
    assertTrue(vm.uiState.value.busy)
    gate.complete(Unit)

    assertFalse(vm.uiState.value.busy)
    assertEquals(OnboardingStep.FUND_ACCOUNT, vm.uiState.value.step)
    assertEquals(
      "No deposit has arrived yet. It can take a few minutes after you send it.",
      vm.uiState.value.fundingNotice,
    )

    accounts.depositCheckFails = true
    vm.onIntent(OnboardingIntent.CheckFundingNow)
    assertEquals("Offline", vm.uiState.value.error)
    assertNull(vm.uiState.value.fundingNotice)
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

  /** A wallet whose setup was left at the funding step, as the app finds it. */
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
  ) =
    OnboardingViewModel(
      wallets = wallets,
      credentials = SetupCredentials,
      accounts = accounts,
      preferences = preferences,
      venue = testVenue(setup = setup),
      trading = SetupTrading,
    )

  private companion object {
    val HyperliquidSetup = VenueSetup(tradingAccounts = false, openTradingAccount = false, builderFee = false)
    const val OWNER = SETUP_OWNER
    const val PHRASE = SETUP_PHRASE
  }
}
