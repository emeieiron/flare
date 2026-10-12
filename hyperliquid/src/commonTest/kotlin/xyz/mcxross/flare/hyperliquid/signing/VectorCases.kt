package xyz.mcxross.flare.hyperliquid.signing

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mcxross.flare.hyperliquid.model.ApproveAgent
import xyz.mcxross.flare.hyperliquid.model.ApproveBuilderFee
import xyz.mcxross.flare.hyperliquid.model.BuilderFee
import xyz.mcxross.flare.hyperliquid.model.CancelByCloidRequest
import xyz.mcxross.flare.hyperliquid.model.CancelOrders
import xyz.mcxross.flare.hyperliquid.model.CancelOrdersByCloid
import xyz.mcxross.flare.hyperliquid.model.CancelRequest
import xyz.mcxross.flare.hyperliquid.model.CancelTwap
import xyz.mcxross.flare.hyperliquid.model.Cloid
import xyz.mcxross.flare.hyperliquid.model.CreateSubAccount
import xyz.mcxross.flare.hyperliquid.model.OrderGrouping
import xyz.mcxross.flare.hyperliquid.model.OrderRequest
import xyz.mcxross.flare.hyperliquid.model.OrderType
import xyz.mcxross.flare.hyperliquid.model.PlaceOrders
import xyz.mcxross.flare.hyperliquid.model.PlaceTwap
import xyz.mcxross.flare.hyperliquid.model.ScheduleCancel
import xyz.mcxross.flare.hyperliquid.model.TimeInForce
import xyz.mcxross.flare.hyperliquid.model.TpSl
import xyz.mcxross.flare.hyperliquid.model.UpdateIsolatedMargin
import xyz.mcxross.flare.hyperliquid.model.UpdateLeverage
import xyz.mcxross.flare.hyperliquid.model.UsdClassTransfer
import xyz.mcxross.flare.hyperliquid.model.UsdSend
import xyz.mcxross.flare.hyperliquid.model.UserSignedAction
import xyz.mcxross.flare.hyperliquid.model.VaultTransfer
import xyz.mcxross.flare.hyperliquid.model.Withdraw
import xyz.mcxross.flare.hyperliquid.model.toHlDecimal

/** An L1 action built with this SDK's types, next to the Python SDK's vector for it. */
internal class L1Case(
  val name: String,
  val action: JsonObject,
  val vector: L1Vector,
  val nonce: Long = 0L,
  val vaultAddress: String? = null,
  val expiresAfter: Long? = null,
)

internal class UserCase(
  val name: String,
  val action: UserSignedAction,
  val vector: UserVector,
  val nonce: Long,
)

/** The actions behind [SdkVectors], rebuilt from this SDK's typed actions where one exists. */
@OptIn(ExperimentalSerializationApi::class)
internal object VectorCases {
  const val VAULT = "0x1719884eb866cb12b2287399b15f7db5e7d775ea"
  const val NONCE = 1_700_000_000_000L
  const val CLOID = "0x0123456789abcdef0123456789abcdef"
  const val BUILDER = "0x8c967e73e7b15087c42a10d344cff4c96d877f1d"
  const val DESTINATION = "0x5e9ee1089755c3435139848e47e6635505d5a13a"
  const val HLP_VAULT = "0xa15099a30bbf2e68942d6f4c43d70d04faeab0a0"

  private fun gtc100(cloid: Cloid? = null) =
    PlaceOrders(
      listOf(
        OrderRequest(
          asset = 1,
          isBuy = true,
          price = "100".toHlDecimal(),
          size = "100".toHlDecimal(),
          reduceOnly = false,
          type = OrderType.Limit(TimeInForce.GTC),
          cloid = cloid,
        )
      )
    )

  private fun dummy(num: String) = buildJsonObject {
    put("type", "dummy")
    put("num", JsonUnquotedLiteral(num))
  }

