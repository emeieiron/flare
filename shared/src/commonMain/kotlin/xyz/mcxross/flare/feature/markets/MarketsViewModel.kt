package xyz.mcxross.flare.feature.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.assetKey
import xyz.mcxross.flare.data.MarketsRepository
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
)

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
)

sealed interface MarketsIntent {
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
      val normalized = f.query.trim().lowercase()
      val categories =
        when (f.instrument) {
          MarketInstrumentFilter.PERPETUALS -> marketCategoryTabs
          MarketInstrumentFilter.SPOT -> spotCategoryTabs
        }
      MarketsUiState(
        loading = catalog.loading,
        query = f.query,
        favoritesOnly = f.favoritesOnly,
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
              (!f.favoritesOnly || it.favorite) &&
              (f.category == null ||
                assets[assetKey(it.market.symbol)]?.kind?.normalizedCategory() == f.category) &&
              (normalized.isEmpty() ||
                it.market.symbol.lowercase().contains(normalized) ||
                it.market.name.lowercase().contains(normalized) ||
                assets[assetKey(it.market.symbol)]?.let { asset ->
                  asset.name.lowercase().contains(normalized) ||
                    asset.kind.lowercase().contains(normalized)
                } == true)
          },
        stale = catalog.stale,
        error = catalog.error,
        assets = assets,
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
      is MarketsIntent.Search -> filter.update { it.copy(query = intent.value) }
      is MarketsIntent.ToggleFavorite ->
        viewModelScope.launch { repository.toggleFavorite(intent.marketAddress) }
      is MarketsIntent.SetFavoritesOnly -> filter.update { it.copy(favoritesOnly = intent.enabled) }
      is MarketsIntent.SetCategory -> filter.update { it.copy(category = intent.category) }
      is MarketsIntent.SetInstrument ->
        filter.update { it.copy(instrument = intent.instrument, category = null) }
    }
  }
}

private fun String.normalizedCategory(): String? = trim().lowercase().takeIf { it.isNotEmpty() }

private val marketCategoryTabs = listOf("commodity", "crypto", "equity")
private val spotCategoryTabs = listOf("crypto")
