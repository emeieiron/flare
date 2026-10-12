package xyz.mcxross.flare.hyperliquid.signing

import xyz.mcxross.fastkrypto.SignatureScheme
import xyz.mcxross.fastkrypto.mnemonicDerivePrivateKey
import xyz.mcxross.fastkrypto.mnemonicGenerate
import xyz.mcxross.fastkrypto.mnemonicValidate

/**
 * EVM wallet keys as Ethereum wallets make them: an English BIP-39 recovery phrase derived at
 * [DERIVATION_PATH], or a raw secp256k1 key written as `0x` and 64 hex digits. Errors never quote
 * their input, which may be key material.
 */
object EvmKeys {
  /** The first account of a BIP-44 Ethereum wallet. */
  const val DERIVATION_PATH: String = "m/44'/60'/0'/0/0"

  private val Whitespace = Regex("\\s+")
  private val PrivateKeyHex = Regex("(?:0[xX])?[0-9a-fA-F]{64}")
  private val WordCounts = setOf(12, 15, 18, 21, 24)

  /** A new phrase from FastKrypto's secure randomness. */
  fun generateMnemonic(words: Int = 12): String {
    require(words in WordCounts) { "A recovery phrase has 12, 15, 18, 21 or 24 words" }
    return mnemonicGenerate(words.toUInt())
  }

  /** Lower-case words separated by single spaces. */
  fun normalizeMnemonic(phrase: String): String =
    phrase.trim().lowercase().split(Whitespace).joinToString(" ")

  fun isValidMnemonic(phrase: String): Boolean =
    runCatching { mnemonicValidate(normalizeMnemonic(phrase)) }.getOrDefault(false)

  /** The key at [DERIVATION_PATH], as `0x` and 64 lower-case hex digits. */
  fun privateKeyFromMnemonic(phrase: String): String {
    require(isValidMnemonic(phrase)) { "Invalid recovery phrase" }
    val key =
      try {
        mnemonicDerivePrivateKey(normalizeMnemonic(phrase), "", SignatureScheme.SECP256K1, DERIVATION_PATH)
      } catch (error: Exception) {
        throw IllegalArgumentException("The recovery phrase couldn’t be read")
      }
    return try {
      key.toHex0x()
    } finally {
      key.fill(0)
    }
  }

  /** `0x` and 64 lower-case hex digits, for a valid secp256k1 key with or without the prefix. */
  fun normalizePrivateKey(privateKeyHex: String): String {
    val trimmed = privateKeyHex.trim()
    require(PrivateKeyHex.matches(trimmed)) { "A private key is 64 hex digits" }
    val normalized = "0x" + trimmed.takeLast(64).lowercase()
    FastKryptoSigner.fromHex(normalized)
    return normalized
  }

  /** The EIP-55 checksummed address of a private key. */
  fun address(privateKeyHex: String): String =
    EvmAddress.checksum(FastKryptoSigner.fromHex(privateKeyHex).address)
}
