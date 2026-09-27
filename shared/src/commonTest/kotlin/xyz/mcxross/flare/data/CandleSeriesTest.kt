package xyz.mcxross.flare.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import xyz.mcxross.flare.decibel.model.Candle

class CandleSeriesTest {
  private val minute = 60_000L

  @Test
  fun theFormingCandleIsUpdatedInPlace() {
    val series = listOf(candle(0), candle(1, close = 10.0))
    val updated = series.withLiveCandle(candle(1, close = 11.0))

    assertEquals(listOf(0L, minute), updated.map(Candle::openTimeMs))
    assertEquals(11.0, updated.last().close)
  }

  @Test
  fun aNewCandleFollowsTheLatest() {
    val updated = listOf(candle(0), candle(1)).withLiveCandle(candle(2))
    assertEquals(listOf(0L, minute, 2 * minute), updated.map(Candle::openTimeMs))
  }

  @Test
  fun aRepeatedOrUnknownOlderCandleChangesNothing() {
    val series = listOf(candle(0), candle(2), candle(3))
    assertSame(series, series.withLiveCandle(candle(3)))
    assertSame(series, series.withLiveCandle(candle(1)))
    assertEquals(9.0, series.withLiveCandle(candle(2, close = 9.0))[1].close)
  }

  @Test
  fun olderPagesJoinInFrontAndNewerDataWins() {
    val merged =
      mergeCandles(
        listOf(candle(0), candle(1, close = 1.0)),
        listOf(candle(1, close = 2.0), candle(2)),
      )
    assertEquals(listOf(0L, minute, 2 * minute), merged.map(Candle::openTimeMs))
    assertEquals(2.0, merged[1].close)
    assertEquals(3, mergeCandles(listOf(candle(0)), listOf(candle(1), candle(2))).size)
  }

  @Test
  fun catchingUpExtendsTheSeriesOrStartsOverAfterAGap() {
    val series = listOf(candle(0), candle(1), candle(2))
    val adjoining = series.caughtUpWith(listOf(candle(2, close = 5.0), candle(3)), minute)
    assertEquals(listOf(0L, minute, 2 * minute, 3 * minute), adjoining.map(Candle::openTimeMs))
    assertEquals(5.0, adjoining[2].close)

    val afterGap = series.caughtUpWith(listOf(candle(9), candle(10)), minute)
    assertEquals(listOf(9 * minute, 10 * minute), afterGap.map(Candle::openTimeMs))
  }

  private fun candle(slot: Long, close: Double = 1.0) =
    Candle(
      openTimeMs = slot * minute,
      closeTimeMs = (slot + 1) * minute - 1,
      open = 1.0,
      high = maxOf(1.0, close),
      low = minOf(1.0, close),
      close = close,
      volume = 0.0,
      interval = "1m",
    )
}
