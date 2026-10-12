package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.flare.data.candlePage
import xyz.mcxross.flare.venue.model.ChartTimeframe

class DefaultChartRepositoryTest {
  @Test
  fun aPageOfHistoryEndsWhereItIsAskedTo() {
    val end = 1_790_518_620_000L
    val (start, pageEnd) = candlePage(end, ChartTimeframe.FIFTEEN_MINUTES)

    assertEquals(end, pageEnd)
    assertEquals(500L, (pageEnd - start) / ChartTimeframe.FIFTEEN_MINUTES.durationMs)
    assertEquals(1, candleRequestWindows(start, pageEnd, ChartTimeframe.FIFTEEN_MINUTES).size)
  }

  @Test
  fun longCandleHistoryIsSplitAtOneThousandIntervals() {
    val dayMs = 24L * 60 * 60 * 1_000
    val windows = candleRequestWindows(0L, 2_001L * dayMs, ChartTimeframe.ONE_DAY)

    assertEquals(3, windows.size)
    assertEquals(0L to 999L * dayMs, windows[0])
    assertEquals(1_998L * dayMs to 2_001L * dayMs, windows[2])
    assertEquals(true, windows.all { (start, end) -> (end - start) / dayMs < 1_000L })
  }
}
