package xyz.mcxross.flare.feature.trade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartViewportTest {
  @Test
  fun smallPriceMovesUseTheViewport() {
    val bounds = priceBounds(listOf(62_400.0, 62_500.0, 62_600.0))
    assertTrue(bounds.start > 62_000)
    assertTrue(bounds.start < 62_400 && bounds.endInclusive > 62_600)
    assertTrue(bounds.endInclusive - bounds.start < 300)
  }

  @Test
  fun flatAndSinglePricesHaveNonzeroHeight() {
    for (price in listOf(0.0, 0.000002, 100.0)) {
      val bounds = priceBounds(listOf(price, price))
      assertTrue(bounds.start < price && bounds.endInclusive > price)
    }
  }

  @Test
  fun invalidSamplesCannotBreakTheViewport() {
    assertEquals(0.0..1.0, priceBounds(listOf(Double.NaN, Double.POSITIVE_INFINITY)))
    assertEquals(priceBounds(listOf(12.0)), priceBounds(listOf(12.0, Double.NaN)))
  }

  @Test
  fun panningIncludesPartialCandlesAndClampsAtHistoryEdges() {
    assertEquals(139..200, visibleCandleIndices(139.8, 199.2, 240))
    assertEquals(0..60, visibleCandleIndices(-0.5, 59.5, 240))
    assertEquals(179..239, visibleCandleIndices(179.3, 239.5, 240))
    assertEquals(0..4, visibleCandleIndices(-0.5, 4.5, 5))
    assertTrue(visibleCandleIndices(0.0, 60.0, 0).isEmpty())
  }
}
