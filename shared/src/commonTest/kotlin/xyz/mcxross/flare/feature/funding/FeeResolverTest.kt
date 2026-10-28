package xyz.mcxross.flare.feature.funding

import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import xyz.mcxross.flare.venue.model.NetworkFee

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeeResolverTest {
  @Test fun typingCancelsImmediatelyAndCoalescesTheNextRead() = runTest {
    var cancelled = false
    val reads = mutableListOf<Int>()
    val resolver = FeeResolver<Int>(backgroundScope, { key ->
      reads += key
      if (key == 1) try { awaitCancellation() } finally { cancelled = true }
      NetworkFee(key.toULong(), 500uL)
    }, { _, _, _ -> }, now = { testScheduler.currentTime })
    resolver.request(1); advanceTimeBy(176); runCurrent()
    resolver.request(2); runCurrent()
    assertTrue(cancelled)
    advanceTimeBy(100); resolver.request(3)
    advanceTimeBy(174); runCurrent(); assertEquals(listOf(1), reads)
    advanceTimeBy(2); runCurrent(); assertEquals(listOf(1, 3), reads)
  }

  @Test fun exactCacheIsImmediateBoundedAndExpires() = runTest {
    val reads = mutableListOf<Int>()
    val results = mutableListOf<NetworkFee?>()
    val resolver = FeeResolver<Int>(backgroundScope, { reads += it; NetworkFee(it.toULong(), 500uL) },
      { _, fee, _ -> results += fee }, now = { testScheduler.currentTime })
    for (i in 1..9) { resolver.request(i, immediate = true); runCurrent() }
    resolver.request(2); assertEquals(NetworkFee(2uL, 500uL), results.last())
    assertEquals(9, reads.size)
    resolver.request(1, immediate = true); runCurrent(); assertEquals(10, reads.size)
    advanceTimeBy(30_001)
    resolver.request(1); assertNull(results.last())
    advanceTimeBy(176); runCurrent(); assertEquals(11, reads.size)
  }

  @Test fun transientFailuresRetryWithoutWaitingForPortfolioRefresh() = runTest {
    var attempts = 0
    var result: NetworkFee? = null
    val resolver = FeeResolver<Int>(backgroundScope, { if (++attempts < 3) null else NetworkFee(12uL, 500uL) },
      { _, fee, _ -> result = fee })
    resolver.request(1, immediate = true); runCurrent()
    advanceTimeBy(1_001); runCurrent()
    assertEquals(3, attempts); assertEquals(NetworkFee(12uL, 500uL), result)
  }

  @Test fun lateResultsCannotPublishOrPopulateTheCacheAfterCancellation() = runTest {
    val gate = CompletableDeferred<Unit>()
    val published = mutableListOf<Pair<Int, NetworkFee?>>()
    var calls = 0
    val resolver = FeeResolver<Int>(backgroundScope, {
      calls++
      if (it == 1 && calls == 1) withContext(NonCancellable) { gate.await() }
      NetworkFee(it.toULong(), 500uL)
    }, { key, fee, _ -> published += key to fee })
    resolver.request(1, immediate = true); runCurrent()
    resolver.request(2, immediate = true); runCurrent()
    gate.complete(Unit); runCurrent()
    assertEquals(2 to NetworkFee(2uL, 500uL), published.last())
    resolver.request(1, immediate = true); runCurrent(); assertEquals(3, calls)
  }

  @Test fun persistentFailuresEndWithAConstraintAndAVisitResetDropsCachedFees() = runTest {
    var fail = false
    var error: String? = null
    var calls = 0
    val resolver = FeeResolver<Int>(backgroundScope, { calls++; if (fail) null else NetworkFee(12uL, 500uL) },
      { _, _, message -> error = message })
    resolver.request(1, immediate = true); runCurrent()
    resolver.cancel(clearCache = true); fail = true
    resolver.request(1, immediate = true); runCurrent()
    advanceTimeBy(1_001); runCurrent()
    assertEquals(4, calls); assertNotNull(error)
  }
  @Test fun aVisibleFormRecoversAfterTheInitialRetryBudgetAndStopsWhenHidden() = runTest {
    var attempts = 0
    var value: NetworkFee? = null
    var error: String? = null
    val resolver = FeeResolver<Int>(backgroundScope, {
      attempts++
      if (attempts <= 3) error("server unavailable") else NetworkFee(12uL, 500uL)
    }, { _, fee, message -> value = fee; error = message })
    resolver.setVisible(true)
    resolver.request(1, immediate = true); runCurrent()
    advanceTimeBy(1_001); runCurrent()
    assertEquals("Fees unavailable", error)
    advanceTimeBy(2_001); runCurrent()
    assertEquals(NetworkFee(12uL, 500uL), value)
    assertNull(error)
    resolver.request(2, immediate = true); runCurrent()
    resolver.setVisible(false)
    val prior = attempts
    advanceTimeBy(31_000); runCurrent()
    assertEquals(prior, attempts)
  }

}
