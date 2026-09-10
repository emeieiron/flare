package xyz.mcxross.flare

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import xyz.mcxross.flare.data.DefaultWalletRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.WalletSecretSlot
import xyz.mcxross.flare.security.WalletVault
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.createPreferencesDataStore

class WalletRepositoryIosTest {
  @Test
  fun ownerAndApiWalletsAreIndependentAndSecretsStayInVault() = runTest {
    val vault = IosTestMemoryVault()
    val repository = DefaultWalletRepository(vault, testPreferences())
    val prompt = VaultPrompt("Test", "Test authorization")

    val backup = repository.createOwner(prompt)
    val apiAddress = repository.createApiWallet(prompt)

    assertEquals(12, backup.words.size)
    assertNotEquals(backup.address, apiAddress)
    assertFalse(repository.profile.first().ownerBackupConfirmed)
    assertTrue(repository.exportApiWallet(prompt).startsWith("ed25519-priv-"))
    assertEquals(backup.words.joinToString(" "), repository.exportOwnerMnemonic(prompt))
    assertEquals(2, vault.entries.size)
    assertTrue(vault.readPrompts.all(VaultPrompt::requireFreshAuthorization))

    repository.confirmOwnerBackup()
    assertTrue(repository.profile.first().ownerBackupConfirmed)
  }

  @Test
  fun importsKnownMnemonicAndAip80RoundTrips() = runTest {
    val prompt = VaultPrompt("Test", "Test authorization")
    val source = DefaultWalletRepository(IosTestMemoryVault(), testPreferences())
    source.createApiWallet(prompt)
    val aip80 = source.exportApiWallet(prompt)

    val targetPreferences = testPreferences()
    val target = DefaultWalletRepository(IosTestMemoryVault(), targetPreferences)
    val phrase =
      "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    val ownerAddress = target.importOwner(phrase, prompt)
    val apiAddress = target.importApiWallet(aip80, prompt)

    assertTrue(ownerAddress.startsWith("0x"))
    assertNotEquals(ownerAddress, apiAddress)
    assertTrue(target.profile.first().apiOnly)
    assertEquals(aip80, target.exportApiWallet(prompt))
    targetPreferences.activateProfile(
      targetPreferences.values.first().profiles.first { it.ownerAddress == ownerAddress }.id
    )
    assertEquals(phrase, target.exportOwnerMnemonic(prompt))
    assertTrue(target.profile.first().ownerBackupConfirmed)
  }

  @Test
  fun rawOwnerKeyIsStoredCanonicallyAndCanSignAfterReopening() = runTest {
    val prompt = VaultPrompt("Test", "Test authorization")
    val vault = IosTestMemoryVault()
    val preferences = testPreferences()
    val repository = DefaultWalletRepository(vault, preferences)
    val rawKey = "ab".repeat(32)
    val address = repository.importOwner(rawKey, prompt)
    val reopened = DefaultWalletRepository(vault, preferences)
    assertEquals("ed25519-priv-0x$rawKey", reopened.exportOwnerMnemonic(prompt))
    reopened.withOwnerAccount(prompt) { assertEquals(address, it.accountAddress.toString()) }
    assertTrue(reopened.profile.first().ownerBackupConfirmed)
    assertEquals(1, vault.entries.size)
    reopened.removeOwner(prompt)
    assertTrue(vault.entries.isEmpty())
    assertEquals(null, reopened.profile.first().ownerAddress)
  }

  @Test
  fun multipleOwnersKeepIndependentKeysAndReuseEachDeviceKey() = runTest {
    val vault = IosTestMemoryVault()
    val preferences = testPreferences()
    val repository = DefaultWalletRepository(vault, preferences)
    val prompt = VaultPrompt("Test", "Test")
    val first = repository.importOwner("ab".repeat(32), prompt)
    val firstProfile = preferences.values.first().activeProfileId
    val firstApi = repository.createApiWallet(prompt)
    val second = repository.importOwner("cd".repeat(32), prompt)
    val secondApi = repository.createApiWallet(prompt)
    assertNotEquals(first, second)
    assertNotEquals(firstApi, secondApi)
    assertEquals(4, vault.entries.size)
    preferences.activateProfile(firstProfile)
    assertEquals(firstApi, repository.createApiWallet(prompt))
    repository.withOwnerAccount(prompt) { assertEquals(first, it.accountAddress.toString()) }
    repository.withApiAccount(prompt) { assertEquals(firstApi, it.accountAddress.toString()) }
    assertEquals(4, vault.entries.size)
  }

  @Test
  fun invalidApiImportDoesNotReplaceAnExistingProfile() = runTest {
    val vault = IosTestMemoryVault()
    val preferences = testPreferences()
    val repository = DefaultWalletRepository(vault, preferences)
    val prompt = VaultPrompt("Test", "Test")
    repository.importOwner("ab".repeat(32), prompt)
    val api = repository.createApiWallet(prompt)
    val id = preferences.values.first().activeProfileId
    assertFailsWith<IllegalStateException> {
      repository.importVerifiedApi("cd".repeat(32), prompt) { error("Not delegated") }
    }
    assertEquals(id, preferences.values.first().activeProfileId)
    assertEquals(api, repository.profile.first().apiWalletAddress)
    assertEquals(2, vault.entries.size)
  }

  @Test
  fun lockClearsThePlatformAuthorizationSession() = runTest {
    val vault = IosTestMemoryVault()
    val repository = DefaultWalletRepository(vault, testPreferences())

    repository.lock()

    assertEquals(1, vault.lockCount)
  }
}

internal fun testPreferences(): AppPreferences {
  val path = "${NSTemporaryDirectory()}flare-${Random.nextLong()}.preferences_pb"
  return AppPreferences(createPreferencesDataStore { path })
}

internal class IosTestMemoryVault : WalletVault {
  val entries = mutableMapOf<WalletSecretSlot, ByteArray>()
  val readPrompts = mutableListOf<VaultPrompt>()
  var lockCount = 0

  override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) {
    entries[slot] = secret.copyOf()
  }

  override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray =
    entries[slot]?.copyOf().also { readPrompts += prompt } ?: error("Missing wallet secret")

  override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) {
    entries.remove(slot)?.fill(0)
  }

  override fun lock() {
    lockCount += 1
  }
}
