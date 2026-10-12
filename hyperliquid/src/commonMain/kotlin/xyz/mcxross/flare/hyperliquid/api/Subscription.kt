package xyz.mcxross.flare.hyperliquid.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.mcxross.flare.hyperliquid.model.CandleInterval
import xyz.mcxross.flare.hyperliquid.model.normalizeEvmAddress

/**
 * A WebSocket subscription. Subscribing to a coin Hyperliquid does not list makes it close the
 * connection, so coins should come from `meta`/`spotMeta`.
 */
sealed interface Subscription {
  /** The subscription object sent in `{"method":"subscribe","subscription":…}`. */
  val wire: JsonObject

  /** The account this subscription follows, for user-specific ones. */
  val user: String?
    get() = null

  data object AllMids : Subscription {
    override val wire: JsonObject = subscriptionOf("allMids")
  }

  data class L2Book(
    val coin: String,
    val significantFigures: Int? = null,
    val mantissa: Int? = null,
  ) : Subscription {
    override val wire: JsonObject =
      subscriptionOf("l2Book") {
        put("coin", coin)
        significantFigures?.let { put("nSigFigs", it) }
        mantissa?.let { put("mantissa", it) }
      }
  }

  data class Trades(val coin: String) : Subscription {
    override val wire: JsonObject = subscriptionOf("trades") { put("coin", coin) }
  }

  data class Candles(val coin: String, val interval: CandleInterval) : Subscription {
    override val wire: JsonObject =
      subscriptionOf("candle") {
        put("coin", coin)
        put("interval", interval.wireValue)
      }
  }

  /** Arrives on `activeAssetCtx` for perps and `activeSpotAssetCtx` for spot. */
  data class ActiveAssetContext(val coin: String) : Subscription {
    override val wire: JsonObject = subscriptionOf("activeAssetCtx") { put("coin", coin) }
  }

  /** Leverage, maximum trade sizes and buying power for one coin. */
  data class ActiveAssetData(override val user: String, val coin: String) : Subscription {
    override val wire: JsonObject =
      subscriptionOf("activeAssetData") {
        put("user", normalizeEvmAddress(user, "user"))
        put("coin", coin)
      }
  }

  data class ClearinghouseState(override val user: String) : Subscription {
    override val wire: JsonObject = userWire("clearinghouseState", user)
  }

  data class OpenOrders(override val user: String) : Subscription {
    override val wire: JsonObject = userWire("openOrders", user)
  }

  data class SpotState(override val user: String) : Subscription {
    override val wire: JsonObject = userWire("spotState", user)
  }

  data class TwapStates(override val user: String) : Subscription {
    override val wire: JsonObject = userWire("twapStates", user)
  }

  data class OrderUpdates(override val user: String) : Subscription {
    override val wire: JsonObject = userWire("orderUpdates", user)
  }

  /** Starts with a snapshot of recent fills. */
  data class UserFills(override val user: String, val aggregateByTime: Boolean = false) :
    Subscription {
    override val wire: JsonObject =
      subscriptionOf("userFills") {
        put("user", normalizeEvmAddress(user, "user"))
        if (aggregateByTime) put("aggregateByTime", true)
      }
  }

  /** Fills, funding, liquidations and cancels Hyperliquid made; arrives on channel `user`. */
  data class UserEvents(override val user: String) : Subscription {
    override val wire: JsonObject = userWire("userEvents", user)
  }

  /** Starts with a snapshot. */
  data class UserFundings(override val user: String) : Subscription {
    override val wire: JsonObject = userWire("userFundings", user)
  }

  /** Deposits, withdrawals and transfers; starts with a snapshot. */
  data class LedgerUpdates(override val user: String) : Subscription {
    override val wire: JsonObject = userWire("userNonFundingLedgerUpdates", user)
  }
}

private fun subscriptionOf(type: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject =
  buildJsonObject {
    put("type", type)
    fields()
  }

private fun userWire(type: String, user: String): JsonObject =
  subscriptionOf(type) { put("user", normalizeEvmAddress(user, "user")) }
