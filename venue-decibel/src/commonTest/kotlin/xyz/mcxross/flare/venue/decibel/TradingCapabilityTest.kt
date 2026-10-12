package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.model.MarginMode
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.TimeInForce
import xyz.mcxross.flare.decibel.model.ValidatedOrder
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.MarginMode as NeutralMarginMode

/**
 * The action decides the key. If a new command appears, the `when` in [requiredSigner] stops
 * compiling, and this test states which side of the boundary each existing command belongs to.
 */
class TradingCapabilityTest {
  @Test
  fun custodyAndDelegationRequireTheOwnerKey() {
    val ownerActions =
      listOf(
        DecibelCommand.CreateSubaccount,
        DecibelCommand.Deposit(SUBACCOUNT, USDC, 1uL),
        DecibelCommand.Withdraw(SUBACCOUNT, USDC, 1uL),
        DecibelCommand.TransferCollateral(SUBACCOUNT, USDC, "0x2", 1uL),
        DecibelCommand.DelegateTrading(SUBACCOUNT, "0x3"),
        DecibelCommand.RevokeDelegation(SUBACCOUNT, "0x3"),
      )

    ownerActions.forEach { command ->
      assertEquals(TradingSigner.OWNER, command.requiredSigner(), command.toString())
    }
  }

  @Test
  fun tradingUsesTheDeviceKeySoTheOwnerKeyStaysOutOfRoutineUse() {
    val tradingActions =
      listOf(
        DecibelCommand.ConfigureMarket(SUBACCOUNT, MARKET, MarginMode.CROSS, 5u),
        DecibelCommand.PlaceOrder(SUBACCOUNT, order()),
        DecibelCommand.CancelOrder(SUBACCOUNT, MARKET, "1"),
        DecibelCommand.CancelPositionTpSl(SUBACCOUNT, MARKET, "1"),
        DecibelCommand.SetPositionTpSl(SUBACCOUNT, MARKET, takeProfitTrigger = 1uL),
      )

    tradingActions.forEach { command ->
      assertEquals(TradingSigner.API, command.requiredSigner(), command.toString())
    }
  }

  @Test
  fun neutralActionsKeepTheSameKeyBoundary() {
    val owner =
      listOf(
        TradeAction.CreateTradingAccount,
        TradeAction.Deposit(SUBACCOUNT, "1"),
        TradeAction.Withdraw(SUBACCOUNT, "1"),
        TradeAction.Transfer(SUBACCOUNT, "0x2", "1"),
        TradeAction.EnableTrading(SUBACCOUNT, "0x3"),
        TradeAction.RevokeTrading(SUBACCOUNT, "0x3"),
        TradeAction.ApproveBuilderFee(SUBACCOUNT, "0x4", 10),
        TradeAction.RevokeBuilderFee(SUBACCOUNT, "0x4", MarketKind.SPOT),
        TradeAction.VaultDeposit(SUBACCOUNT, "0x5", "10"),
        TradeAction.VaultWithdraw(SUBACCOUNT, "0x5", "5"),
      )
    val trading =
      listOf(
        TradeAction.ConfigureMarket(SUBACCOUNT, MARKET, NeutralMarginMode.CROSS, 5),
        TradeAction.CancelOrder(SUBACCOUNT, MARKET, "1", MarketKind.SPOT),
        TradeAction.CancelPositionTpSl(SUBACCOUNT, MARKET, "1"),
        TradeAction.CancelTwapOrder(SUBACCOUNT, MARKET, "1"),
      )

    owner.forEach { action ->
      assertEquals(TradingSigner.OWNER, action.toDecibelCommand(USDC).requiredSigner(), "$action")
    }
    trading.forEach { action ->
      assertEquals(TradingSigner.API, action.toDecibelCommand(USDC).requiredSigner(), "$action")
    }
  }

  private fun order() =
    ValidatedOrder(
      marketAddress = MARKET,
      side = OrderSide.BUY,
      price = 1uL,
      size = 1uL,
      timeInForce = TimeInForce.GOOD_TILL_CANCELLED,
      reduceOnly = false,
      clientOrderId = null,
      stopPrice = null,
      takeProfitTriggerPrice = null,
      takeProfitLimitPrice = null,
      stopLossTriggerPrice = null,
      stopLossLimitPrice = null,
    )

  private companion object {
    const val SUBACCOUNT = "0x1"
    const val MARKET = "0xmarket"
    const val USDC = "0xusdc"
  }
}
