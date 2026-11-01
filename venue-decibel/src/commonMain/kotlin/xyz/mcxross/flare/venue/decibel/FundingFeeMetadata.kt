package xyz.mcxross.flare.venue.decibel

import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.ReplayProtection
import xyz.mcxross.kaptos.model.TransactionOptions

/** Read-only preparation data for funding; execution always obtains its own fresh metadata. */
class FundingFeeMetadata(
  private val aptos: Aptos,
  private val now: () -> Long = run {
    val origin = TimeSource.Monotonic.markNow();
    { origin.elapsedNow().inWholeMilliseconds }
  },
) {
  private val mutex = Mutex()
  private var cachedPrice: Pair<ULong, Long>? = null

  suspend fun price(owner: AccountAddress): ULong = mutex.withLock {
    cachedPrice?.takeIf { now() - it.second < 10_000 }?.first
      ?: run {
        // The SDK resolves the fee price while building this unsigned probe. Its explicit dummy
        // sequence avoids a redundant account read. The probe is never simulated, signed or
        // submitted.
        val built =
          aptos.coins.buildTransfer(
            owner,
            owner,
            0uL,
            options =
              TransactionOptions(
                maxGasAmount = 50_000uL,
                replayProtection = ReplayProtection.SequenceNumber(0uL),
              ),
          )
        val price =
          when (built) {
            is AptosResult.Success -> built.value.rawTransaction.gasUnitPrice
            is AptosResult.Failure -> error("Network fees couldn’t be checked.")
          }
        require(price in 1uL..1_000uL) { "Network fees are unusually high." }
        cachedPrice = price to now()
        price
      }
  }

  suspend fun options(owner: AccountAddress): TransactionOptions = coroutineScope {
    val price = async { price(owner) }
    val sequence = async {
      when (val result = aptos.accounts.get(owner)) {
        is AptosResult.Success -> result.value.sequenceNumber
        is AptosResult.Failure -> error("Your account couldn’t be refreshed.")
      }
    }
    TransactionOptions(
      gasUnitPrice = price.await(),
      replayProtection = ReplayProtection.SequenceNumber(sequence.await()),
    )
  }
}
