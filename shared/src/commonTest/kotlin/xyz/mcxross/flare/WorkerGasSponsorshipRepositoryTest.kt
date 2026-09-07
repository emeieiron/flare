package xyz.mcxross.flare

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.SessionStatus
import xyz.mcxross.flare.data.WorkerGasSponsorshipRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.kaptos.model.AptosResult

class WorkerGasSponsorshipRepositoryTest {
  @Test
  fun onlyExplicitCredentialErrorsMakeServiceUnavailableSafeForSelfPay() = runTest {
    val cases =
      listOf(
        """{"code":"gas_station_not_configured","error":"unavailable"}""" to
          "gas_station_not_configured",
        """{"code":"gas_station_credentials_rejected","error":"unavailable"}""" to
          "gas_station_credentials_rejected",
        """{"code":"unknown","error":"unavailable"}""" to "gas_station_http_503",
        """{"error":"Unable to journal sponsored transaction"}""" to "gas_station_http_503",
        "upstream unavailable" to "gas_station_http_503",
      )
    for ((body, expected) in cases) {
      val client =
        HttpClient(
          MockEngine { request ->
            assertEquals("/gas/sponsor/owner", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            respond(
              body,
              HttpStatusCode.ServiceUnavailable,
              headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
          }
        ) {
          install(ContentNegotiation) { json() }
        }
      try {
        val repository =
          WorkerGasSponsorshipRepository(client, FlareRuntimeConfig(), TestSessionRepository)
        val result =
          repository.submit(
            xyz.mcxross.kaptos.model.ExternalFeePayerRequest(
              byteArrayOf(1),
              byteArrayOf(2),
              fingerprint = "0x" + "11".repeat(32),
            ),
            ownerOnly = true,
          )
        val failure = assertIs<AptosResult.Failure>(result)
        assertEquals(
          expected,
          assertIs<xyz.mcxross.kaptos.model.AptosError.Api>(failure.error).errorCode,
        )
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun resolvesSubmittedFingerprintToOnChainHash() = runTest {
    val fingerprint = "0x" + "11".repeat(32)
    val transactionHash = "0x" + "22".repeat(32)
    val engine = MockEngine { request ->
      assertEquals("/gas/sponsor/status/$fingerprint", request.url.encodedPath)
      assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
      respond(
        content = """{"status":"submitted","transactionHash":"$transactionHash"}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
      )
    }
    val repository =
      WorkerGasSponsorshipRepository(
        client = HttpClient(engine) { install(ContentNegotiation) { json() } },
        runtime = FlareRuntimeConfig(),
        sessions = TestSessionRepository,
      )

    val result = repository.resolve(fingerprint)

    assertEquals(transactionHash, assertIs<AptosResult.Success<String?>>(result).value)
  }

  @Test
  fun pendingFingerprintRemainsUnresolvedWithoutResubmission() = runTest {
    val fingerprint = "0x" + "33".repeat(32)
    val engine = MockEngine {
      respond(
        content = """{"status":"pending"}""",
        status = HttpStatusCode.Accepted,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
      )
    }
    val repository =
      WorkerGasSponsorshipRepository(
        client = HttpClient(engine) { install(ContentNegotiation) { json() } },
        runtime = FlareRuntimeConfig(),
        sessions = TestSessionRepository,
      )

    val result = repository.resolve(fingerprint)

    assertEquals(null, assertIs<AptosResult.Success<String?>>(result).value)
  }
}

private object TestSessionRepository : SessionRepository {
  override val status: StateFlow<SessionStatus?> =
    MutableStateFlow(
      SessionStatus(
        role = SessionRole.ANONYMOUS,
        expiresAt = Long.MAX_VALUE,
      )
    )

  override suspend fun accessToken(): String = "test-token"

  override suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus =
    error("Not used")

  override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus =
    error("Not used")

  override suspend fun useAnonymous(): SessionStatus = status.value ?: error("Missing test session")

  override suspend fun invalidate() = Unit
}
