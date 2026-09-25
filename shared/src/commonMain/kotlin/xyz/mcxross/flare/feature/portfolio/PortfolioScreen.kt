package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.valentinilk.shimmer.shimmer
import kotlin.math.abs
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.formatSignedBalance
import xyz.mcxross.flare.decibel.model.Position
import xyz.mcxross.flare.decibel.model.VaultInfo
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.design.ActionRow
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareSkeletonBox
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.InstrumentBadge
import xyz.mcxross.flare.design.rememberFlareShimmer
import xyz.mcxross.flare.design.settlingActionName
import xyz.mcxross.flare.design.shortAddress

@Composable
fun PortfolioRoute(
  onOpenSetup: () -> Unit,
  onMarketClick: (String) -> Unit = {},
  modifier: Modifier = Modifier,
  viewModel: PortfolioViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(lifecycleOwner, viewModel) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.refreshWhileVisible() }
  }
  PortfolioScreen(state, viewModel::onIntent, onOpenSetup, onMarketClick, modifier)
}

private enum class PortfolioPage(val title: String) {
  OVERVIEW("Portfolio"), POSITIONS("Positions"), HOLDINGS("Holdings"),
  VAULTS("Your vaults"), EXPLORE("Explore vaults"), HISTORY("Performance"), REWARDS("Rewards"),
}

