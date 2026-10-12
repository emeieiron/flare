package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.data.CredentialFormat
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.kaptos.core.crypto.Aip80PrivateKey
import xyz.mcxross.kaptos.core.crypto.MnemonicPhrase

/**
 * Aptos credentials: BIP-39 recovery phrases and AIP-80 private keys (`ed25519-priv-0x…`,
 * `secp256k1-priv-0x…`). A bare 64-digit hex key is read as Ed25519.
 */
object DecibelCredentialParser : CredentialParser {
  override fun detect(input: String): CredentialFormat {
    val value = input.trim()
    return when {
      value.startsWith("ed25519-priv-") ||
        value.startsWith("secp256k1-priv-") ||
        value.matches(Regex("(?:0[xX])?[0-9a-fA-F]{64}")) ->
        CredentialFormat.PRIVATE_KEY
      value.split(Regex("\\s+")).size in listOf(12, 15, 18, 21, 24) ->
        CredentialFormat.RECOVERY_PHRASE
      else -> CredentialFormat.UNKNOWN
    }
  }

  override fun normalize(input: String): String =
    when (detect(input)) {
      CredentialFormat.RECOVERY_PHRASE ->
        MnemonicPhrase.parse(input.trim().lowercase().replace(Regex("\\s+"), " ")).reveal()
      CredentialFormat.PRIVATE_KEY -> {
        val value = input.trim()
        val canonical =
          if (value.startsWith("ed25519-priv-") || value.startsWith("secp256k1-priv-")) value
          else "ed25519-priv-0x" + value.removePrefix("0x").removePrefix("0X").lowercase()
        Aip80PrivateKey.parse(canonical).value
      }
      CredentialFormat.UNKNOWN ->
        throw IllegalArgumentException(
          "Enter a valid recovery phrase or private key."
        )
    }

  override fun keyAddress(input: String): String? =
    if (detect(input) != CredentialFormat.PRIVATE_KEY) null
    else runCatching { keyAccount(normalize(input)).accountAddress.toString() }.getOrNull()

  override fun sameAddress(first: String, second: String): Boolean =
    runCatching { first.sameAptosAddress(second) }.getOrDefault(false)

  /** An Aptos account address: `0x` and up to 64 hex digits. */
  override fun isAddress(input: String): Boolean = AptosAddress.matches(input.trim())
}

private val AptosAddress = Regex("^0x[0-9a-fA-F]{1,64}$")
