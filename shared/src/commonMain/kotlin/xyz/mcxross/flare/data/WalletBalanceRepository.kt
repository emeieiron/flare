package xyz.mcxross.flare.data

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
    get() = amount?.let(::formatTokenBalance) ?: "—"
}
