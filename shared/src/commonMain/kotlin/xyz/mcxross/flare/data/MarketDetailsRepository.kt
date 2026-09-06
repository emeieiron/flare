package xyz.mcxross.flare.data

import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.DecibelStreamData
import xyz.mcxross.flare.decibel.api.DepthAggregation
import xyz.mcxross.flare.decibel.api.MarketDepth
import xyz.mcxross.flare.decibel.api.MarketTrades
import xyz.mcxross.flare.decibel.api.StreamEvent
import xyz.mcxross.flare.decibel.model.MarketTrade
import xyz.mcxross.flare.decibel.model.OrderBook

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

class DefaultMarketDetailsRepository(private val client: DecibelClient) : MarketDetailsRepository {
  private val mutableDetails = MutableStateFlow(MarketDetails())
  private val refreshMutex = Mutex()
  override val details: StateFlow<MarketDetails> = mutableDetails.asStateFlow()

  override suspend fun refresh(market: String) = refreshMutex.withLock {
    mutableDetails.update { current ->
      if (current.market == market) current.copy(loading = true, error = null)
      else MarketDetails(market = market, loading = true)
    }
    runSuspendCatching {
      coroutineScope {
        val book = async { client.markets.orderBook(market) }
        val trades = async { client.markets.trades(market, limit = 40) }
        book.await() to trades.await()
      }
    }
      .onSuccess { (book, trades) ->
        mutableDetails.value =
          MarketDetails(
            market = market,
            orderBook = book,
            recentTrades = trades,
            stale = false,
          )
      }
      .onFailure { error ->
        mutableDetails.update { current ->
          if (current.market == market) {
            current.copy(
              loading = false,
              stale = true,
              error = error.message ?: "Order book is unavailable",
            )
          } else {
            current
          }
        }
      }
    Unit
  }

  override suspend fun connectLive(market: String) {
    var lastRecoveryAtMs = 0L
    client.stream
      .subscribe(setOf(MarketDepth(market, DepthAggregation.ONE), MarketTrades(market)))
      .collect { event ->
        when (event) {
          is StreamEvent.Connected -> {
            refresh(market)
            lastRecoveryAtMs = Clock.System.now().toEpochMilliseconds()
          }
          is StreamEvent.Message -> {
            val now = Clock.System.now().toEpochMilliseconds()
            when (val data = event.data) {
              is DecibelStreamData.MarketDepthValue ->
                mutableDetails.update { current ->
                  if (current.market == market) {
                    current.copy(
                      orderBook = data.value.toOrderBook(),
                      stale = false,
                      error = null,
                    )
                  } else {
                    current
                  }
                }
              is DecibelStreamData.MarketTrades ->
                mutableDetails.update { current ->
                  if (current.market == market) {
                    current.copy(
                      recentTrades = data.values,
                      stale = false,
                      error = null,
                    )
                  } else {
                    current
                  }
                }
              else -> Unit
            }
            if (
              event.data is DecibelStreamData.Malformed &&
                now - lastRecoveryAtMs >= MALFORMED_RECOVERY_INTERVAL_MS
            ) {
              refresh(market)
              lastRecoveryAtMs = now
            }
          }
          is StreamEvent.SequenceGap -> {
            refresh(market)
            lastRecoveryAtMs = Clock.System.now().toEpochMilliseconds()
          }
          is StreamEvent.Rejected ->
            mutableDetails.update { current ->
              if (current.market == market) {
                current.copy(stale = true, error = event.reason)
              } else {
                current
              }
            }
          is StreamEvent.Disconnected ->
            mutableDetails.update { current ->
              if (current.market == market) {
                current.copy(stale = true, error = event.reason ?: "Market stream disconnected")
              } else {
                current
              }
            }
        }
      }
  }

  private companion object {
    const val MALFORMED_RECOVERY_INTERVAL_MS = 10_000L
  }
}
