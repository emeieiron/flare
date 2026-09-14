package xyz.mcxross.flare.feature.trade

import kotlinx.coroutines.flow.Flow
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.MarginMode
import xyz.mcxross.flare.decibel.model.Position

internal enum class OrderStage {
  LEVERAGE,
  ENTRY,
}

internal fun TradeUiState.leverageCommand(
  subaccount: String,
  position: Position?,
): DecibelCommand.ConfigureMarket? {
  val market = checkNotNull(quote).market
  require(leverage in 1..market.maxLeverage.coerceAtMost(100)) {
    "Choose leverage within this market’s limit"
  }
  if (position != null) {
    require(position.leverage == leverage) {
      "Close this market’s position before changing leverage"
    }
    return null
  }
  return DecibelCommand.ConfigureMarket(
    subaccount,
    market.address,
    if (market.isIsolatedOnly) MarginMode.ISOLATED else MarginMode.CROSS,
    leverage.toUByte(),
  )
}

/** A failed or unresolved settings update must never continue into placing an order. */
internal suspend fun placeConfiguredOrder(
  configuration: DecibelCommand.ConfigureMarket?,
  entry: DecibelCommand,
  execute: (DecibelCommand) -> Flow<TransactionState>,
  onState: (OrderStage, TransactionState) -> Unit,
): TransactionState {
  if (configuration != null) {
    var last: TransactionState = TransactionState.Failed("Leverage was not confirmed")
    execute(configuration).collect {
      last = it
      onState(OrderStage.LEVERAGE, it)
    }
    if (last !is TransactionState.Committed) return last
  }
  var last: TransactionState = TransactionState.Failed("The order was not confirmed")
  execute(entry).collect {
    last = it
    onState(OrderStage.ENTRY, it)
  }
  return last
}
