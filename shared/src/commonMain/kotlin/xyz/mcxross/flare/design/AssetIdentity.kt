package xyz.mcxross.flare.design

import xyz.mcxross.flare.data.AssetMetadata
import xyz.mcxross.flare.venue.model.Market

/** Display metadata only. Protocol symbols and addresses remain unchanged. */
data class AssetIdentity(
  val symbol: String,
  val name: String,
  val kind: String? = null,
  val iconUrl: String? = null,
)

fun resolveAssetIdentity(
  symbol: String,
  fallbackName: String,
  metadata: AssetMetadata?,
): AssetIdentity =
  AssetIdentity(
    symbol = metadata?.symbol ?: symbol,
    name = metadata?.name ?: fallbackName,
    kind = metadata?.kind,
    iconUrl = metadata?.iconUrl,
  )

/** A market's asset, named as the pair when its quote is unusual, so pairs of one asset differ. */
fun resolveMarketIdentity(market: Market, metadata: AssetMetadata?): AssetIdentity =
  resolveAssetIdentity(market.symbol, market.displayName, metadata).let {
    if (market.quoteAsset != null) it.copy(name = market.displayName) else it
  }

fun assetMonogram(symbol: String): String = symbol.trim().firstOrNull()?.uppercase() ?: "?"

fun AssetIdentity.detailLabel(): String =
  kind?.replaceFirstChar(Char::titlecase)?.let { "$name · $it" } ?: name