  val l1: List<L1Case> =
    listOf(
      L1Case(
        "phantomAgent",
        PlaceOrders(
            listOf(
              OrderRequest(
                asset = 4,
                isBuy = true,
                price = "1670.1".toHlDecimal(),
                size = "0.0147".toHlDecimal(),
                reduceOnly = false,
                type = OrderType.Limit(TimeInForce.IOC),
              )
            )
          )
          .toWire(),
        SdkVectors.PHANTOM_AGENT,
        nonce = 1_677_777_606_040L,
      ),
      // float_to_int_for_hashing(1000) == 100000000000.
      L1Case("dummy", dummy("100000000000"), SdkVectors.DUMMY),
      L1Case("dummyVault", dummy("100000000000"), SdkVectors.DUMMY_VAULT, vaultAddress = VAULT),
      // float_to_int_for_hashing(123123123123) == 12312312312300000000, above Long.MAX_VALUE.
      L1Case("dummyUint64", dummy("12312312312300000000"), SdkVectors.DUMMY_UINT64),
      L1Case("order", gtc100().toWire(), SdkVectors.ORDER),
      L1Case(
        "orderCloid",
        gtc100(Cloid.parse("0x00000000000000000000000000000001")).toWire(),
        SdkVectors.ORDER_CLOID,
      ),
      L1Case(
        "orderTpsl",
        PlaceOrders(
            listOf(
              OrderRequest(
                asset = 1,
                isBuy = true,
                price = "100".toHlDecimal(),
                size = "100".toHlDecimal(),
                reduceOnly = false,
                type = OrderType.Trigger("103".toHlDecimal(), isMarket = true, TpSl.STOP_LOSS),
              )
            )
          )
          .toWire(),
        SdkVectors.ORDER_TPSL,
      ),
      L1Case("createSubAccount", CreateSubAccount("example").toWire(), SdkVectors.CREATE_SUB_ACCOUNT),
      L1Case(
        "subAccountTransfer",
        buildJsonObject {
          put("type", "subAccountTransfer")
          put("subAccountUser", "0x1d9470d4b963f552e6f671a81619d395877bf409")
          put("isDeposit", true)
          put("usd", 10)
        },
        SdkVectors.SUB_ACCOUNT_TRANSFER,
      ),
      L1Case("scheduleCancel", ScheduleCancel().toWire(), SdkVectors.SCHEDULE_CANCEL),
      L1Case(
        "scheduleCancelTime",
        ScheduleCancel(123_456_789L).toWire(),
        SdkVectors.SCHEDULE_CANCEL_TIME,
      ),
      L1Case(
        "orderBulkBuilder",
        PlaceOrders(
            listOf(
              OrderRequest(
                asset = 3,
                isBuy = false,
                price = "98765.4".toHlDecimal(),
                size = "0.00123".toHlDecimal(),
                reduceOnly = false,
                type = OrderType.Limit(TimeInForce.ALO),
                cloid = Cloid.parse(CLOID),
              ),
              OrderRequest(
                asset = 3,
                isBuy = true,
                price = "90000".toHlDecimal(),
                size = "0.00123".toHlDecimal(),
                reduceOnly = true,
                type =
                  OrderType.Trigger("91000.5".toHlDecimal(), isMarket = false, TpSl.TAKE_PROFIT),
              ),
              OrderRequest(
                asset = 10107,
                isBuy = true,
                price = "0.000123".toHlDecimal(),
                size = "1234.5".toHlDecimal(),
                reduceOnly = false,
                type = OrderType.Limit(TimeInForce.IOC),
              ),
            ),
            grouping = OrderGrouping.NORMAL_TPSL,
            // Mixed case on purpose: the wire carries it lower-cased, as the SDK does.
            builder = BuilderFee("0x8c967E73E7B15087c42A10D344cFf4c96D877f1D", 10),
          )
          .toWire(),
        SdkVectors.ORDER_BULK_BUILDER,
        nonce = NONCE,
      ),
      L1Case(
        "orderPositionTpslExpires",
        PlaceOrders(
            listOf(
              OrderRequest(
                asset = 0,
                isBuy = false,
                price = "140.25".toHlDecimal(),
                size = "2.5".toHlDecimal(),
                reduceOnly = true,
                type = OrderType.Trigger("150".toHlDecimal(), isMarket = true, TpSl.STOP_LOSS),
              )
            ),
            grouping = OrderGrouping.POSITION_TPSL,
          )
          .toWire(),
        SdkVectors.ORDER_POSITION_TPSL_EXPIRES,
        nonce = NONCE,
        vaultAddress = VAULT,
        expiresAfter = NONCE + 60_000L,
      ),
      L1Case(
        "orderExpires",
        gtc100().toWire(),
        SdkVectors.ORDER_EXPIRES,
        nonce = NONCE,
        expiresAfter = NONCE + 60_000L,
      ),
      L1Case(
        "cancel",
        CancelOrders(listOf(CancelRequest(3, 62_467_226_234L), CancelRequest(10107, 1L))).toWire(),
        SdkVectors.CANCEL,
        nonce = NONCE,
      ),
      L1Case(
        "cancelByCloid",
        CancelOrdersByCloid(listOf(CancelByCloidRequest(3, Cloid.parse(CLOID)))).toWire(),
        SdkVectors.CANCEL_BY_CLOID,
        nonce = NONCE,
      ),
      L1Case(
        "updateLeverage",
        UpdateLeverage(asset = 3, isCross = false, leverage = 20).toWire(),
        SdkVectors.UPDATE_LEVERAGE,
        nonce = NONCE,
      ),
      L1Case(
        "updateIsolatedMargin",
        UpdateIsolatedMargin.ofUsdc(3, "-1.5".toHlDecimal()).toWire(),
        SdkVectors.UPDATE_ISOLATED_MARGIN,
        nonce = NONCE,
      ),
      L1Case(
        "twapOrder",
        PlaceTwap(
            asset = 3,
            isBuy = true,
            size = "0.05".toHlDecimal(),
            reduceOnly = false,
            minutes = 30,
            randomize = true,
          )
          .toWire(),
        SdkVectors.TWAP_ORDER,
        nonce = NONCE,
      ),
      L1Case("twapCancel", CancelTwap(3, 77_738_308L).toWire(), SdkVectors.TWAP_CANCEL, nonce = NONCE),
      L1Case(
        "vaultTransfer",
        VaultTransfer.ofUsdc(HLP_VAULT, isDeposit = true, "5".toHlDecimal()).toWire(),
        SdkVectors.VAULT_TRANSFER,
        nonce = NONCE,
      ),
    )

