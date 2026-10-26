package xyz.mcxross.flare.venue.hyperliquid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.venue.model.MarketKind

class HyperliquidMarketsRepositoryTest {
  @Test
  fun listsPerpsWithoutDelistedOnesAndTheirContextsByPosition() = runTest {
    val repository = HyperliquidMarketsRepository(testClient(MarketResponses), memoryPreferences(), MemoryMarketCache())

    repository.refresh()

    val catalog = repository.catalog.value
    val perps = catalog.quotes.filter { it.market.kind == MarketKind.PERP }.map { it.market.id }
    assertEquals(14, perps.size)
    assertTrue(perps.none { it in setOf("MATIC", "RLB", "HPOS", "UNIBOT") })
    val btc = catalog.quotes.first { it.market.id == "BTC" }
    assertEquals("BTC", btc.market.symbol)
    assertEquals("BTC/USD", btc.market.displayName)
    assertEquals(5, btc.market.sizeDecimals)
    assertEquals(1, btc.market.priceDecimals)
    assertEquals(40, btc.market.maxLeverage)
    assertEquals(83109.0, btc.markPrice)
    assertEquals((83109.0 - 82498.0) / 82498.0 * 100.0, btc.changePercent24h, 1e-9)
    assertEquals(48.75562 * 83109.0, btc.openInterest, 1e-6)
    assertEquals(-10.737421, btc.fundingRateBps!!, 1e-9)
    assertFalse(catalog.stale)
    assertNull(catalog.error)
  }

  @Test
  fun listsSpotPairsThatTradedOrAreCanonicalMatchedByCoin() = runTest {
    val repository = HyperliquidMarketsRepository(testClient(MarketResponses), memoryPreferences(), MemoryMarketCache())

    repository.refresh()

    val spots = repository.catalog.value.quotes.filter { it.market.kind == MarketKind.SPOT }
    assertEquals(setOf("PURR/USDC", "@1035"), spots.map { it.market.id }.toSet())
    val hype = spots.first { it.market.id == "@1035" }
    assertEquals("HYPE", hype.market.symbol)
    assertEquals("HYPE/USDC", hype.market.displayName)
    assertEquals(2, hype.market.sizeDecimals)
    assertEquals(6, hype.market.priceDecimals)
    assertEquals(39.141, hype.markPrice)
    assertTrue(repository.isListed("@1035"))
    assertFalse(repository.isListed("@7"))
  }

  @Test
  fun seedsTheWatchlistOnceAndSortsFavoritesFirst() = runTest {
    val preferences = memoryPreferences()
    val repository = HyperliquidMarketsRepository(testClient(MarketResponses), preferences, MemoryMarketCache())

    repository.refresh()

    assertEquals(setOf("BTC", "ETH", "SOL", "@1035"), preferences.favoriteMarkets.first())
    val quotes = repository.catalog.value.quotes
    assertTrue(quotes.take(4).all { it.favorite })
    repository.toggleFavorite("BTC")
    assertFalse(repository.catalog.value.quotes.first { it.market.id == "BTC" }.favorite)
  }

  @Test
  fun showsTheCachedMarketsAsStaleWhenHyperliquidIsUnreachable() = runTest {
    val cache = MemoryMarketCache()
    HyperliquidMarketsRepository(testClient(MarketResponses), memoryPreferences(), cache).refresh()
    val offline = HyperliquidMarketsRepository(testClient(emptyMap()), memoryPreferences(), cache)

    offline.refresh()

    val catalog = offline.catalog.value
    assertTrue(catalog.stale)
    assertEquals(16, catalog.quotes.size)
    val btc = assertNotNull(catalog.quotes.firstOrNull { it.market.id == "BTC" })
    assertEquals(83109.0, btc.markPrice)
    assertEquals(btc.market.rules, HyperliquidOrderRules("BTC", "BTC", MarketKind.PERP, 5))
  }

  @Test
  fun liveMidsMoveThePriceAndTheDaysChange() = runTest {
    val repository = HyperliquidMarketsRepository(testClient(MarketResponses), memoryPreferences(), MemoryMarketCache())
    repository.refresh()

    repository.applyMids(mapOf("BTC" to "90747.8", "@1035" to "40", "#1" to "0.5"), now = 1L)

    val quotes = repository.catalog.value.quotes.associateBy { it.market.id }
    assertEquals(90747.8, quotes.getValue("BTC").markPrice)
    assertEquals(10.0, quotes.getValue("BTC").changePercent24h, 1e-9)
    assertEquals(40.0, quotes.getValue("@1035").markPrice)
    assertEquals(111.27, quotes.getValue("SOL").markPrice)
  }

