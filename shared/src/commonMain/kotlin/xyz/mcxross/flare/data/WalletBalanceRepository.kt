package xyz.mcxross.flare.data

import xyz.mcxross.flare.core.decimalUnits

/** On-chain funds at a wallet address, independently of any venue trading account or collateral. */
interface WalletBalanceRepository {
  val symbols: List<String>

  /** The token quantity as exact decimal text; a failed read must throw rather than return zero. */
  suspend fun balance(address: String, symbol: String): String
}

data class WalletTokenBalance(
  val symbol: String,
  val amount: String? = null,
  val unavailable: Boolean = false,
) {
  val displayAmount: String
    get() {
      val value = amount ?: return "—"
      if (symbol == "APT") {
        val units = decimalUnits(value, "APT balance", 8).getOrThrow()
        if (units in 1uL until 1_000_000uL) return "<0.01"
        val hundredths = units / 1_000_000uL + if (units % 1_000_000uL >= 500_000uL) 1uL else 0uL
        return "${hundredths / 100uL}.${(hundredths % 100uL).toString().padStart(2, '0')}"
      }
      if (symbol != "USDC") return value
      val parts = value.split('.', limit = 2)
      return parts[0] + "." + parts.getOrElse(1) { "" }.padEnd(2, '0')
    }
}
