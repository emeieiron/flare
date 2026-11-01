package xyz.mcxross.flare.decibel.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.DecibelConfig
import xyz.mcxross.flare.decibel.model.CandleInterval

@OptIn(ExperimentalCoroutinesApi::class)
class SharedStreamTest {
  private class Socket(val token: String?) : StreamSocket {
    val incoming = Channel<String>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    var closed = false

    override suspend fun send(message: String) {
      sent += message
    }

    override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()

    fun subscriptions(method: String) = sent.filter { "\"method\":\"$method\"" in it }
  }

  private class Fixture(scope: CoroutineScope) {
    val sockets = mutableListOf<Socket>()
    var token = "owner"
    private val client = HttpClient(MockEngine { error("Unexpected HTTP request") })
    val stream =
      DefaultDecibelStreamService(
        client,
        DecibelConfig(accessToken = { token }),
        DecibelClient.DefaultJson,
        StreamTransport { token, block ->
          val socket = Socket(token).also { sockets += it }
          try {
            socket.block()
          } finally {
            socket.closed = true
          }
        },
        scope,
      )

    fun close() {
      stream.close()
      client.close()
    }
  }

  private fun TestScope.follow(
    f: Fixture,
    topics: Set<DecibelStreamTopic>,
    events: MutableList<StreamEvent>,
  ): Job = backgroundScope.launch { f.stream.subscribe(topics).collect { events += it } }

  @Test
  fun fourFeedsShareOneSocketAndRouteOnlyTheirTopics() = runTest {
    val f = Fixture(backgroundScope)
    try {
      val account = mutableListOf<StreamEvent>()
      val prices = mutableListOf<StreamEvent>()
      val details = mutableListOf<StreamEvent>()
      val candles = mutableListOf<StreamEvent>()
      follow(f, setOf(AccountPositions("0x1")), account)
      runCurrent()
      follow(f, setOf(AllMarketPrices, AllSpotMids), prices)
      follow(f, setOf(MarketDepth("0x2"), MarketTrades("0x2")), details)
      follow(f, setOf(MarketCandlestick("0x2", CandleInterval.ONE_MINUTE)), candles)
      runCurrent()
      assertEquals(1, f.sockets.size)
      val socket = f.sockets.single()
      assertEquals(6, socket.subscriptions("subscribe").size)
      assertEquals(1, account.filterIsInstance<StreamEvent.Connected>().size)
      socket.incoming.send("""{"topic":"all_market_prices","sequence":1,"prices":[]}""")
      socket.incoming.send(
        """{"topic":"${AccountPositions("0x1").wireValue}","sequence":90,"positions":[]}""",
      )
      socket.incoming.send("""{"topic":"all_market_prices","sequence":2,"prices":[]}""")
      runCurrent()
      assertEquals(2, prices.filterIsInstance<StreamEvent.Message>().size)
      assertEquals(1, account.filterIsInstance<StreamEvent.Message>().size)
      assertTrue(details.none { it is StreamEvent.Message })
      assertTrue(candles.none { it is StreamEvent.Message })
      assertTrue(prices.none { it is StreamEvent.SequenceGap })
    } finally {
      f.close()
    }
  }

  @Test
  fun topicsAreReferenceCountedUsingCanonicalWireNames() = runTest {
    val f = Fixture(backgroundScope)
    try {
      val topic = MarketTrades("0x1")
      val a = mutableListOf<StreamEvent>()
      val b = mutableListOf<StreamEvent>()
      val first = follow(f, setOf(topic), a)
      val second = follow(f, setOf(MarketTrades(topic.wireValue.substringAfter(':'))), b)
      runCurrent()
      val socket = f.sockets.single()
      assertEquals(1, socket.subscriptions("subscribe").size)
      socket.incoming.send("""{"topic":"${topic.wireValue}","trades":[]}""")
      runCurrent()
      assertEquals(1, a.filterIsInstance<StreamEvent.Message>().size)
      assertEquals(1, b.filterIsInstance<StreamEvent.Message>().size)
      first.cancel()
      runCurrent()
      assertEquals(0, socket.subscriptions("unsubscribe").size)
      second.cancel()
      runCurrent()
      assertEquals(1, socket.subscriptions("unsubscribe").size)
      advanceTimeBy(2_001)
      runCurrent()
      assertTrue(socket.closed)
    } finally {
      f.close()
    }
  }

