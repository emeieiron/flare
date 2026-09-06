package xyz.mcxross.flare

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

    val target = DefaultWalletRepository(IosTestMemoryVault(), testPreferences())
    val phrase =
      "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    val ownerAddress = target.importOwner(phrase, prompt)
    val apiAddress = target.importApiWallet(aip80, prompt)

    assertTrue(ownerAddress.startsWith("0x"))
    assertNotEquals(ownerAddress, apiAddress)
    assertEquals(phrase, target.exportOwnerMnemonic(prompt))
    assertEquals(aip80, target.exportApiWallet(prompt))
    assertTrue(target.profile.first().ownerBackupConfirmed)
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
