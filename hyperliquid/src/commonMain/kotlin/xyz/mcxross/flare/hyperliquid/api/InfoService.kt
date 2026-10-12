package xyz.mcxross.flare.hyperliquid.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.mcxross.flare.hyperliquid.model.Candle
import xyz.mcxross.flare.hyperliquid.model.CandleInterval
import xyz.mcxross.flare.hyperliquid.model.ClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.Cloid
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
import xyz.mcxross.flare.hyperliquid.model.PerpMetaAndContexts
import xyz.mcxross.flare.hyperliquid.model.Portfolio
import xyz.mcxross.flare.hyperliquid.model.Referral
import xyz.mcxross.flare.hyperliquid.model.SpotAssetContext
import xyz.mcxross.flare.hyperliquid.model.SpotClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.SpotMeta
import xyz.mcxross.flare.hyperliquid.model.SpotMetaAndContexts
import xyz.mcxross.flare.hyperliquid.model.SubAccount
import xyz.mcxross.flare.hyperliquid.model.TwapHistoryEntry
import xyz.mcxross.flare.hyperliquid.model.UserFees
import xyz.mcxross.flare.hyperliquid.model.UserRole
import xyz.mcxross.flare.hyperliquid.model.VaultDetails
import xyz.mcxross.flare.hyperliquid.model.VaultEquity
import xyz.mcxross.flare.hyperliquid.model.normalizeEvmAddress

/**
 * Hyperliquid's `/info` reads. Every request is a POST of `{"type": …}`; every address is
 * validated and lower-cased first. Times are Unix milliseconds.
 */
interface InfoService {
  suspend fun perpMeta(): PerpMeta

  suspend fun spotMeta(): SpotMeta

  suspend fun perpMetaAndContexts(): PerpMetaAndContexts

  suspend fun spotMetaAndContexts(): SpotMetaAndContexts

  /** Mid prices by coin. Includes coins neither meta lists (outcome markets, `#…`). */
  suspend fun allMids(): Map<String, String>

  /**
   * The order book, 20 levels a side. [significantFigures] (2–5) aggregates levels; [mantissa]
   * (1, 2 or 5) refines it and is only allowed with five figures.
   */
  suspend fun l2Book(coin: String, significantFigures: Int? = null, mantissa: Int? = null): L2Book

  /** At most 5000 candles back from now. */
  suspend fun candles(
    coin: String,
    interval: CandleInterval,
    startTimeMs: Long,
    endTimeMs: Long,
  ): List<Candle>

  suspend fun recentTrades(coin: String): List<MarketTrade>

  suspend fun clearinghouseState(user: String): ClearinghouseState

  suspend fun spotClearinghouseState(user: String): SpotClearinghouseState

  /** `frontendOpenOrders`: open orders with their trigger and TP/SL details. */
  suspend fun openOrders(user: String): List<FrontendOrder>

  /** Up to 2000 fills from [startTimeMs]; page by moving the start past the last fill. */
  suspend fun fills(
    user: String,
    startTimeMs: Long,
    endTimeMs: Long? = null,
    aggregateByTime: Boolean = false,
  ): List<Fill>

  suspend fun funding(user: String, startTimeMs: Long, endTimeMs: Long? = null): List<FundingUpdate>

  suspend fun ledgerUpdates(
    user: String,
    startTimeMs: Long,
    endTimeMs: Long? = null,
  ): List<LedgerUpdate>

  /** The latest 2000 orders and their final status. */
  suspend fun historicalOrders(user: String): List<OrderWithStatus>

  suspend fun orderStatus(user: String, oid: Long): OrderStatusResult

  suspend fun orderStatus(user: String, cloid: Cloid): OrderStatusResult

  suspend fun portfolio(user: String): Portfolio

  suspend fun userFees(user: String): UserFees

  suspend fun userRole(user: String): UserRole

  /** Named agents the user approved, with their expiry. */
  suspend fun extraAgents(user: String): List<ExtraAgent>

  /** The builder fee [user] approved for [builder], in tenths of a basis point. */
  suspend fun maxBuilderFee(user: String, builder: String): Int

  suspend fun vaultDetails(vaultAddress: String, user: String? = null): VaultDetails

  suspend fun userVaultEquities(user: String): List<VaultEquity>

  suspend fun twapHistory(user: String): List<TwapHistoryEntry>

  suspend fun subAccounts(user: String): List<SubAccount>

  suspend fun referral(user: String): Referral
}

internal class DefaultInfoService(private val api: HyperliquidApi) : InfoService {
  override suspend fun perpMeta(): PerpMeta = read("meta", "perp metadata")

  override suspend fun spotMeta(): SpotMeta = read("spotMeta", "spot metadata")

  override suspend fun perpMetaAndContexts(): PerpMetaAndContexts {
    val pair = pairOf(api.info(request("metaAndAssetCtxs")), "perp contexts")
    return PerpMetaAndContexts(
      meta = api.decode<PerpMeta>(pair.first, "perp metadata"),
      contexts = api.decode<List<PerpAssetContext>>(pair.second, "perp contexts"),
    )
  }

  override suspend fun spotMetaAndContexts(): SpotMetaAndContexts {
    val pair = pairOf(api.info(request("spotMetaAndAssetCtxs")), "spot contexts")
    return SpotMetaAndContexts(
      meta = api.decode<SpotMeta>(pair.first, "spot metadata"),
      contexts = api.decode<List<SpotAssetContext>>(pair.second, "spot contexts"),
    )
  }

  override suspend fun allMids(): Map<String, String> = read("allMids", "mid prices")

