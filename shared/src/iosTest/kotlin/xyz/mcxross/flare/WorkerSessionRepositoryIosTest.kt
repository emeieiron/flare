package xyz.mcxross.flare

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.DefaultWalletRepository
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.WorkerSessionRepository
import xyz.mcxross.flare.decibel.DecibelNetwork
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.kaptos.core.crypto.Ed25519PublicKey
import xyz.mcxross.kaptos.core.crypto.Ed25519Signature
import xyz.mcxross.kaptos.model.HexInput

class WorkerSessionRepositoryIosTest {
  @Test
  fun ownerChallengeIsSignedWithKaptosAndTokenRemainsInMemory() = runTest {
    val preferences = testPreferences()
    val wallets = DefaultWalletRepository(IosTestMemoryVault(), preferences)
    val prompt = VaultPrompt("Test", "Authorize")
    val phrase =
      "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    val address = wallets.importOwner(phrase, prompt).canonicalAddress()
    val now = Clock.System.now().toEpochMilliseconds()
    val expiresAt = now + 5 * 60_000
    val challenge =
      listOf(
          "FLARE_AUTH_V1",
          "network=testnet",
          "origin=flare://mobile",
          "wallet_address=$address",
          "subaccount=",
          "nonce=${"ab".repeat(32)}",
          "issued_at=$now",
          "expires_at=$expiresAt",
        )
        .joinToString("\n")
    var signatureVerified = false
    var sessionRevoked = false
    val engine = MockEngine { request ->
      when (request.url.encodedPath) {
        "/v1/auth/challenge" ->
          respondJson("""{"challenge":${Json.encodeToString(challenge)},"expiresAt":$expiresAt}""")
        "/v1/auth/session" -> {
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
            """{"token":"owner-token","expiresAt":${now + 55 * 60_000},"role":"owner","walletAddress":"$address"}"""
          )
        }
        "/v1/session/anonymous" ->
          respondJson(
            """{"token":"anonymous-token","expiresAt":${now + 55 * 60_000},"role":"anonymous"}"""
          )
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
        runtime = FlareRuntimeConfig(DecibelNetwork.TESTNET, "http://127.0.0.1:8787"),
        preferences = preferences,
        wallets = wallets,
      )

    val status = repository.authenticateOwner(null, prompt)

    assertTrue(signatureVerified)
    assertEquals(SessionRole.OWNER, status.role)
    assertEquals(address, status.walletAddress?.canonicalAddress())
    assertEquals("owner-token", repository.accessToken())
    assertEquals(SessionRole.OWNER, repository.status.value?.role)

    val pinnedToken =
      repository
        .bind(
          status,
          flow {
            repository.useAnonymous()
            emit(repository.accessToken())
          },
        )
        .single()
    assertEquals("owner-token", pinnedToken)
    assertEquals(SessionRole.ANONYMOUS, repository.status.value?.role)
    repository.authenticateOwner(null, prompt)

    repository.invalidate()

    assertTrue(sessionRevoked)
    assertNull(repository.status.value)
  }

  private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(value: String) =
    respond(
      content = value,
      headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}

private fun String.canonicalAddress(): String =
  "0x" + removePrefix("0x").lowercase().padStart(64, '0')
