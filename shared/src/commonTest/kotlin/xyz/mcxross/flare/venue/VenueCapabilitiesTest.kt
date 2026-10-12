package xyz.mcxross.flare.venue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mcxross.flare.BareCapabilities
import xyz.mcxross.flare.testing.AcceptingOrderRules
import xyz.mcxross.flare.venue.model.ActionKind
import xyz.mcxross.flare.venue.model.MarginMode
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OpenOrderRef
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.actionKind

class VenueCapabilitiesTest {
  @Test
  fun everyActionIsAvailableUnlessTheVenueSaysOtherwise() {
    ActionKind.entries.forEach { assertNull(VenueCapabilities.All.unavailableReason(it)) }
    ActionKind.entries.forEach { assertNull(BareCapabilities.unavailableReason(it)) }
  }

  @Test
  fun aVenueCanHoldBackSomeKindsOfActionWithAReason() {
    val capabilities =
      BareCapabilities.copy(
        unavailableActions =
          mapOf(ActionKind.TRADING to "Orders come later", ActionKind.FUNDS to "Transfers too")
      )

    assertEquals("Orders come later", capabilities.unavailableReason(ActionKind.TRADING))
    assertEquals("Transfers too", capabilities.unavailableReason(ActionKind.FUNDS))
    assertNull(capabilities.unavailableReason(ActionKind.SETUP))
  }

  @Test
  fun actionsAreSortedIntoTradingFundsAndSetup() {
    assertEquals(ActionKind.TRADING, TradeAction.CancelOrder("0xa", "BTC", "1").actionKind)
    assertEquals(ActionKind.TRADING, TradeAction.ConfigureMarket("0xa", "BTC", MarginMode.CROSS, 3).actionKind)
    assertEquals(ActionKind.FUNDS, TradeAction.Withdraw("0xa", "1").actionKind)
    assertEquals(ActionKind.FUNDS, TradeAction.VaultDeposit("0xa", "0xv", "1").actionKind)
    assertEquals(ActionKind.FUNDS, TradeAction.MovePerpSpot("0xa", "1", toSpot = true).actionKind)
    assertEquals(ActionKind.SETUP, TradeAction.EnableTrading("0xa", "0xk").actionKind)
    assertEquals(ActionKind.SETUP, TradeAction.RevokeTrading("0xa", "0xk").actionKind)
    assertEquals(ActionKind.SETUP, TradeAction.ApproveBuilderFee("0xa", "0xb", 10, MarketKind.PERP).actionKind)
    assertEquals(ActionKind.SETUP, TradeAction.RevokeBuilderFee("0xa", "0xb").actionKind)
    assertEquals(ActionKind.SETUP, TradeAction.CreateTradingAccount.actionKind)
  }

  @Test
  fun twapsAreTheirOwnKindOfAction() {
    val market =
      Market("BTC", "BTC", "BTC/USD", MarketKind.PERP, "crypto", 1, 5, 40, false, AcceptingOrderRules)
    fun order(type: OrderType) =
      TradeAction.PlaceOrder("0xa", market, OrderRequest("BTC", OrderSide.BUY, type, "1")).actionKind

    assertEquals(ActionKind.TWAP, order(OrderType.TWAP))
    assertEquals(ActionKind.TRADING, order(OrderType.MARKET))
    assertEquals(ActionKind.TRADING, order(OrderType.LIMIT))
    assertEquals(ActionKind.TWAP, TradeAction.CancelTwapOrder("0xa", "BTC", "7").actionKind)
    assertEquals(ActionKind.TRADING, TradeAction.CancelOrders("0xa", listOf(OpenOrderRef("BTC", "1"))).actionKind)
    assertEquals(false, BareCapabilities.batchCancel)
  }
}
