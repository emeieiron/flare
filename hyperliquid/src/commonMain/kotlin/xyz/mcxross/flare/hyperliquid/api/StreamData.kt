package xyz.mcxross.flare.hyperliquid.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.mcxross.flare.hyperliquid.model.Candle
import xyz.mcxross.flare.hyperliquid.model.ClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.Fill
import xyz.mcxross.flare.hyperliquid.model.FrontendOrder
import xyz.mcxross.flare.hyperliquid.model.L2Book
import xyz.mcxross.flare.hyperliquid.model.LedgerUpdate
import xyz.mcxross.flare.hyperliquid.model.Leverage
import xyz.mcxross.flare.hyperliquid.model.MarketTrade
import xyz.mcxross.flare.hyperliquid.model.PerpAssetContext
import xyz.mcxross.flare.hyperliquid.model.Side
import xyz.mcxross.flare.hyperliquid.model.SpotAssetContext
import xyz.mcxross.flare.hyperliquid.model.SpotClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.TwapState
import xyz.mcxross.flare.hyperliquid.model.TwapStatesSerializer

sealed interface StreamData {
  data class Mids(val mids: Map<String, String>) : StreamData

  data class Book(val book: L2Book) : StreamData

  data class Trades(val trades: List<MarketTrade>) : StreamData

  /** The forming candle, or one that just closed. */
  data class CandleUpdate(val candle: Candle) : StreamData

  data class PerpContext(val coin: String, val context: PerpAssetContext) : StreamData

  data class SpotContext(val coin: String, val context: SpotAssetContext) : StreamData

  data class AssetData(val data: ActiveAssetData) : StreamData

  data class Clearinghouse(val user: String, val state: ClearinghouseState) : StreamData

  data class OpenOrders(val user: String, val orders: List<FrontendOrder>) : StreamData

  data class SpotState(val user: String, val state: SpotClearinghouseState) : StreamData

  /** Running TWAPs by id. */
  data class TwapStates(val user: String, val states: List<Pair<Long, TwapState>>) : StreamData

  data class OrderUpdates(val updates: List<OrderUpdate>) : StreamData

  /** The first message after subscribing is a snapshot. */
  data class UserFills(val user: String, val fills: List<Fill>, val isSnapshot: Boolean) :
    StreamData

  data class UserEvent(val event: UserEventData) : StreamData

  data class UserFundings(
    val user: String,
    val fundings: List<StreamFunding>,
    val isSnapshot: Boolean,
  ) : StreamData

  data class LedgerUpdates(
    val user: String,
    val updates: List<LedgerUpdate>,
    val isSnapshot: Boolean,
  ) : StreamData

  /** A channel this SDK does not decode. */
  data class Raw(val payload: JsonElement) : StreamData

  data class Malformed(val reason: String) : StreamData
}

@Serializable
data class ActiveAssetData(
  val user: String,
  val coin: String,
  val leverage: Leverage,
  /** Largest buy, then sell. */
  val maxTradeSzs: List<String>,
  /** Buying power for a buy, then a sell. */
  val availableToTrade: List<String>,
  val markPx: String? = null,
)

/** The order as `orderUpdates` sends it: fewer fields than [FrontendOrder]. */
@Serializable
data class StreamOrder(
  val coin: String,
  val side: Side,
  val limitPx: String,
  val sz: String,
  val oid: Long,
  val timestamp: Long,
  val origSz: String,
  val cloid: String? = null,
  val reduceOnly: Boolean? = null,
)

@Serializable
data class OrderUpdate(
  val order: StreamOrder,
  /** `open`, `filled`, `canceled`, `triggered`, `rejected`, `marginCanceled`, … */
  val status: String,
  val statusTimestamp: Long,
)

/** One of [fills], [funding], [liquidation] or [nonUserCancel] is set. */
@Serializable
data class UserEventData(
  val fills: List<Fill>? = null,
  val funding: StreamFunding? = null,
  val liquidation: JsonObject? = null,
  val nonUserCancel: List<NonUserCancel>? = null,
)

@Serializable
data class StreamFunding(
  val time: Long? = null,
  val coin: String,
  val usdc: String,
  val szi: String,
  val fundingRate: String,
  val nSamples: Int? = null,
)

/** An order Hyperliquid canceled itself, for example for lack of margin. */
@Serializable data class NonUserCancel(val coin: String, val oid: Long)

