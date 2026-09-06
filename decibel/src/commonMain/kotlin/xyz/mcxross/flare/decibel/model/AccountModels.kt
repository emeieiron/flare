package xyz.mcxross.flare.decibel.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AccountOverview(
  @SerialName("perp_equity_balance") val equityBalance: Double,
  @SerialName("perp_equity_haircutted") val haircuttedEquity: Double? = null,
  @SerialName("unrealized_pnl") val unrealizedPnl: Double,
  @SerialName("unrealized_funding_cost") val unrealizedFundingCost: Double,
  @SerialName("cross_margin_ratio") val crossMarginRatio: Double,
  @SerialName("maintenance_margin") val maintenanceMargin: Double,
  @SerialName("cross_account_leverage_ratio") val leverageRatio: Double? = null,
  @SerialName("total_margin") val totalMargin: Double,
  @SerialName("usdc_cross_withdrawable_balance") val crossWithdrawableBalance: Double,
  @SerialName("usdc_isolated_withdrawable_balance") val isolatedWithdrawableBalance: Double,
  @SerialName("margin_deficit") val marginDeficit: Double = 0.0,
  @SerialName("cross_available_to_trade") val availableToTrade: Double = 0.0,
)

@Serializable
data class Position(
  val market: String,
  val user: String,
  @Serializable(with = DecimalTextSerializer::class) val size: String,
  @SerialName("user_leverage") val leverage: Int,
  @SerialName("entry_price") val entryPrice: Double,
  @SerialName("is_isolated") val isIsolated: Boolean,
  @SerialName("is_deleted") val isDeleted: Boolean = false,
  @SerialName("unrealized_funding") val unrealizedFunding: Double,
  @SerialName("estimated_liquidation_price") val estimatedLiquidationPrice: Double,
  @SerialName("transaction_version") val transactionVersion: Long,
  @SerialName("has_fixed_sized_tpsls") val hasFixedSizeTpSl: Boolean,
  @SerialName("tp_order_id") val takeProfitOrderId: String? = null,
  @SerialName("tp_trigger_price") val takeProfitTriggerPrice: Double? = null,
  @SerialName("tp_limit_price") val takeProfitLimitPrice: Double? = null,
  @SerialName("sl_order_id") val stopLossOrderId: String? = null,
  @SerialName("sl_trigger_price") val stopLossTriggerPrice: Double? = null,
  @SerialName("sl_limit_price") val stopLossLimitPrice: Double? = null,
)

val Position.isLong: Boolean
  get() = !size.trim().startsWith('-')

val Position.absoluteSize: String
  get() = size.trim().removePrefix("-")

object DecimalTextSerializer : KSerializer<String> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("DecimalText", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): String =
    (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content ?: decoder.decodeString()

  override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class Order(
  @SerialName("asset_type") val assetType: AssetType = AssetType.PERP,
  val parent: String = "",
  val market: String,
  @SerialName("client_order_id") val clientOrderId: String? = null,
  @SerialName("order_id") val orderId: String,
  val status: String = "OPEN",
  @SerialName("order_type") val orderType: String = "",
  @SerialName("time_in_force") val timeInForce: String? = null,
  @SerialName("trigger_condition") val triggerCondition: String = "",
  @SerialName("order_direction") val orderDirection: String = "",
  @SerialName("is_buy") val isBuy: Boolean,
  @SerialName("is_reduce_only") val isReduceOnly: Boolean = false,
  val details: String = "",
  @SerialName("is_tpsl") val isTpSl: Boolean = false,
  @SerialName("cancellation_reason") val cancellationReason: String = "",
  @SerialName("transaction_version") val transactionVersion: Long,
  @SerialName("unix_ms") val unixMs: Long,
  val price: Double? = null,
  @SerialName("orig_size") val originalSize: Double? = null,
  @SerialName("remaining_size") val remainingSize: Double? = null,
  @SerialName("size_delta") val sizeDelta: Double? = null,
  @SerialName("tp_trigger_price") val takeProfitTriggerPrice: Double? = null,
  @SerialName("tp_limit_price") val takeProfitLimitPrice: Double? = null,
  @SerialName("sl_trigger_price") val stopLossTriggerPrice: Double? = null,
  @SerialName("sl_limit_price") val stopLossLimitPrice: Double? = null,
)

@Serializable
data class Subaccount(
  @SerialName("subaccount_address") val address: String,
  @SerialName("primary_account_address") val owner: String,
  @SerialName("custom_label") val customLabel: String? = null,
  @SerialName("is_primary") val isPrimary: Boolean,
  @SerialName("is_active") val isActive: Boolean = true,
) {
  val name: String
    get() = customLabel.orEmpty()
}

@Serializable
data class Delegation(
  @SerialName("delegated_account") val delegate: String,
  @SerialName("expiration_time_s") val expirationTimeSeconds: Long? = null,
  @SerialName("permission_type") val permissionType: String,
  @SerialName("permission_market") val permissionMarket: String? = null,
) {
  val canTradeAllPerpMarkets: Boolean
    get() = permissionType == "TradePerpsAllMarkets"
}

@Serializable
data class Page<T>(
  val items: List<T> = emptyList(),
  @SerialName("total_count") val totalCount: Long? = null,
)

@Serializable
data class FundingPayment(
  val market: String,
  val action: String,
  val size: Double,
  @SerialName("realized_funding_amount") val realizedFundingAmount: Double,
  @SerialName("is_rebate") val isRebate: Boolean,
  @SerialName("fee_amount") val feeAmount: Double,
  @SerialName("transaction_unix_ms") val transactionUnixMs: Long,
)
