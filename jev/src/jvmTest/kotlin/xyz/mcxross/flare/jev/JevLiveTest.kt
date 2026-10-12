package xyz.mcxross.flare.jev

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import xyz.mcxross.flare.strategies.DecisionAnswer
import xyz.mcxross.flare.strategies.JevConfluence
import xyz.mcxross.flare.strategies.StrategyBook
import xyz.mcxross.flare.strategies.StrategyBookLevel
import xyz.mcxross.flare.strategies.StrategyCandle
import xyz.mcxross.flare.strategies.StrategyDirection
import xyz.mcxross.flare.strategies.StrategyEngine
import xyz.mcxross.flare.strategies.StrategyMarketKey
import xyz.mcxross.flare.strategies.StrategyMarketSnapshot

class JevLiveTest {
  @Test
  fun syntheticMarketProducesTypedConfluenceWithoutChangingBaseline() {
    val key = System.getenv("FLARE_JEV_LIVE_API_KEY")
    assumeTrue(!key.isNullOrBlank(), "Live Jev verification is opt-in")
    val apiKey = requireNotNull(key)
    runBlocking {
      val now = System.currentTimeMillis()
      val timeframeMs = 60_000L
      val closes = List(40) { 100.0 - it * 0.4 } + 110.0
      val candles =
        closes.mapIndexed { index, close ->
          val openTime = now - (closes.size - index) * timeframeMs
          StrategyCandle(openTime, openTime + timeframeMs, close, close + 1, close - 1, close, 10.0)
        }
      val snapshot =
        StrategyMarketSnapshot(
          StrategyMarketKey("synthetic", "simulation", "SYNTHETIC-BTC", timeframeMs),
          now,
          candles,
          StrategyBook(
            listOf(StrategyBookLevel("109.9", "2")),
            listOf(StrategyBookLevel("110.1", "1")),
            now,
          ),
        )
      val engine = StrategyEngine()
      val baseline = engine.evaluate(snapshot, now)
      assertNull(baseline.rejection)
      assertEquals(StrategyDirection.BUY, baseline.signals.first().direction)
      assertEquals(1.0, baseline.features["bookFresh"])
      val originalSignals = baseline.signals.toList()
      val originalFeatures = baseline.features.toMap()
      val transport = HttpClient(CIO)
      val client = JevClient(transport, timeoutMs = 30_000)
      try {
        val answers = client.evaluate(JevConfluence.query(baseline), apiKey)
        assertEquals("jev-1.13.0", answers.model)
        assertEquals(setOf("direction"), answers.answers.keys)
        val direction = answers.answers["direction"] as? DecisionAnswer.Choice
        assertNotNull(direction)
        assertTrue(direction.choice in setOf("buy", "sell", "wait"))
        assertEquals(setOf("buy", "sell", "wait"), direction.probabilities.keys)
        assertTrue(direction.probabilities.values.all { it.isFinite() && it in 0.0..1.0 })
        assertTrue(abs(direction.probabilities.values.sum() - 1.0) <= 0.001)
        assertEquals(
          direction.probabilities.values.max(),
          direction.probabilities[direction.choice],
        )
        assertTrue(direction.confidence.isFinite() && direction.confidence in 0.0..1.0)
        assertEquals(originalSignals, baseline.signals)
        assertEquals(originalFeatures, baseline.features)
        assertEquals(baseline, engine.evaluate(snapshot, now))
      } finally {
        client.close()
        transport.close()
      }
    }
  }
}
