package xyz.mcxross.flare.venue.decibel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.kaptos.core.crypto.Ed25519PublicKey
import xyz.mcxross.kaptos.core.crypto.Ed25519Signature
import xyz.mcxross.kaptos.model.HexInput

class WorkerSessionRepositoryIosTest {
  @Test
  fun ownerChallengeIsSignedWithKaptosAndTokenRemainsInMemory() = runTest {
    val preferences = testPreferences()
    val wallets = DefaultWalletRepository(ForegroundWalletVault(IosTestMemoryVault()), preferences)
    val prompt = VaultPrompt("Test", "Authorize")
    val phrase =
      "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    val address = wallets.importOwner(phrase, prompt).canonicalAddress()
    val now = Clock.System.now().toEpochMilliseconds()
    val expiresAt = now + 5 * 60_000
    val challenge =
      listOf(
          "FLARE_AUTH_V1",
          "network=mainnet",
          "origin=flare://mobile",
          "wallet_address=$address",
          "subaccount=",
          "nonce=${"ab".repeat(32)}",
          "issued_at=$now",
          "expires_at=$expiresAt",
        )
        .joinToString("\n")
    var anonymousSessions = 0
    var challenges = 0
    var authentications = 0
    var tokenExpiry = now + 55 * 60_000
    var signatureVerified = false
    var sessionRevoked = false
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/v1/auth/challenge" -> {
          challenges++
          respondJson("""{"challenge":${Json.encodeToString(challenge)},"expiresAt":$expiresAt}""")
        }
        "/v1/auth/session" -> {
          authentications++
          val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
          val publicKey = Ed25519PublicKey(body.getValue("publicKey").jsonPrimitive.content)
          val signature =
            Ed25519Signature(HexInput.fromString(body.getValue("signature").jsonPrimitive.content))
          signatureVerified =
            publicKey.verifySignature(
              HexInput.fromByteArray(challenge.encodeToByteArray()),
              signature,
            )
          respondJson(
            """{"token":"owner-token","expiresAt":$tokenExpiry,"role":"owner","walletAddress":"$address"}""",
          )
        }
        "/v1/session/anonymous" -> {
          anonymousSessions++
          respondJson(
            """{"token":"anonymous-token","expiresAt":${now + 55 * 60_000},"role":"anonymous"}""",
          )
        }
        "/v1/session/revoke" -> {
          assertEquals("Bearer owner-token", request.headers[HttpHeaders.Authorization])
          sessionRevoked = true
          respond("", status = io.ktor.http.HttpStatusCode.NoContent)
        }
        else -> error("Unexpected path ${request.url.encodedPath}")
      }
    }
    val client = HttpClient(engine) { install(ContentNegotiation) { json(Json) } }
    val repository =
      WorkerSessionRepository(
        client = client,
        runtime = FlareRuntimeConfig(Network.MAINNET),
        decibelConfig = DecibelRuntimeConfig("http://127.0.0.1:8787"),
        preferences = preferences,
        wallets = wallets,
      )

    val status = repository.authenticateOwner(null, prompt)

    assertTrue(signatureVerified)
    assertEquals(SessionRole.OWNER, status.role)
    assertEquals(address, status.walletAddress?.canonicalAddress())
    assertEquals("owner-token", repository.accessToken())
    assertEquals(SessionRole.OWNER, repository.status.value?.role)
    val reused = List(3) { async { repository.ensureOwner(null, prompt) } }.awaitAll()
    assertTrue(reused.all { it == status })
    assertEquals(1, challenges)
    assertEquals(1, authentications)
    repository.useAnonymous()
    assertEquals(status, repository.ensureOwner(null, prompt))
    assertEquals(1, authentications)
    repository.ensureOwner(null, prompt.copy(requireFreshAuthorization = true))
    assertEquals(2, authentications)
    repository.invalidate()
    tokenExpiry = Clock.System.now().toEpochMilliseconds() + 30_000
    repository.authenticateOwner(null, prompt)
    repository.ensureOwner(null, prompt)
    assertEquals(4, authentications)
    tokenExpiry = Clock.System.now().toEpochMilliseconds() + 55 * 60_000
    val renewed = repository.authenticateOwner(null, prompt)

    val pinnedToken =
      repository
        .bind(
          renewed,
          flow {
            repository.useAnonymous()
            emit(repository.accessToken())
          },
        )
        .single()
    assertEquals("owner-token", pinnedToken)
    assertEquals(SessionRole.ANONYMOUS, repository.status.value?.role)
    repository.authenticateOwner(null, prompt)

    // Binding takes a snapshot of the existing anonymous bearer. A subsequent wallet-session
    // replacement must not renew or change the token used by the read-only operation.
    val anonymous = repository.useAnonymous()
    assertFailsWith<IllegalStateException> {
      repository.bind(anonymous.copy(expiresAt = anonymous.expiresAt + 1), flow { emit(Unit) })
    }
    val anonymousOperation = repository.bind(anonymous, flow { emit(repository.accessToken()) })
    repository.authenticateOwner(null, prompt)
    val beforeChallenges = challenges
    val beforeAuthentications = authentications
    val beforeAnonymousSessions = anonymousSessions
    assertEquals("anonymous-token", anonymousOperation.single())
    assertEquals(SessionRole.OWNER, repository.status.value?.role)
    assertEquals(beforeChallenges, challenges)
    assertEquals(beforeAuthentications, authentications)
    assertEquals(beforeAnonymousSessions, anonymousSessions)

    repository.invalidate()

    assertTrue(sessionRevoked)
    assertNull(repository.status.value)
  }

  @Test
  fun ownerAndTradingSessionsAreHeldSideBySide() = runTest {
    val preferences = testPreferences()
    val wallets = DefaultWalletRepository(ForegroundWalletVault(IosTestMemoryVault()), preferences)
    val prompt = VaultPrompt("Test", "Authorize")
    val owner =
      wallets
        .importOwner(
          "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon about",
          prompt,
        )
        .canonicalAddress()
    wallets.createApiWallet(prompt)
    val subaccount = "0x" + "22".repeat(32)
    var wallet = ""
    var authentications = 0
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/v1/auth/challenge" -> {
          val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
          wallet = body.getValue("walletAddress").jsonPrimitive.content.canonicalAddress()
          val expiresAt = Clock.System.now().toEpochMilliseconds() + 5 * 60_000
          respondJson(
            """{"challenge":"FLARE_AUTH_V1\nwallet_address=$wallet","expiresAt":$expiresAt}""",
          )
        }
        "/v1/auth/session" -> {
          authentications++
          val role = if (wallet == owner) "owner" else "api"
          val expiresAt = Clock.System.now().toEpochMilliseconds() + 55 * 60_000
          respondJson(
            """{"token":"$role-token","expiresAt":$expiresAt,"role":"$role",""" +
              """"walletAddress":"$wallet","subaccount":"$subaccount"}""",
          )
        }
        else -> error("Unexpected path ${request.url.encodedPath}")
      }
    }
    val repository =
      WorkerSessionRepository(
        client = HttpClient(engine) { install(ContentNegotiation) { json(Json) } },
        runtime = FlareRuntimeConfig(Network.MAINNET),
        decibelConfig = DecibelRuntimeConfig("http://127.0.0.1:8787"),
        preferences = preferences,
        wallets = wallets,
      )

    val ownerSession = repository.ensureOwner(subaccount, prompt)
    val tradingSession = repository.authenticateApi(subaccount, prompt)
    assertEquals(2, authentications)

    // Moving between the owner and the trading key reuses what this visit already signed.
    assertEquals(ownerSession, repository.ensureOwner(subaccount, prompt))
    assertEquals("owner-token", repository.accessToken())
    assertEquals(tradingSession, repository.ensureTrading(subaccount, prompt))
    assertEquals("api-token", repository.accessToken())
    assertEquals(2, authentications)

    repository.ensureOwner(subaccount, prompt.copy(requireFreshAuthorization = true))
    assertEquals(3, authentications)
  }

  @Test
  fun aTradingSessionRequestedMidSignInReusesIt() = runTest {
    val preferences = testPreferences()
    val wallets = DefaultWalletRepository(ForegroundWalletVault(IosTestMemoryVault()), preferences)
    val prompt = VaultPrompt("Test", "Authorize")
    val trading = wallets.createApiWallet(prompt).canonicalAddress()
    val subaccount = "0x" + "22".repeat(32)
    val signing = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var authentications = 0
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/v1/auth/challenge" -> {
          val expiresAt = Clock.System.now().toEpochMilliseconds() + 5 * 60_000
          respondJson("""{"challenge":"FLARE_AUTH_V1\ntest","expiresAt":$expiresAt}""")
        }
        "/v1/auth/session" -> {
          authentications++
          signing.complete(Unit)
          release.await()
          val expiresAt = Clock.System.now().toEpochMilliseconds() + 55 * 60_000
          respondJson(
            """{"token":"api-token","expiresAt":$expiresAt,"role":"api",""" +
              """"walletAddress":"$trading","subaccount":"$subaccount"}""",
          )
        }
        else -> error("Unexpected path ${request.url.encodedPath}")
      }
    }
    val repository =
      WorkerSessionRepository(
        client = HttpClient(engine) { install(ContentNegotiation) { json(Json) } },
        runtime = FlareRuntimeConfig(Network.MAINNET),
        decibelConfig = DecibelRuntimeConfig("http://127.0.0.1:8787"),
        preferences = preferences,
        wallets = wallets,
      )

    val verifying = async { repository.authenticateApi(subaccount, prompt) }
    signing.await()
    val reading = async { repository.ensureTrading(subaccount, prompt) }
    testScheduler.runCurrent()
    release.complete(Unit)

    assertEquals(verifying.await(), reading.await())
    assertEquals(1, authentications)
  }

  private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(value: String) =
    respond(
      content = value,
      headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}

private fun String.canonicalAddress(): String =
  "0x" + removePrefix("0x").lowercase().padStart(64, '0')
