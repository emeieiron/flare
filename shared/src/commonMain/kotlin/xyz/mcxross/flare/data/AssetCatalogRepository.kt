package xyz.mcxross.flare.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.store.AssetCatalogDao
import xyz.mcxross.flare.store.AssetCatalogSyncEntity
import xyz.mcxross.flare.store.AssetMetadataEntity

private const val ASSET_CATALOG_URL = "https://flare.mcxross.xyz/api/assets/v1/manifest.json"
private const val ASSET_CATALOG_ORIGIN = "https://flare.mcxross.xyz"

@Serializable
data class AssetManifest(
  val schemaVersion: Int,
  val revision: String,
  val assets: List<AssetManifestEntry>,
)

@Serializable
data class AssetManifestEntry(
  val symbol: String,
  val name: String,
  val kind: String,
  val icon: String? = null,
  val sha256: String? = null,
)

data class AssetMetadata(
  val symbol: String,
  val name: String,
  val kind: String,
  val iconUrl: String?,
  val sha256: String?,
)

interface AssetCatalogRepository {
  val assets: StateFlow<Map<String, AssetMetadata>>

  suspend fun refresh()

  fun assetFor(symbol: String): AssetMetadata? = assets.value[assetKey(symbol)]
}

class DefaultAssetCatalogRepository(
  private val httpClient: HttpClient,
  private val cache: AssetCatalogDao,
) : AssetCatalogRepository {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val refreshMutex = Mutex()

  override val assets: StateFlow<Map<String, AssetMetadata>> =
    cache
      .observeAssets()
      .map { rows -> rows.associate { it.symbolKey to it.toDomain() } }
      .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())

  override suspend fun refresh() {
    refreshMutex.withLock {
      val manifest = httpClient.get(ASSET_CATALOG_URL).body<AssetManifest>()
      require(manifest.schemaVersion == 1) {
        "Unsupported asset catalog schema ${manifest.schemaVersion}"
      }
      val refreshedAtMs = Clock.System.now().toEpochMilliseconds()
      if (cache.revision() == manifest.revision) {
        cache.upsertSync(
          AssetCatalogSyncEntity(revision = manifest.revision, refreshedAtMs = refreshedAtMs)
        )
        return@withLock
      }
      cache.replaceCatalog(
        assets = manifest.assets.map { entry -> entry.toEntity(manifest.revision, refreshedAtMs) },
        sync = AssetCatalogSyncEntity(revision = manifest.revision, refreshedAtMs = refreshedAtMs),
      )
    }
  }
}

fun assetKey(symbol: String): String = symbol.trim().uppercase()

fun resolveAssetIconUrl(path: String?): String? =
  path
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { icon ->
      if (icon.startsWith("https://") || icon.startsWith("http://")) icon
      else "$ASSET_CATALOG_ORIGIN/${icon.trimStart('/')}"
    }

private fun AssetManifestEntry.toEntity(revision: String, updatedAtMs: Long) =
  AssetMetadataEntity(
    symbolKey = assetKey(symbol),
    symbol = symbol,
    name = name,
    kind = kind,
    iconUrl = resolveAssetIconUrl(icon),
    sha256 = sha256,
    revision = revision,
    updatedAtMs = updatedAtMs,
  )

private fun AssetMetadataEntity.toDomain() =
  AssetMetadata(symbol = symbol, name = name, kind = kind, iconUrl = iconUrl, sha256 = sha256)
