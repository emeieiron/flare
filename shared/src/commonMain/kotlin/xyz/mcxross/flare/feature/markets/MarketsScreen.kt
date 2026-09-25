package xyz.mcxross.flare.feature.markets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.assetKey
import xyz.mcxross.flare.data.formatPercent
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSearchField
import xyz.mcxross.flare.design.FlareSegmentedControl
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.MarketListRow
import xyz.mcxross.flare.design.MarketListSkeleton
import xyz.mcxross.flare.design.resolveAssetIdentity

@Composable
fun MarketsRoute(
  onMarketClick: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MarketsViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  MarketsScreen(
    state = state,
    onIntent = viewModel::onIntent,
    onMarketClick = onMarketClick,
    modifier = modifier,
  )
}

@Composable
fun MarketsScreen(
  state: MarketsUiState,
  onIntent: (MarketsIntent) -> Unit,
  onMarketClick: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val focus = LocalFocusManager.current
  val closeSearch = {
    focus.clearFocus()
    onIntent(MarketsIntent.CloseSearch)
  }
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = state.searching,
    onBackCompleted = closeSearch,
  )
  val instruments = MarketInstrumentFilter.entries
  val pagerState = rememberPagerState(initialPage = instruments.indexOf(state.selectedInstrument)) {
    instruments.size
  }
  val scope = rememberCoroutineScope()
  val intents by rememberUpdatedState(onIntent)
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }.collect { intents(MarketsIntent.SetInstrument(instruments[it])) }
  }
  Column(modifier = modifier.fillMaxSize().background(FlareColors.Canvas)) {
    Column(Modifier.padding(horizontal = 24.dp)) {
      if (state.searching) {
        MarketSearchBar(state.query, { onIntent(MarketsIntent.Search(it)) }, closeSearch)
      } else {
        FlareTopBar(
          title = "Markets",
          action = {
            IconButton({ onIntent(MarketsIntent.OpenSearch) }, Modifier.size(48.dp)) {
              Icon(Icons.Outlined.Search, "Search markets", tint = FlareColors.TextPrimary)
            }
          },
        )
      }
      AnimatedVisibility(
        !state.searching,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
      ) {
        FlareSegmentedControl(
          options = instruments,
          selectedOption = instruments[pagerState.currentPage],
          onOptionSelected = { scope.launch { pagerState.animateScrollToPage(instruments.indexOf(it)) } },
          label = {
            when (it) {
              MarketInstrumentFilter.PERPETUALS -> "Perpetuals"
              MarketInstrumentFilter.SPOT -> "Spot"
            }
          },
          indicatorPosition = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
        )
      }
    }
    if (state.searching) {
      Column(Modifier.padding(horizontal = 24.dp)) { MarketSearchResults(state, onIntent, onMarketClick) }
    } else {
      HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { instruments[it] },
      ) { index ->
        val instrument = instruments[index]
        MarketPageContent(state, state.page(instrument), instrument, onIntent, onMarketClick)
      }
    }
  }
}

/** The title row becomes the field; Cancel returns to exactly where browsing left off. */
@Composable
private fun MarketSearchBar(query: String, onQuery: (String) -> Unit, onCancel: () -> Unit) {
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  Row(
    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp).heightIn(min = 52.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FlareSearchField(
      value = query,
      onValueChange = onQuery,
      placeholder = "Search all markets",
      leadingIcon = Icons.Outlined.Search,
      modifier = Modifier.weight(1f),
      focusRequester = focusRequester,
    )
    TextButton(onCancel) { Text("Cancel", color = FlareColors.Positive) }
  }
}

/** One product: its chips and its list, with its own selection and scroll position. */
@Composable
private fun MarketPageContent(
  state: MarketsUiState,
  page: MarketPage,
  instrument: MarketInstrumentFilter,
  onIntent: (MarketsIntent) -> Unit,
  onMarketClick: (String) -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    MarketFilterChips(page, instrument, onIntent)
    val title = marketSectionTitle(page.favoritesOnly, page.category)
    if (state.loading && page.quotes.isEmpty()) {
      Column(Modifier.padding(horizontal = 24.dp)) {
        MarketSectionHeader(title, showPriceLabel = true)
        MarketListSkeleton()
      }
    } else if (page.quotes.isEmpty()) {
      Column(Modifier.padding(horizontal = 24.dp)) {
        EmptyState(
          title =
            when {
              state.error != null -> "Markets are offline"
              page.favoritesOnly -> "Your watchlist starts here"
              else -> "No markets found"
            },
          message =
            if (state.error != null) "Prices appear as soon as market data arrives."
            else if (page.favoritesOnly) "Tap the star beside a market to follow it here."
            else "Try another category.",
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp),
      ) {
        item { MarketSectionHeader(title, showPriceLabel = true) }
        items(page.quotes, key = { it.market.address }) { quote ->
          MarketQuoteRow(quote, state, onIntent, onMarketClick)
        }
      }
    }
  }
}

