package xyz.mcxross.flare.hyperliquid.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** `clearinghouseState`: the perpetuals account. */
@Serializable
data class ClearinghouseState(
  val marginSummary: MarginSummary,
  val crossMarginSummary: MarginSummary,
  val crossMaintenanceMarginUsed: String = "0",
  val withdrawable: String,
  val assetPositions: List<AssetPosition> = emptyList(),
  val time: Long = 0,
)

@Serializable
data class MarginSummary(
  val accountValue: String,
  val totalNtlPos: String,
  val totalRawUsd: String,
  val totalMarginUsed: String,
)

@Serializable
data class AssetPosition(
  /** `oneWay` today. */
  val type: String,
  val position: Position,
)

@Serializable
data class Position(
  val coin: String,
  /** Signed size: negative when short. */
  val szi: String,
  val leverage: Leverage,
  val entryPx: String? = null,
  val positionValue: String,
  val unrealizedPnl: String,
  val returnOnEquity: String,
  val liquidationPx: String? = null,
  val marginUsed: String,
  val maxLeverage: Int,
  val cumFunding: CumulativeFunding? = null,
) {
  val isLong: Boolean
    get() = !szi.trim().startsWith('-')
}

@Serializable
data class Leverage(
  /** `cross` or `isolated`. */
  val type: String,
  val value: Int,
  /** Isolated margin only. */
  val rawUsd: String? = null,
) {
  val isCross: Boolean
    get() = type == "cross"
}

@Serializable
data class CumulativeFunding(
  val allTime: String,
  val sinceOpen: String,
  val sinceChange: String,
)

/** `spotClearinghouseState`. */
@Serializable data class SpotClearinghouseState(val balances: List<SpotBalance> = emptyList())

@Serializable
data class SpotBalance(
  val coin: String,
  val token: Int,
  val total: String,
  /** Reserved by open orders, or by perp margin when spot backs perps. */
  val hold: String,
  val entryNtl: String = "0",
)

/** An open or historical order as the frontend endpoints return it. */
@Serializable
data class FrontendOrder(
  val coin: String,
  val side: Side,
  val limitPx: String,
  val sz: String,
  val oid: Long,
  val timestamp: Long,
  val origSz: String,
  val triggerCondition: String = "N/A",
  val isTrigger: Boolean = false,
  val triggerPx: String = "0.0",
  /** TP/SL orders attached to this one. */
  val children: List<FrontendOrder> = emptyList(),
  val isPositionTpsl: Boolean = false,
  val reduceOnly: Boolean = false,
  /** `Limit`, `Market`, `Stop Market`, `Stop Limit`, `Take Profit Market`, `Take Profit Limit`. */
  val orderType: String = "Limit",
  /** `Gtc`, `Alo`, `Ioc`, `FrontendMarket` or absent for triggers. */
  val tif: String? = null,
  val cloid: String? = null,
)

/**
 * `activeAssetData`: [user]'s leverage setting on a perp, which it has with or without a position,
 * and what it could trade now.
 */
@Serializable
data class ActiveAssetData(
  val user: String,
  val coin: String,
  val leverage: Leverage,
  val maxTradeSzs: List<String> = emptyList(),
  val availableToTrade: List<String> = emptyList(),
  val markPx: String? = null,
)

/** An order and where it stands; [status] is `open`, `filled`, `canceled`, `triggered`, … */
@Serializable
data class OrderWithStatus(
  val order: FrontendOrder,
  val status: String,
  val statusTimestamp: Long,
)

/** `orderStatus`: [order] is absent when [status] is `unknownOid`. */
@Serializable
data class OrderStatusResult(
  val status: String,
  val order: OrderWithStatus? = null,
) {
  val isKnown: Boolean
    get() = status == "order" && order != null
}

@Serializable
data class Fill(
  val coin: String,
  val px: String,
  val sz: String,
  val side: Side,
  val time: Long,
  val startPosition: String,
  /** `Open Long`, `Close Short`, `Buy`, `Sell`, … */
  val dir: String,
  val closedPnl: String,
  val hash: String,
  val oid: Long,
  /** True when the fill took liquidity. */
  val crossed: Boolean,
  val fee: String,
  val tid: Long,
  val feeToken: String = "USDC",
  val builderFee: String? = null,
  val cloid: String? = null,
  val twapId: Long? = null,
)

/** `userFunding`. */
@Serializable
data class FundingUpdate(
  val time: Long,
  val hash: String,
  val delta: FundingDelta,
)

@Serializable
data class FundingDelta(
  val type: String = "funding",
  val coin: String,
  val usdc: String,
  val szi: String,
  val fundingRate: String,
  val nSamples: Int? = null,
)

/** `userNonFundingLedgerUpdates`: deposits, withdrawals, transfers, vault moves and more. */
@Serializable
data class LedgerUpdate(
  val time: Long,
  val hash: String,
  val delta: LedgerDelta,
)

