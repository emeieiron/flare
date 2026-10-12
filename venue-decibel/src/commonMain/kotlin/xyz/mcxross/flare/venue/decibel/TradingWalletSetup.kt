package xyz.mcxross.flare.venue.decibel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.data.SetupTransactionException
import xyz.mcxross.flare.venue.model.ActionState

/** Repository-owned setup: retries only indexed reads, never key generation or transactions. */
internal class TradingWalletSetup {
  private val mutex = Mutex()

  suspend fun prepare(actions: TradingWalletSetupActions) = mutex.withLock {
    val address = actions.existingWallet() ?: actions.createWallet()
    if (!actions.isDelegated(address)) {
      actions.requireNoPendingTransactions()
      val result = actions.delegate()
      if (result !is ActionState.Committed) throw SetupTransactionException(result)
    }
    var lastError: Exception? = null
    repeat(VERIFICATION_ATTEMPTS) { attempt ->
      try {
        actions.verifyTradingKey()
        return@withLock
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        lastError = error
        if (attempt < VERIFICATION_ATTEMPTS - 1) delay((1L shl attempt.coerceAtMost(2)) * 1_000L)
      }
    }
    throw IllegalStateException(
      "Trading is enabled on-chain, but it is not confirmed yet. Try again to reuse the same key.",
      lastError,
    )
  }

  private companion object {
    const val VERIFICATION_ATTEMPTS = 5
  }
}

internal interface TradingWalletSetupActions {
  suspend fun existingWallet(): String?

  suspend fun createWallet(): String

  suspend fun isDelegated(address: String): Boolean

  suspend fun requireNoPendingTransactions()

  suspend fun delegate(): ActionState

  suspend fun verifyTradingKey()
}
