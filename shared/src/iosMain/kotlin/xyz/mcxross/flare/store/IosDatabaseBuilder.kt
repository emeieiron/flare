package xyz.mcxross.flare.store

import androidx.room3.Room
import androidx.room3.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun iosDatabaseBuilder(): RoomDatabase.Builder<FlareDatabase> {
  val directory =
    checkNotNull(
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
  return Room.databaseBuilder<FlareDatabase>("$directory/flare.db")
}
