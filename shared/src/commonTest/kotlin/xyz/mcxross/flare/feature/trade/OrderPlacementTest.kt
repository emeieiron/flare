package xyz.mcxross.flare.feature.trade

import kotlin.test.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.*

class OrderPlacementTest {
  private val market =
    Market(
      AssetType.PERP,
      "0x123",
      "BTC/USD",
      8,
      40,
      100uL,
      2000uL,
      1000uL,
      1_000_000.0,
      2,
      "Open",
      0,
      "crypto",
      1uL,
      100_000_000uL,
      false,
    )
  private val state =
    TradeUiState(
      quote = MarketQuote(market, 70_000.0, 0.0, 0.0, 0.0),
      leverage = 5,
      orderType = OrderType.LIMIT,
      sizeInput = "0.001",
      limitPriceInput = "70000",
      takeProfitInput = "72000",
      stopLossInput = "69000",
    )
  private val entry =
    DecibelCommand.PlaceOrder("0xabc", state.orderDraft(OrderSide.BUY).validate(market).value!!)
  private val configuration = state.leverageCommand("0xabc", null)!!

  @Test
  fun leverageMustCommitBeforeTheEntryIsSubmitted() = runTest {
    val commands = mutableListOf<DecibelCommand>()
    val stages = mutableListOf<OrderStage>()
    val result =
      placeConfiguredOrder(
        configuration,
        entry,
        { command ->
          commands += command
          flowOf(TransactionState.Committed(if (command == configuration) "settings" else "entry"))
        },
        { stage, _ -> stages += stage },
      )
    assertEquals(listOf(configuration, entry), commands)
    assertEquals(listOf(OrderStage.LEVERAGE, OrderStage.ENTRY), stages)
    assertEquals("entry", assertIs<TransactionState.Committed>(result).hash)
  }

  @Test
  fun failedOrUnresolvedLeverageNeverSubmitsTheEntry() = runTest {
    for (terminal in
      listOf(TransactionState.Failed("rejected"), TransactionState.Pending("pending-settings"))) {
      val commands = mutableListOf<DecibelCommand>()
      val result =
        placeConfiguredOrder(
          configuration,
          entry,
          { command ->
            commands += command
            flowOf(terminal)
          },
          { _, _ -> },
        )
      assertEquals(listOf<DecibelCommand>(configuration), commands)
      assertEquals(terminal, result)
    }
  }

  @Test
  fun existingPositionsKeepTheirLeverageAndSkipConfiguration() = runTest {
    val position =
      Position("0x123", "0xabc", "0.001", 5, 70_000.0, false, false, 0.0, 50_000.0, 1, false)
    assertNull(state.leverageCommand("0xabc", position))
    assertFailsWith<IllegalArgumentException> {
      state.copy(leverage = 2).leverageCommand("0xabc", position)
    }
    val commands = mutableListOf<DecibelCommand>()
    placeConfiguredOrder(
      null,
      entry,
      { command ->
        commands += command
        flowOf(TransactionState.Committed("entry"))
      },
      { _, _ -> },
    )
    assertEquals(listOf<DecibelCommand>(entry), commands)
  }

  @Test
  fun leverageChangesMarginWhileBaseSizeProfitAndLossStayTheSame() {
    val one = state.copy(leverage = 1).orderEstimate(OrderSide.BUY)!!
    val five = state.orderEstimate(OrderSide.BUY)!!
    assertEquals(70.0, one.margin)
    assertEquals(14.0, five.margin)
    assertEquals(one.value, five.value)
    assertEquals(one.profit, five.profit)
    assertEquals(one.loss, five.loss)
    assertEquals(5.toUByte(), configuration.leverage)
    assertEquals(MarginMode.CROSS, configuration.marginMode)
    assertFailsWith<IllegalArgumentException> {
      state.copy(leverage = 41).leverageCommand("0xabc", null)
    }
    assertEquals(
      MarginMode.ISOLATED,
      state
        .copy(quote = state.quote!!.copy(market = market.copy(isIsolatedOnly = true)))
        .leverageCommand("0xabc", null)
        ?.marginMode,
    )
  }
}
