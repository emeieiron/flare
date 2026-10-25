package xyz.mcxross.flare.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.testing.MemoryPreferences
import xyz.mcxross.flare.venue.Network

class NetworkAdoptionTest {
  @Test
  fun anInstallationWithNoMarkerCountsAsTestnet() = runTest {
    assertEquals(Network.TESTNET, AppPreferences(MemoryPreferences()).storedNetwork())
  }

  @Test
  fun adoptingMainnetDropsTheTradingAccountButKeepsTheWallet() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(
      AccountProfile(
        id = "owner_0xowner",
        ownerAddress = "0xowner",
        apiWalletAddress = "0xapi",
        ownerBackupConfirmed = true,
        selectedSubaccount = "0xsub",
        onboardingComplete = true,
        tradingAccountOpened = true,
        builderApproved = true,
        spotBuilderApproved = true,
        creationReference = "0xref",
      )
    )

    preferences.adoptNetwork(Network.MAINNET)

    val profile = preferences.values.first().profiles.single { it.id == "owner_0xowner" }
    // Unreachable on the new network, so setup has to run again.
    assertNull(profile.selectedSubaccount)
    assertNull(profile.creationReference)
    assertFalse(profile.onboardingComplete)
    assertFalse(profile.tradingAccountOpened)
    assertFalse(profile.builderApproved)
    assertFalse(profile.spotBuilderApproved)
    // The wallet is not network-specific: the owner keeps their address, backup and trading key.
    assertEquals("0xowner", profile.ownerAddress)
    assertEquals("0xapi", profile.apiWalletAddress)
    assertTrue(profile.ownerBackupConfirmed)
    assertEquals(Network.MAINNET, preferences.storedNetwork())
  }

  /**
   * The marker is what stops [adoptNetwork] running twice, so a second launch cannot wipe an
   * account opened on this network.
   */
  @Test
  fun adoptingRecordsTheNetworkSoItOnlyHappensOnce() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    assertEquals(Network.TESTNET, preferences.storedNetwork())

    preferences.adoptNetwork(Network.MAINNET)

    assertEquals(Network.MAINNET, preferences.storedNetwork())
  }
}
