package xyz.mcxross.flare.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndicatorsTest {
  @Test
  fun risingSeriesProducesMaximumRsi() {
    val values = (1..20).map(Int::toDouble)
    val rsi = TradingIndicators.rsi(values, period = 14)
    assertEquals(100.0, rsi.last())
    assertTrue(rsi.take(14).all { it == null })
  }

  @Test
  fun flatSeriesProducesNeutralRsi() {
    val rsi = TradingIndicators.rsi(List(20) { 42.0 }, period = 14)
    assertEquals(50.0, rsi.last())
  }

  @Test
  fun constantSeriesHasZeroMacd() {
    val macd = TradingIndicators.macd(List(40) { 7.0 })
    assertTrue(
      macd.all { abs(it.macd) < 1e-9 && abs(it.signal) < 1e-9 && abs(it.histogram) < 1e-9 }
    )
  }
}
