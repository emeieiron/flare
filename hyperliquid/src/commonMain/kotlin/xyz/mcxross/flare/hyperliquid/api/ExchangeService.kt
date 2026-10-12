package xyz.mcxross.flare.hyperliquid.api

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment
import xyz.mcxross.flare.hyperliquid.model.CancelStatus
import xyz.mcxross.flare.hyperliquid.model.Cloid
import xyz.mcxross.flare.hyperliquid.model.ExchangeAction
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.hyperliquid.model.L1Action
import xyz.mcxross.flare.hyperliquid.model.OrderStatus
import xyz.mcxross.flare.hyperliquid.model.TwapOrderStatus
import xyz.mcxross.flare.hyperliquid.model.UserSignedAction
import xyz.mcxross.flare.hyperliquid.model.normalizeEvmAddress
import xyz.mcxross.flare.hyperliquid.signing.EvmSignature
import xyz.mcxross.flare.hyperliquid.signing.EvmSigner
import xyz.mcxross.flare.hyperliquid.signing.L1Signing
import xyz.mcxross.flare.hyperliquid.signing.NonceSource
import xyz.mcxross.flare.hyperliquid.signing.UserSigning

/**
 * A signed `/exchange` request, ready to send. Sign, journal the [nonce] (and each order's cloid),
 * then [ExchangeService.send]: if the outcome is unknown the journal entry is what reconciliation
 * starts from. [toString] leaves out the payload and signature.
 */
class SignedExchangeRequest
internal constructor(
  val action: ExchangeAction,
  val signer: String,
  val nonce: Long,
  val vaultAddress: String?,
  val expiresAfter: Long?,
  private val wireAction: JsonObject,
  private val signature: EvmSignature,
) {
  /** `{action, nonce, signature, vaultAddress, expiresAfter?}`, as the Python SDK posts it. */
  fun body(): JsonObject = buildJsonObject {
    put("action", wireAction)
    put("nonce", nonce)
    put("signature", signature.toJson())
    put("vaultAddress", vaultAddress?.let(::JsonPrimitive) ?: JsonNull)
    expiresAfter?.let { put("expiresAfter", it) }
  }

  override fun toString(): String =
    "SignedExchangeRequest(type=${action.type}, signer=$signer, nonce=$nonce)"
}

/**
 * Hyperliquid's `/exchange`: signs actions with an [EvmSigner] and sends them once, never
 * retrying (a retry after an unknown outcome could execute twice). Neither signatures nor payloads
 * are logged.
 */
interface ExchangeService {
  /**
   * Signs an L1 action. [vaultAddress] acts for a sub-account or vault the signer's user controls;
   * [expiresAfter] (ms) makes Hyperliquid reject the action after that time.
   */
  suspend fun sign(
    action: L1Action,
    signer: EvmSigner,
    vaultAddress: String? = null,
    expiresAfter: Long? = null,
  ): SignedExchangeRequest

  /** Signs a user-signed action; [signer] must be the account owner, not an agent. */
  suspend fun sign(action: UserSignedAction, signer: EvmSigner): SignedExchangeRequest

  /** Posts [request] once; a server or transport failure is an outcome, never an exception. */
  suspend fun send(request: SignedExchangeRequest): ExchangeOutcome

  suspend fun submit(
    action: L1Action,
    signer: EvmSigner,
    vaultAddress: String? = null,
    expiresAfter: Long? = null,
  ): ExchangeOutcome = send(sign(action, signer, vaultAddress, expiresAfter))

  suspend fun submit(action: UserSignedAction, signer: EvmSigner): ExchangeOutcome =
    send(sign(action, signer))
}

internal class DefaultExchangeService(
  private val api: HyperliquidApi,
  private val deployment: HyperliquidDeployment,
  private val nonces: NonceSource,
) : ExchangeService {
  override suspend fun sign(
    action: L1Action,
    signer: EvmSigner,
    vaultAddress: String?,
    expiresAfter: Long?,
  ): SignedExchangeRequest {
    val vault = vaultAddress?.let { normalizeEvmAddress(it, "vaultAddress") }
    require(vault == null || action.allowsVaultAddress) {
      "${action.type} is signed without a vaultAddress"
    }
    val wire = action.toWire()
    val nonce = nonces.next()
    val signingInput = L1Signing.signingInput(wire, nonce, deployment, vault, expiresAfter)
    return SignedExchangeRequest(
      action = action,
      signer = signer.address,
      nonce = nonce,
      vaultAddress = vault,
      expiresAfter = expiresAfter,
      wireAction = wire,
      signature = signer.signTypedData(signingInput),
    )
  }

  override suspend fun sign(action: UserSignedAction, signer: EvmSigner): SignedExchangeRequest {
    val nonce = nonces.next()
    val signingInput =
      UserSigning.signingInput(action.message(deployment, nonce), deployment.signatureChainId)
    return SignedExchangeRequest(
      action = action,
      signer = signer.address,
      nonce = nonce,
      vaultAddress = null,
      expiresAfter = null,
      wireAction = action.toWire(deployment, nonce),
      signature = signer.signTypedData(signingInput),
    )
  }

  override suspend fun send(request: SignedExchangeRequest): ExchangeOutcome {
    val reply =
      try {
        api.exchange(request.body())
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: HyperliquidApiError) {
        return if (isDefiniteRejection(error.statusCode)) {
          ExchangeOutcome.Rejected(error.message)
        } else {
          ExchangeOutcome.Unknown(error.message)
        }
      } catch (error: Exception) {
        return ExchangeOutcome.Unknown("Hyperliquid /exchange failed: ${error::class.simpleName}")
      }
    return parseExchangeReply(reply)
  }
}

