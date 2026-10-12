package xyz.mcxross.flare.feature.trade

import kotlin.test.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.testing.AcceptingOrderRules
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarginMode
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.SelfPayOffer
import xyz.mcxross.flare.venue.model.TradeAction

class OrderPlacementTest {
  private val market =
    Market(
      id = "0x123",
      symbol = "BTC",
      displayName = "BTC/USD",
      kind = MarketKind.PERP,
      category = "crypto",
      priceDecimals = 2,
      sizeDecimals = 8,
      maxLeverage = 40,
      isolatedOnly = false,
      rules = AcceptingOrderRules,
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
  private val entry = TradeAction.PlaceOrder("0xabc", market, state.checkedOrder(OrderSide.BUY))
  private val configuration = state.leverageCommand("0xabc", null)!!

  @Test
  fun leverageMustCommitBeforeTheEntryIsSubmitted() = runTest {
    val commands = mutableListOf<TradeAction>()
    val stages = mutableListOf<OrderStage>()
    val result =
      placeConfiguredOrder(
        configuration,
        entry,
        { command ->
          commands += command
          flowOf(ActionState.Committed(if (command == configuration) "settings" else "entry"))
        },
        { stage, _ -> stages += stage },
      )
    assertEquals(listOf(configuration, entry), commands)
    assertEquals(listOf(OrderStage.LEVERAGE, OrderStage.ENTRY), stages)
    assertEquals("entry", assertIs<ActionState.Committed>(result).reference)
  }

  @Test
  fun failedOrUnresolvedLeverageNeverSubmitsTheEntry() = runTest {
    for (terminal in
      listOf(ActionState.Failed("rejected"), ActionState.Pending("pending-settings"))) {
      val commands = mutableListOf<TradeAction>()
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
      assertEquals(listOf<TradeAction>(configuration), commands)
      assertEquals(terminal, result)
    }
  }

  @Test
  fun existingPositionsKeepTheirLeverageAndSkipConfiguration() = runTest {
    val position =
      Position(marketId = "0x123", size = "0.001", leverage = 5, entryPrice = 70_000.0, isolated = false)
    assertNull(state.leverageCommand("0xabc", position))
    assertFailsWith<IllegalArgumentException> {
      state.copy(leverage = 2).leverageCommand("0xabc", position)
    }
    val commands = mutableListOf<TradeAction>()
    placeConfiguredOrder(
      null,
      entry,
      { command ->
        commands += command
        flowOf(ActionState.Committed("entry"))
      },
      { _, _ -> },
    )
    assertEquals(listOf<TradeAction>(entry), commands)
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
    assertEquals(5, configuration.leverage)
    assertEquals(MarginMode.CROSS, configuration.marginMode)
    assertFailsWith<IllegalArgumentException> {
      state.copy(leverage = 41).leverageCommand("0xabc", null)
    }
    assertEquals(
      MarginMode.ISOLATED,
      state
        .copy(quote = state.quote!!.copy(market = market.copy(isolatedOnly = true)))
        .leverageCommand("0xabc", null)
        ?.marginMode,
    )
  }

  @Test
  fun aDeclinedFeeIsOfferedToTheWalletOnlyWhereTheVenueSponsorsFees() {
    val declined =
      state.copy(transaction = ActionState.Failed("Not sponsored", selfPay = SelfPayOffer(2_400uL)))
    assertEquals(2_400uL, declined.selfPayEstimate)
    assertNull(
      declined
        .copy(capabilities = VenueCapabilities.All.copy(sponsoredFees = false))
        .selfPayEstimate
    )
    assertNull(state.copy(transaction = ActionState.Failed("Rejected")).selfPayEstimate)
  }
}
