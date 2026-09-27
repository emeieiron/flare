package xyz.mcxross.flare.data

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.AllMarketPrices
import xyz.mcxross.flare.decibel.api.AllSpotMids
import xyz.mcxross.flare.decibel.api.DecibelStreamData
import xyz.mcxross.flare.decibel.api.MarketCandlestick
import xyz.mcxross.flare.decibel.api.StreamEvent
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Candle
import xyz.mcxross.flare.decibel.model.CandleInterval
import xyz.mcxross.flare.decibel.model.Market
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.CandleEntity
import xyz.mcxross.flare.store.MarketCacheDao
import xyz.mcxross.flare.store.MarketEntity
import xyz.mcxross.flare.store.SelectedMarketEntity

@Serializable
data class MarketQuote(
  val market: Market,
  val markPrice: Double,
  val changePercent24h: Double,
  val volume24h: Double,
  val openInterest: Double,
  val favorite: Boolean = false,
  val fundingRateBps: Double? = null,
)

data class MarketCatalog(
  val loading: Boolean = true,
  val quotes: List<MarketQuote> = emptyList(),
  val stale: Boolean = false,
  val error: String? = null,
  val updatedAtMs: Long? = null,
)

interface MarketsRepository {
  val catalog: StateFlow<MarketCatalog>

  suspend fun refresh()

  suspend fun connectLive()

