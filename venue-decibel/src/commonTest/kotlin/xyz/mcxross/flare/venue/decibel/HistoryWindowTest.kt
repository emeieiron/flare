package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryWindowTest {
  @Test fun backgroundRefreshPreservesLoadedPagesAndReplacesUpdatedRows() {
    val previous = (100 downTo 1).map { it to "old" }
    val incoming = (105 downTo 56).map { it to "new" }
    val merged = refreshedHistoryWindow(previous, incoming, false, true) { it.first }
    assertEquals((105 downTo 1).toList(), merged.items.map { it.first })
    assertEquals("new", merged.items.first { it.first == 100 }.second)
    assertFalse(merged.hasMore)
  }

  @Test fun disconnectedHistoryWindowsRestartPaginationWithoutCreatingAGap() {
    val merged = refreshedHistoryWindow((100 downTo 1).toList(), (200 downTo 151).toList(), false, true) { it }
    assertEquals((200 downTo 151).toList(), merged.items)
    assertTrue(merged.hasMore)
  }
}
