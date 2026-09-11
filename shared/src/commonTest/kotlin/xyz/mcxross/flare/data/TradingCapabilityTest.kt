package xyz.mcxross.flare.data

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.model.MarginMode
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.TimeInForce
import xyz.mcxross.flare.decibel.model.ValidatedOrder

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
