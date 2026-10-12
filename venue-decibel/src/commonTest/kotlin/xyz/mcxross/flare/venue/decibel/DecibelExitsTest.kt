package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.Page
import xyz.mcxross.flare.decibel.model.Position as DecibelPosition
import xyz.mcxross.flare.feature.orders.openOrderConditions
import xyz.mcxross.flare.feature.orders.openOrderSummary
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.OpenOrder

class DecibelExitsTest {
  private val json = DecibelClient.DefaultJson

  // A 0.01 ETH market buy with TP 3,000 and SL 2,000 attached, after it filled, beside a resting buy.
  private val orders =
    json
      .decodeFromString<Page<Order>>(
        """{"items":[""" +
          order("11", isBuy = false, reduceOnly = true, type = "Stop Limit", price = 2000.0, ms = 2) + "," +
          order("12", isBuy = false, reduceOnly = true, type = "Take Profit Limit", price = 3000.0, ms = 2) + "," +
          order("13", isBuy = true, reduceOnly = false, type = "Limit", price = 2000.0, ms = 1) +
          """],"total_count":3}"""
      )
      .items
      .map { it.toOpenOrder() }

  private val long =
    json.decodeFromString<DecibelPosition>(
      """{"market":"$ETH","user":"0xa1","size":0.01,"user_leverage":5,"entry_price":2529.6,""" +
        """"is_isolated":false,"is_deleted":false,"unrealized_funding":0.0,""" +
        """"estimated_liquidation_price":0.0,"transaction_version":38648300,""" +
        """"has_fixed_sized_tpsls":true,"tp_order_id":null,"tp_trigger_price":null,""" +
        """"tp_limit_price":null,"sl_order_id":null,"sl_trigger_price":null,"sl_limit_price":null}"""
    )

  @Test
  fun exitsAttachedToTheOpeningOrderShowOnThePosition() {
    val position = long.toPosition(orders)

    assertEquals(3000.0, position.takeProfitTriggerPrice)
    assertEquals(2000.0, position.stopLossTriggerPrice)
  }

  @Test
  fun exitsSetForThePositionComeFirst() {
    val position = long.copy(takeProfitTriggerPrice = 3200.0).toPosition(orders)

    assertEquals(3200.0, position.takeProfitTriggerPrice)
    assertEquals(2000.0, position.stopLossTriggerPrice)
  }

  @Test
  fun onlyExitsOnThePositionsClosingSideCount() {
    val short = long.copy(size = "-0.01").toPosition(orders)
    assertNull(short.takeProfitTriggerPrice)
    assertNull(short.stopLossTriggerPrice)

    val elsewhere = long.copy(market = "0xbtc").toPosition(orders)
    assertNull(elsewhere.takeProfitTriggerPrice)
  }

  @Test
  fun exitRowsShowTheirKindTriggerAndSize() {
    fun row(order: OpenOrder) = openOrderConditions(order) to openOrderSummary(order)

    // Attached to the opening order: a fixed size, its limit at the trigger.
    assertEquals(
      listOf(
        "stop limit · Reduce only" to "Sell 0.01 · at \$2,000.00 · trigger \$2,000.00",
        "take profit limit · Reduce only" to "Sell 0.01 · at \$3,000.00 · trigger \$3,000.00",
      ),
      orders.take(2).map(::row),
    )
    assertEquals("Buy 0.01 · at \$2,000.00", openOrderSummary(orders[2]))

    // Set for the whole position, as Manage position does: no size of its own.
    val whole = positionExit("21", "Take Profit Limit", tp = 3200.0, size = "null").toOpenOrder()
    assertEquals(
      "take profit limit · Reduce only" to "Sell entire position · at \$3,200.00 · trigger \$3,200.00",
      row(whole),
    )
    val stop = positionExit("22", "Stop Market", sl = 1900.0, price = "null", size = "null").toOpenOrder()
    assertEquals("Sell entire position · at market · trigger \$1,900.00", openOrderSummary(stop))

    // One listed with a size is still whole when the position names it as its exit.
    val sized = listOf(positionExit("23", "Stop Market", sl = 1900.0, price = "null", size = "0.01").toOpenOrder())
    assertEquals("Sell 0.01 · at market · trigger \$1,900.00", openOrderSummary(sized.single()))
    val named = sized.withWholePositionExits(listOf(long.copy(stopLossOrderId = "23")))
    assertEquals("Sell entire position · at market · trigger \$1,900.00", openOrderSummary(named.single()))
  }

