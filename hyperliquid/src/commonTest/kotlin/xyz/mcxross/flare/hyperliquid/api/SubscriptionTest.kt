package xyz.mcxross.flare.hyperliquid.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import xyz.mcxross.flare.hyperliquid.model.CandleInterval

class SubscriptionTest {
  private val user = "0x1000000000000000000000000000000000000001"
  private val mixedCase = "0xABCDEF0123456789abcdef0123456789ABCDEF01"
  private val lower = "0xabcdef0123456789abcdef0123456789abcdef01"

  @Test
  fun writesMarketSubscriptions() {
    assertWire("""{"type":"allMids"}""", Subscription.AllMids)
    assertWire("""{"type":"l2Book","coin":"BTC"}""", Subscription.L2Book("BTC"))
    assertWire(
      """{"type":"l2Book","coin":"BTC","nSigFigs":5,"mantissa":2}""",
      Subscription.L2Book("BTC", significantFigures = 5, mantissa = 2),
    )
    assertWire("""{"type":"trades","coin":"PURR/USDC"}""", Subscription.Trades("PURR/USDC"))
    assertWire(
      """{"type":"candle","coin":"@1035","interval":"1M"}""",
      Subscription.Candles("@1035", CandleInterval.ONE_MONTH),
    )
    assertWire(
      """{"type":"activeAssetCtx","coin":"BTC"}""",
      Subscription.ActiveAssetContext("BTC"),
    )
    assertNull(Subscription.AllMids.user)
    assertNull(Subscription.Trades("BTC").user)
  }

  @Test
  fun writesUserSubscriptionsWithLowerCaseAddresses() {
    assertWire(
      """{"type":"activeAssetData","user":"$lower","coin":"BTC"}""",
      Subscription.ActiveAssetData(mixedCase, "BTC"),
    )
    assertWire(
      """{"type":"clearinghouseState","user":"$lower"}""",
      Subscription.ClearinghouseState(mixedCase),
    )
    assertWire("""{"type":"openOrders","user":"$user"}""", Subscription.OpenOrders(user))
    assertWire("""{"type":"spotState","user":"$user"}""", Subscription.SpotState(user))
    assertWire("""{"type":"twapStates","user":"$user"}""", Subscription.TwapStates(user))
    assertWire("""{"type":"orderUpdates","user":"$user"}""", Subscription.OrderUpdates(user))
    assertWire("""{"type":"userFills","user":"$user"}""", Subscription.UserFills(user))
    assertWire(
      """{"type":"userFills","user":"$user","aggregateByTime":true}""",
      Subscription.UserFills(user, aggregateByTime = true),
    )
    assertWire("""{"type":"userEvents","user":"$user"}""", Subscription.UserEvents(user))
    assertWire("""{"type":"userFundings","user":"$user"}""", Subscription.UserFundings(user))
    assertWire(
      """{"type":"userNonFundingLedgerUpdates","user":"$user"}""",
      Subscription.LedgerUpdates(user),
    )
    assertEquals(user, Subscription.OpenOrders(user).user)
  }

  @Test
  fun rejectsInvalidAddresses() {
    val invalid = listOf("", "0x1", "1000000000000000000000000000000000000001", "0x$lower", "0xZZ")
    invalid.forEach { address ->
      assertFailsWith<IllegalArgumentException>(address) { Subscription.ClearinghouseState(address) }
      assertFailsWith<IllegalArgumentException>(address) { Subscription.UserFills(address) }
      assertFailsWith<IllegalArgumentException>(address) {
        Subscription.ActiveAssetData(address, "BTC")
      }
      assertFailsWith<IllegalArgumentException>(address) { Subscription.LedgerUpdates(address) }
    }
  }

  private fun assertWire(expected: String, subscription: Subscription) {
    assertEquals(expected, subscription.wire.toString())
  }
}
