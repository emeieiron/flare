package xyz.mcxross.flare.feature.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.assetKey
import xyz.mcxross.flare.decibel.model.AssetType

enum class MarketInstrumentFilter {
  PERPETUALS,
  SPOT,
}

/** Each product page keeps its own chip selection, so swiping between them never resets either. */
internal data class MarketPageFilter(
  val favoritesOnly: Boolean = true,
  val category: String? = null,
)

internal data class MarketFilter(
  val query: String = "",
  val instrument: MarketInstrumentFilter = MarketInstrumentFilter.PERPETUALS,
  val pages: Map<MarketInstrumentFilter, MarketPageFilter> =
    MarketInstrumentFilter.entries.associateWith { MarketPageFilter() },
  val searching: Boolean = false,
) {
  val page: MarketPageFilter
    get() = pages[instrument] ?: MarketPageFilter()

  fun updatePage(
    target: MarketInstrumentFilter?,
    transform: (MarketPageFilter) -> MarketPageFilter,
  ): MarketFilter {
    val key = target ?: instrument
    return copy(pages = pages + (key to transform(pages[key] ?: MarketPageFilter())))
  }
}

/** What one product page shows: its chips, its selection, and the markets that pass it. */
data class MarketPage(
  val favoritesOnly: Boolean = true,
  val category: String? = null,
  val categories: List<String> = emptyList(),
  val quotes: List<MarketQuote> = emptyList(),
)

/** Search spans both products; each keeps its own ranked section. */
data class MarketSearchResults(
  val perpetuals: List<MarketQuote> = emptyList(),
  val spot: List<MarketQuote> = emptyList(),
) {
  val isEmpty: Boolean
    get() = perpetuals.isEmpty() && spot.isEmpty()
}

data class MarketsUiState(
  val loading: Boolean = true,
  val query: String = "",
  val favoritesOnly: Boolean = true,
  val selectedInstrument: MarketInstrumentFilter = MarketInstrumentFilter.PERPETUALS,
  val selectedCategory: String? = null,
  val categories: List<String> = marketCategoryTabs,
  val quotes: List<MarketQuote> = emptyList(),
  val stale: Boolean = true,
  val error: String? = null,
  val assets: Map<String, xyz.mcxross.flare.data.AssetMetadata> = emptyMap(),
  val searching: Boolean = false,
  val searchResults: MarketSearchResults = MarketSearchResults(),
  val pages: Map<MarketInstrumentFilter, MarketPage> = emptyMap(),
) {
  fun page(instrument: MarketInstrumentFilter): MarketPage = pages[instrument] ?: MarketPage()
}

sealed interface MarketsIntent {
  data object OpenSearch : MarketsIntent

  data object CloseSearch : MarketsIntent

  data class Search(val value: String) : MarketsIntent

  data class ToggleFavorite(val marketAddress: String) : MarketsIntent

  /** [instrument] names the page the chip lives on; null means the page in view. */
  data class SetFavoritesOnly(
    val enabled: Boolean,
    val instrument: MarketInstrumentFilter? = null,
  ) : MarketsIntent

  data class SetCategory(
    val category: String?,
    val instrument: MarketInstrumentFilter? = null,
  ) : MarketsIntent

  data class SetInstrument(val instrument: MarketInstrumentFilter) : MarketsIntent
}

