package xyz.mcxross.flare.decibel.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AssetType {
  @SerialName("perp") PERP,
  @SerialName("spot") SPOT,
}

@Serializable
data class Market(
  @SerialName("asset_type") val assetType: AssetType,
  @SerialName("market_addr") val address: String,
  @SerialName("market_name") val name: String,
  @SerialName("sz_decimals") val sizeDecimals: Int,
  @SerialName("max_leverage") val maxLeverage: Int,
  @SerialName("tick_size") val tickSize: ULong,
  @SerialName("min_size") val minSize: ULong,
  @SerialName("lot_size") val lotSize: ULong,
  @SerialName("max_open_interest") val maxOpenInterest: Double,
  @SerialName("px_decimals") val priceDecimals: Int,
  val mode: String,
  @SerialName("unrealized_pnl_haircut_bps") val unrealizedPnlHaircutBps: Int,
  val category: String,
  @SerialName("min_price") val minPrice: ULong,
  @SerialName("max_price") val maxPrice: ULong,
  @SerialName("is_isolated_only") val isIsolatedOnly: Boolean,
) {
  val symbol: String
    get() = name.substringBefore('/').substringBefore('-').uppercase()

  val precision: MarketPrecision
    get() =
      MarketPrecision(
        priceDecimals = priceDecimals,
        sizeDecimals = sizeDecimals,
        tickSize = tickSize,
        lotSize = lotSize,
        minimumSize = minSize,
        minimumPrice = minPrice,
        maximumPrice = maxPrice,
      )
}

@Serializable
data class MarketPrice(
  val market: String,
  @SerialName("oracle_px") val oraclePrice: Double,
  @SerialName("mark_px") val markPrice: Double,
  @SerialName("mid_px") val midPrice: Double,
  @SerialName("funding_rate_bps") val fundingRateBps: Double,
  @SerialName("is_funding_positive") val isFundingPositive: Boolean,
  @SerialName("funding_period_s") val fundingPeriodSeconds: Long = 0,
  @SerialName("transaction_unix_ms") val transactionUnixMs: Long,
  @SerialName("open_interest") val openInterest: Double,
)

@Serializable
data class AssetContext(
  val market: String,
  @SerialName("volume_24h") val volume24h: Double,
  @SerialName("open_interest") val openInterest: Double,
  @SerialName("mark_price") val markPrice: Double,
  @SerialName("mid_price") val midPrice: Double,
  @SerialName("oracle_price") val oraclePrice: Double,
  @SerialName("previous_day_price") val previousDayPrice: Double,
  @SerialName("price_change_pct_24h") val priceChangePercent24h: Double,
)

@Serializable
data class Candle(
  @SerialName("t") val openTimeMs: Long,
  @SerialName("T") val closeTimeMs: Long,
  @SerialName("o") val open: Double,
  @SerialName("h") val high: Double,
  @SerialName("l") val low: Double,
  @SerialName("c") val close: Double,
  @SerialName("v") val volume: Double,
  @SerialName("i") val interval: String,
)

@Serializable
data class OrderBook(
  @SerialName("ticker_id") val market: String,
  val timestamp: String,
  val bids: List<List<String>>,
  val asks: List<List<String>>,
) {
  val bestBid: String?
    get() = bids.firstOrNull()?.firstOrNull()

  val bestAsk: String?
    get() = asks.firstOrNull()?.firstOrNull()
}

@Serializable
data class MarketTrade(
  @SerialName("asset_type") val assetType: AssetType = AssetType.PERP,
  val account: String = "",
  val market: String,
  val action: String,
  val source: String = "",
  @SerialName("trade_id") val tradeId: String = "",
  val size: Double,
  val price: Double,
  @SerialName("is_profit") val isProfit: Boolean = false,
  @SerialName("realized_pnl_amount") val realizedPnlAmount: Double = 0.0,
  @SerialName("realized_funding_amount") val realizedFundingAmount: Double = 0.0,
  @SerialName("is_rebate") val isRebate: Boolean = false,
  @SerialName("fee_amount") val feeAmount: Double = 0.0,
  @SerialName("order_id") val orderId: String = "",
  @SerialName("client_order_id") val clientOrderId: String = "",
  @SerialName("transaction_unix_ms") val transactionUnixMs: Long,
  @SerialName("transaction_version") val transactionVersion: Long = 0,
  @SerialName("counter_party_account") val counterPartyAccount: String = "",
)

@Serializable
enum class CandleInterval(val wireValue: String) {
  ONE_MINUTE("1m"),
  FIVE_MINUTES("5m"),
  FIFTEEN_MINUTES("15m"),
  THIRTY_MINUTES("30m"),
  ONE_HOUR("1h"),
  TWO_HOURS("2h"),
  FOUR_HOURS("4h"),
  ONE_DAY("1d"),
  ONE_WEEK("1w"),
  ONE_MONTH("1mo"),
}
