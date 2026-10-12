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
    // Provided by the flavor: src/<flavor>/kotlin/.../VenueEntry.kt.
    val venue = VenueEntry.venue(applicationContext)

    setContent {
      App(
        venue = venue,
        databaseBuilder = databaseBuilder,
        preferences = preferences,
        walletVault = walletVault,
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
  val venue = remember(context) { VenueEntry.venue(context) }
  App(
    venue = venue,
    databaseBuilder = databaseBuilder,
    preferences = preferences,
    walletVault = UnavailableWalletVault(),
  )
}
