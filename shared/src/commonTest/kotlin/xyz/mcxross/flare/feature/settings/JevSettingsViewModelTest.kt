package xyz.mcxross.flare.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.JevCredentials
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.WalletSecretSlot
import xyz.mcxross.flare.security.WalletVault
import xyz.mcxross.flare.store.AppPreferences

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class JevSettingsViewModelTest {
  @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
  @AfterTest fun teardown() { Dispatchers.resetMain() }

  @Test
  fun typingDoesNotSendKeyAndDismissClearsInput() = runTest {
    var checks = 0
    val vm = viewModel(JevKeyChecker { checks++; true })
    vm.onIntent(JevSettingsIntent.Open)
    vm.onIntent(JevSettingsIntent.ChangeKey("draft-key"))
    assertEquals(0, checks)
    vm.onIntent(JevSettingsIntent.Dismiss)
    assertEquals("", vm.uiState.value.keyInput)
    assertFalse(vm.uiState.value.sheetOpen)
  }

  @Test
  fun saveClearsInputAndCheckRequiresExplicitAction() = runTest {
    var checked: String? = null
    val vm = viewModel(JevKeyChecker { checked = it; true })
    vm.onIntent(JevSettingsIntent.ChangeKey("saved-key"))
    vm.onIntent(JevSettingsIntent.Save)
    assertEquals("", vm.uiState.value.keyInput)
    assertTrue(vm.uiState.value.metadata.configured)
    assertFalse(vm.uiState.value.metadata.enabled)
    vm.onIntent(JevSettingsIntent.ChangeKey("check-key"))
    assertEquals(null, checked)
    vm.onIntent(JevSettingsIntent.Check)
    assertEquals("check-key", checked)
    assertEquals("", vm.uiState.value.keyInput)
    vm.onIntent(JevSettingsIntent.Remove)
    assertFalse(vm.uiState.value.metadata.configured)
  }

  @Test
  fun dismissCancelsEnteredKeyCheckAndClearsSecretState() = runTest {
    var cleaned = false
    val vm = viewModel(JevKeyChecker {
      try { awaitCancellation() } finally { cleaned = true }
    })
    vm.onIntent(JevSettingsIntent.Open)
    vm.onIntent(JevSettingsIntent.ChangeKey("draft-key"))
    vm.onIntent(JevSettingsIntent.Check)
    assertTrue(vm.uiState.value.busy)
    vm.onIntent(JevSettingsIntent.Dismiss)
    assertTrue(cleaned)
    assertFalse(vm.uiState.value.busy)
    assertEquals("", vm.uiState.value.keyInput)
    assertFalse(vm.uiState.value.sheetOpen)
  }

  private fun viewModel(checker: JevKeyChecker): JevSettingsViewModel {
    val preferences = AppPreferences(object : DataStore<Preferences> {
      override val data = MutableStateFlow(emptyPreferences())
      override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    })
    val vault = ForegroundWalletVault(object : WalletVault {
      override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) = Unit
      override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt) = error("Unused")
      override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) = Unit
      override fun lock() = Unit
    })
    return JevSettingsViewModel(JevCredentials(vault, preferences), checker)
  }
}
