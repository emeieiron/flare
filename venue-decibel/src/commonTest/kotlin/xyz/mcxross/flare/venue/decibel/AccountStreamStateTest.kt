package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.venue.model.AccountOverview

class AccountStreamStateTest {
  @Test
  fun transportFailureKeepsConfirmedPortfolioAndEmptyHistory() {
    val portfolio = AccountSnapshot(
      account = "0x1",
      overview = AccountOverview(100.0, 0.0, 0.0, 100.0, 100.0),
      stale = false,
    )
    val history = AccountHistorySnapshot(account = "0x1", stale = false)

    assertEquals(portfolio, portfolio.afterStreamFailure("Upstream unavailable"))
    assertEquals(history, history.afterStreamFailure("Upstream unavailable"))
  }

  @Test
  fun transportFailureDoesNotHideTheRestFailure() {
    val portfolio = AccountSnapshot(account = "0x1", error = "Incompatible account response")
    val history = AccountHistorySnapshot(account = "0x1", error = "History request failed")

    assertEquals(portfolio, portfolio.afterStreamFailure("Upstream unavailable"))
    assertEquals(history, history.afterStreamFailure("Upstream unavailable"))
  }

  @Test
  fun unavailableInitialDataReportsTheConnectionFailure() {
    val portfolio = AccountSnapshot(account = "0x1").afterStreamFailure("Upstream unavailable")
    val history = AccountHistorySnapshot(account = "0x1").afterStreamFailure("Upstream unavailable")

    assertTrue(portfolio.stale)
    assertEquals("Upstream unavailable", portfolio.error)
    assertTrue(history.stale)
    assertEquals("Upstream unavailable", history.error)
  }
}
