package xyz.mcxross.flare.feature.referrals

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.koin.dsl.module

/** Public mutation intent only, scoped to an owner; never a record that a referral succeeded. */
interface ReferralIntentStore {
  suspend fun code(owner: String): String?

  suspend fun save(owner: String, code: String?)
}

fun referralStorageModule() = module {
  single<ReferralIntentStore> { PreferencesReferralIntentStore(get()) }
}

private class PreferencesReferralIntentStore(private val storage: DataStore<Preferences>) :
  ReferralIntentStore {
  private fun key(owner: String) =
    stringPreferencesKey("decibel_referral_intent_${owner.lowercase()}")

  override suspend fun code(owner: String): String? = storage.data.first()[key(owner)]

  override suspend fun save(owner: String, code: String?) {
    storage.edit { if (code == null) it.remove(key(owner)) else it[key(owner)] = code }
  }
}
