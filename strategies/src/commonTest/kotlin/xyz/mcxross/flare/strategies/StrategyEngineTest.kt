package xyz.mcxross.flare.strategies

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.domain.TradingIndicators

class StrategyEngineTest {
  private val key = StrategyMarketKey("test", "testnet", "BTC", 1000)
  private val engine = StrategyEngine()

  private fun candles(closes: List<Double>) =
    closes.mapIndexed { index, close ->
      StrategyCandle(index * 1000L, (index + 1) * 1000L, close, close + 1, close - 1, close, 10.0)
    }

  private fun evaluate(closes: List<Double>): StrategyEvaluation {
    val data = candles(closes)
    return engine.evaluate(
      StrategyMarketSnapshot(key, data.last().closeTimeMs, data),
      data.last().closeTimeMs,
    )
  }

  @Test
  fun flatMarketHasNeutralIndicatorsAndSignals() {
    val result = evaluate(List(40) { 100.0 })
    assertNull(result.rejection)
    assertEquals(50.0, result.features["rsi"])
    assertEquals(0.0, result.features["macdHistogram"])
    assertTrue(result.signals.all { it.direction == StrategyDirection.WAIT })
  }

  @Test
  fun macdCrossingIsDeterministicAndUsesClosedCandles() {
    val values = List(40) { 100.0 - it * 0.4 } + 110.0
    val baseline = evaluate(values)
    assertEquals(StrategyDirection.BUY, baseline.signals.first().direction)
    val data = candles(values) + StrategyCandle(41_000, 42_000, 1.0, 200.0, 1.0, 1.0, 0.0)
    assertEquals(baseline, engine.evaluate(StrategyMarketSnapshot(key, 41_000, data), 41_000))
    assertEquals(baseline, evaluate(values))
  }

  @Test
  fun snapshotsCannotKnowFutureClosedCandleValues() {
    val data = candles(List(40) { 100.0 - it * 0.4 } + 110.0)
    val evaluation = engine.evaluate(StrategyMarketSnapshot(key, 40_000, data), 41_000)
    assertEquals(40_000, evaluation.closedAtMs)
    assertEquals(evaluate(List(40) { 100.0 - it * 0.4 }).signals, evaluation.signals)
    assertEquals(
      StrategyDirection.SELL,
      evaluate(List(40) { 100.0 + it * 0.4 } + 90.0).signals.first().direction,
    )
  }

  @Test
  fun rsiExitsOversoldAndOverboughtZones() {
    assertEquals(
      StrategyDirection.BUY,
      evaluate(List(40) { 100.0 - it } + 100.0).signals[1].direction,
    )
    assertEquals(
      StrategyDirection.SELL,
      evaluate(List(40) { 100.0 + it } + 100.0).signals[1].direction,
    )
    assertEquals(100.0, TradingIndicators.rsi(List(40) { it.toDouble() }).last())
  }

  @Test
  fun momentumPullbackRequiresRecoveryInTrendDirection() {
    assertEquals(
      StrategyDirection.BUY,
      evaluate(List(39) { 100.0 + it } + 128.0 + 145.0).signals[2].direction,
    )
    assertEquals(
      StrategyDirection.SELL,
      evaluate(List(39) { 200.0 - it } + 172.0 + 155.0).signals[2].direction,
    )
  }

  @Test
  fun badDataIsRejectedWithoutDirectionalSignals() {
    val data = candles(List(40) { 100.0 })
    val snapshot = StrategyMarketSnapshot(key, 40_000, data)
    val invalid =
      listOf(
        snapshot.copy(fetchedAtMs = -1),
        snapshot.copy(fetchedAtMs = 40_001),
        snapshot.copy(candles = data.take(10)),
        snapshot.copy(candles = data.filterIndexed { index, _ -> index != 15 }),
        snapshot.copy(candles = data.toMutableList().also { it[15] = it[14] }),
        snapshot.copy(
          candles = data.toMutableList().also { it[15] = it[15].copy(close = Double.NaN) }
        ),
        snapshot.copy(candles = data.toMutableList().also { it[15] = it[15].copy(volume = -1.0) }),
        snapshot.copy(key = key.copy(timeframeMs = 0)),
      )
    invalid.forEach { result ->
      val evaluation = engine.evaluate(result, 40_000)
      assertNotNull(evaluation.rejection)
      assertTrue(evaluation.signals.all { it.direction == StrategyDirection.WAIT })
    }
    assertNotNull(engine.evaluate(snapshot, 102_000).rejection)
    assertNotNull(engine.evaluate(snapshot.copy(fetchedAtMs = 42_001), 42_001).rejection)
  }

  @Test
  fun optionalBookAddsFactsWithoutBlockingBaseline() {
    val data = candles(List(40) { 100.0 })
    val snapshot = StrategyMarketSnapshot(key, 40_000, data)
    val book =
      StrategyBook(
        listOf(StrategyBookLevel("99", "2")),
        listOf(StrategyBookLevel("101", "1")),
        40_000,
      )
    val result = engine.evaluate(snapshot.copy(book = book), 40_000)
    assertEquals(1.0, result.features["bookFresh"])
    assertEquals(1.0 / 3, result.features["depthImbalance10"])
    val crossed =
      engine.evaluate(
        snapshot.copy(book = book.copy(asks = listOf(StrategyBookLevel("98", "1")))),
        40_000,
      )
    assertEquals(result.signals, crossed.signals)
    assertEquals(0.0, crossed.features["bookFresh"])
    assertFalse(crossed.features.containsKey("spreadFraction"))
  }

  @Test
  fun replayMatchesEachHistoricalPrefix() {
    val data = candles(List(40) { 100.0 - it * 0.4 } + 110.0 + 60.0)
    val replay = engine.replay(key, data)
    for (i in data.indices) assertEquals(
      engine.evaluate(
        StrategyMarketSnapshot(key, data[i].closeTimeMs, data.take(i + 1)),
        data[i].closeTimeMs,
      ),
      replay[i],
    )
    assertEquals(StrategyDirection.BUY, replay[40].signals.first().direction)
    assertEquals(replay.take(41), engine.replay(key, data.take(41)))
  }

  @Test
  fun optionalModelNeverChangesBaselineAndConfidenceIsSeparate() = runTest {
    val baseline = evaluate(List(40) { 100.0 - it * 0.4 } + 110.0)
    val model = DecisionModel {
      DecisionAnswers(
        "jev-1.13.0",
        mapOf(
          "direction" to
            DecisionAnswer.Choice("buy", mapOf("buy" to 0.8, "sell" to 0.1, "wait" to 0.1), 0.55)
        ),
      )
    }
    val confluence = JevConfluence.evaluate(baseline, model)
    assertEquals(0.8, confluence.probabilities[StrategyDirection.BUY])
    assertEquals(0.55, confluence.confidence)
    val candidate = baseline.signals.first()
    assertEquals(StrategyDirection.BUY, JevConfluence.applyGate(candidate, null, false))
    assertEquals(StrategyDirection.WAIT, JevConfluence.applyGate(candidate, confluence, true))
    assertEquals(StrategyDirection.BUY, JevConfluence.applyGate(candidate, confluence, true, 0.5))
    assertEquals(baseline, evaluate(List(40) { 100.0 - it * 0.4 } + 110.0))
  }
}
