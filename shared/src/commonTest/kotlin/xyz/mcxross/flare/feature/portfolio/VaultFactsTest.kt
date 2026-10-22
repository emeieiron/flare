package xyz.mcxross.flare.feature.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.mcxross.flare.venue.model.ManagerFee
import xyz.mcxross.flare.venue.model.VaultFactValue

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

  @Test
  fun aVenuesMeasureReadsByItsKindNotByTheVenue() {
    assertEquals("$1,250.00", vaultFactText(VaultFactValue.Money(1250.0)))
    assertEquals("18.4%", vaultFactText(VaultFactValue.Percent(18.4)))
    assertEquals("1.42", vaultFactText(VaultFactValue.Ratio(1.4231)))
    assertEquals("flat", vaultFactText(VaultFactValue.Text("flat")))
    // A signed measure keeps its sign so a loss cannot read as a gain.
    assertEquals("+4.2%", vaultFactText(VaultFactValue.Percent(4.2, signed = true)))
    assertEquals("-3.1%", vaultFactText(VaultFactValue.Percent(-3.1, signed = true)))
  }
}
