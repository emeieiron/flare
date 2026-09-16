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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.NoticeTone
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
  val isSpot = quote.market.assetType == AssetType.SPOT
  FlareSheet(
    if (committed != null) "Order placed"
    else if (reviewing) "Review order"
    else if (isSpot) "Trade ${quote.market.name}" else "Trade ${quote.market.symbol}",
    dismiss,
  ) {
    if (committed != null) {
      TransactionReceipt(
        "Your order is with the market. Follow fills and open orders in Activity.",
        committed.hash,
        dismiss,
        enabled = !state.orderBusy,
      )
      return@FlareSheet
    }
    if (reviewing) {
      Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
        OrderReview(state, side)
      }
      inputError?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
      state.orderError?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
      (state.transaction as? TransactionState.Failed)?.selfPayEstimateOctas?.let { estimate ->
        ActionNotice(
          "Flare can’t cover the network fee right now. Your wallet would pay about " +
            "${estimate.toDecimalString(8)} APT.",
          Modifier.padding(top = 12.dp),
        )
        FlareButton(
          "Pay the fee and continue",
          { onIntent(TradeIntent.ConfirmSelfPay) },
          Modifier.fillMaxWidth().padding(top = 8.dp),
          enabled = !state.orderBusy,
          style = FlareButtonStyle.OUTLINE,
        )
        if (state.apiWalletNeedsTopUp)
          state.suggestedTopUpOctas?.let { amount ->
            ActionNotice(
              "This device needs ${amount.toDecimalString(8)} APT to pay the fee itself.",
              Modifier.padding(top = 12.dp),
            )
            FlareButton(
              "Send APT from your wallet",
              { onIntent(TradeIntent.TopUpApiWallet) },
              Modifier.fillMaxWidth().padding(top = 8.dp),
              enabled = !state.orderBusy,
              style = FlareButtonStyle.OUTLINE,
            )
          }
      }
      FlareButton(
        if (state.orderBusy) "Placing your order…"
        else "Confirm ${if (side == OrderSide.BUY) "buy" else "sell"}",
        { onIntent(TradeIntent.Submit(side)) },
        Modifier.fillMaxWidth().padding(top = 16.dp),
        enabled = state.tradingEnabled && inputError == null,
        working = state.orderBusy,
      )
      TextButton({ reviewing = false }, Modifier.fillMaxWidth(), enabled = !state.orderBusy) {
        Text("Edit order")
      }
      return@FlareSheet
    }
    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrderSide.entries.forEach { value ->
          FlareChip(
            if (isSpot) {
              if (value == OrderSide.BUY) "Buy" else "Sell"
            } else {
              if (value == OrderSide.BUY) "Buy / Long" else "Sell / Short"
            },
            side == value,
            { side = value },
            Modifier.weight(1f),
            semanticColor =
              if (value == OrderSide.BUY) FlareColors.Positive else FlareColors.Negative,
            enabled = !state.orderBusy,
          )
        }
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
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "Minimum ${quote.market.minSize.toDecimalString(quote.market.sizeDecimals)} ${quote.market.symbol}",
          style = MaterialTheme.typography.labelSmall,
          color = FlareColors.TextSecondary,
        )
        state.availableDisplay(side)?.let { available ->
          Text(
            "Available $available",
            style = MaterialTheme.typography.labelSmall,
            color = FlareColors.TextSecondary,
          )
        }
      }
      if (!isSpot) {
        LeverageControl(state, estimate?.margin) { onIntent(TradeIntent.SetLeverage(it)) }
      }
      if (state.orderType == OrderType.LIMIT) {
        OrderAmountField(
          "Limit price",
          "USDC",
          state.limitPriceInput,
          { onIntent(TradeIntent.SetLimitPrice(it)) },
          Modifier.fillMaxWidth()
            .padding(top = if (isSpot) 16.dp else 0.dp, bottom = if (isSpot) 0.dp else 16.dp),
          enabled = !state.orderBusy,
        )
      }
      if (!isSpot) {
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
      }
    }
    if (state.sizeInput.isNotBlank() && inputError != null) {
      ActionNotice(inputError, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
    }
    state.orderError?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
    if (!state.tradingEnabled) {
      ActionNotice(
        "Reconnecting to live prices. Trading resumes automatically.",
        Modifier.padding(top = 12.dp),
        NoticeTone.PROGRESS,
      )
    }
    FlareButton(
      "Review order",
      { reviewing = true },
      Modifier.fillMaxWidth().padding(top = 16.dp),
      enabled =
        state.tradingEnabled &&
          state.sizeInput.isNotBlank() &&
          inputError == null &&
          !state.orderBusy &&
          (state.orderType != OrderType.LIMIT || state.limitPriceInput.isNotBlank()),
    )
  }
}
