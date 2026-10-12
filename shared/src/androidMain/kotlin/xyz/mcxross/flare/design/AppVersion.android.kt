package xyz.mcxross.flare.design

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberAppVersion(): String {
  val context = LocalContext.current
  return remember(context) {
    val packages = context.packageManager
    val info =
      runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
          } else {
            @Suppress("DEPRECATION") packages.getPackageInfo(context.packageName, 0)
          }
        }
        .getOrNull()
    info?.versionName.orEmpty()
  }
}
