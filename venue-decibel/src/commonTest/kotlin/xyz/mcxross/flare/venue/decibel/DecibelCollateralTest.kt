package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.decibel.model.SecondaryCollateral

class DecibelCollateralTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun aHaircutReadsAsTheCreditItLeaves() {
    val collateral =
      json.decodeFromString<SecondaryCollateral>(
        """{"asset_type":"0xdlp","amount":1200.5,"haircut_bps":1000,"nav_per_unit":1.0423,""" +
          """"value_in_usdc":1251.28,"withdrawable_amount":900.0}"""
      )
    // Decibel stores the discount; a person is told what still counts.
    assertEquals(90.0, collateral.creditPercent)

    val asset = collateral.toCollateralAsset()
    assertEquals(1200.5, asset.amount)
    assertEquals(1251.28, asset.valueUsd)
    assertEquals(90.0, asset.creditPercent)
    assertEquals(900.0, asset.withdrawable)
    // An asset identified only by address still needs a name on screen.
    assertTrue(asset.name.isNotBlank() && !asset.name.startsWith("0x"))
  }

  @Test
  fun noHaircutIsFullCredit() {
    assertEquals(100.0, SecondaryCollateral(haircutBps = 0).creditPercent)
  }
}
