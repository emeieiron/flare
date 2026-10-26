package xyz.mcxross.flare

import androidx.compose.ui.window.ComposeUIViewController
import xyz.mcxross.flare.security.IosWalletVault
import xyz.mcxross.flare.store.iosDatabaseBuilder
import xyz.mcxross.flare.store.iosPreferencesDataStore
import xyz.mcxross.flare.venue.hyperliquid.HyperliquidVenue

/**
 * The Hyperliquid build's root view controller. [infoPlist] holds the app's `Flare*` Info.plist
 * keys, which each scheme's xcconfig fills in.
 */
fun MainViewController(infoPlist: Map<String, String>) = run {
  val databaseBuilder = iosDatabaseBuilder()
  val preferences = iosPreferencesDataStore()
  val walletVault = IosWalletVault()
  val venue = HyperliquidVenue(infoPlist["FlareHyperliquidBuilderAddress"].orEmpty())
  ComposeUIViewController {
    App(
      venue = venue,
      databaseBuilder = databaseBuilder,
      preferences = preferences,
      walletVault = walletVault,
    )
  }
}
