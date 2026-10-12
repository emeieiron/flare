package xyz.mcxross.flare.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.store.AppPreferences

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class JevCredentialsTest {
  @Test
  fun savesOnlyPresenceAndExplicitEnablementInPreferences() = runTest {
    val store = MetadataStore()
    val preferences = AppPreferences(store)
    val platform = SecretVault()
    val credentials = JevCredentials(ForegroundWalletVault(platform), preferences)
    credentials.store("test-secret-value")
    assertEquals("test-secret-value", platform.secrets[WalletSecretSlot.JEV_API_KEY]?.decodeToString())
    assertTrue(credentials.metadata.first().configured)
    assertFalse(credentials.metadata.first().enabled)
    assertTrue(store.data.value.asMap().values.all { it is Boolean })
    assertFalse(store.data.value.toString().contains("test-secret-value"))
    credentials.setEnabled(true)
    assertEquals("test-secret-value", credentials.withKey { it })
    credentials.remove()
    assertFalse(credentials.metadata.first().configured)
    assertFalse(credentials.metadata.first().enabled)
    assertTrue(platform.secrets.isEmpty())
  }

  @Test
  fun optionalCorruptKeyDoesNotLockWalletAndClearsConfiguredMetadata() = runTest {
    val preferences = AppPreferences(MetadataStore())
    preferences.setJevKeyConfigured(true)
    preferences.setUseJevConfluence(true)
    val vault = ForegroundWalletVault(SecretVault())
    vault.unlock(emptyList(), VaultPrompt("Open", "Open"))
    val credentials = JevCredentials(vault, preferences)
    credentials.preload()
    assertTrue(vault.unlocked.value)
    assertFalse(credentials.metadata.first().configured)
    assertFalse(credentials.metadata.first().enabled)
  }

  @Test
  fun preloadRecoversConfiguredKeyWithoutRepeatedPlatformReads() = runTest {
    val preferences = AppPreferences(MetadataStore())
    val platform = SecretVault()
    val vault = ForegroundWalletVault(platform)
    val credentials = JevCredentials(vault, preferences)
    credentials.store("stored-key")
    credentials.setEnabled(true)
    vault.lock()
    vault.unlock(emptyList(), VaultPrompt("Open", "Open"))
    credentials.preload()
    assertEquals(1, platform.reads)
    repeat(2) { assertEquals("stored-key", credentials.withKey { it }) }
    assertEquals(1, platform.reads)
  }

  @Test
  fun exploringRestoresSavedKeyOnlyOnExplicitEvaluation() = runTest {
    val preferences = AppPreferences(MetadataStore())
    val platform = SecretVault()
    val vault = ForegroundWalletVault(platform)
    val credentials = JevCredentials(vault, preferences)
    credentials.store("explore-key")
    credentials.setEnabled(true)
    vault.lock()
    assertFalse(vault.unlocked.value)
    assertEquals(0, platform.reads)
    assertEquals("explore-key", credentials.withKey { it })
    assertTrue(vault.unlocked.value)
    assertEquals(1, platform.reads)
    assertEquals("explore-key", credentials.withKey { it })
    assertEquals(1, platform.reads)
  }

  @Test
  fun exploringRecoversMissingStoredKeyWithoutRetainingEnabledFlag() = runTest {
    val preferences = AppPreferences(MetadataStore())
    preferences.setJevKeyConfigured(true)
    preferences.setUseJevConfluence(true)
    val credentials = JevCredentials(ForegroundWalletVault(SecretVault()), preferences)
    assertFailsWith<WalletVaultException.Unavailable> { credentials.withKey { it } }
    assertFalse(credentials.metadata.first().configured)
    assertFalse(credentials.metadata.first().enabled)
  }

  @Test
  fun backgroundReplacementRemovalAndDisableCancelRequests() = runTest {
    for (change in 0..3) {
      val vault = ForegroundWalletVault(SecretVault())
      val credentials = JevCredentials(vault, AppPreferences(MetadataStore()))
      credentials.store("original-key")
      credentials.setEnabled(true)
      var cleaned = false
      val request = async {
        credentials.withKey {
          try { awaitCancellation() } finally { cleaned = true }
        }
      }
      runCurrent()
      when (change) {
        0 -> vault.lock()
        1 -> credentials.store("replacement-key")
        2 -> credentials.remove()
        else -> credentials.setEnabled(false)
      }
      assertFailsWith<CancellationException> { request.await() }
      assertTrue(cleaned)
    }
  }

  @Test
  fun optionalLoadCompletingAfterBackgroundCannotReopenVisit() = runTest {
    val platform = SecretVault()
    val vault = ForegroundWalletVault(platform)
    val credentials = JevCredentials(vault, AppPreferences(MetadataStore()))
    credentials.store("stored-key")
    credentials.setEnabled(true)
    vault.lock()
    vault.unlock(emptyList(), VaultPrompt("Open", "Open"))
    platform.wait = CompletableDeferred()
    val loading = async { credentials.preload() }
    runCurrent()
    vault.lock()
    platform.wait!!.complete(Unit)
    loading.await()
    assertFalse(vault.unlocked.value)
    assertTrue(credentials.metadata.first().enabled)
    assertTrue(platform.returned.all { bytes -> bytes.all { it == 0.toByte() } })
  }

  @Test
  fun revokedOptionalLoadDoesNotChangeMetadataAfterRapidReopen() = runTest {
    for (corrupted in listOf(false, true)) {
      val platform = SecretVault()
      val vault = ForegroundWalletVault(platform)
      val credentials = JevCredentials(vault, AppPreferences(MetadataStore()))
      credentials.store("stored-key")
      credentials.setEnabled(true)
      vault.lock()
      vault.unlock(emptyList(), VaultPrompt("Open", "Open"))
      platform.wait = CompletableDeferred()
      platform.corruptReads = corrupted
      val loading = async { credentials.preload() }
      runCurrent()
      vault.lock()
      val reopened = async { vault.unlock(emptyList(), VaultPrompt("Open", "Open")) }
      platform.wait!!.complete(Unit)
      loading.await()
      reopened.await()
      assertTrue(vault.unlocked.value)
      assertTrue(credentials.metadata.first().configured)
      assertTrue(credentials.metadata.first().enabled)
      assertTrue(platform.returned.all { bytes -> bytes.all { it == 0.toByte() } })
    }
  }

  @Test
  fun revokedExploreRestoreCannotClearMetadataOnLateCorruption() = runTest {
    val platform = SecretVault()
    val vault = ForegroundWalletVault(platform)
    val credentials = JevCredentials(vault, AppPreferences(MetadataStore()))
    credentials.store("stored-key")
    credentials.setEnabled(true)
    vault.lock()
    platform.wait = CompletableDeferred()
    platform.corruptReads = true
    var evaluated = false
    val loading = async { runCatching { credentials.withKey { evaluated = true } } }
    runCurrent()
    vault.lock()
    platform.wait!!.complete(Unit)
    assertTrue(loading.await().isFailure)
    assertFalse(evaluated)
    assertTrue(credentials.metadata.first().configured)
    assertTrue(credentials.metadata.first().enabled)
  }

  @Test
  fun invalidInputIsRejectedBeforeStorage() = runTest {
    val platform = SecretVault()
    val credentials = JevCredentials(ForegroundWalletVault(platform), AppPreferences(MetadataStore()))
    for (key in listOf("", "key\nvalue", "key value", "x".repeat(4097))) {
      assertFailsWith<IllegalArgumentException> { credentials.store(key) }
    }
    assertTrue(platform.secrets.isEmpty())
  }

  private class MetadataStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      return transform(data.value).also { data.value = it }
    }
  }

  private class SecretVault : WalletVault {
    val secrets = mutableMapOf<WalletSecretSlot, ByteArray>()
    var reads = 0
    var wait: CompletableDeferred<Unit>? = null
    var corruptReads = false
    val returned = mutableListOf<ByteArray>()
    override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) {
      secrets.put(slot, secret.copyOf())?.fill(0)
    }
    override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray {
      reads++
      wait?.await()
      if (corruptReads) throw WalletVaultException.Corrupted()
      return (secrets[slot]?.copyOf() ?: throw WalletVaultException.Corrupted()).also { returned += it }
    }
    override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) { secrets.remove(slot)?.fill(0) }
    override fun lock() = Unit
  }
}
