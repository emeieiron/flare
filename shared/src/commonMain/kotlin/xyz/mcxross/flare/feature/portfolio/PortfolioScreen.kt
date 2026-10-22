package xyz.mcxross.flare.feature.portfolio

import xyz.mcxross.flare.design.LocalFinishSetup
import xyz.mcxross.flare.design.UnavailableNotice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import xyz.mcxross.flare.data.formatCalendarDateTime
import xyz.mcxross.flare.data.formatPercent
import xyz.mcxross.flare.data.formatCount
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.formatSignedBalance
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.LocalVenue
import xyz.mcxross.flare.design.FlareIcons
import xyz.mcxross.flare.design.FlarePageTransition
import xyz.mcxross.flare.design.FlareSearchField
import xyz.mcxross.flare.design.FlareSkeletonBox
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.InstrumentBadge
import xyz.mcxross.flare.design.rememberFlareShimmer
import xyz.mcxross.flare.design.settlingActionName
import xyz.mcxross.flare.design.SwipeAction
import xyz.mcxross.flare.design.shortAddress
import xyz.mcxross.flare.venue.model.FeeTier
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.ReferralSummary
import xyz.mcxross.flare.venue.model.VaultSummary
import xyz.mcxross.flare.venue.model.ActionKind

@Composable
fun PortfolioRoute(
  onOpenSetup: () -> Unit,
  reselections: Flow<Unit> = emptyFlow(),
  onMarketClick: (String) -> Unit = {},
  onPositionClick: (String) -> Unit = {},
  modifier: Modifier = Modifier,
  viewModel: PortfolioViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(lifecycleOwner, viewModel) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.refreshWhileVisible() }
  }
  PortfolioScreen(state, viewModel::onIntent, onOpenSetup, onMarketClick, onPositionClick, modifier, reselections)
}

private enum class PortfolioPage(val title: String) {
  OVERVIEW("Portfolio"), POSITIONS("Positions"), HOLDINGS("Holdings"),
  VAULTS("Vaults"), VAULT_DETAIL("Vault details"),
  HISTORY("Performance"), REWARDS("Rewards");

  val depth: Int
    get() = when (this) {
      OVERVIEW -> 0
      VAULT_DETAIL -> 2
      else -> 1
    }
}

