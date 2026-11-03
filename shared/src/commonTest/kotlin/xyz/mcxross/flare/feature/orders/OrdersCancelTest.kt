package xyz.mcxross.flare.feature.orders

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.MarketCatalog
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.testing.SETUP_OWNER
import xyz.mcxross.flare.testing.SetupAccounts
import xyz.mcxross.flare.testing.SetupWallets
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.OpenOrder
import xyz.mcxross.flare.venue.model.TradeAction

@OptIn(ExperimentalCoroutinesApi::class)
class OrdersCancelTest {
  @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

  @AfterTest fun tearDown() = Dispatchers.resetMain()

  private val order = OpenOrder("0xmarket", "7", isBuy = true, timeMs = 0)

  @Test
  fun aSentCancelLeavesTheListAtOnceAndComesBackOnlyIfItFails() = runTest {
    val accounts = OrderAccounts(order)
    val trading = SettlingTrading(ActionState.Failed("Cancel rejected"))
    val vm = model(accounts, trading)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OrdersIntent.Cancel("0xmarket", "7", isTpSl = false))
    runCurrent()
    assertTrue(vm.uiState.value.account.openOrders.isEmpty())
    assertFalse(vm.uiState.value.busy)
    trading.settle.complete(Unit)
    runCurrent()
    assertEquals(listOf(order), vm.uiState.value.account.openOrders)
    assertNotNull(vm.uiState.value.error)
  }

  @Test
  fun aLandedCancelStaysHiddenUntilTheListDropsIt() = runTest {
    val accounts = OrderAccounts(order)
    val trading = SettlingTrading(ActionState.Committed("0xhash"))
    val vm = model(accounts, trading)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OrdersIntent.Cancel("0xmarket", "7", isTpSl = false))
    trading.settle.complete(Unit)
    runCurrent()
    // The list hasn't caught up yet, and the cancelled order still doesn't come back.
    assertTrue(vm.uiState.value.account.openOrders.isEmpty())
    accounts.snapshot.value = accounts.snapshot.value.copy(openOrders = emptyList())
    runCurrent()
    assertTrue(vm.uiState.value.cancellingOrders.isEmpty())
  }

  private fun model(accounts: OrderAccounts, trading: TradingRepository) =
    OrdersViewModel(
      accounts,
      SetupWallets().apply { profile.value = profile.value.copy(ownerAddress = SETUP_OWNER) },
      trading,
      object : MarketsRepository {
        override val catalog = MutableStateFlow(MarketCatalog(loading = false))

        override suspend fun refresh() {}

        override suspend fun connectLive() {}

        override suspend fun toggleFavorite(marketAddress: String) {}
      },
      testVenue(),
    )

  private class OrderAccounts(order: OpenOrder) : SetupAccounts() {
    override val snapshot =
      MutableStateFlow(AccountSnapshot(account = "0xa1", openOrders = listOf(order), stale = false))
  }

  /** Sends at once, then lands with [outcome] when [settle] completes. */
  private class SettlingTrading(private val outcome: ActionState) : TradingRepository {
    val settle = CompletableDeferred<Unit>()

    override val pendingTransactions: Flow<List<PendingTransaction>> = MutableStateFlow(emptyList())

    override fun execute(
      action: TradeAction,
      prompt: VaultPrompt,
      feePayment: FeePayment,
      onPrepared: suspend (String) -> Unit,
    ): Flow<ActionState> = flow {
      emit(ActionState.Pending("0xpending"))
      settle.await()
      emit(outcome)
    }

    override suspend fun transactionStatus(reference: String) = ActionState.Pending(reference)

    override suspend fun tradingKeyFeeBalance() = 0uL

    override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt) = emptyFlow<ActionState>()

    override suspend fun reconcilePending() = ReconciliationResult(0, 0)
  }
}
