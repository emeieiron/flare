package xyz.mcxross.flare.core

import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ReadOperation { FEES, ACTIVITY, WALLET_BALANCES, SWAP_BALANCE, SWAP_STATUS, SWAP_QUOTE, ACCOUNT_LOOKUP }

data class ReadRecoveryEvent(
  val operation: ReadOperation,
  val failures: Int,
  val attempt: Int,
  val failureType: String,
  val updatedAtMs: Long,
  val recovered: Boolean = false,
)

/** Bounded, redacted diagnostics: no request bodies, wallet identifiers, tokens or error messages. */
object ReadRecoveryDiagnostics {
  private val mutable = MutableStateFlow<List<ReadRecoveryEvent>>(emptyList())
  val events = mutable.asStateFlow()

  fun failed(operation: ReadOperation, attempt: Int, error: Throwable? = null, timeout: Boolean = false) {
    val type = if (timeout) "Timeout" else error?.let { it::class.simpleName } ?: "Unavailable"
    mutable.update { events ->
      val previous = events.lastOrNull()
      val next = ReadRecoveryEvent(operation,
        if (previous?.operation == operation && !previous.recovered) previous.failures + 1 else 1,
        attempt, type, Clock.System.now().toEpochMilliseconds())
      (if (previous?.operation == operation && !previous.recovered) events.dropLast(1) + next else events + next).takeLast(64)
    }
  }

  fun recovered(operation: ReadOperation) {
    mutable.update { events ->
      val index = events.indexOfLast { it.operation == operation && !it.recovered }
      if (index < 0) events else events.mapIndexed { i, event ->
        if (i == index) event.copy(recovered = true, updatedAtMs = Clock.System.now().toEpochMilliseconds()) else event
      }
    }
  }
}

internal fun readBackoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

internal fun Throwable.isReadAccessFailure(): Boolean =
  this is xyz.mcxross.flare.security.WalletVaultException || message == "Open Flare to continue" ||
    message?.contains("not authorized", ignoreCase = true) == true ||
    message?.contains("authentication", ignoreCase = true) == true ||
    this is io.ktor.client.plugins.ResponseException && response.status.value in setOf(401, 403)

internal fun Throwable.readAccessMessage(): String =
  if (message == "Open Flare to continue") "Unlock your wallet" else "Account access unavailable"
