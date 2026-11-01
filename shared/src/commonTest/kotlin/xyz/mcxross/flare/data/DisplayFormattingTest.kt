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
  fun tokenBalancesShareTwoDecimalsWithoutRoundingStoredAmounts() {
    assertEquals("1.39", formatTokenBalance("1.387092"))
    assertEquals("6.66", formatTokenBalance("6.6571584"))
    assertEquals("1.00", formatTokenBalance("1"))
    assertEquals("0.00", formatTokenBalance("0.000000"))
    assertEquals("10.00", formatTokenBalance("9.99999999"))
    assertEquals("1.01", formatTokenBalance("1.005"))
    assertEquals("18446744073709551615.99", formatTokenBalance("18446744073709551615.987654"))
    assertEquals("<0.01", formatTokenBalance("0.000001"))
    assertEquals("−<0.01", formatTokenBalance("-0.000001"))
    assertEquals("0.00", formatTokenBalance(-0.0))
    assertEquals("<0.01", formatTokenBalance(1.0e-12))
    assertEquals("100000000000000000000.00", formatTokenBalance(1.0e20))
    assertEquals("—", formatTokenBalance(Double.NaN))
    assertEquals("—", formatTokenBalance("unavailable"))
    val balance = WalletTokenBalance("USDC", "1.387092")
    assertEquals("1.39", balance.displayAmount)
    assertEquals("1.387092", balance.amount)
    assertEquals("2.35", WalletTokenBalance("OTHER", "2.3456").displayAmount)
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
