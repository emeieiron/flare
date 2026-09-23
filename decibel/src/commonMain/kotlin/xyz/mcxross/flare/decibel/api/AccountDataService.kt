package xyz.mcxross.flare.decibel.api

import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.mcxross.flare.decibel.model.AccountOverview
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

interface AccountDataService {
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

  suspend fun streak(account: String): TradingStreak

  suspend fun amps(owner: String): AmpsBreakdown

  suspend fun tier(account: String): TierInfo

  suspend fun verifyReferralCode(code: String): ReferralCodeInfo

  suspend fun redeemReferralCode(account: String, code: String): ReferralRedemptionResponse
}

internal class DefaultAccountDataService(private val api: DecibelApi) : AccountDataService {
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
    runCatching {
      api.get<List<PortfolioChartPoint>>("portfolio_chart") {
        parameter("account", account)
        parameter("range", timeRange)
        parameter("metric", metric)
      }
    }.getOrElse {
      emptyList()
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

  override suspend fun streak(account: String): TradingStreak =
    runCatching {
      api.get<TradingStreak>("streaks/account") { parameter("account", account) }
    }.getOrDefault(TradingStreak())

  override suspend fun amps(owner: String): AmpsBreakdown =
    runCatching {
      api.get<AmpsBreakdown>("amps/$owner")
    }.getOrDefault(AmpsBreakdown())

  override suspend fun tier(account: String): TierInfo =
    runCatching {
      api.get<TierInfo>("points/tier") { parameter("account", account) }
    }.getOrDefault(TierInfo())

  override suspend fun verifyReferralCode(code: String): ReferralCodeInfo =
    api.get("referrals/code/$code")

  override suspend fun redeemReferralCode(
    account: String,
    code: String,
  ): ReferralRedemptionResponse =
    api.post("referrals/redeem") {
      setBody(ReferralRedemptionRequest(account, code))
    }
}