  @Test
  fun backgroundClosesImmediatelyAndResumeReplaysTopics() = runTest {
    val f = Fixture(backgroundScope)
    try {
      follow(f, setOf(AllMarketPrices), mutableListOf())
      runCurrent()
      f.stream.setEnabled(false)
      runCurrent()
      assertTrue(f.sockets.single().closed)
      advanceTimeBy(10_000)
      runCurrent()
      assertEquals(1, f.sockets.size)
      f.stream.setEnabled(true)
      runCurrent()
      assertEquals(2, f.sockets.size)
      assertEquals(1, f.sockets.last().subscriptions("subscribe").size)
    } finally {
      f.close()
    }
  }

  @Test
  fun rapidPauseResumeCannotLeaveACancelledConnectionInUse() = runTest {
    val f = Fixture(backgroundScope)
    try {
      follow(f, setOf(AllMarketPrices), mutableListOf())
      runCurrent()
      f.stream.setEnabled(false)
      f.stream.setEnabled(true)
      runCurrent()
      assertTrue(f.sockets.first().closed)
      assertEquals(2, f.sockets.size)
      assertEquals(1, f.sockets.last().subscriptions("subscribe").size)
    } finally {
      f.close()
    }
  }

  @Test
  fun privateJoinUpgradesAnonymousAndAccountSwitchRemovesOldListener() = runTest {
    val f = Fixture(backgroundScope)
    try {
      f.token = "anonymous"
      follow(f, setOf(AllMarketPrices), mutableListOf())
      runCurrent()
      f.token = "owner-a"
      val oldEvents = mutableListOf<StreamEvent>()
      val old = follow(f, setOf(AccountPositions("0x1")), oldEvents)
      runCurrent()
      assertEquals("owner-a", f.sockets.last().token)
      assertTrue(f.sockets.first().closed)
      f.token = "owner-b"
      follow(f, setOf(AccountPositions("0x2")), mutableListOf())
      runCurrent()
      assertFalse(old.isActive)
      assertEquals("owner-b", f.sockets.last().token)
      assertTrue(
        f.sockets.last().subscriptions("subscribe").none {
          AccountPositions("0x1").wireValue in it
        },
      )
      assertEquals(2, f.sockets.last().subscriptions("subscribe").size)
    } finally {
      f.close()
    }
  }

  @Test
  fun reconnectRenewsTokenAndMalformedFramesDoNotKillOtherFeeds() = runTest {
    val f = Fixture(backgroundScope)
    try {
      val events = mutableListOf<StreamEvent>()
      follow(f, setOf(AllMarketPrices), events)
      runCurrent()
      val socket = f.sockets.single()
      socket.incoming.send("invalid JSON")
      socket.incoming.send("""{"topic":{},"success":{}}""")
      socket.incoming.send("""{"topic":"all_market_prices","sequence":1,"prices":[]}""")
      socket.incoming.send("""{"topic":"all_market_prices","sequence":3,"prices":[]}""")
      socket.incoming.send("""{"topic":"all_market_prices","sequence":2,"prices":[]}""")
      runCurrent()
      assertEquals(2, events.filterIsInstance<StreamEvent.Message>().size)
      assertEquals(1, events.filterIsInstance<StreamEvent.SequenceGap>().size)
      f.token = "renewed"
      socket.incoming.close()
      runCurrent()
      advanceTimeBy(751)
      runCurrent()
      assertEquals(2, f.sockets.size)
      assertEquals("renewed", f.sockets.last().token)
      assertEquals(1, f.sockets.last().subscriptions("subscribe").size)
    } finally {
      f.close()
    }
  }
}