  override suspend fun l2Book(coin: String, significantFigures: Int?, mantissa: Int?): L2Book {
    require(significantFigures == null || significantFigures in 2..5) {
      "Book aggregation uses 2 to 5 significant figures"
    }
    require(mantissa == null || (significantFigures == 5 && mantissa in setOf(1, 2, 5))) {
      "A book mantissa of 1, 2 or 5 needs five significant figures"
    }
    return read("l2Book", "order book") {
      put("coin", coin)
      significantFigures?.let { put("nSigFigs", it) }
      mantissa?.let { put("mantissa", it) }
    }
  }

  override suspend fun candles(
    coin: String,
    interval: CandleInterval,
    startTimeMs: Long,
    endTimeMs: Long,
  ): List<Candle> {
    require(startTimeMs < endTimeMs) { "Candle start time must be before end time" }
    return read("candleSnapshot", "candles") {
      putJsonObject("req") {
        put("coin", coin)
        put("interval", interval.wireValue)
        put("startTime", startTimeMs)
        put("endTime", endTimeMs)
      }
    }
  }

  override suspend fun recentTrades(coin: String): List<MarketTrade> =
    read("recentTrades", "trades") { put("coin", coin) }

  override suspend fun clearinghouseState(user: String): ClearinghouseState =
    read("clearinghouseState", "account state") { putUser(user) }

  override suspend fun spotClearinghouseState(user: String): SpotClearinghouseState =
    read("spotClearinghouseState", "spot balances") { putUser(user) }

  override suspend fun openOrders(user: String): List<FrontendOrder> =
    read("frontendOpenOrders", "open orders") { putUser(user) }

  override suspend fun fills(
    user: String,
    startTimeMs: Long,
    endTimeMs: Long?,
    aggregateByTime: Boolean,
  ): List<Fill> =
    read("userFillsByTime", "fills") {
      putUser(user)
      putRange(startTimeMs, endTimeMs)
      if (aggregateByTime) put("aggregateByTime", true)
    }

  override suspend fun funding(
    user: String,
    startTimeMs: Long,
    endTimeMs: Long?,
  ): List<FundingUpdate> =
    read("userFunding", "funding history") {
      putUser(user)
      putRange(startTimeMs, endTimeMs)
    }

  override suspend fun ledgerUpdates(
    user: String,
    startTimeMs: Long,
    endTimeMs: Long?,
  ): List<LedgerUpdate> =
    read("userNonFundingLedgerUpdates", "ledger updates") {
      putUser(user)
      putRange(startTimeMs, endTimeMs)
    }

  override suspend fun historicalOrders(user: String): List<OrderWithStatus> =
    read("historicalOrders", "order history") { putUser(user) }

  override suspend fun orderStatus(user: String, oid: Long): OrderStatusResult =
    read("orderStatus", "order status") {
      putUser(user)
      put("oid", oid)
    }

  override suspend fun orderStatus(user: String, cloid: Cloid): OrderStatusResult =
    read("orderStatus", "order status") {
      putUser(user)
      put("oid", cloid.hex)
    }

  override suspend fun portfolio(user: String): Portfolio =
    read("portfolio", "portfolio") { putUser(user) }

  override suspend fun userFees(user: String): UserFees =
    read("userFees", "fee rates") { putUser(user) }

  override suspend fun userRole(user: String): UserRole =
    read("userRole", "account role") { putUser(user) }

  override suspend fun extraAgents(user: String): List<ExtraAgent> =
    read("extraAgents", "agents") { putUser(user) }

  override suspend fun maxBuilderFee(user: String, builder: String): Int =
    read("maxBuilderFee", "builder fee") {
      putUser(user)
      put("builder", normalizeEvmAddress(builder, "builder"))
    }

  override suspend fun vaultDetails(vaultAddress: String, user: String?): VaultDetails =
    read("vaultDetails", "vault details") {
      put("vaultAddress", normalizeEvmAddress(vaultAddress, "vaultAddress"))
      user?.let { putUser(it) }
    }

  override suspend fun userVaultEquities(user: String): List<VaultEquity> =
    read("userVaultEquities", "vault equities") { putUser(user) }

  override suspend fun twapHistory(user: String): List<TwapHistoryEntry> =
    read("twapHistory", "TWAP history") { putUser(user) }

  override suspend fun subAccounts(user: String): List<SubAccount> {
    // An account without sub-accounts answers `null`.
    val element = api.info(request("subAccounts") { putUser(user) })
    return if (element is JsonNull) emptyList() else api.decode(element, "sub-accounts")
  }

  override suspend fun referral(user: String): Referral =
    read("referral", "referral state") { putUser(user) }

  private suspend inline fun <reified T> read(
    type: String,
    what: String,
    noinline fields: JsonObjectBuilder.() -> Unit = {},
  ): T = api.decode(api.info(request(type, fields)), what)

  private fun request(type: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
      put("type", type)
      fields()
    }

  private fun JsonObjectBuilder.putUser(user: String) {
    put("user", normalizeEvmAddress(user, "user"))
  }

  private fun JsonObjectBuilder.putRange(startTimeMs: Long, endTimeMs: Long?) {
    require(endTimeMs == null || startTimeMs <= endTimeMs) { "Start time must not be after end" }
    put("startTime", startTimeMs)
    endTimeMs?.let { put("endTime", it) }
  }

  private fun pairOf(element: JsonElement, what: String): Pair<JsonElement, JsonElement> {
    val array = element as? JsonArray
    if (array == null || array.size != 2) {
      throw HyperliquidApiError(200, "Hyperliquid returned an incompatible $what", false)
    }
    return array[0] to array[1]
  }
}
