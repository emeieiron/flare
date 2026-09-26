package xyz.mcxross.flare.design

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

// How many shown screens want each window kept secure. Two can overlap while one slides over the
// other, and the first to leave mustn't unprotect the second.
private val protectedWindows = mutableMapOf<Window, Int>()

@Composable
actual fun ProtectFromScreenCapture() {
  if (!LocalProtectSecrets.current) return
  val view = LocalView.current
  DisposableEffect(view) {
    val window = view.context.findActivity()?.window
    if (window != null) {
      val holds = protectedWindows[window] ?: 0
      if (holds == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
      protectedWindows[window] = holds + 1
    }
    onDispose {
      if (window != null) {
        val holds = (protectedWindows[window] ?: 1) - 1
        if (holds <= 0) {
          protectedWindows.remove(window)
          window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
          protectedWindows[window] = holds
        }
      }
    }
  }
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }
