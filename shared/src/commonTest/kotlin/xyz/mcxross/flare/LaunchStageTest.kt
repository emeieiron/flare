package xyz.mcxross.flare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.FlarePreferences

class LaunchStageTest {
  private val finished = AccountProfile("owner_0x1", ownerAddress = "0x1", onboardingComplete = true)
  private val deferred = AccountProfile("owner_0x2", ownerAddress = "0x2", setupDeferred = true)
  private val unfinished = AccountProfile("owner_0x3", ownerAddress = "0x3")

  private fun saved(vararg profiles: AccountProfile, active: AccountProfile? = profiles.firstOrNull()) =
    FlarePreferences(activeProfileId = active?.id ?: "legacy", profiles = profiles.toList())

  @Test
  fun waitsUntilPreferencesAndTheWalletAreRead() {
    assertEquals(LaunchStage.LOADING, launchStage(null, walletLoaded = true, unlocked = true))
    assertEquals(LaunchStage.LOADING, launchStage(saved(finished), walletLoaded = false, unlocked = true))
  }

  @Test
  fun withoutAWalletThereIsOnlySetup() {
    assertEquals(LaunchStage.ONBOARDING, launchStage(saved(), walletLoaded = true, unlocked = false))
  }

  @Test
  fun anyWalletUnlocksFirst() {
    listOf(finished, deferred, unfinished).forEach {
      assertEquals(LaunchStage.LOCKED, launchStage(saved(it), walletLoaded = true, unlocked = false))
    }
  }

  @Test
  fun aWalletLeftWaitingForFundsOpensTheAppLikeAFinishedOne() {
    assertEquals(LaunchStage.READY, launchStage(saved(finished), walletLoaded = true, unlocked = true))
    assertEquals(LaunchStage.READY, launchStage(saved(deferred), walletLoaded = true, unlocked = true))
    assertEquals(LaunchStage.ONBOARDING, launchStage(saved(unfinished), walletLoaded = true, unlocked = true))
    assertEquals(
      LaunchStage.READY,
      launchStage(saved(unfinished, deferred, active = unfinished), walletLoaded = true, unlocked = true),
    )
  }

  @Test
  fun firstSetupResumesOnlyAWalletThatDoesntOpenTheApp() {
    assertEquals(unfinished.id, resumedSetup(saved(unfinished)))
    assertNull(resumedSetup(saved(deferred)))
    assertNull(resumedSetup(saved(finished)))
  }

  @Test
  fun theAppKeepsShowingItsWalletWhileAnotherIsSetUp() {
    assertEquals(deferred.id, shownWallet(saved(finished, deferred, active = deferred), finished.id))
    assertEquals(finished.id, shownWallet(saved(finished, unfinished, active = unfinished), finished.id))
    assertEquals(deferred.id, shownWallet(saved(finished, deferred, unfinished, active = unfinished), deferred.id))
    assertNull(shownWallet(saved(unfinished), null))
  }
}
