package xyz.mcxross.flare.feature.trade

import kotlinx.coroutines.flow.Flow
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarginMode
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.TradeAction

internal enum class OrderStage {
  LEVERAGE,
  ENTRY,
}

internal fun TradeUiState.leverageCommand(
  subaccount: String,
  position: Position?,
): TradeAction.ConfigureMarket? {
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
  return TradeAction.ConfigureMarket(
    subaccount,
    market.id,
    if (market.isolatedOnly) MarginMode.ISOLATED else MarginMode.CROSS,
    leverage,
  )
}

/** A failed or unresolved settings update must never continue into placing an order. */
internal suspend fun placeConfiguredOrder(
  configuration: TradeAction.ConfigureMarket?,
  entry: TradeAction,
  execute: (TradeAction) -> Flow<ActionState>,
  onState: (OrderStage, ActionState) -> Unit,
): ActionState {
  if (configuration != null) {
    var last: ActionState = ActionState.Failed("Leverage was not confirmed")
    execute(configuration).collect {
      last = it
      onState(OrderStage.LEVERAGE, it)
    }
    if (last !is ActionState.Committed) return last
  }
  var last: ActionState = ActionState.Failed("The order was not confirmed")
  execute(entry).collect {
    last = it
    onState(OrderStage.ENTRY, it)
  }
  return last
}
