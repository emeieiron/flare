package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.hyperliquid.model.CancelStatus
import xyz.mcxross.flare.hyperliquid.model.Cloid
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.hyperliquid.model.L1Action
import xyz.mcxross.flare.hyperliquid.model.OrderStatus
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.operationName
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarginMode
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OpenOrderRef
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderBookLevel
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TimeInForce
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.TwapSchedule

/**
 * Orders, exits, cancels and leverage as sent: the wire JSON of each is what
 * hyperliquid-python-sdk 0.24.0 builds for the same order (see the SDK's SdkOrderShapesTest).
 */
class HyperliquidTradingTest {
  private val prompt = VaultPrompt("Confirm order", "Confirm your identity")
  private val owner = SetupHarness.OWNER

  @Test
  fun aMarketBuyIsAnIocAtTheSlippageBoundWithTheBuilderFee() = runTest {
    val harness = harness()
    val book = book(bid = "64990", ask = "65000")
    val order =
      check(
        BTC,
        OrderRequest(
          "BTC", OrderSide.BUY, OrderType.MARKET, "0.01", slippageBps = 50,
          builderAddress = BUILDER, builderFeeBps = 5,
        ),
        book,
      )
    harness.submitter.outcome = {
      ExchangeOutcome.Accepted(ExchangeResponse.Orders(listOf(OrderStatus.Filled("0.01", "65010.5", 123L, CLOID))))
    }
    var journaled: String? = null
    harness.submitter.beforeSigning = { journaled = harness.journal.find(CLOID.hex)?.operationName }

    val states = harness.trading.execute(TradeAction.PlaceOrder(owner, BTC, order), prompt).toList()

    assertEquals(
      """{"type":"order","orders":[{"a":0,"b":true,"p":"65325","s":"0.01","r":false,"t":{"limit":{"tif":"Ioc"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na","builder":{"b":"0x8c967e73e7b15087c42a10d344cff4c96d877f1d","f":50}}""",
      harness.wire(),
    )
    assertEquals(
      JournaledAction.PlaceOrder(owner, harness.now + 60_000 + 300_000),
      JournaledAction.decode(checkNotNull(journaled)),
    )
    assertEquals(ActionState.AwaitingAuthorization, states.first())
    assertEquals(ActionState.Committed("123", "Filled 0.01 at 65010.5."), states.last())
    assertTrue(harness.journal.entries.value.isEmpty())
    assertTrue(harness.wallet.ownerPrompts.isEmpty())
  }

  @Test
  fun limitOrdersSendEachTimeInForce() = runTest {
    val harness = harness()
    harness.submitter.outcome = { resting(7L) }

    harness.place(BTC, limit("BTC", OrderSide.SELL, "0.5", "70000"))
    harness.place(ETH, limit("ETH", OrderSide.BUY, "1.25", "3000.5", TimeInForce.POST_ONLY))
    val last =
      harness.place(
        BTC,
        limit("BTC", OrderSide.SELL, "0.01", "64000", TimeInForce.IMMEDIATE_OR_CANCEL).copy(reduceOnly = true),
      )

    assertEquals(
      listOf(
        """{"type":"order","orders":[{"a":0,"b":false,"p":"70000","s":"0.5","r":false,"t":{"limit":{"tif":"Gtc"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na"}""",
        """{"type":"order","orders":[{"a":1,"b":true,"p":"3000.5","s":"1.25","r":false,"t":{"limit":{"tif":"Alo"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na"}""",
        """{"type":"order","orders":[{"a":0,"b":false,"p":"64000","s":"0.01","r":true,"t":{"limit":{"tif":"Ioc"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na"}""",
      ),
      harness.wires(),
    )
    assertEquals(ActionState.Committed("7", "Your order is on the book."), last)
  }

  @Test
  fun aSpotOrderUsesTheSpotAssetId() = runTest {
    val harness = harness()
    harness.submitter.outcome = { resting(8L) }

    harness.place(HYPE, limit("@107", OrderSide.BUY, "2", "25.123"))

    assertEquals(
      """{"type":"order","orders":[{"a":10107,"b":true,"p":"25.123","s":"2","r":false,"t":{"limit":{"tif":"Gtc"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na"}""",
      harness.wire(),
    )
  }