/**
 * One ledger change. [type] says which fields apply: `deposit` and `withdraw` (`usdc`, `fee`),
 * `internalTransfer` and `subAccountTransfer` (`usdc`, `user`, `destination`),
 * `accountClassTransfer` (`usdc`, `toPerp`), `spotTransfer` and `send` (`token`, `amount`, `user`,
 * `destination`), `vaultDeposit` and `vaultWithdraw` (`vault`, `usdc`, `requestedUsd`,
 * `netWithdrawnUsd`), and others. Kept flat so new types still decode.
 */
@Serializable
data class LedgerDelta(
  val type: String,
  val usdc: String? = null,
  val fee: String? = null,
  val nonce: Long? = null,
  val user: String? = null,
  val destination: String? = null,
  val toPerp: Boolean? = null,
  val token: String? = null,
  val amount: String? = null,
  val usdcValue: String? = null,
  val sourceDex: String? = null,
  val destinationDex: String? = null,
  val vault: String? = null,
  val requestedUsd: String? = null,
  val commission: String? = null,
  val closingCost: String? = null,
  val basis: String? = null,
  val netWithdrawnUsd: String? = null,
  val isDeposit: Boolean? = null,
)

/**
 * `portfolio`: account value and PnL histories by period (`day`, `week`, `month`, `allTime`, and
 * the same for perps only, `perpDay` and so on).
 */
@Serializable(with = PortfolioSerializer::class)
data class Portfolio(val periods: Map<String, PortfolioPeriod>) {
  operator fun get(period: String): PortfolioPeriod? = periods[period]
}

@Serializable
data class PortfolioPeriod(
  val accountValueHistory: List<PortfolioPoint> = emptyList(),
  val pnlHistory: List<PortfolioPoint> = emptyList(),
  val vlm: String = "0",
)

/** A `[timestampMs, "value"]` pair. */
@Serializable(with = PortfolioPointSerializer::class)
data class PortfolioPoint(val timeMs: Long, val value: String)

internal object PortfolioPointSerializer : KSerializer<PortfolioPoint> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PortfolioPoint")

  override fun deserialize(decoder: Decoder): PortfolioPoint {
    val array = (decoder as JsonDecoder).decodeJsonElement().jsonArray
    return PortfolioPoint(array[0].jsonPrimitive.long, array[1].jsonPrimitive.content)
  }

  override fun serialize(encoder: Encoder, value: PortfolioPoint) {
    (encoder as JsonEncoder).encodeJsonElement(
      buildJsonArray {
        add(JsonPrimitive(value.timeMs))
        add(JsonPrimitive(value.value))
      }
    )
  }
}

internal object PortfolioSerializer : KSerializer<Portfolio> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Portfolio")

  override fun deserialize(decoder: Decoder): Portfolio {
    val json = (decoder as JsonDecoder).json
    val entries = decoder.decodeJsonElement().jsonArray
    return Portfolio(
      entries.associate { entry ->
        val pair = entry.jsonArray
        pair[0].jsonPrimitive.content to
          json.decodeFromJsonElement(PortfolioPeriod.serializer(), pair[1])
      }
    )
  }

  override fun serialize(encoder: Encoder, value: Portfolio) {
    val json = (encoder as JsonEncoder).json
    encoder.encodeJsonElement(
      JsonArray(
        value.periods.map { (period, data) ->
          buildJsonArray {
            add(JsonPrimitive(period))
            add(json.encodeToJsonElement(PortfolioPeriod.serializer(), data))
          }
        }
      )
    )
  }
}

/** `userFees`: the account's current rates, as fractions (`0.00045` is 4.5 bps). */
@Serializable
data class UserFees(
  val userCrossRate: String,
  val userAddRate: String,
  val userSpotCrossRate: String? = null,
  val userSpotAddRate: String? = null,
  val activeReferralDiscount: String = "0",
  val dailyUserVlm: List<DailyVolume> = emptyList(),
  val feeSchedule: FeeSchedule? = null,
  val activeStakingDiscount: StakingDiscount? = null,
)

@Serializable
data class DailyVolume(
  val date: String,
  val userCross: String,
  val userAdd: String,
  val exchange: String,
)

@Serializable
data class FeeSchedule(
  val cross: String,
  val add: String,
  val spotCross: String? = null,
  val spotAdd: String? = null,
)

@Serializable
data class StakingDiscount(
  val bpsOfMaxSupply: String,
  val discount: String,
)

/**
 * `userRole`: `missing` (never funded), `user`, `agent` ([data] names its user), `vault` or
 * `subAccount` ([data] names its master).
 */
@Serializable
data class UserRole(
  val role: String,
  val data: UserRoleData? = null,
) {
  val isMissing: Boolean
    get() = role == "missing"
}

@Serializable
data class UserRoleData(
  val user: String? = null,
  val master: String? = null,
)

/**
 * `userAbstraction`: how the account keeps its balances. In [UNIFIED] and [PORTFOLIO_MARGIN] one
 * balance per asset backs spot and perps alike, so spot USDC includes the perp account's value;
 * [DISABLED] and [DEFAULT] keep perps and spot apart.
 */
