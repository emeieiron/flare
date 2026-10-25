package xyz.mcxross.flare.venue.decibel

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
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.MarketCatalog
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.AllMarketPrices
import xyz.mcxross.flare.decibel.api.AllSpotMids
import xyz.mcxross.flare.decibel.api.DecibelStreamData
import xyz.mcxross.flare.decibel.api.StreamEvent
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.MarketCacheDao
import xyz.mcxross.flare.store.MarketEntity
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.decibel.model.Market as DecibelMarket

/**
 * A market quote as the market cache stores it: Decibel's market as Decibel sends it. Its JSON
 * matches what earlier versions cached, so a cache written before an update still loads.
 */
@Serializable
internal data class CachedMarketQuote(
  val market: DecibelMarket,
  val markPrice: Double,
  val changePercent24h: Double,
  val volume24h: Double,
  val openInterest: Double,
  val favorite: Boolean = false,
  val fundingRateBps: Double? = null,
) {
  fun toMarketQuote(): MarketQuote =
    MarketQuote(
      market = market.toMarket(),
      markPrice = markPrice,
      changePercent24h = changePercent24h,
      volume24h = volume24h,
      openInterest = openInterest,
      favorite = favorite,
      fundingRateBps = fundingRateBps,
    )
}

class DefaultMarketsRepository(
  private val client: DecibelClient,
  private val preferences: AppPreferences,
  private val cache: MarketCacheDao,
) : MarketsRepository {
  private val favorites = mutableSetOf<String>()
  private val mutableCatalog = MutableStateFlow(MarketCatalog())
  private val refreshMutex = Mutex()
  /** When the last live snapshot landed; guarded by [refreshMutex]. */
  private var lastSnapshotAtMs = 0L
  override val catalog: StateFlow<MarketCatalog> = mutableCatalog.asStateFlow()

  override suspend fun refresh() = refreshMutex.withLock {
    mutableCatalog.update { it.copy(loading = it.quotes.isEmpty(), error = null) }
    favorites.clear()
    favorites += preferences.favoriteMarkets.first()
    // At launch the last known prices show at once, marked stale, while the live snapshot loads.
    if (mutableCatalog.value.quotes.isEmpty()) loadCachedMarkets()
    runSuspendCatching {
      coroutineScope {
        val markets = async { client.markets.markets() }
        val contexts = async { client.markets.assetContexts() }
        val prices = async { client.markets.prices() }
        val spotContexts = async {
          runCatching { client.markets.spotAssetContexts() }.getOrDefault(emptyList())
        }
        val contextsByMarket = contexts.await().associateBy { it.market }
        val pricesByMarket = prices.await().associateBy { it.market }
        val spotContextsByMarket = spotContexts.await().associateBy { it.marketAddress }
        val marketList = markets.await()
        seedDefaultWatchlist(marketList.map(DecibelMarket::toMarket))
        marketList.map { market ->
          if (market.assetType == AssetType.SPOT) {
            val spot = spotContextsByMarket[market.address] ?: spotContextsByMarket[market.name]
            CachedMarketQuote(
              market = market,
              markPrice = spot?.price ?: 0.0,
              changePercent24h = spot?.priceChangePercent24h ?: 0.0,
              volume24h = spot?.volume24hQuote ?: 0.0,
              openInterest = 0.0,
              favorite = market.address in favorites,
            )
          } else {
            val context = contextsByMarket[market.address] ?: contextsByMarket[market.name]
            val price = pricesByMarket[market.address] ?: pricesByMarket[market.name]
            CachedMarketQuote(
              market = market,
              markPrice = context?.markPrice ?: price?.markPrice ?: 0.0,
              changePercent24h = context?.priceChangePercent24h ?: 0.0,
              volume24h = context?.volume24h ?: 0.0,
              openInterest = context?.openInterest ?: price?.openInterest ?: 0.0,
              fundingRateBps = price?.fundingRateBps,
              favorite = market.address in favorites,
            )
          }
        }
      }
    }
      .onSuccess { cachedQuotes ->
        val updatedAtMs = Clock.System.now().toEpochMilliseconds()
        lastSnapshotAtMs = updatedAtMs
        val rows = withContext(Dispatchers.Default) {
          cachedQuotes.map { quote ->
            MarketEntity(
              marketAddress = quote.market.address,
              payloadJson = DecibelClient.DefaultJson.encodeToString(quote.copy(favorite = false)),
              updatedAtMs = updatedAtMs,
            )
          }
        }
        cache.clearMarkets()
        cache.upsertMarkets(rows)
        val quotes = cachedQuotes.map(CachedMarketQuote::toMarketQuote)
        mutableCatalog.value =
          MarketCatalog(
            loading = false,
            quotes =
              quotes.sortedWith(
                compareByDescending<MarketQuote> { it.favorite }.thenBy { it.market.symbol }
              ),
            stale = false,
            updatedAtMs = updatedAtMs,
          )
      }
      .onFailure { error ->
        loadCachedMarkets()
        mutableCatalog.update {
          it.copy(
            loading = false,
            stale = true,
            error =
              if (it.quotes.isEmpty()) error.message ?: "Market data is unavailable" else null,
          )
        }
      }
    Unit
  }

  private suspend fun loadCachedMarkets() {
    val rows = cache.markets()
    // Decoding every cached market is real work; it stays off the main thread during launch.
    val cachedQuotes = withContext(Dispatchers.Default) {
      rows.mapNotNull { row ->
        runCatching {
          DecibelClient.DefaultJson.decodeFromString<CachedMarketQuote>(row.payloadJson)
        }
          .getOrNull()
          ?.toMarketQuote()
      }
    }
    seedDefaultWatchlist(cachedQuotes.map { it.market })
    val quotes = cachedQuotes.map { quote ->
      quote.copy(favorite = quote.market.id in favorites)
    }
    if (quotes.isNotEmpty()) {
      mutableCatalog.value =
        MarketCatalog(
          loading = false,
          quotes =
            quotes.sortedWith(
              compareByDescending<MarketQuote> { it.favorite }.thenBy { it.market.symbol }
            ),
          stale = true,
          updatedAtMs = rows.maxOfOrNull(MarketEntity::updatedAtMs),
        )
    }
  }

  private suspend fun seedDefaultWatchlist(markets: List<Market>) {
    var changed = false
    val perpMarkets = markets.filter { it.kind == MarketKind.PERP }
    val spotMarkets = markets.filter { it.kind == MarketKind.SPOT }

    if (perpMarkets.isNotEmpty() && perpMarkets.none { it.id in favorites }) {
      val seededAddresses =
        perpMarkets
          .filter { it.symbol in DEFAULT_WATCHLIST_SYMBOLS }
          .mapTo(mutableSetOf()) { it.id }
      if (seededAddresses.isNotEmpty()) {
        favorites += seededAddresses
        changed = true
      }
    }

    val spotSeeded = preferences.values.first().spotWatchlistSeeded
    if (!spotSeeded && spotMarkets.isNotEmpty()) {
      val seededAddresses =
        spotMarkets
          .filter { it.symbol in DEFAULT_SPOT_WATCHLIST_SYMBOLS }
          .mapTo(mutableSetOf()) { it.id }
      if (seededAddresses.isNotEmpty()) {
        favorites += seededAddresses
        changed = true
      }
      preferences.setSpotWatchlistSeeded(true)
    }

    if (changed) {
      preferences.setFavoriteMarkets(favorites)
    }
  }

  override suspend fun connectLive() {
    var lastRecoveryAtMs = 0L
    client.stream.subscribe(setOf(AllMarketPrices, AllSpotMids)).collect { event ->
      when (event) {
        is StreamEvent.Connected -> backfill()
        is StreamEvent.Message -> {
          val now = Clock.System.now().toEpochMilliseconds()
          val prices = (event.data as? DecibelStreamData.MarketPrices)?.values
          val spotMids = (event.data as? DecibelStreamData.SpotMids)?.values
          if (prices != null) {
            val pricesByMarket = prices.associateBy { it.market }
            mutableCatalog.update { current ->
              current.copy(
                quotes =
                  current.quotes.map { quote ->
                    val price =
                      pricesByMarket[quote.market.id] ?: pricesByMarket[quote.market.displayName]
                    if (price == null) {
                      quote
                    } else {
                      quote.copy(
                        markPrice = price.markPrice,
                        openInterest = price.openInterest,
                        fundingRateBps = price.fundingRateBps,
                      )
                    }
                  },
                stale = false,
                error = null,
                updatedAtMs = now,
              )
            }
          }
          if (spotMids != null) {
            val spotByMarket = spotMids.associateBy { it.marketAddress }
            mutableCatalog.update { current ->
              current.copy(
                quotes =
                  current.quotes.map { quote ->
                    val spot = spotByMarket[quote.market.id]
                    val newPrice = spot?.price
                    if (newPrice == null) {
                      quote
                    } else {
                      quote.copy(markPrice = newPrice)
                    }
                  },
                stale = false,
                error = null,
                updatedAtMs = now,
              )
            }
          }
          if (
            event.data is DecibelStreamData.Malformed &&
              now - lastRecoveryAtMs >= MALFORMED_RECOVERY_INTERVAL_MS
          ) {
            lastRecoveryAtMs = now
            refresh()
          }
        }
        is StreamEvent.SequenceGap -> {
          lastRecoveryAtMs = Clock.System.now().toEpochMilliseconds()
          refresh()
        }
        is StreamEvent.Rejected ->
          mutableCatalog.update {
            it.copy(stale = true, error = event.reason)
          }
        is StreamEvent.Disconnected ->
          mutableCatalog.update {
            it.copy(stale = true, error = event.reason ?: "Live market stream disconnected")
          }
      }
    }
  }

  /**
   * A (re)connected stream backfills what it missed. At launch the stream connects while the first
   * snapshot is still loading, so it waits for that one and skips a second fetch if it just landed.
   */
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
            .map { quote ->
              if (quote.market.id == marketAddress) {
                quote.copy(favorite = marketAddress in favorites)
              } else {
                quote
              }
            }
            .sortedWith(
              compareByDescending<MarketQuote> { it.favorite }.thenBy { it.market.symbol }
            )
      )
    }
  }

  private companion object {
    const val MALFORMED_RECOVERY_INTERVAL_MS = 30_000L
    val DEFAULT_WATCHLIST_SYMBOLS = setOf("APT", "BTC", "GOLD", "AAPL")
    val DEFAULT_SPOT_WATCHLIST_SYMBOLS = setOf("APT")
  }
}

/** A stream that connects within this long of a live snapshot has nothing to backfill. */
private const val BACKFILL_FRESH_MS = 15_000L
