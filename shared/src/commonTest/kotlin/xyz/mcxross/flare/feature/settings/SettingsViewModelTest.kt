package xyz.mcxross.flare.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.BareCapabilities
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.RewardsRepository
import xyz.mcxross.flare.data.VaultsRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.CredentialFormat
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.SessionStatus
import xyz.mcxross.flare.data.WalletBalanceRepository
import xyz.mcxross.flare.data.WalletTokenBalance
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.venue.WithdrawalTerms
import xyz.mcxross.flare.venue.model.NetworkFee
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.SeriesPoint
import xyz.mcxross.flare.venue.model.RewardPoints
import xyz.mcxross.flare.venue.model.SelfPayOffer
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private class MemoryPreferences(initial: Preferences = emptyPreferences()) :
    DataStore<Preferences> {
    override val data = MutableStateFlow(initial)
    var failWrites = false

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      check(!failWrites) { "Storage unavailable" }
      return transform(data.value).also { data.value = it }
    }
  }

  private class FakeWalletRepository : WalletRepository {
    override val profile: Flow<WalletProfile> =
      MutableStateFlow(WalletProfile(ownerAddress = "0xowner", apiWalletAddress = "0xapi"))
    override val authorizationGeneration: Long = 0L

    override fun requireAuthorization(generation: Long) {}
    override suspend fun createOwner(prompt: VaultPrompt): OwnerBackup =
      OwnerBackup("0xowner", emptyList())
    override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String = "0xowner"
    override suspend fun confirmOwnerBackup() {}
    override suspend fun createApiWallet(prompt: VaultPrompt): String = "0xapi"
    override suspend fun importApiWallet(
      key: String,
      prompt: VaultPrompt,
      verify: suspend (address: String) -> Unit,
    ): String = "0xapi"
    override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String = "word1 word2"
    override suspend fun exportApiWallet(prompt: VaultPrompt): String = "key"
    val removed = mutableListOf<String>()
    override suspend fun removeOwner(prompt: VaultPrompt) {
      removed += "owner"
    }
    override suspend fun removeApiWallet(prompt: VaultPrompt) {
      removed += "api"
    }
    override fun lock() {}
  }

  private class FakeSessionRepository : SessionRepository {
    override val status: StateFlow<SessionStatus?> = MutableStateFlow(null)
    override fun <T> bind(status: SessionStatus, operation: Flow<T>): Flow<T> = operation
    override suspend fun accessToken(): String = "token"
    override suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus =
      SessionStatus(SessionRole.OWNER, "0xowner", subaccount, 1_000_000L)
    override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus =
      SessionStatus(SessionRole.API, "0xapi", subaccount, 1_000_000L)
    override suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus =
      SessionStatus(SessionRole.API, "0xapi", subaccount, 1_000_000L)
    override suspend fun useAnonymous(): SessionStatus =
      SessionStatus(SessionRole.ANONYMOUS, null, null, 1_000_000L)
    var invalidations = 0
    override suspend fun invalidate() {
      invalidations++
    }
  }

  private class FakeAccountRepository : AccountRepository, VaultsRepository, RewardsRepository {
    override val snapshot: StateFlow<AccountSnapshot> = MutableStateFlow(AccountSnapshot())
    override val history: StateFlow<AccountHistorySnapshot> = MutableStateFlow(AccountHistorySnapshot())
    var withdrawCallCount = 0
    var lastWithdrawAmount = ""
    var lastWithdrawDestination: String? = null
    var rewardReads = 0
    var subaccountReads = 0
    var selections = 0
    var restores = 0
    var tradingAccounts: List<TradingAccount> = emptyList()

    override suspend fun delegations(): List<TradingKey> = emptyList()
    override suspend fun revokeDelegation(address: String, prompt: VaultPrompt): ActionState =
      ActionState.Committed("0x")
    override suspend fun restoreTrading() { restores++ }
    override suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt) {}
    override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount> = emptyList()
    override suspend fun subaccounts(owner: String): List<TradingAccount> {
      subaccountReads++
      return tradingAccounts
    }
    override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) { selections++ }
    override suspend fun createSubaccount(prompt: VaultPrompt, feePayment: FeePayment): ActionState =
      ActionState.Committed("0xsub")
    override suspend fun openTradingAccount(prompt: VaultPrompt): String = "0xsub"
    override suspend fun delegateApiWallet(prompt: VaultPrompt, feePayment: FeePayment): ActionState =
      ActionState.Committed("0xdel")
    override suspend fun prepareTradingWallet(prompt: VaultPrompt, feePayment: FeePayment) {}
    override fun depositUsdc(amount: String, prompt: VaultPrompt, feePayment: FeePayment): Flow<ActionState> =
      flowOf(ActionState.Committed("0xdep"))
    override fun withdrawUsdc(
      amount: String,
      destination: String?,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<ActionState> {
      withdrawCallCount++
      lastWithdrawAmount = amount
      lastWithdrawDestination = destination
      return flowOf(ActionState.Committed("0xwithdraw_hash_123"))
    }

    val transfers = mutableListOf<Pair<String, String>>()

    override fun transferUsdc(amount: String, destination: String, prompt: VaultPrompt): Flow<ActionState> {
      transfers += amount to destination
      return flowOf(ActionState.Committed("0xsend"))
    }
    override suspend fun approveBuilderFee(
      builderAddress: String,
      feeBps: UInt,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      product: MarketKind,
    ): ActionState {
      approvals += feePayment
      return approveResult(feePayment)
    }
    val approvals = mutableListOf<FeePayment>()
    var approveResult: (FeePayment) -> ActionState = { ActionState.Committed("0x") }
    override suspend fun revokeBuilderFee(
      builderAddress: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      product: MarketKind,
    ): ActionState = ActionState.Committed("0x")
    override suspend fun refresh() {}
    override suspend fun refreshHistory() {}
    override suspend fun loadMoreHistory(kind: AccountHistoryKind) {}
    override suspend fun portfolioChart(
      range: PortfolioChartRange,
      metric: PortfolioMetric,
    ): List<SeriesPoint> = emptyList()
    override suspend fun rewards(): Rewards {
      rewardReads++
      return Rewards(points = RewardPoints(total = 120.0))
    }
    override suspend fun redeemReferralCode(code: String): Boolean = true
    override suspend fun activeTwaps(): List<TwapOrder> = emptyList()
    override suspend fun twapHistory(limit: Int): List<TwapOrder> = emptyList()
    override suspend fun vaults(limit: Int): List<VaultSummary> = emptyList()
    override suspend fun vaultPositions(): List<VaultPosition> = emptyList()
    override fun contribute(
      vaultAddress: String,
      amount: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<ActionState> = flowOf(ActionState.Committed("0xvault"))
    override fun redeem(
      vaultAddress: String,
      shares: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<ActionState> = flowOf(ActionState.Committed("0xvault"))
    override fun startLive() {}
  }

  @Test
  fun withdrawIntentValidationAndSubmission() = runTest {
    val fakeAccounts = FakeAccountRepository()
    val vm =
      SettingsViewModel(
        preferences = AppPreferences(MemoryPreferences()),
        wallets = FakeWalletRepository(),
        accounts = fakeAccounts,
        sessions = FakeSessionRepository(),
        rewardsRepository = fakeAccounts,
        venue = testVenue(),
        credentials = AddressCredentials,
      )

    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    // Test input changes
    vm.onIntent(SettingsIntent.ChangeWithdrawDestination("0xa11ce"))
    assertEquals("0xa11ce", vm.uiState.value.withdrawDestination)

    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("25.5"))
    assertEquals("25.5", vm.uiState.value.withdrawAmount)

    // Test submission without destination
    vm.onIntent(SettingsIntent.ChangeWithdrawDestination(""))
    vm.onIntent(SettingsIntent.SubmitWithdraw)
    assertEquals("Enter a valid Aptos address.", vm.uiState.value.withdrawError)

    // Test submission with invalid amount
    vm.onIntent(SettingsIntent.ChangeWithdrawDestination("0xa11ce"))
    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("0"))
    vm.onIntent(SettingsIntent.SubmitWithdraw)
    assertEquals("Enter an amount greater than zero.", vm.uiState.value.withdrawError)

    // Test valid submission
    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("50.0"))
    vm.onIntent(SettingsIntent.SubmitWithdraw)
    assertEquals(1, fakeAccounts.withdrawCallCount)
    assertEquals("50.0", fakeAccounts.lastWithdrawAmount)
    assertEquals("0xa11ce", fakeAccounts.lastWithdrawDestination)
    val tx = vm.uiState.value.withdrawTransaction
    kotlin.test.assertIs<ActionState.Committed>(tx)
    assertEquals("0xwithdraw_hash_123", tx.reference)

    // Test dismiss
    vm.onIntent(SettingsIntent.DismissWithdraw)
    assertEquals("", vm.uiState.value.withdrawDestination)
    assertEquals("", vm.uiState.value.withdrawAmount)
    assertNull(vm.uiState.value.withdrawTransaction)
    assertNull(vm.uiState.value.withdrawError)
  }

  @Test
  fun aSendOnTheVenueIsATransferAndAWithdrawalNamesItsNetwork() = runTest {
    val fakeAccounts = FakeAccountRepository()
    val venue = testVenue().copy(withdrawal = WithdrawalTerms("Arbitrum", "1", internalTransfers = true))
    val vm =
      SettingsViewModel(
        AppPreferences(MemoryPreferences()), FakeWalletRepository(), fakeAccounts, FakeSessionRepository(),
        fakeAccounts, venue, AddressCredentials,
      )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("5"))
    vm.onIntent(SettingsIntent.SubmitWithdraw)
    assertEquals("Enter a valid Arbitrum address.", vm.uiState.value.withdrawError)
    vm.onIntent(SettingsIntent.SetWithdrawOnVenue(true))
    vm.onIntent(SettingsIntent.SubmitWithdraw)
    assertEquals("Enter a valid Aptos address.", vm.uiState.value.withdrawError)

    vm.onIntent(SettingsIntent.ChangeWithdrawDestination("0xa11ce"))
    vm.onIntent(SettingsIntent.SubmitWithdraw)

    assertEquals(listOf("5" to "0xa11ce"), fakeAccounts.transfers)
    assertEquals(0, fakeAccounts.withdrawCallCount)
    assertEquals(ActionState.Committed("0xsend"), vm.uiState.value.withdrawTransaction)
    vm.onIntent(SettingsIntent.DismissWithdraw)
    assertFalse(vm.uiState.value.withdrawOnVenue)
  }

  @Test
  fun removingAKeySignsOutOfTheVenueSession() = runTest {
    val wallets = FakeWalletRepository()
    val sessions = FakeSessionRepository()
    val vm = settingsViewModel(wallets, sessions)

    vm.onIntent(SettingsIntent.RemoveApi)
    vm.onIntent(SettingsIntent.RemoveOwner)

    assertEquals(listOf("api", "owner"), wallets.removed)
    assertEquals(2, sessions.invalidations)
    assertNull(vm.uiState.value.error)
  }

  @Test
  fun removingAKeyWorksForVenuesWithoutASession() = runTest {
    val wallets = FakeWalletRepository()
    val vm = settingsViewModel(wallets, sessions = null)

    vm.onIntent(SettingsIntent.RemoveApi)

    assertEquals(listOf("api"), wallets.removed)
    assertNull(vm.uiState.value.error)
  }

  @Test
  fun venueSettingsComeFromTheDescriptor() = runTest {
    val accounts = FakeAccountRepository()
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("owner", ownerAddress = "0xowner"))
    val vm =
      SettingsViewModel(
        preferences,
        FakeWalletRepository(),
        accounts,
        null,
        accounts,
        testVenue(),
        AddressCredentials,
      )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    assertEquals("https://proxy.test", vm.uiState.value.proxyUrl)
    assertEquals("0xb0b", vm.uiState.value.defaultBuilderAddress)
    assertEquals(120.0, vm.uiState.value.rewards?.points?.total)
    assertEquals(1, accounts.rewardReads)
    assertEquals(1, accounts.subaccountReads)
  }

  @Test
  fun venuesWithoutRewardsOrTradingAccountsNeverLoadThem() = runTest {
    val accounts = FakeAccountRepository()
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("owner", ownerAddress = "0xowner"))
    val vm =
      SettingsViewModel(
        preferences,
        FakeWalletRepository(),
        accounts,
        null,
        null,
        testVenue(BareCapabilities),
        AddressCredentials,
      )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(SettingsIntent.LoadSubaccounts)

    assertEquals(BareCapabilities, vm.uiState.value.capabilities)
    assertNull(vm.uiState.value.rewards)
    assertEquals(0, accounts.rewardReads)
    assertEquals(0, accounts.subaccountReads)
  }

  @Test
  fun anUnsponsoredBuilderApprovalWaitsForTheWalletToPay() = runTest {
    val accounts = FakeAccountRepository()
    accounts.approveResult = { feePayment ->
      if (feePayment == FeePayment.SPONSORED)
        ActionState.Failed(
          "Fee sponsorship is unavailable. You can choose to pay fees with APT.",
          reference = "sponsor:0x1",
          definitelyNotSubmitted = true,
          selfPay = SelfPayOffer(200uL, 2_000uL),
        )
      else ActionState.Committed("0xapproved")
    }
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("owner", ownerAddress = "0xowner"))
    val vm =
      SettingsViewModel(preferences, FakeWalletRepository(), accounts, null, accounts, testVenue(), AddressCredentials)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(SettingsIntent.SetBuilderFeeBps(MarketKind.PERP, 5))
    assertEquals(BuilderSelfPay(MarketKind.PERP, 5, 200uL), vm.uiState.value.builderSelfPay)
    assertNull(vm.uiState.value.error)

    vm.onIntent(SettingsIntent.ConfirmBuilderSelfPay)
    assertEquals(listOf(FeePayment.SPONSORED, FeePayment.SELF_PAY), accounts.approvals)
    assertNull(vm.uiState.value.builderSelfPay)
    assertNull(vm.uiState.value.error)
    assertEquals(5, preferences.values.first().builderFeeBps)
  }

  @Test
  fun anAbortedBuilderApprovalExplainsItselfWithoutOfferingSelfPay() = runTest {
    val accounts = FakeAccountRepository()
    accounts.approveResult = {
      ActionState.Failed(
        "Move abort in 0xd368::dex_accounts_entry: EBUILDER_SUBACCOUNT_NOT_FOUND(0x1):",
        definitelyNotSubmitted = false,
      )
    }
    val vm =
      SettingsViewModel(
        AppPreferences(MemoryPreferences()), FakeWalletRepository(), accounts, null, accounts, testVenue(),
        AddressCredentials,
      )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(SettingsIntent.SetBuilderFeeBps(MarketKind.PERP, 5))
    assertNull(vm.uiState.value.builderSelfPay)
    assertEquals(
      "Your builder fee setting didn’t change. Flare’s builder account isn’t set up on the exchange yet.",
      vm.uiState.value.error,
    )
    assertEquals(listOf(FeePayment.SPONSORED), accounts.approvals)
  }

  @Test
  fun renamingUsesTheCapturedTargetAndNeverSwitchesOrRestoresAccounts() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1", selectedSubaccount = "0x3"))
    val accounts = FakeAccountRepository()
    val vm = SettingsViewModel(preferences, FakeWalletRepository(), accounts, null, null, testVenue(), AddressCredentials)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(SettingsIntent.RenameWallet("first"))
    assertEquals("Wallet 1", vm.uiState.value.nameEditor?.name)
    assertFalse(vm.uiState.value.nameEditor!!.canSave)
    vm.onIntent(SettingsIntent.ChangeAccountName("Main wallet"))
    vm.onIntent(SettingsIntent.SaveAccountName)
    assertNull(vm.uiState.value.nameEditor)

    vm.onIntent(SettingsIntent.RenameTradingAccount("first", "0x3"))
    vm.onIntent(SettingsIntent.ChangeAccountName("BTC strategy"))
    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x2", selectedSubaccount = "0x4"))
    vm.onIntent(SettingsIntent.SaveAccountName)

    val saved = preferences.values.first()
    assertEquals("second", saved.activeProfileId)
    assertEquals("0x4", saved.selectedSubaccount)
    val first = saved.profiles.first { it.id == "first" }
    assertEquals("Main wallet", first.walletName)
    assertEquals("BTC strategy", first.tradingAccountNames["0x3"])
    assertEquals(0, accounts.selections)
    assertEquals(0, accounts.restores)
    assertEquals(1, accounts.subaccountReads)
    assertNull(vm.uiState.value.nameEditor)
  }

  @Test
  fun cancellingAndInvalidNamesLeaveSavedNamesUntouched() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1", walletName = "Main wallet"))
    val vm = SettingsViewModel(preferences, FakeWalletRepository(), FakeAccountRepository(), null, null, testVenue(), AddressCredentials)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(SettingsIntent.RenameWallet("first"))
    vm.onIntent(SettingsIntent.ChangeAccountName(" "))
    assertEquals("Enter a name", vm.uiState.value.nameEditor?.error)
    vm.onIntent(SettingsIntent.SaveAccountName)
    assertEquals("Main wallet", preferences.values.first().profiles.single().walletName)
    vm.onIntent(SettingsIntent.ChangeAccountName("Unsaved name"))
    vm.onIntent(SettingsIntent.DismissAccountName)
    assertNull(vm.uiState.value.nameEditor)
    assertEquals("Main wallet", preferences.values.first().profiles.single().walletName)
  }

  @Test
  fun failedSaveKeepsTheDraftForRetry() = runTest {
    val store = MemoryPreferences()
    val preferences = AppPreferences(store)
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1"))
    val vm = SettingsViewModel(preferences, FakeWalletRepository(), FakeAccountRepository(), null, null, testVenue(), AddressCredentials)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(SettingsIntent.RenameWallet("first"))
    vm.onIntent(SettingsIntent.ChangeAccountName("Main wallet"))
    store.failWrites = true
    vm.onIntent(SettingsIntent.SaveAccountName)
    assertEquals("Main wallet", vm.uiState.value.nameEditor?.name)
    assertFalse(vm.uiState.value.nameEditor!!.saving)
    assertEquals("The name couldn’t be saved. Try again.", vm.uiState.value.nameEditor?.error)
    store.failWrites = false
    vm.onIntent(SettingsIntent.SaveAccountName)
    assertNull(vm.uiState.value.nameEditor)
    assertEquals("Main wallet", preferences.values.first().profiles.single().walletName)
  }

  @Test
  fun usingTheVenueNameOnlyClearsTheLocalNicknameAfterSave() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1", selectedSubaccount = "0x3"))
    preferences.renameTradingAccount("first", "0x3", "BTC strategy")
    val accounts = FakeAccountRepository().apply {
      tradingAccounts = listOf(TradingAccount("0x3", "0x1", label = "Primary account"))
    }
    val vm = SettingsViewModel(preferences, FakeWalletRepository(), accounts, null, null, testVenue(), AddressCredentials)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(SettingsIntent.RenameTradingAccount("first", "0x3"))
    assertEquals("BTC strategy", vm.uiState.value.nameEditor?.name)
    vm.onIntent(SettingsIntent.UseDefaultAccountName)
    assertEquals("Primary account", vm.uiState.value.nameEditor?.name)
    vm.onIntent(SettingsIntent.DismissAccountName)
    assertEquals("BTC strategy", preferences.values.first().profiles.single().tradingAccountNames["0x3"])
    vm.onIntent(SettingsIntent.RenameTradingAccount("first", "0x3"))
    vm.onIntent(SettingsIntent.UseDefaultAccountName)
    vm.onIntent(SettingsIntent.SaveAccountName)
    assertEquals(emptyMap(), preferences.values.first().profiles.single().tradingAccountNames)
    assertEquals("0x3", preferences.values.first().selectedSubaccount)
    assertEquals(0, accounts.selections)
  }

  private class FakeWalletBalances : WalletBalanceRepository {
    override val symbols = listOf("APT", "USDC")
    val reads = mutableListOf<Pair<String, String>>()
    var read: suspend (String, String) -> String = { _, symbol -> if (symbol == "APT") "8.67285184" else "0" }
    override suspend fun balance(address: String, symbol: String): String {
      reads += address to symbol
      return read(address, symbol)
    }
  }

  private fun balanceViewModel(preferences: AppPreferences, balances: WalletBalanceRepository) =
    SettingsViewModel(preferences, FakeWalletRepository(), FakeAccountRepository(), null, null,
      testVenue(), AddressCredentials, walletBalances = balances)

  @Test
  fun walletBalancesUseTheWalletAddressAndOnlyPollWhileTheAccountRouteIsVisible() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1", apiWalletAddress = "0x2", selectedSubaccount = "0x3"))
    val balances = FakeWalletBalances()
    val vm = balanceViewModel(preferences, balances)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    assertEquals(0, balances.reads.size)
    val visible = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followWalletBalances() }
    runCurrent()
    assertEquals(listOf("0x1", "0x1"), balances.reads.map { it.first })
    assertEquals("8.67285184", vm.uiState.value.walletBalances.first().amount)
    assertEquals("0.00", vm.uiState.value.walletBalances.last().displayAmount)
    preferences.renameWallet("first", "Main")
    runCurrent()
    assertEquals(2, balances.reads.size)
    advanceTimeBy(15_001)
    runCurrent()
    assertEquals(4, balances.reads.size)
    visible.cancel()
    runCurrent()
    advanceTimeBy(30_000)
    runCurrent()
    assertEquals(4, balances.reads.size)
  }

  @Test
  fun aFailedTokenReadDoesNotHideAnotherTokenOrInventZeroAndCanRetry() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1"))
    val balances = FakeWalletBalances().apply {
      read = { _, symbol -> if (symbol == "APT") "8.67285184" else error("Offline") }
    }
    val vm = balanceViewModel(preferences, balances)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followWalletBalances() }
    runCurrent()
    assertEquals("8.67285184", vm.uiState.value.walletBalances.first().amount)
    assertNull(vm.uiState.value.walletBalances.last().amount)
    assertEquals("—", vm.uiState.value.walletBalances.last().displayAmount)
    assertEquals(true, vm.uiState.value.walletBalances.last().unavailable)
    balances.read = { _, symbol -> if (symbol == "APT") error("Offline") else "0" }
    vm.onIntent(SettingsIntent.RefreshWalletBalances)
    runCurrent()
    assertEquals("8.67285184", vm.uiState.value.walletBalances.first().amount)
    assertEquals(true, vm.uiState.value.walletBalances.first().unavailable)
    assertEquals("0.00", vm.uiState.value.walletBalances.last().displayAmount)
    assertFalse(vm.uiState.value.walletBalances.last().unavailable)
  }

  @Test
  fun switchingWalletsDiscardsLateResponsesFromThePreviousWallet() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1"))
    val release = CompletableDeferred<Unit>()
    val balances = FakeWalletBalances().apply {
      read = { address, _ ->
        if (address == "0x1") withContext(NonCancellable) { release.await(); "999" } else "2"
      }
    }
    val vm = balanceViewModel(preferences, balances)
    val observed = mutableListOf<SettingsUiState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { observed += it } }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followWalletBalances() }
    runCurrent()
    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x4"))
    runCurrent()
    release.complete(Unit)
    runCurrent()
    assertEquals("0x4", vm.uiState.value.walletBalanceAddress)
    assertEquals(listOf("2", "2"), vm.uiState.value.walletBalances.map { it.amount })
    assertFalse(observed.any { state -> state.walletBalances.any { it.amount == "999" } })
  }

  @Test
  fun anApiOnlyWalletUsesItsOwnAddressAndRemovingItClearsTheBalances() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("api", apiWalletAddress = "0x2", selectedSubaccount = "0x3"))
    val balances = FakeWalletBalances()
    val vm = balanceViewModel(preferences, balances)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followWalletBalances() }
    runCurrent()
    assertEquals(listOf("0x2", "0x2"), balances.reads.map { it.first })
    preferences.setApiWallet(null)
    runCurrent()
    assertNull(vm.uiState.value.walletBalanceAddress)
    assertEquals(emptyList(), vm.uiState.value.walletBalances)
    advanceTimeBy(30_000)
    runCurrent()
    assertEquals(2, balances.reads.size)
  }

  @Test
  fun walletBalanceFormattingPreservesSmallAmountsAndTokenPrecision() {
    assertEquals("<0.01", WalletTokenBalance("APT", "0.00000001").displayAmount)
    assertEquals("8.67", WalletTokenBalance("APT", "8.67285184").displayAmount)
    assertEquals("10.00", WalletTokenBalance("APT", "9.99999999").displayAmount)
    assertEquals("0.01", WalletTokenBalance("APT", "0.01").displayAmount)
    assertEquals("0.00", WalletTokenBalance("APT", "0").displayAmount)
    assertEquals("184467440737.10", WalletTokenBalance("APT", "184467440737.09551615").displayAmount)
    assertEquals("1.234567", WalletTokenBalance("USDC", "1.234567").displayAmount)
    assertEquals("0.00", WalletTokenBalance("USDC", "0").displayAmount)
    assertEquals("—", WalletTokenBalance("USDC").displayAmount)
  }

  @Test
  fun withdrawalFeesResolveImmediatelyForMaxAndRejectOldAccountResults() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1", selectedSubaccount = "0x3"))
    val gate = CompletableDeferred<Unit>()
    val reads = mutableListOf<String>()
    val accounts = object : AccountRepository by FakeAccountRepository() {
      override suspend fun estimateFundingFee(amount: String, withdrawing: Boolean): NetworkFee {
        reads += amount
        if (amount == "2") withContext(NonCancellable) { gate.await() }
        return NetworkFee(amount.toULong() * 100uL, 5_000uL)
      }
    }
    val vm = SettingsViewModel(preferences, FakeWalletRepository(), accounts, null, null, testVenue(), AddressCredentials)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    runCurrent()
    vm.onIntent(SettingsIntent.OpenWithdraw)
    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("1", immediate = true)); runCurrent()
    assertEquals(NetworkFee(100uL, 5_000uL), vm.uiState.value.withdrawNetworkFee)
    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("1.0")); runCurrent()
    assertEquals(listOf("1"), reads)
    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("2")); advanceTimeBy(176); runCurrent()
    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x4", selectedSubaccount = "0x5")); runCurrent()
    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("3", immediate = true)); runCurrent()
    gate.complete(Unit); runCurrent()
    assertEquals(NetworkFee(300uL, 5_000uL), vm.uiState.value.withdrawNetworkFee)
    vm.onIntent(SettingsIntent.DismissWithdraw); runCurrent()
    assertNull(vm.uiState.value.withdrawNetworkFee)
  }

  private fun settingsViewModel(wallets: WalletRepository, sessions: SessionRepository?) =
    SettingsViewModel(
      preferences = AppPreferences(MemoryPreferences()),
      wallets = wallets,
      accounts = FakeAccountRepository(),
      sessions = sessions,
      rewardsRepository = FakeAccountRepository(),
      venue = testVenue(),
      credentials = AddressCredentials,
    )

  /** Accepts `0x` addresses; nothing here parses keys. */
  private object AddressCredentials : CredentialParser {
    override fun detect(input: String) = CredentialFormat.UNKNOWN

    override fun normalize(input: String) = input

    override fun keyAddress(input: String): String? = null

    override fun sameAddress(first: String, second: String) = first == second

    override fun isAddress(input: String) = input.trim().matches(Regex("0x[0-9a-fA-F]+"))
  }
}
