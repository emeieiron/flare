package xyz.mcxross.flare.feature.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.DetailRow
import xyz.mcxross.flare.design.ExitPriceFields
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.NoticeTone
import xyz.mcxross.flare.design.shortAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionManagementSheet(state: PortfolioUiState, onIntent: (PortfolioIntent) -> Unit) {
  val busy by rememberUpdatedState(state.busy)
  val sheetState = rememberModalBottomSheetState(
    skipPartiallyExpanded = true,
    confirmValueChange = { it != SheetValue.Hidden || !busy },
  )
  val scope = rememberCoroutineScope()
  val dismiss = { onIntent(PortfolioIntent.DismissPositionManagement) }
  MaterialTheme(motionScheme = MotionScheme.standard()) {
    ModalBottomSheet(
      onDismissRequest = { if (!busy) dismiss() },
      sheetState = sheetState,
      sheetGesturesEnabled = !state.busy,
      shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
      containerColor = FlareColors.Surface,
      contentColor = FlareColors.TextPrimary,
      scrimColor = Color.Black.copy(alpha = 0.64f),
      tonalElevation = 0.dp,
      dragHandle = null,
      properties = ModalBottomSheetProperties(
        shouldDismissOnBackPress = !state.busy,
        shouldDismissOnClickOutside = !state.busy,
      ),
    ) {
      Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
        Row(
          Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 24.dp, end = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            state.marketSymbols[state.managedPositionMarket] ?: "Position",
            Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
          )
          IconButton(
            enabled = !state.busy,
            onClick = { scope.launch { sheetState.hide(); if (!sheetState.isVisible) dismiss() } },
          ) { Icon(Icons.Outlined.Close, "Dismiss position details") }
        }
        HorizontalDivider(color = FlareColors.BorderSubtle)
        Column(
          Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 24.dp),
        ) {
          if (state.account.positions.any { it.market == state.managedPositionMarket }) {
            PositionDetails(state, onIntent)
          } else {
            Text("This position is no longer open", color = FlareColors.TextSecondary)
          }
        }
      }
    }
  }
}

@Composable
fun PositionDetails(state: PortfolioUiState, onIntent: (PortfolioIntent) -> Unit) {
  val position =
    state.account.positions.firstOrNull { it.market == state.managedPositionMarket } ?: return
  val symbol = state.marketSymbols[position.market] ?: shortAddress(position.market)
  val mark = state.markPrices[position.market]
  val size = position.size.toDoubleOrNull()
  val pnl = mark?.let { price -> size?.let { (price - position.entryPrice) * it } }
  var confirmClose by remember { mutableStateOf(false) }
  Column {
    Column {
      Text(
        "${if (position.isLong) "Long" else "Short"} ${formatQuantity(abs(size ?: 0.0))} · " +
          "${position.leverage}× · ${if (position.isIsolated) "isolated" else "cross"}",
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
      )
      DetailRow(
        "Unrealized P&L",
        pnl?.let { (if (it >= 0) "+" else "") + formatBalance(it) } ?: "—",
      )
      DetailRow("Entry price", formatPrice(position.entryPrice))
      DetailRow("Mark price", mark?.let(::formatPrice) ?: "—")
      if (position.unrealizedFunding != 0.0)
        DetailRow(
          "Unrealized funding",
          (if (position.unrealizedFunding >= 0) "+" else "") +
            formatBalance(position.unrealizedFunding),
        )
      DetailRow("Liquidation price", formatPrice(position.estimatedLiquidationPrice))
      position.takeProfitTriggerPrice?.let { DetailRow("Take profit", formatPrice(it)) }
      position.stopLossTriggerPrice?.let { DetailRow("Stop loss", formatPrice(it)) }
      Text(
        "Set an exit",
        Modifier.padding(top = 24.dp),
        style = MaterialTheme.typography.titleMedium,
      )
      ExitPriceFields(
        state.takeProfitInput, state.stopLossInput,
        { onIntent(PortfolioIntent.ChangeTakeProfit(it)) },
        { onIntent(PortfolioIntent.ChangeStopLoss(it)) },
        Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !state.busy,
      )
      Text(
        "Leverage and margin mode can only change once this position is closed.",
        Modifier.padding(top = 12.dp),
        color = FlareColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    state.actionError?.let { error ->
      ActionNotice(error, Modifier.padding(top = 12.dp), NoticeTone.ALERT)
    }
    (state.positionTransaction as? TransactionState.Failed)?.selfPayEstimateOctas?.let { estimate ->
      ActionNotice(
        "Flare can’t cover the network fee right now. Your wallet would pay about " +
          "${estimate.toDecimalString(8)} APT.",
        Modifier.padding(top = 12.dp),
      )
      FlareButton(
        "Pay the fee and continue",
        { onIntent(PortfolioIntent.ConfirmPositionSelfPay) },
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.busy,
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
            { onIntent(PortfolioIntent.TopUpApiWallet) },
            Modifier.fillMaxWidth().padding(top = 8.dp),
            enabled = !state.busy,
            style = FlareButtonStyle.OUTLINE,
          )
        }
    }
    FlareButton(
      if (state.busy) "Working…" else "Save exits",
      { onIntent(PortfolioIntent.SubmitTpSl) },
      Modifier.fillMaxWidth().padding(top = 16.dp),
      enabled = state.takeProfitInput.isNotBlank() || state.stopLossInput.isNotBlank(),
      working = state.busy,
    )
    FlareButton(
      "Close position",
      { confirmClose = true },
      Modifier.fillMaxWidth().padding(top = 8.dp),
      enabled = !state.busy,
      style = FlareButtonStyle.SELL,
    )
  }
  if (confirmClose)
    AlertDialog(
      onDismissRequest = { confirmClose = false },
      title = { Text("Close this position?") },
      text = {
        Text(
          "Flare will ${if (position.isLong) "sell" else "buy"} " +
            "${formatQuantity(abs(size ?: 0.0))} $symbol at the market price."
        )
      },
      confirmButton = {
        TextButton({
          confirmClose = false
          onIntent(PortfolioIntent.ClosePosition)
        }) {
          Text("Close position")
        }
      },
      dismissButton = { TextButton({ confirmClose = false }) { Text("Keep it open") } },
      containerColor = FlareColors.Surface,
    )
}
