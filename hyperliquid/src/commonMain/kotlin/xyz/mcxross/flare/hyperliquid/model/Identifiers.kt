package xyz.mcxross.flare.hyperliquid.model

import kotlin.jvm.JvmInline
import kotlin.random.Random

/**
 * Validates an EVM address and lower-cases it, the form Hyperliquid's requests and signed actions
 * use. Throws [IllegalArgumentException] for anything but `0x` and 40 hex digits.
 */
fun normalizeEvmAddress(value: String, field: String = "address"): String {
  val trimmed = value.trim()
  require(trimmed.length == 42 && trimmed.startsWith("0x", ignoreCase = true)) {
    "$field must be 0x followed by 40 hex digits"
  }
  require(trimmed.substring(2).all(::isHexDigit)) { "$field must be 0x followed by 40 hex digits" }
  return "0x" + trimmed.substring(2).lowercase()
}

/**
 * A client order id: 16 bytes written as `0x` and 32 lower-case hex digits. Flare sets one on
 * every order so an order whose submission outcome is unknown can be found again.
 */
@JvmInline
value class Cloid private constructor(val hex: String) {
  override fun toString(): String = hex

  companion object {
    fun parse(value: String): Cloid {
      val trimmed = value.trim()
      require(trimmed.length == 34 && trimmed.startsWith("0x", ignoreCase = true)) {
        "A cloid must be 0x followed by 32 hex digits"
      }
      require(trimmed.substring(2).all(::isHexDigit)) {
        "A cloid must be 0x followed by 32 hex digits"
      }
      return Cloid("0x" + trimmed.substring(2).lowercase())
    }

    fun random(random: Random = Random.Default): Cloid =
      Cloid("0x" + random.nextBytes(16).joinToString("") { byteToHex(it) })
  }
}

private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

private fun byteToHex(byte: Byte): String {
  val value = byte.toInt() and 0xff
  return HEX_DIGITS[value ushr 4].toString() + HEX_DIGITS[value and 0x0f]
}

private const val HEX_DIGITS = "0123456789abcdef"
