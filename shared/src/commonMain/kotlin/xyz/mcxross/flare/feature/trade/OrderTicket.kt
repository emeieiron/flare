package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.TransactionReceipt

@Composable
fun OrderTicket(state: TradeUiState, onIntent: (TradeIntent) -> Unit, onDismiss: () -> Unit) {
  val quote = state.quote ?: return
  val committed = state.transaction as? TransactionState.Committed
  val dismiss = {
    if (!state.orderBusy) {
      if (committed != null) onIntent(TradeIntent.DismissOrderReceipt)
      onDismiss()
    }
  }
  var side by rememberSaveable { mutableStateOf(OrderSide.BUY) }
  val estimate = state.orderEstimate(side)
  val inputError = state.orderInputError(side)
  var reviewing by
    rememberSaveable(
      state.sizeInput,
      state.limitPriceInput,
      state.takeProfitInput,
      state.stopLossInput,
      state.orderType,
      state.leverage,
      side,
    ) {
      mutableStateOf(false)
    }
  FlareSheet(
    if (committed != null) "Order placed"
    else if (reviewing) "Review order" else "Trade ${quote.market.symbol}",
    dismiss,
  ) {
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
      if (committed != null) {
        TransactionReceipt(
          "Your order was submitted. Follow fills and open orders in Activity.",
          committed.hash,
          dismiss,
          enabled = !state.orderBusy,
        )
      } else if (reviewing) {
        OrderReview(state, side)
        inputError?.let {
          Text(it, color = FlareColors.Negative, modifier = Modifier.padding(bottom = 12.dp))
        }
        FlareButton(
          if (state.orderBusy) "Placing order…"
          else "Confirm ${if (side == OrderSide.BUY) "buy" else "sell"}",
          {
            onIntent(TradeIntent.Submit(side))
            reviewing = false
          },
          Modifier.fillMaxWidth(),
          enabled = state.tradingEnabled && !state.orderBusy && inputError == null,
        )
        TextButton({ reviewing = false }, Modifier.fillMaxWidth(), enabled = !state.orderBusy) {
          Text("Edit order")
        }
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OrderSide.entries.forEach { value ->
            FlareChip(
              if (value == OrderSide.BUY) "Buy / Long" else "Sell / Short",
              side == value,
              { side = value },
              Modifier.weight(1f),
              semanticColor =
                if (value == OrderSide.BUY) FlareColors.Positive else FlareColors.Negative,
              enabled = !state.orderBusy,
            )
          }
        }
        if (
          state.expectedSignerAddress != null &&
            !state.expectedSignerAddress.equals(state.sessionSignerAddress, ignoreCase = true)
        ) {
          Text(
            "Unlock the selected trading wallet before submitting.",
            Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
          )
          FlareButton(
            "Unlock trading wallet",
            { onIntent(TradeIntent.Unlock) },
            Modifier.fillMaxWidth().padding(top = 8.dp),
            enabled = !state.orderBusy,
            style = FlareButtonStyle.OUTLINE,
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          listOf(OrderType.MARKET, OrderType.LIMIT).forEach { type ->
            FlareChip(
              text = type.name.lowercase().replaceFirstChar(Char::uppercase),
              selected = state.orderType == type,
              onClick = { onIntent(TradeIntent.SetOrderType(type)) },
              modifier = Modifier.weight(1f),
              enabled = !state.orderBusy,
            )
          }
        }
        OrderAmountField(
          "Size",
          quote.market.symbol,
          state.sizeInput,
          { onIntent(TradeIntent.SetSize(it)) },
          Modifier.fillMaxWidth().padding(top = 16.dp),
          enabled = !state.orderBusy,
        )
        Text(
          "Minimum ${quote.market.minSize.toDecimalString(quote.market.sizeDecimals)} ${quote.market.symbol}",
          style = MaterialTheme.typography.labelSmall,
          color = FlareColors.TextSecondary,
          modifier = Modifier.padding(top = 6.dp),
        )
        LeverageControl(state, estimate?.margin) { onIntent(TradeIntent.SetLeverage(it)) }
        if (state.orderType == OrderType.LIMIT) {
          OrderAmountField(
            "Limit price",
            "USDC",
            state.limitPriceInput,
            { onIntent(TradeIntent.SetLimitPrice(it)) },
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            enabled = !state.orderBusy,
          )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OrderAmountField(
            "Take profit",
            "USDC",
            state.takeProfitInput,
            { onIntent(TradeIntent.SetTakeProfit(it)) },
            Modifier.weight(1f),
            enabled = !state.orderBusy,
            optional = true,
          )
          OrderAmountField(
            "Stop loss",
            "USDC",
            state.stopLossInput,
            { onIntent(TradeIntent.SetStopLoss(it)) },
            Modifier.weight(1f),
            enabled = !state.orderBusy,
            optional = true,
          )
        }
        Text(
          "Optional exits, included with your order.",
          style = MaterialTheme.typography.bodySmall,
          color = FlareColors.TextSecondary,
          modifier = Modifier.padding(top = 8.dp),
        )
        if (state.sizeInput.isNotBlank() && inputError != null) {
          Text(
            inputError,
            color = FlareColors.Negative,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
          )
        }
        (state.transaction
            ?: state.leverageTransaction?.takeUnless { it is TransactionState.Committed })
          ?.let { transaction ->
            Text(
              (if (state.transaction == null) "Leverage · " else "") + transaction.ticketLabel(),
              modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelMedium,
            )
            if (transaction is TransactionState.Failed)
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
                  { onIntent(TradeIntent.ConfirmSelfPay) },
                  Modifier.fillMaxWidth().padding(top = 8.dp),
                  enabled = !state.orderBusy,
                  style = FlareButtonStyle.OUTLINE,
                )
                if (state.apiWalletNeedsTopUp)
                  state.suggestedTopUpOctas?.let { amount ->
                    Text(
                      "The API wallet does not have enough APT for this fallback. " +
                        "Transfer ${amount.toDecimalString(8)} APT from the owner wallet, then unlock the API wallet again.",
                      Modifier.padding(top = 12.dp),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      style = MaterialTheme.typography.bodyMedium,
                    )
                    FlareButton(
                      "Top up API wallet",
                      { onIntent(TradeIntent.TopUpApiWallet) },
                      Modifier.fillMaxWidth().padding(top = 8.dp),
                      enabled = !state.orderBusy,
                      style = FlareButtonStyle.OUTLINE,
                    )
                  }
              }
          }
        state.topUpTransaction?.let { transaction ->
          Text(
            "API-wallet top-up · ${transaction.ticketLabel()}",
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            color =
              if (transaction is TransactionState.Failed) {
                MaterialTheme.colorScheme.error
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            style = MaterialTheme.typography.labelMedium,
          )
        }
        state.orderError?.let { error ->
          Text(
            error,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
          )
        }

        if (!state.tradingEnabled && !state.orderBusy) {
          Text(
            "Trading requires an unlocked account and a live market connection.",
            Modifier.padding(top = 16.dp),
            color = FlareColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        FlareButton(
          "Review order",
          { reviewing = true },
          Modifier.fillMaxWidth().padding(top = 24.dp),
          enabled =
            state.tradingEnabled &&
              state.sizeInput.isNotBlank() &&
              inputError == null &&
              !state.orderBusy &&
              (state.orderType != OrderType.LIMIT || state.limitPriceInput.isNotBlank()),
        )
      }
    }
  }
}

private fun TransactionState.ticketLabel(): String =
  when (this) {
    TransactionState.Simulating -> "Checking order"
    TransactionState.AwaitingAuthorization -> "Awaiting device authorization"
    TransactionState.Submitting -> "Submitting order"
    is TransactionState.Pending -> "Pending · ${hash.take(10)}…${hash.takeLast(6)}"
    is TransactionState.Committed -> "Order placed · ${hash.take(10)}…${hash.takeLast(6)}"
    is TransactionState.Failed -> "Order failed · $message"
  }
