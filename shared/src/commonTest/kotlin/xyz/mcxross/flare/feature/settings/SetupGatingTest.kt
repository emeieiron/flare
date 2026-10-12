package xyz.mcxross.flare.feature.settings

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.data.AccountSetupGate
import xyz.mcxross.flare.data.SetupNeed
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.testVenue
import xyz.mcxross.flare.testing.MemoryPreferences
import xyz.mcxross.flare.testing.SETUP_OWNER
import xyz.mcxross.flare.testing.SetupAccounts
import xyz.mcxross.flare.testing.SetupCredentials
import xyz.mcxross.flare.testing.SetupWallets
import xyz.mcxross.flare.venue.model.ActionKind

@OptIn(ExperimentalCoroutinesApi::class)
class SetupGatingTest {
  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun aWalletLeftForLaterShowsHowToFinishInPlaceOfItsActions() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(
      AccountProfile("owner_$SETUP_OWNER", ownerAddress = SETUP_OWNER, selectedSubaccount = SETUP_OWNER)
    )
    preferences.deferSetup()
    val accounts = SetupAccounts().apply { awaitingDeposit = true }
    val gate = AccountSetupGate(preferences, accounts)
    val vm = SettingsViewModel(preferences, SetupWallets(), accounts, null, testVenue(), SetupCredentials, gate)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

    assertEquals(SetupNeed.TRADING, vm.uiState.value.setupNeed)
    gate.refresh()
    assertEquals(SetupNeed.FUNDS, vm.uiState.value.setupNeed)
    ActionKind.entries.forEach {
      assertEquals(SetupNeed.FUNDS.reason, vm.uiState.value.capabilities.unavailableReason(it))
    }

    preferences.setOnboardingComplete(true)
    assertNull(vm.uiState.value.setupNeed)
    ActionKind.entries.forEach { assertNull(vm.uiState.value.capabilities.unavailableReason(it)) }
  }
}
