package xyz.mcxross.flare.feature.markets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.AssetMetadata
import xyz.mcxross.flare.data.MarketCatalog
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Market

class MarketsViewModelTest {

  private fun createMarket(
    name: String,
    address: String,
    assetType: AssetType,
    category: String,
  ): Market =
    Market(
      assetType = assetType,
      address = address,
      name = name,
      sizeDecimals = 2,
      maxLeverage = 20,
      tickSize = 1uL,
      minSize = 1uL,
      lotSize = 1000uL,
      maxOpenInterest = 1_000_000.0,
      priceDecimals = 2,
      mode = "Open",
      unrealizedPnlHaircutBps = 0,
      category = category,
      minPrice = 1uL,
      maxPrice = 100_000_000uL,
      isIsolatedOnly = false,
    )

  private val btcPerp = createMarket("BTC/USD", "0xbtc_perp", AssetType.PERP, "crypto")
  private val adaPerp = createMarket("ADA/USD", "0xada_perp", AssetType.PERP, "crypto")
  private val goldPerp = createMarket("GOLD/USD", "0xgold_perp", AssetType.PERP, "commodity")
  private val aptSpot = createMarket("APT/USDC", "0xapt_spot", AssetType.SPOT, "crypto")
  private val usdcSpot = createMarket("USDC/USDT", "0xusdc_spot", AssetType.SPOT, "crypto")

  private val quotes =
    listOf(
      MarketQuote(market = btcPerp, markPrice = 70_000.0, changePercent24h = 0.0, volume24h = 0.0, openInterest = 0.0, favorite = true),
      MarketQuote(market = adaPerp, markPrice = 0.30, changePercent24h = 0.0, volume24h = 0.0, openInterest = 0.0, favorite = false),
      MarketQuote(market = goldPerp, markPrice = 2_600.0, changePercent24h = 0.0, volume24h = 0.0, openInterest = 0.0, favorite = true),
      MarketQuote(market = aptSpot, markPrice = 10.0, changePercent24h = 0.0, volume24h = 0.0, openInterest = 0.0, favorite = true),
      MarketQuote(market = usdcSpot, markPrice = 1.0, changePercent24h = 0.0, volume24h = 0.0, openInterest = 0.0, favorite = false),
    )

  private val assetMetadataMap =
    mapOf(
      "BTC" to AssetMetadata(symbol = "BTC", name = "Bitcoin", kind = "crypto", iconUrl = null, sha256 = null),
      "ADA" to AssetMetadata(symbol = "ADA", name = "Cardano", kind = "crypto", iconUrl = null, sha256 = null),
      "GOLD" to AssetMetadata(symbol = "GOLD", name = "Gold", kind = "commodity", iconUrl = null, sha256 = null),
      "APT" to AssetMetadata(symbol = "APT", name = "Aptos", kind = "crypto", iconUrl = null, sha256 = null),
      "USDC" to AssetMetadata(symbol = "USDC", name = "USDC", kind = "crypto", iconUrl = null, sha256 = null),
    )

  private class FakeMarketsRepository(initialQuotes: List<MarketQuote>) : MarketsRepository {
    override val catalog: StateFlow<MarketCatalog> =
      MutableStateFlow(MarketCatalog(loading = false, quotes = initialQuotes, stale = false))

    override suspend fun refresh() {}
    override suspend fun connectLive() {}
    override suspend fun toggleFavorite(marketAddress: String) {}
  }

  private class FakeAssetCatalogRepository(assetsMap: Map<String, AssetMetadata>) : AssetCatalogRepository {
    override val assets: StateFlow<Map<String, AssetMetadata>> = MutableStateFlow(assetsMap)
    override suspend fun refresh() {}
  }

  @Test
  fun perpetualsTabDefaultsToWatchlistAndNeverShowsAllPerpsWithoutCategory() = runTest {
    val vm = MarketsViewModel(FakeMarketsRepository(quotes), FakeAssetCatalogRepository(assetMetadataMap))
    val state = vm.uiState.first { it.quotes.isNotEmpty() }

    assertEquals(MarketInstrumentFilter.PERPETUALS, state.selectedInstrument)
    assertTrue(state.favoritesOnly)
    assertEquals(null, state.selectedCategory)
    assertEquals("Your watchlist", marketSectionTitle(state))

    // Only favorited perpetuals should be present (BTC and GOLD), not ADA or spot
    val symbols = state.quotes.map { it.market.symbol }
    assertEquals(listOf("BTC", "GOLD"), symbols)
  }

  @Test
  fun categoryFilteringAndTitle() = runTest {
    val vm = MarketsViewModel(FakeMarketsRepository(quotes), FakeAssetCatalogRepository(assetMetadataMap))
    vm.uiState.first { it.quotes.isNotEmpty() }

    vm.onIntent(MarketsIntent.SetFavoritesOnly(false))
    vm.onIntent(MarketsIntent.SetCategory("crypto"))

    val state = vm.uiState.first { it.selectedCategory == "crypto" }
    assertFalse(state.favoritesOnly)
    assertEquals("crypto", state.selectedCategory)
    assertEquals("Crypto", marketSectionTitle(state))

    // Both BTC and ADA are crypto perps
    val symbols = state.quotes.map { it.market.symbol }
    assertEquals(listOf("BTC", "ADA"), symbols)
  }

