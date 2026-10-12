package xyz.mcxross.flare.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.testing.MemoryPreferences
import xyz.mcxross.flare.testing.SetupAccounts
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.model.ActionKind

class AccountSetupGateTest {
  private var now = 1_000L

  private suspend fun gate(
    accounts: SetupAccounts,
    profile: AccountProfile = AccountProfile("owner_0x1", ownerAddress = "0x1", setupDeferred = true),
  ): Pair<AccountSetupGate, AppPreferences> {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(profile)
    return AccountSetupGate(preferences, accounts, minIntervalMs = 15_000L) { now } to preferences
  }

  @Test
  fun aFinishedOrUnfinishedSetupHoldsNothingBack() = runTest {
    val accounts = SetupAccounts().apply { awaitingDeposit = true }
    listOf(
        AccountProfile("a", ownerAddress = "0x1", onboardingComplete = true),
        AccountProfile("b", ownerAddress = "0x1"),
      )
      .forEach { profile ->
        val (gate, _) = gate(accounts, profile)
        gate.refresh()
        assertNull(gate.need.first())
      }
    assertEquals(0, accounts.depositChecks)
  }

  @Test
  fun aWalletLeftForLaterWaitsForFundsThenForTrading() = runTest {
    val accounts = SetupAccounts().apply { awaitingDeposit = true }
    val (gate, preferences) = gate(accounts)
    // Until the venue answers, finishing setup is the way on: it checks the deposit itself.
    assertEquals(SetupNeed.TRADING, gate.need.first())

    gate.refresh()
    assertEquals(SetupNeed.FUNDS, gate.need.first())

    accounts.awaitingDeposit = false
    gate.refresh()
    assertEquals(1, accounts.depositChecks, "a check made moments ago is reused")
    assertEquals(SetupNeed.FUNDS, gate.need.first())

    now += 15_000L
    gate.refresh()
    assertEquals(2, accounts.depositChecks)
    assertEquals(SetupNeed.TRADING, gate.need.first())

    preferences.setOnboardingComplete(true)
    assertNull(gate.need.first())
  }

  @Test
  fun aFailedCheckKeepsTheLastAnswer() = runTest {
    val accounts = SetupAccounts().apply { awaitingDeposit = true }
    val (gate, _) = gate(accounts)
    gate.refresh()
    accounts.depositCheckFails = true
    now += 60_000L
    gate.refresh()
    assertEquals(SetupNeed.FUNDS, gate.need.first())
  }

  @Test
  fun everyActionWaitsBehindTheVenuesOwnReasons() {
    val venue = VenueCapabilities.All.copy(unavailableActions = mapOf(ActionKind.TWAP to "Not on this venue"))

    assertSame(venue, venue.gatedBy(null))
    val gated = venue.gatedBy(SetupNeed.FUNDS)
    assertEquals("Not on this venue", gated.unavailableReason(ActionKind.TWAP))
    listOf(ActionKind.TRADING, ActionKind.FUNDS, ActionKind.SETUP).forEach {
      assertEquals(SetupNeed.FUNDS.reason, gated.unavailableReason(it))
    }
  }
}
