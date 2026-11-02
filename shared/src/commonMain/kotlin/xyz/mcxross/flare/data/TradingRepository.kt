package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradeAction

@Serializable
enum class FeePayment {
  SPONSORED,
  SELF_PAY,
  /** Transfers may retry a definite sponsor rejection within the same authorization. */
  AUTOMATIC,
}

data class PendingTransaction(
  val hash: String,
  val network: String,
  val operation: String,
  val state: String,
  val createdAtMs: Long,
  val updatedAtMs: Long,
)

data class ExactAssetBalance(val total: String, val available: String)

data class ReconciliationResult(val finalized: Int, val unresolved: Int)

/** Public fee consent; the venue binds [reviewId] to the reviewed account and actions. */
data class OrderFeeReview(
  val reviewId: String,
  val actions: List<TradeAction>,
  val maximumPerTransactionOctas: ULong,
  val maximumTotalOctas: ULong,
  val tradingKeyBalance: ULong?,
  val tradingKeyAddress: String,
  /** Deployment policy; null means that availability could not be established. */
  val sponsorshipAvailable: Boolean? = null,
) {
  val canSelfPay: Boolean
    get() = tradingKeyBalance?.let { it >= maximumTotalOctas } == true

  val fundingDeficit: ULong?
    get() = tradingKeyBalance?.let { if (it < maximumTotalOctas) maximumTotalOctas - it else 0uL }
}

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

  /** Reads a bounded fee budget without requesting wallet authorization or signing. */
  suspend fun prepareOrderFees(actions: List<TradeAction>): OrderFeeReview? = null

  /** Authorizes the reviewed order once, including only the explicitly accepted fee fallback. */
  suspend fun <T> confirmedOrderFees(
    review: OrderFeeReview,
    allowSelfPay: Boolean,
    prompt: VaultPrompt,
    block: suspend () -> T,
  ): T = confirmedAction(prompt, block)

  suspend fun transactionStatus(reference: String): ActionState

  /** The fee token the device trading key holds, in its smallest unit. */
  suspend fun tradingKeyFeeBalance(): ULong

  /** The fee token the owner wallet holds, which pays an owner action's fee if Flare can't. */
  suspend fun ownerFeeBalance(): ULong = error("The owner wallet's balance isn't available")

  suspend fun assetBalanceDecimal(accountAddress: String, symbol: String): ExactAssetBalance =
    error("Exact asset balances are unavailable")

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
 * Returns the amount of fee token to send the device trading key, at least [minimumTopUp], or null
 * when no top-up applies.
 */
suspend fun TradingRepository.tradingKeyTopUpFor(
  failure: ActionState.Failed,
  profile: WalletProfile,
  minimumTopUp: ULong,
): ULong? {
  val offer = failure.selfPay ?: return null
  val reserve = maxOf(offer.estimate, offer.reserve ?: offer.estimate)
  if (profile.apiWalletAddress == null || profile.ownerAddress == null) return null
  val balance = runSuspendCatching { tradingKeyFeeBalance() }.getOrNull() ?: return null
  if (balance >= reserve) return null
  return maxOf(minimumTopUp, reserve - balance)
}
