package xyz.mcxross.flare.hyperliquid.signing

import kotlinx.serialization.json.JsonObject
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment
import xyz.mcxross.flare.hyperliquid.model.normalizeEvmAddress

/**
 * Hyperliquid's L1 action signing, as `sign_l1_action` in the official Python SDK
 * (`hyperliquid/utils/signing.py`):
 * 1. `connectionId = keccak256(msgpack(action) ‖ nonce (8 bytes, big-endian) ‖ vault part ‖
 *    expiry part)`, where the vault part is `0x00` without a vault and
 *    `0x01 ‖ address (20 bytes)` with one, and the expiry part is empty without `expiresAfter`
 *    and `0x00 ‖ expiresAfter (8 bytes, big-endian)` with it;
 * 2. the phantom agent `Agent{source, connectionId}`, `source` being `"a"` on mainnet and `"b"`
 *    on testnet;
 * 3. EIP-712 under domain `Exchange`, version `1`, chain id 1337, the zero verifying contract.
 */
object L1Signing {
  const val DOMAIN_NAME: String = "Exchange"
  const val DOMAIN_VERSION: String = "1"
  const val DOMAIN_CHAIN_ID: Long = 1337L

  val domain: Eip712Struct =
    Eip712.domain(DOMAIN_NAME, DOMAIN_VERSION, DOMAIN_CHAIN_ID, Eip712.ZERO_ADDRESS)

  /** The bytes [actionHash] hashes. */
  fun hashInput(
    action: JsonObject,
    nonce: Long,
    vaultAddress: String? = null,
    expiresAfter: Long? = null,
  ): ByteArray {
    require(nonce >= 0L) { "Nonce must not be negative" }
    val packed = MsgPack.encode(action)
    val vault = vaultAddress?.let { decodeHex(normalizeEvmAddress(it, "vaultAddress")) }
    val size = packed.size + 8 + 1 + (vault?.size ?: 0) + (if (expiresAfter != null) 9 else 0)
    val out = ByteArray(size)
    packed.copyInto(out)
    var at = packed.size
    at = writeLong(out, at, nonce)
    if (vault == null) {
      out[at++] = 0x00
    } else {
      out[at++] = 0x01
      vault.copyInto(out, at)
      at += vault.size
    }
    if (expiresAfter != null) {
      require(expiresAfter >= 0L) { "expiresAfter must not be negative" }
      out[at++] = 0x00
      writeLong(out, at, expiresAfter)
    }
    return out
  }

  /** The phantom agent's `connectionId`. */
  fun actionHash(
    action: JsonObject,
    nonce: Long,
    vaultAddress: String? = null,
    expiresAfter: Long? = null,
  ): ByteArray = keccak256(hashInput(action, nonce, vaultAddress, expiresAfter))

  /** `Agent{source, connectionId}`. */
  fun phantomAgent(actionHash: ByteArray, source: String): Eip712Struct =
    Eip712Struct(
      "Agent",
      listOf(
        "source" to Eip712Value.Str(source),
        "connectionId" to Eip712Value.Bytes32(actionHash),
      ),
    )

  /** The 66-byte EIP-712 signing input for an action whose hash is [actionHash]. */
  fun signingInput(actionHash: ByteArray, source: String): ByteArray =
    Eip712.signingInput(domain, phantomAgent(actionHash, source))

  fun signingInput(
    action: JsonObject,
    nonce: Long,
    deployment: HyperliquidDeployment,
    vaultAddress: String? = null,
    expiresAfter: Long? = null,
  ): ByteArray =
    signingInput(actionHash(action, nonce, vaultAddress, expiresAfter), deployment.agentSource)

  private fun writeLong(out: ByteArray, at: Int, value: Long): Int {
    for (index in 0 until 8) out[at + index] = (value ushr (8 * (7 - index))).toByte()
    return at + 8
  }
}

/**
 * Hyperliquid's user-signed actions, as `sign_user_signed_action` in the official Python SDK:
 * EIP-712 of the action's own fields under domain `HyperliquidSignTransaction`, version `1`, the
 * chain id the action declares in `signatureChainId`, and the zero verifying contract.
 */
object UserSigning {
  const val DOMAIN_NAME: String = "HyperliquidSignTransaction"
  const val DOMAIN_VERSION: String = "1"

  fun domain(signatureChainId: String): Eip712Struct =
    Eip712.domain(DOMAIN_NAME, DOMAIN_VERSION, parseChainId(signatureChainId), Eip712.ZERO_ADDRESS)

  /** The 66-byte EIP-712 signing input for [message] under [signatureChainId]'s domain. */
  fun signingInput(message: Eip712Struct, signatureChainId: String): ByteArray =
    Eip712.signingInput(domain(signatureChainId), message)

  /** `"0x66eee"` → 421614. */
  fun parseChainId(signatureChainId: String): Long {
    val digits = signatureChainId.trim()
    require(digits.startsWith("0x") && digits.length in 3..17) {
      "signatureChainId must be 0x followed by hex digits"
    }
    return digits.drop(2).toLongOrNull(16)?.takeIf { it > 0L }
      ?: throw IllegalArgumentException("signatureChainId must be 0x followed by hex digits")
  }
}
