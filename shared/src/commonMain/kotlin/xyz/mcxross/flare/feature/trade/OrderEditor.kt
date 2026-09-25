package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.valentinilk.shimmer.shimmer
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.ExitPriceFields
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSkeletonBox
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.rememberFlareShimmer

@Composable
internal fun OrderEditor(state: TradeUiState, side: OrderSide, onSideChange: (OrderSide) -> Unit, onIntent: (TradeIntent) -> Unit) {
  val quote = state.quote ?: return
  val isSpot = quote.market.assetType == AssetType.SPOT
  val estimate = state.orderEstimate(side)
  val inputError = state.orderInputError(side)
  var showExits by rememberSaveable { mutableStateOf(false) }
  Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OrderSide.entries.forEach { value ->
        FlareChip(
          if (isSpot) {
            if (value == OrderSide.BUY) "Buy" else "Sell"
          } else {
            if (value == OrderSide.BUY) "Buy / Long" else "Sell / Short"
          },
          side == value,
          { onSideChange(value) },
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
      val orderTypes =
        if (isSpot) listOf(OrderType.MARKET, OrderType.LIMIT)
        else listOf(OrderType.MARKET, OrderType.LIMIT, OrderType.TWAP)
      orderTypes.forEach { type ->
        FlareChip(
          text = if (type == OrderType.TWAP) "TWAP" else type.name.lowercase().replaceFirstChar(Char::uppercase),
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
    FlowRow(
      modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
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
    if (state.orderType == OrderType.TWAP) {
      Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OrderAmountField(
          "Duration",
          "min",
          state.twapDurationMinutesInput,
          { onIntent(TradeIntent.SetTwapDurationMinutes(it)) },
          Modifier.weight(1f),
          enabled = !state.orderBusy,
        )
        OrderAmountField(
          "Interval",
          "min",
          state.twapFrequencyMinutesInput,
          { onIntent(TradeIntent.SetTwapFrequencyMinutes(it)) },
          Modifier.weight(1f),
          enabled = !state.orderBusy,
        )
      }
      Text(
        "TWAP divides the order into regular slices across the duration.",
        style = MaterialTheme.typography.bodySmall,
        color = FlareColors.TextSecondary,
        modifier = Modifier.padding(top = 6.dp),
      )
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
    if (!isSpot && state.orderType != OrderType.TWAP) {
      val hasExits = state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank()
      TextButton(
        { showExits = !showExits },
        enabled = !state.orderBusy,
        contentPadding = PaddingValues(0.dp),
      ) {
        Text(if (showExits || hasExits) "Take profit / stop loss" else "Add take profit / stop loss")
      }
      if (showExits || hasExits) {
        ExitPriceFields(
          state.takeProfitInput, state.stopLossInput,
          { onIntent(TradeIntent.SetTakeProfit(it)) },
          { onIntent(TradeIntent.SetStopLoss(it)) },
          enabled = !state.orderBusy,
        )
      }
    }
    if (state.sizeInput.isNotBlank() && inputError != null) {
      ActionNotice(inputError, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
    }
    state.orderError?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
    if (!state.tradingEnabled) {
      OrderConnectionNotice()
    }
  }
}

@Composable
internal fun OrderReviewStatus(state: TradeUiState, side: OrderSide, onIntent: (TradeIntent) -> Unit) {
  val inputError = state.orderInputError(side)
  Column {
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
    val hasNotices = inputError != null || state.orderError != null ||
      (state.transaction as? TransactionState.Failed)?.selfPayEstimateOctas != null
    if (hasNotices) Spacer(Modifier.height(20.dp))
    OrderReview(state, side)
    if (!state.tradingEnabled) {
      OrderConnectionNotice()
    }
  }
}

@Composable
private fun OrderConnectionNotice() {
  Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FlareSkeletonBox(Modifier.width(40.dp).height(3.dp).shimmer(rememberFlareShimmer()))
    Text("Reconnecting to live prices. Trading resumes automatically.",
      color = FlareColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
  }
}
