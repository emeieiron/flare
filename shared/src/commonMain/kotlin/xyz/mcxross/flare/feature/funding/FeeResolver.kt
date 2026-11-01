package xyz.mcxross.flare.feature.funding

import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mcxross.flare.core.ReadOperation
import xyz.mcxross.flare.core.ReadRecoveryDiagnostics
import xyz.mcxross.flare.core.isReadAccessFailure
import xyz.mcxross.flare.core.readAccessMessage
import xyz.mcxross.flare.core.readBackoffMs
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.venue.model.NetworkFee

/** Owns read cancellation, exact-input caching and retries for one funding form. */
internal class FeeResolver<K : Any>(
  private val scope: CoroutineScope,
  private val read: suspend (K) -> NetworkFee?,
  private val publish: (K, NetworkFee?, String?) -> Unit,
  private val now: () -> Long = run {
    val origin = TimeSource.Monotonic.markNow();
    { origin.elapsedNow().inWholeMilliseconds }
  },
) {
  private data class Cached(val fee: NetworkFee, val at: Long)

  private val cache = linkedMapOf<K, Cached>()
  private var key: K? = null
  private var revision = 0L
  private var job: Job? = null
  private var visible = false

  fun setVisible(value: Boolean) {
    visible = value
    if (!value) cancel(clearCache = true)
  }

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
      var attempt = 0
      while (currentCoroutineContext().isActive) {
        val result = withTimeoutOrNull(3_500) { runSuspendCatching { read(value) } }
        if (revision != version || key != value) return@launch
        val fee = result?.getOrNull()
        if (fee != null) {
          cache[value] = Cached(fee.copy(covered = false), now())
          while (cache.size > 8) cache.remove(cache.keys.first())
          ReadRecoveryDiagnostics.recovered(ReadOperation.FEES)
          publish(value, fee, null)
          return@launch
        }
        attempt++
        ReadRecoveryDiagnostics.failed(
          ReadOperation.FEES,
          attempt,
          result?.exceptionOrNull(),
          result == null,
        )
        val accessError = result?.exceptionOrNull()?.takeIf { it.isReadAccessFailure() }
        if (attempt >= 3 || accessError != null) {
          publish(value, null, accessError?.readAccessMessage() ?: "Fees unavailable")
          if (!visible) return@launch
        }
        delay(
          when (attempt) {
            1 -> 250
            2 -> 750
            else -> readBackoffMs(attempt - 1)
          },
        )
      }
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
