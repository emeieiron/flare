package xyz.mcxross.flare.data

private val DecimalBalance = Regex("""([+-]?)(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?""")

/** Display only: round decimal text directly, retaining nonzero balances smaller than 0.01. */
fun formatTokenBalance(amount: String): String {
  val match = DecimalBalance.matchEntire(amount) ?: return "—"
  val exponent =
    match.groupValues[4].let { if (it.isEmpty()) 0 else it.toIntOrNull() } ?: return "—"
  if (exponent !in -1_000..1_000) return "—"
  val digits = match.groupValues[2] + match.groupValues[3]
  val point = match.groupValues[2].length + exponent
  val whole =
    if (point <= 0) "0" else digits.padEnd(point, '0').take(point).trimStart('0').ifEmpty { "0" }
  val fraction = if (point < 0) "0".repeat(-point) + digits else digits.drop(point)
  val nonzero = digits.any { it != '0' }
  val sign = if (match.groupValues[1] == "-" && nonzero) "−" else ""
  val decimals = fraction.padEnd(3, '0')
  if (whole == "0" && decimals.take(2) == "00" && nonzero) return sign + "<0.01"

  val rounded = (whole + decimals.take(2)).toCharArray()
  var carry = decimals[2] >= '5'
  for (index in rounded.lastIndex downTo 0) {
    if (!carry) break
    if (rounded[index] == '9') rounded[index] = '0'
    else {
      rounded[index] = rounded[index] + 1
      carry = false
    }
  }
  val hundredths = (if (carry) "1" else "") + rounded.concatToString()
  return sign + hundredths.dropLast(2) + "." + hundredths.takeLast(2)
}

fun formatTokenBalance(amount: Double): String =
  if (amount.isFinite()) formatTokenBalance(amount.toString()) else "—"
