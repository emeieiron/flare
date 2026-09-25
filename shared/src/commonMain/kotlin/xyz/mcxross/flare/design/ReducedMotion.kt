package xyz.mcxross.flare.design

import androidx.compose.runtime.Composable

/** True when the person has asked the system to minimise motion; decorative animation should rest. */
@Composable
expect fun rememberReducedMotion(): Boolean
