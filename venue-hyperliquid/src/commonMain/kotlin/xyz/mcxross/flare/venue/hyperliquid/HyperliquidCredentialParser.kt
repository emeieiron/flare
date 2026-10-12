package xyz.mcxross.flare.venue.hyperliquid

import xyz.mcxross.flare.data.CredentialFormat
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.hyperliquid.signing.EvmAddress
import xyz.mcxross.flare.hyperliquid.signing.EvmKeys

/**
 * EVM credentials: BIP-39 recovery phrases (derived at `m/44'/60'/0'/0/0`, as Ethereum wallets do)
 * and secp256k1 private keys written as 64 hex digits, with or without `0x`.
 */
object HyperliquidCredentialParser : CredentialParser {
  private val PrivateKeyHex = Regex("(?:0[xX])?[0-9a-fA-F]{64}")
  private val Address = Regex("0[xX][0-9a-fA-F]{40}")
  private val Whitespace = Regex("\\s+")

  override fun detect(input: String): CredentialFormat {
    val value = input.trim()
    return when {
      PrivateKeyHex.matches(value) -> CredentialFormat.PRIVATE_KEY
      value.split(Whitespace).size in PhraseLengths -> CredentialFormat.RECOVERY_PHRASE
      else -> CredentialFormat.UNKNOWN
    }
  }

  /** A phrase as lower-case words, or a key as `0x` and 64 lower-case hex digits. */
  override fun normalize(input: String): String =
    when (detect(input)) {
      CredentialFormat.RECOVERY_PHRASE ->
        EvmKeys.normalizeMnemonic(input).also {
          require(EvmKeys.isValidMnemonic(it)) { "Enter a valid recovery phrase or private key." }
        }
      CredentialFormat.PRIVATE_KEY ->
        runCatching { EvmKeys.normalizePrivateKey(input) }
          .getOrElse { throw IllegalArgumentException("Enter a valid recovery phrase or private key.") }
      CredentialFormat.UNKNOWN ->
        throw IllegalArgumentException("Enter a valid recovery phrase or private key.")
    }

  /** The EIP-55 address [input] signs as. */
  override fun keyAddress(input: String): String? =
    if (detect(input) != CredentialFormat.PRIVATE_KEY) null
    else runCatching { EvmKeys.address(normalize(input)) }.getOrNull()

  /** EVM addresses are one account however they're cased. */
  override fun sameAddress(first: String, second: String): Boolean {
    val a = first.trim()
    val b = second.trim()
    return Address.matches(a) && Address.matches(b) && a.equals(b, ignoreCase = true)
  }

  /**
   * `0x` and 40 hex digits. A mixed-case address must carry a correct EIP-55 checksum, since a wrong
   * one is most likely a typo.
   */
  override fun isAddress(input: String): Boolean {
    val value = input.trim()
    if (!Address.matches(value)) return false
    val digits = value.drop(2)
    if (digits == digits.lowercase() || digits == digits.uppercase()) return true
    return runCatching { EvmAddress.isValidChecksum("0x$digits") }.getOrDefault(false)
  }

  /** The private key of a stored owner credential: a recovery phrase or the key itself. */
  internal fun ownerKey(credential: String): String =
    if (detect(credential) == CredentialFormat.PRIVATE_KEY) EvmKeys.normalizePrivateKey(credential)
    else EvmKeys.privateKeyFromMnemonic(credential)

  private val PhraseLengths = setOf(12, 15, 18, 21, 24)
}
