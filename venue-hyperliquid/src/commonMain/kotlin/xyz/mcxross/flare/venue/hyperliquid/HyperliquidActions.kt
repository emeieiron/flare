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
  ): ActionState {
    if (!submissionMutex.tryLock()) {
      return ActionState.Failed("Another action is already being authorized", definitelyNotSubmitted = true)
    }
    try {
      return wallets.withOwnerSigner(prompt) { signer ->
        send(action, signer.address, record) { nonce, beforeSend ->
          submitter.submit(action, signer, nonce, beforeSend)
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      return ActionState.Failed(
        error.message ?: "The action wasn’t signed",
        definitelyNotSubmitted = true,
      )
    } finally {
      submissionMutex.unlock()
    }
  }

  private suspend fun send(
    action: UserSignedAction,
    signer: String,
    record: (nonce: Long) -> JournaledAction,
    submit: suspend (nonce: Long, beforeSend: suspend () -> Unit) -> ExchangeOutcome,
  ): ActionState {
    val nonce = uniqueNonce(signer)
    val reference = nonce.toString()
    val profile = preferences.values.first().activeProfileId
    val now = clockMs()
    journal.upsert(
      PendingTransactionEntity(
        hash = reference,
        network = network.name,
        operation = journalOperation(JournaledAction.encode(record(nonce)), profile),
        state = JournalState.PREPARED,
        createdAtMs = now,
        updatedAtMs = now,
      )
    )
    var sent = false
    val outcome =
      try {
        submit(nonce) {
          updateState(reference, JournalState.SUBMITTING)
          sent = true
        }
      } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { settleInterrupted(reference, sent) }
        throw cancelled
      } catch (error: Exception) {
        settleInterrupted(reference, sent)
        return if (sent) ActionState.Pending(reference)
        else
          ActionState.Failed(
            error.message ?: "${action.type} wasn’t signed",
            definitelyNotSubmitted = true,
          )
      }
    return when (outcome) {
      is ExchangeOutcome.Accepted -> {
        journal.remove(reference)
        ActionState.Committed(reference)
      }
      is ExchangeOutcome.Rejected -> {
        journal.remove(reference)
        ActionState.Failed(outcome.reason, reference, committed = true)
      }
      // It may have executed: it stays journaled until Hyperliquid's state says what happened.
      is ExchangeOutcome.Unknown -> {
        updateState(reference, JournalState.RECONCILE_REQUIRED)
        ActionState.Pending(reference)
      }
    }
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
          }
        }
        .getOrNull() ?: return Reconciled.UNRESOLVED
    return when {
      inEffect -> Reconciled.CONFIRMED
      clockMs() > action.lapsesAtMs -> Reconciled.NOT_APPLIED
      else -> Reconciled.UNRESOLVED
    }
  }

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
  }
}

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
