package xyz.mcxross.flare.hyperliquid.signing

import xyz.mcxross.flare.hyperliquid.model.normalizeEvmAddress

/** Keccak-256, via FastKrypto. */
internal fun keccak256(bytes: ByteArray): ByteArray = xyz.mcxross.fastkrypto.keccak256(bytes)

/** One value of a flat EIP-712 struct; only the types Hyperliquid's messages use. */
sealed interface Eip712Value {
  val type: String

  data class Str(val value: String) : Eip712Value {
    override val type: String
      get() = "string"
  }

  /** A 20-byte address; any case is accepted, since only its bytes are hashed. */
  class Address(value: String) : Eip712Value {
    val value: String = normalizeEvmAddress(value)

    override val type: String
      get() = "address"

    override fun equals(other: Any?): Boolean = other is Address && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Address($value)"
  }

  data class Uint64(val value: Long) : Eip712Value {
    init {
      require(value >= 0L) { "uint64 must not be negative" }
    }

    override val type: String
      get() = "uint64"
  }

  data class Uint256(val value: Long) : Eip712Value {
    init {
      require(value >= 0L) { "uint256 must not be negative" }
    }

    override val type: String
      get() = "uint256"
  }

  data class Bool(val value: Boolean) : Eip712Value {
    override val type: String
      get() = "bool"
  }

  class Bytes32(value: ByteArray) : Eip712Value {
    val value: ByteArray = value.copyOf()

    init {
      require(value.size == 32) { "bytes32 must be 32 bytes" }
    }

    override val type: String
      get() = "bytes32"

    override fun equals(other: Any?): Boolean = other is Bytes32 && other.value.contentEquals(value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "Bytes32(${value.toHex0x()})"
  }
}

/**
 * A flat EIP-712 struct: a primary type with fields in declaration order and no nested structs,
 * which covers every message Hyperliquid signs. The type name is used verbatim, colons included
 * (`HyperliquidTransaction:UsdSend`).
 */
class Eip712Struct(val typeName: String, val fields: List<Pair<String, Eip712Value>>) {
  /** `Name(type1 name1,type2 name2)`. */
  val encodedType: String
    get() = fields.joinToString(",", prefix = "$typeName(", postfix = ")") { (name, value) ->
      "${value.type} $name"
    }

  fun typeHash(): ByteArray = keccak256(encodedType.encodeToByteArray())

  /** `keccak256(typeHash ‖ enc(field1) ‖ …)`. */
  fun hashStruct(): ByteArray {
    val out = ByteArray(32 * (fields.size + 1))
    typeHash().copyInto(out, 0)
    fields.forEachIndexed { index, (_, value) ->
      encodeValue(value).copyInto(out, 32 * (index + 1))
    }
    return keccak256(out)
  }
}

/** EIP-712 typed-data encoding for the domains Hyperliquid uses. */
object Eip712 {
  const val ZERO_ADDRESS: String = "0x0000000000000000000000000000000000000000"

  /** `EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)`. */
  fun domain(
    name: String,
    version: String,
    chainId: Long,
    verifyingContract: String,
  ): Eip712Struct =
    Eip712Struct(
      "EIP712Domain",
      listOf(
        "name" to Eip712Value.Str(name),
        "version" to Eip712Value.Str(version),
        "chainId" to Eip712Value.Uint256(chainId),
        "verifyingContract" to Eip712Value.Address(verifyingContract),
      ),
    )

  /**
   * The 66 bytes a wallet signs: `0x19 0x01 ‖ hashStruct(domain) ‖ hashStruct(message)`. The
   * signature is over its Keccak-256 hash.
   */
  fun signingInput(domain: Eip712Struct, message: Eip712Struct): ByteArray {
    val out = ByteArray(SIGNING_INPUT_SIZE)
    out[0] = 0x19
    out[1] = 0x01
    domain.hashStruct().copyInto(out, 2)
    message.hashStruct().copyInto(out, 34)
    return out
  }

  const val SIGNING_INPUT_SIZE: Int = 66
}

private fun encodeValue(value: Eip712Value): ByteArray =
  when (value) {
    is Eip712Value.Str -> keccak256(value.value.encodeToByteArray())
    is Eip712Value.Address -> ByteArray(32).also { decodeHex(value.value).copyInto(it, 12) }
    is Eip712Value.Uint64 -> word(value.value)
    is Eip712Value.Uint256 -> word(value.value)
    is Eip712Value.Bool -> word(if (value.value) 1L else 0L)
    is Eip712Value.Bytes32 -> value.value.copyOf()
  }

/** A non-negative [value] as a 32-byte big-endian word. */
private fun word(value: Long): ByteArray =
  ByteArray(32).also { out ->
    for (index in 0 until 8) out[31 - index] = (value ushr (8 * index)).toByte()
  }
