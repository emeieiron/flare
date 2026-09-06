package xyz.mcxross.flare.decibel.api

import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.mcxross.flare.decibel.model.AccountOverview
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.FundingPayment
import xyz.mcxross.flare.decibel.model.MarketTrade
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.Page
import xyz.mcxross.flare.decibel.model.Position
import xyz.mcxross.flare.decibel.model.Subaccount

interface AccountDataService {
  suspend fun overview(account: String): AccountOverview

  suspend fun positions(account: String, market: String? = null): List<Position>

  suspend fun openOrders(account: String, limit: Int = 200, offset: Int = 0): Page<Order>

  suspend fun orderHistory(account: String, limit: Int = 100, offset: Int = 0): Page<Order>

  suspend fun tradeHistory(account: String, limit: Int = 100, offset: Int = 0): Page<MarketTrade>

  suspend fun fundingHistory(
    account: String,
    limit: Int = 100,
    offset: Int = 0,
  ): Page<FundingPayment>

  suspend fun subaccounts(owner: String): List<Subaccount>

  suspend fun delegations(subaccount: String): List<Delegation>
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

  override suspend fun openOrders(account: String, limit: Int, offset: Int): Page<Order> =
    api.get("open_orders") {
      parameter("account", account)
      parameter("asset_type", "perp")
      parameter("limit", limit.coerceIn(1, 200))
      parameter("offset", offset.coerceIn(0, 10_000))
    }

  override suspend fun orderHistory(account: String, limit: Int, offset: Int): Page<Order> =
    api.get("order_history") {
      parameter("account", account)
      parameter("asset_type", "perp")
      parameter("limit", limit.coerceIn(1, 200))
      parameter("offset", offset.coerceIn(0, 10_000))
    }

  override suspend fun tradeHistory(account: String, limit: Int, offset: Int): Page<MarketTrade> =
    api.get("trade_history") {
      parameter("account", account)
      parameter("asset_type", "perp")
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
}
