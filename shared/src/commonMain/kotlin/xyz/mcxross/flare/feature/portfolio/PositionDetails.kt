package xyz.mcxross.flare.feature.portfolio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.data.formatSignedBalance
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.Position
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.BackBar
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.ExitPriceFields
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareConfirmSheet
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.shortAddress

/** One open position, reached from the portfolio. It shares the portfolio's state and actions. */
@Composable
fun PositionRoute(market: String, onBack: () -> Unit, viewModel: PortfolioViewModel) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(lifecycleOwner, viewModel) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.refreshWhileVisible() }
  }
  // Loads this position's saved exits into the editor, and lets them go on the way out.
  DisposableEffect(market) {
    viewModel.onIntent(PortfolioIntent.ManagePosition(market))
    onDispose { viewModel.onIntent(PortfolioIntent.DismissPositionManagement) }
  }
  PositionScreen(state, market, viewModel::onIntent, onBack)
}

@Composable
fun PositionScreen(
  state: PortfolioUiState,
  market: String,
  onIntent: (PortfolioIntent) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val position = state.account.positions.firstOrNull { it.market == market }
  val symbol = state.marketSymbols[market] ?: shortAddress(market)
  var confirmClose by rememberSaveable { mutableStateOf(false) }
  Column(modifier.fillMaxSize().background(FlareColors.Canvas)) {
    BackBar(symbol, onBack, backEnabled = !state.busy)
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp),
    ) {
      if (position != null) PositionDetails(state, position, onIntent)
      else Text("This position is no longer open", color = FlareColors.TextSecondary)
    }
    if (position != null) {
      FlareButton(
        "Close position",
        { confirmClose = true },
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.SELL,
      )
    }
  }
  if (confirmClose && position != null) {
    ClosePositionSheet(
      state, position, onIntent,
      onDismiss = { confirmClose = false },
      onClosed = {
        confirmClose = false
        onBack()
      },
    )
  }
}