  @Test
  fun listsOnlyStablecoinPairsThatTradeAndNamesAnUnusualQuote() = runTest {
    val preferences = memoryPreferences()
    val repository =
      HyperliquidMarketsRepository(
        testClient(MarketResponses + ("spotMetaAndAssetCtxs" to MixedSpots)),
        preferences,
        MemoryMarketCache(),
      )

    repository.refresh()

    val spots = repository.catalog.value.quotes.filter { it.market.kind == MarketKind.SPOT }.associateBy { it.market.id }
    // PURR/TZERO is canonical but not quoted in a stable, and JUNK and UETH trade too little.
    assertEquals(setOf("PURR/USDC", "@1", "@3"), spots.keys)
    assertNull(spots.getValue("@1").market.quoteAsset)
    assertEquals("USDT0", spots.getValue("@3").market.quoteAsset)
    val favorites = preferences.favoriteMarkets.first()
    assertTrue("@1" in favorites)
    assertFalse("@3" in favorites)
  }

  @Test
  fun aSpotPairNeedsAThousandUsdcOfDailyVolume() = runTest {
    val spots = testClient(mapOf("spotMetaAndAssetCtxs" to MixedSpots)).info.spotMetaAndContexts()
    val listed = spots.toCachedMarkets(MIN_SPOT_DAY_VOLUME).map { it.coin }.toSet()
    assertEquals(1_000.0, MIN_SPOT_DAY_VOLUME)
    // @6 trades 500 USDC a day; canonical PURR/USDC is listed whatever it trades.
    assertEquals(setOf("PURR/USDC", "@1", "@3"), listed)
    assertEquals(listed + "@6", spots.toCachedMarkets(500.0).map { it.coin }.toSet())
  }

  private companion object {
    const val MixedSpots =
      """[{"universe":[""" +
        """{"tokens":[4,0],"name":"PURR/USDC","index":0,"isCanonical":true},""" +
        """{"tokens":[1,0],"name":"@1","index":1},""" +
        """{"tokens":[1,2],"name":"@2","index":2},""" +
        """{"tokens":[1,3],"name":"@3","index":3},""" +
        """{"tokens":[4,2],"name":"@4","index":4,"isCanonical":true},""" +
        """{"tokens":[5,0],"name":"@5","index":5},""" +
        """{"tokens":[6,0],"name":"@6","index":6}],"tokens":[""" +
        """{"name":"USDC","szDecimals":8,"weiDecimals":8,"index":0,"tokenId":"0x0","isCanonical":true},""" +
        """{"name":"HYPE","szDecimals":2,"weiDecimals":8,"index":1,"tokenId":"0x1"},""" +
        """{"name":"TZERO","szDecimals":2,"weiDecimals":8,"index":2,"tokenId":"0x2"},""" +
        """{"name":"USDT0","szDecimals":2,"weiDecimals":8,"index":3,"tokenId":"0x3"},""" +
        """{"name":"PURR","szDecimals":0,"weiDecimals":5,"index":4,"tokenId":"0x4","isCanonical":true},""" +
        """{"name":"JUNK","szDecimals":0,"weiDecimals":5,"index":5,"tokenId":"0x5"},""" +
        """{"name":"UETH","szDecimals":4,"weiDecimals":8,"index":6,"tokenId":"0x6"}]},[""" +
        """{"coin":"PURR/USDC","prevDayPx":"4.5","dayNtlVlm":"0.0","markPx":"4.6"},""" +
        """{"coin":"@1","prevDayPx":"32.0","dayNtlVlm":"50000.0","markPx":"32.7"},""" +
        """{"coin":"@2","prevDayPx":"120.0","dayNtlVlm":"500.0","markPx":"124.1"},""" +
        """{"coin":"@3","prevDayPx":"32.0","dayNtlVlm":"5000.0","markPx":"32.8"},""" +
        """{"coin":"@4","prevDayPx":"0.0","dayNtlVlm":"0.0","markPx":"5.0"},""" +
        """{"coin":"@5","prevDayPx":"0.0","dayNtlVlm":"50.0","markPx":"0.005"},""" +
        """{"coin":"@6","prevDayPx":"100.0","dayNtlVlm":"500.0","markPx":"105.6"}]]"""
  }
}