internal fun decodeStreamData(channel: String, data: JsonElement?, json: Json): StreamData {
  if (data == null || data is JsonNull) return StreamData.Malformed("$channel sent no data")
  return runCatching {
      when (channel) {
        "allMids" -> StreamData.Mids(json.decodeFromJsonElement<MidsEnvelope>(data).mids)
        "l2Book" -> StreamData.Book(json.decodeFromJsonElement<L2Book>(data))
        "trades" -> StreamData.Trades(json.decodeFromJsonElement<List<MarketTrade>>(data))
        "candle" -> StreamData.CandleUpdate(json.decodeFromJsonElement<Candle>(data))
        "activeAssetCtx" ->
          json.decodeFromJsonElement<PerpContextEnvelope>(data).let {
            StreamData.PerpContext(it.coin, it.ctx)
          }
        "activeSpotAssetCtx" ->
          json.decodeFromJsonElement<SpotContextEnvelope>(data).let {
            StreamData.SpotContext(it.coin, it.ctx)
          }
        "activeAssetData" -> StreamData.AssetData(json.decodeFromJsonElement<ActiveAssetData>(data))
        "clearinghouseState" ->
          json.decodeFromJsonElement<ClearinghouseEnvelope>(data).let {
            StreamData.Clearinghouse(it.user, it.clearinghouseState)
          }
        "openOrders" ->
          json.decodeFromJsonElement<OpenOrdersEnvelope>(data).let {
            StreamData.OpenOrders(it.user, it.orders)
          }
        "spotState" ->
          json.decodeFromJsonElement<SpotStateEnvelope>(data).let {
            StreamData.SpotState(it.user, it.spotState)
          }
        "twapStates" ->
          json.decodeFromJsonElement<TwapStatesEnvelope>(data).let {
            StreamData.TwapStates(it.user, it.states)
          }
        "orderUpdates" ->
          StreamData.OrderUpdates(json.decodeFromJsonElement<List<OrderUpdate>>(data))
        "userFills" ->
          json.decodeFromJsonElement<UserFillsEnvelope>(data).let {
            StreamData.UserFills(it.user, it.fills, it.isSnapshot)
          }
        "user" -> StreamData.UserEvent(json.decodeFromJsonElement<UserEventData>(data))
        "userFundings" ->
          json.decodeFromJsonElement<UserFundingsEnvelope>(data).let {
            StreamData.UserFundings(it.user, it.fundings, it.isSnapshot)
          }
        "userNonFundingLedgerUpdates" ->
          json.decodeFromJsonElement<LedgerUpdatesEnvelope>(data).let {
            StreamData.LedgerUpdates(it.user, it.nonFundingLedgerUpdates, it.isSnapshot)
          }
        else -> StreamData.Raw(data)
      }
    }
    .getOrElse { error ->
      StreamData.Malformed("Invalid $channel payload: ${error.message?.take(MAX_REASON_LENGTH)}")
    }
}

private const val MAX_REASON_LENGTH = 160

@Serializable private data class MidsEnvelope(val mids: Map<String, String>)

@Serializable private data class PerpContextEnvelope(val coin: String, val ctx: PerpAssetContext)

@Serializable private data class SpotContextEnvelope(val coin: String, val ctx: SpotAssetContext)

@Serializable
private data class ClearinghouseEnvelope(
  val user: String,
  val clearinghouseState: ClearinghouseState,
)

@Serializable
private data class OpenOrdersEnvelope(val user: String, val orders: List<FrontendOrder>)

@Serializable
private data class SpotStateEnvelope(val user: String, val spotState: SpotClearinghouseState)

@Serializable
private data class TwapStatesEnvelope(
  val user: String,
  @Serializable(with = TwapStatesSerializer::class) val states: List<Pair<Long, TwapState>>,
)

@Serializable
private data class UserFillsEnvelope(
  val user: String,
  val fills: List<Fill>,
  val isSnapshot: Boolean = false,
)

@Serializable
private data class UserFundingsEnvelope(
  val user: String,
  val fundings: List<StreamFunding>,
  val isSnapshot: Boolean = false,
)

@Serializable
private data class LedgerUpdatesEnvelope(
  val user: String,
  val nonFundingLedgerUpdates: List<LedgerUpdate>,
  val isSnapshot: Boolean = false,
)
