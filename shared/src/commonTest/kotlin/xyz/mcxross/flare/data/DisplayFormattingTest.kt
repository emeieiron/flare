package xyz.mcxross.flare.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayFormattingTest {
  @Test
  fun balancesKeepZeroAndSmallLossesVisible() {
    assertEquals("\$0.00", formatBalance(0.0))
    assertEquals("−\$0.12", formatBalance(-0.12))
    assertEquals("\$12,480.65", formatBalance(12480.65))
    assertEquals("—", formatBalance(Double.NaN))
    assertEquals("\$-0.12", formatCompact(-0.12))
    assertEquals("−<\$0.01", formatBalance(-0.00056))
    assertEquals("<\$0.01", formatBalance(0.001))
  }

  @Test
  fun priceSeparatorsImproveScanningWithoutChangingPrecision() {
    assertEquals("\$67,432.18", formatPrice(67432.18))
    assertEquals("\$0.14280", formatPrice(0.1428))
    assertEquals("—", formatPrice(0.0))
  }

  @Test
  fun smallQuantitiesStayReadableWithoutScientificNotation() {
    assertEquals("0.00002", formatQuantity(2.0e-5))
    assertEquals("-0.00002", formatQuantity(-2.0e-5))
    assertEquals("9.998321", formatQuantity(9.998321, 6))
    assertEquals("1", formatQuantity(1.0))
    assertEquals("0", formatQuantity(0.0))
    assertEquals("—", formatQuantity(Double.NaN))
  }
}
