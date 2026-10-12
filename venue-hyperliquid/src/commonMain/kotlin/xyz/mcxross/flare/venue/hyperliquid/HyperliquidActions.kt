package xyz.mcxross.flare.venue.hyperliquid

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.Cloid
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.hyperliquid.model.L1Action
import xyz.mcxross.flare.hyperliquid.model.LedgerUpdate
import xyz.mcxross.flare.hyperliquid.model.UserSignedAction
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.PendingTransactionEntity
import xyz.mcxross.flare.store.TransactionJournalDao
import xyz.mcxross.flare.store.journalOperation
import xyz.mcxross.flare.store.operationName
import xyz.mcxross.flare.store.profileId
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.model.ActionState

/**
 * What a journal entry asked Hyperliquid to do, and so how reconciliation finds out whether it was
 * done. The entry's reference is the action's nonce, or an order's cloid. [lapsesAtMs] is when
 * Hyperliquid stops accepting the signed action, after which it can never take effect.
 */
internal sealed interface JournaledAction {
  /** The journal's operation name, as the screens know it. */
  val operation: String
  /** The account the action is for, which reconciliation reads. */
  val account: String
  val lapsesAtMs: Long

  fun details(): List<String>

  data class ApproveAgent(
    override val account: String,
    val agent: String,
    val name: String,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "DELEGATE_TRADING"

    override fun details() = listOf(agent, name.toHexText())
  }

  data class RevokeAgent(
    override val account: String,
    val agent: String,
    val name: String,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "REVOKE_DELEGATION"

    override fun details() = listOf(agent, name.toHexText())
  }

  data class ApproveBuilderFee(
    override val account: String,
    val builder: String,
    val tenthsOfBasisPoint: Int,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "APPROVE_MAX_BUILDER_FEE"

    override fun details() = listOf(builder, tenthsOfBasisPoint.toString())
  }

  data class RevokeBuilderFee(
    override val account: String,
    val builder: String,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "REVOKE_MAX_BUILDER_FEE"

    override fun details() = listOf(builder)
  }

  /** An order, journaled under its cloid. */
  data class PlaceOrder(override val account: String, override val lapsesAtMs: Long) :
    JournaledAction {
    override val operation = "PLACE_ORDER"

    override fun details() = emptyList<String>()
  }

  /** Cancels of the orders [orders] names, each by oid or cloid. */
  data class CancelOrders(
    override val account: String,
    val orders: List<String>,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "CANCEL_ORDER"

    override fun details() = listOf(orders.joinToString(","))
  }

  data class UpdateLeverage(
    override val account: String,
    val coin: String,
    val isCross: Boolean,
    val leverage: Int,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "CONFIGURE_MARKET"

    override fun details() = listOf(coin.toHexText(), isCross.toString(), leverage.toString())
  }

  /** A `withdraw3` to [destination] on Arbitrum; [amount] includes Hyperliquid's fee. */
  data class Withdraw(
    override val account: String,
    val destination: String,
    val amount: String,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "WITHDRAW"

    override fun details() = listOf(destination, amount)
  }

  /** A `usdSend` to another Hyperliquid account. */
  data class SendUsdc(
    override val account: String,
    val destination: String,
    val amount: String,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "TRANSFER"

    override fun details() = listOf(destination, amount)
  }

  /** A `usdClassTransfer` between the perp and spot balances. */
  data class MovePerpSpot(
    override val account: String,
    val amount: String,
    val toPerp: Boolean,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "MOVE_PERP_SPOT"

    override fun details() = listOf(amount, toPerp.toString())
  }

  /** A `vaultTransfer` into or out of [vault]. */
  data class VaultTransfer(
    override val account: String,
    val vault: String,
    val isDeposit: Boolean,
    val amount: String,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = if (isDeposit) "CONTRIBUTE_TO_VAULT" else "REDEEM_FROM_VAULT"

    override fun details() = listOf(vault, amount)
  }

  data class PlaceTwap(
    override val account: String,
    val coin: String,
    val isBuy: Boolean,
    val size: String,
    val minutes: Int,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "PLACE_TWAP_ORDER"

    override fun details() = listOf(coin.toHexText(), isBuy.toString(), size, minutes.toString())
  }

  data class CancelTwap(
    override val account: String,
    val twapId: Long,
    override val lapsesAtMs: Long,
  ) : JournaledAction {
    override val operation = "CANCEL_TWAP_ORDER"

    override fun details() = listOf(twapId.toString())
  }

