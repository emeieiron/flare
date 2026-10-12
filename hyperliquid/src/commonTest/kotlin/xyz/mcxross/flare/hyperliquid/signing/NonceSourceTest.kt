package xyz.mcxross.flare.hyperliquid.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NonceSourceTest {
  @Test
  fun followsTheClock() {
    var now = 1_000L
    val nonces = ClockNonceSource { now }

    assertEquals(1_000L, nonces.next())
    now = 1_500L
    assertEquals(1_500L, nonces.next())
  }

  @Test
  fun staysStrictlyIncreasingWithinOneMillisecond() {
    val nonces = ClockNonceSource { 1_000L }

    assertEquals(listOf(1_000L, 1_001L, 1_002L), List(3) { nonces.next() })
  }

  @Test
  fun staysStrictlyIncreasingWhenTheClockGoesBack() {
    var now = 5_000L
    val nonces = ClockNonceSource { now }

    assertEquals(5_000L, nonces.next())
    now = 4_000L
    assertEquals(5_001L, nonces.next())
    assertEquals(5_002L, nonces.next())
    now = 6_000L
    assertEquals(6_000L, nonces.next())
  }

  @Test
  fun defaultsToTheSystemClock() {
    val first = ClockNonceSource().next()
    // Any millisecond timestamp after 2024-01-01.
    assertTrue(first > 1_704_067_200_000L)
  }
}