/**
 * HTTP answers that mean the request was refused before execution: client errors (malformed body,
 * rate limit). 408 is left out, since the request may have been processed before the timeout.
 */
internal fun isDefiniteRejection(statusCode: Int): Boolean =
  statusCode in 400..499 && statusCode != 408

/** Parses an `/exchange` reply. */
internal fun parseExchangeReply(reply: JsonElement): ExchangeOutcome {
  val root = reply as? JsonObject ?: return unknown("not an object")
  val status = root.string("status") ?: return unknown("no status")
  val response = root["response"]
  return when (status) {
    "err" -> ExchangeOutcome.Rejected(errorText(response))
    "ok" -> parseResponse(response)?.let { ExchangeOutcome.Accepted(it) } ?: unknown("bad response")
    else -> unknown("status $status")
  }
}

private fun parseResponse(element: JsonElement?): ExchangeResponse? {
  val response = element as? JsonObject ?: return null
  val type = response.string("type") ?: return null
  val data = response["data"]
  return when (type) {
    "default" -> ExchangeResponse.Default
    "order" -> statuses(data)?.let { list -> ExchangeResponse.Orders(list.map(::orderStatus)) }
    "cancel" -> statuses(data)?.let { list -> ExchangeResponse.Cancels(list.map(::cancelStatus)) }
    "twapOrder" -> twapStatus(data)?.let { ExchangeResponse.TwapPlaced(twapOrderStatus(it)) }
    "twapCancel" -> twapStatus(data)?.let { ExchangeResponse.TwapCanceled(cancelStatus(it)) }
    else -> ExchangeResponse.Other(type, data)
  }
}

private fun statuses(data: JsonElement?): JsonArray? =
  (data as? JsonObject)?.get("statuses") as? JsonArray

private fun twapStatus(data: JsonElement?): JsonElement? = (data as? JsonObject)?.get("status")

private fun orderStatus(element: JsonElement): OrderStatus {
  if (element is JsonPrimitive && element.isString) {
    return when (element.content) {
      "waitingForFill" -> OrderStatus.WaitingForFill
      "waitingForTrigger" -> OrderStatus.WaitingForTrigger
      else -> OrderStatus.Unrecognized(element.content.take(MAX_KIND))
    }
  }
  val obj = element as? JsonObject ?: return OrderStatus.Unrecognized("non-object")
  obj.string("error")?.let { return OrderStatus.Error(it) }
  (obj["resting"] as? JsonObject)?.let { resting ->
    val oid = resting.long("oid") ?: return OrderStatus.Unrecognized("resting")
    return OrderStatus.Resting(oid, resting.cloid())
  }
  (obj["filled"] as? JsonObject)?.let { filled ->
    val oid = filled.long("oid")
    val totalSize = filled.string("totalSz")
    val averagePrice = filled.string("avgPx")
    if (oid == null || totalSize == null || averagePrice == null) {
      return OrderStatus.Unrecognized("filled")
    }
    return OrderStatus.Filled(totalSize, averagePrice, oid, filled.cloid())
  }
  return OrderStatus.Unrecognized(obj.keys.joinToString(",").take(MAX_KIND))
}

private fun cancelStatus(element: JsonElement): CancelStatus {
  if (element is JsonPrimitive && element.isString) {
    return if (element.content == "success") {
      CancelStatus.Success
    } else {
      CancelStatus.Unrecognized(element.content.take(MAX_KIND))
    }
  }
  val obj = element as? JsonObject ?: return CancelStatus.Unrecognized("non-object")
  obj.string("error")?.let { return CancelStatus.Error(it) }
  return CancelStatus.Unrecognized(obj.keys.joinToString(",").take(MAX_KIND))
}

private fun twapOrderStatus(element: JsonElement): TwapOrderStatus {
  val obj = element as? JsonObject ?: return TwapOrderStatus.Unrecognized("non-object")
  obj.string("error")?.let { return TwapOrderStatus.Error(it) }
  (obj["running"] as? JsonObject)?.long("twapId")?.let { return TwapOrderStatus.Running(it) }
  return TwapOrderStatus.Unrecognized(obj.keys.joinToString(",").take(MAX_KIND))
}

private fun errorText(response: JsonElement?): String {
  val text =
    when {
      response is JsonPrimitive && response.isString -> response.content
      response == null || response is JsonNull -> ""
      else -> "unexpected error shape"
    }
  return text.trim().take(MAX_ERROR).ifEmpty { "Hyperliquid rejected the action" }
}

private fun unknown(why: String): ExchangeOutcome.Unknown =
  ExchangeOutcome.Unknown("Hyperliquid /exchange returned an unrecognized reply ($why)")

private fun JsonObject.string(key: String): String? =
  (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? =
  (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun JsonObject.cloid(): Cloid? =
  string("cloid")?.let { runCatching { Cloid.parse(it) }.getOrNull() }

private const val MAX_KIND = 40
private const val MAX_ERROR = 300