  companion object {
    fun encode(action: JournaledAction): String =
      (listOf(action.operation, action.account, action.lapsesAtMs.toString()) + action.details())
        .joinToString(SEPARATOR)

    fun decode(name: String): JournaledAction? {
      val parts = name.split(SEPARATOR)
      if (parts.size < 3) return null
      val account = parts[1]
      val lapses = parts[2].toLongOrNull() ?: return null
      val rest = parts.drop(3)
      return runCatching {
          when (parts[0]) {
            "DELEGATE_TRADING" -> ApproveAgent(account, rest[0], rest[1].fromHexText(), lapses)
            "REVOKE_DELEGATION" -> RevokeAgent(account, rest[0], rest[1].fromHexText(), lapses)
            "APPROVE_MAX_BUILDER_FEE" -> ApproveBuilderFee(account, rest[0], rest[1].toInt(), lapses)
            "REVOKE_MAX_BUILDER_FEE" -> RevokeBuilderFee(account, rest[0], lapses)
            "PLACE_ORDER" -> PlaceOrder(account, lapses)
            "CANCEL_ORDER" -> CancelOrders(account, rest[0].split(","), lapses)
            "CONFIGURE_MARKET" ->
              UpdateLeverage(account, rest[0].fromHexText(), rest[1].toBooleanStrict(), rest[2].toInt(), lapses)
            "WITHDRAW" -> Withdraw(account, rest[0], rest[1], lapses)
            "TRANSFER" -> SendUsdc(account, rest[0], rest[1], lapses)
            "MOVE_PERP_SPOT" -> MovePerpSpot(account, rest[0], rest[1].toBooleanStrict(), lapses)
            "CONTRIBUTE_TO_VAULT" -> VaultTransfer(account, rest[0], true, rest[1], lapses)
            "REDEEM_FROM_VAULT" -> VaultTransfer(account, rest[0], false, rest[1], lapses)
            "PLACE_TWAP_ORDER" ->
              PlaceTwap(account, rest[0].fromHexText(), rest[1].toBooleanStrict(), rest[2], rest[3].toInt(), lapses)
            "CANCEL_TWAP_ORDER" -> CancelTwap(account, rest[0].toLong(), lapses)
            else -> null
          }
        }
        .getOrNull()
    }

    private const val SEPARATOR = ":"
  }
}

/** What reconciliation found out about a journaled action. */
internal enum class Reconciled {
  /** It took effect. */
  CONFIRMED,
  /** It can no longer take effect, and it isn't in effect. */
  NOT_APPLIED,
  /** Not known yet; the entry stays for the next pass. */
  UNRESOLVED,
}

/**
 * Sends Hyperliquid actions and follows them to an outcome. Each action takes a fresh nonce for its
 * signer and is journaled before it's sent; an answer that isn't definite leaves the entry
 * RECONCILE_REQUIRED for [reconcile], which asks Hyperliquid's state, never sending it again.
 */
