package xyz.mcxross.flare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.ChartRange
import xyz.mcxross.flare.data.MarketQuote
import xyz.mcxross.flare.data.candleRequestWindows
import xyz.mcxross.flare.data.chartRequestSpec
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.CandleInterval
import xyz.mcxross.flare.decibel.model.Market

class SharedCommonTest {

  @Test
  fun suspendResultPreservesCoroutineCancellation() = runTest {
    assertFailsWith<CancellationException> {
      runSuspendCatching<Unit> { throw CancellationException("test cancellation") }
    }
  }

  @Test
  fun runtimeConfigDerivesOnlyAllowlistedWorkerEndpoints() {
    val runtime = FlareRuntimeConfig(workerBaseUrl = "https://worker.example/")

    assertEquals("https://worker.example/decibel", runtime.decibelRestUrl)
    assertEquals("wss://worker.example/decibel/ws", runtime.decibelWebSocketUrl)
    assertEquals("https://worker.example/aptos/v1", runtime.aptosFullnodeUrl)
    assertEquals(
      "http://10.0.2.2:8787/aptos/v1",
      FlareRuntimeConfig(workerBaseUrl = "http://10.0.2.2:8787").aptosFullnodeUrl,
    )
    assertFailsWith<IllegalArgumentException> {
      FlareRuntimeConfig(workerBaseUrl = "http://worker.example")
    }
    assertFailsWith<IllegalArgumentException> {
      FlareRuntimeConfig(workerBaseUrl = "http://10.0.2.2.example:8787")
    }
  }

  @Test
  fun yearToDateStartsAtUtcYearBoundaryAndBoundsOrdinaryRequest() {
    val earlyYear = Instant.parse("2026-02-01T00:00:00Z").toEpochMilliseconds()
    val lateYear = Instant.parse("2026-09-01T00:00:00Z").toEpochMilliseconds()
    val expectedStart = Instant.parse("2026-01-01T00:00:00Z").toEpochMilliseconds()

    assertEquals(
      expectedStart,
      chartRequestSpec(ChartRange.YEAR_TO_DATE, earlyYear).startTimeMs,
    )
    assertEquals(
      CandleInterval.FOUR_HOURS,
      chartRequestSpec(ChartRange.YEAR_TO_DATE, earlyYear).interval,
    )
    assertEquals(
      CandleInterval.ONE_DAY,
      chartRequestSpec(ChartRange.YEAR_TO_DATE, lateYear).interval,
    )
  }

  @Test
  fun longCandleHistoryIsSplitAtOneThousandIntervals() {
    val dayMs = 24L * 60 * 60 * 1_000
    val windows = candleRequestWindows(0L, 2_001L * dayMs, CandleInterval.ONE_DAY)

    assertEquals(3, windows.size)
    assertEquals(0L to 999L * dayMs, windows[0])
    assertEquals(1_998L * dayMs to 2_001L * dayMs, windows[2])
    assertEquals(true, windows.all { (start, end) -> (end - start) / dayMs < 1_000L })
  }

  @Test
  fun cachedMarketQuoteRoundTripsWithoutPersistingFavoriteState() {
    val quote =
      MarketQuote(
        market =
          Market(
            assetType = AssetType.PERP,
            address = "0xdec1be1",
            name = "BTC-USD",
            sizeDecimals = 4,
            maxLeverage = 20,
            tickSize = 10u,
            minSize = 100u,
            lotSize = 10u,
            maxOpenInterest = 1_000_000.0,
            priceDecimals = 2,
            mode = "active",
            unrealizedPnlHaircutBps = 100,
            category = "crypto",
            minPrice = 100u,
            maxPrice = 100_000_000u,
            isIsolatedOnly = false,
          ),
        markPrice = 62_500.25,
        changePercent24h = -1.25,
        volume24h = 12_000_000.0,
        openInterest = 4_000_000.0,
        favorite = false,
      )

    val encoded = DecibelClient.DefaultJson.encodeToString(quote)
    val restored = DecibelClient.DefaultJson.decodeFromString<MarketQuote>(encoded)

    assertEquals(quote, restored)
  }
}
