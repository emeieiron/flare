package xyz.mcxross.flare.venue.decibel

import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares overlapping reads without moving authentication out of the caller's coroutine context. */
internal class RefreshCoordinator<K>(private val mutex: Mutex = Mutex()) {
  private data class Completion<K>(val key: K, val at: TimeMark)

  private val completed = MutableStateFlow<Completion<K>?>(null)

  suspend fun run(
    key: K,
    maxAgeMs: Long = 0L,
    shareInFlight: Boolean = true,
    read: suspend () -> Boolean,
  ) {
    val observed = completed.value
    mutex.withLock {
      val latest = completed.value
      if (
        latest?.key == key &&
          ((shareInFlight && latest !== observed) ||
            latest.at.elapsedNow().inWholeMilliseconds < maxAgeMs)
      )
        return
      // Failed or cancelled reads never count as a usable refresh.
      if (read()) completed.value = Completion(key, TimeSource.Monotonic.markNow())
    }
  }
}
