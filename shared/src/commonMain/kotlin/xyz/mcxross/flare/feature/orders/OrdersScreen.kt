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
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.CompactActionButton
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareTopBar
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
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(FlareColors.Canvas)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
  ) {
    FlareTopBar("Activity", subtitle = if (state.account.stale) "Reconnecting…" else null)
    Row(
      modifier =
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 16.dp),
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
          state.account.openOrders.isNotEmpty() ->
            state.account.openOrders.forEach { order ->
              val cancelling = state.busy && state.lastCancelOrderId == order.orderId
              Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Column(Modifier.weight(1f)) {
                  Text(
                    (state.marketSymbols[order.market] ?: shortAddress(order.market)),
                    style = MaterialTheme.typography.labelLarge,
                  )
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
          state.profile.ownerAddress != null || state.profile.apiWalletAddress != null ->
            EmptyState(
              title = if (state.account.stale) "Orders are on their way" else "No open orders",
              message =
                if (state.account.stale) {
                  "They appear as soon as your account reconnects."
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
      OrdersSection.ORDERS -> {
        if (state.history.orders.isEmpty()) {
          HistoryEmpty(state.history.stale, "No order history")
        } else {
          state.history.orders.forEach { order ->
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                  Text(order.orderDirection.ifBlank { if (order.isBuy) "Buy" else "Sell" })
                  Text(
                    "${(state.marketSymbols[order.market] ?: shortAddress(order.market))} · ${order.status}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
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
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                  Text(tradeActionLabel(trade.action))
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
