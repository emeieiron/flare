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
  @SerialName("usdc_cross_balance") val crossUsdcBalance: Double? = null,
  @SerialName("free_vault_equity") val freeVaultEquity: Double? = null,
  val spot: SpotOverview? = null,
)

@Serializable
data class SpotOverview(
  val positions: List<SpotAssetBalance> = emptyList(),
  @SerialName("in_flight_orders") val reservations: List<SpotReservation> = emptyList(),
  @SerialName("total_usd") val totalUsd: Double = 0.0,
)

@Serializable
data class SpotAssetBalance(
  @SerialName("asset_addr") val assetAddress: String,
  @SerialName("asset_symbol") val symbol: String = "",
  val amount: Double,
  @SerialName("usd_value") val valueUsd: Double,
)

@Serializable
data class SpotReservation(
  @SerialName("market_addr") val market: String,
  @SerialName("order_id") val orderId: String,
  @SerialName("is_bid") val isBuy: Boolean,
  @SerialName("reserved_asset") val assetAddress: String,
  @SerialName("reserved_amount") val amount: Double,
  @SerialName("reserved_usd_value") val valueUsd: Double,
)

@Serializable
data class ProductFees(
  @SerialName("user_maker_rate") val makerRate: Double,
  @SerialName("user_taker_rate") val takerRate: Double,
)

@Serializable
data class AccountFees(val perp: ProductFees? = null, val spot: ProductFees? = null)

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

  val canTradeAllSpotMarkets: Boolean
    get() = permissionType == "TradeSpotAllMarkets"
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

@Serializable
data class PortfolioChartPoint(
  val timestamp: Long = 0L,
  val value: Double = 0.0,
  @SerialName("account_value") val accountValue: Double? = null,
  @SerialName("realized_pnl") val realizedPnl: Double? = null,
)

@Serializable
data class FundMovement(
  val timestamp: Long = 0L,
  val type: String = "deposit",
  val amount: Double = 0.0,
  @SerialName("asset_symbol") val assetSymbol: String = "USDC",
  @SerialName("transaction_hash") val transactionHash: String? = null,
  @SerialName("transaction_version") val transactionVersion: Long? = null,
  val status: String = "confirmed",
)

@Serializable
data class TradingStreak(
  @SerialName("streak_count") val streakCount: Int = 0,
  @SerialName("longest_streak") val longestStreak: Int = 0,
  @SerialName("grace_days_remaining") val graceDaysRemaining: Int = 0,
  @SerialName("qualifying_dates") val qualifyingDates: List<String> = emptyList(),
)

@Serializable
data class AmpsBreakdown(
  @SerialName("total_amps") val totalAmps: Double = 0.0,
  @SerialName("trading_amps") val tradingAmps: Double = 0.0,
  @SerialName("streak_amps") val streakAmps: Double = 0.0,
  @SerialName("bonus_amps") val bonusAmps: Double = 0.0,
  @SerialName("referral_amps") val referralAmps: Double = 0.0,
  @SerialName("vault_amps") val vaultAmps: Double = 0.0,
  val rank: Int? = null,
)

@Serializable
data class TierInfo(
  val tier: String = "Bronze",
  val percentile: Double = 0.0,
  @SerialName("fee_discount_bps") val feeDiscountBps: Int = 0,
)

@Serializable
data class ReferralCodeInfo(
  @SerialName("referral_code") val code: String = "",
  @SerialName("is_valid") val valid: Boolean = false,
  @SerialName("is_active") val active: Boolean = false,
  val owner: String? = null,
  @SerialName("discount_percent") val discountPercent: Double = 0.0,
)

@Serializable
data class ReferralRedemptionRequest(
  val account: String,
  val code: String,
)

@Serializable
data class ReferralRedemptionResponse(
  val success: Boolean = true,
  val message: String? = null,
)

@Serializable
data class TwapOrder(
  @SerialName("order_id") val orderId: String = "",
  @SerialName("account") val account: String = "",
  @SerialName("market") val market: String = "",
  @SerialName("side") val side: OrderSide = OrderSide.BUY,
  @SerialName("total_size") val totalSize: Double = 0.0,
  @SerialName("executed_size") val executedSize: Double = 0.0,
  @SerialName("status") val status: String = "active",
  @SerialName("duration_seconds") val durationSeconds: Long = 0L,
  @SerialName("frequency_seconds") val frequencySeconds: Long = 0L,
  @SerialName("created_at") val createdAt: Long = 0L,
  @SerialName("client_order_id") val clientOrderId: String? = null,
) {
  val twapId: String get() = orderId
  val isBuy: Boolean get() = side == OrderSide.BUY
}

