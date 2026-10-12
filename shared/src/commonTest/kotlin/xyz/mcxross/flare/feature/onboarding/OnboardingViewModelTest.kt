package xyz.mcxross.flare.feature.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.CredentialFormat
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.venue.VenueSetup
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

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

  private fun viewModel(accounts: SetupAccounts, setup: VenueSetup) =
    OnboardingViewModel(
      wallets = SetupWallets(),
      credentials = SetupCredentials,
      accounts = accounts,
      preferences = AppPreferences(MemoryPreferences()),
      venue = testVenue(setup = setup),
      trading = SetupTrading,
    )

  private class MemoryPreferences : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
      transform(data.value).also { data.value = it }
  }

  private class SetupWallets : WalletRepository {
    override val profile = MutableStateFlow(WalletProfile())
    override val authorizationGeneration: Long = 0L

    override fun requireAuthorization(generation: Long) {}

    override suspend fun createOwner(prompt: VaultPrompt) = error("Not used")

    override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String {
      profile.value = profile.value.copy(ownerAddress = OWNER, ownerBackupConfirmed = true)
      return OWNER
    }

    override suspend fun confirmOwnerBackup() {}

    override suspend fun createApiWallet(prompt: VaultPrompt): String {
      profile.value = profile.value.copy(apiWalletAddress = "0xapi")
      return "0xapi"
    }

    override suspend fun importApiWallet(
      key: String,
      prompt: VaultPrompt,
      verify: suspend (address: String) -> Unit,
    ): String = error("Not used")

    override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String = error("Not used")

    override suspend fun exportApiWallet(prompt: VaultPrompt): String = error("Not used")

    override suspend fun removeOwner(prompt: VaultPrompt) {}

    override suspend fun removeApiWallet(prompt: VaultPrompt) {}

    override fun lock() {}
  }

  private object SetupCredentials : CredentialParser {
    override fun detect(input: String) = CredentialFormat.RECOVERY_PHRASE

    override fun normalize(input: String) = input

    override fun keyAddress(input: String): String? = null

    override fun sameAddress(first: String, second: String) = first == second

    override fun isAddress(input: String) = input.startsWith("0x")
  }

  private class SetupAccounts : AccountRepository {
    val events = mutableListOf<String>()
    val listed =
      listOf(
        TradingAccount("0xa1", OWNER, primary = true),
        TradingAccount("0xa2", OWNER),
      )
    override val snapshot: StateFlow<AccountSnapshot> = MutableStateFlow(AccountSnapshot())
    override val history: StateFlow<AccountHistorySnapshot> =
      MutableStateFlow(AccountHistorySnapshot())

    override suspend fun delegations(): List<TradingKey> = emptyList()

    override suspend fun revokeDelegation(address: String, prompt: VaultPrompt) = error("Not used")

    override suspend fun restoreTrading() {}

    override suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt) {}

    override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount> {
      events += "discover"
      return listed
    }

    override suspend fun subaccounts(owner: String): List<TradingAccount> = listed

    override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) {
      events += "select $subaccount"
    }

    override suspend fun createSubaccount(prompt: VaultPrompt, feePayment: FeePayment) =
      error("Not used")

    override suspend fun openTradingAccount(prompt: VaultPrompt): String = error("Not used")

    override suspend fun delegateApiWallet(prompt: VaultPrompt, feePayment: FeePayment) =
      error("Not used")

    override suspend fun prepareTradingWallet(prompt: VaultPrompt, feePayment: FeePayment) {
      events += "prepare"
    }

    var awaitingDeposit = false
    var approveResult: ActionState = ActionState.Committed("0x")
    var depositCheckFails = false

    override suspend fun awaitsFirstDeposit(): Boolean {
      check(!depositCheckFails) { "Offline" }
      return awaitingDeposit
    }

    override fun depositUsdc(amount: String, prompt: VaultPrompt, feePayment: FeePayment) =
      emptyFlow<ActionState>()

    override fun withdrawUsdc(
      amount: String,
      destination: String?,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ) = emptyFlow<ActionState>()

    override suspend fun approveBuilderFee(
      builderAddress: String,
      feeBps: UInt,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      product: MarketKind,
    ): ActionState {
      events += "approve builder"
      return approveResult
    }

    override suspend fun revokeBuilderFee(
      builderAddress: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      product: MarketKind,
    ) = error("Not used")

    override suspend fun portfolioChart(range: PortfolioChartRange, metric: PortfolioMetric) =
      emptyList<PortfolioPoint>()

    override suspend fun rewards(): Rewards? = null

    override suspend fun redeemReferralCode(code: String) = false

    override suspend fun activeTwaps() = emptyList<TwapOrder>()

    override suspend fun twapHistory(limit: Int) = emptyList<TwapOrder>()

    override suspend fun vaults(limit: Int) = emptyList<VaultSummary>()

    override suspend fun vaultPositions() = emptyList<VaultPosition>()

    override fun contributeToVault(
      vaultAddress: String,
      amount: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ) = emptyFlow<ActionState>()

    override fun redeemFromVault(
      vaultAddress: String,
      shares: String,
      prompt: VaultPrompt,
      feePayment: FeePayment,
    ) = emptyFlow<ActionState>()

    override suspend fun refresh() {}

    override suspend fun refreshHistory() {}

    override suspend fun loadMoreHistory(kind: AccountHistoryKind) {}

    override fun startLive() {}
  }

  private object SetupTrading : TradingRepository {
    override val pendingTransactions: Flow<List<PendingTransaction>> =
      MutableStateFlow(emptyList())

    override fun execute(
      action: TradeAction,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      onPrepared: suspend (String) -> Unit,
    ): Flow<ActionState> = emptyFlow()

    override suspend fun transactionStatus(reference: String) = ActionState.Pending(reference)

    override suspend fun tradingKeyFeeBalance() = 0uL

    override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt) = emptyFlow<ActionState>()

    override suspend fun reconcilePending() = ReconciliationResult(0, 0)
  }

  private companion object {
    const val OWNER = "0xowner"
    const val PHRASE = "abandon abandon about"
  }
}
