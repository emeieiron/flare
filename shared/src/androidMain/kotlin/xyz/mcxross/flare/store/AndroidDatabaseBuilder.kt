package xyz.mcxross.flare.store

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

fun androidDatabaseBuilder(context: Context): RoomDatabase.Builder<FlareDatabase> =
  Room.databaseBuilder<FlareDatabase>(
    context = context.applicationContext,
    name = "flare.db",
  )
