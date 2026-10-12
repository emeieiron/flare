package xyz.mcxross.flare.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.BareCapabilities
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.SessionStatus
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.RewardPoints
import xyz.mcxross.flare.venue.model.Rewards
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

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
      transform(data.value).also { data.value = it }
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

  private class FakeAccountRepository : AccountRepository {
    override val snapshot: StateFlow<AccountSnapshot> = MutableStateFlow(AccountSnapshot())
    override val history: StateFlow<AccountHistorySnapshot> = MutableStateFlow(AccountHistorySnapshot())
    var withdrawCallCount = 0
    var lastWithdrawAmount = ""
    var lastWithdrawDestination: String? = null
    var rewardReads = 0
    var subaccountReads = 0

    override suspend fun delegations(): List<TradingKey> = emptyList()
    override suspend fun revokeDelegation(address: String, prompt: VaultPrompt): TransactionState =
      TransactionState.Committed("0x")
    override suspend fun restoreTrading() {}
    override suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt) {}
    override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<Subaccount> = emptyList()
    override suspend fun subaccounts(owner: String): List<Subaccount> {
      subaccountReads++
      return emptyList()
    }
    override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) {}
    override suspend fun createSubaccount(prompt: VaultPrompt, feePayment: FeePayment): TransactionState =
      TransactionState.Committed("0xsub")
    override suspend fun openTradingAccount(prompt: VaultPrompt): String = "0xsub"
    override suspend fun delegateApiWallet(prompt: VaultPrompt, feePayment: FeePayment): TransactionState =
      TransactionState.Committed("0xdel")
    override suspend fun prepareTradingWallet(prompt: VaultPrompt, feePayment: FeePayment) {}
    override fun depositUsdc(amount: String, prompt: VaultPrompt, feePayment: FeePayment): Flow<TransactionState> =
      flowOf(TransactionState.Committed("0xdep"))
    override fun withdrawUsdc(
      amount: String,
      destination: String?,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<TransactionState> {
      withdrawCallCount++
      lastWithdrawAmount = amount
      lastWithdrawDestination = destination
      return flowOf(TransactionState.Committed("0xwithdraw_hash_123"))
    }
    override suspend fun approveBuilderFee(
      builderAddress: String,
      feeBps: UInt,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      product: AssetType,
    ): TransactionState = TransactionState.Committed("0x")
    override suspend fun revokeBuilderFee(
      builderAddress: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      product: AssetType,
    ): TransactionState = TransactionState.Committed("0x")
    override suspend fun refresh() {}
    override suspend fun refreshHistory() {}
    override suspend fun loadMoreHistory(kind: AccountHistoryKind) {}
    override suspend fun portfolioChart(
      range: PortfolioChartRange,
      metric: PortfolioMetric,
    ): List<PortfolioPoint> = emptyList()
    override suspend fun rewards(): Rewards {
      rewardReads++
      return Rewards(points = RewardPoints(total = 120.0))
    }
    override suspend fun redeemReferralCode(code: String): Boolean = true
    override suspend fun activeTwaps(): List<TwapOrder> = emptyList()
    override suspend fun twapHistory(limit: Int): List<TwapOrder> = emptyList()
    override suspend fun vaults(limit: Int): List<VaultSummary> = emptyList()
    override suspend fun vaultPositions(): List<VaultPosition> = emptyList()
    override fun contributeToVault(
      vaultAddress: String,
      amount: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<TransactionState> = flowOf(TransactionState.Committed("0xvault"))
    override fun redeemFromVault(
      vaultAddress: String,
      shares: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ): Flow<TransactionState> = flowOf(TransactionState.Committed("0xvault"))
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
        venue = testVenue(),
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
    kotlin.test.assertIs<TransactionState.Committed>(tx)
    assertEquals("0xwithdraw_hash_123", tx.hash)

    // Test dismiss
    vm.onIntent(SettingsIntent.DismissWithdraw)
    assertEquals("", vm.uiState.value.withdrawDestination)
    assertEquals("", vm.uiState.value.withdrawAmount)
    assertNull(vm.uiState.value.withdrawTransaction)
    assertNull(vm.uiState.value.withdrawError)
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
      SettingsViewModel(preferences, FakeWalletRepository(), accounts, null, testVenue())
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
        testVenue(BareCapabilities),
      )
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    vm.onIntent(SettingsIntent.LoadSubaccounts)

    assertEquals(BareCapabilities, vm.uiState.value.capabilities)
    assertNull(vm.uiState.value.rewards)
    assertEquals(0, accounts.rewardReads)
    assertEquals(0, accounts.subaccountReads)
  }

  private fun settingsViewModel(wallets: WalletRepository, sessions: SessionRepository?) =
    SettingsViewModel(
      preferences = AppPreferences(MemoryPreferences()),
      wallets = wallets,
      accounts = FakeAccountRepository(),
      sessions = sessions,
      venue = testVenue(),
    )
}
