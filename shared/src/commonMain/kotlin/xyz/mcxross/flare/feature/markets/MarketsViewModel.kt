package xyz.mcxross.flare.feature.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.assetKey
import xyz.mcxross.flare.data.MarketsRepository

data class MarketsUiState(
  val loading: Boolean = true,
  val query: String = "",
  val favoritesOnly: Boolean = true,
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
}

class MarketsViewModel(
  private val repository: MarketsRepository,
  private val assetCatalog: AssetCatalogRepository,
) : ViewModel() {
  private val query = MutableStateFlow("")
  private val favoritesOnly = MutableStateFlow(true)
  private val selectedCategory = MutableStateFlow<String?>(null)

  val uiState: StateFlow<MarketsUiState> =
    combine(repository.catalog, assetCatalog.assets, query, favoritesOnly, selectedCategory) {
        catalog,
        assets,
        search,
        onlyFavorites,
        category ->
        val normalized = search.trim().lowercase()
        MarketsUiState(
          loading = catalog.loading,
          query = search,
          favoritesOnly = onlyFavorites,
          selectedCategory = category,
          quotes =
            catalog.quotes.filter {
              (!onlyFavorites || it.favorite) &&
                (category == null ||
                  assets[assetKey(it.market.symbol)]?.kind?.normalizedCategory() == category) &&
                (normalized.isEmpty() ||
                  it.market.symbol.lowercase().contains(normalized) ||
                  it.market.name.lowercase().contains(normalized) ||
                  assets[assetKey(it.market.symbol)]?.let { asset ->
                    asset.name.lowercase().contains(normalized) || asset.kind.lowercase().contains(normalized)
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
      is MarketsIntent.Search -> query.value = intent.value
      is MarketsIntent.ToggleFavorite ->
        viewModelScope.launch { repository.toggleFavorite(intent.marketAddress) }
      is MarketsIntent.SetFavoritesOnly -> favoritesOnly.value = intent.enabled
      is MarketsIntent.SetCategory -> selectedCategory.value = intent.category
    }
  }
}

private fun String.normalizedCategory(): String? = trim().lowercase().takeIf { it.isNotEmpty() }

private val marketCategoryTabs = listOf("commodity", "crypto", "equity")