@Composable
fun PortfolioScreen(
  state: PortfolioUiState,
  onIntent: (PortfolioIntent) -> Unit,
  onOpenSetup: () -> Unit,
  onMarketClick: (String) -> Unit = {},
  onPositionClick: (String) -> Unit = {},
  modifier: Modifier = Modifier,
  reselections: Flow<Unit> = emptyFlow(),
) {
  var page by rememberSaveable { mutableStateOf(PortfolioPage.OVERVIEW) }
  val busy by rememberUpdatedState(state.busy)
  LaunchedEffect(reselections) { reselections.collect { if (!busy) page = PortfolioPage.OVERVIEW } }
  // The position a left swipe asked to close.
  var closeTarget by rememberSaveable { mutableStateOf<String?>(null) }
  var showBalance by rememberSaveable { mutableStateOf(false) }
  var vaultAddress by rememberSaveable { mutableStateOf<String?>(null) }
  val savedPages = rememberSaveableStateHolder()
  val inspectedVault = state.accountVaults.firstOrNull { it.vault.address == vaultAddress }?.vault
    ?: state.vaults.firstOrNull { it.address == vaultAddress }
  val back: () -> Unit = {
    if (!state.busy) {
      page = when (page) {
        PortfolioPage.VAULT_DETAIL -> PortfolioPage.VAULTS
        else -> PortfolioPage.OVERVIEW
      }
    }
  }
  NavigationBackHandler(
    state = rememberNavigationEventState(NavigationEventInfo.None),
    isBackEnabled = page != PortfolioPage.OVERVIEW,
    onBackCompleted = back,
  )
  LaunchedEffect(page) {
    onIntent(PortfolioIntent.SetHistoryVisible(page == PortfolioPage.HISTORY))
    if (page == PortfolioPage.VAULTS) onIntent(PortfolioIntent.RefreshVaults)
  }
  FlarePageTransition(page, { it.depth }, modifier) { displayedPage ->
    Column(Modifier.fillMaxSize()) {
      if (displayedPage != PortfolioPage.OVERVIEW) {
        val title = when (displayedPage) {
          PortfolioPage.VAULT_DETAIL -> inspectedVault?.name ?: displayedPage.title
          else -> displayedPage.title
        }
        BackBar(title, back)
      }
      savedPages.SaveableStateProvider(displayedPage) {
        var query by rememberSaveable { mutableStateOf("") }
        LazyColumn(
          state = rememberLazyListState(),
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
          if (state.profile.ownerAddress == null && state.profile.apiWalletAddress == null) {
            item {
              FlareTopBar("Portfolio")
              Text("Your portfolio starts here", style = MaterialTheme.typography.headlineMedium)
              Text("Connect a wallet to view your assets and positions.",
                Modifier.padding(top = 12.dp), color = FlareColors.TextSecondary)
              FlareButton("Create or import wallet", onOpenSetup, Modifier.fillMaxWidth().padding(top = 24.dp))
            }
            return@LazyColumn
          }
          when (displayedPage) {
            PortfolioPage.OVERVIEW -> item {
              PortfolioOverview(state, showBalance, { showBalance = !showBalance }, onIntent) { page = it }
            }
            PortfolioPage.POSITIONS -> {
              val positions = state.account.positions.sortedByDescending { abs(it.size.toDoubleOrNull() ?: 0.0) * it.entryPrice }
              if (positions.size > 8 || query.isNotEmpty()) item {
                FlareSearchField(query, { query = it }, "Search positions", FlareIcons.Search,
                  Modifier.padding(bottom = 16.dp))
              }
              val visible = positions.filter { (state.marketSymbols[it.marketId] ?: it.marketId).contains(query, true) }
              if (positions.isEmpty()) item { PositionsEmptyState(state) }
              else if (visible.isEmpty()) item { QuietPortfolioMessage("No matching positions") }
              items(visible, key = { it.marketId }) { position ->
                SwipeAction("Close", { closeTarget = position.marketId },
                  enabled = !state.busy && state.capabilities.unavailableReason(ActionKind.TRADING) == null) {
                  PositionRow(position, state.marketSymbols[position.marketId] ?: shortAddress(position.marketId),
                    state.markPrices[position.marketId], !state.busy, { onPositionClick(position.marketId) })
                }
                HorizontalDivider(color = FlareColors.BorderSubtle)
              }
            }
            PortfolioPage.HOLDINGS -> {
              if (state.spotHoldings.size > 8 || query.isNotEmpty()) item {
                FlareSearchField(query, { query = it }, "Search holdings", FlareIcons.Search,
                  Modifier.padding(bottom = 16.dp))
              }
              val visible = state.spotHoldings.filter { it.symbol.contains(query, true) || it.name.contains(query, true) }
              if (state.spotHoldings.isEmpty()) item { HoldingsEmptyState(state) }
              else if (visible.isEmpty()) item { QuietPortfolioMessage("No matching holdings") }
              items(visible) { holding ->
                HoldingRow(holding, holding.marketAddress != null && !state.busy, { holding.marketAddress?.let(onMarketClick) })
                HorizontalDivider(color = FlareColors.BorderSubtle)
              }
            }
            PortfolioPage.HISTORY -> item {
              PortfolioPerformanceChart(
                points = state.chartPoints, range = state.chartRange, metric = state.chartMetric,
                loading = state.chartLoading || (!state.chartLoaded && state.chartError == null), error = state.chartError,
                onRangeSelect = { onIntent(PortfolioIntent.SelectChartRange(it)) },
                onMetricSelect = { onIntent(PortfolioIntent.SelectChartMetric(it)) },
              )
            }
            PortfolioPage.VAULTS -> {
              val owned = state.accountVaults.map { it.vault.address }.toSet()
              val available = state.vaults.filter { it.address !in owned }.distinctBy { it.address }
              val ownedMatches = state.accountVaults.filter { it.vault.name.contains(query, true) }
              val availableMatches = available.filter { it.name.contains(query, true) }
              item {
                Text("Invested in vaults", color = FlareColors.TextSecondary)
                if (!state.vaultsLoaded && state.vaultsError == null) PortfolioShimmer(balance = true)
                else Text(if (state.vaultsLoaded) formatBalance(state.accountVaults.sumOf { it.value }) else "—",
                  Modifier.padding(top = 8.dp), style = MaterialTheme.typography.displaySmall)
                if (state.vaultsError != null) QuietPortfolioMessage(
                  if (state.vaultsLoaded) "Updating · showing your last vault balances" else "Vaults unavailable. Reconnecting automatically.")
                if (state.vaults.size > 8 || query.isNotEmpty()) FlareSearchField(query, { query = it }, "Search vaults", FlareIcons.Search,
                  Modifier.padding(top = 20.dp))
                if (ownedMatches.isNotEmpty()) SectionLabel("Your deposits")
                else if (state.vaultsLoaded && owned.isEmpty() && query.isBlank()) QuietPortfolioMessage("No vault deposits yet")
              }
              items(ownedMatches, key = { "owned:${it.vault.address}" }) { position ->
                VaultSummaryRow(position.vault.name, formatBalance(position.value),
                  "${formatQuantity(position.shares, 4)} shares") {
                  vaultAddress = position.vault.address
                  page = PortfolioPage.VAULT_DETAIL
                }
              }
              if (availableMatches.isNotEmpty()) item { SectionLabel("Discover") }
              items(availableMatches, key = { it.address }) { vault ->
                VaultSummaryRow(vault.name, formatBalance(vault.totalValue),
                  "Assets managed · ${formatQuantity(vault.performanceFeePercent, 2)}% fee") {
                  vaultAddress = vault.address
                  page = PortfolioPage.VAULT_DETAIL
                }
              }
              if (!state.vaultsLoaded && state.vaultsError == null) item { PortfolioShimmer() }
              else if (ownedMatches.isEmpty() && availableMatches.isEmpty()) item {
                if (query.isNotBlank()) QuietPortfolioMessage("No matching vaults")
                else if (state.vaultsLoaded) QuietPortfolioMessage("No vaults available")
              }
            }
            PortfolioPage.VAULT_DETAIL -> item {
              if (inspectedVault != null) Column { VaultDetails(state, inspectedVault, onIntent) }
              else QuietPortfolioMessage("This vault is no longer available")
            }
            PortfolioPage.REWARDS -> item {
              val rewards = state.rewards
              if (rewards?.streak != null || rewards?.points != null || rewards?.tier != null ||
                rewards?.feeTier != null || rewards?.referral != null) {
                Column { RewardsDetails(state) }
                if (state.rewardsError) QuietPortfolioMessage("Updating · showing your last rewards")
              } else if (!state.rewardsLoaded && !state.rewardsError) PortfolioShimmer()
              else QuietPortfolioMessage(if (state.rewardsError) "Rewards unavailable. Reconnecting automatically." else "No rewards yet")
            }
          }
        }
      }
    }
  }
  closeTarget?.let { market ->
    val position = state.account.positions.firstOrNull { it.marketId == market }
    if (position != null) ClosePositionSheet(state, position, onIntent, { closeTarget = null }, { closeTarget = null })
    else LaunchedEffect(market) { closeTarget = null }
  }
  if (state.fundingMode != null) FundingSheet(state, onIntent)
  if (state.vaultAction != null && state.selectedVault != null) VaultActionSheet(state, onIntent)
}

