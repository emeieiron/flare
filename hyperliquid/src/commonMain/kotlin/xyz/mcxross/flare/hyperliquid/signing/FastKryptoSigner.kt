package xyz.mcxross.flare.hyperliquid.signing

import xyz.mcxross.fastkrypto.secp256k1PublicKeyUncompressed
import xyz.mcxross.fastkrypto.secp256k1RecoverPublicKeyKeccak256
import xyz.mcxross.fastkrypto.secp256k1SignRecoverableKeccak256

/**
 * An [EvmSigner] over a raw secp256k1 private key, signing with FastKrypto. Every signature is
 * checked before it is returned: the public key recovered from it must give this signer's address,
 * so a fault never yields a signature for someone else.
 *
 * The key is copied in and never logged; [toString] shows only the address.
 */
class FastKryptoSigner(privateKey: ByteArray) : EvmSigner {
  private val privateKey: ByteArray = privateKey.copyOf()

  init {
    require(privateKey.size == PRIVATE_KEY_SIZE) { "A secp256k1 private key is 32 bytes" }
  }

  override val address: String =
    try {
      EvmAddress.fromPublicKey(secp256k1PublicKeyUncompressed(this.privateKey))
    } catch (error: Exception) {
      // FastKrypto's message could quote its input; keep it out of the error.
      throw IllegalArgumentException("Invalid secp256k1 private key")
    }

  override suspend fun signTypedData(signingInput: ByteArray): EvmSignature = sign(signingInput)

  /** Signs the 66-byte EIP-712 [signingInput]; FastKrypto hashes it with Keccak-256. */
  fun sign(signingInput: ByteArray): EvmSignature {
    require(
      signingInput.size == Eip712.SIGNING_INPUT_SIZE &&
        signingInput[0] == 0x19.toByte() &&
        signingInput[1] == 0x01.toByte()
    ) {
      "EIP-712 signing input is 0x1901 followed by two 32-byte hashes"
    }
    val signature =
      try {
        secp256k1SignRecoverableKeccak256(privateKey, signingInput)
      } catch (error: Exception) {
        throw IllegalStateException("Signing failed")
      }
    val recovered =
      try {
        EvmAddress.fromPublicKey(secp256k1RecoverPublicKeyKeccak256(signingInput, signature))
      } catch (error: Exception) {
        throw IllegalStateException("The new signature could not be verified")
      }
    check(recovered == address) { "The new signature does not recover this signer's address" }
    return EvmSignature.fromRecoverable(signature)
  }

  override fun toString(): String = "FastKryptoSigner($address)"

  companion object {
    const val PRIVATE_KEY_SIZE: Int = 32

    /** From `0x` and 64 hex digits (the prefix is optional). */
    fun fromHex(privateKeyHex: String): FastKryptoSigner {
      val bytes = decodeHex(privateKeyHex, "Private key")
      try {
        return FastKryptoSigner(bytes)
      } finally {
        bytes.fill(0)
      }
    }
  }
}
