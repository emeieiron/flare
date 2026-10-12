package xyz.mcxross.flare.hyperliquid.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment
import xyz.mcxross.flare.hyperliquid.signing.VectorCases

/** Wire JSON: field names and order as the Python SDK sends them. */
class ExchangeActionsTest {
  private val testnet = HyperliquidDeployment.Testnet

  @Test
  fun writesOrdersWithEveryOptionalField() {
    val case = VectorCases.l1.single { it.name == "orderBulkBuilder" }

    assertEquals(
      """{"type":"order","orders":[""" +
        """{"a":3,"b":false,"p":"98765.4","s":"0.00123","r":false,"t":{"limit":{"tif":"Alo"}},""" +
        """"c":"0x0123456789abcdef0123456789abcdef"},""" +
        """{"a":3,"b":true,"p":"90000","s":"0.00123","r":true,""" +
        """"t":{"trigger":{"isMarket":false,"triggerPx":"91000.5","tpsl":"tp"}}},""" +
        """{"a":10107,"b":true,"p":"0.000123","s":"1234.5","r":false,""" +
        """"t":{"limit":{"tif":"Ioc"}}}],"grouping":"normalTpsl",""" +
        """"builder":{"b":"0x8c967e73e7b15087c42a10d344cff4c96d877f1d","f":10}}""",
      case.action.toString(),
    )
  }

  @Test
  fun writesPricesAndSizesWithoutTrailingZeros() {
    val order =
      OrderRequest(
        asset = 0,
        isBuy = true,
        price = "1670.10".toHlDecimal(),
        size = "100.000".toHlDecimal(),
        reduceOnly = false,
        type = OrderType.Limit(TimeInForce.GTC),
      )

    assertEquals(
      """{"type":"order","orders":[{"a":0,"b":true,"p":"1670.1","s":"100","r":false,""" +
        """"t":{"limit":{"tif":"Gtc"}}}],"grouping":"na"}""",
      PlaceOrders(listOf(order)).toWire().toString(),
    )
  }

  @Test
  fun rejectsPricesFinerThanTheWireCarries() {
    val order =
      OrderRequest(
        asset = 0,
        isBuy = true,
        price = "0.000000001".toHlDecimal(),
        size = "1".toHlDecimal(),
        reduceOnly = false,
        type = OrderType.Limit(TimeInForce.GTC),
      )

    assertFailsWith<IllegalArgumentException> { PlaceOrders(listOf(order)).toWire() }
  }

  @Test
  fun validatesOrders() {
    fun order(price: String, size: String) =
      OrderRequest(
        asset = 0,
        isBuy = true,
        price = price.toHlDecimal(),
        size = size.toHlDecimal(),
        reduceOnly = false,
        type = OrderType.Limit(TimeInForce.GTC),
      )

    assertFailsWith<IllegalArgumentException> { order("0", "1") }
    assertFailsWith<IllegalArgumentException> { order("1", "0") }
    assertFailsWith<IllegalArgumentException> { order("-1", "1") }
    assertFailsWith<IllegalArgumentException> { PlaceOrders(emptyList()) }
    assertFailsWith<IllegalArgumentException> { CancelOrders(emptyList()) }
  }

  @Test
  fun writesL1Actions() {
    assertEquals(
      """{"type":"cancel","cancels":[{"a":3,"o":62467226234},{"a":10107,"o":1}]}""",
      wire("cancel"),
    )
    assertEquals(
      """{"type":"cancelByCloid",""" +
        """"cancels":[{"asset":3,"cloid":"0x0123456789abcdef0123456789abcdef"}]}""",
      wire("cancelByCloid"),
    )
    assertEquals(
      """{"type":"updateLeverage","asset":3,"isCross":false,"leverage":20}""",
      wire("updateLeverage"),
    )
    assertEquals(
      """{"type":"updateIsolatedMargin","asset":3,"isBuy":true,"ntli":-1500000}""",
      wire("updateIsolatedMargin"),
    )
    assertEquals(
      """{"type":"twapOrder","twap":{"a":3,"b":true,"s":"0.05","r":false,"m":30,"t":true}}""",
      wire("twapOrder"),
    )
    assertEquals("""{"type":"twapCancel","a":3,"t":77738308}""", wire("twapCancel"))
    assertEquals(
      """{"type":"vaultTransfer","vaultAddress":"0xa15099a30bbf2e68942d6f4c43d70d04faeab0a0",""" +
        """"isDeposit":true,"usd":5000000}""",
      wire("vaultTransfer"),
    )
    assertEquals("""{"type":"createSubAccount","name":"example"}""", wire("createSubAccount"))
    assertEquals("""{"type":"scheduleCancel"}""", wire("scheduleCancel"))
    assertEquals("""{"type":"scheduleCancel","time":123456789}""", wire("scheduleCancelTime"))
  }

