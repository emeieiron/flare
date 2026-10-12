package xyz.mcxross.flare.hyperliquid.model

enum class AssetKind {
  PERP,
  SPOT,
}

/**
 * One tradable asset with its wire identifiers and precision rules.
 *
 * Hyperliquid accepts a price with at most five significant figures and at most
 * `maxDecimals - szDecimals` decimal places (`maxDecimals` is 6 for perps, 8 for spot); an integer
 * price is always accepted. Sizes carry at most [szDecimals] places. An order must be worth at
 * least [MIN_ORDER_VALUE_USDC].
 */
data class HyperliquidAsset(
  /** The `a` field of an order: the perp's index, or `10000 + index` for a spot pair. */
  val id: Int,
  /** The coin on the wire: `BTC`, `PURR/USDC`, `@107`. */
  val coin: String,
  val kind: AssetKind,
  /** `BTC`, or `HYPE/USDC` for spot, built from the token names. */
  val displayName: String,
  val szDecimals: Int,
  /** Perps only. */
  val maxLeverage: Int? = null,
  val isIsolatedOnly: Boolean = false,
  val isDelisted: Boolean = false,
  /** Spot only. */
  val baseToken: SpotToken? = null,
  val quoteToken: SpotToken? = null,
) {
  val maxPriceDecimals: Int
    get() = ((if (kind == AssetKind.SPOT) SPOT_MAX_DECIMALS else PERP_MAX_DECIMALS) - szDecimals)
      .coerceAtLeast(0)

  fun isValidPrice(price: HlDecimal): Boolean =
    price.signum > 0 &&
      (price.isInteger ||
        (price.significantFigures <= MAX_SIGNIFICANT_FIGURES &&
          price.decimalPlaces <= maxPriceDecimals))

  /** The nearest valid price in the [rounding] direction. */
  fun roundPrice(price: HlDecimal, rounding: HlRounding): HlDecimal {
    if (price.isInteger) return price
    return price
      .roundToSignificantFigures(MAX_SIGNIFICANT_FIGURES, rounding)
      .roundToDecimals(maxPriceDecimals, rounding)
  }

  fun isValidSize(size: HlDecimal): Boolean = size.signum > 0 && size.decimalPlaces <= szDecimals

  fun roundSize(size: HlDecimal, rounding: HlRounding): HlDecimal =
    size.roundToDecimals(szDecimals, rounding)

  companion object {
    const val PERP_MAX_DECIMALS: Int = 6
    const val SPOT_MAX_DECIMALS: Int = 8
    const val MAX_SIGNIFICANT_FIGURES: Int = 5
    const val SPOT_ASSET_OFFSET: Int = 10_000

    /** Hyperliquid rejects orders worth less than this, in USDC. */
    val MIN_ORDER_VALUE_USDC: HlDecimal = HlDecimal.of(10)
  }
}

/** Maps coins and asset ids to [HyperliquidAsset]s, built from `meta` and `spotMeta`. */
class AssetDirectory(perpMeta: PerpMeta, spotMeta: SpotMeta) {
  val perps: List<HyperliquidAsset> =
    perpMeta.universe.mapIndexed { index, info ->
      HyperliquidAsset(
        id = index,
        coin = info.name,
        kind = AssetKind.PERP,
        displayName = info.name,
        szDecimals = info.szDecimals,
        maxLeverage = info.maxLeverage,
        isIsolatedOnly = info.isIsolatedOnly,
        isDelisted = info.isDelisted,
      )
    }

  val spots: List<HyperliquidAsset> =
    spotMeta.tokens.associateBy(SpotToken::index).let { tokens ->
      spotMeta.universe.mapNotNull { pair ->
        val base = tokens[pair.tokens.getOrNull(0)] ?: return@mapNotNull null
        val quote = tokens[pair.tokens.getOrNull(1)] ?: return@mapNotNull null
        HyperliquidAsset(
          id = HyperliquidAsset.SPOT_ASSET_OFFSET + pair.index,
          coin = pair.name,
          kind = AssetKind.SPOT,
          displayName = "${base.name}/${quote.name}",
          szDecimals = base.szDecimals,
          baseToken = base,
          quoteToken = quote,
        )
      }
    }

  private val byCoin: Map<String, HyperliquidAsset> = (perps + spots).associateBy { it.coin }

  private val byId: Map<Int, HyperliquidAsset> = (perps + spots).associateBy { it.id }

  fun byCoin(coin: String): HyperliquidAsset? = byCoin[coin]

  fun byId(id: Int): HyperliquidAsset? = byId[id]

  fun requireCoin(coin: String): HyperliquidAsset =
    byCoin(coin) ?: throw IllegalArgumentException("Unknown Hyperliquid coin $coin")
}
