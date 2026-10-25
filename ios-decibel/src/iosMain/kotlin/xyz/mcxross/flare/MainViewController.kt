package xyz.mcxross.flare

import androidx.compose.ui.window.ComposeUIViewController
import xyz.mcxross.flare.security.IosWalletVault
import xyz.mcxross.flare.store.iosDatabaseBuilder
import xyz.mcxross.flare.store.iosPreferencesDataStore
import xyz.mcxross.flare.venue.decibel.DecibelRuntimeConfig
import xyz.mcxross.flare.venue.decibel.DecibelVenue

/**
 * The Decibel build's root view controller. [infoPlist] holds the app's `Flare*` Info.plist keys,
 * which each scheme's xcconfig fills in.
 */
fun MainViewController(infoPlist: Map<String, String>) = run {
  val databaseBuilder = iosDatabaseBuilder()
  val preferences = iosPreferencesDataStore()
  val walletVault = IosWalletVault()
  val venue =
    DecibelVenue(
      DecibelRuntimeConfig(workerBaseUrl = infoPlist["FlareWorkerURL"] ?: MAINNET_WORKER_URL)
    )
  ComposeUIViewController {
    App(
      venue = venue,
      databaseBuilder = databaseBuilder,
      preferences = preferences,
      walletVault = walletVault,
    )
  }
}

/** Decibel is mainnet-only, so a build with no configured Worker still reaches the real one. */
private const val MAINNET_WORKER_URL = "https://api.mcxross.xyz"
