package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.flare.data.CredentialFormat

/** The checks that need no cryptography; key derivation is tested on iOS and in :hyperliquid's JVM tests. */
class HyperliquidCredentialParserTest {
  private val parser = HyperliquidCredentialParser

  @Test
  fun recognizesHexKeysAndPhrases() {
    assertEquals(CredentialFormat.PRIVATE_KEY, parser.detect("0x" + "ab".repeat(32)))
    assertEquals(CredentialFormat.PRIVATE_KEY, parser.detect(" " + "AB".repeat(32) + " "))
    assertEquals(CredentialFormat.RECOVERY_PHRASE, parser.detect("word ".repeat(12)))
    assertEquals(CredentialFormat.RECOVERY_PHRASE, parser.detect("word ".repeat(24)))
    assertEquals(CredentialFormat.UNKNOWN, parser.detect("word ".repeat(13)))
    assertEquals(CredentialFormat.UNKNOWN, parser.detect("ed25519-priv-0x" + "ab".repeat(32)))
    assertEquals(CredentialFormat.UNKNOWN, parser.detect("0x" + "ab".repeat(20)))
  }

  @Test
  fun rejectsWhatIsNeitherAPhraseNorAKey() {
    assertFailsWith<IllegalArgumentException> { parser.normalize("not a key") }
    assertNull(parser.keyAddress("word ".repeat(12)))
  }

  @Test
  fun acceptsEvmAddressesOnly() {
    assertTrue(parser.isAddress("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"))
    assertTrue(parser.isAddress(" 0xF39FD6E51AAD88F6F4CE6AB8827279CFFFB92266 "))
    assertFalse(parser.isAddress("0xf39fd6e51aad88f6f4ce6ab8827279cfffb9226"))
    assertFalse(parser.isAddress("f39fd6e51aad88f6f4ce6ab8827279cfffb92266"))
    assertFalse(parser.isAddress("0x" + "a".repeat(64)))
  }

  @Test
  fun comparesAddressesWhateverTheirCase() {
    assertTrue(
      parser.sameAddress(
        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
        "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266",
      )
    )
    assertFalse(parser.sameAddress("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266", "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"))
    assertFalse(parser.sameAddress("0x1", "0x1"))
  }
}