  suspend fun toggleFavorite(marketAddress: String)
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
        seedDefaultWatchlist(marketList)
        marketList.map { market ->
          if (market.assetType == AssetType.SPOT) {
            val spot = spotContextsByMarket[market.address] ?: spotContextsByMarket[market.name]
            MarketQuote(
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
            MarketQuote(
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
      .onSuccess { quotes ->
        val updatedAtMs = Clock.System.now().toEpochMilliseconds()
        lastSnapshotAtMs = updatedAtMs
        val rows = withContext(Dispatchers.Default) {
          quotes.map { quote ->
            MarketEntity(
              marketAddress = quote.market.address,
              payloadJson = DecibelClient.DefaultJson.encodeToString(quote.copy(favorite = false)),
              updatedAtMs = updatedAtMs,
            )
          }
        }
        cache.upsertMarkets(rows)
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
          DecibelClient.DefaultJson.decodeFromString<MarketQuote>(row.payloadJson)
        }
          .getOrNull()
      }
    }
    seedDefaultWatchlist(cachedQuotes.map { it.market })
    val quotes = cachedQuotes.map { quote ->
      quote.copy(favorite = quote.market.address in favorites)
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
    val perpMarkets = markets.filter { it.assetType == AssetType.PERP }
    val spotMarkets = markets.filter { it.assetType == AssetType.SPOT }

    if (perpMarkets.isNotEmpty() && perpMarkets.none { it.address in favorites }) {
      val seededAddresses =
        perpMarkets
          .filter { it.symbol in DEFAULT_WATCHLIST_SYMBOLS }
          .mapTo(mutableSetOf()) { it.address }
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
          .mapTo(mutableSetOf()) { it.address }
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
                      pricesByMarket[quote.market.address] ?: pricesByMarket[quote.market.name]
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
                    val spot = spotByMarket[quote.market.address]
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
              if (quote.market.address == marketAddress) {
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

/** A chart's candle size. The chart opens on the latest candles and loads older ones as it pans. */
enum class ChartTimeframe(
  val label: String,
  val title: String,
  val interval: CandleInterval,
) {
  ONE_MINUTE("1m", "1 minute", CandleInterval.ONE_MINUTE),
  FIVE_MINUTES("5m", "5 minutes", CandleInterval.FIVE_MINUTES),
  FIFTEEN_MINUTES("15m", "15 minutes", CandleInterval.FIFTEEN_MINUTES),
  THIRTY_MINUTES("30m", "30 minutes", CandleInterval.THIRTY_MINUTES),
  ONE_HOUR("1h", "1 hour", CandleInterval.ONE_HOUR),
  TWO_HOURS("2h", "2 hours", CandleInterval.TWO_HOURS),
  FOUR_HOURS("4h", "4 hours", CandleInterval.FOUR_HOURS),
  EIGHT_HOURS("8h", "8 hours", CandleInterval.EIGHT_HOURS),
  TWELVE_HOURS("12h", "12 hours", CandleInterval.TWELVE_HOURS),
  ONE_DAY("1d", "1 day", CandleInterval.ONE_DAY),
  ONE_WEEK("1w", "1 week", CandleInterval.ONE_WEEK),
  // A capital M, so a month never reads as a minute.
  ONE_MONTH("1M", "1 month", CandleInterval.ONE_MONTH);

  val durationMs: Long
    get() = interval.durationMs()

  companion object {
    val Default = FIVE_MINUTES
  }
}

/** The span of the page of history that ends at [endTimeMs]. */
fun candlePage(endTimeMs: Long, timeframe: ChartTimeframe): Pair<Long, Long> =
  (endTimeMs - timeframe.durationMs * CANDLES_PER_PAGE) to endTimeMs

fun candleRequestWindows(
  startTimeMs: Long,
  endTimeMs: Long,
  interval: CandleInterval,
): List<Pair<Long, Long>> {
  require(startTimeMs < endTimeMs) { "Candle start time must be before end time" }
  // Overlap one boundary so this stays safe whether Decibel treats endTime as inclusive or
  // exclusive. At most 999 intervals can yield at most 1000 inclusive candle timestamps.
  val windowDuration = interval.durationMs() * (MAX_CANDLES_PER_REQUEST - 1)
  val windows = mutableListOf<Pair<Long, Long>>()
  var cursor = startTimeMs
  while (cursor < endTimeMs) {
    val windowEnd = minOf(endTimeMs, cursor + windowDuration)
    windows += cursor to windowEnd
    if (windowEnd == endTimeMs) break
    cursor = windowEnd
  }
  return windows
}

interface ChartRepository {
  /** The latest page of candles, from the cache when the network fails. */
  suspend fun candles(market: String, timeframe: ChartTimeframe): ChartSnapshot

  /** The page of candles before [beforeMs]; empty once the market's history runs out. */
  suspend fun candlesBefore(market: String, timeframe: ChartTimeframe, beforeMs: Long): List<Candle>

  /** The forming candle as it changes, and each new one, while collected. */
  fun liveCandles(market: String, timeframe: ChartTimeframe): Flow<LiveCandles>
}

sealed interface LiveCandles {
  /** The stream (re)started or missed updates, so history may need catching up. */
  data object Connected : LiveCandles

  data class Update(val candle: Candle) : LiveCandles
}

data class ChartSnapshot(
  val candles: List<Candle>,
  val stale: Boolean,
  val error: String? = null,
)

class DefaultChartRepository(
  private val client: DecibelClient,
  private val cache: MarketCacheDao,
) : ChartRepository {
  override suspend fun candles(market: String, timeframe: ChartTimeframe): ChartSnapshot {
    val now = Clock.System.now().toEpochMilliseconds()
    val (startTimeMs, endTimeMs) = candlePage(now, timeframe)
    cache.selectMarket(SelectedMarketEntity(market, now))
    return runSuspendCatching { fetch(market, timeframe, startTimeMs, endTimeMs) }
      .fold(
        onSuccess = { candles -> ChartSnapshot(candles = candles, stale = false) },
        onFailure = { error ->
          val cached =
            cache
              .candles(market, timeframe.interval.wireValue, startTimeMs, endTimeMs)
              .map(CandleEntity::toDomain)
          if (cached.isEmpty()) throw error
          ChartSnapshot(
            candles = cached,
            stale = true,
            error = error.message ?: "Using cached candle history",
          )
        },
      )
  }

  override suspend fun candlesBefore(
    market: String,
    timeframe: ChartTimeframe,
    beforeMs: Long,
  ): List<Candle> {
    val (startTimeMs, endTimeMs) = candlePage(beforeMs - 1, timeframe)
    return fetch(market, timeframe, startTimeMs, endTimeMs).filter { it.openTimeMs < beforeMs }
  }

  override fun liveCandles(market: String, timeframe: ChartTimeframe): Flow<LiveCandles> =
    client.stream.subscribe(setOf(MarketCandlestick(market, timeframe.interval))).mapNotNull { event ->
      when (event) {
        is StreamEvent.Connected,
        is StreamEvent.SequenceGap -> LiveCandles.Connected
        is StreamEvent.Message ->
          (event.data as? DecibelStreamData.CandleValue)
            ?.value
            ?.takeIf { it.interval == timeframe.interval.wireValue }
            ?.let(LiveCandles::Update)
        is StreamEvent.Rejected,
        is StreamEvent.Disconnected -> null
      }
    }

  private suspend fun fetch(
    market: String,
    timeframe: ChartTimeframe,
    startTimeMs: Long,
    endTimeMs: Long,
  ): List<Candle> {
    val candles =
      candleRequestWindows(startTimeMs, endTimeMs, timeframe.interval)
        .flatMap { (windowStartMs, windowEndMs) ->
          client.markets.candles(
            market = market,
            interval = timeframe.interval,
            startTimeMs = windowStartMs,
            endTimeMs = windowEndMs,
            filterWicks = false,
          )
        }
        .distinctBy(Candle::openTimeMs)
        .sortedBy(Candle::openTimeMs)
    cache.upsertCandles(candles.map { it.toEntity(market, timeframe.interval) })
    cache.deleteCandlesBefore(Clock.System.now().toEpochMilliseconds() - CANDLE_RETENTION_MS)
    return candles
  }
}

private fun Candle.toEntity(market: String, interval: CandleInterval) =
  CandleEntity(
    marketAddress = market,
    interval = interval.wireValue,
    openTimeMs = openTimeMs,
    closeTimeMs = closeTimeMs,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
  )

private fun CandleEntity.toDomain() =
  Candle(
    openTimeMs = openTimeMs,
    closeTimeMs = closeTimeMs,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume,
    interval = interval,
  )

private const val MAX_CANDLES_PER_REQUEST = 1_000L
private const val CANDLES_PER_PAGE = 500L
private const val FOUR_HOURS_MS = 4L * 60 * 60 * 1_000
private const val CANDLE_RETENTION_MS = 5L * 366 * 24 * 60 * 60 * 1_000

/** How long a candle of this wire interval lasts, or null for one Flare doesn't know. */
fun candleDurationMs(interval: String): Long? =
  CandleInterval.entries.firstOrNull { it.wireValue == interval }?.durationMs()

private fun CandleInterval.durationMs(): Long =
  when (this) {
    CandleInterval.ONE_MINUTE -> 60L * 1_000
    CandleInterval.FIVE_MINUTES -> 5L * 60 * 1_000
    CandleInterval.FIFTEEN_MINUTES -> 15L * 60 * 1_000
    CandleInterval.THIRTY_MINUTES -> 30L * 60 * 1_000
    CandleInterval.ONE_HOUR -> 60L * 60 * 1_000
    CandleInterval.TWO_HOURS -> 2L * 60 * 60 * 1_000
    CandleInterval.FOUR_HOURS -> FOUR_HOURS_MS
    CandleInterval.EIGHT_HOURS -> 8L * 60 * 60 * 1_000
    CandleInterval.TWELVE_HOURS -> 12L * 60 * 60 * 1_000
    CandleInterval.ONE_DAY -> 24L * 60 * 60 * 1_000
    CandleInterval.ONE_WEEK -> 7L * 24 * 60 * 60 * 1_000
    CandleInterval.ONE_MONTH -> 30L * 24 * 60 * 60 * 1_000
  }

fun formatPrice(value: Double): String =
  when {
    !value.isFinite() || value <= 0.0 -> "—"
    value >= 1_000.0 -> "\$${groupDigits(fixed(value, 2))}"
    value >= 1.0 -> "\$${fixed(value, 3)}"
    else -> "\$${fixed(value, 5)}"
  }

fun formatPercent(value: Double): String = "${fixed(abs(value), 2)}%"

fun formatCompact(value: Double): String {
  val magnitude = abs(value)
  return when {
    magnitude >= 1_000_000_000 -> "\$${fixed(value / 1_000_000_000, 2)}B"
    magnitude >= 1_000_000 -> "\$${fixed(value / 1_000_000, 2)}M"
    magnitude >= 1_000 -> "\$${fixed(value / 1_000, 2)}K"
    else -> "\$${fixed(value, 2)}"
  }
}

private fun fixed(value: Double, decimals: Int): String {
  val factor = pow10(decimals)
  if (abs(value) > Long.MAX_VALUE.toDouble() / factor) return value.toString()
  val scaled = (value * factor).roundToLong()
  val whole = scaled / factor
  val fraction = abs(scaled % factor).toString().padStart(decimals, '0')
  val sign = if (scaled < 0 && whole == 0L) "-" else ""
  return sign + if (decimals == 0) whole.toString() else "$whole.$fraction"
}

private fun pow10(exponent: Int): Long {
  var result = 1L
  repeat(exponent) { result *= 10L }
  return result
}

/** Account balances include zero and losses, unlike market quotes that require a positive price. */
fun formatBalance(value: Double): String =
  if (!value.isFinite()) "—"
  else if (value != 0.0 && abs(value) < 0.005) {
    (if (value < 0) "−" else "") + "<\$0.01"
  } else (if (value < 0) "−" else "") + "\$" + groupDigits(fixed(abs(value), 2))

/** Signed money. A value that rounds away is flat, never a signed "<$0.01". */
fun formatSignedBalance(value: Double): String =
  when {
    !value.isFinite() -> "—"
    abs(value) < 0.005 -> "\$0.00"
    value > 0.0 -> "+" + formatBalance(value)
    else -> formatBalance(value)
  }

/** Display only. Transaction inputs continue to use exact decimal strings and chain units. */
fun formatQuantity(value: Double, decimals: Int = 8): String {
  if (!value.isFinite()) return "—"
  val precision = decimals.coerceIn(0, 12)
  val formatted = fixed(value, precision)
  return if (precision == 0) formatted else formatted.trimEnd('0').trimEnd('.')
}

/** A whole count with its thousands grouped, like a points total. Counts are never negative. */
fun formatCount(value: Double): String = if (!value.isFinite()) "—" else groupDigits(fixed(abs(value), 0))

private fun groupDigits(number: String): String {
  val parts = number.split('.')
  val whole = parts.first().reversed().chunked(3).joinToString(",").reversed()
  return whole + if (parts.size > 1) "." + parts[1] else ""
}

/** A stream that connects within this long of a live snapshot has nothing to backfill. */
private const val BACKFILL_FRESH_MS = 15_000L
