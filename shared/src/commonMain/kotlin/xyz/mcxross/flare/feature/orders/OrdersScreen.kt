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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Instant
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareTopBar

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
    FlareTopBar(
      "Activity",
      subtitle =
        if (state.account.stale) {
          "Orders, trades & funding"
        } else {
          "Your latest account activity"
        },
    )
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
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.history.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.transaction
      ?.takeIf { state.section == OrdersSection.OPEN }
      ?.let { transaction ->
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
                    Text(
                      (state.marketSymbols[order.market] ?: shortAddress(order.market)),
                      style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                      "${if (order.isBuy) "Buy" else "Sell"} · ${order.orderType.ifBlank { order.status }}",
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      style = MaterialTheme.typography.labelSmall,
                    )
                  }
                  Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(
                      order.remainingSize?.let { formatQuantity(it) } ?: "—",
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
                  "Unlock your account to see current orders."
                } else {
                  "Working orders will appear here after submission."
                },
              actionLabel = if (state.account.stale) "Unlock account" else null,
              onAction = { onIntent(OrdersIntent.Unlock) },
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
          HistoryEmpty(state.history.stale, "No order history", onIntent)
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
                  Text(tradeActionLabel(trade.action))
                  Text(
                    "${(state.marketSymbols[trade.market] ?: shortAddress(trade.market))} · ${formatQuantity(trade.size)} @ ${formatPrice(trade.price)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                  Text(
                    "PnL ${signedAmount(trade.realizedPnlAmount)}",
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
                  "${state.marketSymbols[payment.market] ?: shortAddress(payment.market)} · ${formatQuantity(payment.size)}",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.labelSmall,
                )
              }
              Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(
                  signedAmount(payment.realizedFundingAmount),
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
  (if (value > 0) "+" else "") + formatBalance(value)

private fun tradeActionLabel(action: String): String =
  when (action) {
    "OpenLong" -> "Opened long"
    "CloseLong" -> "Closed long"
    "OpenShort" -> "Opened short"
    "CloseShort" -> "Closed short"
    else -> action.ifBlank { "Fill" }
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
