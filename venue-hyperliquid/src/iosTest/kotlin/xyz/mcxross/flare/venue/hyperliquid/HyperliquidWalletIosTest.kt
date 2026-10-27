package xyz.mcxross.flare.venue.hyperliquid

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSTemporaryDirectory
import xyz.mcxross.flare.data.CredentialFormat
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.signing.EvmKeys
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
  fun rejectsBadCredentials() = runTest {
    val vault = MemoryVault()
    val wallets = HyperliquidWalletRepository(ForegroundWalletVault(vault), testPreferences())

    assertFailsWith<IllegalArgumentException> { wallets.importOwner("test ".repeat(12), prompt) }
    assertFailsWith<IllegalArgumentException> { wallets.importOwner("0".repeat(64), prompt) }
    assertTrue(vault.entries.isEmpty())
  }

  @Test
  fun theDeviceKeyIsANewAgentKeyInTheTradingKeySlot() = runTest {
    val vault = MemoryVault()
    val preferences = testPreferences()
    val wallets = HyperliquidWalletRepository(ForegroundWalletVault(vault), preferences)
    wallets.importOwner(HARDHAT_PHRASE, prompt)

    val agent = wallets.createApiWallet(prompt)

    assertEquals(agent, wallets.createApiWallet(prompt))
    val key = wallets.exportApiWallet(prompt)
    assertEquals(agent, EvmKeys.address(key))
    assertEquals(
      key,
      vault.entries.getValue(WalletSecretSlot.API_PRIVATE_KEY.forProfile("owner_$HARDHAT_ADDRESS")).decodeToString(),
    )
    wallets.withAgentSigner(prompt) { assertEquals(agent.lowercase(), it.address) }
    wallets.withOwnerSigner(prompt) { assertEquals(HARDHAT_ADDRESS.lowercase(), it.address) }
    val replaced = wallets.newApiWallet(prompt)
    assertNotEquals(agent, replaced)
    assertEquals(replaced, preferences.values.first().apiWalletAddress)
    wallets.discardApiWallet(prompt)
    assertNull(preferences.values.first().apiWalletAddress)
    assertEquals(1, vault.entries.size)
  }

  @Test
  fun importsAnAgentKeyOnlyOnceItIsVerified() = runTest {
    val vault = MemoryVault()
    val preferences = testPreferences()
    val wallets = HyperliquidWalletRepository(ForegroundWalletVault(vault), preferences)

    assertFailsWith<IllegalStateException> {
      wallets.importApiWallet(HARDHAT_KEY, prompt) { error("Not an agent") }
    }
    assertTrue(vault.entries.isEmpty())

    assertEquals(HARDHAT_ADDRESS, wallets.importApiWallet(HARDHAT_KEY, prompt) {})
    assertEquals(HARDHAT_ADDRESS, preferences.values.first().apiWalletAddress)
    assertEquals("api_$HARDHAT_ADDRESS", preferences.values.first().activeProfileId)
  }

  /** The whole setup with real keys and signatures, against a mock Hyperliquid. */
  @Test
  fun enablingTradingPostsAnOwnerSignedApproveAgent() = runTest {
    val vault = MemoryVault()
    val preferences = testPreferences()
    val wallets = HyperliquidWalletRepository(ForegroundWalletVault(vault), preferences)
    wallets.importOwner(HARDHAT_PHRASE, prompt)
    val agents = mutableListOf<String>()
    var posted: JsonObject? = null
    val client =
      HyperliquidClient(
        HttpClient(
          MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val reply =
              if (request.url.encodedPath.endsWith("/exchange")) {
                posted = body
                agents += body["action"]!!.jsonObject["agentAddress"]!!.jsonPrimitive.content
                """{"status":"ok","response":{"type":"default"}}"""
              } else
                when (body["type"]!!.jsonPrimitive.content) {
                  "userRole" -> """{"role":"user"}"""
                  "extraAgents" ->
                    agents.joinToString(",", "[", "]") {
                      """{"name":"flare","address":"$it","validUntil":4102444800000}"""
                    }
                  else -> error("Unexpected request")
                }
            respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
          }
        )
      )
    val nonces = HyperliquidNonces(preferences)
    val actions =
      HyperliquidActions(MemoryJournal(), preferences, wallets, client, ClientSubmitter(client), nonces)
    val setup = HyperliquidSetup(client, preferences, wallets, actions, nonces, verificationDelayMs = 0)

    setup.enableTrading(prompt)

    val agent = preferences.values.first().apiWalletAddress!!
    val sent = posted!!
    val action = sent["action"]!!.jsonObject
    assertEquals("approveAgent", action["type"]!!.jsonPrimitive.content)
    assertEquals(agent.lowercase(), action["agentAddress"]!!.jsonPrimitive.content)
    assertEquals("flare", action["agentName"]!!.jsonPrimitive.content)
    assertEquals(action["nonce"], sent["nonce"])
    assertTrue("signature" in sent)
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
