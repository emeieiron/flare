package xyz.mcxross.flare.hyperliquid.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.hyperliquid.Fixtures
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.Side

class StreamDataTest {
  private val json = HyperliquidClient.DefaultJson

  @Test
  fun decodesAllMids() {
    val data = assertIs<StreamData.Mids>(decode(Fixtures.WS_ALL_MIDS))
    assertEquals("0.5", data.mids["#102170"])
    assertTrue("@1" in data.mids)
    assertTrue("BTC" in data.mids)
  }

  @Test
  fun decodesBook() {
    val book = assertIs<StreamData.Book>(decode(Fixtures.WS_L2_BOOK)).book
    assertEquals("BTC", book.coin)
    assertEquals("83108.0", book.bids.first().px)
    assertEquals("83109.0", book.asks.first().px)
  }

  @Test
  fun decodesTrades() {
    val trades = assertIs<StreamData.Trades>(decode(Fixtures.WS_TRADES)).trades
    assertEquals(2, trades.size)
    assertEquals(901060856429885L, trades.first().tid)
    assertEquals(Side.BUY, trades.first().side)
  }

  @Test
  fun decodesCandle() {
    val candle = assertIs<StreamData.CandleUpdate>(decode(Fixtures.WS_CANDLE)).candle
    assertEquals(1791768600000L, candle.openTimeMs)
    assertEquals("1m", candle.interval)
    assertEquals("83079.0", candle.close)
    assertEquals(5, candle.trades)
  }

  @Test
  fun decodesPerpAndSpotContexts() {
    val perp = assertIs<StreamData.PerpContext>(decode(Fixtures.WS_ACTIVE_ASSET_CTX))
    assertEquals("BTC", perp.coin)
    assertEquals("83109.0", perp.context.markPx)
    assertEquals(2, perp.context.impactPxs?.size)

    val spot = assertIs<StreamData.SpotContext>(decode(Fixtures.WS_ACTIVE_SPOT_ASSET_CTX))
    assertEquals("PURR/USDC", spot.coin)
    assertEquals("4.6252", spot.context.markPx)
  }

  @Test
  fun decodesActiveAssetData() {
    val data = assertIs<StreamData.AssetData>(decode(Fixtures.WS_ACTIVE_ASSET_DATA)).data
    assertEquals(Fixtures.USER, data.user)
    assertEquals(5, data.leverage.value)
    assertEquals(listOf("4.71793", "6.6984"), data.maxTradeSzs)
    assertEquals("83003.8", data.markPx)
  }

  @Test
  fun decodesAccountStreams() {
    val clearinghouse =
      assertIs<StreamData.Clearinghouse>(decode(Fixtures.WS_CLEARINGHOUSE_STATE))
    assertEquals(Fixtures.USER, clearinghouse.user)
    assertEquals("104966.305173", clearinghouse.state.marginSummary.accountValue)

    val orders = assertIs<StreamData.OpenOrders>(decode(Fixtures.WS_OPEN_ORDERS))
    assertEquals(62467426601L, orders.orders.first().oid)

    val spot = assertIs<StreamData.SpotState>(decode(Fixtures.WS_SPOT_STATE))
    assertEquals("0.00825041", spot.state.balances.first().total)

    val twaps = assertIs<StreamData.TwapStates>(decode(Fixtures.WS_TWAP_STATES))
    assertEquals(Fixtures.USER, twaps.user)
    assertTrue(twaps.states.isEmpty())
  }

  @Test
  fun decodesRunningTwapStates() {
    // Testnet had no running TWAP to capture; this follows the documented `[[id, state], …]` form.
    val payload =
      """{"user":"${Fixtures.USER}","states":[[42,{"coin":"BTC","user":"${Fixtures.USER}","side":"B","sz":"0.5","executedSz":"0.1","executedNtl":"8300.0","minutes":30,"reduceOnly":false,"randomize":true,"timestamp":1791768600000}]]}"""

    val twaps =
      assertIs<StreamData.TwapStates>(decodeStreamData("twapStates", parse(payload), json))
    val (id, state) = twaps.states.single()
    assertEquals(42L, id)
    assertEquals("0.1", state.executedSz)
    assertEquals(30, state.minutes)
  }

  @Test
  fun decodesOrderUpdates() {
    val updates = assertIs<StreamData.OrderUpdates>(decode(Fixtures.WS_ORDER_UPDATES)).updates
    assertEquals("open", updates.first().status)
    assertEquals(62467344389L, updates.first().order.oid)
    assertNull(updates.first().order.cloid)
  }

  @Test
  fun decodesUserFillsWithSnapshotFlag() {
    val snapshot = assertIs<StreamData.UserFills>(decode(Fixtures.WS_USER_FILLS_SNAPSHOT))
    assertTrue(snapshot.isSnapshot)
    assertEquals(2, snapshot.fills.size)
    assertEquals(Fixtures.USER, snapshot.user)

    val live = assertIs<StreamData.UserFills>(decode(Fixtures.WS_USER_FILLS))
    assertFalse(live.isSnapshot)
    assertEquals(1, live.fills.size)
  }

  @Test
  fun decodesUserEvents() {
    val event = assertIs<StreamData.UserEvent>(decode(Fixtures.WS_USER_EVENTS)).event
    assertEquals(62467320706L, event.fills?.single()?.oid)
    assertNull(event.funding)
    assertNull(event.nonUserCancel)
  }

  @Test
  fun decodesFundingAndLedgerSnapshots() {
    val fundings = assertIs<StreamData.UserFundings>(decode(Fixtures.WS_USER_FUNDINGS))
    assertTrue(fundings.isSnapshot)
    assertEquals("26.785405", fundings.fundings.first().usdc)
    assertEquals(1791590400017L, fundings.fundings.first().time)

    val ledger = assertIs<StreamData.LedgerUpdates>(decode(Fixtures.WS_LEDGER_UPDATES))
    assertTrue(ledger.isSnapshot)
    assertEquals("internalTransfer", ledger.updates.first().delta.type)
  }

  @Test
  fun keepsUnknownChannelsRaw() {
    val raw = assertIs<StreamData.Raw>(decode(Fixtures.WS_NOTIFICATION))
    assertEquals(
      "Resting order filled: Sold 0.0002 BTC at $82995",
      raw.payload.jsonObject["notification"]?.jsonPrimitive?.content,
    )
  }

  @Test
  fun reportsMalformedPayloads() {
    assertIs<StreamData.Malformed>(decodeStreamData("l2Book", parse("""{"coin":"BTC"}"""), json))
    assertIs<StreamData.Malformed>(decodeStreamData("trades", parse("""{"coin":"BTC"}"""), json))
    assertIs<StreamData.Malformed>(
      decodeStreamData("userFills", parse("""{"user":"x","fills":[{"coin":1}]}"""), json)
    )
    assertIs<StreamData.Malformed>(decodeStreamData("allMids", null, json))
    assertIs<StreamData.Malformed>(
      decodeStreamData("candle", kotlinx.serialization.json.JsonNull, json)
    )
    assertIs<StreamData.Malformed>(decodeStreamData("allMids", JsonPrimitive("x"), json))
  }

  private fun decode(message: String): StreamData {
    val payload = parse(message)
    val channel = payload.getValue("channel").jsonPrimitive.content
    return decodeStreamData(channel, payload["data"], json)
  }

  private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
}
