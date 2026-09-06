package xyz.mcxross.flare.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.decibel.api.TransactionState

/** Repository-owned setup: retries only indexed reads, never key generation or transactions. */
internal class TradingWalletSetup {
  private val mutex = Mutex()

  suspend fun prepare(actions: TradingWalletSetupActions) = mutex.withLock {
    actions.authorizeOwner()
    val address = actions.existingWallet() ?: actions.createWallet()
    if (!actions.isDelegated(address)) {
      actions.requireNoPendingTransactions()
      val result = actions.delegate()
      check(result is TransactionState.Committed) {
        (result as? TransactionState.Failed)?.let {
          listOfNotNull(it.message, it.hash?.let { hash -> "Transaction: $hash" })
            .joinToString(". ")
        } ?: "Delegation did not reach a confirmed state"
      }
    }
    var lastError: Exception? = null
    repeat(5) { attempt ->
      try {
        actions.connectApi()
        return@withLock
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        lastError = error
        if (attempt < 4) delay((1L shl attempt.coerceAtMost(2)) * 1_000L)
      }
    }
    throw IllegalStateException(
      "Delegation is configured, but verification is not available yet. Retry setup to reconnect the same API wallet.",
      lastError,
    )
  }
}

internal interface TradingWalletSetupActions {
  suspend fun authorizeOwner()

  suspend fun existingWallet(): String?

  suspend fun createWallet(): String

  suspend fun isDelegated(address: String): Boolean

  suspend fun requireNoPendingTransactions()

  suspend fun delegate(): TransactionState

  suspend fun connectApi()
}