@Composable
fun PortfolioScreen(
  state: PortfolioUiState,
  onIntent: (PortfolioIntent) -> Unit,
  onOpenSetup: () -> Unit,
  onMarketClick: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var page by rememberSaveable { mutableStateOf(PortfolioPage.OVERVIEW) }
  var showBalance by rememberSaveable { mutableStateOf(false) }
  var inspectedVault by remember { mutableStateOf<VaultInfo?>(null) }
  val overview = state.account.overview
  val back = { page = if (page == PortfolioPage.EXPLORE) PortfolioPage.VAULTS else PortfolioPage.OVERVIEW }
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = page != PortfolioPage.OVERVIEW,
    onBackCompleted = back,
  )
  LaunchedEffect(page) {
    onIntent(PortfolioIntent.SetHistoryVisible(page == PortfolioPage.HISTORY))
    if (page == PortfolioPage.VAULTS) onIntent(PortfolioIntent.RefreshVaults)
  }
  Column(modifier.fillMaxSize().background(FlareColors.Canvas)) {
    if (page != PortfolioPage.OVERVIEW) BackBar(page.title, back)
    key(page) {
      Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        if (page == PortfolioPage.OVERVIEW) FlareTopBar("Portfolio")
        if (state.profile.ownerAddress == null && state.profile.apiWalletAddress == null) {
          Text("Your portfolio starts here", style = MaterialTheme.typography.headlineMedium)
          Text("Connect a wallet to view your assets and positions.",
            Modifier.padding(top = 12.dp), color = FlareColors.TextSecondary)
          FlareButton("Create or import wallet", onOpenSetup, Modifier.fillMaxWidth().padding(top = 24.dp))
          return@Column
        }
        when (page) {
          PortfolioPage.OVERVIEW -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically) {
              Text("Account value", style = MaterialTheme.typography.bodyMedium, color = FlareColors.TextSecondary)
              TextButton({ showBalance = true }) { Text("Details") }
            }
            if (overview == null && state.account.error == null) {
              PortfolioShimmer(Modifier.padding(vertical = 8.dp), balance = true)
            } else {
              Text(if (overview != null || state.spotHoldings.isNotEmpty()) formatBalance(state.totalBalance) else "—",
                style = MaterialTheme.typography.displayMedium)
            }
            Text(
              when {
                overview == null && state.account.error != null -> "Account unavailable. Reconnecting automatically."
                overview == null -> "Loading your account"
                state.account.stale -> "Updating · showing your last balance"
                state.balanceIncomplete -> "Partial balance · updating"
                else -> ""
              }, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
              color = FlareColors.TextTertiary,
            )
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
              PortfolioMetric("Available to trade", overview?.availableToTrade?.let(::formatBalance) ?: "—", Modifier.weight(1f))
              PortfolioMetric("Unrealized P&L", overview?.unrealizedPnl?.let(::formatSignedBalance) ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              if (state.profile.ownerAddress != null) FlareButton("Transfer",
                { onIntent(PortfolioIntent.OpenFunding(FundingMode.DEPOSIT)) }, Modifier.weight(1f))
              FlareButton("Performance", { page = PortfolioPage.HISTORY }, Modifier.weight(1f), style = FlareButtonStyle.OUTLINE)
            }
            settlingNotice(state.pendingTransactions)?.let {
              Text(it, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
            }
            PortfolioSectionHeader("Positions", state.account.positions.size > 3) { page = PortfolioPage.POSITIONS }
            PositionsContent(state, onIntent, limit = 3)
            PortfolioSectionHeader("Holdings", state.spotHoldings.size > 3) { page = PortfolioPage.HOLDINGS }
            HoldingsContent(state, onMarketClick, limit = 3)
            Spacer(Modifier.height(20.dp))
            ActionRow("Vaults", subtitle = when {
              state.accountVaults.isNotEmpty() -> "${formatBalance(state.accountVaults.sumOf { it.currentValue })} invested"
              !state.vaultsLoaded -> "Explore vaults"
              else -> "Explore vaults and manage deposits"
            }, onClick = { page = PortfolioPage.VAULTS })
            if (state.streak != null || state.amps != null || state.tier != null) {
              ActionRow("Rewards", subtitle = state.amps?.let { "${formatQuantity(it.totalAmps, 0)} Amps" },
                onClick = { page = PortfolioPage.REWARDS })
            }
          }
          PortfolioPage.POSITIONS -> PositionsContent(state, onIntent)
          PortfolioPage.HOLDINGS -> HoldingsContent(state, onMarketClick)
          PortfolioPage.HISTORY -> PortfolioPerformanceChart(
            points = state.chartPoints, range = state.chartRange, metric = state.chartMetric,
            loading = state.chartLoading || (!state.chartLoaded && state.chartError == null), error = state.chartError,
            onRangeSelect = { onIntent(PortfolioIntent.SelectChartRange(it)) },
            onMetricSelect = { onIntent(PortfolioIntent.SelectChartMetric(it)) },
          )
          PortfolioPage.VAULTS -> {
            if (state.accountVaults.isNotEmpty()) {
              Text("Invested in vaults", color = FlareColors.TextSecondary)
              Text(formatBalance(state.accountVaults.sumOf { it.currentValue }),
                Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.displaySmall)
              state.accountVaults.forEach { position ->
                VaultSummaryRow(position.vault.name, formatBalance(position.currentValue),
                  "${formatQuantity(position.currentNumShares, 4)} shares") { inspectedVault = position.vault }
              }
              if (state.vaultsError != null) QuietPortfolioMessage("Updating vaults · showing your last balances")
            } else if (!state.vaultsLoaded && state.vaultsError == null) PortfolioShimmer()
            else QuietPortfolioMessage(if (state.vaultsError != null) "Vaults unavailable. Reconnecting automatically." else "No vault deposits yet")
            ActionRow("Explore vaults", onClick = { page = PortfolioPage.EXPLORE })
          }
          PortfolioPage.EXPLORE -> {
            if (state.vaults.isEmpty() && !state.vaultsLoaded && state.vaultsError == null) PortfolioShimmer()
            else if (state.vaults.isEmpty()) QuietPortfolioMessage(
              if (state.vaultsError != null) "Vaults unavailable. Reconnecting automatically." else "No vaults available")
            else {
              Text("${state.vaults.size} vaults · ${formatBalance(state.vaults.sumOf { it.totalAum })} managed",
                Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
              state.vaults.forEach { vault ->
                VaultSummaryRow(vault.name, formatBalance(vault.totalAum), "${formatQuantity(vault.performanceFeeBps / 100.0, 2)}% performance fee") {
                  inspectedVault = vault
                }
              }
            }
          }
          PortfolioPage.REWARDS -> TradingRewardsCard(state.streak, state.amps, state.tier)
        }
        Spacer(Modifier.height(32.dp))
      }
    }
  }
  if (showBalance) FlareSheet("Balance details", { showBalance = false }) {
    DetailRow("Account value", if (overview != null) formatBalance(state.totalBalance) else "—")
    DetailRow("Trading collateral", overview?.crossUsdcBalance?.let(::formatBalance) ?: "—")
    DetailRow("Spot assets", if (overview?.spot != null || state.spotHoldings.isNotEmpty()) formatBalance(state.totalSpotValue) else "—")
    DetailRow("Free vault equity", overview?.freeVaultEquity?.let(::formatBalance) ?: "—")
    DetailRow("Margin ratio", overview?.let { "${formatQuantity(it.crossMarginRatio * 100, 2)}%" } ?: "—")
    Text("Collateral is included in trading equity. Available funds depend on open positions and orders.",
      Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
  }
  inspectedVault?.let { vault ->
    val position = state.accountVaults.firstOrNull { it.vault.address == vault.address }
    FlareSheet(vault.name.ifBlank { "Vault" }, { inspectedVault = null }) {
      position?.let {
        DetailRow("Your value", formatBalance(it.currentValue))
        DetailRow("Your shares", formatQuantity(it.currentNumShares, 4))
        DetailRow("Return", "${if (it.returnsPercent >= 0) "+" else ""}${formatQuantity(it.returnsPercent, 2)}%")
      }
      DetailRow("Share price", formatPrice(position?.effectiveSharePrice ?: vault.sharePrice))
      DetailRow("Assets managed", formatBalance(vault.totalAum))
      DetailRow("Performance fee", "${formatQuantity(vault.performanceFeeBps / 100.0, 2)}%")
      Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FlareButton("Deposit", { inspectedVault = null; onIntent(PortfolioIntent.OpenVaultAction(vault, VaultActionMode.DEPOSIT)) },
          Modifier.weight(1f), enabled = !state.busy)
        if (position != null && position.currentNumShares > 0) FlareButton("Redeem",
          { inspectedVault = null; onIntent(PortfolioIntent.OpenVaultAction(vault, VaultActionMode.REDEEM)) },
          Modifier.weight(1f), style = FlareButtonStyle.OUTLINE, enabled = !state.busy)
      }
    }
  }
  if (state.fundingMode != null) FundingSheet(state, onIntent)
  if (state.managedPositionMarket != null) PositionSheet(state, onIntent)
  if (state.vaultAction != null && state.selectedVault != null) VaultActionSheet(state, onIntent)
}