@Composable
private fun MarketFilterChips(
  page: MarketPage,
  instrument: MarketInstrumentFilter,
  onIntent: (MarketsIntent) -> Unit,
) {
  LazyRow(
    modifier = Modifier.padding(vertical = 12.dp),
    contentPadding = PaddingValues(horizontal = 24.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      FlareChip("All", !page.favoritesOnly && page.category == null,
        { onIntent(MarketsIntent.SetCategory(null, instrument)) })
    }
    item {
      FlareChip(
        text = "Watchlist",
        selected = page.favoritesOnly,
        onClick = { onIntent(MarketsIntent.SetFavoritesOnly(true, instrument)) },
      )
    }
    items(page.categories, key = { it }) { category ->
      val isCategorySelected = !page.favoritesOnly && page.category == category
      FlareChip(
        text = marketCategoryLabel(category),
        selected = isCategorySelected,
        onClick = {
          onIntent(MarketsIntent.SetCategory(if (isCategorySelected) null else category, instrument))
        },
      )
    }
  }
}

/** Perpetuals then spot, each ranked; an empty query shows the watchlist across both. */
@Composable
private fun MarketSearchResults(
  state: MarketsUiState,
  onIntent: (MarketsIntent) -> Unit,
  onMarketClick: (String) -> Unit,
) {
  val results = state.searchResults
  val listState = rememberLazyListState()
  val keyboard = LocalSoftwareKeyboardController.current
  LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) keyboard?.hide() }
  val query = state.query.trim()
  if (results.isEmpty) {
    if (state.loading && state.quotes.isEmpty()) {
      MarketListSkeleton()
    } else {
      EmptyState(
        title = if (query.isEmpty()) "Search every market" else "No markets match “$query”",
        message = if (query.isEmpty()) "Type a ticker, name, or category. Starred markets appear here."
          else "Try a ticker, asset name, or category.",
      )
    }
    return
  }
  LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
    val firstSection = if (results.perpetuals.isNotEmpty()) "Perpetuals" else "Spot"
    if (query.isEmpty()) item { MarketSectionHeader("Your watchlist", showPriceLabel = true) }
    listOf("Perpetuals" to results.perpetuals, "Spot" to results.spot).forEach { (title, quotes) ->
      if (quotes.isEmpty()) return@forEach
      item(key = "section-$title") {
        MarketSectionHeader(
          title,
          showPriceLabel = query.isNotEmpty() && title == firstSection,
          subdued = query.isEmpty(),
        )
      }
      items(quotes, key = { "search-${it.market.address}" }) { quote ->
        MarketQuoteRow(quote, state, onIntent, onMarketClick)
      }
    }
  }
}

@Composable
private fun MarketSectionHeader(title: String, showPriceLabel: Boolean, subdued: Boolean = false) {
  Row(
    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Bottom,
  ) {
    Text(
      title,
      style = if (subdued) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
      color = if (subdued) FlareColors.TextSecondary else FlareColors.TextPrimary,
    )
    if (showPriceLabel) {
      Text("Price / 24h", style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
    }
  }
}

@Composable
private fun MarketQuoteRow(
  quote: MarketQuote,
  state: MarketsUiState,
  onIntent: (MarketsIntent) -> Unit,
  onMarketClick: (String) -> Unit,
) {
  MarketListRow(
    asset =
      resolveAssetIdentity(
        quote.market.symbol,
        quote.market.name,
        state.assets[assetKey(quote.market.symbol)],
      ),
    price = formatPrice(quote.markPrice),
    delta = formatPercent(quote.changePercent24h),
    positive = quote.changePercent24h >= 0,
    favorite = quote.favorite,
    onClick = { onMarketClick(quote.market.address) },
    onFavorite = { onIntent(MarketsIntent.ToggleFavorite(quote.market.address)) },
    badgeText = if (quote.market.assetType == AssetType.SPOT) "SPOT" else null,
  )
}

internal fun marketCategoryLabel(category: String): String =
  when (category.trim().lowercase()) {
    "equity" -> "Equities"
    else -> category.trim().replaceFirstChar(Char::titlecase)
  }

internal fun marketSectionTitle(state: MarketsUiState): String =
  marketSectionTitle(state.favoritesOnly, state.selectedCategory)

internal fun marketSectionTitle(favoritesOnly: Boolean, category: String?): String =
  when {
    favoritesOnly -> "Your watchlist"
    category != null -> marketCategoryLabel(category)
    else -> "All markets"
  }
