package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.MarginMode
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.TimeInForce
import xyz.mcxross.flare.decibel.model.ValidatedOrder
import xyz.mcxross.flare.decibel.model.ValidatedTwapOrder
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.ExternalFeePayerRequest

class SponsorshipRoutingTest {
  @Test
  fun everyCommandIsSponsoredOnTheWorkerRouteItsSignerMayUse() = runTest {
    val owner =
      listOf(
        DecibelCommand.CreateSubaccount,
        DecibelCommand.Deposit(SUB, ASSET, 1uL),
        DecibelCommand.Withdraw(SUB, ASSET, 1uL),
        DecibelCommand.TransferCollateral(SUB, ASSET, "0x66", 1uL),
        DecibelCommand.DelegateTrading(SUB, "0x44"),
        DecibelCommand.RevokeDelegation(SUB, "0x44"),
        DecibelCommand.ApproveMaxBuilderFee(SUB, "0x55", 500uL),
        DecibelCommand.RevokeMaxBuilderFee(SUB, "0x55"),
        DecibelCommand.ApproveMaxSpotBuilderFee(SUB, "0x55", 500uL),
        DecibelCommand.RevokeMaxSpotBuilderFee(SUB, "0x55"),
        DecibelCommand.ContributeToVault(SUB, "0x77", ASSET, 1uL),
        DecibelCommand.RedeemFromVault(SUB, "0x77", 1uL),
      )
    val trading =
      listOf(
        DecibelCommand.ConfigureMarket(SUB, MARKET, MarginMode.CROSS, 1u),
        DecibelCommand.PlaceOrder(SUB, ORDER),
        DecibelCommand.PlaceSpotOrder(SUB, ORDER),
        DecibelCommand.PlaceTwapOrder(SUB, ValidatedTwapOrder(MARKET, OrderSide.BUY, 1uL)),
        DecibelCommand.CancelOrder(SUB, MARKET, "1"),
        DecibelCommand.CancelSpotOrder(SUB, MARKET, "1"),
        DecibelCommand.CancelTwapOrder(SUB, MARKET, "1"),
        DecibelCommand.SetPositionTpSl(SUB, MARKET, stopLossTrigger = 1uL),
        DecibelCommand.CancelPositionTpSl(SUB, MARKET, "1"),
      )
    for ((commands, ownerOnly) in listOf(owner to true, trading to false)) {
      for (command in commands) {
        val routes = mutableListOf<Boolean>()
        val sponsorship =
          object : GasSponsorshipRepository {
            override suspend fun submit(request: ExternalFeePayerRequest, ownerOnly: Boolean) =
              AptosResult.Success("0x" + "ab".repeat(32)).also { routes += ownerOnly }

            override suspend fun resolve(fingerprint: String) = AptosResult.Success(null)
          }
        sponsorship.submitterFor(command).submit(REQUEST)
        assertEquals(listOf(ownerOnly), routes, "$command")
      }
    }
  }

  @Test
  fun onlyDefiniteRejectionsReachTheSelfPayOffer() {
    val definite =
      TransactionState.Failed(
        "unavailable",
        hash = "sponsor:0x1",
        selfPayEstimateOctas = 200uL,
        definitelyNotSubmitted = true,
        selfPayReserveOctas = 2_000uL,
      )
    val offered = definite.toActionState() as xyz.mcxross.flare.venue.model.ActionState.Failed
    assertTrue(offered.definitelyNotSubmitted)
    assertEquals(200uL, assertNotNull(offered.selfPay).estimate)

    val uncertain = TransactionState.Failed("timeout", hash = "sponsor:0x1")
    val pending = uncertain.toActionState() as xyz.mcxross.flare.venue.model.ActionState.Failed
    assertFalse(pending.definitelyNotSubmitted)
    assertNull(pending.selfPay)
    assertEquals("sponsor:0x1", pending.reference)
  }
}

private const val SUB = "0x11"
private const val MARKET = "0x22"
private const val ASSET = "0x33"
private val ORDER =
  ValidatedOrder(MARKET, OrderSide.BUY, 10uL, 1uL, TimeInForce.GOOD_TILL_CANCELLED, false, null, null, null, null, null, null)
private val REQUEST = ExternalFeePayerRequest(byteArrayOf(1), byteArrayOf(2), fingerprint = "0x" + "11".repeat(32))
