package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import xyz.mcxross.flare.data.CredentialFormat

class DecibelCredentialParserTest {
  // Public test material; never use this key with funds.
  private val hex = "ab".repeat(32)

  @Test
  fun recognizesAllSupportedPrivateKeyForms() {
    for (value in listOf(hex, "0x$hex", "0X${hex.uppercase()}", "ed25519-priv-0x$hex")) {
      assertEquals(CredentialFormat.PRIVATE_KEY, DecibelCredentialParser.detect(value))
      assertEquals("ed25519-priv-0x$hex", DecibelCredentialParser.normalize("  $value\n"))
    }
  }

  @Test
  fun detectsPhraseWithWhitespaceWithoutGuessingKeyPermissions() {
    assertEquals(
      CredentialFormat.RECOVERY_PHRASE,
      DecibelCredentialParser.detect("  " + List(11) { "abandon" }.joinToString("\n") + "\tabout  "),
    )
    assertEquals(CredentialFormat.PRIVATE_KEY, DecibelCredentialParser.detect("ed25519-priv-0x$hex"))
  }

  @Test
  fun rejectsInvalidKeysAndUnsupportedAlgorithms() {
    for (value in
      listOf("0x1234", "z".repeat(64), "ed25519-priv-0x1234", "secp256k1-priv-0x1234", "secp256r1-priv-0x$hex", "")) {
      assertFailsWith<IllegalArgumentException> { DecibelCredentialParser.normalize(value) }
    }
  }

  @Test
  fun keepsSecp256k1Keys() {
    val key = "secp256k1-priv-0x$hex"
    assertEquals(CredentialFormat.PRIVATE_KEY, DecibelCredentialParser.detect(key))
    assertEquals(key, DecibelCredentialParser.normalize(key))
  }

  @Test
  fun onlyKeysHaveAnAddress() {
    assertEquals(null, DecibelCredentialParser.keyAddress("abandon ".repeat(11) + "about"))
    assertEquals(null, DecibelCredentialParser.keyAddress("0x1234"))
  }
}
