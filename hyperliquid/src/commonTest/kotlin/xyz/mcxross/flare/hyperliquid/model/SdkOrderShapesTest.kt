package xyz.mcxross.flare.hyperliquid.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import xyz.mcxross.flare.hyperliquid.signing.MsgPack
import xyz.mcxross.flare.hyperliquid.signing.toHexDigits

/**
 * The order shapes Flare sends, against what hyperliquid-python-sdk 0.24.0 builds for the same
 * orders (`order_request_to_order_wire`, `order_wires_to_order_action`, then `msgpack.packb`).
 */
class SdkOrderShapesTest {
  @Test
  fun writesMarketIocWithABuilderFee() =
    check(
      PlaceOrders(listOf(order(0, true, "65325", "0.01", type = ioc, cloid = CLOID)), builder = BUILDER),
      """{"type":"order","orders":[{"a":0,"b":true,"p":"65325","s":"0.01","r":false,"t":{"limit":{"tif":"Ioc"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na","builder":{"b":"0x8c967e73e7b15087c42a10d344cff4c96d877f1d","f":50}}""",
      "84a474797065a56f72646572a66f72646572739187a16100a162c3a170a53635333235a173a4302e3031a172c2a17481a56c696d697481a3746966a3496f63a163d92230783031323334353637383961626364656630313233343536373839616263646566a867726f7570696e67a26e61a76275696c64657282a162d92a307838633936376537336537623135303837633432613130643334346366663463393664383737663164a16632",
    )

  @Test
  fun writesLimitOrdersForEachTimeInForce() {
    check(
      PlaceOrders(listOf(order(0, false, "70000", "0.5", type = gtc, cloid = CLOID))),
      """{"type":"order","orders":[{"a":0,"b":false,"p":"70000","s":"0.5","r":false,"t":{"limit":{"tif":"Gtc"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na"}""",
      "83a474797065a56f72646572a66f72646572739187a16100a162c2a170a53730303030a173a3302e35a172c2a17481a56c696d697481a3746966a3477463a163d92230783031323334353637383961626364656630313233343536373839616263646566a867726f7570696e67a26e61",
    )
    check(
      PlaceOrders(listOf(order(1, true, "3000.5", "1.25", type = OrderType.Limit(TimeInForce.ALO), cloid = CLOID))),
      """{"type":"order","orders":[{"a":1,"b":true,"p":"3000.5","s":"1.25","r":false,"t":{"limit":{"tif":"Alo"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na"}""",
      "83a474797065a56f72646572a66f72646572739187a16101a162c3a170a6333030302e35a173a4312e3235a172c2a17481a56c696d697481a3746966a3416c6fa163d92230783031323334353637383961626364656630313233343536373839616263646566a867726f7570696e67a26e61",
    )
    check(
      PlaceOrders(listOf(order(0, false, "64000", "0.01", reduceOnly = true, type = ioc, cloid = CLOID))),
      """{"type":"order","orders":[{"a":0,"b":false,"p":"64000","s":"0.01","r":true,"t":{"limit":{"tif":"Ioc"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na"}""",
      "83a474797065a56f72646572a66f72646572739187a16100a162c2a170a53634303030a173a4302e3031a172c3a17481a56c696d697481a3746966a3496f63a163d92230783031323334353637383961626364656630313233343536373839616263646566a867726f7570696e67a26e61",
    )
  }

  @Test
  fun writesSpotOrdersWithTheSpotAssetId() =
    check(
      PlaceOrders(listOf(order(10107, true, "25.123", "2", type = gtc, cloid = CLOID))),
      """{"type":"order","orders":[{"a":10107,"b":true,"p":"25.123","s":"2","r":false,"t":{"limit":{"tif":"Gtc"}},"c":"0x0123456789abcdef0123456789abcdef"}],"grouping":"na"}""",
      "83a474797065a56f72646572a66f72646572739187a161cd277ba162c3a170a632352e313233a173a132a172c2a17481a56c696d697481a3746966a3477463a163d92230783031323334353637383961626364656630313233343536373839616263646566a867726f7570696e67a26e61",
    )

  @Test
  fun writesAttachedTakeProfitAndStopLoss() =
    check(
      PlaceOrders(
        listOf(
          order(0, true, "65000", "0.01", type = gtc, cloid = CLOID),
          order(0, false, "63000", "0.01", reduceOnly = true, type = trigger("70000", TpSl.TAKE_PROFIT)),
          order(0, false, "54000", "0.01", reduceOnly = true, type = trigger("60000", TpSl.STOP_LOSS)),
        ),
        OrderGrouping.NORMAL_TPSL,
        BUILDER,
      ),
      """{"type":"order","orders":[{"a":0,"b":true,"p":"65000","s":"0.01","r":false,"t":{"limit":{"tif":"Gtc"}},"c":"0x0123456789abcdef0123456789abcdef"},{"a":0,"b":false,"p":"63000","s":"0.01","r":true,"t":{"trigger":{"isMarket":true,"triggerPx":"70000","tpsl":"tp"}}},{"a":0,"b":false,"p":"54000","s":"0.01","r":true,"t":{"trigger":{"isMarket":true,"triggerPx":"60000","tpsl":"sl"}}}],"grouping":"normalTpsl","builder":{"b":"0x8c967e73e7b15087c42a10d344cff4c96d877f1d","f":50}}""",
      "84a474797065a56f72646572a66f72646572739387a16100a162c3a170a53635303030a173a4302e3031a172c2a17481a56c696d697481a3746966a3477463a163d9223078303132333435363738396162636465663031323334353637383961626364656686a16100a162c2a170a53633303030a173a4302e3031a172c3a17481a77472696767657283a869734d61726b6574c3a9747269676765725078a53730303030a47470736ca2747086a16100a162c2a170a53534303030a173a4302e3031a172c3a17481a77472696767657283a869734d61726b6574c3a9747269676765725078a53630303030a47470736ca2736ca867726f7570696e67aa6e6f726d616c5470736ca76275696c64657282a162d92a307838633936376537336537623135303837633432613130643334346366663463393664383737663164a16632",
    )

