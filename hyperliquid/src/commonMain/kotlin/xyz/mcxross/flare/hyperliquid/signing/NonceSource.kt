package xyz.mcxross.flare.hyperliquid.signing

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

/**
 * Nonces for one signer. Hyperliquid keeps the 100 highest nonces per signer (the owner, or the
 * agent that signs for it) and rejects a nonce that was used or is not above the smallest of them;
 * nonces must also be within (T − 2 days, T + 1 day) of the block time. A millisecond timestamp
 * that never repeats satisfies both.
 */
fun interface NonceSource {
  /** A nonce strictly greater than every one this source returned before. */
  fun next(): Long
}

/**
 * Millisecond-clock nonces, strictly increasing even when called twice in one millisecond or when
 * the clock steps back: the next nonce is `max(clock(), previous + 1)`. Safe across threads.
 *
 * Use one instance per signer (sharing one between signers is also safe, just denser).
 */
@OptIn(ExperimentalAtomicApi::class)
class ClockNonceSource(
  private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) : NonceSource {
  private val last = AtomicLong(0L)

  override fun next(): Long {
    while (true) {
      val previous = last.load()
      val candidate = maxOf(clockMs(), previous + 1)
      if (last.compareAndSet(previous, candidate)) return candidate
    }
  }
}