class MarketsViewModel(
  private val repository: MarketsRepository,
  private val assetCatalog: AssetCatalogRepository,
) : ViewModel() {
  private val filter = MutableStateFlow(MarketFilter())

  val uiState: StateFlow<MarketsUiState> =
    combine(
        repository.catalog,
        assetCatalog.assets,
        filter,
      ) { catalog, assets, f ->
        val pages =
          MarketInstrumentFilter.entries.associateWith { instrument ->
            val page = f.pages[instrument] ?: MarketPageFilter()
            MarketPage(
              favoritesOnly = page.favoritesOnly,
              category = page.category,
              categories =
                when (instrument) {
                  MarketInstrumentFilter.PERPETUALS -> marketCategoryTabs
                  MarketInstrumentFilter.SPOT -> spotCategoryTabs
                },
              quotes =
                catalog.quotes.filter {
                  val matchesInstrument =
                    when (instrument) {
                      MarketInstrumentFilter.PERPETUALS -> it.market.assetType == AssetType.PERP
                      MarketInstrumentFilter.SPOT -> it.market.assetType == AssetType.SPOT
                    }
                  matchesInstrument &&
                    (!page.favoritesOnly || it.favorite) &&
                    (page.category == null ||
                      (assets[assetKey(it.market.symbol)]?.kind?.normalizedCategory()
                        ?: it.market.category.normalizedCategory()) == page.category)
                },
            )
          }
        val selected = pages.getValue(f.instrument)
        MarketsUiState(
          loading = catalog.loading,
          query = f.query,
          favoritesOnly = selected.favoritesOnly,
          selectedInstrument = f.instrument,
          selectedCategory = selected.category,
          categories = selected.categories,
          quotes = selected.quotes,
          stale = catalog.stale,
          error = catalog.error,
          assets = assets,
          searching = f.searching,
          searchResults =
            if (f.searching) searchMarkets(catalog.quotes, assets, f.query) else MarketSearchResults(),
          pages = pages,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MarketsUiState(),
      )

  init {
    viewModelScope.launch { repository.refresh() }
    viewModelScope.launch { runCatching { assetCatalog.refresh() } }
    // The live stream owns freshness from here: it reconnects on its own and backfills on connect.
    viewModelScope.launch { repository.connectLive() }
  }

  fun onIntent(intent: MarketsIntent) {
    when (intent) {
      // Search is its own mode: browsing filters stay untouched so closing it returns to them.
      MarketsIntent.OpenSearch -> filter.update { it.copy(searching = true, query = "") }
      MarketsIntent.CloseSearch -> filter.update { it.copy(searching = false, query = "") }
      is MarketsIntent.Search -> filter.update { it.copy(query = intent.value) }
      is MarketsIntent.ToggleFavorite ->
        viewModelScope.launch { repository.toggleFavorite(intent.marketAddress) }
      is MarketsIntent.SetFavoritesOnly ->
        filter.update { f ->
          f.updatePage(intent.instrument) { it.copy(favoritesOnly = intent.enabled, category = null) }
        }
      is MarketsIntent.SetCategory ->
        filter.update { f ->
          f.updatePage(intent.instrument) { it.copy(category = intent.category, favoritesOnly = false) }
        }
      is MarketsIntent.SetInstrument -> filter.update { it.copy(instrument = intent.instrument) }
    }
  }
}

private fun String.normalizedCategory(): String? = trim().lowercase().takeIf { it.isNotEmpty() }

private val marketCategoryTabs = listOf("commodity", "crypto", "equity")
private val spotCategoryTabs = listOf("crypto")

/**
 * Ranks every market, perpetual and spot, against [query]: exact ticker, ticker prefix, name
 * prefix, anything containing the text, then category. An empty query lists the watchlist.
 */
fun searchMarkets(
  quotes: List<MarketQuote>,
  assets: Map<String, xyz.mcxross.flare.data.AssetMetadata>,
  query: String,
): MarketSearchResults {
  val normalized = query.trim().lowercase()
  val matches =
    if (normalized.isEmpty()) quotes.filter { it.favorite }
    else
      quotes
        .mapNotNull { quote ->
          marketMatchRank(quote, assets[assetKey(quote.market.symbol)], normalized)?.let { quote to it }
        }
        .sortedBy { it.second }
        .map { it.first }
  return MarketSearchResults(
    perpetuals = matches.filter { it.market.assetType == AssetType.PERP },
    spot = matches.filter { it.market.assetType == AssetType.SPOT },
  )
}

private fun marketMatchRank(
  quote: MarketQuote,
  asset: xyz.mcxross.flare.data.AssetMetadata?,
  query: String,
): Int? {
  val ticker = quote.market.symbol.substringBefore('/').lowercase()
  val names = listOfNotNull(quote.market.name, asset?.name).map { it.lowercase() }
  val kind = (asset?.kind ?: quote.market.category).lowercase()
  return when {
    ticker == query -> 0
    ticker.startsWith(query) -> 1
    names.any { it.startsWith(query) } -> 2
    ticker.contains(query) || names.any { it.contains(query) } -> 3
    kind.isNotBlank() && (kind.contains(query) || marketCategoryLabel(kind).lowercase().contains(query)) -> 4
    else -> null
  }
}
