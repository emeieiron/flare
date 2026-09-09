package xyz.mcxross.flare.data

import xyz.mcxross.kaptos.core.crypto.Aip80PrivateKey
import xyz.mcxross.kaptos.core.crypto.MnemonicPhrase

/** Format detection is local. A key's owner/API permissions cannot be inferred from its bytes. */
enum class CredentialFormat {
  RECOVERY_PHRASE,
  PRIVATE_KEY,
  UNKNOWN,
}

object WalletCredential {
  fun detect(input: String): CredentialFormat {
    val value = input.trim()
    return when {
      value.startsWith("ed25519-priv-") || value.matches(Regex("(?:0[xX])?[0-9a-fA-F]{64}")) ->
        CredentialFormat.PRIVATE_KEY
      value.split(Regex("\\s+")).size in listOf(12, 15, 18, 21, 24) ->
        CredentialFormat.RECOVERY_PHRASE
      else -> CredentialFormat.UNKNOWN
    }
  }

  fun normalize(input: String): String =
    when (detect(input)) {
      CredentialFormat.RECOVERY_PHRASE ->
        MnemonicPhrase.parse(input.trim().lowercase().replace(Regex("\\s+"), " ")).reveal()
      CredentialFormat.PRIVATE_KEY -> {
        val value = input.trim()
        val canonical =
          if (value.startsWith("ed25519-priv-")) value
          else "ed25519-priv-0x" + value.removePrefix("0x").removePrefix("0X").lowercase()
        Aip80PrivateKey.parse(canonical).value
      }
      CredentialFormat.UNKNOWN ->
        throw IllegalArgumentException(
          "Enter a valid recovery phrase or a 64-character Ed25519 private key."
        )
    }
}
