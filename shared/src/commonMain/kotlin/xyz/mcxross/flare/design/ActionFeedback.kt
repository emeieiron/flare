package xyz.mcxross.flare.design

import xyz.mcxross.flare.decibel.api.TransactionState

/**
 * How Flare talks about an action it is carrying out.
 *
 * People follow their own action - an order, a deposit, a cancellation - not the transaction
 * underneath it. Every surface that submits work shares this vocabulary: progress is reported with
 * the person's verb, a failure is one sentence they can act on, and chain detail (hashes, Move
 * status strings, journal states) never reaches the screen.
 */

/** True until an action has either settled or failed. */
val TransactionState.settling: Boolean
  get() =
    when (this) {
      TransactionState.Simulating,
      TransactionState.AwaitingAuthorization,
      TransactionState.Submitting,
      is TransactionState.Pending -> true
      is TransactionState.Committed,
      is TransactionState.Failed -> false
    }

/**
 * Rewrites a raw failure - a Move abort, a node error, a dropped connection - as one sentence.
 *
 * [outcome] names what did not happen. A recognised cause is appended to it as the reason, an
 * unrecognised technical failure leaves the outcome alone, and copy Flare wrote itself (input
 * guidance, preconditions) is already a sentence and passes through untouched.
 */
fun actionFailure(raw: String?, outcome: String): String {
  val detail = raw?.trim().orEmpty()
  val cause = failureCause(detail)
  return when {
    cause != null -> "$outcome $cause"
    detail.isNotEmpty() && !TechnicalDetail.containsMatchIn(detail) -> detail
    else -> outcome
  }
}

/** Move aborts, status codes, addresses and class names never read as an explanation. */
private val TechnicalDetail =
  Regex("""\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b|::|0x[0-9a-fA-F]|\b[a-z]+\.[a-z]+\.[A-Za-z]""")

private fun failureCause(detail: String): String? =
  when {
    detail.isBlank() -> null
    detail.mentions("INSUFFICIENT_COLLATERAL", "INSUFFICIENT_MARGIN", "EINSUFFICIENT_EQUITY") ->
      "There isn’t enough available margin for it."
    detail.mentions("INSUFFICIENT_BALANCE_FOR_TRANSACTION_FEE", "OUT_OF_GAS") ->
      "The network fee couldn’t be covered."
    detail.mentions("INSUFFICIENT_BALANCE", "INSUFFICIENT_FUNDS") -> "The balance is too low."
    detail.mentions("SEQUENCE_NUMBER") -> "Another action was still in flight. Try again."
    detail.mentions("TRANSACTION_EXPIRED", "EXPIRATION") -> "It took too long to confirm."
    detail.mentions(
      "unable to resolve",
      "unknownhost",
      "failed to connect",
      "connection refused",
      "connection reset",
      "network is unreachable",
      "timed out",
      "timeout",
    ) -> "Flare couldn’t reach the network."
    detail.mentions("429", "too many requests", "rate limit") ->
      "The service is busy. Try again in a moment."
    else -> null
  }

private fun String.mentions(vararg needles: String): Boolean =
  needles.any { contains(it, ignoreCase = true) }

/** The everyday name for a journaled operation, used while the app confirms it. */
fun settlingActionName(operation: String): String =
  when (operation) {
    "DEPOSIT" -> "deposit"
    "WITHDRAW",
    "TRANSFER_COLLATERAL" -> "withdrawal"
    "PLACE_ORDER" -> "order"
    "CANCEL_ORDER",
    "CANCEL_POSITION_TP_SL" -> "cancellation"
    "SET_POSITION_TP_SL" -> "exit update"
    "CONFIGURE_MARKET" -> "leverage change"
    "CREATE_SUBACCOUNT" -> "account setup"
    "DELEGATE_TRADING" -> "trading access"
    "REVOKE_DELEGATION" -> "access change"
    "TOP_UP_API_WALLET" -> "network fee"
    else -> "action"
  }
