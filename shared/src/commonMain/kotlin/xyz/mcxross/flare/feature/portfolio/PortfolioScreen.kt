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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.formatSignedBalance
import xyz.mcxross.flare.decibel.model.AccountVaultPerformance
import xyz.mcxross.flare.decibel.model.Position
import xyz.mcxross.flare.decibel.model.VaultInfo
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.CompactActionButton
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSegmentedControl
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.InstrumentBadge
import xyz.mcxross.flare.design.NoticeTone
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
  PortfolioScreen(state, viewModel::onIntent, onOpenSetup, onMarketClick, modifier)
}

@Composable
fun PortfolioScreen(
  state: PortfolioUiState,
  onIntent: (PortfolioIntent) -> Unit,
  onOpenSetup: () -> Unit,
  onMarketClick: (String) -> Unit = {},
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
    FlareTopBar("Portfolio", subtitle = if (state.isLive) null else "Reconnecting…")
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
      if (overview != null || state.spotHoldings.isNotEmpty()) formatBalance(state.totalBalance)
      else "—",
      Modifier.padding(top = 8.dp),
      style = MaterialTheme.typography.displayMedium,
    )
    if (
      overview != null ||
        state.spotHoldings.isNotEmpty() ||
        state.accountVaults.isNotEmpty() ||
        state.vaults.isNotEmpty()
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        when (state.selectedTab) {
          PortfolioTab.POSITIONS -> {
            PortfolioMetric(
              "Unrealized P&L",
              overview?.unrealizedPnl?.let(::formatSignedBalance) ?: "$0.00",
              Modifier.weight(1f),
            )
            PortfolioMetric(
              "Available",
              overview?.availableToTrade?.let(::formatBalance) ?: "$0.00",
              Modifier.weight(1f),
            )
            PortfolioMetric(
              "Margin ratio",
              overview?.let { "${(it.crossMarginRatio * 100).toInt()}%" } ?: "0%",
              Modifier.weight(1f),
            )
          }
          PortfolioTab.HOLDINGS -> {
            PortfolioMetric(
              "USDC Cash",
              formatBalance(state.collateralBalance),
              Modifier.weight(1f),
            )
            PortfolioMetric(
              "Spot assets",
              formatBalance(state.totalSpotValue),
              Modifier.weight(1f),
            )
            PortfolioMetric(
              "Assets",
              "${state.spotHoldings.size}",
              Modifier.weight(1f),
            )
          }
          PortfolioTab.VAULTS -> {
            PortfolioMetric(
              "Vault equity",
              formatBalance(state.accountVaults.sumOf { it.currentValue }),
              Modifier.weight(1f),
            )
            PortfolioMetric(
              "Total DLP AUM",
              formatBalance(state.vaults.sumOf { it.totalAum }),
              Modifier.weight(1f),
            )
            PortfolioMetric(
              "Active vaults",
              "${state.vaults.size}",
              Modifier.weight(1f),
            )
          }
        }
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

    if (state.streak != null || state.amps != null || state.tier != null) {
      TradingRewardsCard(
        streak = state.streak,
        amps = state.amps,
        tier = state.tier,
        modifier = Modifier.padding(top = 16.dp),
      )
    }

    if (overview != null || state.chartPoints.isNotEmpty()) {
      PortfolioPerformanceChart(
        points = state.chartPoints,
        range = state.chartRange,
        metric = state.chartMetric,
        onRangeSelect = { onIntent(PortfolioIntent.SelectChartRange(it)) },
        onMetricSelect = { onIntent(PortfolioIntent.SelectChartMetric(it)) },
        modifier = Modifier.padding(top = 16.dp),
      )
    }

    if (
      !state.isLive &&
        (state.profile.ownerAddress != null || state.profile.apiWalletAddress != null)
    ) {
      ActionNotice(
        "Reconnecting to your account…",
        Modifier.padding(top = 16.dp),
        NoticeTone.PROGRESS,
      )
    }
    settlingNotice(state.pendingTransactions)?.let { notice ->
      ActionNotice(notice, Modifier.padding(top = 16.dp), NoticeTone.PROGRESS)
    }
    Spacer(Modifier.height(24.dp))
    FlareSegmentedControl(
      options = PortfolioTab.entries,
      selectedOption = state.selectedTab,
      onOptionSelected = { onIntent(PortfolioIntent.SelectTab(it)) },
      label = { it.title },
    )
    Spacer(Modifier.height(16.dp))
    when (state.selectedTab) {
      PortfolioTab.POSITIONS -> {
        HorizontalDivider(color = FlareColors.BorderSubtle)
        when {
          state.account.positions.isNotEmpty() ->
            state.account.positions.forEach { position ->
              PositionRow(
                position = position,
                symbol = state.marketSymbols[position.market] ?: shortAddress(position.market),
                markPrice = state.markPrices[position.market],
                enabled = !state.busy,
                onClick = { onIntent(PortfolioIntent.ManagePosition(position.market)) },
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
              title = "Positions are on their way",
              message = "They appear as soon as your account reconnects.",
            )
        }
      }
      PortfolioTab.HOLDINGS -> {
        HorizontalDivider(color = FlareColors.BorderSubtle)
        when {
          state.spotHoldings.isNotEmpty() ->
            state.spotHoldings.forEach { holding ->
              HoldingRow(
                holding = holding,
                enabled = holding.marketAddress != null && !state.busy,
                onClick = { holding.marketAddress?.let(onMarketClick) },
              )
              HorizontalDivider(color = FlareColors.BorderSubtle)
            }
          state.isLive ->
            EmptyState(
              title = "No assets held",
              message = "Deposit funds or trade spot to build your portfolio.",
            )
          else ->
            EmptyState(
              title = "Holdings are on their way",
              message = "They appear as soon as your account reconnects.",
            )
        }
      }
      PortfolioTab.VAULTS -> {
        VaultsSection(
          state = state,
          onIntent = onIntent,
        )
      }
    }
  }
  if (state.fundingMode != null) FundingSheet(state, onIntent)
  if (state.managedPositionMarket != null) PositionSheet(state, onIntent)
  if (state.vaultAction != null && state.selectedVault != null) VaultActionSheet(state, onIntent)
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
        if (holding.isCollateral) "${holding.name} · Collateral"
        else "${holding.name} · ${formatPrice(holding.markPrice)}",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(
        formatBalance(holding.valueUsd),
        style = MaterialTheme.typography.labelLarge,
      )
      Text(
        "${formatQuantity(holding.quantity, 4)} ${holding.symbol}",
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

@Composable
private fun VaultsSection(
  state: PortfolioUiState,
  onIntent: (PortfolioIntent) -> Unit,
) {
  if (state.accountVaults.isEmpty() && state.vaults.isEmpty()) {
    HorizontalDivider(color = FlareColors.BorderSubtle)
    EmptyState(
      title = "No DLP vaults available",
      message = "Decibel Liquidity Provider earn vaults will appear here.",
    )
    return
  }

  if (state.accountVaults.isNotEmpty()) {
    Text(
      text = "Your Vault Positions",
      style = MaterialTheme.typography.titleSmall,
      color = FlareColors.TextSecondary,
      modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    HorizontalDivider(color = FlareColors.BorderSubtle)
    state.accountVaults.forEach { performance ->
      VaultPerformanceRow(
        performance = performance,
        enabled = !state.busy,
        onRedeem = {
          onIntent(PortfolioIntent.OpenVaultAction(performance.vault, VaultActionMode.REDEEM))
        },
      )
      HorizontalDivider(color = FlareColors.BorderSubtle)
    }
    Spacer(Modifier.height(16.dp))
  }

  Text(
    text = "Available Vaults",
    style = MaterialTheme.typography.titleSmall,
    color = FlareColors.TextSecondary,
    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
  )
  HorizontalDivider(color = FlareColors.BorderSubtle)
  state.vaults.forEach { vault ->
    val userPosition = state.accountVaults.firstOrNull { it.vault.address == vault.address }
    VaultRow(
      vault = vault,
      userShares = userPosition?.currentNumShares,
      enabled = !state.busy,
      onDeposit = {
        onIntent(PortfolioIntent.OpenVaultAction(vault, VaultActionMode.DEPOSIT))
      },
      onRedeem = {
        onIntent(PortfolioIntent.OpenVaultAction(vault, VaultActionMode.REDEEM))
      },
    )
    HorizontalDivider(color = FlareColors.BorderSubtle)
  }
}

@Composable
private fun VaultPerformanceRow(
  performance: AccountVaultPerformance,
  enabled: Boolean,
  onRedeem: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        performance.vault.name.ifBlank { "DLP Vault" },
        style = MaterialTheme.typography.labelLarge,
      )
      Text(
        "${formatQuantity(performance.currentNumShares, 4)} shares · ${formatPrice(performance.vault.sharePrice)}/sh",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(
        formatBalance(performance.currentValue),
        style = MaterialTheme.typography.labelLarge,
      )
      val returns = performance.returnsPercent
      Text(
        "${if (returns >= 0) "+" else ""}${formatPrice(returns)}%",
        color = if (returns >= 0) FlareColors.Positive else FlareColors.Negative,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    CompactActionButton(
      text = "Redeem",
      positive = false,
      onClick = onRedeem,
      enabled = enabled,
    )
  }
}

@Composable
private fun VaultRow(
  vault: VaultInfo,
  userShares: Double?,
  enabled: Boolean,
  onDeposit: () -> Unit,
  onRedeem: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        vault.name.ifBlank { "DLP Vault" },
        style = MaterialTheme.typography.labelLarge,
      )
      Text(
        "AUM ${formatBalance(vault.totalAum)} · Fee ${(vault.performanceFeeBps / 100.0)}%",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      if (userShares != null && userShares > 0.0) {
        CompactActionButton(
          text = "Redeem",
          positive = false,
          onClick = onRedeem,
          enabled = enabled,
        )
      }
      CompactActionButton(
        text = "Deposit",
        positive = true,
        onClick = onDeposit,
        enabled = enabled,
      )
    }
  }
}