class HyperliquidActions(
  private val journal: TransactionJournalDao,
  private val preferences: AppPreferences,
  private val wallets: HyperliquidSigningWallet,
  private val client: HyperliquidClient,
  private val submitter: HyperliquidSubmitter,
  private val nonces: HyperliquidNonces,
  private val network: Network,
  private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
  private val submissionMutex = Mutex()

  val pendingTransactions: Flow<List<PendingTransaction>> =
    journal
      .observePending()
      .combine(preferences.values) { entries, saved ->
        entries
          .filter { it.network == network.name && it.profileId == saved.activeProfileId }
          .map { it.toPendingTransaction() }
      }
      .distinctUntilChanged()

  /**
   * Signs [action] with the owner key and sends it. [record] describes it for reconciliation, given
   * the nonce it's signed with.
   */
  internal suspend fun sendAsOwner(
    action: UserSignedAction,
    prompt: VaultPrompt,
    record: (nonce: Long) -> JournaledAction,
  ): ActionState =
    exclusively(action.type) {
      wallets.withOwnerSigner(prompt) { signer ->
        val nonce = uniqueNonce(signer.address)
        send(action.type, nonce.toString(), record(nonce), { ActionState.Committed(nonce.toString()) }) {
          beforeSend ->
          submitter.submit(action, signer, nonce, beforeSend)
        }
      }
    }

  /**
   * Signs [action] with the device's agent key and sends it, to lapse [ACTION_TTL_MS] from now so
   * an answer that never comes resolves quickly. It's journaled under [cloid] when it places
   * orders, otherwise under its nonce. [settle] reads Hyperliquid's answer, given the reference;
   * null means the answer doesn't say, and the action is reconciled. [vaultAddress] is the
   * sub-account it acts for, if any.
   */
  internal suspend fun sendAsAgent(
    action: L1Action,
    prompt: VaultPrompt,
    cloid: Cloid? = null,
    vaultAddress: String? = null,
    record: (lapsesAtMs: Long) -> JournaledAction,
    settle: (response: ExchangeResponse, reference: String) -> ActionState?,
  ): ActionState =
    exclusively(action.type) {
      wallets.withAgentSigner(prompt) { signer ->
        val nonce = uniqueNonce(signer.address)
        val reference = cloid?.hex ?: nonce.toString()
        val expiresAfter = clockMs() + ACTION_TTL_MS
        send(action.type, reference, record(expiresAfter + CLOCK_SKEW_MS), { settle(it, reference) }) {
          beforeSend ->
          submitter.submit(action, signer, nonce, vaultAddress, expiresAfter, beforeSend)
        }
      }
    }

  private suspend fun exclusively(type: String, block: suspend () -> ActionState): ActionState {
    if (!submissionMutex.tryLock()) {
      return ActionState.Failed("Another action is already being authorized", definitelyNotSubmitted = true)
    }
    try {
      return block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      return ActionState.Failed(error.message ?: "$type wasn’t signed", definitelyNotSubmitted = true)
    } finally {
      submissionMutex.unlock()
    }
  }

  private suspend fun send(
    type: String,
    reference: String,
    journaled: JournaledAction,
    settle: (ExchangeResponse) -> ActionState?,
    submit: suspend (beforeSend: suspend () -> Unit) -> ExchangeOutcome,
  ): ActionState {
    val profile = preferences.values.first().activeProfileId
    val now = clockMs()
    journal.upsert(
      PendingTransactionEntity(
        hash = reference,
        network = network.name,
        operation = journalOperation(JournaledAction.encode(journaled), profile),
        state = JournalState.PREPARED,
        createdAtMs = now,
        updatedAtMs = now,
      )
    )
    var sent = false
    val outcome =
      try {
        submit {
          updateState(reference, JournalState.SUBMITTING)
          sent = true
        }
      } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { settleInterrupted(reference, sent) }
        throw cancelled
      } catch (error: Exception) {
        settleInterrupted(reference, sent)
        return if (sent) ActionState.Pending(reference)
        else ActionState.Failed(error.message ?: "$type wasn’t signed", definitelyNotSubmitted = true)
      }
    val settled =
      when (outcome) {
        is ExchangeOutcome.Accepted -> settle(outcome.response)
        is ExchangeOutcome.Rejected -> ActionState.Failed(outcome.reason, reference, committed = true)
        is ExchangeOutcome.Unknown -> null
      }
    // Without a definite answer it may have executed: it stays journaled until Hyperliquid's state says.
    if (settled == null) {
      updateState(reference, JournalState.RECONCILE_REQUIRED)
      return ActionState.Pending(reference)
    }
    journal.remove(reference)
    return settled
  }

  private suspend fun settleInterrupted(reference: String, sent: Boolean) {
    if (sent) updateState(reference, JournalState.RECONCILE_REQUIRED) else journal.remove(reference)
  }

  /** A nonce that no journal entry already uses as its reference. */
  private suspend fun uniqueNonce(signer: String): Long {
    while (true) {
      val nonce = nonces.next(signer)
      if (journal.find(nonce.toString()) == null) return nonce
    }
  }

  /** Settles what the journal holds for the active profile on this network. */
  suspend fun reconcile(): ReconciliationResult {
    var finalized = 0
    var unresolved = 0
    val profile = preferences.values.first().activeProfileId
    journal.pending().forEach { entry ->
      if (entry.network != network.name || entry.profileId != profile) return@forEach
      when (check(entry)) {
        Reconciled.CONFIRMED,
        Reconciled.NOT_APPLIED -> {
          journal.remove(entry.hash)
          finalized += 1
        }
        Reconciled.UNRESOLVED -> {
          updateState(entry.hash, JournalState.RECONCILE_REQUIRED)
          unresolved += 1
        }
      }
    }
    return ReconciliationResult(finalized = finalized, unresolved = unresolved)
  }

  suspend fun status(reference: String): ActionState {
    val entry = journal.find(reference) ?: return ActionState.Pending(reference)
    return when (check(entry)) {
      Reconciled.CONFIRMED -> {
        journal.remove(reference)
        ActionState.Committed(reference)
      }
      Reconciled.NOT_APPLIED -> {
        journal.remove(reference)
        ActionState.Failed("Hyperliquid didn’t carry this out", reference)
      }
      Reconciled.UNRESOLVED -> ActionState.Pending(reference)
    }
  }

  /** True while an earlier attempt to approve [agent] for [account] is still being reconciled. */
  internal suspend fun approvalPending(account: String, agent: String): Boolean {
    val profile = preferences.values.first().activeProfileId
    return journal.pending().any { entry ->
      entry.network == network.name &&
        entry.profileId == profile &&
        (JournaledAction.decode(entry.operationName) as? JournaledAction.ApproveAgent)?.let {
          it.account.sameAs(account) && it.agent.sameAs(agent)
        } == true
    }
  }

  /**
   * The sub-account an action for [account] signs for: the owner's keys act for its sub-accounts
   * through `vaultAddress`. Null for the owner's own account, or when the device holds no owner.
   */
  internal suspend fun vaultFor(account: String): String? {
    val owner = preferences.values.first().ownerAddress ?: return null
    return account.takeUnless { it.sameAs(owner) }
  }

  private suspend fun check(entry: PendingTransactionEntity): Reconciled {
    val action = JournaledAction.decode(entry.operationName) ?: return Reconciled.UNRESOLVED
    updateState(entry.hash, JournalState.RECONCILING)
    val inEffect =
      runSuspendCatching {
          when (action) {
            is JournaledAction.ApproveAgent ->
              client.info.extraAgents(action.account).any { it.address.sameAs(action.agent) }
            is JournaledAction.RevokeAgent ->
              client.info.extraAgents(action.account).none { it.address.sameAs(action.agent) }
            is JournaledAction.ApproveBuilderFee ->
              client.info.maxBuilderFee(action.account, action.builder) == action.tenthsOfBasisPoint
            is JournaledAction.RevokeBuilderFee ->
              client.info.maxBuilderFee(action.account, action.builder) == 0
            // Any status means Hyperliquid has the order; `unknownOid` means it doesn't (yet).
            is JournaledAction.PlaceOrder ->
              client.info.orderStatus(action.account, Cloid.parse(entry.hash)).isKnown
            // Once none of them is open, the cancel has done what it was for, whoever did it.
            is JournaledAction.CancelOrders -> {
              val wanted = action.orders.map(String::lowercase).toSet()
              client.info.openOrders(action.account).none {
                it.oid.toString() in wanted || it.cloid?.lowercase() in wanted
              }
            }
            is JournaledAction.UpdateLeverage ->
              client.info.activeAssetData(action.account, action.coin).leverage.let {
                it.isCross == action.isCross && it.value == action.leverage
              }
            is JournaledAction.Withdraw,
            is JournaledAction.SendUsdc,
            is JournaledAction.MovePerpSpot,
            is JournaledAction.VaultTransfer -> ledgerEntry(action, entry.hash, entry.createdAtMs) != null
            is JournaledAction.PlaceTwap ->
              client.info.twapHistory(action.account).any { HyperliquidLedger.isTwap(it, action, entry.createdAtMs) }
            // Once it's no longer running, the cancel has done what it was for.
            is JournaledAction.CancelTwap ->
              client.info.twapHistory(action.account)
                .filter { it.twapId == action.twapId }
                .maxByOrNull { it.time }
                ?.let { it.status.status != TWAP_RUNNING } == true
          }
        }
        .getOrNull() ?: return Reconciled.UNRESOLVED
    return when {
      inEffect -> Reconciled.CONFIRMED
      clockMs() > action.lapsesAtMs -> Reconciled.NOT_APPLIED
      else -> Reconciled.UNRESOLVED
    }
  }

  /**
   * The ledger entry a fund move journaled under [nonce] made, if it has been made. It's looked
   * for from shortly before [sentAtMs], when it was signed.
   */
  internal suspend fun ledgerEntry(action: JournaledAction, nonce: String, sentAtMs: Long): LedgerUpdate? =
    client.info.ledgerUpdates(action.account, sentAtMs - CLOCK_SKEW_MS)
      .firstOrNull { HyperliquidLedger.matches(it, action, nonce.toLongOrNull(), sentAtMs - CLOCK_SKEW_MS) }

  private suspend fun updateState(reference: String, state: String) {
    val current = journal.find(reference) ?: return
    journal.upsert(current.copy(state = state, updatedAtMs = clockMs()))
  }

  companion object {
    /**
     * Hyperliquid accepts a nonce only within (T − 2 days, T + 1 day) of its block time T, so a
     * signed action not executed within two days never will be. The margin covers clock skew.
     */
    const val NONCE_WINDOW_MS: Long = 2 * 24 * 60 * 60 * 1000L + 10 * 60 * 1000L

    /** How long Hyperliquid may take to execute an agent-signed action (`expiresAfter`). */
    const val ACTION_TTL_MS: Long = 60_000L

    /** How far this device's clock may run ahead of Hyperliquid's. */
    const val CLOCK_SKEW_MS: Long = 5 * 60 * 1000L
  }
}

private const val TWAP_RUNNING = "activated"

internal object JournalState {
  const val PREPARED = "PREPARED"
  const val SUBMITTING = "SUBMITTING"
  const val RECONCILING = "RECONCILING"
  const val RECONCILE_REQUIRED = "RECONCILE_REQUIRED"
}

private fun PendingTransactionEntity.toPendingTransaction(): PendingTransaction =
  PendingTransaction(
    hash = hash,
    network = network,
    operation = operationName.substringBefore(':'),
    state = state,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
  )

private fun String.toHexText(): String =
  encodeToByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.fromHexText(): String =
  chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString()
