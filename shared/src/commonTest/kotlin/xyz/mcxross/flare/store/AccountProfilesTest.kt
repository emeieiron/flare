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
  fun namesSurviveRestartWithoutChangingCredentialsOrTheActiveAccount() = runTest {
    val store = MemoryPreferences()
    val preferences = AppPreferences(store)
    val first = AccountProfile("first", ownerAddress = "0x1", apiWalletAddress = "0x2",
      selectedSubaccount = "0x3", onboardingComplete = true)
    preferences.registerProfile(first)
    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x4"))

    preferences.renameWallet("first", "  Main wallet  ")
    preferences.renameTradingAccount("first", "0x03", "BTC strategy")
    preferences.renameTradingAccount("second", "0x3", "Savings")

    val saved = AppPreferences(store).values.first()
    assertEquals("second", saved.activeProfileId)
    val renamed = saved.profiles.first { it.id == "first" }
    assertEquals(first.copy(walletName = "Main wallet", tradingAccountNames = mapOf("0x3" to "BTC strategy")), renamed)
    assertEquals("BTC strategy", renamed.tradingAccountDisplayName("0X0003", "Venue name"))
    assertEquals("Savings", saved.profiles.last().tradingAccountDisplayName("0x3"))
    preferences.resetWalletName("first")
    preferences.resetTradingAccountName("first", "0X0003")
    assertEquals(first, preferences.values.first().profiles.first { it.id == "first" })
    assertEquals("Savings", preferences.values.first().profiles.last().tradingAccountDisplayName("0x3"))
  }

  @Test
  fun oldProfilesAndVenueNamesRemainValidFallbacks() = runTest {
    val preferences = AppPreferences(MemoryPreferences(preferencesOf(
      stringPreferencesKey("account_profiles_v1") to """[{"id":"first","ownerAddress":"0x1"}]""",
    )))
    val profile = preferences.values.first().profiles.single()
    assertEquals("Wallet 1", profile.walletDisplayName(0))
    assertEquals("Primary account", profile.tradingAccountDisplayName("0x3", "Primary account"))
    assertEquals("Trading account 2", profile.tradingAccountDisplayName("0x4", position = 1))
  }

  @Test
  fun invalidNamesAndRemovedWalletsCannotRewriteProfiles() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1"))
    val before = preferences.values.first()
    for (name in listOf(" ", "x".repeat(ACCOUNT_NAME_MAX_LENGTH + 1), "First\nSecond")) {
      assertFailsWith<IllegalArgumentException> { preferences.renameWallet("first", name) }
    }
    assertFailsWith<IllegalStateException> { preferences.renameWallet("removed", "Name") }
    assertEquals(before, preferences.values.first())
  }

  @Test
  fun sequencesNeverRepeatAndSurviveARestart() = runTest {
    val store = MemoryPreferences(emptyPreferences())
    val first = AppPreferences(store)

    assertEquals(1_000L, first.nextSequence("signer", floor = 1_000L))
    // The clock stood still or stepped back: still above every earlier value.
    assertEquals(1_001L, first.nextSequence("signer", floor = 1_000L))
    assertEquals(1_002L, first.nextSequence("signer", floor = 900L))
    assertEquals(5_000L, first.nextSequence("signer", floor = 5_000L))
    assertEquals(10L, first.nextSequence("other", floor = 10L))

    val restarted = AppPreferences(store)
    assertEquals(5_001L, restarted.nextSequence("signer", floor = 4_000L))
  }

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
    assertEquals(LEGACY_PROFILE_ID, preferences.values.first().profiles.single().id)
    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x4"))
    preferences.activateProfile(LEGACY_PROFILE_ID)
    val saved = preferences.values.first()
    assertEquals("0x1", saved.ownerAddress)
    assertEquals("0x2", saved.apiWalletAddress)
    assertEquals("0x3", saved.selectedSubaccount)
    assertEquals(
      WalletSecretSlot.OWNER_MNEMONIC,
      WalletSecretSlot.OWNER_MNEMONIC.forProfile(LEGACY_PROFILE_ID),
    )
    assertNull(store.data.value[stringPreferencesKey("owner_address")])
  }

  @Test
  fun removingBothKeysForgetsTheProfileAndFallsBackToTheOther() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1"))
    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x2"))
    preferences.setApiWallet("0xc")

    preferences.setOwnerWallet(null, backupConfirmed = false)
    preferences.setApiWallet(null)

    val saved = preferences.values.first()
    assertEquals(listOf("first"), saved.profiles.map(AccountProfile::id))
    assertEquals("0x1", saved.ownerAddress)
    assertFailsWith<IllegalArgumentException> { preferences.activateProfile("second") }
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

  @Test
  fun builderSupportPreferencesArePersistedPerProfile() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("first", ownerAddress = "0x1"))
    preferences.setBuilderSupport("0xbuilder1", 5, true)

    val saved1 = preferences.values.first()
    assertEquals("0xbuilder1", saved1.builderAddress)
    assertEquals(5, saved1.builderFeeBps)
    assertTrue(saved1.builderApproved)

    preferences.registerProfile(AccountProfile("second", ownerAddress = "0x2"))
    preferences.activateProfile("second")
    val saved2 = preferences.values.first()
    assertNull(saved2.builderAddress)
    assertFalse(saved2.builderApproved)

    preferences.activateProfile("first")
    preferences.setBuilderFeeBps(10)
    preferences.setBuilderAddress("0xcustom")
    val updated1 = preferences.values.first()
    assertEquals("0xcustom", updated1.builderAddress)
    assertEquals(10, updated1.builderFeeBps)
    assertTrue(updated1.builderApproved)

    preferences.setBuilderApproved(false)
    assertFalse(preferences.values.first().builderApproved)
  }

  @Test
  fun ownerProfileAddressIsSanitizedFromIdWhenCorrupted() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("owner_0xreal", ownerAddress = "0xcorrupted"))
    val saved = preferences.values.first()
    assertEquals("0xreal", saved.ownerAddress)
    assertEquals("0xreal", saved.profiles.first().ownerAddress)
  }

  @Test
  fun setOwnerWalletDoesNotOverwriteOwnerProfileAddressWithForeignAddress() = runTest {
    val preferences = AppPreferences(MemoryPreferences())
    preferences.registerProfile(AccountProfile("owner_0xreal", ownerAddress = "0xreal"))
    preferences.setOwnerWallet("0xforeign", backupConfirmed = true)
    val saved = preferences.values.first()
    assertEquals("0xreal", saved.ownerAddress)
    assertTrue(saved.ownerBackupConfirmed)
  }
}

private class MemoryPreferences(initial: Preferences = emptyPreferences()) :
  DataStore<Preferences> {
  override val data = MutableStateFlow(initial)

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
    transform(data.value).also { data.value = it }
}
