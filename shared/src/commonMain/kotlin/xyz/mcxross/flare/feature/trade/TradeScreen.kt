package xyz.mcxross.flare.feature.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import xyz.mcxross.flare.data.ChartRange
import xyz.mcxross.flare.data.formatCompact
import xyz.mcxross.flare.data.formatPercent
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.MarketTrade
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.AssetHeader
import xyz.mcxross.flare.design.BottomTradeDock
import xyz.mcxross.flare.design.EmptyState
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareChip
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.IndicatorChip
import xyz.mcxross.flare.design.TimeRangeSelector

@Composable
fun TradeRoute(
  marketAddress: String?,
  modifier: Modifier = Modifier,
  viewModel: TradeViewModel = koinViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(marketAddress) {
    viewModel.onIntent(TradeIntent.SelectMarket(marketAddress))
  }
  TradeScreen(state, viewModel::onIntent, modifier)
}

@Composable
fun TradeScreen(
  state: TradeUiState,
  onIntent: (TradeIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val quote = state.quote
  if (quote == null) {
    EmptyState(
      title = "Select a market",
      message = state.error ?: "Open Markets and choose a Decibel perpetual to inspect.",
      modifier = modifier.fillMaxSize().background(FlareColors.Canvas),
      actionLabel = if (state.error != null) "Retry" else null,
      onAction = { onIntent(TradeIntent.Retry) },
    )
    return
  }

  Column(modifier = modifier.fillMaxSize().background(FlareColors.Canvas)) {
    Column(
      modifier =
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
      Spacer(Modifier.height(8.dp))
      AssetHeader(
        symbol = quote.market.symbol,
        name = quote.market.name,
        price = formatPrice(quote.markPrice),
        delta = formatPercent(quote.changePercent24h),
        positive = quote.changePercent24h >= 0,
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        ContextValue("24h volume", formatCompact(quote.volume24h), Modifier.weight(1f))
        ContextValue("Open interest", formatCompact(quote.openInterest), Modifier.weight(1f))
        ContextValue("Max leverage", "${quote.market.maxLeverage}×", Modifier.weight(1f))
      }
      Row(
        modifier =
          Modifier.fillMaxWidth().padding(top = 20.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        TimeRangeSelector(
          values = ChartRange.entries,
          selected = state.range,
          label = ChartRange::label,
          onSelected = { onIntent(TradeIntent.SelectRange(it)) },
        )
      }
      Row(
        modifier =
          Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        ChartStyle.entries.forEach { style ->
          FlareChip(
            text = if (style == ChartStyle.LINE) "Line" else "Candles",
            selected = style == state.chartStyle,
            onClick = { onIntent(TradeIntent.SelectChartStyle(style)) },
          )
        }
        IndicatorChip(
          text = "RSI",
          selected = state.showRsi,
          onClick = { onIntent(TradeIntent.ToggleRsi) },
          color = FlareColors.IndicatorCyan,
        )
        IndicatorChip(
          text = "MACD",
          selected = state.showMacd,
          onClick = { onIntent(TradeIntent.ToggleMacd) },
          color = FlareColors.IndicatorOrange,
        )
      }
      if (state.chartLoading && state.candles.isEmpty()) {
        Column(
          modifier = Modifier.fillMaxWidth().height(300.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
      } else {
        FlareChartStack(
          candles = state.candles,
          style = state.chartStyle,
          showRsi = state.showRsi,
          showMacd = state.showMacd,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      if (state.stale || state.error != null) {
        Text(
          text = state.error ?: "Chart snapshot is stale",
          modifier =
            Modifier.fillMaxWidth()
              .background(FlareColors.Surface, MaterialTheme.shapes.small)
              .padding(12.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      }
      Spacer(Modifier.height(20.dp))
      Text("Market context", style = MaterialTheme.typography.titleLarge)
      HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = FlareColors.BorderSubtle,
      )
      Text(
        if (state.marketDetails.stale) {
          state.marketDetails.error ?: "Order book snapshot is stale"
        } else {
          "Live order book · best bid ${state.marketDetails.orderBook?.bestBid ?: "—"} · best ask ${state.marketDetails.orderBook?.bestAsk ?: "—"}"
        },
        color =
          if (state.marketDetails.stale) MaterialTheme.colorScheme.error
          else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OrderBookSide(
          label = "Bids",
          levels = state.marketDetails.orderBook?.bids.orEmpty().take(5),
          modifier = Modifier.weight(1f),
        )
        OrderBookSide(
          label = "Asks",
          levels = state.marketDetails.orderBook?.asks.orEmpty().take(5),
          modifier = Modifier.weight(1f),
        )
      }
      Text(
        "Recent trades",
        modifier = Modifier.padding(top = 20.dp),
        style = MaterialTheme.typography.labelMedium,
      )
      if (state.marketDetails.recentTrades.isEmpty()) {
        Text(
          "No recent trades",
          modifier = Modifier.padding(top = 8.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      } else {
        state.marketDetails.recentTrades.take(8).forEach { trade ->
          RecentTradeRow(trade)
        }
      }
      Spacer(Modifier.height(28.dp))
      Text("Order ticket", style = MaterialTheme.typography.titleLarge)
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
        OrderType.entries.forEach { type ->
          FlareChip(
            text = type.name.lowercase().replaceFirstChar(Char::uppercase),
            selected = state.orderType == type,
            onClick = { onIntent(TradeIntent.SetOrderType(type)) },
            modifier = Modifier.weight(1f),
          )
        }
      }
      OutlinedTextField(
        value = state.sizeInput,
        onValueChange = { onIntent(TradeIntent.SetSize(it)) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        label = { Text("Size (${quote.market.symbol})") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      )
      if (state.orderType == OrderType.LIMIT) {
        OutlinedTextField(
          value = state.limitPriceInput,
          onValueChange = { onIntent(TradeIntent.SetLimitPrice(it)) },
          modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
          label = { Text("Limit price (USDC)") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
      } else {
        Text(
          "IOC limit derived from the live book · ${state.slippageBps} bps maximum slippage",
          modifier = Modifier.padding(top = 10.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      }
      state.transaction?.let { transaction ->
        Text(
          transaction.ticketLabel(),
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
      Spacer(Modifier.height(28.dp))
    }
    BottomTradeDock(
      quantity = "${state.sizeInput.ifBlank { "0" }} ${quote.market.symbol}",
      enabled = state.tradingEnabled && state.sizeInput.isNotBlank() && !state.orderBusy,
      onBuy = { onIntent(TradeIntent.Submit(OrderSide.BUY)) },
      onSell = { onIntent(TradeIntent.Submit(OrderSide.SELL)) },
      onQuantity = {},
    )
  }
}

@Composable
private fun RecentTradeRow(trade: MarketTrade) {
  val isBuy = trade.action.equals("buy", ignoreCase = true)
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      if (isBuy) "B · Buy" else "S · Sell",
      modifier = Modifier.weight(1f),
      color = if (isBuy) FlareColors.Positive else FlareColors.Negative,
      style = MaterialTheme.typography.labelSmall,
    )
    Text(
      formatPrice(trade.price),
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.labelSmall,
    )
    Text(
      trade.size.toString(),
      modifier = Modifier.weight(1f),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

@Composable
private fun OrderBookSide(
  label: String,
  levels: List<List<String>>,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    levels.forEach { level ->
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(level.getOrNull(0) ?: "—", style = MaterialTheme.typography.labelSmall)
        Text(
          level.getOrNull(1) ?: "—",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    if (levels.isEmpty()) {
      Text(
        "—",
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun TransactionState.ticketLabel(): String =
  when (this) {
    TransactionState.Simulating -> "Simulating against Aptos"
    TransactionState.AwaitingAuthorization -> "Awaiting device authorization"
    TransactionState.Submitting -> "Submitting through Kaptos"
    is TransactionState.Pending -> "Pending · ${hash.take(10)}…${hash.takeLast(6)}"
    is TransactionState.Committed -> "Order committed · ${hash.take(10)}…${hash.takeLast(6)}"
    is TransactionState.Failed -> "Order failed · $message"
  }

@Composable
private fun ContextValue(label: String, value: String, modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(
      label,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
    Text(value, style = MaterialTheme.typography.labelMedium)
  }
}
