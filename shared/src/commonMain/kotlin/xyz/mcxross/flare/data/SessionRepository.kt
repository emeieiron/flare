package xyz.mcxross.flare.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.kaptos.account.Ed25519Account
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.HexInput

/** Renew a session that has less than this left, so an action never starts on an expiring token. */
internal const val SESSION_RENEWAL_MARGIN_MS = 60_000L

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

  /** Authenticates a trading key that is not stored yet, to verify it before adopting it. */
  suspend fun verifyApiCredential(account: Ed25519Account, subaccount: String): SessionStatus

  /** Reuses the current trading session while it remains valid, renewing it silently otherwise. */
  suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus

  suspend fun useAnonymous(): SessionStatus

  suspend fun invalidate()
}

/**
 * Worker bearer tokens deliberately remain memory-only. Authentication challenges are signed with
 * Kaptos account primitives after the platform vault performs user-presence authorization.
 */
class WorkerSessionRepository(
  private val client: HttpClient,
  private val runtime: FlareRuntimeConfig,
  private val preferences: AppPreferences,
  private val wallets: WalletRepository,
) : SessionRepository {
  private val mutex = Mutex()
  private var session: WorkerSession? = null
  private val authenticatedSessions =
    MutableStateFlow<Map<SessionStatus, WorkerSession>>(emptyMap())
  private val mutableStatus = MutableStateFlow<SessionStatus?>(null)
  override val status: StateFlow<SessionStatus?> = mutableStatus.asStateFlow()

  override fun <T> bind(status: SessionStatus, operation: Flow<T>): Flow<T> {
    val bound = authenticatedSessions.value[status] ?: error("Session renewal is required")
    return operation.flowOn(SessionBearer(bound.token, bound.expiresAt))
  }

  override suspend fun accessToken(): String {
    currentCoroutineContext()[SessionBearer]?.let {
      check(it.expiresAt > Clock.System.now().toEpochMilliseconds()) {
        "Session expired; try again"
      }
      return it.token
    }
    return mutex.withLock {
      val now = Clock.System.now().toEpochMilliseconds()
      session
        ?.takeIf {
          if (it.role.toSessionRole() == SessionRole.ANONYMOUS) {
            it.expiresAt - SESSION_RENEWAL_MARGIN_MS > now
          } else {
            it.expiresAt > now
          }
        }
        ?.token
        ?.let {
          return it
        }
      val previous = session
      if (previous != null && previous.role.toSessionRole() != SessionRole.ANONYMOUS) {
        val saved = preferences.values.first()
        val profile = wallets.profile.first()
        val address =
          if (previous.role.toSessionRole() == SessionRole.API) profile.apiWalletAddress
          else profile.ownerAddress
        if (address != null && previous.walletAddress?.sameAptosAddress(address) == true) {
          try {
            wallets.requireAuthorization(wallets.authorizationGeneration)
            val prompt = VaultPrompt("Open Flare", "Confirm your identity")
            if (
              previous.role.toSessionRole() == SessionRole.API && saved.selectedSubaccount != null
            ) {
              wallets.withApiAccount(prompt) {
                authenticate(it, saved.selectedSubaccount, SessionRole.API)
              }
            } else {
              wallets.withOwnerAccount(prompt) {
                authenticate(it, previous.subaccount, SessionRole.OWNER)
              }
            }
            return@withLock session!!.token
          } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            /* A locked visit may continue using anonymous market data. */
          }
        }
      }
      createAnonymousSession(now).token
    }
  }

  override suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus =
    mutex.withLock {
      wallets.withOwnerAccount(prompt) { account ->
        authenticate(account, subaccount, SessionRole.OWNER)
      }
    }

  override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus =
    mutex.withLock {
      require(subaccount.isNotBlank()) { "A trading session must be bound to a trading account" }
      wallets.withApiAccount(prompt) { account ->
        authenticate(account, subaccount, SessionRole.API)
      }
    }

  override suspend fun verifyApiCredential(
    account: Ed25519Account,
    subaccount: String,
  ): SessionStatus = mutex.withLock { authenticate(account, subaccount, SessionRole.API) }

  override suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus {
    val address = wallets.profile.first().apiWalletAddress ?: error("Complete trading setup")
    val current = status.value
    if (
      current?.role == SessionRole.API &&
        current.walletAddress?.sameAptosAddress(address) == true &&
        current.subaccount?.sameAptosAddress(subaccount) == true &&
        current.expiresAt > Clock.System.now().toEpochMilliseconds() + SESSION_RENEWAL_MARGIN_MS
    )
      return current
    return authenticateApi(subaccount, prompt)
  }

  override suspend fun useAnonymous(): SessionStatus = mutex.withLock {
    createAnonymousSession(Clock.System.now().toEpochMilliseconds()).toStatus()
  }

  override suspend fun invalidate() {
    mutex.withLock {
      try {
        session?.let { active ->
          client.post("${runtime.workerBaseUrl.trimEnd('/')}/v1/session/revoke") {
            bearerAuth(active.token)
            header(io.ktor.http.HttpHeaders.Origin, runtime.appOrigin)
          }
        }
      } finally {
        session = null
        authenticatedSessions.value = emptyMap()
        mutableStatus.value = null
      }
    }
  }

  private suspend fun authenticate(
    account: Ed25519Account,
    subaccount: String?,
    expectedRole: SessionRole,
  ): SessionStatus {
    val generation = wallets.authorizationGeneration
    val address = account.accountAddress.toString()
    val challenge =
      client
        .post("${runtime.workerBaseUrl.trimEnd('/')}/v1/auth/challenge") {
          contentType(ContentType.Application.Json)
          setBody(
            ChallengeRequest(
              network = runtime.network.name.lowercase(),
              origin = runtime.appOrigin,
              walletAddress = address,
              subaccount = subaccount,
            )
          )
        }
        .body<ChallengeResponse>()
    val now = Clock.System.now().toEpochMilliseconds()
    require(challenge.challenge.startsWith("FLARE_AUTH_V1\n") && challenge.expiresAt > now) {
      "The Flare Worker returned an invalid authentication challenge"
    }

    val challengeBytes = challenge.challenge.encodeToByteArray()
    val publicKey = account.publicKey.toByteArray()
    val signature =
      try {
        wallets.requireAuthorization(generation)
        account.sign(HexInput.fromByteArray(challengeBytes)).toByteArray()
      } finally {
        challengeBytes.fill(0)
      }
    val response =
      try {
        client
          .post("${runtime.workerBaseUrl.trimEnd('/')}/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(
              AuthenticatedSessionRequest(
                challenge = challenge.challenge,
                publicKey = publicKey.toLowerHex(),
                signature = signature.toLowerHex(),
              )
            )
          }
          .body<WorkerSession>()
      } finally {
        publicKey.fill(0)
        signature.fill(0)
      }
    val actualRole = response.role.toSessionRole()
    require(response.token.isNotBlank() && response.expiresAt > now) {
      "The Flare Worker returned an invalid authenticated session"
    }
    require(actualRole == expectedRole) {
      "The wallet is not authorized for the requested session role"
    }
    require(response.walletAddress?.sameAptosAddress(address) == true) {
      "The Flare Worker session is bound to a different wallet"
    }
    require(
      when (subaccount) {
        null -> response.subaccount == null
        else -> response.subaccount?.sameAptosAddress(subaccount) == true
      }
    ) {
      "The Flare Worker session is bound to a different subaccount"
    }
    session = response
    val status = response.toStatus()
    authenticatedSessions.value =
      authenticatedSessions.value.entries.toList().takeLast(31).associate { it.toPair() } +
        (status to response)
    mutableStatus.value = status
    return status
  }

  private suspend fun createAnonymousSession(now: Long): WorkerSession {
    val refreshed =
      client
        .post("${runtime.workerBaseUrl.trimEnd('/')}/v1/session/anonymous") {
          contentType(ContentType.Application.Json)
          setBody(AnonymousSessionRequest(preferences.installationId()))
        }
        .body<WorkerSession>()
    require(
      refreshed.token.isNotBlank() &&
        refreshed.expiresAt > now &&
        refreshed.role.toSessionRole() == SessionRole.ANONYMOUS
    ) {
      "The Flare Worker returned an invalid anonymous session"
    }
    session = refreshed
    mutableStatus.value = refreshed.toStatus()
    return refreshed
  }

  private fun WorkerSession.toStatus(): SessionStatus =
    SessionStatus(
      role = role.toSessionRole(),
      walletAddress = walletAddress,
      subaccount = subaccount,
      expiresAt = expiresAt,
    )
}

@Serializable private data class AnonymousSessionRequest(val installationId: String)

@Serializable
private data class ChallengeRequest(
  val network: String,
  val origin: String,
  val walletAddress: String,
  val subaccount: String? = null,
)

@Serializable private data class ChallengeResponse(val challenge: String, val expiresAt: Long)

@Serializable
private data class AuthenticatedSessionRequest(
  val challenge: String,
  val publicKey: String,
  val signature: String,
)

@Serializable
private data class WorkerSession(
  val token: String,
  val expiresAt: Long,
  val role: String,
  val walletAddress: String? = null,
  val subaccount: String? = null,
)

private fun String.toSessionRole(): SessionRole =
  when (this) {
    "anonymous" -> SessionRole.ANONYMOUS
    "owner" -> SessionRole.OWNER
    "api" -> SessionRole.API
    else -> error("The Flare Worker returned an unknown session role")
  }

private fun ByteArray.toLowerHex(): String =
  joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

internal fun String.sameAptosAddress(other: String): Boolean =
  AccountAddress.fromString(this) == AccountAddress.fromString(other)

private class SessionBearer(val token: String, val expiresAt: Long) :
  AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<SessionBearer>
}
