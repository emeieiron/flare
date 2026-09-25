package xyz.mcxross.flare.decibel.api

import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.mcxross.flare.decibel.model.AccountOverview
import xyz.mcxross.flare.decibel.model.AccountVaultPerformance
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.FundMovement
import xyz.mcxross.flare.decibel.model.FundingPayment
import xyz.mcxross.flare.decibel.model.MarketTrade
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.Page
import xyz.mcxross.flare.decibel.model.PortfolioChartPoint
import xyz.mcxross.flare.decibel.model.Position
import xyz.mcxross.flare.decibel.model.ReferralCodeInfo
import xyz.mcxross.flare.decibel.model.ReferralRedemptionRequest
import xyz.mcxross.flare.decibel.model.ReferralRedemptionResponse
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.TradingStreak
import xyz.mcxross.flare.decibel.model.TwapOrder
import xyz.mcxross.flare.decibel.model.VaultInfo

interface AccountDataService {
  suspend fun fees(account: String): xyz.mcxross.flare.decibel.model.AccountFees? = null

  suspend fun overview(account: String): AccountOverview

  suspend fun positions(account: String, market: String? = null): List<Position>

  suspend fun openOrders(
    account: String,
    limit: Int = 200,
    offset: Int = 0,
    assetType: AssetType? = null,
  ): Page<Order>

  suspend fun orderHistory(
    account: String,
    limit: Int = 100,
    offset: Int = 0,
    assetType: AssetType? = null,
  ): Page<Order>

  suspend fun tradeHistory(
    account: String,
    limit: Int = 100,
    offset: Int = 0,
    assetType: AssetType? = null,
  ): Page<MarketTrade>

  suspend fun fundingHistory(
    account: String,
    limit: Int = 100,
    offset: Int = 0,
  ): Page<FundingPayment>

  suspend fun subaccounts(owner: String): List<Subaccount>

  suspend fun delegations(subaccount: String): List<Delegation>

  suspend fun portfolioChart(
    account: String,
    timeRange: String = "1D",
    metric: String = "account_value",
  ): List<PortfolioChartPoint>

  suspend fun fundHistory(
    account: String,
    limit: Int = 100,
    offset: Int = 0,
  ): Page<FundMovement>

  suspend fun streak(account: String): TradingStreak?

  suspend fun amps(owner: String): AmpsBreakdown

  suspend fun tier(account: String): TierInfo?

  suspend fun verifyReferralCode(code: String): ReferralCodeInfo

  suspend fun redeemReferralCode(account: String, code: String): ReferralRedemptionResponse

  suspend fun activeTwaps(account: String): List<TwapOrder>

  suspend fun twapHistory(account: String, limit: Int = 20): List<TwapOrder>

  suspend fun vaults(limit: Int = 50): List<VaultInfo>

  suspend fun accountVaultPerformance(account: String): List<AccountVaultPerformance>
}

internal class DefaultAccountDataService(private val api: DecibelApi) : AccountDataService {
  override suspend fun fees(account: String): xyz.mcxross.flare.decibel.model.AccountFees =
    api.get("user_fee_rates") { parameter("account", account) }

  override suspend fun overview(account: String): AccountOverview =
    api.get("account_overviews") { parameter("account", account) }

  override suspend fun positions(account: String, market: String?): List<Position> =
    api.get("account_positions") {
      parameter("account", account)
      parameter("include_deleted", false)
      market?.let { parameter("market_address", it) }
    }

  override suspend fun openOrders(
    account: String,
    limit: Int,
    offset: Int,
    assetType: AssetType?,
  ): Page<Order> =
    api.get("open_orders") {
      parameter("account", account)
      assetType?.let { parameter("asset_type", it.name.lowercase()) }
      parameter("limit", limit.coerceIn(1, 200))
      parameter("offset", offset.coerceIn(0, 10_000))
    }

  override suspend fun orderHistory(
    account: String,
    limit: Int,
    offset: Int,
    assetType: AssetType?,
  ): Page<Order> =
    api.get("order_history") {
      parameter("account", account)
      assetType?.let { parameter("asset_type", it.name.lowercase()) }
      parameter("limit", limit.coerceIn(1, 200))
      parameter("offset", offset.coerceIn(0, 10_000))
    }

  override suspend fun tradeHistory(
    account: String,
    limit: Int,
    offset: Int,
    assetType: AssetType?,
  ): Page<MarketTrade> =
    api.get("trade_history") {
      parameter("account", account)
      assetType?.let { parameter("asset_type", it.name.lowercase()) }
      parameter("limit", limit.coerceIn(1, 200))
      parameter("offset", offset.coerceIn(0, 10_000))
    }

  override suspend fun fundingHistory(
    account: String,
    limit: Int,
    offset: Int,
  ): Page<FundingPayment> =
    api.get("funding_rate_history") {
      parameter("account", account)
      parameter("limit", limit.coerceIn(1, 200))
      parameter("offset", offset.coerceIn(0, 10_000))
    }