  @Test
  fun attachedExitsAreReduceOnlyMarketTriggersGroupedWithTheOrder() = runTest {
    val harness = harness()
    harness.submitter.outcome = {
      ExchangeOutcome.Accepted(
        ExchangeResponse.Orders(listOf(OrderStatus.Resting(5L, CLOID), OrderStatus.WaitingForFill, OrderStatus.WaitingForFill))
      )
    }
    val order =
      limit("BTC", OrderSide.BUY, "0.01", "65000").copy(
        takeProfitTrigger = "70000",
        takeProfitLimit = "70000",
        stopLossTrigger = "60000",
        stopLossLimit = "60000",
        builderAddress = BUILDER,
        builderFeeBps = 5,
      )

    val result = harness.place(BTC, order)

    assertEquals(
      """{"type":"order","orders":[{"a":0,"b":true,"p":"65000","s":"0.01","r":false,"t":{"limit":{"tif":"Gtc"}},"c":"0x0123456789abcdef0123456789abcdef"},{"a":0,"b":false,"p":"63000","s":"0.01","r":true,"t":{"trigger":{"isMarket":true,"triggerPx":"70000","tpsl":"tp"}}},{"a":0,"b":false,"p":"54000","s":"0.01","r":true,"t":{"trigger":{"isMarket":true,"triggerPx":"60000","tpsl":"sl"}}}],"grouping":"normalTpsl","builder":{"b":"0x8c967e73e7b15087c42a10d344cff4c96d877f1d","f":50}}""",
      harness.wire(),
    )
    assertEquals(ActionState.Committed("5", "Your order is on the book."), result)
  }

  @Test
  fun noBuilderAddressMeansNoBuilderField() {
    assertNull(HyperliquidOrders.builderFee(null, 5))
    assertNull(HyperliquidOrders.builderFee(" ", 5))
    assertNull(HyperliquidOrders.builderFee(BUILDER, 0))
    assertEquals(50, HyperliquidOrders.builderFee(BUILDER, 5)?.tenthsOfBasisPoint)
  }

  @Test
  fun eachOrdersStatusDecidesWhatThePersonSees() {
    val labels = listOf("Order", "take profit", "stop loss")
    fun orders(vararg statuses: OrderStatus) = ExchangeResponse.Orders(statuses.toList())

    assertEquals(
      ActionState.Committed("5", "Your order is on the book. Not set: take profit: Invalid TP/SL price."),
      HyperliquidResults.orders(
        orders(OrderStatus.Resting(5L), OrderStatus.Error("Invalid TP/SL price."), OrderStatus.WaitingForFill),
        "ref",
        labels,
      ),
    )
    val failed =
      HyperliquidResults.orders(orders(OrderStatus.Error("Insufficient margin")), "ref", labels.take(1))
    assertEquals(ActionState.Failed("Insufficient margin", "ref", committed = true), failed)
    assertNull(HyperliquidResults.orders(orders(OrderStatus.Unrecognized("x")), "ref", labels.take(1)))
    assertNull(HyperliquidResults.orders(ExchangeResponse.Default, "ref", labels))
    assertEquals(
      ActionState.Committed("ref", "Canceled 1 of 2. Order was never placed, already canceled, or filled."),
      HyperliquidResults.cancels(
        ExchangeResponse.Cancels(
          listOf(CancelStatus.Success, CancelStatus.Error("Order was never placed, already canceled, or filled."))
        ),
        "ref",
      ),
    )
    assertIs<ActionState.Failed>(
      HyperliquidResults.cancels(ExchangeResponse.Cancels(listOf(CancelStatus.Error("gone"))), "ref")
    )
  }

  @Test
  fun anOrderWithoutADefiniteAnswerStaysPendingUntilItsCloidIsFound() = runTest {
    val harness = harness()
    var status = """{"status":"unknownOid"}"""
    harness.answer = { type, body ->
      if (type == "orderStatus") {
        assertEquals(CLOID.hex, body["oid"]!!.jsonPrimitive.content)
        status
      } else assets(type)
    }
    harness.submitter.outcome = { ExchangeOutcome.Unknown("timed out") }

    val pending = harness.place(BTC, limit("BTC", OrderSide.BUY, "0.01", "65000"))

    assertEquals(ActionState.Pending(CLOID.hex), pending)
    assertEquals("RECONCILE_REQUIRED", harness.journal.find(CLOID.hex)?.state)
    assertEquals(1, harness.actions.reconcile().unresolved)
    status = Fixtures.ORDER_STATUS
    assertEquals(1, harness.actions.reconcile().finalized)
    assertTrue(harness.journal.entries.value.isEmpty())
  }

