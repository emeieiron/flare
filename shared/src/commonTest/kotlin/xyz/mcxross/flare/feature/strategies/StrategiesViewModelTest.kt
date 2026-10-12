package xyz.mcxross.flare.feature.strategies

import androidx.lifecycle.ViewModelStore
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.security.*
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.strategies.*
import xyz.mcxross.flare.testing.MemoryPreferences
import xyz.mcxross.flare.testing.SetupWallets
import xyz.mcxross.flare.venue.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StrategiesViewModelTest {
  private val descriptor = VenueDescriptor("test", "Test", Network.TESTNET, VenueChain("Test", "TEST", 8), VenueCapabilities.All, VenueExplorer("Test", "https://example.test/tx/{reference}"))
  private val now = 12_000_000L

  private fun snapshot(key: StrategyMarketKey) = StrategyMarketSnapshot(key, now,
    (0 until 100).map { index ->
      val open = now - (100 - index) * 60_000
      StrategyCandle(open, open + 59_999, 100.0, 101.0, 99.0, 100.0, 10.0)
    })

  private class Secrets : WalletVault {
    private var value = byteArrayOf()
    override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) { value = secret.copyOf() }
    override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt) = value.copyOf()
    override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) { value = byteArrayOf() }
    override fun lock() = Unit
  }

  @Test
  fun optionalModelOffNeedsNoKeyAndProviderFailureRetainsBaseline() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val store = ViewModelStore()
    try {
      val preferences = AppPreferences(MemoryPreferences())
      val credentials = JevCredentials(ForegroundWalletVault(Secrets()), preferences)
      var calls = 0
      val vm = StrategiesViewModel(StrategyMarketFeed(::snapshot), preferences, credentials, SetupWallets(),
        DecisionModel { calls++; error("unavailable") }, descriptor, { now })
      store.put("vm", vm)
      runCurrent()
      vm.onIntent(StrategiesIntent.Bind("BTC", 60_000, "account"))
      vm.onIntent(StrategiesIntent.Resume)
      vm.onIntent(StrategiesIntent.Evaluate)
      runCurrent()
      assertNotNull(vm.uiState.value.baseline)
      assertEquals(0, calls)
      credentials.store("key")
      credentials.setEnabled(true)
      runCurrent()
      vm.onIntent(StrategiesIntent.Evaluate)
      runCurrent()
      assertNotNull(vm.uiState.value.baseline)
      assertEquals(1, calls)
      assertNull(vm.uiState.value.confluence)
      assertNotNull(vm.uiState.value.confluenceError)
    } finally { store.clear(); Dispatchers.resetMain() }
  }

  @Test
  fun lateResultsAreDiscardedForMarketTimeframeAccountProfilePauseAndCredentials() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      for (change in 0..5) {
        val store = ViewModelStore()
        val preferences = AppPreferences(MemoryPreferences())
        val credentials = JevCredentials(ForegroundWalletVault(Secrets()), preferences)
        val wallets = SetupWallets()
        val pending = CompletableDeferred<Unit>()
        val vm = StrategiesViewModel(StrategyMarketFeed { key ->
          withContext(NonCancellable) { pending.await() }; snapshot(key)
        }, preferences, credentials, wallets, DecisionModel { error("must not call") }, descriptor, { now })
        store.put("vm", vm)
        runCurrent()
        vm.onIntent(StrategiesIntent.Bind("BTC", 60_000, "account"))
        vm.onIntent(StrategiesIntent.Resume)
        vm.onIntent(StrategiesIntent.Evaluate)
        runCurrent()
        when (change) {
          0 -> vm.onIntent(StrategiesIntent.Bind("ETH", 60_000, "account"))
          1 -> vm.onIntent(StrategiesIntent.Bind("BTC", 300_000, "account"))
          2 -> vm.onIntent(StrategiesIntent.Bind("BTC", 60_000, "other"))
          3 -> wallets.profile.value = WalletProfile(ownerAddress = "other")
          4 -> vm.onIntent(StrategiesIntent.Pause)
          else -> credentials.store("replacement")
        }
        runCurrent()
        pending.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.baseline, "change $change")
        assertFalse(vm.uiState.value.working)
        store.clear()
      }
    } finally { Dispatchers.resetMain() }
  }

  @Test
  fun displayedSignalsExpireAndMismatchedSnapshotNeverAppears() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val store = ViewModelStore()
    try {
      val preferences = AppPreferences(MemoryPreferences())
      val credentials = JevCredentials(ForegroundWalletVault(Secrets()), preferences)
      var mismatch = false
      val vm = StrategiesViewModel(StrategyMarketFeed { key -> snapshot(if (mismatch) key.copy(marketId = "ETH") else key) },
        preferences, credentials, SetupWallets(), DecisionModel { error("off") }, descriptor, { now + testScheduler.currentTime })
      store.put("vm", vm)
      runCurrent()
      vm.onIntent(StrategiesIntent.Bind("BTC", 60_000, null))
      vm.onIntent(StrategiesIntent.Resume)
      vm.onIntent(StrategiesIntent.Evaluate)
      runCurrent()
      assertNotNull(vm.uiState.value.baseline)
      advanceTimeBy(60_001); runCurrent()
      assertNull(vm.uiState.value.baseline)
      assertNotNull(vm.uiState.value.error)
      mismatch = true
      vm.onIntent(StrategiesIntent.Evaluate); runCurrent()
      assertNull(vm.uiState.value.baseline)
      assertNotNull(vm.uiState.value.error)
    } finally { store.clear(); Dispatchers.resetMain() }
  }
}