  @Test
  fun writesWholePositionExitsWithSizeZero() =
    check(
      PlaceOrders(
        listOf(
          order(0, true, "66000", "0", reduceOnly = true, type = trigger("60000", TpSl.TAKE_PROFIT), cloid = CLOID),
          order(0, true, "77000", "0", reduceOnly = true, type = trigger("70000", TpSl.STOP_LOSS)),
        ),
        OrderGrouping.POSITION_TPSL,
      ),
      """{"type":"order","orders":[{"a":0,"b":true,"p":"66000","s":"0","r":true,"t":{"trigger":{"isMarket":true,"triggerPx":"60000","tpsl":"tp"}},"c":"0x0123456789abcdef0123456789abcdef"},{"a":0,"b":true,"p":"77000","s":"0","r":true,"t":{"trigger":{"isMarket":true,"triggerPx":"70000","tpsl":"sl"}}}],"grouping":"positionTpsl"}""",
      "83a474797065a56f72646572a66f72646572739287a16100a162c3a170a53636303030a173a130a172c3a17481a77472696767657283a869734d61726b6574c3a9747269676765725078a53630303030a47470736ca27470a163d9223078303132333435363738396162636465663031323334353637383961626364656686a16100a162c3a170a53737303030a173a130a172c3a17481a77472696767657283a869734d61726b6574c3a9747269676765725078a53730303030a47470736ca2736ca867726f7570696e67ac706f736974696f6e5470736c",
    )

  @Test
  fun onlyAReduceOnlyTriggerMayHaveSizeZero() {
    assertFailsWith<IllegalArgumentException> { order(0, true, "1", "0", type = gtc) }
    assertFailsWith<IllegalArgumentException> {
      order(0, true, "1", "0", type = trigger("1", TpSl.STOP_LOSS))
    }
  }

  @Test
  fun writesCancelsAndLeverage() {
    check(
      CancelOrders(listOf(CancelRequest(0, 123_456L), CancelRequest(10107, 789L))),
      """{"type":"cancel","cancels":[{"a":0,"o":123456},{"a":10107,"o":789}]}""",
      "82a474797065a663616e63656ca763616e63656c739282a16100a16fce0001e24082a161cd277ba16fcd0315",
    )
    check(
      CancelOrdersByCloid(listOf(CancelByCloidRequest(0, Cloid.parse(CLOID_HEX)))),
      """{"type":"cancelByCloid","cancels":[{"asset":0,"cloid":"0x0123456789abcdef0123456789abcdef"}]}""",
      "82a474797065ad63616e63656c4279436c6f6964a763616e63656c739182a5617373657400a5636c6f6964d92230783031323334353637383961626364656630313233343536373839616263646566",
    )
    check(
      UpdateLeverage(0, isCross = true, leverage = 10),
      """{"type":"updateLeverage","asset":0,"isCross":true,"leverage":10}""",
      "84a474797065ae7570646174654c65766572616765a5617373657400a7697343726f7373c3a86c657665726167650a",
    )
    check(
      UpdateLeverage(5, isCross = false, leverage = 3),
      """{"type":"updateLeverage","asset":5,"isCross":false,"leverage":3}""",
      "84a474797065ae7570646174654c65766572616765a5617373657405a7697343726f7373c2a86c6576657261676503",
    )
  }

  private fun check(action: L1Action, json: String, msgpack: String) {
    val wire = action.toWire()
    assertEquals(json, wire.toString())
    assertEquals(msgpack, MsgPack.encode(wire).toHexDigits())
  }

  private fun order(
    asset: Int,
    isBuy: Boolean,
    price: String,
    size: String,
    reduceOnly: Boolean = false,
    type: OrderType,
    cloid: Cloid? = null,
  ) = OrderRequest(asset, isBuy, price.toHlDecimal(), size.toHlDecimal(), reduceOnly, type, cloid)

  private fun trigger(price: String, tpsl: TpSl) =
    OrderType.Trigger(price.toHlDecimal(), isMarket = true, tpsl = tpsl)

  private companion object {
    const val CLOID_HEX = "0x0123456789abcdef0123456789abcdef"
    val CLOID = Cloid.parse(CLOID_HEX)
    val BUILDER = BuilderFee("0x8C967E73E7B15087c42A10D344cFf4c96D877f1D", 50)
    val ioc = OrderType.Limit(TimeInForce.IOC)
    val gtc = OrderType.Limit(TimeInForce.GTC)
  }
}
