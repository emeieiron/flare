package xyz.mcxross.flare

import xyz.mcxross.flare.store.FlarePreferences
import xyz.mcxross.flare.store.opensApp

internal enum class LaunchStage {
  LOADING,
  LOCKED,
  ONBOARDING,
  READY,
}

/**
 * Where a launch stands. Without a wallet there's only setup; a wallet whose setup finished, or was
 * left for later while it waits for funds, opens the app once unlocked.
 */
internal fun launchStage(persisted: FlarePreferences?, walletLoaded: Boolean, unlocked: Boolean): LaunchStage =
  when {
    persisted == null || !walletLoaded -> LaunchStage.LOADING
    persisted.profiles.isNotEmpty() && !unlocked -> LaunchStage.LOCKED
    persisted.profiles.none { it.opensApp } -> LaunchStage.ONBOARDING
    else -> LaunchStage.READY
  }

/** The wallet a first setup picks up again; null when it starts a new one. */
internal fun resumedSetup(persisted: FlarePreferences?): String? =
  persisted?.profiles?.firstOrNull { it.id == persisted.activeProfileId && !it.opensApp }?.id

/**
 * The wallet the app shows. While another wallet is being set up it becomes the active one, and the
 * app keeps showing [shown], the wallet it had, rather than jumping to another.
 */
internal fun shownWallet(persisted: FlarePreferences?, shown: String?): String? {
  val profiles = persisted?.profiles.orEmpty().filter { it.opensApp }
  return (profiles.firstOrNull { it.id == persisted?.activeProfileId }
      ?: profiles.firstOrNull { it.id == shown }
      ?: profiles.firstOrNull())
    ?.id
}
