package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradeAction

enum class FeePayment {
  SPONSORED,
  SELF_PAY,
}

data class PendingTransaction(
  val hash: String,
  val network: String,
  val operation: String,
  val state: String,
  val createdAtMs: Long,
  val updatedAtMs: Long,
)

data class ReconciliationResult(val finalized: Int, val unresolved: Int)

/**
 * Sends the person's actions to the exchange and follows them to an outcome. Every action is
 * journaled before it is sent, so an uncertain outcome is reconciled rather than sent twice.
 */
interface TradingRepository {
  val pendingTransactions: Flow<List<PendingTransaction>>

  /**
   * Signs with the key the action requires: the owner key for custody and delegation, the device
   * trading key for everything else. Callers describe the action, never the key.
   */
  fun execute(
    action: TradeAction,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
    onPrepared: suspend (String) -> Unit = {},
  ): Flow<ActionState>

  suspend fun transactionStatus(reference: String): ActionState

  /** The fee token the device trading key holds, in its smallest unit. */
  suspend fun tradingKeyFeeBalance(): ULong

  /** The fee token the owner wallet holds, which pays an owner action's fee if Flare can't. */
  suspend fun ownerFeeBalance(): ULong = error("The owner wallet's balance isn't available")

  suspend fun baseAssetBalance(accountAddress: String, symbol: String): Double = 0.0

  /** Sends [amount] of the fee token from the owner wallet to the device trading key. */
  fun topUpTradingKey(amount: ULong, prompt: VaultPrompt): Flow<ActionState>

  suspend fun reconcilePending(): ReconciliationResult

  /**
   * Confirms one user action that signs several transactions, so the person authenticates once for
   * the action rather than once per signature.
   */
  suspend fun <T> confirmedAction(prompt: VaultPrompt, block: suspend () -> T): T = block()
}

/**
 * Sponsorship can fall back to self-payment only when the signing key can pay the fee itself.
 * Returns the amount of fee token the device trading key is missing, or null when no top-up applies.
 */
suspend fun TradingRepository.tradingKeyTopUpFor(
  failure: ActionState.Failed,
  profile: WalletProfile,
): ULong? {
  val estimate = failure.selfPay?.estimate ?: return null
  if (profile.apiWalletAddress == null || profile.ownerAddress == null) return null
  val balance = runSuspendCatching { tradingKeyFeeBalance() }.getOrNull() ?: return null
  if (balance >= estimate) return null
  val buffered = if (estimate <= ULong.MAX_VALUE / 3uL) estimate * 3uL else estimate
  return maxOf(MINIMUM_TOP_UP, buffered)
}

private const val MINIMUM_TOP_UP = 1_000_000uL