@Composable
private fun PortfolioOverview(
  state: PortfolioUiState,
  expanded: Boolean,
  onExpand: () -> Unit,
  onIntent: (PortfolioIntent) -> Unit,
  onPage: (PortfolioPage) -> Unit,
) {
  val overview = state.account.overview
  FlareTopBar("Portfolio")
  Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse balance breakdown" else "Expand balance breakdown", onClick = onExpand)) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Account value", style = MaterialTheme.typography.bodyMedium, color = FlareColors.TextSecondary)
      Icon(if (expanded) FlareIcons.ChevronUp else FlareIcons.ChevronDown,
        contentDescription = null, tint = FlareColors.TextSecondary)
    }
    if (overview == null && state.account.error == null) PortfolioShimmer(Modifier.padding(vertical = 8.dp), balance = true)
    else Text(if (overview != null || state.spotHoldings.isNotEmpty()) formatBalance(state.totalBalance) else "—",
      style = MaterialTheme.typography.displayMedium)
  }
  Text(when {
    overview == null && state.account.error != null -> "Account unavailable. Reconnecting automatically."
    overview == null -> "Loading your account"
    state.account.stale -> "Updating · showing your last balance"
    state.balanceIncomplete -> "Partial balance · updating"
    else -> ""
  }, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = FlareColors.TextTertiary)
  AnimatedVisibility(expanded) {
    Column(Modifier.padding(top = 12.dp)) {
      DetailRow("Trading equity", overview?.equity?.let(::formatBalance) ?: "—")
      DetailRow("Trading collateral", overview?.balances?.collateral?.let(::formatBalance) ?: "—")
      if (state.capabilities.spot) DetailRow("Spot assets",
        if (overview?.balances?.spot != null || state.spotHoldings.isNotEmpty()) formatBalance(state.totalSpotValue) else "—")
      if (state.vaultsAvailable) DetailRow("Free vault equity", overview?.vaultEquity?.let(::formatBalance) ?: "—")
      DetailRow("Margin ratio", overview?.let { "${formatQuantity(it.marginRatio * 100, 2)}%" } ?: "—")
      Text("Trading collateral is included in trading equity.", Modifier.padding(top = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
    }
  }
  Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    PortfolioMetric("Available to trade", overview?.availableToTrade?.let(::formatBalance) ?: "—", Modifier.weight(1f))
    PortfolioMetric("Unrealized P&L", overview?.unrealizedPnl?.let(::formatSignedBalance) ?: "—", Modifier.weight(1f))
  }
  Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    val setupNeed = state.setupNeed
    val finishSetup = LocalFinishSetup.current
    if (setupNeed != null) FlareButton(setupNeed.actionLabel, { finishSetup(setupNeed) }, Modifier.weight(1f))
    else if (state.profile.ownerAddress != null && state.capabilities.unavailableReason(ActionKind.FUNDS) == null) FlareButton("Transfer",
      { onIntent(PortfolioIntent.OpenFunding(FundingMode.DEPOSIT)) }, Modifier.weight(1f))
    FlareButton("Performance", { onPage(PortfolioPage.HISTORY) }, Modifier.weight(1f), style = FlareButtonStyle.OUTLINE)
  }
  settlingNotice(state.pendingTransactions)?.let {
    Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
  }
  val position = state.account.positions.maxByOrNull { abs(it.size.toDoubleOrNull() ?: 0.0) * it.entryPrice }
  PortfolioDestination("Positions", state.account.positions.size.takeIf { overview != null }?.toString(),
    { onPage(PortfolioPage.POSITIONS) }) {
    if (position != null) {
      val size = position.size.toDoubleOrNull()
      val pnl = state.markPrices[position.marketId]?.let { mark -> size?.let { (mark - position.entryPrice) * it } }
      PreviewValue(state.marketSymbols[position.marketId] ?: shortAddress(position.marketId),
        pnl?.let(::formatSignedBalance) ?: "—",
        "${if (position.isLong) "Long" else "Short"} ${formatQuantity(abs(size ?: 0.0))}",
        valueColor = when {
          pnl == null || abs(pnl) < FLAT_PNL -> FlareColors.TextSecondary
          pnl > 0 -> FlareColors.Positive
          else -> FlareColors.Negative
        })
    } else PositionsEmptyState(state)
  }
  val assets = state.spotHoldings.filter { it.quantity > 0 }.map { it.symbol.uppercase() }.distinct().size
  PortfolioDestination("Holdings", if (overview != null) "$assets ${if (assets == 1) "asset" else "assets"}" else null,
    { onPage(PortfolioPage.HOLDINGS) }) {
    when (val preview = state.holdingsPreview) {
      HoldingsPreview.Loading -> PortfolioShimmer(rows = 1)
      HoldingsPreview.NoneHeld -> QuietPortfolioMessage("No assets held")
      is HoldingsPreview.Holding -> PreviewValue(preview.symbol, preview.valueUsd?.let(::formatBalance) ?: "—", preview.subtitle)
    }
  }
  if (state.vaultsAvailable) PortfolioDestination("Vaults",
    state.accountVaults.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "vault" else "vaults"}" },
    { onPage(PortfolioPage.VAULTS) }) {
    if (!state.vaultsLoaded && state.vaultsError == null) PortfolioShimmer(rows = 1)
    else PreviewValue("Invested", if (state.vaultsLoaded) formatBalance(state.accountVaults.sumOf { it.value }) else "—",
      if (state.vaultsError != null) "Updating automatically" else null)
  }
  if (state.rewardsAvailable && state.rewardPointsName == null) {
    // Without reward points, the card is the account's fee tier.
    val feeTier = state.rewards?.feeTier
    PortfolioDestination("Fees & referrals", feeTier?.name, { onPage(PortfolioPage.REWARDS) }) {
      if (!state.rewardsLoaded && !state.rewardsError && feeTier == null) PortfolioShimmer(rows = 1)
      else PreviewValue("Taker · maker", feeTier?.let { "${feeRate(it.perp.takerRate)} · ${feeRate(it.perp.makerRate)}" } ?: "—",
        if (state.rewardsError) "Updating automatically" else null)
    }
  } else if (state.rewardsAvailable) PortfolioDestination("Rewards", state.rewards?.tier?.name, { onPage(PortfolioPage.REWARDS) }) {
    val points = state.rewards?.points
    if (!state.rewardsLoaded && !state.rewardsError && points == null) PortfolioShimmer(rows = 1)
    else PreviewValue(state.rewardPointsName ?: "Points", points?.let { formatCount(it.total) } ?: "—",
      if (state.rewardsError) "Updating automatically" else null)
  }
}

