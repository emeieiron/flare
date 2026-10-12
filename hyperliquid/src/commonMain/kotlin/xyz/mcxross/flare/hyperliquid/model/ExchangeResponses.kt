package xyz.mcxross.flare.hyperliquid.model

import kotlinx.serialization.json.JsonElement

/**
 * What became of a request to `/exchange`. Only [Rejected] means the action certainly did not
 * execute; after [Unknown] it may have, so callers reconcile (order status by cloid, or a state
 * query) instead of marking it failed or sending it again.
 */
sealed interface ExchangeOutcome {
  /**
   * Hyperliquid processed the request (`"status": "ok"`). Individual orders or cancels inside it
   * can still have failed: see [ExchangeResponse.Orders] and [ExchangeResponse.Cancels].
   */
  data class Accepted(val response: ExchangeResponse) : ExchangeOutcome

  /**
   * Hyperliquid refused the whole request without executing it: `"status": "err"` (bad
   * signature, unknown user, nonce already used, …) or an HTTP 4xx answer.
   */
  data class Rejected(val reason: String) : ExchangeOutcome

  /**
   * No definite answer: the connection failed, the server failed (HTTP 5xx), or the reply was not
   * the JSON Hyperliquid sends. The action may have executed.
   */
  data class Unknown(val reason: String) : ExchangeOutcome
}

sealed interface ExchangeResponse {
  /** `{"type": "default"}`: leverage, margin, transfers, approvals and other simple actions. */
  data object Default : ExchangeResponse

  /** `order`: one status per order, in request order. */
  data class Orders(val statuses: List<OrderStatus>) : ExchangeResponse

  /** `cancel` (also the answer to `cancelByCloid`): one status per cancel, in request order. */
  data class Cancels(val statuses: List<CancelStatus>) : ExchangeResponse

  data class TwapPlaced(val status: TwapOrderStatus) : ExchangeResponse

  data class TwapCanceled(val status: CancelStatus) : ExchangeResponse

  /** A response type this SDK does not model yet, kept for the caller. */
  data class Other(val type: String, val data: JsonElement?) : ExchangeResponse
}

sealed interface OrderStatus {
  data class Resting(val oid: Long, val cloid: Cloid? = null) : OrderStatus

  data class Filled(
    val totalSize: String,
    val averagePrice: String,
    val oid: Long,
    val cloid: Cloid? = null,
  ) : OrderStatus

  /** This order was rejected; others in the same action may still have been placed. */
  data class Error(val message: String) : OrderStatus

  /** A TP/SL child waiting for its parent to fill (`normalTpsl`). */
  data object WaitingForFill : OrderStatus

  /** A trigger order waiting for its trigger price. */
  data object WaitingForTrigger : OrderStatus

  /** A status shape this SDK does not know; the order's fate must be reconciled. */
  data class Unrecognized(val kind: String) : OrderStatus
}

sealed interface CancelStatus {
  data object Success : CancelStatus

  data class Error(val message: String) : CancelStatus

  data class Unrecognized(val kind: String) : CancelStatus
}

sealed interface TwapOrderStatus {
  data class Running(val twapId: Long) : TwapOrderStatus

  data class Error(val message: String) : TwapOrderStatus

  data class Unrecognized(val kind: String) : TwapOrderStatus
}
