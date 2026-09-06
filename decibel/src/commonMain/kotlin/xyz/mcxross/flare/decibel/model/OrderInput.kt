package xyz.mcxross.flare.decibel.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
data class MarketPrecision(
  val priceDecimals: Int,
  val sizeDecimals: Int,
  val tickSize: ULong,
  val lotSize: ULong,
  val minimumSize: ULong,
  val minimumPrice: ULong = 0u,
  val maximumPrice: ULong = ULong.MAX_VALUE,
)

@JvmInline
@Serializable
value class DecimalInput(val value: String) {
  init {
    require(value.isNotBlank()) { "Decimal input cannot be blank" }
  }
}

@JvmInline
@Serializable
value class SlippageBps(val value: UInt) {
  init {
    require(value in 1u..1_000u) { "Slippage must be between 1 and 1,000 bps" }
  }
}

@Serializable
enum class OrderSide {
  BUY,
  SELL,
}

@Serializable
enum class OrderType {
  LIMIT,
  MARKET,
}

@Serializable
enum class TimeInForce(val chainValue: UByte) {
  GOOD_TILL_CANCELLED(0u),
  POST_ONLY(1u),
  IMMEDIATE_OR_CANCEL(2u),
}

@Serializable
enum class MarginMode {
  CROSS,
  ISOLATED,
}

@Serializable
data class OrderDraft(
  val marketAddress: String,
  val side: OrderSide,
  val type: OrderType,
  val size: DecimalInput,
  val limitPrice: DecimalInput? = null,
  val timeInForce: TimeInForce = TimeInForce.GOOD_TILL_CANCELLED,
  val reduceOnly: Boolean = false,
  val slippage: SlippageBps = SlippageBps(50u),
  val clientOrderId: String? = null,
  val stopPrice: DecimalInput? = null,
  val takeProfitTriggerPrice: DecimalInput? = null,
  val takeProfitLimitPrice: DecimalInput? = null,
  val stopLossTriggerPrice: DecimalInput? = null,
  val stopLossLimitPrice: DecimalInput? = null,
)

@Serializable
data class ValidatedOrder(
  val marketAddress: String,
  val side: OrderSide,
  val price: ULong,
  val size: ULong,
  val timeInForce: TimeInForce,
  val reduceOnly: Boolean,
  val clientOrderId: String?,
  val stopPrice: ULong?,
  val takeProfitTriggerPrice: ULong?,
  val takeProfitLimitPrice: ULong?,
  val stopLossTriggerPrice: ULong?,
  val stopLossLimitPrice: ULong?,
)

sealed interface OrderValidationError {
  data class InvalidDecimal(val field: String, val reason: String) : OrderValidationError

  data class InvalidMarketAddress(val field: String, val reason: String) : OrderValidationError

  data class MarketMismatch(val field: String, val expected: String, val actual: String) :
    OrderValidationError

  data class Overflow(val field: String) : OrderValidationError

  data class TooPrecise(val field: String, val allowedDecimals: Int) : OrderValidationError

  data class NotAligned(val field: String, val increment: ULong) : OrderValidationError

  data class BelowMinimum(val field: String, val minimum: ULong) : OrderValidationError

  data class AboveMaximum(val field: String, val maximum: ULong) : OrderValidationError

  data object MissingLimitPrice : OrderValidationError

  data object MissingMarketPrice : OrderValidationError
}

data class OrderValidationResult(
  val value: ValidatedOrder? = null,
  val errors: List<OrderValidationError> = emptyList(),
) {
  val isValid: Boolean
    get() = value != null && errors.isEmpty()
}
