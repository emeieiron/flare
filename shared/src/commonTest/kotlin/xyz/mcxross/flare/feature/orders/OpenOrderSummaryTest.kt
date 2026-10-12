package xyz.mcxross.flare.feature.orders

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.flare.venue.model.OpenOrder
import xyz.mcxross.flare.venue.model.OrderTrigger

class OpenOrderSummaryTest {
  private val resting = OpenOrder("BTC", "1", isBuy = true, orderType = "Limit", price = 82_000.0, remainingSize = 0.3, timeMs = 0)

  @Test
  fun aRestingOrderReadsAsBefore() {
    assertEquals("Buy 0.3 · at \$82,000.00", openOrderSummary(resting))
    assertEquals("Sell — · at market", openOrderSummary(resting.copy(isBuy = false, price = null, remainingSize = null)))
  }

  @Test
  fun aTriggerOrderShowsItsTrigger() {
    val stop = resting.copy(isBuy = false, orderType = "Stop Market", price = null, triggerPrice = 35_000.0, reduceOnly = true)
    assertEquals("stop market · Reduce only", openOrderConditions(stop))
    assertEquals("Sell 0.3 · at market · trigger \$35,000.00", openOrderSummary(stop))
  }

  @Test
  fun aWholePositionExitCoversThePositionRatherThanItsSize() {
    val exit =
      resting.copy(isBuy = false, orderType = "Take Profit Limit", price = 117_000.0, remainingSize = 0.0,
        triggerPrice = 130_000.0, reduceOnly = true, coversPosition = true)
    assertEquals("Sell entire position · at \$117,000.00 · trigger \$130,000.00", openOrderSummary(exit))
  }

  @Test
  fun exitsAttachedToAnOrderStayInItsConditions() {
    val withExits = resting.copy(takeProfit = OrderTrigger(90_000.0), stopLoss = OrderTrigger(80_000.0))
    assertEquals("limit · TP \$90,000.00 · SL \$80,000.00", openOrderConditions(withExits))
    assertEquals("Buy 0.3 · at \$82,000.00", openOrderSummary(withExits))
  }
}