  @Test
  fun aLostOrderResolvesOnceItsExpiryHasPassed() = runTest {
    val harness = harness()
    harness.answer = { type, _ -> if (type == "orderStatus") """{"status":"unknownOid"}""" else assets(type) }
    harness.submitter.outcome = { ExchangeOutcome.Unknown("timed out") }

    harness.place(BTC, limit("BTC", OrderSide.BUY, "0.01", "65000"))
    harness.now += HyperliquidActions.ACTION_TTL_MS
    assertEquals(1, harness.actions.reconcile().unresolved)
    harness.now += HyperliquidActions.CLOCK_SKEW_MS + 1
    assertEquals(1, harness.actions.reconcile().finalized)
  }

  @Test
  fun aRejectedOrderFailsAndLeavesNothingToReconcile() = runTest {
    val harness = harness()
    harness.submitter.outcome = { ExchangeOutcome.Rejected("Must deposit before performing actions.") }

    val failed = harness.place(BTC, limit("BTC", OrderSide.BUY, "0.01", "65000"))

    assertEquals(
      ActionState.Failed("Must deposit before performing actions.", CLOID.hex, committed = true),
      failed,
    )
    assertTrue(harness.journal.entries.value.isEmpty())
  }

  @Test
  fun anOrderThatCantBeBuiltIsNeverSent() = runTest {
    val harness = harness()

    // Past the rules' check, so only the order's own validation stops it.
    val failed =
      harness.place(BTC, OrderRequest("BTC", OrderSide.BUY, OrderType.LIMIT, "0.000001", price = "65000"))

    assertTrue(assertIs<ActionState.Failed>(failed).definitelyNotSubmitted)
    assertTrue(harness.submitter.sent.isEmpty())
    assertTrue(harness.journal.entries.value.isEmpty())
  }

  @Test
  fun cancelsGoByOidInOneBatchAndByCloidWhereThatsAllThereIs() = runTest {
    val harness = harness()
    harness.submitter.outcome = {
      ExchangeOutcome.Accepted(ExchangeResponse.Cancels(listOf(CancelStatus.Success, CancelStatus.Success)))
    }

    val batch =
      harness.trading
        .execute(TradeAction.CancelOrders(owner, listOf(OpenOrderRef("BTC", "123456"), OpenOrderRef("@107", "789"))), prompt)
        .toList()
        .last()
    harness.submitter.outcome = { ExchangeOutcome.Accepted(ExchangeResponse.Cancels(listOf(CancelStatus.Success))) }
    val byCloid = harness.trading.execute(TradeAction.CancelOrder(owner, "BTC", CLOID.hex), prompt).toList().last()

    assertEquals(
      listOf(
        """{"type":"cancel","cancels":[{"a":0,"o":123456},{"a":10107,"o":789}]}""",
        """{"type":"cancelByCloid","cancels":[{"asset":0,"cloid":"0x0123456789abcdef0123456789abcdef"}]}""",
      ),
      harness.wires(),
    )
    assertIs<ActionState.Committed>(batch)
    assertIs<ActionState.Committed>(byCloid)
  }

  @Test
  fun aCancelIsReconciledByWhetherItsOrdersAreStillOpen() = runTest {
    val harness = harness()
    var open = Fixtures.FRONTEND_OPEN_ORDERS
    harness.answer = { type, _ -> if (type == "frontendOpenOrders") open else assets(type) }
    harness.submitter.outcome = { ExchangeOutcome.Unknown("timed out") }

    val pending = harness.trading.execute(TradeAction.CancelOrder(owner, "ETH", "62467226234"), prompt).toList().last()

    assertIs<ActionState.Pending>(pending)
    assertEquals(1, harness.actions.reconcile().unresolved)
    open = "[]"
    assertEquals(1, harness.actions.reconcile().finalized)
  }

