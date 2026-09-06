package xyz.mcxross.flare.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

fun androidPreferencesDataStore(context: Context): DataStore<Preferences> =
  createPreferencesDataStore {
    context.applicationContext.filesDir.resolve(PreferencesFileName).absolutePath
  }
