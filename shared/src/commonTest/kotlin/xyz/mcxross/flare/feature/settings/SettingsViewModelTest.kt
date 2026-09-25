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
import xyz.mcxross.flare.core.FlareRuntimeConfig
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
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.kaptos.account.Ed25519Account

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
      verify: suspend (Ed25519Account) -> Unit,
    ): String = "0xapi"
    override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String = "word1 word2"
    override suspend fun exportApiWallet(prompt: VaultPrompt): String = "key"
    override suspend fun removeOwner(prompt: VaultPrompt) {}
    override suspend fun removeApiWallet(prompt: VaultPrompt) {}
    override fun lock() {}
    override suspend fun <T> withOwnerAccount(
      prompt: VaultPrompt,
      block: suspend (Ed25519Account) -> T,
    ): T = error("Not supported")
    override suspend fun <T> withApiAccount(
      prompt: VaultPrompt,
      block: suspend (Ed25519Account) -> T,
    ): T = error("Not supported")
  }

  private class FakeSessionRepository : SessionRepository {
    override val status: StateFlow<SessionStatus?> = MutableStateFlow(null)
    override fun <T> bind(status: SessionStatus, operation: Flow<T>): Flow<T> = operation
    override suspend fun accessToken(): String = "token"
    override suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus =
      SessionStatus(SessionRole.OWNER, "0xowner", subaccount, 1_000_000L)
    override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus =
      SessionStatus(SessionRole.API, "0xapi", subaccount, 1_000_000L)
    override suspend fun verifyApiCredential(account: Ed25519Account, subaccount: String): SessionStatus =
      SessionStatus(SessionRole.API, "0xapi", subaccount, 1_000_000L)
    override suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus =
      SessionStatus(SessionRole.API, "0xapi", subaccount, 1_000_000L)
    override suspend fun useAnonymous(): SessionStatus =
      SessionStatus(SessionRole.ANONYMOUS, null, null, 1_000_000L)
    override suspend fun invalidate() {}
  }

  private class FakeAccountRepository : AccountRepository {
    override val snapshot: StateFlow<AccountSnapshot> = MutableStateFlow(AccountSnapshot())
    override val history: StateFlow<AccountHistorySnapshot> = MutableStateFlow(AccountHistorySnapshot())
    var withdrawCallCount = 0
    var lastWithdrawAmount = ""
    var lastWithdrawDestination: String? = null

    override suspend fun delegations(): List<Delegation> = emptyList()
    override suspend fun revokeDelegation(address: String, prompt: VaultPrompt): TransactionState =
      TransactionState.Committed("0x")
    override suspend fun restoreTrading() {}
    override suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt) {}
    override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<Subaccount> = emptyList()
    override suspend fun subaccounts(owner: String): List<Subaccount> = emptyList()
    override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) {}
    override suspend fun createSubaccount(prompt: VaultPrompt, feePayment: FeePayment): TransactionState =
      TransactionState.Committed("0xsub")
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
      timeRange: String,
      metric: String,
    ): List<xyz.mcxross.flare.decibel.model.PortfolioChartPoint> = emptyList()
    override suspend fun tradingStreak(): xyz.mcxross.flare.decibel.model.TradingStreak? = null
    override suspend fun ampsBreakdown(): xyz.mcxross.flare.decibel.model.AmpsBreakdown? = null
    override suspend fun tierInfo(): xyz.mcxross.flare.decibel.model.TierInfo? = null
    override suspend fun verifyReferralCode(code: String): xyz.mcxross.flare.decibel.model.ReferralCodeInfo? = null
    override suspend fun redeemReferralCode(code: String): Boolean = true
    override suspend fun activeTwaps(): List<xyz.mcxross.flare.decibel.model.TwapOrder> = emptyList()
    override suspend fun twapHistory(limit: Int): List<xyz.mcxross.flare.decibel.model.TwapOrder> = emptyList()
    override suspend fun vaults(limit: Int): List<xyz.mcxross.flare.decibel.model.VaultInfo> = emptyList()
    override suspend fun accountVaultPerformance(): List<xyz.mcxross.flare.decibel.model.AccountVaultPerformance> = emptyList()
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
        runtime = FlareRuntimeConfig(),
      )

    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    // Test input changes
    vm.onIntent(SettingsIntent.ChangeWithdrawDestination("0xrecipient123"))
    assertEquals("0xrecipient123", vm.uiState.value.withdrawDestination)

    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("25.5"))
    assertEquals("25.5", vm.uiState.value.withdrawAmount)

    // Test submission without destination
    vm.onIntent(SettingsIntent.ChangeWithdrawDestination(""))
    vm.onIntent(SettingsIntent.SubmitWithdraw)
    assertEquals("Enter a recipient Aptos address", vm.uiState.value.withdrawError)

    // Test submission with invalid amount
    vm.onIntent(SettingsIntent.ChangeWithdrawDestination("0xrecipient123"))
    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("0"))
    vm.onIntent(SettingsIntent.SubmitWithdraw)
    assertEquals("Enter an amount greater than zero", vm.uiState.value.withdrawError)

    // Test valid submission
    vm.onIntent(SettingsIntent.ChangeWithdrawAmount("50.0"))
    vm.onIntent(SettingsIntent.SubmitWithdraw)
    assertEquals(1, fakeAccounts.withdrawCallCount)
    assertEquals("50.0", fakeAccounts.lastWithdrawAmount)
    assertEquals("0xrecipient123", fakeAccounts.lastWithdrawDestination)
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
}
