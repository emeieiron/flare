package xyz.mcxross.flare.feature.shell

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Instant
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatCompact
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.feature.orders.OrdersIntent
import xyz.mcxross.flare.feature.orders.OrdersSection
import xyz.mcxross.flare.feature.orders.OrdersUiState
import xyz.mcxross.flare.feature.orders.OrdersViewModel
import xyz.mcxross.flare.feature.portfolio.FundingMode
import xyz.mcxross.flare.feature.portfolio.PortfolioIntent
import xyz.mcxross.flare.feature.portfolio.PortfolioUiState
import xyz.mcxross.flare.feature.portfolio.PortfolioViewModel
import xyz.mcxross.flare.feature.settings.SettingsIntent
import xyz.mcxross.flare.feature.settings.SettingsUiState
import xyz.mcxross.flare.feature.settings.SettingsViewModel

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
        .padding(horizontal = 20.dp)
  ) {
    FlareTopBar(
      "Portfolio",
      subtitle =
        when {
          state.isLive -> "Live · ${state.sessionRole?.name?.lowercase()} session"
          state.profile.apiOnly -> "API wallet only · locked"
          state.profile.ownerAddress != null -> "Wallet configured · locked"
          else -> "Anonymous mode"
        },
    )
    Text(
      "Total equity",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
    )
    Text(
      overview?.equityBalance?.let(::formatCompact) ?: "—",
      style = MaterialTheme.typography.displaySmall,
    )
    if (overview != null) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        PortfolioMetric(
          "Unrealized PnL",
          formatCompact(overview.unrealizedPnl),
          Modifier.weight(1f),
        )
        PortfolioMetric("Available", formatCompact(overview.availableToTrade), Modifier.weight(1f))
        PortfolioMetric(
          "Margin ratio",
          "${(overview.crossMarginRatio * 100).toInt()}%",
          Modifier.weight(1f),
        )
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FlareButton(
        "Deposit",
        { onIntent(PortfolioIntent.OpenFunding(FundingMode.DEPOSIT)) },
        Modifier.weight(1f),
        enabled = state.profile.ownerAddress != null,
      )
      FlareButton(
        "Withdraw",
        { onIntent(PortfolioIntent.OpenFunding(FundingMode.WITHDRAW)) },
        Modifier.weight(1f),
        enabled = state.profile.ownerAddress != null,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    state.fundingMode?.let { mode ->
      Text(
        if (mode == FundingMode.DEPOSIT) "Deposit Aptos USDC" else "Withdraw Aptos USDC",
        Modifier.padding(top = 20.dp),
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        "Owner authorization is required. Amounts use six-decimal USDC chain precision.",
        Modifier.padding(top = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
      OutlinedTextField(
        value = state.fundingAmount,
        onValueChange = { onIntent(PortfolioIntent.ChangeFundingAmount(it)) },
        label = { Text("Amount in USDC") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      )
      state.fundingTransaction?.let { transaction ->
        Text(
          transaction.label(),
          Modifier.padding(top = 8.dp),
          color =
            if (transaction is TransactionState.Failed) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          style = MaterialTheme.typography.labelMedium,
        )
        if (transaction is TransactionState.Failed)
          transaction.selfPayEstimateOctas?.let { estimate ->
            Text(
              "Sponsorship was rejected. Self-payment is estimated at " +
                "${estimate.toDecimalString(8)} APT.",
              Modifier.padding(top = 8.dp),
              color = MaterialTheme.colorScheme.tertiary,
              style = MaterialTheme.typography.bodyMedium,
            )
            FlareButton(
              "Confirm and self-pay",
              { onIntent(PortfolioIntent.ConfirmSelfPay) },
              Modifier.fillMaxWidth().padding(top = 8.dp),
              enabled = !state.busy,
              style = FlareButtonStyle.OUTLINE,
            )
          }
      }
      FlareButton(
        if (state.busy) "Authorizing…"
        else if (mode == FundingMode.DEPOSIT) "Deposit USDC" else "Withdraw USDC",
        { onIntent(PortfolioIntent.SubmitFunding) },
        Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = !state.busy && state.fundingAmount.isNotBlank(),
      )
      FlareButton(
        "Close",
        { onIntent(PortfolioIntent.CloseFunding) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    if (
      !state.isLive &&
        (state.profile.ownerAddress != null || state.profile.apiWalletAddress != null)
    ) {
      FlareButton(
        text = if (state.busy) "Unlocking…" else "Unlock live account",
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
        "${state.pendingTransactions.size} transaction${if (state.pendingTransactions.size == 1) "" else "s"} awaiting reconciliation",
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
        "Retry reconciliation",
        { onIntent(PortfolioIntent.Refresh) },
        Modifier.fillMaxWidth().padding(top = 10.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    Spacer(Modifier.height(32.dp))
    Text("Positions", style = MaterialTheme.typography.titleLarge)
    HorizontalDivider(Modifier.padding(top = 12.dp), color = FlareColors.BorderSubtle)
    when {
      state.account.positions.isNotEmpty() ->
        state.account.positions.forEach { position ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(Modifier.weight(1f)) {
              Text(shortAddress(position.market), style = MaterialTheme.typography.labelMedium)
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
          title = "No connected account",
          message = "Create or import a wallet to view collateral, margin health, and positions.",
          actionLabel = "Set up wallet",
          onAction = onOpenSetup,
        )
    }
    val managedPosition =
      state.account.positions.firstOrNull { it.market == state.managedPositionMarket }
    managedPosition?.let { position ->
      Spacer(Modifier.height(28.dp))
      Text("Position controls", style = MaterialTheme.typography.titleLarge)
      Text(
        "${shortAddress(position.market)} · exact size ${position.size}",
        Modifier.padding(top = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
      )
      Text(
        "Leverage · ${position.leverage}×",
        Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.labelMedium,
      )
      Text(
        "Decibel requires this market’s position to be closed before changing leverage or margin mode.",
        Modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedTextField(
        value = state.takeProfitInput,
        onValueChange = { onIntent(PortfolioIntent.ChangeTakeProfit(it)) },
        label = { Text("Take-profit trigger / limit") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      )
      OutlinedTextField(
        value = state.stopLossInput,
        onValueChange = { onIntent(PortfolioIntent.ChangeStopLoss(it)) },
        label = { Text("Stop-loss trigger / limit") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      )
      FlareButton(
        "Set TP / SL",
        { onIntent(PortfolioIntent.SubmitTpSl) },
        Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled =
          !state.busy && (state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank()),
      )
      FlareButton(
        "Close full position",
        { onIntent(PortfolioIntent.ClosePosition) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.SELL,
      )
      state.positionTransaction?.let { transaction ->
        Text(
          transaction.label(),
          Modifier.padding(top = 12.dp),
          color =
            if (transaction is TransactionState.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelMedium,
        )
        if (transaction is TransactionState.Failed) {
          transaction.selfPayEstimateOctas?.let { estimate ->
            Text(
              "Sponsorship was rejected. Estimated self-pay cost: " +
                "${estimate.toDecimalString(8)} APT.",
              Modifier.padding(top = 8.dp),
              color = MaterialTheme.colorScheme.tertiary,
              style = MaterialTheme.typography.bodyMedium,
            )
            FlareButton(
              "Confirm and self-pay",
              { onIntent(PortfolioIntent.ConfirmPositionSelfPay) },
              Modifier.fillMaxWidth().padding(top = 8.dp),
              enabled = !state.busy,
              style = FlareButtonStyle.OUTLINE,
            )
            if (state.apiWalletNeedsTopUp)
              state.suggestedTopUpOctas?.let { amount ->
                Text(
                  "The API wallet needs APT for self-payment. Transfer " +
                    "${amount.toDecimalString(8)} APT from the owner wallet, then unlock the API wallet again.",
                  Modifier.padding(top = 12.dp),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodyMedium,
                )
                FlareButton(
                  "Top up API wallet",
                  { onIntent(PortfolioIntent.TopUpApiWallet) },
                  Modifier.fillMaxWidth().padding(top = 8.dp),
                  enabled = !state.busy,
                  style = FlareButtonStyle.OUTLINE,
                )
              }
          }
        }
      }
      state.topUpTransaction?.let { transaction ->
        Text(
          "API-wallet top-up · ${transaction.label()}",
          Modifier.padding(top = 12.dp),
          color =
            if (transaction is TransactionState.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelMedium,
        )
      }
      FlareButton(
        "Done",
        { onIntent(PortfolioIntent.DismissPositionManagement) },
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
  }
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
fun OrdersRoute(
  onOpenSetup: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: OrdersViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  OrdersScreen(state, viewModel::onIntent, onOpenSetup, modifier)
}

@Composable
fun OrdersScreen(
  state: OrdersUiState,
  onIntent: (OrdersIntent) -> Unit,
  onOpenSetup: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(FlareColors.Canvas)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
  ) {
    FlareTopBar(
      "Orders",
      subtitle =
        if (state.account.stale) {
          "Account data · stale or locked"
        } else {
          "Open orders and account history · live"
        },
    )
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OrdersSection.entries.forEach { section ->
        FlareChip(
          text = section.label,
          selected = state.section == section,
          onClick = { onIntent(OrdersIntent.SelectSection(section)) },
        )
      }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.history.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.transaction?.let { transaction ->
      Text(
        transaction.label(),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
      )
      if (transaction is TransactionState.Failed)
        transaction.selfPayEstimateOctas?.let { estimate ->
          Text(
            "Sponsorship was rejected. Estimated self-pay cost: " +
              "${estimate.toDecimalString(8)} APT.",
            Modifier.padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodyMedium,
          )
          FlareButton(
            "Confirm and self-pay",
            { onIntent(OrdersIntent.ConfirmSelfPay) },
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            enabled = !state.busy,
            style = FlareButtonStyle.OUTLINE,
          )
          if (state.apiWalletNeedsTopUp)
            state.suggestedTopUpOctas?.let { amount ->
              Text(
                "The API wallet needs APT for self-payment. Transfer " +
                  "${amount.toDecimalString(8)} APT from the owner wallet, then unlock the API wallet again.",
                Modifier.padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
              )
              FlareButton(
                "Top up API wallet",
                { onIntent(OrdersIntent.TopUpApiWallet) },
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                enabled = !state.busy,
                style = FlareButtonStyle.OUTLINE,
              )
            }
        }
    }
    state.topUpTransaction?.let { transaction ->
      Text(
        "API-wallet top-up · ${transaction.label()}",
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        color =
          if (transaction is TransactionState.Failed) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        style = MaterialTheme.typography.labelMedium,
      )
    }
    val expectedSigner = state.profile.apiWalletAddress ?: state.profile.ownerAddress
    if (
      expectedSigner != null &&
        !expectedSigner.equals(state.sessionWalletAddress, ignoreCase = true)
    ) {
      FlareButton(
        "Unlock trading wallet",
        { onIntent(OrdersIntent.Unlock) },
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    when (state.section) {
      OrdersSection.OPEN ->
        when {
          state.account.openOrders.isNotEmpty() ->
            state.account.openOrders.forEach { order ->
              Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Column(Modifier.weight(1f)) {
                    Text(shortAddress(order.market), style = MaterialTheme.typography.titleLarge)
                    Text(
                      "${if (order.isBuy) "Buy" else "Sell"} · ${order.orderType.ifBlank { order.status }}",
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      style = MaterialTheme.typography.labelSmall,
                    )
                  }
                  Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(
                      order.remainingSize?.toString() ?: "—",
                      style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                      order.price?.let(::formatPrice) ?: "Market",
                      style = MaterialTheme.typography.labelSmall,
                    )
                  }
                }
                FlareButton(
                  text = if (order.isTpSl) "Cancel TP/SL" else "Cancel order",
                  onClick = {
                    onIntent(
                      OrdersIntent.Cancel(
                        order.market,
                        order.orderId,
                        order.isTpSl,
                      )
                    )
                  },
                  modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                  enabled = !state.busy,
                  style = FlareButtonStyle.OUTLINE,
                )
              }
              HorizontalDivider(color = FlareColors.BorderSubtle)
            }
          state.profile.ownerAddress != null || state.profile.apiWalletAddress != null ->
            EmptyState(
              title = if (state.account.stale) "Account is locked" else "No open orders",
              message =
                if (state.account.stale) {
                  "Unlock the wallet to load current orders. Stale order status is never presented as live."
                } else {
                  "Working orders will appear here after submission."
                },
              actionLabel = if (state.account.stale) "Unlock account" else null,
              onAction = { onIntent(OrdersIntent.Unlock) },
            )
          else ->
            EmptyState(
              title = "No account orders",
              message =
                "Connect a delegated API wallet to manage open orders, fills, funding, and TP/SL.",
              actionLabel = "Set up trading",
              onAction = onOpenSetup,
            )
        }
      OrdersSection.ORDERS -> {
        if (state.history.orders.isEmpty()) {
          HistoryEmpty(state.history.stale, "No order history", onIntent)
        } else {
          state.history.orders.forEach { order ->
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                  Text(order.orderDirection.ifBlank { if (order.isBuy) "Buy" else "Sell" })
                  Text(
                    "${shortAddress(order.market)} · ${order.status}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                  Text(order.price?.let(::formatPrice) ?: "Market")
                  Text(
                    formatHistoryTime(order.unixMs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
              }
            }
            HorizontalDivider(color = FlareColors.BorderSubtle)
          }
        }
      }
      OrdersSection.TRADES -> {
        if (state.history.trades.isEmpty()) {
          HistoryEmpty(state.history.stale, "No trade history", onIntent)
        } else {
          state.history.trades.forEach { trade ->
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                  Text(trade.action.ifBlank { "Fill" })
                  Text(
                    "${shortAddress(trade.market)} · ${trade.size} @ ${formatPrice(trade.price)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                  Text(
                    "PnL ${signedAmount(trade.realizedPnlAmount)} USDC",
                    color =
                      if (trade.realizedPnlAmount >= 0) {
                        FlareColors.Positive
                      } else {
                        MaterialTheme.colorScheme.error
                      },
                    style = MaterialTheme.typography.labelMedium,
                  )
                  Text(
                    formatHistoryTime(trade.transactionUnixMs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
              }
            }
            HorizontalDivider(color = FlareColors.BorderSubtle)
          }
        }
      }
      OrdersSection.FUNDING -> {
        if (state.history.funding.isEmpty()) {
          HistoryEmpty(state.history.stale, "No funding history", onIntent)
        } else {
          state.history.funding.forEach { payment ->
            Row(
              Modifier.fillMaxWidth().padding(vertical = 14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(Modifier.weight(1f)) {
                Text(payment.action.ifBlank { "Funding payment" })
                Text(
                  "${shortAddress(payment.market)} · size ${payment.size}",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.labelSmall,
                )
              }
              Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(
                  "${signedAmount(payment.realizedFundingAmount)} USDC",
                  color =
                    if (payment.realizedFundingAmount >= 0) {
                      FlareColors.Positive
                    } else {
                      MaterialTheme.colorScheme.error
                    },
                )
                Text(
                  formatHistoryTime(payment.transactionUnixMs),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.labelSmall,
                )
              }
            }
            HorizontalDivider(color = FlareColors.BorderSubtle)
          }
        }
      }
    }
    val canLoadMore =
      when (state.section) {
        OrdersSection.OPEN -> false
        OrdersSection.ORDERS -> state.history.ordersHasMore
        OrdersSection.TRADES -> state.history.tradesHasMore
        OrdersSection.FUNDING -> state.history.fundingHasMore
      }
    if (canLoadMore) {
      FlareButton(
        text = if (state.history.loadingMore) "Loading…" else "Load more",
        onClick = { onIntent(OrdersIntent.LoadMore) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        enabled = !state.busy && !state.history.loadingMore,
        style = FlareButtonStyle.OUTLINE,
      )
    }
  }
}

@Composable
private fun HistoryEmpty(
  stale: Boolean,
  title: String,
  onIntent: (OrdersIntent) -> Unit,
) {
  EmptyState(
    title = if (stale) "Account history is locked" else title,
    message =
      if (stale) {
        "Unlock the wallet to load private Decibel history."
      } else {
        "Completed account activity will appear here."
      },
    actionLabel = if (stale) "Unlock account" else null,
    onAction = { onIntent(OrdersIntent.Unlock) },
  )
}

private fun formatHistoryTime(unixMs: Long): String =
  Instant.fromEpochMilliseconds(unixMs).toString().replace('T', ' ').take(16) + " UTC"

private fun signedAmount(value: Double): String =
  (if (value >= 0) "+" else "−") + formatCompact(kotlin.math.abs(value))

@Composable
fun SettingsRoute(
  onOpenSetup: () -> Unit = {},
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
    viewModel.onIntent(SettingsIntent.HideSecret)
  }
  SettingsScreen(state, viewModel::onIntent, onOpenSetup, modifier)
}

@Composable
fun SettingsScreen(
  state: SettingsUiState,
  onIntent: (SettingsIntent) -> Unit,
  onOpenSetup: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(FlareColors.Canvas)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
  ) {
    FlareTopBar("Settings", subtitle = "Flare ${state.preferences.network.name.lowercase()}")
    SettingsRow("Network", "Decibel ${state.preferences.network.name.lowercase()}")
    SettingsRow("Owner wallet", state.profile.ownerAddress?.let(::shortAddress) ?: "Not configured")
    SettingsRow(
      "API wallet",
      state.profile.apiWalletAddress?.let(::shortAddress) ?: "Not configured",
    )
    SettingsRow(
      "Signing session",
      if (state.sessionRole == null) "Locked" else state.sessionRole.name.lowercase(),
    )
    SettingsRow("Proxy", state.proxyUrl)
    Text(
      "Market order slippage",
      Modifier.padding(top = 20.dp),
      style = MaterialTheme.typography.labelMedium,
    )
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      listOf(25, 50, 100).forEach { bps ->
        FlareChip(
          text = "$bps bps",
          selected = state.preferences.slippageBps == bps,
          onClick = { onIntent(SettingsIntent.SetSlippage(bps)) },
          modifier = Modifier.weight(1f),
        )
      }
    }
    SettingsRow("Dynamic color", "Disabled")
    state.error?.let {
      Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
    }
    state.revealedSecret?.let { secret ->
      Text(
        state.revealedSecretLabel.orEmpty(),
        Modifier.padding(top = 20.dp),
        style = MaterialTheme.typography.labelMedium,
      )
      SelectionContainer {
        Text(
          secret,
          Modifier.fillMaxWidth().padding(top = 8.dp),
          color = MaterialTheme.colorScheme.tertiary,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      FlareButton(
        "Hide secret",
        { onIntent(SettingsIntent.HideSecret) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        style = FlareButtonStyle.OUTLINE,
      )
    }
    Spacer(Modifier.height(20.dp))
    FlareButton(
      text = "Wallet and API setup",
      onClick = onOpenSetup,
      modifier = Modifier.fillMaxWidth(),
      style = FlareButtonStyle.OUTLINE,
    )
    if (state.profile.ownerAddress != null) {
      FlareButton(
        "Export owner recovery phrase",
        { onIntent(SettingsIntent.ExportOwner) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    if (state.profile.apiWalletAddress != null) {
      FlareButton(
        "Export API wallet",
        { onIntent(SettingsIntent.ExportApi) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    if (state.profile.ownerAddress != null || state.profile.apiWalletAddress != null) {
      FlareButton(
        "Lock wallet session",
        { onIntent(SettingsIntent.Lock) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    if (state.profile.apiWalletAddress != null) {
      FlareButton(
        "Remove API wallet from device",
        { onIntent(SettingsIntent.RemoveApi) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
    if (state.profile.ownerAddress != null) {
      FlareButton(
        "Remove owner wallet from device",
        { onIntent(SettingsIntent.RemoveOwner) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    } else if (state.profile.apiOnly) {
      Text(
        "API-wallet-only mode: deposits, withdrawals, and delegation changes require the owner wallet.",
        Modifier.padding(top = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
    Spacer(Modifier.height(24.dp))
    Text("Flare", style = MaterialTheme.typography.titleLarge)
    Text(
      "Open-source native Decibel client. Transactions are constructed and signed only through Kaptos.",
      modifier = Modifier.padding(top = 8.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@Composable
private fun SettingsRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Text(
      value,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  HorizontalDivider(color = FlareColors.BorderSubtle)
}

private fun shortAddress(address: String): String =
  if (address.length <= 18) address else address.take(10) + "…" + address.takeLast(6)

private fun TransactionState.label(): String =
  when (this) {
    TransactionState.Simulating -> "Simulating transaction"
    TransactionState.AwaitingAuthorization -> "Awaiting authorization"
    TransactionState.Submitting -> "Submitting transaction"
    is TransactionState.Pending -> "Pending · ${shortAddress(hash)}"
    is TransactionState.Committed -> "Committed · ${shortAddress(hash)}"
    is TransactionState.Failed -> "Failed · $message"
  }