  override suspend fun subaccounts(owner: String): List<Subaccount> {
    val element =
      api.get<kotlinx.serialization.json.JsonElement>("subaccounts") {
        parameter("owner", owner)
      }
    return when (element) {
      is JsonArray -> api.json.decodeFromJsonElement(element)
      is JsonObject -> {
        val items = element["subaccounts"] ?: element["items"] ?: JsonArray(emptyList())
        api.json.decodeFromJsonElement(items)
      }
      else -> emptyList()
    }
  }

  override suspend fun delegations(subaccount: String): List<Delegation> =
    api.get("delegations") { parameter("subaccount", subaccount) }

  override suspend fun portfolioChart(
    account: String,
    timeRange: String,
    metric: String,
  ): List<PortfolioChartPoint> =
    run {
      val rangeParam = when (timeRange.uppercase()) {
        "1D", "DAY", "DAY_1", "24H" -> "24h"
        "1W", "WEEK", "WEEK_1", "7D" -> "7d"
        "1M", "MONTH", "MONTH_1", "30D" -> "30d"
        "90D", "3M" -> "90d"
        else -> "all"
      }
      val dataType = if (metric.contains("pnl", ignoreCase = true)) "pnl" else "account_value"
      api.get<List<PortfolioChartPoint>>("portfolio_chart") {
        parameter("account", account)
        parameter("range", rangeParam)
        parameter("data_type", dataType)
      }
    }

  override suspend fun fundHistory(
    account: String,
    limit: Int,
    offset: Int,
  ): Page<FundMovement> =
    runCatching {
      api.get<Page<FundMovement>>("account_fund_history") {
        parameter("account", account)
        parameter("limit", limit.coerceIn(1, 200))
        parameter("offset", offset.coerceIn(0, 10_000))
      }
    }.getOrElse {
      runCatching {
        val items =
          api.get<List<FundMovement>>("account_fund_history") {
            parameter("account", account)
            parameter("limit", limit.coerceIn(1, 200))
            parameter("offset", offset.coerceIn(0, 10_000))
          }
        Page(items = items, totalCount = items.size.toLong())
      }.getOrDefault(Page())
    }

  override suspend fun streak(account: String): TradingStreak? =
    try {
      api.get<TradingStreak>("streaks/account") { parameter("account", account) }
    } catch (error: DecibelApiError) {
      if (error.statusCode == 404) null else throw error
    }

  override suspend fun amps(owner: String): AmpsBreakdown =
    api.get("points/amps") { parameter("owner", owner) }

  override suspend fun tier(account: String): TierInfo? =
    try {
      api.get<TierInfo>("points/tier") { parameter("account", account) }
    } catch (error: DecibelApiError) {
      if (error.statusCode == 404) null else throw error
    }

  override suspend fun verifyReferralCode(code: String): ReferralCodeInfo =
    api.get("referrals/code/$code")

  override suspend fun redeemReferralCode(
    account: String,
    code: String,
  ): ReferralRedemptionResponse =
    api.post("referrals/redeem") {
      setBody(ReferralRedemptionRequest(account, code))
    }

  override suspend fun activeTwaps(account: String): List<TwapOrder> =
    runCatching<List<TwapOrder>> {
      val element = api.get<JsonElement>("active_twaps") { parameter("account", account) }
      when (element) {
        is JsonArray -> api.json.decodeFromJsonElement<List<TwapOrder>>(element)
        is JsonObject -> {
          val items = element["items"] ?: element["twaps"] ?: JsonArray(emptyList())
          api.json.decodeFromJsonElement<List<TwapOrder>>(items)
        }
        else -> emptyList()
      }
    }.getOrElse { emptyList() }

  override suspend fun twapHistory(account: String, limit: Int): List<TwapOrder> =
    runCatching<List<TwapOrder>> {
      val element = api.get<JsonElement>("twap_history") {
        parameter("account", account)
        parameter("limit", limit.coerceIn(1, 200))
      }
      when (element) {
        is JsonArray -> api.json.decodeFromJsonElement<List<TwapOrder>>(element)
        is JsonObject -> {
          val items = element["items"] ?: element["twaps"] ?: JsonArray(emptyList())
          api.json.decodeFromJsonElement<List<TwapOrder>>(items)
        }
        else -> emptyList()
      }
    }.getOrElse { emptyList() }

  override suspend fun vaults(limit: Int): List<VaultInfo> =
    run {
      val element = api.get<JsonElement>("vaults") {
        parameter("limit", limit.coerceIn(1, 100))
      }
      when (element) {
        is JsonArray -> api.json.decodeFromJsonElement<List<VaultInfo>>(element)
        is JsonObject -> {
          val items = element["items"] ?: element["vaults"] ?: JsonArray(emptyList())
          api.json.decodeFromJsonElement<List<VaultInfo>>(items)
        }
        else -> emptyList()
      }
    }

  override suspend fun accountVaultPerformance(account: String): List<AccountVaultPerformance> =
    run {
      val element = api.get<JsonElement>("account_vault_performance") {
        parameter("account", account)
      }
      when (element) {
        is JsonArray -> api.json.decodeFromJsonElement<List<AccountVaultPerformance>>(element)
        is JsonObject -> {
          val items = element["items"] ?: element["performances"] ?: JsonArray(emptyList())
          api.json.decodeFromJsonElement<List<AccountVaultPerformance>>(items)
        }
        else -> emptyList()
      }
    }
}
