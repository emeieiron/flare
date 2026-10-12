package xyz.mcxross.flare.feature.trade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.mcxross.flare.venue.model.AccountOverview
import xyz.mcxross.flare.venue.model.Balances
import xyz.mcxross.flare.venue.model.SpotBalance
import xyz.mcxross.flare.venue.model.SpotBalances

class SpotQuoteBalanceTest {
  private val spot = SpotBalances(assets = listOf(SpotBalance("0", "USDC", 120.0, 120.0)))

  @Test
  fun addsWhatPerpetualsCanWithdrawToSpotUsdc() {
    val overview = AccountOverview(0.0, 0.0, 0.0, 50.0, withdrawable = 30.0, balances = Balances(spot = spot))

    assertEquals(150.0, spotQuoteBalance(overview))
  }

  @Test
  fun takesTheVenuesFigureWhereSpotAlreadyHoldsThePerpetualsValue() {
    val overview =
      AccountOverview(
        equity = 100.0,
        unrealizedPnl = 0.0,
        marginRatio = 0.0,
        availableToTrade = 20.0,
        withdrawable = 20.0,
        balances = Balances(spot = spot, sharedWithEquity = 100.0, spotBuyingPower = 20.0),
      )

    assertEquals(20.0, spotQuoteBalance(overview))
  }

  @Test
  fun isUnknownWithoutSpotBalances() {
    assertNull(spotQuoteBalance(null))
    assertNull(spotQuoteBalance(AccountOverview(0.0, 0.0, 0.0, 0.0, 10.0)))
  }
}
