package xyz.mcxross.flare

import androidx.compose.ui.window.ComposeUIViewController
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.security.IosWalletVault
import xyz.mcxross.flare.store.iosDatabaseBuilder
import xyz.mcxross.flare.store.iosPreferencesDataStore

fun MainViewController(workerBaseUrl: String) = run {
  val databaseBuilder = iosDatabaseBuilder()
  val preferences = iosPreferencesDataStore()
  val walletVault = IosWalletVault()
  ComposeUIViewController {
    App(
      databaseBuilder = databaseBuilder,
      preferences = preferences,
      walletVault = walletVault,
      runtimeConfig = FlareRuntimeConfig(workerBaseUrl = workerBaseUrl),
    )
  }
}
