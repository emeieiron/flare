package xyz.mcxross.flare.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.formatRelativeTime
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.CompactActionButton
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSegmentedControl
import xyz.mcxross.flare.design.FlareTopBar
import xyz.mcxross.flare.design.InstrumentBadge
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.shortAddress

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
  var historySelected by rememberSaveable { mutableStateOf(state.section !in listOf(OrdersSection.OPEN, OrdersSection.TWAP)) }
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(FlareColors.Canvas)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
  ) {
    FlareTopBar("Activity", subtitle = when {
      state.account.error != null -> "Account data unavailable"
      state.account.stale -> "Connecting…"
      else -> null
    })
    FlareSegmentedControl(
      listOf(false, true), historySelected,
      { history ->
        historySelected = history
        onIntent(OrdersIntent.SelectSection(if (history) OrdersSection.ORDERS else OrdersSection.OPEN))
      }, { if (it) "History" else "Open" }, Modifier.padding(bottom = 12.dp),
    )
    Row(
      modifier =
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val sections = if (historySelected)
        listOf(OrdersSection.ORDERS, OrdersSection.TRADES, OrdersSection.FUNDING, OrdersSection.TRANSFERS, OrdersSection.TWAP)
        else listOf(OrdersSection.OPEN, OrdersSection.TWAP)
      sections.forEach { section ->
        FlareChip(
          text = if (section == OrdersSection.OPEN) "Orders" else section.label,
          selected = state.section == section,
          onClick = { onIntent(OrdersIntent.SelectSection(section)) },
        )
      }
    }
    val readError = if (historySelected) state.history.error else state.account.error
    if (readError != null) {
      ActionNotice("Account data couldn’t be loaded. Check your connection and account access.",
        Modifier.padding(bottom = 8.dp), NoticeTone.ALERT)
      androidx.compose.material3.TextButton({ onIntent(OrdersIntent.RetryAccount) }) { Text("Retry") }
    }
    state.error?.let { ActionNotice(it, Modifier.padding(bottom = 12.dp), NoticeTone.ALERT) }
    (state.transaction as? TransactionState.Failed)?.selfPayEstimateOctas?.let { estimate ->
      ActionNotice(
        "Flare can’t cover the network fee right now. Your wallet would pay about " +
          "${estimate.toDecimalString(8)} APT.",
        Modifier.padding(bottom = 8.dp),
      )
      FlareButton(
        "Pay the fee and continue",
        { onIntent(OrdersIntent.ConfirmSelfPay) },
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
      if (state.apiWalletNeedsTopUp)
        state.suggestedTopUpOctas?.let { amount ->
          ActionNotice(
            "This device needs ${amount.toDecimalString(8)} APT to pay the fee itself.",
            Modifier.padding(bottom = 8.dp),
          )
          FlareButton(
            "Send APT from your wallet",
            { onIntent(OrdersIntent.TopUpApiWallet) },
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            enabled = !state.busy,
            style = FlareButtonStyle.OUTLINE,
          )
        }
    }
    when (state.section) {
      OrdersSection.OPEN ->
        when {
          state.account.openOrders.isNotEmpty() -> {
            Row(
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                "${state.account.openOrders.size} open ${if (state.account.openOrders.size == 1) "order" else "orders"}",
                style = MaterialTheme.typography.labelSmall,
                color = FlareColors.TextSecondary,
              )
              CompactActionButton(
                text = "Cancel all",
                positive = false,
                onClick = { onIntent(OrdersIntent.CancelAll) },
                enabled = !state.busy,
              )
            }
            state.account.openOrders.forEach { order ->
              val cancelling = state.busy && state.lastCancelOrderId == order.orderId
              val isSpot = order.assetType == AssetType.SPOT || order.market in state.spotMarkets
              Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Column(Modifier.weight(1f)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    Text(
                      (state.marketSymbols[order.market] ?: shortAddress(order.market)),
                      style = MaterialTheme.typography.labelLarge,
                    )
                    if (isSpot) {
                      InstrumentBadge("SPOT")
                    }
                  }
                  OrderConditions(order)
                  Text(
                    "${if (order.isBuy) "Buy" else "Sell"} " +
                      "${order.remainingSize?.let { formatQuantity(it) } ?: "—"} · " +
                      (order.price?.let { "at ${formatPrice(it)}" } ?: "at market"),
                    color = FlareColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
                CompactActionButton(
                  text = if (cancelling) "Cancelling…" else "Cancel",
                  positive = false,
                  onClick = {
                    onIntent(OrdersIntent.Cancel(order.market, order.orderId, order.isTpSl))
                  },
                  enabled = !state.busy,
                )
              }
              HorizontalDivider(color = FlareColors.BorderSubtle)
            }
          }
          state.profile.ownerAddress != null || state.profile.apiWalletAddress != null ->
            EmptyState(
              title = when {
                state.account.error != null -> "Orders unavailable"
                state.account.stale -> "Loading orders"
                else -> "No open orders"
              },
              message =
                if (state.account.stale) {
                  "Your orders will appear when account access is restored."
                } else {
                  "Working orders will appear here after submission."
                },
            )
          else ->
            EmptyState(
              title = "No account orders",
              message = "Connect your account to see orders, trades, and funding in one place.",
              actionLabel = "Connect account",
              onAction = onOpenSetup,
            )
        }
      OrdersSection.TWAP ->
        when {
          (!historySelected && state.activeTwaps.isNotEmpty()) || (historySelected && state.twapHistory.isNotEmpty()) -> {
            if (!historySelected && state.activeTwaps.isNotEmpty()) {
              Text(
                "Active TWAPs",
                style = MaterialTheme.typography.titleSmall,
                color = FlareColors.TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp),
              )
              state.activeTwaps.forEach { twap ->
                val cancelling = state.busy && state.lastCancelTwapId == twap.twapId
                Row(
                  Modifier.fillMaxWidth().padding(vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Column(Modifier.weight(1f)) {
                    Text(
                      state.marketSymbols[twap.market] ?: shortAddress(twap.market),
                      style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                      "${if (twap.isBuy) "Buy" else "Sell"} · " +
                        "Interval: ${twap.frequencySeconds}s · Duration: ${twap.durationSeconds / 60}m",
                      color = FlareColors.TextSecondary,
                      style = MaterialTheme.typography.labelSmall,
                    )
                  }
                  CompactActionButton(
                    text = if (cancelling) "Cancelling…" else "Cancel",
                    positive = false,
                    onClick = {
                      onIntent(OrdersIntent.CancelTwap(twap.market, twap.twapId))
                    },
                    enabled = !state.busy,
                  )
                }
                HorizontalDivider(color = FlareColors.BorderSubtle)
              }
            }
            if (historySelected && state.twapHistory.isNotEmpty()) {
              Text(
                "Past TWAPs",
                style = MaterialTheme.typography.titleSmall,
                color = FlareColors.TextSecondary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
              )
              state.twapHistory.forEach { twap ->
                Row(
                  Modifier.fillMaxWidth().padding(vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Column(Modifier.weight(1f)) {
                    Text(
                      state.marketSymbols[twap.market] ?: shortAddress(twap.market),
                      style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                      "${if (twap.isBuy) "Buy" else "Sell"} · Status: ${twap.status ?: "Finished"}",
                      color = FlareColors.TextSecondary,
                      style = MaterialTheme.typography.labelSmall,
                    )
                  }
                }
                HorizontalDivider(color = FlareColors.BorderSubtle)
              }
            }
          }
          else ->
            EmptyState(
              title = "No TWAP orders",
              message = "TWAP orders execute automatically across regular time intervals.",
            )
        }
      OrdersSection.ORDERS -> {
        if (state.history.orders.isEmpty()) {
          HistoryEmpty(state.history.stale, "No order history")
        } else {
          state.history.orders.forEach { order ->
            val isSpot = order.assetType == AssetType.SPOT || order.market in state.spotMarkets
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    Text(order.orderDirection.ifBlank { if (order.isBuy) "Buy" else "Sell" })
                    if (isSpot) {
                      InstrumentBadge("SPOT")
                    }
                  }
                  Text(
                    "${(state.marketSymbols[order.market] ?: shortAddress(order.market))} · ${order.status}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
                  OrderConditions(order)
                  order.originalSize?.let { size ->
                    Text("${formatQuantity(size)} ordered · ${order.remainingSize?.let { formatQuantity(it) } ?: "—"} remaining",
                      style = MaterialTheme.typography.labelSmall, color = FlareColors.TextSecondary)
                  }

                }
                Column(horizontalAlignment = Alignment.End) {
                  Text(order.price?.let(::formatPrice) ?: "Market")
                  Text(
                    formatRelativeTime(order.unixMs),
                    color = FlareColors.TextSecondary,
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
          HistoryEmpty(state.history.stale, "No trade history")
        } else {
          state.history.trades.forEach { trade ->
            val isSpot = trade.assetType == AssetType.SPOT || trade.market in state.spotMarkets
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    Text(tradeActionLabel(trade.action))
                    if (isSpot) {
                      InstrumentBadge("SPOT")
                    }
                  }
                  Text(
                    "${(state.marketSymbols[trade.market] ?: shortAddress(trade.market))} · ${formatQuantity(trade.size)} @ ${formatPrice(trade.price)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
                Column(horizontalAlignment = Alignment.End) {
                  if (trade.realizedPnlAmount != 0.0)
                    Text(
                      signedAmount(trade.realizedPnlAmount),
                      color =
                        if (trade.realizedPnlAmount > 0) FlareColors.Positive
                        else FlareColors.Negative,
                      style = MaterialTheme.typography.labelMedium,
                    )
                  Text(
                    formatRelativeTime(trade.transactionUnixMs),
                    color = FlareColors.TextSecondary,
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
          HistoryEmpty(state.history.stale, "No funding history")
        } else {
          state.history.funding.forEach { payment ->
            Row(
              Modifier.fillMaxWidth().padding(vertical = 14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(Modifier.weight(1f)) {
                Text(payment.action.ifBlank { "Funding payment" })
                Text(
                  "${state.marketSymbols[payment.market] ?: shortAddress(payment.market)} · ${formatQuantity(payment.size)}",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.labelSmall,
                )
              }
              Column(horizontalAlignment = Alignment.End) {
                Text(
                  signedAmount(payment.realizedFundingAmount),
                  color =
                    if (payment.realizedFundingAmount >= 0) FlareColors.Positive
                    else FlareColors.Negative,
                )
                Text(
                  formatRelativeTime(payment.transactionUnixMs),
                  color = FlareColors.TextSecondary,
                  style = MaterialTheme.typography.labelSmall,
                )
              }
            }
            HorizontalDivider(color = FlareColors.BorderSubtle)
          }
        }
      }
      OrdersSection.TRANSFERS -> {
        if (state.history.transfers.isEmpty()) {
          HistoryEmpty(state.history.stale, "No transfer history")
        } else {
          state.history.transfers.forEach { transfer ->
            val isDeposit = transfer.type.lowercase().contains("deposit")
            Row(
              Modifier.fillMaxWidth().padding(vertical = 14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(Modifier.weight(1f)) {
                Text(if (isDeposit) "Deposit" else "Withdrawal")
                Text(
                  "${transfer.assetSymbol} · ${transfer.status.replaceFirstChar { it.uppercase() }}" +
                    (transfer.transactionHash?.let { " · ${shortAddress(it)}" } ?: ""),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.labelSmall,
                )
              }
              Column(horizontalAlignment = Alignment.End) {
                Text(
                  (if (isDeposit) "+" else "-") + formatBalance(transfer.amount),
                  color = if (isDeposit) FlareColors.Positive else FlareColors.Negative,
                )
                if (transfer.timestamp > 0) {
                  Text(
                    formatRelativeTime(transfer.timestamp),
                    color = FlareColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
              }
            }
            HorizontalDivider(color = FlareColors.BorderSubtle)
          }
        }
      }
    }
    val canLoadMore =
      when (state.section) {
        OrdersSection.OPEN, OrdersSection.TWAP -> false
        OrdersSection.ORDERS -> state.history.ordersHasMore
        OrdersSection.TRADES -> state.history.tradesHasMore
        OrdersSection.FUNDING -> state.history.fundingHasMore
        OrdersSection.TRANSFERS -> state.history.transfersHasMore
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
private fun HistoryEmpty(stale: Boolean, title: String) {
  EmptyState(
    title = if (stale) "History is on its way" else title,
    message =
      if (stale) {
        "It appears as soon as your account reconnects."
      } else {
        "Completed account activity will appear here."
      },
  )
}

private fun signedAmount(value: Double): String =
  (if (value > 0) "+" else "") + formatBalance(value)

private fun tradeActionLabel(action: String): String =
  when (action) {
    "OpenLong" -> "Opened long"
    "CloseLong" -> "Closed long"
    "OpenShort" -> "Opened short"
    "CloseShort" -> "Closed short"
    else -> action.ifBlank { "Fill" }
  }

@Composable
private fun OrderConditions(order: Order) {
  val conditions = buildList {
    order.orderType.takeIf { it.isNotBlank() }?.let { add(it.lowercase().replace('_', ' ')) }
    order.takeProfitTriggerPrice?.let { add("TP ${formatPrice(it)}") }
    order.stopLossTriggerPrice?.let { add("SL ${formatPrice(it)}") }
    if (order.isReduceOnly) add("Reduce only")
  }
  if (conditions.isNotEmpty()) Text(conditions.joinToString(" · "),
    style = MaterialTheme.typography.labelSmall, color = FlareColors.TextSecondary)
}
