package xyz.mcxross.flare.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.security.WalletSecretSlot

class AccountProfilesTest {
  @Test
  fun legacyAccountRemainsInItsOriginalSecretNamespace() = runTest {
    val store =
      MemoryPreferences(
        preferencesOf(
          stringPreferencesKey("owner_address") to "0x1",
          stringPreferencesKey("api_wallet_address") to "0x2",
          stringPreferencesKey("selected_subaccount") to "0x3",
        )
      )
    val preferences = AppPreferences(store)
    assertEquals("legacy", preferences.values.first().profiles.single().id)
    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x4"))
    preferences.activateProfile("legacy")
    val saved = preferences.values.first()
    assertEquals("0x1", saved.ownerAddress)
    assertEquals("0x2", saved.apiWalletAddress)
    assertEquals("0x3", saved.selectedSubaccount)
    assertEquals(
      WalletSecretSlot.OWNER_MNEMONIC,
      WalletSecretSlot.OWNER_MNEMONIC.forProfile("legacy"),
    )
  }

  @Test
  fun switchingPreservesEachOwnersTradingCredentialsAndSetup() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1"))
    preferences.setApiWallet("0xa")
    preferences.setSelectedSubaccount("0xb")
    preferences.setOnboardingComplete(true)
    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x2"))
    preferences.setApiWallet("0xc")
    preferences.setSelectedSubaccount("0xd")
    preferences.activateProfile("first")
    assertEquals("0xa", preferences.values.first().apiWalletAddress)
    assertEquals("0xb", preferences.values.first().selectedSubaccount)
    assertTrue(preferences.values.first().onboardingComplete)
    preferences.activateProfile("second")
    assertEquals("0xc", preferences.values.first().apiWalletAddress)
    assertFalse(preferences.values.first().onboardingComplete)
  }

  @Test
  fun withdrawalProgressSurvivesSwitchingAndPreferenceEdits() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1"))
    val continuation = WithdrawalContinuation("0x2", "0x3", "10", "0xhash", true)
    preferences.setWithdrawal("first", continuation)
    preferences.setSelectedSubaccount("0x2")
    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x4"))
    preferences.activateProfile("first")
    assertEquals(
      continuation,
      preferences.values.first().profiles.first { it.id == "first" }.withdrawal,
    )
  }
}

private class MemoryPreferences(initial: Preferences = emptyPreferences()) :
  DataStore<Preferences> {
  override val data = MutableStateFlow(initial)

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
    transform(data.value).also { data.value = it }
}
