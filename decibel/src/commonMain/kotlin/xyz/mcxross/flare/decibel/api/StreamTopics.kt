package xyz.mcxross.flare.decibel.api

import xyz.mcxross.flare.decibel.model.CandleInterval
import xyz.mcxross.kaptos.model.AccountAddress

sealed interface DecibelStreamTopic {
  val wireValue: String
}

sealed interface PublicStreamTopic : DecibelStreamTopic

sealed interface AccountStreamTopic : DecibelStreamTopic

data object AllMarketPrices : PublicStreamTopic {
  override val wireValue: String = "all_market_prices"
}

data object AllSpotMids : PublicStreamTopic {
  override val wireValue: String = "all_spot_mids"
}

enum class DepthAggregation(val wireValue: String) {
  ONE("1"),
  TWO("2"),
  FIVE("5"),
  TEN("10"),
  ONE_HUNDRED("100"),
  ONE_THOUSAND("1000"),
}

data class MarketDepth(
  val marketAddress: String,
  val aggregation: DepthAggregation? = null,
) : PublicStreamTopic {
  override val wireValue: String = buildString {
    append("depth:")
    append(canonicalTopicAddress(marketAddress))
    aggregation?.let {
      append(':')
      append(it.wireValue)
    }
  }
}

data class MarketTrades(val marketAddress: String) : PublicStreamTopic {
  override val wireValue: String = "trades:${canonicalTopicAddress(marketAddress)}"
}

data class MarketPriceTopic(val marketAddress: String) : PublicStreamTopic {
  override val wireValue: String = "market_price:${canonicalTopicAddress(marketAddress)}"
}

data class MarketCandlestick(
  val marketAddress: String,
  val interval: CandleInterval,
) : PublicStreamTopic {
  override val wireValue: String =
    "market_candlestick:${canonicalTopicAddress(marketAddress)}:${interval.wireValue}"
}

data class AccountOpenOrders(val accountAddress: String) : AccountStreamTopic {
  override val wireValue: String = "account_open_orders:${canonicalTopicAddress(accountAddress)}"
}

data class AccountOverviewTopic(val accountAddress: String) : AccountStreamTopic {
  override val wireValue: String = "account_overview:${canonicalTopicAddress(accountAddress)}"
}

data class AccountPositions(val accountAddress: String) : AccountStreamTopic {
  override val wireValue: String = "account_positions:${canonicalTopicAddress(accountAddress)}"
}

data class OrderUpdates(val accountAddress: String) : AccountStreamTopic {
  override val wireValue: String = "order_updates:${canonicalTopicAddress(accountAddress)}"
}

data class UserTrades(val accountAddress: String) : AccountStreamTopic {
  override val wireValue: String = "user_trades:${canonicalTopicAddress(accountAddress)}"
}

data class AccountNotifications(val accountAddress: String) : AccountStreamTopic {
  override val wireValue: String = "notifications:${canonicalTopicAddress(accountAddress)}"
}

data class WithdrawQueueUpdates(val accountAddress: String) : AccountStreamTopic {
  override val wireValue: String = "withdraw_queue:${canonicalTopicAddress(accountAddress)}"
}

private fun canonicalTopicAddress(value: String): String =
  try {
    AccountAddress.fromString(value).toString()
  } catch (error: Throwable) {
    throw IllegalArgumentException("Invalid Aptos topic address", error)
  }