  val user: List<UserCase> =
    listOf(
      UserCase("usdSend", UsdSend(DESTINATION, "1".toHlDecimal()), SdkVectors.USD_SEND, 1_687_816_341_423L),
      UserCase(
        "withdraw3",
        Withdraw(DESTINATION, "1".toHlDecimal()),
        SdkVectors.WITHDRAW3,
        1_687_816_341_423L,
      ),
      UserCase("approveAgent", ApproveAgent(DESTINATION, "flare"), SdkVectors.APPROVE_AGENT, NONCE),
      UserCase(
        "approveAgentUnnamed",
        ApproveAgent(DESTINATION),
        SdkVectors.APPROVE_AGENT_UNNAMED,
        NONCE,
      ),
      UserCase(
        "approveBuilderFee",
        ApproveBuilderFee.ofTenthsOfBasisPoint(BUILDER, 1),
        SdkVectors.APPROVE_BUILDER_FEE,
        NONCE,
      ),
      UserCase(
        "usdClassTransfer",
        UsdClassTransfer("12.5".toHlDecimal(), toPerp = true),
        SdkVectors.USD_CLASS_TRANSFER,
        NONCE,
      ),
      UserCase(
        "usdClassTransferSubAccount",
        UsdClassTransfer("1".toHlDecimal(), toPerp = false, subAccount = VAULT),
        SdkVectors.USD_CLASS_TRANSFER_SUB_ACCOUNT,
        NONCE,
      ),
    )
}
