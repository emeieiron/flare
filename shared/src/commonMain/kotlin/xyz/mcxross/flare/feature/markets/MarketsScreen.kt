package xyz.mcxross.flare.feature.markets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatPercent
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSearchField
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.MarketListRow
import xyz.mcxross.flare.design.resolveAssetIdentity
import xyz.mcxross.flare.data.assetKey

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
  Column(
    modifier = modifier.fillMaxSize().background(FlareColors.Canvas).padding(horizontal = 24.dp)
  ) {
    FlareTopBar(
      title = "Markets",
      subtitle = if (state.stale && state.quotes.isNotEmpty()) "Reconnecting…" else null,
    )
    FlareSearchField(
      value = state.query,
      onValueChange = { onIntent(MarketsIntent.Search(it)) },
      modifier = Modifier.fillMaxWidth(),
      placeholder = "Search markets",
      leadingIcon = Icons.Outlined.Search,
    )
    LazyRow(
      modifier = Modifier.padding(vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        FlareChip(
          text = "All",
          selected = !state.favoritesOnly && state.selectedCategory == null,
          onClick = {
            onIntent(MarketsIntent.SetFavoritesOnly(false))
            onIntent(MarketsIntent.SetCategory(null))
          },
        )
      }
      item {
        FlareChip(
          text = "Watchlist",
          selected = state.favoritesOnly,
          onClick = {
            onIntent(MarketsIntent.SetFavoritesOnly(true))
            onIntent(MarketsIntent.SetCategory(null))
          },
        )
      }
      items(state.categories, key = { it }) { category ->
        FlareChip(
          text = marketCategoryLabel(category),
          selected = !state.favoritesOnly && state.selectedCategory == category,
          onClick = {
            onIntent(MarketsIntent.SetFavoritesOnly(false))
            onIntent(MarketsIntent.SetCategory(category))
          },
        )
      }
    }
    if (state.loading && state.quotes.isEmpty()) {
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
          "Loading markets…",
          modifier = Modifier.padding(top = 12.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else if (state.quotes.isEmpty()) {
      EmptyState(
        title =
          when {
            state.error != null -> "Markets are offline"
            state.query.isNotBlank() -> "No matching markets"
            state.favoritesOnly -> "Your watchlist starts here"
            else -> "No markets found"
          },
        message =
          if (state.error != null) "Flare is reconnecting. Prices appear as soon as they arrive."
          else if (state.favoritesOnly && state.query.isBlank()) {
            "Tap the star beside a market to follow it here."
          } else {
            "Try a different symbol or market name."
          },
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
          Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              when {
                state.query.isNotBlank() -> "Search results"
                state.favoritesOnly -> "Your watchlist"
                else -> "All markets"
              },
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              "Price / 24h",
              style = MaterialTheme.typography.bodySmall,
              color = FlareColors.TextSecondary,
            )
          }
        }
        if (state.stale) {
          item {
            ActionNotice(
              "Reconnecting to live prices. Trading resumes automatically.",
              tone = NoticeTone.PROGRESS,
            )
          }
        }
        items(state.quotes, key = { it.market.address }) { quote ->
          MarketListRow(
            asset = resolveAssetIdentity(
              quote.market.symbol,
              quote.market.name,
              state.assets[assetKey(quote.market.symbol)],
            ),
            price = formatPrice(quote.markPrice),
            delta = formatPercent(quote.changePercent24h),
            positive = quote.changePercent24h >= 0,
            favorite = quote.favorite,
            onClick = { onMarketClick(quote.market.address) },
            onFavorite = {
              onIntent(MarketsIntent.ToggleFavorite(quote.market.address))
            },
          )
        }
      }
    }
  }
}

internal fun marketCategoryLabel(category: String): String =
  when (category.trim().lowercase()) {
    "equity" -> "Equities"
    else -> category.trim().replaceFirstChar(Char::titlecase)
  }
