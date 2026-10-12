package xyz.mcxross.flare.venue.decibel

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.SessionStatus
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.kaptos.model.AptosResult

class WorkerGasSponsorshipRepositoryTest {
  @Test
  fun onlyExplicitPreSubmissionCodesAreReportedAsDefinite() = runTest {
    val unavailable = HttpStatusCode.ServiceUnavailable
    val forbidden = HttpStatusCode.Forbidden
    val cases =
      listOf(
        Triple(unavailable, """{"code":"gas_station_not_configured","error":"unavailable"}""", "gas_station_not_configured"),
        Triple(unavailable, """{"code":"gas_station_credentials_rejected","error":"unavailable"}""", "gas_station_credentials_rejected"),
        Triple(forbidden, """{"code":"gas_station_transaction_not_allowed","error":"Entry function is outside the sponsorship route allowlist"}""", "gas_station_transaction_not_allowed"),
        Triple(unavailable, """{"code":"gas_station_transaction_not_allowed","error":"unavailable"}""", "gas_station_http_503"),
        Triple(forbidden, """{"code":"gas_station_not_configured","error":"forbidden"}""", "gas_station_http_403"),
        Triple(forbidden, """{"error":"Owner authorization required"}""", "gas_station_http_403"),
        Triple(unavailable, """{"code":"unknown","error":"unavailable"}""", "gas_station_http_503"),
        Triple(unavailable, """{"error":"Unable to journal sponsored transaction"}""", "gas_station_http_503"),
        Triple(unavailable, "upstream unavailable", "gas_station_http_503"),
      )
    for ((status, body, expected) in cases) {
      val client =
        HttpClient(
          MockEngine { request ->
            assertEquals("/gas/sponsor/owner", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            respond(
              body,
              status,
              headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
          }
        ) {
          install(ContentNegotiation) { json() }
        }
      try {
        val failure = assertIs<AptosResult.Failure>(submit(client))
        val error = assertIs<xyz.mcxross.kaptos.model.AptosError.Api>(failure.error)
        assertEquals(expected, error.errorCode, body)
        if (!expected.startsWith("gas_station_http_")) {
          assertEquals("Gas sponsorship is unavailable. You can choose to pay gas with APT.", error.message)
        }
      } finally {
        client.close()
      }
    }
  }

  @Test
  fun transportFailureIsNeverReportedAsDefinite() = runTest {
    val client = HttpClient(MockEngine { throw RuntimeException("connection reset") }) {
      install(ContentNegotiation) { json() }
    }
    try {
      val failure = assertIs<AptosResult.Failure>(submit(client))
      assertIs<xyz.mcxross.kaptos.model.AptosError.Transport>(failure.error)
    } finally {
      client.close()
    }
  }

  @Test
  fun tradingKeySubmissionsUseTheTradingRoute() = runTest {
    val client =
      HttpClient(
        MockEngine { request ->
          assertEquals("/gas/sponsor/trading", request.url.encodedPath)
          respond(
            """{"transactionHash":"0x${"22".repeat(32)}"}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
          )
        }
      ) {
        install(ContentNegotiation) { json() }
      }
    try {
      assertIs<AptosResult.Success<String>>(submit(client, ownerOnly = false))
    } finally {
      client.close()
    }
  }

  private suspend fun submit(client: HttpClient, ownerOnly: Boolean = true) =
    WorkerGasSponsorshipRepository(
        client,
        FlareRuntimeConfig(),
        DecibelRuntimeConfig(),
        TestSessionRepository,
      )
      .submit(
        xyz.mcxross.kaptos.model.ExternalFeePayerRequest(
          byteArrayOf(1),
          byteArrayOf(2),
          fingerprint = "0x" + "11".repeat(32),
        ),
        ownerOnly = ownerOnly,
      )

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
        decibelConfig = DecibelRuntimeConfig(),
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
        decibelConfig = DecibelRuntimeConfig(),
        sessions = TestSessionRepository,
      )

    val result = repository.resolve(fingerprint)

    assertEquals(null, assertIs<AptosResult.Success<String?>>(result).value)
  }
}

private object TestSessionRepository : SessionRepository {
  override val status: StateFlow<SessionStatus?> =
    MutableStateFlow(SessionStatus(role = SessionRole.ANONYMOUS, expiresAt = Long.MAX_VALUE))

  override fun <T> bind(status: SessionStatus, operation: Flow<T>): Flow<T> = operation

  override suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus =
    error("Not used")

  override suspend fun accessToken(): String = "test-token"

  override suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus =
    error("Not used")

  override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus =
    error("Not used")

  override suspend fun useAnonymous(): SessionStatus = status.value ?: error("Missing test session")

  override suspend fun invalidate() = Unit
}
