package xyz.mcxross.flare.hyperliquid.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EvmKeysTest {
  @Test
  fun derivesTheHardhatAccount() {
    val key = EvmKeys.privateKeyFromMnemonic(HARDHAT_PHRASE)

    assertEquals(HARDHAT_KEY, key)
    assertEquals(HARDHAT_ADDRESS, EvmKeys.address(key))
  }

  @Test
  fun derivesTheBip39TestVectorAccount() {
    val phrase = "abandon ".repeat(11) + "about"

    assertEquals(
      "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
      EvmKeys.address(EvmKeys.privateKeyFromMnemonic(phrase)),
    )
  }

  @Test
  fun readsAPhraseHoweverItIsSpaced() {
    val messy = "  " + HARDHAT_PHRASE.uppercase().replace(" ", " \n ") + " "

    assertTrue(EvmKeys.isValidMnemonic(messy))
    assertEquals(HARDHAT_KEY, EvmKeys.privateKeyFromMnemonic(messy))
  }

  @Test
  fun rejectsAPhraseWithABadChecksumWithoutEchoingIt() {
    val wrong = "test ".repeat(12).trim()

    assertFalse(EvmKeys.isValidMnemonic(wrong))
    val error = assertFailsWith<IllegalArgumentException> { EvmKeys.privateKeyFromMnemonic(wrong) }
    assertFalse(error.message.orEmpty().contains("test"))
  }

  @Test
  fun generatesValidPhrasesOfTheRequestedLength() {
    val first = EvmKeys.generateMnemonic()
    val second = EvmKeys.generateMnemonic(24)

    assertEquals(12, first.split(' ').size)
    assertEquals(24, second.split(' ').size)
    assertTrue(EvmKeys.isValidMnemonic(first))
    assertNotEquals(first, EvmKeys.generateMnemonic())
    assertFailsWith<IllegalArgumentException> { EvmKeys.generateMnemonic(13) }
  }

  @Test
  fun generatesDistinctUsableKeys() {
    val first = EvmKeys.generatePrivateKey()
    val second = EvmKeys.generatePrivateKey()

    assertEquals(first, EvmKeys.normalizePrivateKey(first))
    assertNotEquals(first, second)
    assertNotEquals(EvmKeys.address(first), EvmKeys.address(second))
  }

  @Test
  fun importsAHexKeyWithOrWithoutItsPrefix() {
    val bare = HARDHAT_KEY.removePrefix("0x").uppercase()

    assertEquals(HARDHAT_KEY, EvmKeys.normalizePrivateKey(bare))
    assertEquals(HARDHAT_KEY, EvmKeys.normalizePrivateKey("  0X$bare "))
    assertEquals(HARDHAT_ADDRESS, EvmKeys.address(bare))
    assertFailsWith<IllegalArgumentException> { EvmKeys.normalizePrivateKey("0x1234") }
    // Zero is not a valid secp256k1 scalar.
    assertFailsWith<IllegalArgumentException> { EvmKeys.normalizePrivateKey("0".repeat(64)) }
  }

  @Test
  fun checksumsAddressesPerEip55() {
    assertEquals(HARDHAT_ADDRESS, EvmAddress.checksum(HARDHAT_ADDRESS.lowercase()))
    assertTrue(EvmAddress.isValidChecksum(HARDHAT_ADDRESS))
    assertTrue(EvmAddress.isValidChecksum(HARDHAT_ADDRESS.lowercase()))
    assertFalse(EvmAddress.isValidChecksum(HARDHAT_ADDRESS.replace("f39Fd6", "F39fd6")))
  }

  private companion object {
    const val HARDHAT_PHRASE = "test test test test test test test test test test test junk"
    const val HARDHAT_KEY = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
    const val HARDHAT_ADDRESS = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
  }
}
