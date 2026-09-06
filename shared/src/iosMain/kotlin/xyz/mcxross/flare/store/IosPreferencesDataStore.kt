package xyz.mcxross.flare.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun iosPreferencesDataStore(): DataStore<Preferences> = createPreferencesDataStore {
  val directory =
    requireNotNull(
      NSFileManager.defaultManager
        .URLForDirectory(
          directory = NSApplicationSupportDirectory,
          inDomain = NSUserDomainMask,
          appropriateForURL = null,
          create = true,
          error = null,
        )
        ?.path
    ) {
      "Application Support directory is unavailable"
    }
  "$directory/$PreferencesFileName"
}