@Serializable
data class VaultInfo(
  val address: String = "",
  val name: String = "",
  val manager: String = "",
  val description: String = "",
  @SerialName("total_shares") val totalShares: Double = 0.0,
  @SerialName("share_price") val sharePrice: Double = 1.0,
  @SerialName("tvl") val tvl: Double? = null,
  @SerialName("total_aum") val totalAumFallback: Double = 0.0,
  @SerialName("profit_share") val profitShare: Double? = null,
  @SerialName("performance_fee_bps") val performanceFeeBpsFallback: Int = 0,
  @SerialName("lockdown_period_s") val lockdownPeriodS: Long? = null,
  @SerialName("lockup_seconds") val lockupSecondsFallback: Long = 0L,
  @SerialName("status") val status: String? = null,
  @SerialName("is_active") val isActiveFallback: Boolean = true,
) {
  constructor(
    address: String = "",
    name: String = "",
    manager: String = "",
    description: String = "",
    totalShares: Double = 0.0,
    sharePrice: Double = 1.0,
    totalAum: Double = 0.0,
    performanceFeeBps: Int = 0,
    lockupSeconds: Long = 0L,
    isActive: Boolean = true,
  ) : this(
    address = address,
    name = name,
    manager = manager,
    description = description,
    totalShares = totalShares,
    sharePrice = sharePrice,
    tvl = null,
    totalAumFallback = totalAum,
    profitShare = null,
    performanceFeeBpsFallback = performanceFeeBps,
    lockdownPeriodS = null,
    lockupSecondsFallback = lockupSeconds,
    status = null,
    isActiveFallback = isActive,
  )

  val totalAum: Double get() = tvl ?: totalAumFallback
  val performanceFeeBps: Int
    get() = if (performanceFeeBpsFallback > 0) performanceFeeBpsFallback else ((profitShare ?: 0.0) * 100.0).toInt()
  val performanceFeePercent: Double get() = profitShare ?: (performanceFeeBpsFallback / 100.0)
  val lockupSeconds: Long get() = lockdownPeriodS ?: lockupSecondsFallback
  val isActive: Boolean
    get() = if (status != null) status.equals("active", ignoreCase = true) else isActiveFallback
}

@Serializable
data class AccountVaultPerformance(
  val vault: VaultInfo = VaultInfo(),
  @SerialName("current_num_shares") val rawShares: Double = 0.0,
  @SerialName("current_value_of_shares") val currentValueOfShares: Double? = null,
  @SerialName("current_value") val currentValueFallback: Double = 0.0,
  @SerialName("total_deposited") val totalDeposited: Double = 0.0,
  @SerialName("total_withdrawn") val totalWithdrawn: Double = 0.0,
  @SerialName("net_deposits") val netDepositsFallback: Double = 0.0,
  @SerialName("all_time_earned") val allTimeEarned: Double = 0.0,
  @SerialName("realized_pnl") val realizedPnlFallback: Double = 0.0,
  @SerialName("all_time_return") val allTimeReturn: Double? = null,
  @SerialName("returns_percent") val returnsPercentFallback: Double = 0.0,
  @SerialName("share_price") val positionSharePrice: Double? = null,
) {
  constructor(
    vault: VaultInfo = VaultInfo(),
    currentNumShares: Double = 0.0,
    currentValue: Double = 0.0,
    netDeposits: Double = 0.0,
    realizedPnl: Double = 0.0,
    returnsPercent: Double = 0.0,
  ) : this(
    vault = vault,
    rawShares = currentNumShares,
    currentValueOfShares = null,
    currentValueFallback = currentValue,
    totalDeposited = 0.0,
    totalWithdrawn = 0.0,
    netDepositsFallback = netDeposits,
    allTimeEarned = 0.0,
    realizedPnlFallback = realizedPnl,
    allTimeReturn = null,
    returnsPercentFallback = returnsPercent,
    positionSharePrice = null,
  )

  /**
   * Normalized share count. Decibel Move contracts store shares with 6 decimals (1 share = 1,000,000 base units).
   * The Decibel indexer API returns raw integer share units (e.g. 10,000,000 for 10 shares).
   * This property presents normalized, human-readable shares for intuitive understanding.
   */
  val currentNumShares: Double
    get() = if (rawShares >= 100_000.0) {
      rawShares / 1_000_000.0
    } else {
      rawShares
    }

  val rawNumShares: Long get() = rawShares.toLong()

  val currentValue: Double
    get() = currentValueOfShares ?: if (currentValueFallback > 0.0) currentValueFallback else (currentNumShares * effectiveSharePrice)

  val netDeposits: Double
    get() = if (netDepositsFallback != 0.0) netDepositsFallback else (totalDeposited - totalWithdrawn)

  val realizedPnl: Double
    get() = if (realizedPnlFallback != 0.0) realizedPnlFallback else allTimeEarned

  val returnsPercent: Double
    get() = allTimeReturn ?: returnsPercentFallback

  val effectiveSharePrice: Double
    get() = positionSharePrice ?: vault.sharePrice
}
