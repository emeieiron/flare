package xyz.mcxross.flare.venue.decibel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OpenOrder

internal enum class ExitKind {
  TAKE_PROFIT,
  STOP_LOSS,
}

/** Whether this order takes profit or stops a loss, from Decibel's order type; null otherwise. */
internal val OpenOrder.exitKind: ExitKind?
  get() {
    val type = orderType.lowercase().filter(Char::isLetter)
    return when {
      "takeprofit" in type -> ExitKind.TAKE_PROFIT
      "stop" in type -> ExitKind.STOP_LOSS
      else -> null
    }
  }

/** The price that sets the exit off. Flare places exits with their limit at the trigger. */
internal val OpenOrder.exitTrigger: Double?
  get() =
    when (exitKind) {
      ExitKind.TAKE_PROFIT -> takeProfit?.triggerPrice ?: price
      ExitKind.STOP_LOSS -> stopLoss?.triggerPrice ?: price
      null -> null
    }

/**
 * The take-profits and stop-losses guarding a position in [market]: reduce-only trigger orders on
 * its closing side. Decibel lists exits set for the whole position on the position itself, but the
 * ones attached to the order that opened it only as orders like these.
 */
internal fun List<OpenOrder>.exitsFor(market: String, closesWithBuy: Boolean): List<OpenOrder> =
  filter {
    it.kind == MarketKind.PERP && it.marketId.equals(market, ignoreCase = true) && it.reduceOnly &&
      it.isBuy == closesWithBuy && it.exitKind != null
  }

/** The trigger of the latest exit of [kind]. */
internal fun List<OpenOrder>.latestTrigger(kind: ExitKind): Double? =
  filter { it.exitKind == kind }.maxByOrNull(OpenOrder::timeMs)?.exitTrigger

/**
 * Replaces a position's exits: places the new ones, then cancels the earlier ones they replace,
 * those set for the whole position and those attached to the order that opened it. Until the new
 * ones are in, the old ones stay, so the position is never left without the exits it had.
 */
internal class ExitReplacement(
  /** The open exits of the kinds being set, read at the moment it's called. */
  private val openExits: suspend () -> List<OpenOrder>,
  private val cancel: suspend (OpenOrder) -> ActionState,
) {
  fun replace(placement: Flow<ActionState>): Flow<ActionState> = flow {
    val earlier = runSuspendCatching { openExits() }.getOrNull()
    var placed: ActionState? = null
    placement.collect {
      placed = it
      if (it !is ActionState.Committed) emit(it)
    }
    val committed = placed as? ActionState.Committed ?: return@flow
    if (earlier == null) return@flow emit(committed.withNote(EARLIER_EXITS_UNCHECKED))
    if (earlier.isEmpty()) return@flow emit(committed)
    val cancelled =
      runSuspendCatching {
          // An earlier exit the new ones already took the place of is gone, and isn't cancelled.
          val stillOpen = openExits().map(OpenOrder::orderId).toSet()
          earlier.filter { it.orderId in stillOpen }.all { cancel(it) is ActionState.Committed }
        }
        .getOrDefault(false)
    emit(if (cancelled) committed else committed.withNote(EARLIER_EXITS_OPEN))
  }

  private fun ActionState.Committed.withNote(note: String) =
    copy(detail = listOfNotNull(detail, note).joinToString(" "))

  private companion object {
    const val EARLIER_EXITS_OPEN = "Your earlier exits are still open; cancel them in Orders."
    const val EARLIER_EXITS_UNCHECKED =
      "Check Orders for earlier exits on this position and cancel any you no longer want."
  }
}