  @Test
  fun aTriggerConditionNamesTheTrigger() {
    val order = positionExit("24", "Stop Limit", sl = null, price = "1890.0", size = "0.01", condition = "mark <= 1,900.5")
    assertEquals(1900.5, order.toOpenOrder().triggerPrice)
  }

  @Test
  fun newExitsArePlacedBeforeTheEarlierOnesAreCancelled() = runTest {
    val events = mutableListOf<String>()
    val earlier = orders.exitsFor(ETH, closesWithBuy = false)
    val replacement =
      ExitReplacement(
        openExits = { events += "read"; earlier },
        cancel = { events += "cancel ${it.orderId}"; ActionState.Committed("0xc${it.orderId}") },
      )
    val placement = flow {
      events += "place"
      emit(ActionState.Submitting)
      emit(ActionState.Committed("0xnew"))
    }

    val states = replacement.replace(placement).toList()

    assertEquals(listOf("read", "place", "read", "cancel 11", "cancel 12"), events)
    assertEquals(listOf(ActionState.Submitting, ActionState.Committed("0xnew")), states)
  }

  @Test
  fun aFailedPlacementLeavesTheEarlierExits() = runTest {
    val cancelled = mutableListOf<String>()
    val replacement =
      ExitReplacement(
        openExits = { orders.exitsFor(ETH, closesWithBuy = false) },
        cancel = { cancelled += it.orderId; ActionState.Committed("0x") },
      )

    val states = replacement.replace(flowOf(ActionState.Failed("Simulation failed"))).toList()

    assertIs<ActionState.Failed>(states.last())
    assertEquals(emptyList(), cancelled)
  }

  @Test
  fun anExitAlreadyReplacedIsNotCancelledAndAFailedCancelIsExplained() = runTest {
    val earlier = orders.exitsFor(ETH, closesWithBuy = false)
    var reads = 0
    val cancelled = mutableListOf<String>()
    val replacement =
      ExitReplacement(
        openExits = { if (reads++ == 0) earlier else earlier.filter { it.orderId == "11" } },
        cancel = { cancelled += it.orderId; ActionState.Failed("Network busy") },
      )

    val last = replacement.replace(flowOf(ActionState.Committed("0xnew"))).toList().last()

    assertEquals(listOf("11"), cancelled)
    assertEquals(
      ActionState.Committed("0xnew", "Your earlier exits are still open; cancel them in Orders."),
      last,
    )
  }

  private fun positionExit(
    id: String,
    type: String,
    tp: Double? = null,
    sl: Double? = null,
    price: String = "${tp ?: sl}",
    size: String,
    condition: String = "",
  ): Order =
    json.decodeFromString(
      """{"asset_type":"perp","parent":"0xce9f","market":"$ETH","client_order_id":"",""" +
        """"order_id":"$id","status":"Open","order_type":"$type","trigger_condition":"$condition",""" +
        """"order_direction":"Close Long","is_buy":false,"is_reduce_only":true,"details":"",""" +
        """"is_tpsl":true,"cancellation_reason":"","transaction_version":38648400,"unix_ms":3,""" +
        """"price":$price,"orig_size":$size,"remaining_size":$size,"size_delta":null,""" +
        """"tp_trigger_price":$tp,"tp_limit_price":${if (price == "null") null else tp},""" +
        """"sl_trigger_price":$sl,"sl_limit_price":${if (price == "null") null else sl}}"""
    )

  private companion object {
    const val ETH = "0x59fa603bd934597e8d69ce9ffc8070c0a91112e4948ec46339071b5f8922c30d"

    fun order(id: String, isBuy: Boolean, reduceOnly: Boolean, type: String, price: Double, ms: Long) =
      """{"asset_type":"perp","parent":"0xce9f","market":"$ETH","client_order_id":"",""" +
        """"order_id":"$id","status":"Open","order_type":"$type","trigger_condition":"",""" +
        """"order_direction":"${if (reduceOnly) "Close Long" else "Open Long"}","is_buy":$isBuy,""" +
        """"is_reduce_only":$reduceOnly,"details":"","is_tpsl":false,"cancellation_reason":"",""" +
        """"transaction_version":38648301,"unix_ms":$ms,"price":$price,"orig_size":0.01,""" +
        """"remaining_size":0.01,"size_delta":null,"tp_trigger_price":null,"tp_limit_price":null,""" +
        """"sl_trigger_price":null,"sl_limit_price":null}"""
  }
}