@Composable
private fun PortfolioSectionHeader(title: String, showAll: Boolean, onAll: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (showAll) TextButton(onAll) { Text("View all") }
  }
}

@Composable
private fun QuietPortfolioMessage(message: String) {
  Text(message, Modifier.fillMaxWidth().padding(vertical = 16.dp),
    color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PortfolioShimmer(modifier: Modifier = Modifier, balance: Boolean = false) {
  Column(modifier.fillMaxWidth().shimmer(rememberFlareShimmer()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (balance) FlareSkeletonBox(Modifier.width(200.dp).height(56.dp))
    else repeat(2) {
      Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        FlareSkeletonBox(Modifier.width(120.dp).height(20.dp))
        FlareSkeletonBox(Modifier.width(72.dp).height(20.dp))
      }
    }
  }
}

@Composable
private fun PositionsContent(state: PortfolioUiState, onIntent: (PortfolioIntent) -> Unit, limit: Int = Int.MAX_VALUE) {
  if (state.account.positions.isNotEmpty()) state.account.positions.take(limit).forEach { position ->
    PositionRow(position, state.marketSymbols[position.market] ?: shortAddress(position.market), state.markPrices[position.market],
      !state.busy, { onIntent(PortfolioIntent.ManagePosition(position.market)) })
    HorizontalDivider(color = FlareColors.BorderSubtle)
  } else if (state.account.overview == null && state.account.error == null) PortfolioShimmer()
  else QuietPortfolioMessage(if (state.account.overview == null || state.account.stale) "Positions are updating" else "No open positions")
}

@Composable
private fun HoldingsContent(state: PortfolioUiState, onMarketClick: (String) -> Unit, limit: Int = Int.MAX_VALUE) {
  if (state.spotHoldings.isNotEmpty()) state.spotHoldings.take(limit).forEach { holding ->
    HoldingRow(holding, holding.marketAddress != null && !state.busy, { holding.marketAddress?.let(onMarketClick) })
    HorizontalDivider(color = FlareColors.BorderSubtle)
  } else if (state.account.overview == null && state.account.error == null) PortfolioShimmer()
  else QuietPortfolioMessage(if (state.holdingsError != null || state.account.overview == null || state.account.stale) "Holdings are updating" else "No assets held")
}

@Composable
private fun VaultSummaryRow(name: String, value: String, subtitle: String, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 18.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.weight(1f)) {
      Text(name.ifBlank { "Vault" }, style = MaterialTheme.typography.bodyLarge)
      Text(subtitle, color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
    Text(value, style = MaterialTheme.typography.labelLarge)
    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = FlareColors.TextTertiary, modifier = Modifier.size(18.dp))
  }
  HorizontalDivider(color = FlareColors.BorderSubtle)
}

/** One tappable summary per position: what it is, what it is worth, where it opened. */
@Composable
private fun PositionRow(
  position: Position,
  symbol: String,
  markPrice: Double?,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val size = position.size.toDoubleOrNull()
  val pnl = markPrice?.let { mark -> size?.let { (mark - position.entryPrice) * it } }
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(symbol, style = MaterialTheme.typography.labelLarge)
      Text(
        "${if (position.isLong) "Long" else "Short"} ${formatQuantity(size?.let(::abs) ?: 0.0)} · " +
          "${position.leverage}× · entry ${formatPrice(position.entryPrice)}",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(
        pnl?.let(::formatSignedBalance) ?: "—",
        style = MaterialTheme.typography.labelLarge,
        color =
          when {
            pnl == null || abs(pnl) < FLAT_PNL -> FlareColors.TextSecondary
            pnl > 0 -> FlareColors.Positive
            else -> FlareColors.Negative
          },
      )
      Text(
        markPrice?.let { "Mark ${formatPrice(it)}" } ?: "Mark —",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Icon(
      Icons.AutoMirrored.Outlined.KeyboardArrowRight,
      contentDescription = "Manage $symbol position",
      tint = FlareColors.TextTertiary,
      modifier = Modifier.size(20.dp),
    )
  }
}

/** One tappable summary per holding: symbol, name/collateral, quantity and USD value. */
@Composable
private fun HoldingRow(
  holding: SpotHolding,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(holding.symbol, style = MaterialTheme.typography.labelLarge)
        if (holding.badge != null) {
          InstrumentBadge(holding.badge)
        }
      }
      Text(
        if (holding.isCollateral) "Trading collateral"
        else "${holding.name} · ${formatPrice(holding.markPrice)}",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(
        if (holding.markPrice > 0 || holding.isCollateral) formatBalance(holding.valueUsd) else "—",
        style = MaterialTheme.typography.labelLarge,
      )
      if (holding.reservedQuantity > 0) Text(
        "${formatQuantity(holding.reservedQuantity)} reserved",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
      )
      Text(
        "${formatQuantity(holding.quantity)} ${holding.symbol}",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    if (holding.marketAddress != null) {
      Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = "Trade ${holding.symbol}",
        tint = FlareColors.TextTertiary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/** Below this, a position's profit and loss rounds to nothing and reads as flat. */
private const val FLAT_PNL = 0.005

/** A single line for work the app is still confirming; the chain detail stays in the journal. */
private fun settlingNotice(pending: List<PendingTransaction>): String? =
  when {
    pending.isEmpty() -> null
    pending.size == 1 -> "Confirming your ${settlingActionName(pending.single().operation)}…"
    else -> "Confirming ${pending.size} actions…"
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
