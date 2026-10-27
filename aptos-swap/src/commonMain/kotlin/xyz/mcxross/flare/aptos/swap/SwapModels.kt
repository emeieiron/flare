package xyz.mcxross.flare.aptos.swap

import kotlinx.serialization.Serializable
import xyz.mcxross.kaptos.core.crypto.sha3Hash
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.TransactionPayload

object AptosSwapAssets {
  const val APT = "0xa"
  const val USDC = "0xbae207659db88bea0cbead6da0ed00aac12edcdda169e591cd41c94180b46f3b"
  const val ROUTER = "0x1c3206329806286fd2223647c9f9b130e66baeb6d7224a18c1f642ffe48f3b4c::panora_swap::router_entry"
}

@Serializable
enum class SwapKind { EXACT_INPUT, EXACT_OUTPUT }

@Serializable
data class AptosSwapRequest(
  val owner: String,
  val kind: SwapKind,
  val amount: String,
  val slippageBps: Int = 50,
) {
  init {
    AccountAddress.fromString(owner)
    require(amount.matches(Regex("[1-9][0-9]*")) && amount.toULongOrNull() != null)
    require(slippageBps in 1..100)
  }
}

/** Frozen payload bytes prevent changes between quote review, simulation and signing. */
class ValidatedSwapPlan internal constructor(
  val id: String,
  val request: AptosSwapRequest,
  val expectedInput: ULong,
  val expectedOutput: ULong,
  val maximumInput: ULong,
  val minimumOutput: ULong,
  val feeAmount: ULong,
  val expiresAtMs: Long,
  val sponsorshipAuthorization: String,
  payload: TransactionPayload.EntryFunction,
) {
  private val bytes = payload.toBcs()
  val payloadHash: String = digest(bytes)

  fun payload(): TransactionPayload.EntryFunction =
    TransactionPayload.fromBcs(bytes.copyOf()) as TransactionPayload.EntryFunction

  fun requireCurrent(nowMs: Long, owner: String) {
    require(nowMs < expiresAtMs) { "The swap quote expired. Request a fresh quote." }
    require(AccountAddress.fromString(owner) == AccountAddress.fromString(request.owner)) {
      "The swap quote belongs to another wallet"
    }
  }
}

fun interface SwapProvider {
  suspend fun quote(request: AptosSwapRequest): ValidatedSwapPlan
}

internal fun digest(bytes: ByteArray): String =
  "0x" + sha3Hash(bytes).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

internal fun units(value: String, decimals: Int): ULong {
  require(value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "Invalid decimal quantity" }
  val parts = value.split('.')
  val fraction = parts.getOrElse(1) { "" }
  require(fraction.length <= decimals || fraction.drop(decimals).all { it == '0' }) {
    "Quantity exceeds asset precision"
  }
  return requireNotNull((parts[0] + fraction.take(decimals).padEnd(decimals, '0')).toULongOrNull()) {
    "Quantity exceeds u64"
  }
}

internal fun decimal(value: ULong, decimals: Int): String {
  val padded = value.toString().padStart(decimals + 1, '0')
  return padded.dropLast(decimals) + "." + padded.takeLast(decimals)
}
