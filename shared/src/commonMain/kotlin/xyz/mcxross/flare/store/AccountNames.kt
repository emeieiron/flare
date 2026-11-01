package xyz.mcxross.flare.store

const val ACCOUNT_NAME_MAX_LENGTH = 40

fun accountNameError(name: String): String? =
  when {
    name.isBlank() -> "Enter a name"
    name.trim().length > ACCOUNT_NAME_MAX_LENGTH ->
      "Use $ACCOUNT_NAME_MAX_LENGTH characters or fewer"
    name.any { it.isISOControl() } -> "Keep the name on one line"
    else -> null
  }

fun AccountProfile.walletDisplayName(position: Int): String =
  walletName?.takeIf(String::isNotBlank) ?: "Wallet ${position + 1}"

fun AccountProfile.tradingAccountDisplayName(
  address: String,
  venueLabel: String? = null,
  position: Int = 0,
): String =
  tradingAccountNickname(address)
    ?: venueLabel?.takeIf(String::isNotBlank)
    ?: "Trading account ${position + 1}"

fun AccountProfile.tradingAccountNickname(address: String): String? =
  tradingAccountNames[accountNameKey(address)]?.takeIf(String::isNotBlank)

/** Hex address padding and case do not change identity; other address formats retain their case. */
internal fun accountNameKey(address: String): String {
  val trimmed = address.trim()
  val hex = trimmed.removePrefix("0x").removePrefix("0X")
  return if (
    trimmed.startsWith("0x", ignoreCase = true) &&
      hex.isNotEmpty() &&
      hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
  )
    "0x${hex.lowercase().trimStart('0').ifEmpty { "0" }}"
  else trimmed
}
