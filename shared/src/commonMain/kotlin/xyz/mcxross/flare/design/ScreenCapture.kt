package xyz.mcxross.flare.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Whether screens showing secrets keep out of screen capture; design previews turn it off. */
val LocalProtectSecrets = staticCompositionLocalOf { true }

/**
 * Keeps the screen out of screenshots, screen recordings and the app switcher while this is shown,
 * for screens that display a secret such as a recovery phrase. Platforms that can't block capture
 * leave it alone.
 */
@Composable expect fun ProtectFromScreenCapture()
