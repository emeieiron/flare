package xyz.mcxross.flare.decibel.api

import kotlin.test.Test
import kotlin.test.assertTrue

class StreamReconnectBackoffTest {
  @Test
  fun immediatelyClosedConnectionsDoNotResetBackoff() {
    val backoff = StreamReconnectBackoff()

    assertTrue(backoff.nextDelay(300L) in 500L until 750L)
    assertTrue(backoff.nextDelay(300L) in 1_000L until 1_500L)
    assertTrue(backoff.nextDelay(0L) in 2_000L until 3_000L)
    repeat(10) { backoff.nextDelay(300L) }
    assertTrue(backoff.nextDelay(300L) in 32_000L until 48_000L)
  }

  @Test
  fun stableConnectionRestoresFastRecovery() {
    val backoff = StreamReconnectBackoff()
    repeat(10) { backoff.nextDelay(300L) }

    assertTrue(backoff.nextDelay(30_000L) in 500L until 750L)
    assertTrue(backoff.nextDelay(300L) in 1_000L until 1_500L)
  }
}
