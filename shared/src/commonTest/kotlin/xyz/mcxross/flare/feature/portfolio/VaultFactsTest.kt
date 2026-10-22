package xyz.mcxross.flare.feature.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.flare.venue.model.ManagerFee

class VaultFactsTest {
  @Test
  fun aRealButTinyManagerStakeNeverReadsAsNone() {
    assertEquals("<0.01% of the vault", managerStakeText(6.645107064126772e-5))
    assertEquals("7.5% of the vault", managerStakeText(7.5))
    assertEquals("0% of the vault", managerStakeText(0.0))
  }

  @Test
  fun eachFeeMechanicExplainsWhatItCosts() {
    assertEquals("10%", managerFeeRate(ManagerFee.Interval(10.0)))
    assertEquals("10% every 90 days", managerFeeRate(ManagerFee.Interval(10.0, days = 90)))
    assertEquals("0%", managerFeeRate(ManagerFee.HighWatermark(0.0)))

    val interval = requireNotNull(managerFeeNote(ManagerFee.Interval(10.0)))
    assertTrue("no high watermark" in interval && "before you arrived" in interval)
    val watermark = requireNotNull(managerFeeNote(ManagerFee.HighWatermark(10.0)))
    assertTrue("previous peak" in watermark)
    // Nothing to disclose when the manager takes nothing.
    assertNull(managerFeeNote(ManagerFee.HighWatermark(0.0)))
    assertNull(managerFeeNote(ManagerFee.Interval(0.0)))
  }
}
