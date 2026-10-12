package xyz.mcxross.flare.venue.hyperliquid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradeAction

/**
 * Hyperliquid actions aren't sent yet: each one fails before signing, so nothing is ever journaled
 * or pending.
 */
class HyperliquidTradingRepository(private val client: HyperliquidClient) : TradingRepository {
  override val pendingTransactions: Flow<List<PendingTransaction>> = flowOf(emptyList())

  override fun execute(
    action: TradeAction,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<ActionState> = flowOf(unavailable())

  override suspend fun transactionStatus(reference: String): ActionState = unavailable()

  /** Hyperliquid charges no network fee, so there's no fee token to hold. */
  override suspend fun tradingKeyFeeBalance(): ULong = 0uL

  override suspend fun ownerFeeBalance(): ULong = 0uL

  /** The spot balance free to trade: what [accountAddress] holds of [symbol], less what orders hold. */
  override suspend fun baseAssetBalance(accountAddress: String, symbol: String): Double {
    val balance =
      client.info.spotClearinghouseState(accountAddress).balances.firstOrNull {
        it.coin.trim().equals(symbol, ignoreCase = true)
      } ?: return 0.0
    return (balance.total.toDoubleOrZero() - balance.hold.toDoubleOrZero()).coerceAtLeast(0.0)
  }

  override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt): Flow<ActionState> =
    flowOf(unavailable())

  override suspend fun reconcilePending(): ReconciliationResult = ReconciliationResult(0, 0)
}
