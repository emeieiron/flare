package xyz.mcxross.flare.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import xyz.mcxross.flare.hyperliquid.model.Candle
import xyz.mcxross.flare.hyperliquid.model.ClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.ExtraAgent
import xyz.mcxross.flare.hyperliquid.model.Fill
import xyz.mcxross.flare.hyperliquid.model.FrontendOrder
import xyz.mcxross.flare.hyperliquid.model.FundingUpdate
import xyz.mcxross.flare.hyperliquid.model.L2Book
import xyz.mcxross.flare.hyperliquid.model.LedgerUpdate
import xyz.mcxross.flare.hyperliquid.model.MarketTrade
import xyz.mcxross.flare.hyperliquid.model.OrderStatusResult
import xyz.mcxross.flare.hyperliquid.model.OrderWithStatus
import xyz.mcxross.flare.hyperliquid.model.PerpAssetContext
import xyz.mcxross.flare.hyperliquid.model.PerpMeta
import xyz.mcxross.flare.hyperliquid.model.Portfolio
import xyz.mcxross.flare.hyperliquid.model.Referral
import xyz.mcxross.flare.hyperliquid.model.SpotAssetContext
import xyz.mcxross.flare.hyperliquid.model.SpotClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.SpotMeta
import xyz.mcxross.flare.hyperliquid.model.SubAccount
import xyz.mcxross.flare.hyperliquid.model.TwapHistoryEntry
import xyz.mcxross.flare.hyperliquid.model.UserFees
import xyz.mcxross.flare.hyperliquid.model.UserRole
import xyz.mcxross.flare.hyperliquid.model.VaultDetails
import xyz.mcxross.flare.hyperliquid.model.VaultEquity

/** Every captured `/info` response decodes with the client's [HyperliquidClient.DefaultJson]. */
class JsonFixtureTest {
  private val json = HyperliquidClient.DefaultJson

  @Test
  fun everyInfoFixtureDecodes() {
    val fixtures: List<Pair<String, KSerializer<*>>> =
      listOf(
        Fixtures.META to PerpMeta.serializer(),
        Fixtures.SPOT_META to SpotMeta.serializer(),
        Fixtures.ALL_MIDS to MapSerializer(String.serializer(), String.serializer()),
        Fixtures.L2_BOOK to L2Book.serializer(),
        Fixtures.CANDLE_SNAPSHOT to ListSerializer(Candle.serializer()),
        Fixtures.RECENT_TRADES to ListSerializer(MarketTrade.serializer()),
        Fixtures.CLEARINGHOUSE_STATE to ClearinghouseState.serializer(),
        Fixtures.CLEARINGHOUSE_STATE_UNIFIED to ClearinghouseState.serializer(),
        Fixtures.CLEARINGHOUSE_STATE_EMPTY to ClearinghouseState.serializer(),
        Fixtures.SPOT_CLEARINGHOUSE_STATE to SpotClearinghouseState.serializer(),
        Fixtures.SPOT_CLEARINGHOUSE_STATE_UNIFIED to SpotClearinghouseState.serializer(),
        Fixtures.FRONTEND_OPEN_ORDERS to ListSerializer(FrontendOrder.serializer()),
        Fixtures.FRONTEND_OPEN_ORDERS_EMPTY to ListSerializer(FrontendOrder.serializer()),
        Fixtures.USER_FILLS_BY_TIME to ListSerializer(Fill.serializer()),
        Fixtures.USER_FUNDING to ListSerializer(FundingUpdate.serializer()),
        Fixtures.USER_NON_FUNDING_LEDGER_UPDATES to ListSerializer(LedgerUpdate.serializer()),
        Fixtures.HISTORICAL_ORDERS to ListSerializer(OrderWithStatus.serializer()),
        Fixtures.ORDER_STATUS to OrderStatusResult.serializer(),
        Fixtures.ORDER_STATUS_UNKNOWN to OrderStatusResult.serializer(),
        Fixtures.PORTFOLIO to Portfolio.serializer(),
        Fixtures.USER_FEES to UserFees.serializer(),
        Fixtures.USER_ROLE to UserRole.serializer(),
        Fixtures.USER_ROLE_MISSING to UserRole.serializer(),
        Fixtures.EXTRA_AGENTS to ListSerializer(ExtraAgent.serializer()),
        Fixtures.MAX_BUILDER_FEE to Int.serializer(),
        Fixtures.VAULT_DETAILS to VaultDetails.serializer(),
        Fixtures.VAULT_DETAILS_FOLLOWER to VaultDetails.serializer(),
        Fixtures.USER_VAULT_EQUITIES to ListSerializer(VaultEquity.serializer()),
        Fixtures.USER_VAULT_EQUITIES_EMPTY to ListSerializer(VaultEquity.serializer()),
        Fixtures.TWAP_HISTORY to ListSerializer(TwapHistoryEntry.serializer()),
        Fixtures.SUB_ACCOUNTS to ListSerializer(SubAccount.serializer()).nullable,
        Fixtures.REFERRAL to Referral.serializer(),
      )

    fixtures.forEachIndexed { index, (text, serializer) ->
      val result = runCatching { json.decodeFromString(serializer, text) }
      assertTrue(result.isSuccess, "fixture $index: ${result.exceptionOrNull()?.message}")
    }
  }

  @Test
  fun metaAndContextPairsDecode() {
    val perp = json.parseToJsonElement(Fixtures.META_AND_ASSET_CTXS).jsonArray
    val meta = json.decodeFromJsonElement(PerpMeta.serializer(), perp[0])
    val contexts =
      json.decodeFromJsonElement(ListSerializer(PerpAssetContext.serializer()), perp[1])
    assertEquals(meta.universe.size, contexts.size)

    val spot = json.parseToJsonElement(Fixtures.SPOT_META_AND_ASSET_CTXS).jsonArray
    json.decodeFromJsonElement(SpotMeta.serializer(), spot[0])
    json.decodeFromJsonElement(ListSerializer(SpotAssetContext.serializer()), spot[1])
  }

  @Test
  fun controlMessagesHaveTheShapeTheStreamExpects() {
    val error = json.parseToJsonElement(Fixtures.WS_ERROR) as JsonObject
    assertEquals(JsonPrimitive("error"), error["channel"])
    assertTrue((error["data"] as JsonPrimitive).isString)
    val pong = json.parseToJsonElement(Fixtures.WS_PONG) as JsonObject
    assertEquals(JsonPrimitive("pong"), pong["channel"])
    val ack = json.parseToJsonElement(Fixtures.WS_SUBSCRIPTION_RESPONSE) as JsonObject
    assertEquals(JsonPrimitive("subscriptionResponse"), ack["channel"])
    assertTrue(json.parseToJsonElement(Fixtures.CANDLE_SNAPSHOT) is JsonArray)
  }
}
