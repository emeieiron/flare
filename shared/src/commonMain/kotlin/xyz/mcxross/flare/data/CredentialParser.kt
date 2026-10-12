package xyz.mcxross.flare.data

/** Format detection is local. A key's owner/API permissions cannot be inferred from its bytes. */
enum class CredentialFormat {
  RECOVERY_PHRASE,
  PRIVATE_KEY,
  UNKNOWN,
}

/**
 * Recognizes the recovery phrases and private keys the exchange accepts. Each venue binds its own:
 * key formats and the address a key signs as differ between chains.
 */
interface CredentialParser {
  /** What [input] looks like, without validating it. */
  fun detect(input: String): CredentialFormat

  /**
   * The canonical form of a phrase or key, as stored in the vault. Throws
   * [IllegalArgumentException] when [input] isn't valid.
   */
  fun normalize(input: String): String

  /** The address a private key signs as, or null when [input] isn't a valid key. */
  fun keyAddress(input: String): String?
}
