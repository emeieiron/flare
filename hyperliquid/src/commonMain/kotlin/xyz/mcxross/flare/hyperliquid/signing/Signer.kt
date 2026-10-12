package xyz.mcxross.flare.hyperliquid.signing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mcxross.flare.hyperliquid.model.normalizeEvmAddress

/**
 * An EVM key that signs EIP-712 signing input (`0x19 0x01 ‖ domainSeparator ‖ structHash`, 66
 * bytes) with secp256k1 over its Keccak-256 hash.
 */
interface EvmSigner {
  /** The key's address, lower-case. */
  val address: String

  suspend fun signTypedData(signingInput: ByteArray): EvmSignature
}

/**
 * A recoverable secp256k1 signature as Hyperliquid's `/exchange` takes it: [r] and [s] as `0x` and
 * 64 hex digits, [v] 27 or 28.
 */
data class EvmSignature(val r: String, val s: String, val v: Int) {
  init {
    require(isWord(r) && isWord(s)) { "r and s must be 0x followed by 64 hex digits" }
    require(v == 27 || v == 28) { "v must be 27 or 28" }
  }

  fun toJson(): JsonObject = buildJsonObject {
    put("r", r)
    put("s", s)
    put("v", v)
  }

  /** Signatures stay out of logs. */
  override fun toString(): String = "EvmSignature(<redacted>)"

  companion object {
    /** From `r ‖ s ‖ recoveryId` (65 bytes, recovery id 0 or 1). */
    fun fromRecoverable(signature: ByteArray): EvmSignature {
      require(signature.size == 65) { "A recoverable signature is 65 bytes" }
      val recoveryId = signature[64].toInt()
      require(recoveryId == 0 || recoveryId == 1) { "Recovery id must be 0 or 1" }
      return EvmSignature(
        r = signature.copyOfRange(0, 32).toHex0x(),
        s = signature.copyOfRange(32, 64).toHex0x(),
        v = 27 + recoveryId,
      )
    }

    private fun isWord(value: String): Boolean =
      value.length == 66 &&
        value.startsWith("0x") &&
        value.drop(2).all { it in '0'..'9' || it in 'a'..'f' }
  }
}

/** EVM address helpers. */
object EvmAddress {
  /** The address of an uncompressed secp256k1 public key (`0x04 ‖ x ‖ y`), lower-case. */
  fun fromPublicKey(uncompressed: ByteArray): String {
    require(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) {
      "An uncompressed public key is 65 bytes starting with 0x04"
    }
    return keccak256(uncompressed.copyOfRange(1, 65)).copyOfRange(12, 32).toHex0x()
  }

  /** The EIP-55 mixed-case checksum form of [address]. */
  fun checksum(address: String): String {
    val lower = normalizeEvmAddress(address).drop(2)
    val hash = keccak256(lower.encodeToByteArray()).toHexDigits()
    return "0x" +
      lower.mapIndexed { index, char ->
        if (char in 'a'..'f' && hash[index] >= '8') char.uppercaseChar() else char
      }.joinToString("")
  }

  /**
   * True when [address] is lower-case, upper-case, or correctly EIP-55 checksummed; false for a
   * mixed-case address whose checksum is wrong (a likely typo).
   */
  fun isValidChecksum(address: String): Boolean {
    val digits = address.trim().drop(2)
    if (digits.lowercase() == digits || digits.uppercase() == digits) return true
    return checksum(address) == address.trim()
  }
}
