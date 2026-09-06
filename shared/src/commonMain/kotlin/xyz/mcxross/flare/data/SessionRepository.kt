package xyz.mcxross.flare.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.kaptos.account.Ed25519Account
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.HexInput

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

  suspend fun accessToken(): String

  suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus

  suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus

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
  private val mutableStatus = MutableStateFlow<SessionStatus?>(null)
  override val status: StateFlow<SessionStatus?> = mutableStatus.asStateFlow()

  override suspend fun accessToken(): String = mutex.withLock {
    val now = Clock.System.now().toEpochMilliseconds()
    session
      ?.takeIf {
        if (it.role.toSessionRole() == SessionRole.ANONYMOUS) {
          it.expiresAt - REFRESH_WINDOW_MS > now
        } else {
          it.expiresAt > now
        }
      }
      ?.token
      ?.let {
        return it
      }
    createAnonymousSession(now).token
  }

  override suspend fun authenticateOwner(
    subaccount: String?,
    prompt: VaultPrompt,
  ): SessionStatus = mutex.withLock {
    wallets.withOwnerAccount(prompt) { account ->
      authenticate(account, subaccount, SessionRole.OWNER)
    }
  }

  override suspend fun authenticateApi(
    subaccount: String,
    prompt: VaultPrompt,
  ): SessionStatus = mutex.withLock {
    require(subaccount.isNotBlank()) { "An API wallet session must be bound to a subaccount" }
    wallets.withApiAccount(prompt) { account ->
      authenticate(account, subaccount, SessionRole.API)
    }
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
        mutableStatus.value = null
      }
    }
  }

  private suspend fun authenticate(
    account: Ed25519Account,
    subaccount: String?,
    expectedRole: SessionRole,
  ): SessionStatus {
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
    return session!!.toStatus().also { mutableStatus.value = it }
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

  private companion object {
    const val REFRESH_WINDOW_MS = 60_000L
  }
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

private fun String.sameAptosAddress(other: String): Boolean =
  AccountAddress.fromString(this) == AccountAddress.fromString(other)
