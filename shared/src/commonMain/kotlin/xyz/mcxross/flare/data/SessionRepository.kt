package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import xyz.mcxross.flare.security.VaultPrompt

enum class SessionRole {
  ANONYMOUS,
  OWNER,
  API,
}

data class SessionStatus(
  val role: SessionRole,
  val walletAddress: String? = null,
  val subaccount: String? = null,
  val expiresAt: Long,
)

/**
 * An authenticated session with the venue's backend (Decibel's is the Flare Worker). Venues that
 * talk to their exchange directly don't bind one, so common code takes it as optional.
 */
interface SessionRepository {
  val status: StateFlow<SessionStatus?>

  /**
   * Pins [status] to [operation] so a background refresh cannot swap the session under a
   * transaction that was already authorized with it.
   */
  fun <T> bind(status: SessionStatus, operation: Flow<T>): Flow<T>

  suspend fun accessToken(): String

  suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus

  suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus

  /** Reuses the current trading session while it remains valid, renewing it silently otherwise. */
  suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus

  suspend fun useAnonymous(): SessionStatus

  suspend fun invalidate()
}
