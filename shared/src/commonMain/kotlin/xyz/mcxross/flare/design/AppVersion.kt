package xyz.mcxross.flare.design

import androidx.compose.runtime.Composable

/** The installed app's version name, as the platform reports it. */
@Composable
expect fun rememberAppVersion(): String
