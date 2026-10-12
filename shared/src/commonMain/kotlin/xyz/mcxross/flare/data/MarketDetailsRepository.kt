package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.StateFlow
import xyz.mcxross.flare.venue.model.MarketTrade
import xyz.mcxross.flare.venue.model.OrderBook

data class MarketDetails(
  val market: String? = null,
  val orderBook: OrderBook? = null,
  val recentTrades: List<MarketTrade> = emptyList(),
  val loading: Boolean = false,
  val stale: Boolean = true,
  val error: String? = null,
)

interface MarketDetailsRepository {
  val details: StateFlow<MarketDetails>

  suspend fun refresh(market: String)

  suspend fun connectLive(market: String)
}
