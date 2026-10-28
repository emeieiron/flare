package xyz.mcxross.flare.feature.orders

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.MarketCatalog
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.testing.SETUP_OWNER
import xyz.mcxross.flare.testing.SetupAccounts
import xyz.mcxross.flare.testing.SetupTrading
import xyz.mcxross.flare.testing.SetupWallets

@OptIn(ExperimentalCoroutinesApi::class)
class OrdersReadRecoveryTest {
  @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
  @AfterTest fun tearDown() { Dispatchers.resetMain() }

  @Test fun accountReadsRecoverAndStopWhenTheRouteLeaves() = runTest {
    var reads = 0
    val accounts = object : ReadAccounts() {
      override suspend fun restoreTrading() {
        reads++
        if (reads < 3) error("Connection lost")
        snapshot.value = snapshot.value.copy(stale = false, error = null)
      }
    }
    val vm = model(accounts)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val visible = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followHistoryWhileVisible() }
    assertFalse(vm.uiState.value.readUnavailable)
    advanceTimeBy(3_001); runCurrent()
    assertEquals(3, reads)
    assertFalse(vm.uiState.value.account.stale)
    assertFalse(vm.uiState.value.readUnavailable)

    visible.cancelAndJoin()
    accounts.snapshot.value = accounts.snapshot.value.copy(stale = true)
    advanceTimeBy(31_000); runCurrent()
    assertEquals(3, reads)
  }

  @Test fun persistentHistoryFailureIsVisibleAndRecoversWithoutAnActionRetry() = runTest {
    var reads = 0
    val accounts = object : ReadAccounts() {
      override suspend fun refreshHistory() {
        reads++
        if (reads <= 3) error("Connection lost")
        history.value = history.value.copy(stale = false, error = null)
      }
    }
    accounts.snapshot.value = accounts.snapshot.value.copy(stale = false)
    val vm = model(accounts)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    vm.onIntent(OrdersIntent.SelectSection(OrdersSection.TRADES))
    val visible = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followHistoryWhileVisible() }
    advanceTimeBy(3_001); runCurrent()
    assertEquals(3, reads)
    assertTrue(vm.uiState.value.readUnavailable)
    assertFalse(vm.uiState.value.busy)
    advanceTimeBy(4_001); runCurrent()
    assertEquals(4, reads)
    assertFalse(vm.uiState.value.history.stale)
    assertFalse(vm.uiState.value.readUnavailable)
    assertTrue(accounts.events.isEmpty())
    visible.cancelAndJoin()
  }

  @Test fun accessFailureRemainsVisibleUntilReadsRecover() = runTest {
    var authorized = false
    val accounts = object : ReadAccounts() {
      override suspend fun restoreTrading() {
        check(authorized) { "Open Flare to continue" }
        snapshot.value = snapshot.value.copy(stale = false)
      }
    }
    val vm = model(accounts)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    val visible = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followHistoryWhileVisible() }
    assertEquals("Unlock your wallet", vm.uiState.value.readAccessError)
    assertTrue(vm.uiState.value.readUnavailable)
    authorized = true
    advanceTimeBy(1_001); runCurrent()
    assertNull(vm.uiState.value.readAccessError)
    assertFalse(vm.uiState.value.readUnavailable)
    visible.cancelAndJoin()
  }

  private fun model(accounts: ReadAccounts): OrdersViewModel {
    val wallets = SetupWallets().apply {
      profile.value = profile.value.copy(ownerAddress = SETUP_OWNER)
    }
    return OrdersViewModel(accounts, wallets, SetupTrading, object : MarketsRepository {
      override val catalog = MutableStateFlow(MarketCatalog(loading = false))
      override suspend fun refresh() {}
      override suspend fun connectLive() {}
      override suspend fun toggleFavorite(marketAddress: String) {}
    }, testVenue())
  }

  private open class ReadAccounts : SetupAccounts() {
    override val snapshot = MutableStateFlow(AccountSnapshot(account = "0xa1"))
    override val history = MutableStateFlow(AccountHistorySnapshot(account = "0xa1"))
  }
}
