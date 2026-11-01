package xyz.mcxross.flare.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwapRequestTest {
  private val apt = SwapAsset("aptos:1", "0xa", 8)
  private val usdc = SwapAsset("aptos:1", "0xb", 6)

  @Test
  fun quantitiesRetainPrecisionAboveTheFloatingPointIntegerRange() {
    val request =
      SwapRequest("owner", apt, usdc, SwapAmountKind.EXACT_OUTPUT, "18446744073709551615")
    assertEquals("18446744073709551615", request.amount)
    for (amount in listOf("18446744073709551616", "1.5", "1e6", "-1", "0", "01")) {
      assertFailsWith<IllegalArgumentException> { request.copy(amount = amount) }
    }
  }

  @Test
  fun rejectsCrossNetworkSwapsAndUnboundedSlippage() {
    assertFailsWith<IllegalArgumentException> {
      SwapRequest("owner", apt, usdc.copy(network = "aptos:2"), SwapAmountKind.EXACT_INPUT, "1")
    }
    assertFailsWith<IllegalArgumentException> {
      SwapRequest("owner", apt, usdc, SwapAmountKind.EXACT_INPUT, "1", slippageBps = 101)
    }
  }
}
