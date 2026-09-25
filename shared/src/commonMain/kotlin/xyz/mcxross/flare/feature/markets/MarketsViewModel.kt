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

internal data class MarketFilter(
  val query: String = "",
  val favoritesOnly: Boolean = true,
  val instrument: MarketInstrumentFilter = MarketInstrumentFilter.PERPETUALS,
  val category: String? = null,
  val searching: Boolean = false,
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
)

sealed interface MarketsIntent {
  data object OpenSearch : MarketsIntent

  data object CloseSearch : MarketsIntent

  data class Search(val value: String) : MarketsIntent

  data class ToggleFavorite(val marketAddress: String) : MarketsIntent

  data class SetFavoritesOnly(val enabled: Boolean) : MarketsIntent

  data class SetCategory(val category: String?) : MarketsIntent

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
        val effectiveFavoritesOnly = f.favoritesOnly
        val categories =
          when (f.instrument) {
            MarketInstrumentFilter.PERPETUALS -> marketCategoryTabs
            MarketInstrumentFilter.SPOT -> spotCategoryTabs
          }
        MarketsUiState(
          loading = catalog.loading,
          query = f.query,
          favoritesOnly = effectiveFavoritesOnly,
          selectedInstrument = f.instrument,
          selectedCategory = f.category,
          categories = categories,
          quotes =
            catalog.quotes.filter {
              val matchesInstrument =
                when (f.instrument) {
                  MarketInstrumentFilter.PERPETUALS -> it.market.assetType == AssetType.PERP
                  MarketInstrumentFilter.SPOT -> it.market.assetType == AssetType.SPOT
                }
              matchesInstrument &&
                (!effectiveFavoritesOnly || it.favorite) &&
                (f.category == null ||
                  (assets[assetKey(it.market.symbol)]?.kind?.normalizedCategory()
                    ?: it.market.category.normalizedCategory()) == f.category)
            },
          stale = catalog.stale,
          error = catalog.error,
          assets = assets,
          searching = f.searching,
          searchResults =
            if (f.searching) searchMarkets(catalog.quotes, assets, f.query) else MarketSearchResults(),
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
        filter.update { it.copy(favoritesOnly = intent.enabled, category = null, query = "") }
      is MarketsIntent.SetCategory ->
        filter.update { it.copy(category = intent.category, favoritesOnly = false, query = "") }
      is MarketsIntent.SetInstrument ->
        filter.update { current ->
          current.copy(instrument = intent.instrument, favoritesOnly = true, category = null)
        }
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
