package xyz.mcxross.flare.hyperliquid.signing

/** Lower-case hex with a `0x` prefix. */
internal fun ByteArray.toHex0x(): String = "0x" + toHexDigits()

/** Lower-case hex without a prefix. */
internal fun ByteArray.toHexDigits(): String {
  val out = StringBuilder(size * 2)
  for (byte in this) {
    val value = byte.toInt() and 0xff
    out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
  }
  return out.toString()
}

/**
 * Decodes hex with or without a `0x` prefix. Throws [IllegalArgumentException] for an odd length
 * or a non-hex character, without echoing the input (it may be key material).
 */
internal fun decodeHex(value: String, what: String = "hex"): ByteArray {
  val trimmed = value.trim()
  val digits =
    if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) trimmed.drop(2) else trimmed
  require(digits.length % 2 == 0) { "$what must have an even number of hex digits" }
  return ByteArray(digits.length / 2) { index ->
    val high = hexValue(digits[2 * index])
    val low = hexValue(digits[2 * index + 1])
    require(high >= 0 && low >= 0) { "$what must be hex" }
    ((high shl 4) or low).toByte()
  }
}

private fun hexValue(char: Char): Int =
  when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
  }

private const val HEX_DIGITS = "0123456789abcdef"
