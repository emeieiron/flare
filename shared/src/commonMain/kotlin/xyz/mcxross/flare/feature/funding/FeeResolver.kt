package xyz.mcxross.flare.feature.funding

import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.venue.model.NetworkFee

/** Owns read cancellation, exact-input caching and retries for one funding form. */
internal class FeeResolver<K : Any>(
  private val scope: CoroutineScope,
  private val read: suspend (K) -> NetworkFee?,
  private val publish: (K, NetworkFee?, String?) -> Unit,
  private val now: () -> Long = run { val origin = TimeSource.Monotonic.markNow(); { origin.elapsedNow().inWholeMilliseconds } },
) {
  private data class Cached(val fee: NetworkFee, val at: Long)
  private val cache = linkedMapOf<K, Cached>()
  private var key: K? = null
  private var revision = 0L
  private var job: Job? = null

  fun request(value: K, immediate: Boolean = false) {
    val cached = cache[value]?.takeIf { now() - it.at < 30_000 }
    if (key == value && job?.isActive == true && !immediate) return
    if (key == value && cached != null) return
    cancel()
    key = value
    if (cached != null) {
      publish(value, cached.fee, null)
      return
    }
    cache.remove(value)
    publish(value, null, null)
    val version = revision
    job = scope.launch {
      if (!immediate) delay(175)
      val fee = withTimeoutOrNull(12_000) {
        repeat(3) { attempt ->
          if (attempt > 0) delay(if (attempt == 1) 250 else 750)
          val result = runSuspendCatching { withTimeoutOrNull(3_500) { read(value) } }.getOrNull()
          if (result != null) return@withTimeoutOrNull result
        }
        null
      }
      if (revision != version || key != value) return@launch
      if (fee != null) {
        cache[value] = Cached(fee.copy(covered = false), now())
        while (cache.size > 8) cache.remove(cache.keys.first())
      }
      publish(value, fee, if (fee == null) "Fees unavailable. Check your connection." else null)
    }
  }

  fun cancel(clearCache: Boolean = false) {
    revision++
    job?.cancel()
    job = null
    key = null
    if (clearCache) cache.clear()
  }
}