  @Test
  fun switchingFromSpotBackToPerpetualsResetsToWatchlist() = runTest {
    val vm = MarketsViewModel(FakeMarketsRepository(quotes), FakeAssetCatalogRepository(assetMetadataMap))
    vm.uiState.first { it.quotes.isNotEmpty() }

    // Switch to Spot
    vm.onIntent(MarketsIntent.SetInstrument(MarketInstrumentFilter.SPOT))
    val spotState = vm.uiState.first { it.selectedInstrument == MarketInstrumentFilter.SPOT }
    assertEquals(MarketInstrumentFilter.SPOT, spotState.selectedInstrument)

    // Switch back to Perpetuals
    vm.onIntent(MarketsIntent.SetInstrument(MarketInstrumentFilter.PERPETUALS))
    val perpState = vm.uiState.first { it.selectedInstrument == MarketInstrumentFilter.PERPETUALS }

    assertTrue(perpState.favoritesOnly)
    assertEquals(null, perpState.selectedCategory)
    assertEquals("Your watchlist", marketSectionTitle(perpState))
    assertEquals(listOf("BTC", "GOLD"), perpState.quotes.map { it.market.symbol })
  }

  @Test
  fun perpetualsCannotHaveFavoritesOnlyFalseWhenCategoryIsNull() = runTest {
    val vm = MarketsViewModel(FakeMarketsRepository(quotes), FakeAssetCatalogRepository(assetMetadataMap))
    vm.uiState.first { it.quotes.isNotEmpty() }

    // Attempt to set favoritesOnly = false without category on Perpetuals
    vm.onIntent(MarketsIntent.SetFavoritesOnly(false))
    val state = vm.uiState.value

    assertTrue(state.favoritesOnly)
    assertEquals(null, state.selectedCategory)
    assertEquals("Your watchlist", marketSectionTitle(state))
    assertEquals(listOf("BTC", "GOLD"), state.quotes.map { it.market.symbol })
  }

  @Test
  fun searchReturnsMatchingMarketsAcrossEntireInstrument() = runTest {
    val vm = MarketsViewModel(FakeMarketsRepository(quotes), FakeAssetCatalogRepository(assetMetadataMap))
    vm.uiState.first { it.quotes.isNotEmpty() }

    // Search for non-favorite "ADA"
    vm.onIntent(MarketsIntent.Search("ADA"))
    val state = vm.uiState.first { it.query == "ADA" }

    assertEquals("Search results", marketSectionTitle(state))
    assertEquals(listOf("ADA"), state.quotes.map { it.market.symbol })
  }

  @Test
  fun spotTabDefaultsToWatchlistWithSeededFavorite() = runTest {
    val vm = MarketsViewModel(FakeMarketsRepository(quotes), FakeAssetCatalogRepository(assetMetadataMap))
    vm.uiState.first { it.quotes.isNotEmpty() }

    vm.onIntent(MarketsIntent.SetInstrument(MarketInstrumentFilter.SPOT))
    val state = vm.uiState.first { it.selectedInstrument == MarketInstrumentFilter.SPOT }

    assertTrue(state.favoritesOnly)
    assertEquals(null, state.selectedCategory)
    assertEquals("Your watchlist", marketSectionTitle(state))
    assertEquals(listOf("APT"), state.quotes.map { it.market.symbol })
  }

  @Test
  fun spotCategoryFilteringAndToggleBackToWatchlist() = runTest {
    val vm = MarketsViewModel(FakeMarketsRepository(quotes), FakeAssetCatalogRepository(assetMetadataMap))
    vm.uiState.first { it.quotes.isNotEmpty() }

    vm.onIntent(MarketsIntent.SetInstrument(MarketInstrumentFilter.SPOT))
    vm.onIntent(MarketsIntent.SetFavoritesOnly(false))
    vm.onIntent(MarketsIntent.SetCategory("crypto"))

    val state = vm.uiState.first { it.selectedCategory == "crypto" && it.selectedInstrument == MarketInstrumentFilter.SPOT }
    assertFalse(state.favoritesOnly)
    assertEquals("crypto", state.selectedCategory)
    assertEquals("Crypto", marketSectionTitle(state))
    assertEquals(listOf("APT", "USDC"), state.quotes.map { it.market.symbol })

    // Deselect category back to Watchlist
    vm.onIntent(MarketsIntent.SetCategory(null))
    val watchlistState = vm.uiState.first { it.selectedCategory == null && it.selectedInstrument == MarketInstrumentFilter.SPOT }
    assertTrue(watchlistState.favoritesOnly)
    assertEquals("Your watchlist", marketSectionTitle(watchlistState))
    assertEquals(listOf("APT"), watchlistState.quotes.map { it.market.symbol })
  }

  @Test
  fun spotCannotHaveFavoritesOnlyFalseWhenCategoryIsNull() = runTest {
    val vm = MarketsViewModel(FakeMarketsRepository(quotes), FakeAssetCatalogRepository(assetMetadataMap))
    vm.uiState.first { it.quotes.isNotEmpty() }

    vm.onIntent(MarketsIntent.SetInstrument(MarketInstrumentFilter.SPOT))
    val state = vm.uiState.first { it.selectedInstrument == MarketInstrumentFilter.SPOT }

    vm.onIntent(MarketsIntent.SetFavoritesOnly(false))
    val currentState = vm.uiState.value

    assertTrue(currentState.favoritesOnly)
    assertEquals(null, currentState.selectedCategory)
    assertEquals("Your watchlist", marketSectionTitle(currentState))
    assertEquals(listOf("APT"), currentState.quotes.map { it.market.symbol })
  }
}
