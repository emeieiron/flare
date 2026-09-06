package xyz.mcxross.flare.decibel.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.mcxross.flare.decibel.model.AccountOverview
import xyz.mcxross.flare.decibel.model.DecimalTextSerializer
import xyz.mcxross.flare.decibel.model.MarketPrice
import xyz.mcxross.flare.decibel.model.MarketTrade
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.OrderBook
import xyz.mcxross.flare.decibel.model.Position

sealed interface DecibelStreamData {
  data class MarketPrices(val values: List<MarketPrice>) : DecibelStreamData

  data class MarketPriceValue(val value: MarketPrice) : DecibelStreamData

  data class MarketDepthValue(val value: MarketDepthUpdate) : DecibelStreamData

  data class MarketTrades(val values: List<MarketTrade>) : DecibelStreamData

  data class AccountOverviewValue(val value: AccountOverview) : DecibelStreamData

  data class AccountPositionsValue(val values: List<Position>) : DecibelStreamData

  data class AccountOpenOrdersValue(val values: List<Order>) : DecibelStreamData

  data class Raw(val payload: JsonObject) : DecibelStreamData

  data class Malformed(val reason: String) : DecibelStreamData
}

@Serializable
data class MarketDepthLevel(
  @Serializable(with = DecimalTextSerializer::class) val price: String,
  @Serializable(with = DecimalTextSerializer::class) val size: String,
)

@Serializable
data class MarketDepthUpdate(
  val market: String,
  val asks: List<MarketDepthLevel>,
  val bids: List<MarketDepthLevel>,
  @SerialName("unix_ms") val unixMs: Long,
) {
  fun toOrderBook(): OrderBook =
    OrderBook(
      market = market,
      timestamp = unixMs.toString(),
      bids = bids.map { listOf(it.price, it.size) },
      asks = asks.map { listOf(it.price, it.size) },
    )
}

internal fun decodeStreamData(
  topic: DecibelStreamTopic?,
  payload: JsonObject,
  json: Json,
): DecibelStreamData = runCatching {
  when (topic) {
    AllMarketPrices ->
      DecibelStreamData.MarketPrices(
        json.decodeFromJsonElement<MarketPricesEnvelope>(payload).prices
      )
    is MarketPriceTopic ->
      DecibelStreamData.MarketPriceValue(
        json.decodeFromJsonElement<MarketPriceEnvelope>(payload).price
      )
    is MarketDepth ->
      DecibelStreamData.MarketDepthValue(json.decodeFromJsonElement<MarketDepthUpdate>(payload))
    is MarketTrades,
    is UserTrades ->
      DecibelStreamData.MarketTrades(
        json.decodeFromJsonElement<MarketTradesEnvelope>(payload).trades
      )
    is AccountOverviewTopic ->
      DecibelStreamData.AccountOverviewValue(
        json.decodeFromJsonElement<AccountOverviewEnvelope>(payload).accountOverview
      )
    is AccountPositions ->
      DecibelStreamData.AccountPositionsValue(
        json.decodeFromJsonElement<AccountPositionsEnvelope>(payload).positions
      )
    is AccountOpenOrders ->
      DecibelStreamData.AccountOpenOrdersValue(
        json.decodeFromJsonElement<AccountOpenOrdersEnvelope>(payload).orders
      )
    else -> DecibelStreamData.Raw(payload)
  }
}
  .getOrElse { error ->
    DecibelStreamData.Malformed(error.message ?: "Invalid Decibel WebSocket payload")
  }

@Serializable private data class MarketPricesEnvelope(val prices: List<MarketPrice>)

@Serializable private data class MarketPriceEnvelope(val price: MarketPrice)

@Serializable private data class MarketTradesEnvelope(val trades: List<MarketTrade>)

@Serializable
private data class AccountOverviewEnvelope(
  @SerialName("account_overview") val accountOverview: AccountOverview
)

@Serializable private data class AccountPositionsEnvelope(val positions: List<Position>)

@Serializable private data class AccountOpenOrdersEnvelope(val orders: List<Order>)
