package xyz.mcxross.flare.hyperliquid.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Hyperliquid writes the side as `B` (bid, buy) or `A` (ask, sell). */
@Serializable
enum class Side {
  @SerialName("B") BUY,
  @SerialName("A") SELL,
}

/** `meta`: the perpetuals universe. An asset's position in [universe] is its asset id. */
@Serializable
data class PerpMeta(
  val universe: List<PerpAssetInfo>,
  val collateralToken: Int? = null,
)

@Serializable
data class PerpAssetInfo(
  val name: String,
  val szDecimals: Int,
  val maxLeverage: Int,
  val onlyIsolated: Boolean = false,
  val isDelisted: Boolean = false,
  /** `strictIsolated` or `noCross` when cross margin is unavailable; absent otherwise. */
  val marginMode: String? = null,
  val marginTableId: Int? = null,
) {
  val isIsolatedOnly: Boolean
    get() = onlyIsolated || marginMode == "strictIsolated" || marginMode == "noCross"
}

/** `spotMeta`: spot pairs and the tokens they trade. */
@Serializable
data class SpotMeta(
  val universe: List<SpotPairInfo>,
  val tokens: List<SpotToken>,
)

/**
 * A spot pair. [name] is its coin on the wire: `PURR/USDC` for the first pair, `@<index>` for the
 * rest. Its asset id is `10000 + index`; [index] is not its position in the list.
 */
@Serializable
data class SpotPairInfo(
  val name: String,
  /** Base and quote token indexes. */
  val tokens: List<Int>,
  val index: Int,
  val isCanonical: Boolean = false,
)

@Serializable
data class SpotToken(
  val name: String,
  val szDecimals: Int,
  val weiDecimals: Int,
  val index: Int,
  val tokenId: String,
  val isCanonical: Boolean = false,
  val evmContract: SpotTokenEvmContract? = null,
  val fullName: String? = null,
)

@Serializable
data class SpotTokenEvmContract(
  val address: String,
  @SerialName("evm_extra_wei_decimals") val evmExtraWeiDecimals: Int = 0,
)

/** A perpetual's live context, aligned by position with [PerpMeta.universe]. */
@Serializable
data class PerpAssetContext(
  val funding: String,
  val openInterest: String,
  val prevDayPx: String,
  val dayNtlVlm: String,
  val premium: String? = null,
  val oraclePx: String,
  val markPx: String,
  /** Absent when the book is empty on one side. */
  val midPx: String? = null,
  val impactPxs: List<String>? = null,
  val dayBaseVlm: String = "0",
)

/** A spot pair's live context, keyed by [coin]. */
@Serializable
data class SpotAssetContext(
  val coin: String,
  val prevDayPx: String,
  val dayNtlVlm: String,
  val markPx: String,
  val midPx: String? = null,
  val circulatingSupply: String = "0",
  val totalSupply: String = "0",
  val dayBaseVlm: String = "0",
)

data class PerpMetaAndContexts(
  val meta: PerpMeta,
  val contexts: List<PerpAssetContext>,
) {
  /** The context of each asset, by coin. */
  val contextByCoin: Map<String, PerpAssetContext>
    get() = meta.universe.zip(contexts).associate { (asset, context) -> asset.name to context }
}

data class SpotMetaAndContexts(
  val meta: SpotMeta,
  /** Includes coins `spotMeta` does not list; match by [SpotAssetContext.coin]. */
  val contexts: List<SpotAssetContext>,
) {
  val contextByCoin: Map<String, SpotAssetContext>
    get() = contexts.associateBy(SpotAssetContext::coin)
}

@Serializable
data class BookLevel(
  val px: String,
  val sz: String,
  /** Orders at this level. */
  val n: Int,
)

/** `l2Book`: [levels] holds bids then asks, each best first. */
@Serializable
data class L2Book(
  val coin: String,
  val time: Long,
  val levels: List<List<BookLevel>>,
) {
  val bids: List<BookLevel>
    get() = levels.getOrNull(0).orEmpty()

  val asks: List<BookLevel>
    get() = levels.getOrNull(1).orEmpty()
}

@Serializable
data class Candle(
  @SerialName("t") val openTimeMs: Long,
  @SerialName("T") val closeTimeMs: Long,
  @SerialName("s") val coin: String,
  @SerialName("i") val interval: String,
  @SerialName("o") val open: String,
  @SerialName("c") val close: String,
  @SerialName("h") val high: String,
  @SerialName("l") val low: String,
  @SerialName("v") val volume: String,
  @SerialName("n") val trades: Int = 0,
)

/** Hyperliquid's candle intervals. Its month (`1M`) is a calendar month. */
enum class CandleInterval(val wireValue: String) {
  ONE_MINUTE("1m"),
  THREE_MINUTES("3m"),
  FIVE_MINUTES("5m"),
  FIFTEEN_MINUTES("15m"),
  THIRTY_MINUTES("30m"),
  ONE_HOUR("1h"),
  TWO_HOURS("2h"),
  FOUR_HOURS("4h"),
  EIGHT_HOURS("8h"),
  TWELVE_HOURS("12h"),
  ONE_DAY("1d"),
  THREE_DAYS("3d"),
  ONE_WEEK("1w"),
  ONE_MONTH("1M"),
}

/** A public trade. [users] is buyer then seller. */
@Serializable
data class MarketTrade(
  val coin: String,
  val side: Side,
  val px: String,
  val sz: String,
  val time: Long,
  val hash: String,
  val tid: Long,
  val users: List<String> = emptyList(),
)
