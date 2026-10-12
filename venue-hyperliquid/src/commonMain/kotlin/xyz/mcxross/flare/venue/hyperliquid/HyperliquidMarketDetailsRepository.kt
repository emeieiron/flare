package xyz.mcxross.flare.venue.hyperliquid

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
import xyz.mcxross.flare.data.MarketDetails
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.api.StreamData
import xyz.mcxross.flare.hyperliquid.api.StreamEvent
import xyz.mcxross.flare.hyperliquid.api.Subscription

/** A market's order book and recent trades from `/info`, then from the `l2Book` and `trades` streams. */
class HyperliquidMarketDetailsRepository(
  private val client: HyperliquidClient,
  private val markets: HyperliquidMarketsRepository,
) : MarketDetailsRepository {
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
        val book = async { client.info.l2Book(market) }
        val trades = async { client.info.recentTrades(market) }
        book.await() to trades.await()
      }
    }
      .onSuccess { (book, trades) ->
        mutableDetails.value =
          MarketDetails(
            market = market,
            orderBook = book.toOrderBook(),
            recentTrades = trades.toRecentTrades(),
            stale = false,
          )
      }
      .onFailure { error ->
        updateFor(market) {
          it.copy(loading = false, stale = true, error = error.message ?: "Order book is unavailable")
        }
      }
    Unit
  }

  override suspend fun connectLive(market: String) {
    if (!markets.isListed(market)) {
      updateFor(market) { it.copy(stale = true, error = "Hyperliquid doesn’t list this market") }
      return
    }
    var lastRecoveryAtMs = 0L
    client.stream
      .subscribe(setOf(Subscription.L2Book(market), Subscription.Trades(market)))
      .collect { event ->
        when (event) {
          is StreamEvent.Connected -> refresh(market)
          is StreamEvent.Message ->
            when (val data = event.data) {
              is StreamData.Book ->
                if (data.book.coin == market) {
                  updateFor(market) {
                    it.copy(orderBook = data.book.toOrderBook(), stale = false, error = null)
                  }
                }
              is StreamData.Trades -> {
                val trades = data.trades.filter { it.coin == market }
                if (trades.isNotEmpty()) {
                  updateFor(market) {
                    it.copy(
                      recentTrades = trades.toRecentTrades(earlier = it.recentTrades),
                      stale = false,
                      error = null,
                    )
                  }
                }
              }
              is StreamData.Malformed -> {
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastRecoveryAtMs >= MALFORMED_RECOVERY_INTERVAL_MS) {
                  lastRecoveryAtMs = now
                  refresh(market)
                }
              }
              else -> Unit
            }
          is StreamEvent.Rejected -> updateFor(market) { it.copy(stale = true, error = event.reason) }
          is StreamEvent.Disconnected ->
            updateFor(market) {
              it.copy(stale = true, error = event.reason ?: "Market stream disconnected")
            }
        }
      }
  }

  private fun updateFor(market: String, transform: (MarketDetails) -> MarketDetails) {
    mutableDetails.update { if (it.market == market) transform(it) else it }
  }
}

private const val MALFORMED_RECOVERY_INTERVAL_MS = 10_000L
