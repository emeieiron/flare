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
    val slots = (1_000L until 1_240L).toList()
    assertEquals(139..200, visibleCandleIndices(slots, 1_139L..1_200L, 60))
    assertEquals(0..60, visibleCandleIndices(slots, 990L..1_060L, 60))
    assertEquals(179..239, visibleCandleIndices(slots, 1_179L..1_250L, 60))
    assertEquals(180..239, visibleCandleIndices(slots, null, 60))
    assertEquals(0..4, visibleCandleIndices(slots.take(5), null, 60))
    assertTrue(visibleCandleIndices(emptyList(), 0L..60L, 60).isEmpty())
  }

  @Test
  fun missingCandlesDoNotShiftTheWindow() {
    val slots = listOf(10L, 11L, 14L, 15L, 16L)
    assertEquals(2..3, visibleCandleIndices(slots, 12L..15L, 60))
    assertEquals(4..4, visibleCandleIndices(slots, 20L..30L, 60))
    assertEquals(0..0, visibleCandleIndices(slots, 0L..5L, 60))
  }
}