@Composable
private fun PositionDetails(
  state: PortfolioUiState,
  position: Position,
  onIntent: (PortfolioIntent) -> Unit,
) {
  val mark = state.markPrices[position.market]
  val size = position.size.toDoubleOrNull()
  val pnl = mark?.let { price -> size?.let { (price - position.entryPrice) * it } }
  // Close failures belong to the close sheet; only exit changes report here.
  val savingExits = state.busy && state.closingMarket == null
  val exitsChanged =
    state.takeProfitInput != exitPriceInput(position.takeProfitTriggerPrice) ||
      state.stopLossInput != exitPriceInput(position.stopLossTriggerPrice)
  Text(
    "${if (position.isLong) "Long" else "Short"} ${formatQuantity(abs(size ?: 0.0))} · " +
      "${position.leverage}× · ${if (position.isIsolated) "isolated" else "cross"}",
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodyMedium,
  )
  DetailRow("Unrealized P&L", pnl?.let(::formatSignedBalance) ?: "—")
  DetailRow("Entry price", formatPrice(position.entryPrice))
  DetailRow("Mark price", mark?.let(::formatPrice) ?: "—")
  if (position.unrealizedFunding != 0.0)
    DetailRow(
      "Unrealized funding",
      (if (position.unrealizedFunding >= 0) "+" else "") + formatBalance(position.unrealizedFunding),
    )
  DetailRow("Liquidation price", formatPrice(position.estimatedLiquidationPrice))
  position.takeProfitTriggerPrice?.let { DetailRow("Take profit", formatPrice(it)) }
  position.stopLossTriggerPrice?.let { DetailRow("Stop loss", formatPrice(it)) }
  Text("Set an exit", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
  ExitPriceFields(
    state.takeProfitInput, state.stopLossInput,
    { onIntent(PortfolioIntent.ChangeTakeProfit(it)) },
    { onIntent(PortfolioIntent.ChangeStopLoss(it)) },
    Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !state.busy,
  )
  // Saving appears only once there is something to save.
  AnimatedVisibility(
    savingExits || (exitsChanged && (state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank())),
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut(),
  ) {
    FlareButton(
      "Save exits",
      { onIntent(PortfolioIntent.SubmitTpSl) },
      Modifier.fillMaxWidth().padding(top = 16.dp),
      working = savingExits,
    )
  }
  Text(
    "Leverage and margin mode can only change once this position is closed.",
    Modifier.padding(top = 12.dp),
    color = FlareColors.TextSecondary,
    style = MaterialTheme.typography.bodySmall,
  )
  if (state.closingMarket == null) {
    state.actionError?.let { ActionNotice(it, Modifier.padding(top = 12.dp), NoticeTone.ALERT) }
    PositionFeeRecovery(state, onIntent)
  }
}

/**
 * Closing a position, from its screen or by swiping its row. The sheet stays open while the close
 * runs and reports a failure in place; [onClosed] runs once the close lands.
 */
@Composable
fun ClosePositionSheet(
  state: PortfolioUiState,
  position: Position,
  onIntent: (PortfolioIntent) -> Unit,
  onDismiss: () -> Unit,
  onClosed: () -> Unit,
) {
  val symbol = state.marketSymbols[position.market] ?: shortAddress(position.market)
  val closing = state.closingMarket == position.market
  LaunchedEffect(state.closedMarket) {
    if (state.closedMarket == position.market) {
      onIntent(PortfolioIntent.CloseHandled)
      onClosed()
    }
  }
  val size = abs(position.size.toDoubleOrNull() ?: 0.0)
  val mark = state.markPrices[position.market]
  val pnl = mark?.let { price -> position.size.toDoubleOrNull()?.let { (price - position.entryPrice) * it } }
  val feeEstimate =
    (state.positionTransaction as? TransactionState.Failed)?.selfPayEstimateOctas?.takeIf { closing }
  FlareConfirmSheet(
    title = "Close this position?",
    message =
      "Flare ${if (position.isLong) "sells" else "buys back"} ${formatQuantity(size)} $symbol at " +
        "the market price" + (mark?.let { ", about ${formatPrice(it)} each" } ?: "") + ".",
    confirmLabel = if (feeEstimate != null) "Pay the fee and close" else "Close position",
    onConfirm = {
      onIntent(
        if (feeEstimate != null) PortfolioIntent.ConfirmPositionSelfPay
        else PortfolioIntent.ClosePosition(position.market)
      )
    },
    onDismiss = {
      onIntent(PortfolioIntent.CancelClose)
      onDismiss()
    },
    dismissLabel = "Keep it open",
    working = state.busy && closing,
    error = state.actionError?.takeIf { closing },
  ) {
    pnl?.let { Column(Modifier.padding(top = 8.dp)) { DetailRow("Estimated P&L", formatSignedBalance(it)) } }
    if (closing) PositionFeeRecovery(state, onIntent, offerSelfPay = false)
  }
}

/**
 * What to do when Flare can't cover a position transaction's network fee: pay it from the wallet,
 * or send this device enough APT to pay it. The close sheet offers self-pay on its own button.
 */
@Composable
private fun PositionFeeRecovery(
  state: PortfolioUiState,
  onIntent: (PortfolioIntent) -> Unit,
  offerSelfPay: Boolean = true,
) {
  val estimate = (state.positionTransaction as? TransactionState.Failed)?.selfPayEstimateOctas ?: return
  ActionNotice(
    "Flare can’t cover the network fee right now. Your wallet would pay about " +
      "${estimate.toDecimalString(8)} APT.",
    Modifier.padding(top = 12.dp),
  )
  if (offerSelfPay) {
    FlareButton(
      "Pay the fee and continue",
      { onIntent(PortfolioIntent.ConfirmPositionSelfPay) },
      Modifier.fillMaxWidth().padding(top = 8.dp),
      enabled = !state.busy,
      style = FlareButtonStyle.OUTLINE,
    )
  }
  if (state.apiWalletNeedsTopUp)
    state.suggestedTopUpOctas?.let { amount ->
      ActionNotice(
        "This device needs ${amount.toDecimalString(8)} APT to pay the fee itself.",
        Modifier.padding(top = 12.dp),
      )
      FlareButton(
        "Send APT from your wallet",
        { onIntent(PortfolioIntent.TopUpApiWallet) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
        style = FlareButtonStyle.OUTLINE,
      )
    }
}