  @Test
  fun leverageRespectsTheMarketsLimits() = runTest {
    val harness = harness()
    harness.submitter.outcome = { ExchangeOutcome.Accepted(ExchangeResponse.Default) }

    val cross = harness.configure("BTC", MarginMode.CROSS, 10)
    val isolated = harness.configure("ISO", MarginMode.ISOLATED, 3)
    // The screens ask for cross by default; an isolated-only market stays isolated.
    val crossOnIsolatedOnly = harness.configure("ISO", MarginMode.CROSS, 2)
    val tooHigh = harness.configure("BTC", MarginMode.CROSS, 41)

    assertEquals(
      listOf(
        """{"type":"updateLeverage","asset":0,"isCross":true,"leverage":10}""",
        """{"type":"updateLeverage","asset":5,"isCross":false,"leverage":3}""",
        """{"type":"updateLeverage","asset":5,"isCross":false,"leverage":2}""",
      ),
      harness.wires(),
    )
    assertIs<ActionState.Committed>(cross)
    assertIs<ActionState.Committed>(isolated)
    assertIs<ActionState.Committed>(crossOnIsolatedOnly)
    assertTrue(assertIs<ActionState.Failed>(tooHigh).definitelyNotSubmitted)
  }

  @Test
  fun leverageIsSentOnlyWhenItChangesAndKeepsAnIsolatedMarket() = runTest {
    val harness = harness()
    harness.leverage = """{"type":"isolated","value":10,"rawUsd":"0"}"""
    harness.submitter.outcome = { ExchangeOutcome.Accepted(ExchangeResponse.Default) }

    val unchanged = harness.configure("BTC", MarginMode.CROSS, 10)
    val changed = harness.configure("BTC", MarginMode.CROSS, 5)

    assertEquals(ActionState.Committed("", "Leverage is already 10×."), unchanged)
    assertEquals(listOf("""{"type":"updateLeverage","asset":0,"isCross":false,"leverage":5}"""), harness.wires())
    assertIs<ActionState.Committed>(changed)
    assertTrue(harness.journal.entries.value.isEmpty())
  }

  @Test
  fun anUnreadableSettingSendsNothing() = runTest {
    val harness = harness()
    harness.leverage = null

    val failed = harness.configure("BTC", MarginMode.CROSS, 10)

    assertTrue(assertIs<ActionState.Failed>(failed).definitelyNotSubmitted)
    assertTrue(harness.submitter.sent.isEmpty())
  }

  @Test
  fun leverageIsReconciledFromTheAccountsSetting() = runTest {
    val harness = harness()
    var setting = """{"type":"cross","value":20}"""
    harness.answer = { type, body ->
      if (type == "activeAssetData") {
        assertEquals("BTC", body["coin"]!!.jsonPrimitive.content)
        """{"user":"${owner.lowercase()}","coin":"BTC","leverage":$setting,"maxTradeSzs":["0","0"],"availableToTrade":["0","0"],"markPx":"65000"}"""
      } else assets(type)
    }
    harness.submitter.outcome = { ExchangeOutcome.Unknown("timed out") }

    assertIs<ActionState.Pending>(harness.configure("BTC", MarginMode.ISOLATED, 10))
    assertEquals(1, harness.actions.reconcile().unresolved)
    setting = """{"type":"isolated","value":10,"rawUsd":"0"}"""
    assertEquals(1, harness.actions.reconcile().finalized)
  }

  @Test
  fun positionExitsCoverTheWholePositionAndReplaceTheOldOnes() = runTest {
    val harness = harness()
    harness.answer = { type, _ ->
      when (type) {
        "clearinghouseState" -> SHORT_BTC
        "frontendOpenOrders" -> OLD_BTC_STOP
        else -> assets(type)
      }
    }
    harness.submitter.outcome = { action ->
      if (action.type == "order")
        ExchangeOutcome.Accepted(
          ExchangeResponse.Orders(listOf(OrderStatus.WaitingForTrigger, OrderStatus.WaitingForTrigger))
        )
      else ExchangeOutcome.Accepted(ExchangeResponse.Cancels(listOf(CancelStatus.Success)))
    }

    val result =
      harness.trading
        .execute(
          TradeAction.SetPositionTpSl(
            owner, BTC, takeProfitTrigger = "60000", takeProfitLimit = "60000",
            stopLossTrigger = "70000", stopLossLimit = "70000",
          ),
          prompt,
        )
        .toList()
        .last()

    assertEquals(
      listOf(
        """{"type":"order","orders":[{"a":0,"b":true,"p":"66000","s":"0","r":true,"t":{"trigger":{"isMarket":true,"triggerPx":"60000","tpsl":"tp"}},"c":"0x0123456789abcdef0123456789abcdef"},{"a":0,"b":true,"p":"77000","s":"0","r":true,"t":{"trigger":{"isMarket":true,"triggerPx":"70000","tpsl":"sl"}}}],"grouping":"positionTpsl"}""",
        """{"type":"cancel","cancels":[{"a":0,"o":571747466629}]}""",
      ),
      harness.wires(),
    )
    assertEquals(ActionState.Committed(CLOID.hex), result)
  }

