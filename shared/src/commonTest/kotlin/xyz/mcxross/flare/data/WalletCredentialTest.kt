package xyz.mcxross.flare.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletCredentialTest {
  // Public test material; never use this key with funds.
  private val hex = "ab".repeat(32)

  @Test
  fun recognizesAllSupportedPrivateKeyForms() {
    for (value in listOf(hex, "0x$hex", "0X${hex.uppercase()}", "ed25519-priv-0x$hex")) {
      assertEquals(CredentialFormat.PRIVATE_KEY, WalletCredential.detect(value))
      assertEquals("ed25519-priv-0x$hex", WalletCredential.normalize("  $value\n"))
    }
  }

  @Test
  fun detectsPhraseWithWhitespaceWithoutGuessingKeyPermissions() {
    assertEquals(
      CredentialFormat.RECOVERY_PHRASE,
      WalletCredential.detect("  " + List(11) { "abandon" }.joinToString("\n") + "\tabout  "),
    )
    assertEquals(CredentialFormat.PRIVATE_KEY, WalletCredential.detect("ed25519-priv-0x$hex"))
  }

  @Test
  fun rejectsInvalidKeysAndUnsupportedAlgorithms() {
    for (value in
      listOf("0x1234", "z".repeat(64), "ed25519-priv-0x1234", "secp256k1-priv-0x$hex", "")) {
      assertFailsWith<IllegalArgumentException> { WalletCredential.normalize(value) }
    }
  }
}