  @Test
  fun keepsVaultTransfersAndSubAccountCreationOffVaultAddresses() {
    assertFalse(VaultTransfer(VectorCases.HLP_VAULT, true, 1L).allowsVaultAddress)
    assertFalse(CreateSubAccount("a").allowsVaultAddress)
    assertTrue(CancelTwap(0, 1L).allowsVaultAddress)
  }

  @Test
  fun writesUserSignedActions() {
    assertEquals(
      """{"type":"usdSend","signatureChainId":"0x66eee","hyperliquidChain":"Testnet",""" +
        """"destination":"0x5e9ee1089755c3435139848e47e6635505d5a13a","amount":"1",""" +
        """"time":1687816341423}""",
      userWire("usdSend"),
    )
    assertEquals(
      """{"type":"withdraw3","signatureChainId":"0x66eee","hyperliquidChain":"Testnet",""" +
        """"destination":"0x5e9ee1089755c3435139848e47e6635505d5a13a","amount":"1",""" +
        """"time":1687816341423}""",
      userWire("withdraw3"),
    )
    assertEquals(
      """{"type":"approveAgent","signatureChainId":"0x66eee","hyperliquidChain":"Testnet",""" +
        """"agentAddress":"0x5e9ee1089755c3435139848e47e6635505d5a13a","agentName":"flare",""" +
        """"nonce":1700000000000}""",
      userWire("approveAgent"),
    )
    // Unnamed: signed with agentName "" but sent without the field, as the SDK does.
    assertEquals(
      """{"type":"approveAgent","signatureChainId":"0x66eee","hyperliquidChain":"Testnet",""" +
        """"agentAddress":"0x5e9ee1089755c3435139848e47e6635505d5a13a","nonce":1700000000000}""",
      userWire("approveAgentUnnamed"),
    )
    assertEquals(
      """{"type":"approveBuilderFee","signatureChainId":"0x66eee","hyperliquidChain":"Testnet",""" +
        """"maxFeeRate":"0.001%","builder":"0x8c967e73e7b15087c42a10d344cff4c96d877f1d",""" +
        """"nonce":1700000000000}""",
      userWire("approveBuilderFee"),
    )
    assertEquals(
      """{"type":"usdClassTransfer","signatureChainId":"0x66eee","hyperliquidChain":"Testnet",""" +
        """"amount":"1 subaccount:0x1719884eb866cb12b2287399b15f7db5e7d775ea","toPerp":false,""" +
        """"nonce":1700000000000}""",
      userWire("usdClassTransferSubAccount"),
    )
  }

  @Test
  fun usesTheMainnetChainOnMainnet() {
    val wire =
      UsdSend(VectorCases.DESTINATION, "2.5".toHlDecimal())
        .toWire(HyperliquidDeployment.Mainnet, 7L)

    assertEquals(
      """{"type":"usdSend","signatureChainId":"0x66eee","hyperliquidChain":"Mainnet",""" +
        """"destination":"0x5e9ee1089755c3435139848e47e6635505d5a13a","amount":"2.5","time":7}""",
      wire.toString(),
    )
  }

  @Test
  fun formatsBuilderFeeRates() {
    fun rate(tenths: Int) = ApproveBuilderFee.ofTenthsOfBasisPoint(VectorCases.BUILDER, tenths)

    assertEquals("0.001%", rate(1).maxFeeRate)
    assertEquals("0.01%", rate(10).maxFeeRate)
    assertEquals("0.1%", rate(100).maxFeeRate)
    assertEquals("0%", ApproveBuilderFee.revoke(VectorCases.BUILDER).maxFeeRate)
    assertFailsWith<IllegalArgumentException> { ApproveBuilderFee(VectorCases.BUILDER, "0.001") }
    assertFailsWith<IllegalArgumentException> { ApproveBuilderFee(VectorCases.BUILDER, "-1%") }
    assertFailsWith<IllegalArgumentException> { ApproveBuilderFee("0x1234", "0.001%") }
  }

  @Test
  fun convertsUsdcToMicros() {
    assertEquals(5_000_000L, usdcToMicros("5".toHlDecimal()))
    assertEquals(1_234_567L, usdcToMicros("1.234567".toHlDecimal()))
    assertEquals(-1_500_000L, usdcToMicros("-1.5".toHlDecimal()))
    assertFailsWith<IllegalArgumentException> { usdcToMicros("0.0000001".toHlDecimal()) }
    assertFailsWith<IllegalArgumentException> { VaultTransfer(VectorCases.HLP_VAULT, true, 0L) }
  }

  private fun wire(name: String): String =
    VectorCases.l1.single { it.name == name }.action.toString()

  private fun userWire(name: String): String {
    val case = VectorCases.user.single { it.name == name }
    val wire: JsonObject = case.action.toWire(testnet, case.nonce)
    return wire.toString()
  }
}