data class AccountAbstraction(val mode: String) {
  val sharesSpotCollateral: Boolean
    get() = mode == UNIFIED || mode == PORTFOLIO_MARGIN

  companion object {
    const val DEFAULT = "default"
    const val DISABLED = "disabled"
    const val UNIFIED = "unifiedAccount"
    const val PORTFOLIO_MARGIN = "portfolioMargin"
    const val DEX_ABSTRACTION = "dexAbstraction"
  }
}

/** An approved agent (API wallet) other than the unnamed one. */
@Serializable
data class ExtraAgent(
  val name: String,
  val address: String,
  val validUntil: Long,
)

@Serializable
data class VaultDetails(
  val name: String,
  val vaultAddress: String,
  val leader: String,
  val description: String = "",
  val portfolio: Portfolio? = null,
  @Serializable(with = DecimalTextSerializer::class) val apr: String = "0",
  val followerState: VaultFollower? = null,
  @Serializable(with = DecimalTextSerializer::class) val leaderFraction: String = "0",
  @Serializable(with = DecimalTextSerializer::class) val leaderCommission: String = "0",
  val followers: List<VaultFollower> = emptyList(),
  @Serializable(with = DecimalTextSerializer::class) val maxDistributable: String = "0",
  @Serializable(with = DecimalTextSerializer::class) val maxWithdrawable: String = "0",
  val isClosed: Boolean = false,
  val relationship: VaultRelationship? = null,
  val allowDeposits: Boolean = true,
  val alwaysCloseOnWithdraw: Boolean = false,
)

@Serializable
data class VaultFollower(
  val user: String,
  val vaultEquity: String,
  val pnl: String,
  val allTimePnl: String,
  val daysFollowing: Int,
  val vaultEntryTime: Long,
  /** Withdrawals are refused until then. */
  val lockupUntil: Long,
)

/** `parent` with [VaultRelationshipData.childAddresses], `child`, or `normal`. */
@Serializable
data class VaultRelationship(
  val type: String,
  val data: VaultRelationshipData? = null,
)

@Serializable
data class VaultRelationshipData(
  val childAddresses: List<String> = emptyList(),
  val parent: String? = null,
)

@Serializable
data class VaultEquity(
  val vaultAddress: String,
  val equity: String,
  val lockedUntilTimestamp: Long? = null,
)

@Serializable
data class TwapState(
  val coin: String,
  val user: String,
  val side: Side,
  val sz: String,
  val executedSz: String,
  val executedNtl: String,
  val minutes: Int,
  val reduceOnly: Boolean,
  val randomize: Boolean,
  val timestamp: Long,
)

/** [status] is `activated`, `finished`, `terminated` or `error` (with [description]). */
@Serializable
data class TwapStatus(
  val status: String,
  val description: String? = null,
)

@Serializable
data class TwapHistoryEntry(
  /** In seconds, unlike the other times here. */
  val time: Long,
  val state: TwapState,
  val status: TwapStatus,
  val twapId: Long? = null,
)

@Serializable
data class SubAccount(
  val name: String,
  val subAccountUser: String,
  val master: String,
  val clearinghouseState: ClearinghouseState? = null,
  val spotState: SpotClearinghouseState? = null,
)

@Serializable
data class Referral(
  val referredBy: ReferredBy? = null,
  val cumVlm: String = "0",
  val unclaimedRewards: String = "0",
  val claimedRewards: String = "0",
  val builderRewards: String = "0",
  val referrerState: ReferrerState? = null,
)

@Serializable
data class ReferredBy(
  val referrer: String,
  val code: String,
)

/** [stage] is `ready` (with a code in [data]), `needToCreateCode` or `needToTrade`. */
@Serializable
data class ReferrerState(
  val stage: String,
  val data: JsonObject? = null,
)

/** `[[twapId, state], …]` as id and state pairs. */
internal object TwapStatesSerializer : KSerializer<List<Pair<Long, TwapState>>> {
  override val descriptor: SerialDescriptor = ListSerializer(TwapState.serializer()).descriptor

  override fun deserialize(decoder: Decoder): List<Pair<Long, TwapState>> {
    val json = (decoder as JsonDecoder).json
    return decoder.decodeJsonElement().jsonArray.map { entry ->
      val pair = entry.jsonArray
      pair[0].jsonPrimitive.long to json.decodeFromJsonElement(TwapState.serializer(), pair[1])
    }
  }

  override fun serialize(encoder: Encoder, value: List<Pair<Long, TwapState>>) {
    val json = (encoder as JsonEncoder).json
    encoder.encodeJsonElement(
      JsonArray(
        value.map { (id, state) ->
          buildJsonArray {
            add(JsonPrimitive(id))
            add(json.encodeToJsonElement(TwapState.serializer(), state))
          }
        }
      )
    )
  }
}