@Composable
private fun PortfolioDestination(title: String, count: String?, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "View $title", onClick = onClick).padding(vertical = 20.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
      if (count != null) Text(count, style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
      Icon(FlareIcons.ChevronRight, null, tint = FlareColors.TextTertiary, modifier = Modifier.size(20.dp))
    }
    Spacer(Modifier.height(12.dp))
    content()
  }
  HorizontalDivider(color = FlareColors.BorderSubtle)
}

@Composable
private fun PreviewValue(
  label: String,
  value: String,
  subtitle: String? = null,
  valueColor: Color = FlareColors.TextPrimary,
) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FlareColors.TextSecondary)
    }
    Text(value, style = MaterialTheme.typography.labelLarge, color = valueColor)
  }
}

@Composable
private fun SectionLabel(title: String) {
  Text(title, Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun VaultDetails(state: PortfolioUiState, vault: VaultSummary, onIntent: (PortfolioIntent) -> Unit) {
  val position = state.accountVaults.firstOrNull { it.vault.address == vault.address }
  if (vault.description.isNotBlank()) Text(vault.description, Modifier.padding(bottom = 20.dp), color = FlareColors.TextSecondary)
  val stakeInUsdc = LocalVenue.current.vaultTerms.stakeInUsdc
  position?.let {
    DetailRow("Your value", formatBalance(it.value))
    if (!stakeInUsdc) DetailRow("Your shares", formatQuantity(it.shares, 4))
    DetailRow("Return", "${if (it.returnPercent >= 0) "+" else ""}${formatQuantity(it.returnPercent, 2)}%")
  }
  if (!stakeInUsdc) DetailRow("Share price", formatPrice(position?.sharePrice ?: vault.sharePrice))
  DetailRow("Assets managed", formatBalance(vault.totalValue))
  DetailRow("Performance fee", "${formatQuantity(vault.performanceFeePercent, 2)}%")
  val actionsUnavailable = state.capabilities.unavailableReason(ActionKind.FUNDS)
  val lockedUntil = position?.lockedUntil()
  Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    FlareButton("Deposit", { onIntent(PortfolioIntent.OpenVaultAction(vault, VaultActionMode.DEPOSIT)) },
      Modifier.weight(1f), enabled = !state.busy && actionsUnavailable == null)
    if (position != null && position.shares > 0) FlareButton("Redeem",
      { onIntent(PortfolioIntent.OpenVaultAction(vault, VaultActionMode.REDEEM)) },
      Modifier.weight(1f), style = FlareButtonStyle.OUTLINE,
      enabled = !state.busy && actionsUnavailable == null && lockedUntil == null)
  }
  lockedUntil?.let {
    Text("Your latest deposit is locked until ${formatCalendarDateTime(it)}.", Modifier.padding(top = 12.dp),
      color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
  }
  actionsUnavailable?.let { UnavailableNotice(it, state.setupNeed, Modifier.padding(top = 12.dp)) }
}

@Composable
private fun RewardsDetails(state: PortfolioUiState) {
  if (state.rewardPointsName == null) {
    FeeTierDetails(state.rewards?.feeTier)
    state.rewards?.referral?.let { ReferralDetails(it) }
    return
  }
  val pointsName = state.rewardPointsName
  val points = state.rewards?.points
  Text("Total $pointsName", color = FlareColors.TextSecondary)
  Text(points?.let { formatCount(it.total) } ?: "—",
    Modifier.padding(top = 8.dp, bottom = 24.dp), style = MaterialTheme.typography.displayMedium)
  state.rewards?.tier?.let { DetailRow("Tier", it.name) }
  points?.rank?.let { DetailRow("Rank", "#$it") }
  state.rewards?.streak?.let {
    DetailRow("Current streak", if (it.days == 0) "No active streak" else "${it.days} days")
    if (it.longestDays > 0) DetailRow("Longest streak", "${it.longestDays} days")
    if (it.graceDaysRemaining > 0) DetailRow("Grace remaining", "${it.graceDaysRemaining} days")
  }
  points?.let {
    val breakdown = it.sources.filter { source -> source.amount > 0 }
    if (breakdown.isNotEmpty()) SectionLabel("$pointsName earned")
    breakdown.forEach { source -> DetailRow(source.label, formatQuantity(source.amount, 0)) }
  }
}

@Composable
private fun FeeTierDetails(tier: FeeTier?) {
  if (tier == null) return
  Text("Fee tier", color = FlareColors.TextSecondary)
  Text(tier.name, Modifier.padding(top = 8.dp, bottom = 24.dp), style = MaterialTheme.typography.displayMedium)
  DetailRow("14-day volume", formatBalance(tier.volumeUsd))
  DetailRow("Perps taker", feeRate(tier.perp.takerRate))
  DetailRow("Perps maker", feeRate(tier.perp.makerRate))
  tier.spot?.let {
    DetailRow("Spot taker", feeRate(it.takerRate))
    DetailRow("Spot maker", feeRate(it.makerRate))
  }
  tier.stakingDiscount?.let { DetailRow("Staking discount", formatPercent(it * 100)) }
  tier.referralDiscount?.let { DetailRow("Referral discount", formatPercent(it * 100)) }
}

@Composable
private fun ReferralDetails(referral: ReferralSummary) {
  SectionLabel("Referrals")
  DetailRow("Your code", referral.code ?: "None yet")
  referral.referredBy?.let { DetailRow("Referred with", it) }
  referral.earnedUsd?.let { DetailRow("Earned", formatBalance(it)) }
}

/** A fee rate as a percentage of notional, with a rebate shown as negative. */
private fun feeRate(rate: Double): String = (if (rate < 0) "−" else "") + formatQuantity(abs(rate) * 100, 4) + "%"

@Composable
private fun QuietPortfolioMessage(message: String) {
  Text(message, Modifier.fillMaxWidth().padding(vertical = 12.dp), color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PortfolioShimmer(modifier: Modifier = Modifier, balance: Boolean = false, rows: Int = 2) {
  Column(modifier.fillMaxWidth().shimmer(rememberFlareShimmer()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (balance) FlareSkeletonBox(Modifier.width(200.dp).height(56.dp))
    else repeat(rows) {
      Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        FlareSkeletonBox(Modifier.width(120.dp).height(20.dp))
        FlareSkeletonBox(Modifier.width(72.dp).height(20.dp))
      }
    }
  }
}

@Composable
private fun PositionsEmptyState(state: PortfolioUiState) {
  if (state.account.overview == null && state.account.error == null) PortfolioShimmer(rows = 1)
  else QuietPortfolioMessage(if (state.account.overview == null || state.account.stale) "Positions are updating" else "No open positions")
}

@Composable
private fun HoldingsEmptyState(state: PortfolioUiState) {
  if (state.account.overview == null && state.account.error == null) PortfolioShimmer()
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
    Icon(FlareIcons.ChevronRight, null, tint = FlareColors.TextTertiary, modifier = Modifier.size(18.dp))
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
      FlareIcons.ChevronRight,
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
        FlareIcons.ChevronRight,
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
