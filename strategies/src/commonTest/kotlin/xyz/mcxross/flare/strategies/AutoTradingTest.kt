package xyz.mcxross.flare.strategies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoTradingTest {
  private val config =
    AutoRunConfig(
      StrategyKind.MACD_CROSSOVER,
      AutoRunMode.SIMULATION,
      "1",
      "20",
      "100000",
      20,
      60,
      30,
      100,
      1,
    )

  private fun snapshot(
    bid: String = "99",
    ask: String = "100",
    time: Long = 10_000,
    bidSize: String = "100",
    askSize: String = "100",
  ) =
    StrategyMarketSnapshot(
      StrategyMarketKey("test", "simulation", "BTC", 1000),
      time,
      emptyList(),
      StrategyBook(
        listOf(StrategyBookLevel(bid, bidSize)),
        listOf(StrategyBookLevel(ask, askSize)),
        time,
      ),
    )

  @Test
  fun exactAmountsPreserveUsefulPrecisionAndNormalize() {
    assertEquals("800", AutoAmounts.multiply("0.010000000000000000", "80000.000000000000000000"))
    assertEquals("2000", AutoAmounts.multiply("1.000000000000000000", "2000.000000000000000000"))
    assertEquals("0.012345678901234567", AutoAmounts.multiply("0.12345678901234567", "0.1"))
    assertEquals("0.000000000000000001", AutoAmounts.normalize("0.000000000000000001"))
    assertEquals("0.3", AutoAmounts.add("0.1", "0.2"))
    assertEquals("-0.1", AutoAmounts.subtract("0.2", "0.3"))
    assertTrue(AutoAmounts.compare("0.000000000000000001", "100000") < 0)
    assertTrue(AutoAmounts.compare("-100000", "-0.000000000000000001") < 0)
    assertEquals("1", AutoAmounts.multiply("125", "0.008"))
    assertEquals(ExactAmount.parse("1"), ExactAmount.parse("1.000"))
  }

  @Test
  fun unsupportedPrecisionAndOverflowAreExplicit() {
    assertFailsWith<IllegalArgumentException> { AutoAmounts.normalize("0.0000000000000000001") }
    assertFailsWith<IllegalStateException> { AutoAmounts.normalize("9223372036854775808") }
    assertFailsWith<IllegalArgumentException> { AutoAmounts.add("9223372036854775807", "1") }
    assertFailsWith<IllegalArgumentException> { AutoAmounts.multiply("9223372036854775807", "2") }
    assertFailsWith<IllegalArgumentException> {
      AutoAmounts.multiply("0.000000000000000001", "0.1")
    }
    assertFailsWith<IllegalArgumentException> { AutoAmounts.normalize("1e3") }
    assertFailsWith<IllegalArgumentException> { AutoAmounts.normalize("NaN") }
  }

  @Test
  fun riskReservesAttemptsAndCumulativeNotionalBeforeSend() {
    val limits =
      config.copy(
        orderSize = "0.01",
        maxTotalNotional = "1600",
        maxOrders = 2,
        mode = AutoRunMode.REAL,
        leverage = 5,
      )
    val first = AutoRisk.check(limits, "0", 0, "0", StrategyDirection.BUY, "80000")
    assertTrue(first.allowed)
    val reservation = assertNotNull(first.reservation)
    assertEquals("800", reservation.notional)
    assertEquals("0.01", reservation.projectedPosition)
    assertEquals(1, reservation.nextAttempts)
    val second =
      AutoRisk.check(
        limits,
        reservation.projectedPosition,
        reservation.nextAttempts,
        reservation.nextConsumedNotional,
        StrategyDirection.SELL,
        "80000",
      )
    assertEquals("1600", assertNotNull(second.reservation).nextConsumedNotional)
    assertEquals("0", second.reservation.projectedPosition)
    assertFalse(AutoRisk.check(limits, "0", 2, "1600", StrategyDirection.BUY, "1").allowed)
    assertFalse(AutoRisk.check(limits, "0", 0, "801", StrategyDirection.BUY, "80000").allowed)
    assertFalse(AutoRisk.check(limits, "0", 0, "0", StrategyDirection.WAIT, "80000").allowed)
    assertFalse(AutoRisk.check(limits, "0", 0, "-1", StrategyDirection.BUY, "80000").allowed)
  }

  @Test
  fun positionCapAllowsReductionButBoundsReversalAndSpot() {
    val limits = config.copy(maxPositionSize = "2", orderSize = "1")
    assertTrue(AutoRisk.check(limits, "10", 0, "0", StrategyDirection.SELL, "100").allowed)
    assertFalse(AutoRisk.check(limits, "10", 0, "0", StrategyDirection.BUY, "100").allowed)
    assertFalse(
      AutoRisk.check(limits.copy(orderSize = "19"), "10", 0, "0", StrategyDirection.SELL, "100")
        .allowed
    )
    assertTrue(
      AutoRisk.check(limits.copy(orderSize = "11"), "10", 0, "0", StrategyDirection.SELL, "100")
        .allowed
    )
    assertTrue(
      AutoRisk.check(limits.copy(orderSize = "10"), "10", 0, "0", StrategyDirection.SELL, "100")
        .allowed
    )
    assertFalse(
      AutoRisk.check(limits, "0", 0, "0", StrategyDirection.SELL, "100", allowShort = false).allowed
    )
    assertFalse(
      AutoRisk.check(
          limits.copy(orderSize = "2"),
          "1",
          0,
          "0",
          StrategyDirection.SELL,
          "100",
          allowShort = false,
        )
        .allowed
    )
  }

  @Test
  fun invalidConfigAndRiskAmountsFailClosed() {
    listOf(
        config.copy(leverage = 2),
        config.copy(orderSize = "0"),
        config.copy(maxOrders = 0),
        config.copy(durationMinutes = 0),
        config.copy(slippageBps = 1001),
        config.copy(minConfidence = Double.NaN),
      )
      .forEach { assertNotNull(AutoRisk.validate(it)) }
    assertNull(AutoRisk.validate(config))
    assertFalse(
      AutoRisk.check(
          config.copy(orderSize = "9223372036854775807"),
          "0",
          0,
          "0",
          StrategyDirection.BUY,
          "2",
        )
        .allowed
    )
  }

  @Test
  fun paperLongReductionAndReversalAllocateCostExactly() {
    val simulator = PaperSimulator(feeBps = 0)
    val opened =
      simulator.fill(
        config.copy(orderSize = "2"),
        PaperState(),
        snapshot(),
        StrategyDirection.BUY,
        10_000,
      )
    assertNull(opened.rejection)
    assertEquals("2", opened.state.positionSize)
    assertEquals("200", opened.state.entryNotional)
    assertEquals("20", simulator.unrealizedPnl(opened.state, "110"))
    val reduced =
      simulator.fill(config, opened.state, snapshot("110", "111"), StrategyDirection.SELL, 10_000)
    assertEquals("1", reduced.state.positionSize)
    assertEquals("100", reduced.state.entryNotional)
    assertEquals("10", reduced.state.realizedPnl)
    val reversed =
      simulator.fill(
        config.copy(orderSize = "2"),
        reduced.state,
        snapshot("120", "121"),
        StrategyDirection.SELL,
        10_000,
      )
    assertEquals("-1", reversed.state.positionSize)
    assertEquals("120", reversed.state.entryNotional)
    assertEquals("30", reversed.state.realizedPnl)
    assertEquals("10", simulator.unrealizedPnl(reversed.state, "110"))
    val closed =
      simulator.fill(config, reversed.state, snapshot("99", "100"), StrategyDirection.BUY, 10_000)
    assertEquals("0", closed.state.positionSize)
    assertEquals("0", closed.state.entryNotional)
    assertEquals("50", closed.state.realizedPnl)
  }

  @Test
  fun paperShortReductionAndLongReversalHaveCorrectPnlSign() {
    val simulator = PaperSimulator(feeBps = 0)
    val opened =
      simulator.fill(
        config.copy(orderSize = "2"),
        PaperState(),
        snapshot("100", "101"),
        StrategyDirection.SELL,
        10_000,
      )
    assertEquals("-2", opened.state.positionSize)
    assertEquals("200", opened.state.entryNotional)
    assertEquals("-20", simulator.unrealizedPnl(opened.state, "110"))
    val reduced =
      simulator.fill(config, opened.state, snapshot("89", "90"), StrategyDirection.BUY, 10_000)
    assertEquals("-1", reduced.state.positionSize)
    assertEquals("10", reduced.state.realizedPnl)
    val reversed =
      simulator.fill(
        config.copy(orderSize = "2"),
        reduced.state,
        snapshot("79", "80"),
        StrategyDirection.BUY,
        10_000,
      )
    assertEquals("1", reversed.state.positionSize)
    assertEquals("80", reversed.state.entryNotional)
    assertEquals("30", reversed.state.realizedPnl)
  }

  @Test
  fun paperDepthSpreadAndFeesAreExplicit() {
    val simulator = PaperSimulator()
    val book =
      snapshot()
        .copy(
          book =
            StrategyBook(
              listOf(StrategyBookLevel("99", "10")),
              listOf(StrategyBookLevel("100", "0.5"), StrategyBookLevel("101", "0.5")),
              10_000,
            )
        )
    val result = simulator.fill(config, PaperState(), book, StrategyDirection.BUY, 10_000)
    assertNull(result.rejection)
    assertEquals("100.5", result.fill?.price)
    assertEquals("100.5", result.fill?.notional)
    assertEquals("0.05025", result.fill?.fee)
    assertEquals("-0.05025", result.state.realizedPnl)
    assertEquals("0.05025", result.state.feesPaid)
    assertEquals("-1.5", simulator.unrealizedPnl(result.state, "99"))
    assertNotNull(
      simulator
        .fill(config.copy(slippageBps = 0), PaperState(), book, StrategyDirection.BUY, 10_000)
        .rejection
    )
    assertNotNull(
      simulator
        .fill(config.copy(orderSize = "2"), PaperState(), book, StrategyDirection.BUY, 10_000)
        .rejection
    )
  }

  @Test
  fun staleInvalidOrRejectedPaperFillsPreserveLedger() {
    val simulator = PaperSimulator()
    val initial = PaperState()
    val cases =
      listOf(
        snapshot(time = 1),
        snapshot(time = 10_001),
        snapshot("101", "100"),
        snapshot(askSize = "0.1"),
        snapshot().copy(book = null),
      )
    cases.forEach { market ->
      val result = simulator.fill(config, initial, market, StrategyDirection.BUY, 10_000)
      assertNotNull(result.rejection)
      assertEquals(initial, result.state)
      assertNull(result.fill)
    }
    assertNotNull(
      simulator
        .fill(config, initial, snapshot(), StrategyDirection.SELL, 10_000, allowShort = false)
        .rejection
    )
    assertNotNull(
      simulator
        .fill(
          config.copy(mode = AutoRunMode.REAL),
          initial,
          snapshot(),
          StrategyDirection.BUY,
          10_000,
        )
        .rejection
    )
    assertNotNull(
      simulator
        .fill(config, PaperState(positionSize = "1"), snapshot(), StrategyDirection.BUY, 10_000)
        .rejection
    )
  }
}