  @Test
  fun positionExitsNeedAnOpenPosition() = runTest {
    val harness = harness()
    harness.answer = { type, _ -> if (type == "clearinghouseState") Fixtures.CLEARINGHOUSE_STATE_EMPTY else assets(type) }

    val failed =
      harness.trading.execute(TradeAction.SetPositionTpSl(owner, BTC, stopLossTrigger = "60000"), prompt).toList().last()

    assertTrue(assertIs<ActionState.Failed>(failed).definitelyNotSubmitted)
    assertTrue(harness.submitter.sent.isEmpty())
  }

  @Test
  fun aPositionsExitIsCanceledByOid() = runTest {
    val harness = harness()
    harness.submitter.outcome = { ExchangeOutcome.Accepted(ExchangeResponse.Cancels(listOf(CancelStatus.Success))) }

    val result =
      harness.trading.execute(TradeAction.CancelPositionTpSl(owner, "BTC", "571747466629"), prompt).toList().last()

    assertEquals("""{"type":"cancel","cancels":[{"a":0,"o":571747466629}]}""", harness.wire())
    assertIs<ActionState.Committed>(result)
  }

  @Test
  fun closingIsAReduceOnlyIocForTheWholeSizeAtTheSlippageBound() = runTest {
    val harness = harness()
    harness.submitter.outcome = {
      ExchangeOutcome.Accepted(ExchangeResponse.Orders(listOf(OrderStatus.Filled("0.76666", "64990", 9L))))
    }
    val close =
      check(
        BTC,
        OrderRequest("BTC", OrderSide.SELL, OrderType.MARKET, "0.76666", reduceOnly = true, slippageBps = 50),
        book(bid = "64990", ask = "65000"),
      )

    val result = harness.place(BTC, close)

    assertEquals(
      """{"type":"order","orders":[{"a":0,"b":false,"p":"64666","s":"0.76666","r":true,"t":{"limit":{"tif":"Ioc"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na"}""",
      harness.wire(),
    )
    assertEquals(ActionState.Committed("9", "Filled 0.76666 at 64990."), result)
  }

  @Test
  fun newJournaledActionsSurviveARestart() {
    val cancel = JournaledAction.CancelOrders(owner, listOf("123", CLOID.hex), 5L)
    val leverage = JournaledAction.UpdateLeverage(owner, "xyz:ABC/USD", isCross = false, leverage = 3, lapsesAtMs = 6L)

    assertEquals(cancel, JournaledAction.decode(JournaledAction.encode(cancel)))
    assertEquals(leverage, JournaledAction.decode(JournaledAction.encode(leverage)))
  }

  private class Harness(val setup: SetupHarness) {
    /** The market's leverage setting as `activeAssetData` reports it; null fails the read. */
    var leverage: String? = """{"type":"cross","value":20}"""
    val submitter get() = setup.submitter
    val journal get() = setup.journal
    val actions get() = setup.actions
    val wallet get() = setup.wallet
    var now: Long
      get() = setup.now
      set(value) {
        setup.now = value
      }
    var answer: (String, kotlinx.serialization.json.JsonObject) -> String?
      get() = setup.answer
      set(value) {
        setup.answer = value
      }
    val trading =
      HyperliquidTradingRepository(
        setup.client,
        setup.actions,
        setup.setup,
        HyperliquidFunds(setup.client, setup.actions, setup.preferences) { setup.now },
        HyperliquidAssets(setup.client),
        newCloid = { CLOID },
      )

    fun wires(): List<String> = submitter.sent.map { (it.first as L1Action).toWire().toString() }

    fun wire(): String = wires().last()

    suspend fun place(market: Market, order: OrderRequest): ActionState =
      trading.execute(TradeAction.PlaceOrder(SetupHarness.OWNER, market, order), VaultPrompt("Order", "")).toList().last()

    suspend fun configure(coin: String, mode: MarginMode, leverage: Int): ActionState =
      trading
        .execute(TradeAction.ConfigureMarket(SetupHarness.OWNER, coin, mode, leverage), VaultPrompt("Leverage", ""))
        .toList()
        .last()
  }

