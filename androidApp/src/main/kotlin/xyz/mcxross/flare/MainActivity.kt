package xyz.mcxross.flare

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.security.AndroidWalletVault
import xyz.mcxross.flare.security.UnavailableWalletVault
import xyz.mcxross.flare.store.androidDatabaseBuilder
import xyz.mcxross.flare.store.androidPreferencesDataStore

class MainActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // One splash on every Android version. The app's first frame repeats it exactly, so the splash is
    // removed at once instead of fading out through black before the app appears.
    installSplashScreen().setOnExitAnimationListener { splash -> splash.remove() }
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    val databaseBuilder = androidDatabaseBuilder(applicationContext)
    val preferences = androidPreferencesDataStore(applicationContext)
    val walletVault = AndroidWalletVault(this)
    val runtimeConfig =
      FlareRuntimeConfig(
        workerBaseUrl = getString(R.string.flare_worker_url),
        defaultBuilderAddress = getString(R.string.flare_builder_address),
      )

    setContent {
      App(
        databaseBuilder = databaseBuilder,
        preferences = preferences,
        walletVault = walletVault,
        runtimeConfig = runtimeConfig,
      )
    }
  }
}

@Preview
@Composable
fun AppAndroidPreview() {
  val context = LocalContext.current
  val databaseBuilder = remember(context) { androidDatabaseBuilder(context) }
  val preferences = remember(context) { androidPreferencesDataStore(context) }
  App(
    databaseBuilder = databaseBuilder,
    preferences = preferences,
    walletVault = UnavailableWalletVault(),
  )
}
