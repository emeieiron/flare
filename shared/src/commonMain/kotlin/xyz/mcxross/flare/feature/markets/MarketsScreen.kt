package xyz.mcxross.flare.feature.markets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSearchField
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.MarketListRow

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
    modifier = modifier.fillMaxSize().background(FlareColors.Canvas).padding(horizontal = 20.dp)
  ) {
    FlareTopBar(
      title = "Markets",
      subtitle = if (state.stale) "Offline snapshot" else "Decibel perpetuals",
      action = {
        IconButton(onClick = { onIntent(MarketsIntent.Refresh) }) {
          Icon(Icons.Outlined.Refresh, contentDescription = "Refresh markets")
        }
      },
    )
    FlareSearchField(
      value = state.query,
      onValueChange = { onIntent(MarketsIntent.Search(it)) },
      modifier = Modifier.fillMaxWidth(),
      placeholder = "Search perpetuals",
      leadingIcon = Icons.Outlined.Search,
    )
    Row(
      modifier = Modifier.padding(vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FlareChip(
        text = "All",
        selected = !state.favoritesOnly,
        onClick = { onIntent(MarketsIntent.SetFavoritesOnly(false)) },
      )
      FlareChip(
        text = "Favorites",
        selected = state.favoritesOnly,
        onClick = { onIntent(MarketsIntent.SetFavoritesOnly(true)) },
      )
    }
    if (state.loading && state.quotes.isEmpty()) {
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
          "Connecting to Flare proxy…",
          modifier = Modifier.padding(top = 12.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else if (state.quotes.isEmpty()) {
      EmptyState(
        title = if (state.error == null) "No markets found" else "Markets unavailable",
        message =
          state.error
            ?: if (state.favoritesOnly) {
              "Favorite a market to keep it at hand."
            } else {
              "Try a different symbol or market name."
            },
        actionLabel = if (state.error != null) "Retry" else null,
        onAction = { onIntent(MarketsIntent.Refresh) },
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.stale) {
          item {
            Text(
              "Prices may be stale. Trading will remain disabled until the connection is live.",
              modifier =
                Modifier.fillMaxWidth()
                  .background(FlareColors.Surface, MaterialTheme.shapes.small)
                  .padding(12.dp),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        items(state.quotes, key = { it.market.address }) { quote ->
          MarketListRow(
            symbol = quote.market.symbol,
            name = quote.market.name,
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