  private suspend fun harness(): Harness {
    val setup = SetupHarness().withOwner()
    setup.preferences.setApiWallet(AGENT)
    val harness = Harness(setup)
    setup.answer = { type, body ->
      if (type == "activeAssetData") harness.leverage?.let { activeAssetData(body["coin"]!!.jsonPrimitive.content, it) }
      else assets(type)
    }
    return harness
  }

  private fun activeAssetData(coin: String, leverage: String) =
    """{"user":"${owner.lowercase()}","coin":"$coin","leverage":$leverage,"maxTradeSzs":["0","0"],"availableToTrade":["0","0"],"markPx":"65000"}"""

  private fun check(market: Market, order: OrderRequest, book: OrderBook? = null): OrderRequest =
    assertIs<OrderCheck.Valid>(market.rules.check(order, book)).order

  private fun limit(
    coin: String,
    side: OrderSide,
    size: String,
    price: String,
    timeInForce: TimeInForce = TimeInForce.GOOD_TILL_CANCELLED,
  ): OrderRequest =
    check(
      MARKETS.getValue(coin),
      OrderRequest(coin, side, OrderType.LIMIT, size, price = price, timeInForce = timeInForce),
    )

  private fun book(bid: String, ask: String) =
    OrderBook("BTC", bids = listOf(OrderBookLevel(bid, "1")), asks = listOf(OrderBookLevel(ask, "1")))

  private fun resting(oid: Long) =
    ExchangeOutcome.Accepted(ExchangeResponse.Orders(listOf(OrderStatus.Resting(oid, CLOID))))

  private companion object {
    const val AGENT = "0x1000000000000000000000000000000000000009"
    const val BUILDER = "0x8C967E73E7B15087c42A10D344cFf4c96D877f1D"
    val CLOID = Cloid.parse("0x0123456789abcdef0123456789abcdef")

    /** Perps BTC, ETH and an isolated-only market at asset 5; spot HYPE/USDC at index 107. */
    const val META =
      """{"universe":[{"szDecimals":5,"name":"BTC","maxLeverage":40},{"szDecimals":4,"name":"ETH","maxLeverage":25},{"szDecimals":2,"name":"SOL","maxLeverage":10},{"szDecimals":2,"name":"APT","maxLeverage":3},{"szDecimals":2,"name":"ATOM","maxLeverage":10},{"szDecimals":2,"name":"ISO","maxLeverage":3,"onlyIsolated":true}]}"""
    const val SPOT_META =
      """{"universe":[{"tokens":[1,0],"name":"@107","index":107}],"tokens":[{"name":"USDC","szDecimals":8,"weiDecimals":8,"index":0,"tokenId":"0x6d1e7cde53ba9467b783cb7c530ce054"},{"name":"HYPE","szDecimals":2,"weiDecimals":8,"index":1,"tokenId":"0x7317beb7cceed72ef0b346074cc8e7ab"}]}"""

    val SHORT_BTC = Fixtures.CLEARINGHOUSE_STATE.replace("\"szi\":\"0.76666\"", "\"szi\":\"-0.76666\"")
    val OLD_BTC_STOP =
      """[{"coin":"BTC","side":"B","limitPx":"77000","sz":"0.0","oid":571747466629,"timestamp":1791784718318,"triggerCondition":"Price above 70000","isTrigger":true,"triggerPx":"70000.0","children":[],"isPositionTpsl":true,"reduceOnly":true,"orderType":"Stop Market","origSz":"0.0","tif":null,"cloid":null}]"""

    val BTC = market("BTC", "BTC", MarketKind.PERP, 5)
    val ETH = market("ETH", "ETH", MarketKind.PERP, 4)
    val HYPE = market("@107", "HYPE", MarketKind.SPOT, 2)
    val MARKETS = mapOf("BTC" to BTC, "ETH" to ETH, "@107" to HYPE)

    fun market(coin: String, symbol: String, kind: MarketKind, szDecimals: Int): Market {
      val rules = HyperliquidOrderRules(coin, symbol, kind, szDecimals)
      return Market(coin, symbol, "$symbol/USD", kind, "crypto", rules.maxPriceDecimals, szDecimals, 40, false, rules)
    }

    fun assets(type: String): String? =
      when (type) {
        "meta" -> META
        "spotMeta" -> SPOT_META
        else -> null
      }
  }
}
