package xyz.mcxross.flare.venue.hyperliquid

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import xyz.mcxross.flare.data.CredentialFormat
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.WalletSecretSlot
import xyz.mcxross.flare.security.WalletVault
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.createPreferencesDataStore

/** Runs FastKrypto natively, which Android host tests can't load. */
class HyperliquidWalletIosTest {
  private val prompt = VaultPrompt("Test", "Test authorization")

  @Test
  fun importsTheHardhatPhraseAsItsChecksummedAddress() = runTest {
    val vault = MemoryVault()
    val preferences = testPreferences()
    val wallets = HyperliquidWalletRepository(ForegroundWalletVault(vault), preferences)

    val address = wallets.importOwner("  TEST test test test test test test test test test test junk ", prompt)

    assertEquals(HARDHAT_ADDRESS, address)
    assertEquals("owner_$HARDHAT_ADDRESS", preferences.values.first().activeProfileId)
    assertEquals(HARDHAT_ADDRESS, wallets.profile.first().ownerAddress)
    assertTrue(wallets.profile.first().ownerBackupConfirmed)
    assertEquals(HARDHAT_PHRASE, wallets.exportOwnerMnemonic(prompt))
    assertEquals(1, vault.entries.size)
  }

  @Test
  fun importsAHexKeyCanonicallyAndFindsTheSameWalletAgain() = runTest {
    val vault = MemoryVault()
    val preferences = testPreferences()
    val wallets = HyperliquidWalletRepository(ForegroundWalletVault(vault), preferences)

    assertEquals(HARDHAT_ADDRESS, wallets.importOwner(HARDHAT_KEY.removePrefix("0x").uppercase(), prompt))
    assertEquals(HARDHAT_KEY, wallets.exportOwnerMnemonic(prompt))
    assertEquals(HARDHAT_ADDRESS, wallets.importOwner(HARDHAT_PHRASE, prompt))
    assertEquals(1, preferences.values.first().profiles.size)
    assertEquals(1, vault.entries.size)

    wallets.removeOwner(prompt)
    assertTrue(vault.entries.isEmpty())
    assertEquals(null, wallets.profile.first().ownerAddress)
  }

  @Test
  fun aNewWalletKeepsItsPhraseUntilBackedUp() = runTest {
    val vault = MemoryVault()
    val preferences = testPreferences()
    val wallets = HyperliquidWalletRepository(ForegroundWalletVault(vault), preferences)

    val backup = wallets.createOwner(prompt)

    assertEquals(12, backup.words.size)
    assertTrue(HyperliquidCredentialParser.isAddress(backup.address))
    assertEquals(backup.address, wallets.importOwner(backup.words.joinToString(" "), prompt))
    val profile = preferences.values.first().profiles.single()
    assertTrue(profile.createdInApp)
    assertEquals(backup.words.joinToString(" "), wallets.exportOwnerMnemonic(prompt))
    assertTrue(vault.readPrompts.all(VaultPrompt::requireFreshAuthorization))
  }

  @Test
  fun offersNoTradingKeyYetAndRejectsBadCredentials() = runTest {
    val vault = MemoryVault()
    val wallets = HyperliquidWalletRepository(ForegroundWalletVault(vault), testPreferences())

    assertFailsWith<UnsupportedOperationException> { wallets.createApiWallet(prompt) }
    assertFailsWith<IllegalArgumentException> { wallets.importOwner("test ".repeat(12), prompt) }
    assertFailsWith<IllegalArgumentException> { wallets.importOwner("0".repeat(64), prompt) }
    assertTrue(vault.entries.isEmpty())
  }

  @Test
  fun theParserDerivesAddressesAndChecksChecksums() {
    val parser = HyperliquidCredentialParser

    assertEquals(HARDHAT_ADDRESS, parser.keyAddress(HARDHAT_KEY))
    assertEquals(HARDHAT_KEY, parser.normalize(HARDHAT_KEY.uppercase().replace("0X", "0x")))
    assertEquals(HARDHAT_PHRASE, parser.normalize(HARDHAT_PHRASE.uppercase()))
    assertEquals(CredentialFormat.RECOVERY_PHRASE, parser.detect(HARDHAT_PHRASE))
    assertFailsWith<IllegalArgumentException> { parser.normalize("test ".repeat(12)) }
    assertTrue(parser.isAddress(HARDHAT_ADDRESS))
    assertFalse(parser.isAddress(HARDHAT_ADDRESS.replace("f39Fd6", "F39fd6")))
  }

  private companion object {
    const val HARDHAT_PHRASE = "test test test test test test test test test test test junk"
    const val HARDHAT_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
    const val HARDHAT_ADDRESS = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
  }
}

private fun testPreferences(): AppPreferences {
  val path = "${NSTemporaryDirectory()}flare-hl-${Random.nextLong()}.preferences_pb"
  return AppPreferences(createPreferencesDataStore { path })
}

private class MemoryVault : WalletVault {
  val entries = mutableMapOf<WalletSecretSlot, ByteArray>()
  val readPrompts = mutableListOf<VaultPrompt>()

  override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) {
    entries[slot] = secret.copyOf()
  }

  override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray =
    entries[slot]?.copyOf().also { readPrompts += prompt } ?: error("Missing wallet secret")

  override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) {
    entries.remove(slot)?.fill(0)
  }

  override fun lock() {}
}
