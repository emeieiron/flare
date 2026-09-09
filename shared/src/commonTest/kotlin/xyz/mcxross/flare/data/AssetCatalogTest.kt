package xyz.mcxross.flare.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.design.resolveAssetIdentity

class AssetCatalogTest {
  @Test
  fun manifestDecodesMixedAssetKindsAndUnknownFields() {
    val manifest =
      Json { ignoreUnknownKeys = true }
        .decodeFromString<AssetManifest>(
          """{"schemaVersion":1,"revision":"abc","ignored":true,"assets":[{"symbol":"BTC","name":"Bitcoin","kind":"crypto","icon":"/assets/btc.svg","sha256":"hash"},{"symbol":"AAPL","name":"Apple","kind":"equity","icon":null}]}"""
        )

    assertEquals("abc", manifest.revision)
    assertEquals(listOf("crypto", "equity"), manifest.assets.map(AssetManifestEntry::kind))
    assertNull(manifest.assets[1].icon)
  }

  @Test
  fun assetKeysAreCaseInsensitiveAndPathsResolveAgainstCatalogOrigin() {
    assertEquals("KPEPE", assetKey("kPEPE"))
    assertEquals("https://flare.mcxross.xyz/assets/kpepe.svg", resolveAssetIconUrl("/assets/kpepe.svg"))
    assertEquals("https://example.test/icon.svg", resolveAssetIconUrl("https://example.test/icon.svg"))
  }

  @Test
  fun identityUsesMetadataWhenAvailableAndProtocolFallbackOtherwise() {
    val known =
      resolveAssetIdentity(
        symbol = "kPEPE",
        fallbackName = "kPEPE-USD",
        metadata = AssetMetadata("kPEPE", "1000PEPE", "crypto", null, null),
      )
    val unknown = resolveAssetIdentity("NEW", "NEW-USD", null)

    assertEquals("1000PEPE", known.name)
    assertEquals("kPEPE", known.symbol)
    assertEquals("NEW-USD", unknown.name)
    assertEquals("NEW", unknown.symbol)
  }
}
