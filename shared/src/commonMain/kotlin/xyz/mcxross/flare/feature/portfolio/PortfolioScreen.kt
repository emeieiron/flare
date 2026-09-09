package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareTopBar

@Composable
fun PortfolioRoute(
  onOpenSetup: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PortfolioViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  PortfolioScreen(state, viewModel::onIntent, onOpenSetup, modifier)
}

@Composable
fun PortfolioScreen(
  state: PortfolioUiState,
  onIntent: (PortfolioIntent) -> Unit,
  onOpenSetup: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val overview = state.account.overview
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(FlareColors.Canvas)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
  ) {
    FlareTopBar(
      "Portfolio",
      subtitle =
        when {
          state.isLive -> "Your account at a glance"
          state.profile.apiOnly -> "Trading account · locked"
          state.profile.ownerAddress != null -> "Unlock to see your balance"
          else -> "Your trading, in one place"
        },
    )
    if (state.profile.ownerAddress == null && state.profile.apiWalletAddress == null) {
      Spacer(Modifier.height(48.dp))
      Text("Room for your\nnext move.", style = MaterialTheme.typography.displaySmall)
      Text(
        "Connect your account to see your balance, positions, and buying power.",
        Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = FlareColors.TextSecondary,
      )
      FlareButton(
        "Create or import account",
        onOpenSetup,
        Modifier.fillMaxWidth().padding(top = 32.dp),
      )
      return@Column
    }
    Text(
      if (state.account.stale && overview != null) "Last known balance" else "Total balance",
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
    Text(
      overview?.equityBalance?.let(::formatBalance) ?: "—",
      Modifier.padding(top = 8.dp),
      style = MaterialTheme.typography.displayMedium,
    )
    if (overview != null) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        PortfolioMetric(
          "Unrealized P&L",
          formatBalance(overview.unrealizedPnl),
          Modifier.weight(1f),
        )
        PortfolioMetric("Available", formatBalance(overview.availableToTrade), Modifier.weight(1f))
        PortfolioMetric(
          "Margin ratio",
          "${(overview.crossMarginRatio * 100).toInt()}%",
          Modifier.weight(1f),
        )
      }
    }
    if (state.profile.ownerAddress != null)
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FlareButton(
          "Deposit",
          { onIntent(PortfolioIntent.OpenFunding(FundingMode.DEPOSIT)) },
          Modifier.weight(1f),
        )
        FlareButton(
          "Withdraw",
          { onIntent(PortfolioIntent.OpenFunding(FundingMode.WITHDRAW)) },
          Modifier.weight(1f),
          style = FlareButtonStyle.OUTLINE,
        )
      }
    if (
      !state.isLive &&
        (state.profile.ownerAddress != null || state.profile.apiWalletAddress != null)
    ) {
      FlareButton(
        text = if (state.busy) "Unlocking…" else "Unlock account",
        onClick = { onIntent(PortfolioIntent.Unlock) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    state.actionError?.let { error ->
      Text(error, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
    }
    if (state.pendingTransactions.isNotEmpty()) {
      Text(
        "${state.pendingTransactions.size} transaction${if (state.pendingTransactions.size == 1) "" else "s"} pending confirmation",
        modifier = Modifier.padding(top = 12.dp),
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelMedium,
      )
      state.pendingTransactions.forEach { pending ->
        Text(
          "${pending.operation.replace('_', ' ').lowercase()} · ${pending.state.lowercase()} · " +
            pending.hash.take(18) +
            "…",
          modifier = Modifier.padding(top = 4.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      }
      FlareButton(
        "Check status",
        { onIntent(PortfolioIntent.Refresh) },
        Modifier.fillMaxWidth().padding(top = 10.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    Spacer(Modifier.height(32.dp))
    Text("Your positions", style = MaterialTheme.typography.titleLarge)
    HorizontalDivider(Modifier.padding(top = 12.dp), color = FlareColors.BorderSubtle)
    when {
      state.account.positions.isNotEmpty() ->
        state.account.positions.forEach { position ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                (state.marketSymbols[position.market] ?: shortAddress(position.market)),
                style = MaterialTheme.typography.labelMedium,
              )
              Text(
                "${if (position.isLong) "Long" else "Short"} · ${position.leverage}×",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
              )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
              Text(position.size, style = MaterialTheme.typography.labelMedium)
              Text(
                "Entry ${formatPrice(position.entryPrice)}",
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
          FlareButton(
            text =
              if (state.managedPositionMarket == position.market) "Managing position"
              else "Manage position",
            onClick = { onIntent(PortfolioIntent.ManagePosition(position.market)) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            enabled = !state.busy,
            style = FlareButtonStyle.OUTLINE,
          )
          HorizontalDivider(color = FlareColors.BorderSubtle)
        }
      state.isLive ->
        EmptyState(
          title = "No open positions",
          message = "Positions will appear here after an order fills.",
        )
      else ->
        EmptyState(
          title = "Your account is locked",
          message = "Unlock your account to see current positions.",
        )
    }
  }
  if (state.fundingMode != null) FundingSheet(state, onIntent)
  if (state.managedPositionMarket != null) PositionSheet(state, onIntent)
}

@Composable
private fun PortfolioMetric(label: String, value: String, modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(
      label,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
    Text(value, style = MaterialTheme.typography.labelMedium)
  }
}
