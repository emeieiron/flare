package xyz.mcxross.flare.core

/**
 * [value] as a whole number of 10^-[decimals] units, exactly. [field] names the input in errors,
 * as in "Take profit supports at most 2 decimal places".
 */
fun decimalUnits(value: String, field: String, decimals: Int): Result<ULong> = runCatching {
  require(decimals in 0..18) { "$field uses an unsupported precision" }
  val normalized = value.trim()
  require(normalized.isNotEmpty()) { "$field is required" }
  require(normalized.none { it == 'e' || it == 'E' }) { "$field must not use exponent notation" }
  require(normalized.count { it == '.' } <= 1) { "$field is not a decimal number" }
  require(normalized.all { it.isDigit() || it == '.' }) { "$field must be positive" }

  val parts = normalized.split('.', limit = 2)
  val whole = parts[0].ifEmpty { "0" }
  val fraction = parts.getOrElse(1) { "" }
  require(fraction.length <= decimals) { "$field supports at most $decimals decimal places" }

  val digits =
    (whole.trimStart('0').ifEmpty { "0" } + fraction.padEnd(decimals, '0')).trimStart('0').ifEmpty {
      "0"
    }
  digits.toULongOrNull() ?: error("$field exceeds u64")
}

/** Whole units of 10^-[decimals] as decimal text: 1050 at 2 decimals reads "10.5". */
fun ULong.toDecimalString(decimals: Int): String {
  require(decimals >= 0)
  if (decimals == 0) return toString()
  val digits = toString().padStart(decimals + 1, '0')
  val whole = digits.dropLast(decimals)
  val fraction = digits.takeLast(decimals).trimEnd('0')
  return if (fraction.isEmpty()) whole else "$whole.$fraction"
}
