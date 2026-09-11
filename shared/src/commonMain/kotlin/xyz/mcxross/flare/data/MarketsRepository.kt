package xyz.mcxross.flare.data

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.AllMarketPrices
import xyz.mcxross.flare.decibel.api.DecibelStreamData
import xyz.mcxross.flare.decibel.api.StreamEvent
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
)

data class MarketCatalog(
  val loading: Boolean = false,
  val quotes: List<MarketQuote> = emptyList(),
  val stale: Boolean = true,
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
  override val catalog: StateFlow<MarketCatalog> = mutableCatalog.asStateFlow()

  override suspend fun refresh() = refreshMutex.withLock {
    mutableCatalog.update { it.copy(loading = true, error = null) }
    favorites.clear()
    favorites += preferences.favoriteMarkets.first()
    if (mutableCatalog.value.quotes.isEmpty()) loadCachedMarkets()
    runSuspendCatching {
      coroutineScope {
        val markets = async { client.markets.markets() }
        val contexts = async { client.markets.assetContexts() }
        val prices = async { client.markets.prices() }
        val contextsByMarket = contexts.await().associateBy { it.market }
        val pricesByMarket = prices.await().associateBy { it.market }
        val marketList = markets.await()
        seedDefaultWatchlist(marketList)
        marketList.map { market ->
          val context = contextsByMarket[market.address] ?: contextsByMarket[market.name]
          val price = pricesByMarket[market.address] ?: pricesByMarket[market.name]
          MarketQuote(
            market = market,
            markPrice = context?.markPrice ?: price?.markPrice ?: 0.0,
            changePercent24h = context?.priceChangePercent24h ?: 0.0,
            volume24h = context?.volume24h ?: 0.0,
            openInterest = context?.openInterest ?: price?.openInterest ?: 0.0,
            favorite = market.address in favorites,
          )
        }
      }
    }
      .onSuccess { quotes ->
        val updatedAtMs = Clock.System.now().toEpochMilliseconds()
        cache.upsertMarkets(
          quotes.map { quote ->
            MarketEntity(
              marketAddress = quote.market.address,
              payloadJson = DecibelClient.DefaultJson.encodeToString(quote.copy(favorite = false)),
              updatedAtMs = updatedAtMs,
            )
          }
        )
        mutableCatalog.value =
          MarketCatalog(
            quotes =
              quotes.sortedWith(
                compareByDescending<MarketQuote> { it.favorite }.thenBy { it.market.symbol }
              ),
            stale = false,
            updatedAtMs = updatedAtMs,
          )
      }
      .onFailure { error ->
        mutableCatalog.update {
          it.copy(
            loading = false,
            stale = true,
            error = error.message ?: "Market data is unavailable",
          )
        }
      }
    Unit
  }

  private suspend fun loadCachedMarkets() {
    val rows = cache.markets()
    val cachedQuotes = rows.mapNotNull { row ->
      runCatching {
        DecibelClient.DefaultJson.decodeFromString<MarketQuote>(row.payloadJson)
      }
        .getOrNull()
    }
    seedDefaultWatchlist(cachedQuotes.map { it.market })
    val quotes = cachedQuotes.map { quote -> quote.copy(favorite = quote.market.address in favorites) }
    if (quotes.isNotEmpty()) {
      mutableCatalog.value =
        MarketCatalog(
          loading = true,
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
    if (markets.any { it.address in favorites }) return

    val seededAddresses =
      markets.filter { it.symbol in DEFAULT_WATCHLIST_SYMBOLS }.mapTo(mutableSetOf()) { it.address }
    if (seededAddresses.isNotEmpty()) {
      favorites += seededAddresses
      preferences.setFavoriteMarkets(favorites)
    }
  }

  override suspend fun connectLive() {
    var lastRecoveryAtMs = 0L
    client.stream.subscribe(setOf(AllMarketPrices)).collect { event ->
      when (event) {
        is StreamEvent.Connected -> refresh()
        is StreamEvent.Message -> {
          val now = Clock.System.now().toEpochMilliseconds()
          val prices = (event.data as? DecibelStreamData.MarketPrices)?.values
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
                      )
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
  }
}

enum class ChartRange(val label: String, val interval: CandleInterval, val durationMs: Long?) {
  DAY("1D", CandleInterval.ONE_MINUTE, 24L * 60 * 60 * 1_000),
  WEEK("1W", CandleInterval.FIFTEEN_MINUTES, 7L * 24 * 60 * 60 * 1_000),
  MONTH("1M", CandleInterval.ONE_HOUR, 30L * 24 * 60 * 60 * 1_000),
  THREE_MONTHS("3M", CandleInterval.FOUR_HOURS, 90L * 24 * 60 * 60 * 1_000),
  YEAR_TO_DATE("YTD", CandleInterval.FOUR_HOURS, null),
  YEAR("1Y", CandleInterval.ONE_DAY, 365L * 24 * 60 * 60 * 1_000),
  FIVE_YEARS("5Y", CandleInterval.ONE_DAY, 5L * 365 * 24 * 60 * 60 * 1_000),
}

data class ChartRequestSpec(
  val startTimeMs: Long,
  val interval: CandleInterval,
)

fun chartRequestSpec(range: ChartRange, nowMs: Long): ChartRequestSpec {
  val startTimeMs =
    range.durationMs?.let { nowMs - it }
      ?: run {
        val year = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.UTC).year
        LocalDate(year, 1, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
      }
  val interval =
    if (
      range == ChartRange.YEAR_TO_DATE &&
        (nowMs - startTimeMs) / FOUR_HOURS_MS >= MAX_CANDLES_PER_REQUEST
    ) {
      CandleInterval.ONE_DAY
    } else {
      range.interval
    }
  return ChartRequestSpec(startTimeMs, interval)
}

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
  suspend fun candles(market: String, range: ChartRange): ChartSnapshot
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
  override suspend fun candles(market: String, range: ChartRange): ChartSnapshot {
    val now = Clock.System.now().toEpochMilliseconds()
    val request = chartRequestSpec(range, now)
    cache.selectMarket(SelectedMarketEntity(market, now))
    val cached =
      cache
        .candles(market, request.interval.wireValue, request.startTimeMs, now)
        .map(CandleEntity::toDomain)
    return runSuspendCatching {
        candleRequestWindows(request.startTimeMs, now, request.interval)
          .flatMap { (startTimeMs, endTimeMs) ->
            client.markets.candles(
              market = market,
              interval = request.interval,
              startTimeMs = startTimeMs,
              endTimeMs = endTimeMs,
              filterWicks = false,
            )
          }
          .distinctBy(Candle::openTimeMs)
          .sortedBy(Candle::openTimeMs)
      }
      .fold(
        onSuccess = { candles ->
          cache.upsertCandles(candles.map { it.toEntity(market, request.interval) })
          cache.deleteCandlesBefore(now - CANDLE_RETENTION_MS)
          ChartSnapshot(candles = candles, stale = false)
        },
        onFailure = { error ->
          if (cached.isEmpty()) throw error
          ChartSnapshot(
            candles = cached,
            stale = true,
            error = error.message ?: "Using cached candle history",
          )
        },
      )
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
private const val FOUR_HOURS_MS = 4L * 60 * 60 * 1_000
private const val CANDLE_RETENTION_MS = 5L * 366 * 24 * 60 * 60 * 1_000

private fun CandleInterval.durationMs(): Long =
  when (this) {
    CandleInterval.ONE_MINUTE -> 60L * 1_000
    CandleInterval.FIVE_MINUTES -> 5L * 60 * 1_000
    CandleInterval.FIFTEEN_MINUTES -> 15L * 60 * 1_000
    CandleInterval.THIRTY_MINUTES -> 30L * 60 * 1_000
    CandleInterval.ONE_HOUR -> 60L * 60 * 1_000
    CandleInterval.TWO_HOURS -> 2L * 60 * 60 * 1_000
    CandleInterval.FOUR_HOURS -> FOUR_HOURS_MS
    CandleInterval.ONE_DAY -> 24L * 60 * 60 * 1_000
    CandleInterval.ONE_WEEK -> 7L * 24 * 60 * 60 * 1_000
    CandleInterval.ONE_MONTH -> 31L * 24 * 60 * 60 * 1_000
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

private fun groupDigits(number: String): String {
  val parts = number.split('.')
  val whole = parts.first().reversed().chunked(3).joinToString(",").reversed()
  return whole + if (parts.size > 1) "." + parts[1] else ""
}
