package xyz.mcxross.flare.feature.orders

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.flare.venue.model.FillDirection
import xyz.mcxross.flare.venue.model.FundingPayment

class FundingLabelTest {
  private val payment = FundingPayment("0x1", "CloseLong", 1.0, -0.5, timeMs = 1L)

  @Test
  fun aSettlingTradeIsNamedLikeAFill() {
    assertEquals("Closed long", fundingLabel(payment.copy(direction = FillDirection.CLOSE_LONG)))
    assertEquals("Opened short", fundingLabel(payment.copy(direction = FillDirection.OPEN_SHORT)))
  }

  @Test
  fun otherwiseTheVenueLabelOrAGenericOneIsShown() {
    assertEquals("Long funding", fundingLabel(payment.copy(action = "Long funding")))
    assertEquals("Funding payment", fundingLabel(payment.copy(action = "")))
  }
}
