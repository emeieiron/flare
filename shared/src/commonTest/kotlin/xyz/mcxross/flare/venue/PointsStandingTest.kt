package xyz.mcxross.flare.venue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mcxross.flare.venue.model.PointsStanding

class PointsStandingTest {
  private val season = PointsStanding(holders = 61_200, distributed = 9_000_000.0)

  @Test
  fun aRankBecomesAPositionAgainstTheField() {
    assertEquals(7.86, season.topPercent(4_812)!!, 0.01)
    // The very top still reads as a sliver rather than zero.
    assertEquals(0.01, season.topPercent(1)!!, 1e-9)
    assertEquals(100.0, season.topPercent(61_200)!!, 1e-9)
  }

  @Test
  fun anUnrankedOrEmptySeasonSaysNothingRatherThanZero() {
    assertNull(season.topPercent(null))
    assertNull(season.topPercent(0))
    assertNull(PointsStanding(holders = 0, distributed = 0.0).topPercent(5))
    assertNull(PointsStanding(holders = 10, distributed = 0.0).shareOf(1_000.0))
    assertNull(season.shareOf(0.0))
  }

  @Test
  fun aShareIsOfEverythingHandedOut() {
    assertEquals(0.015, season.shareOf(1_350.0)!!, 1e-9)
  }
}
