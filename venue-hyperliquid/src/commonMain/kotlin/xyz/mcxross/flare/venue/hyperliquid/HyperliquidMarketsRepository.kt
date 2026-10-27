package xyz.mcxross.flare.venue.hyperliquid

import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.MarketCatalog
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.api.StreamData
import xyz.mcxross.flare.hyperliquid.api.StreamEvent
import xyz.mcxross.flare.hyperliquid.api.Subscription
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.MarketCacheDao
import xyz.mcxross.flare.store.MarketEntity
import xyz.mcxross.flare.venue.model.MarketKind

/**
 * Hyperliquid's perp and spot markets from `metaAndAssetCtxs` and `spotMetaAndAssetCtxs`, kept live
 * by the `allMids` stream. The last snapshot is cached, so the list shows at once on launch.
 */
class HyperliquidMarketsRepository(
  private val client: HyperliquidClient,
  private val preferences: AppPreferences,
  private val cache: MarketCacheDao,
) : MarketsRepository {
  private val favorites = mutableSetOf<String>()
  private val mutableCatalog = MutableStateFlow(MarketCatalog())
  private val refreshMutex = Mutex()
  /** When the last live snapshot landed; guarded by [refreshMutex]. */
  private var lastSnapshotAtMs = 0L
  /** Yesterday's price of each market, which live mids measure the day's change against. */
  private var previousDayPrices: Map<String, Double> = emptyMap()
  override val catalog: StateFlow<MarketCatalog> = mutableCatalog.asStateFlow()

  /** Whether Hyperliquid lists [coin]. Streams close on a coin it doesn't, so they check first. */
  suspend fun isListed(coin: String): Boolean {
    if (mutableCatalog.value.quotes.isEmpty()) refresh()
    return mutableCatalog.value.quotes.any { it.market.id == coin }
  }

  override suspend fun refresh() = refreshMutex.withLock {
    mutableCatalog.update { it.copy(loading = it.quotes.isEmpty(), error = null) }
    favorites.clear()
    favorites += preferences.favoriteMarkets.first()
    if (mutableCatalog.value.quotes.isEmpty()) loadCachedMarkets()
    runSuspendCatching {
      coroutineScope {
        val perps = async { client.info.perpMetaAndContexts() }
        val spots = async {
          runSuspendCatching { client.info.spotMetaAndContexts() }.getOrNull()
        }
        perps.await().toCachedMarkets() +
          spots.await()?.toCachedMarkets(MIN_SPOT_DAY_VOLUME).orEmpty()
      }
    }
      .onSuccess { markets ->
        val updatedAtMs = Clock.System.now().toEpochMilliseconds()
        lastSnapshotAtMs = updatedAtMs
        val rows = withContext(Dispatchers.Default) {
          markets.map {
            MarketEntity(it.coin, CacheJson.encodeToString(CachedMarket.serializer(), it), updatedAtMs)
          }
        }
        cache.clearMarkets()
        cache.upsertMarkets(rows)
        show(markets, stale = false, updatedAtMs = updatedAtMs)
      }
      .onFailure { error ->
        loadCachedMarkets()
        mutableCatalog.update {
          it.copy(
            loading = false,
            stale = true,
            error = if (it.quotes.isEmpty()) error.message ?: "Market data is unavailable" else null,
          )
        }
      }
    Unit
  }

  private suspend fun loadCachedMarkets() {
    val rows = cache.markets()
    val markets = withContext(Dispatchers.Default) {
      rows.mapNotNull { row ->
        runCatching { CacheJson.decodeFromString(CachedMarket.serializer(), row.payloadJson) }
          .getOrNull()
      }
    }
    if (markets.isNotEmpty()) show(markets, stale = true, updatedAtMs = rows.maxOf { it.updatedAtMs })
  }

  private suspend fun show(markets: List<CachedMarket>, stale: Boolean, updatedAtMs: Long) {
    previousDayPrices = markets.associate { it.coin to it.previousDayPrice }
    val quotes = markets.map { it.toQuote() }
    seedDefaultWatchlist(quotes)
    mutableCatalog.value =
      MarketCatalog(
        loading = false,
        quotes = quotes.map { it.copy(favorite = it.market.id in favorites) }.sortedWith(QuoteOrder),
        stale = stale,
        updatedAtMs = updatedAtMs,
      )
  }

  private suspend fun seedDefaultWatchlist(quotes: List<MarketQuote>) {
    var changed = false
    val perps = quotes.filter { it.market.kind == MarketKind.PERP }.map { it.market }
    val spots = quotes.filter { it.market.kind == MarketKind.SPOT }.map { it.market }
    if (perps.isNotEmpty() && perps.none { it.id in favorites }) {
      val seeded = perps.filter { it.symbol in DEFAULT_WATCHLIST }.map { it.id }
      if (seeded.isNotEmpty()) {
        favorites += seeded
        changed = true
      }
    }
    if (!preferences.values.first().spotWatchlistSeeded && spots.isNotEmpty()) {
      val seeded = spots.filter { it.symbol in DEFAULT_SPOT_WATCHLIST && it.quoteAsset == null }.map { it.id }
      if (seeded.isNotEmpty()) {
        favorites += seeded
        changed = true
      }
      preferences.setSpotWatchlistSeeded(true)
    }
    if (changed) preferences.setFavoriteMarkets(favorites)
  }

  override suspend fun connectLive() {
    var lastRecoveryAtMs = 0L
    client.stream.subscribe(setOf(Subscription.AllMids)).collect { event ->
      when (event) {
        is StreamEvent.Connected -> backfill()
        is StreamEvent.Message -> {
          val now = Clock.System.now().toEpochMilliseconds()
          when (val data = event.data) {
            is StreamData.Mids -> applyMids(data.mids, now)
            is StreamData.Malformed ->
              if (now - lastRecoveryAtMs >= MALFORMED_RECOVERY_INTERVAL_MS) {
                lastRecoveryAtMs = now
                refresh()
              }
            else -> Unit
          }
        }
        is StreamEvent.Rejected -> mutableCatalog.update { it.copy(stale = true, error = event.reason) }
        is StreamEvent.Disconnected ->
          mutableCatalog.update {
            it.copy(stale = true, error = event.reason ?: "Live market stream disconnected")
          }
      }
    }
  }

  internal fun applyMids(mids: Map<String, String>, now: Long) {
    mutableCatalog.update { current ->
      current.copy(
        quotes =
          current.quotes.map { quote ->
            val mid = mids[quote.market.id]?.toDoubleOrNull()?.takeIf { it > 0.0 }
            if (mid == null) quote
            else
              quote.copy(
                markPrice = mid,
                changePercent24h = changePercent(previousDayPrices[quote.market.id] ?: 0.0, mid),
              )
          },
        stale = false,
        error = null,
        updatedAtMs = now,
      )
    }
  }

  /** A (re)connected stream catches up, unless a snapshot just landed. */
  private suspend fun backfill() {
    refreshMutex.withLock {}
    if (Clock.System.now().toEpochMilliseconds() - lastSnapshotAtMs < BACKFILL_FRESH_MS) return
    refresh()
  }

  override suspend fun toggleFavorite(marketAddress: String) {
    if (!favorites.add(marketAddress)) favorites.remove(marketAddress)
    preferences.setFavoriteMarkets(favorites)
    mutableCatalog.update { state ->
      state.copy(
        quotes =
          state.quotes
            .map { if (it.market.id == marketAddress) it.copy(favorite = marketAddress in favorites) else it }
            .sortedWith(QuoteOrder)
      )
    }
  }

  private companion object {
    const val MALFORMED_RECOVERY_INTERVAL_MS = 30_000L
    const val BACKFILL_FRESH_MS = 15_000L
    val DEFAULT_WATCHLIST = setOf("BTC", "ETH", "HYPE", "SOL")
    val DEFAULT_SPOT_WATCHLIST = setOf("HYPE")
    val QuoteOrder = compareByDescending<MarketQuote> { it.favorite }.thenBy { it.market.symbol }
    val CacheJson = Json { ignoreUnknownKeys = true }
  }
}
