package xyz.mcxross.flare.decibel.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import xyz.mcxross.flare.decibel.DecibelConfig

class DecibelApiTest {
  @Test
  fun buildsWorkerPathAndAuthenticationHeaders() = runTest {
    var requestPath = ""
    var authorization = ""
    var origin = ""
    val client =
      HttpClient(
        MockEngine { request ->
          requestPath = request.url.encodedPath
          authorization = request.headers[HttpHeaders.Authorization].orEmpty()
          origin = request.headers[HttpHeaders.Origin].orEmpty()
          respondJson("""{"value":"ok"}""")
        }
      )
    val api =
      DecibelApi(
        client,
        DecibelConfig(
          restBaseUrl = "https://worker.example/decibel",
          origin = "flare://mobile",
          accessToken = { "session-token" },
        ),
        xyz.mcxross.flare.decibel.DecibelClient.DefaultJson,
      )

    assertEquals("ok", api.get<TestResponse>("markets").value)
    assertEquals("/decibel/api/v1/markets", requestPath)
    assertEquals("Bearer session-token", authorization)
    assertEquals("flare://mobile", origin)
  }

  @Test
  fun retriesEligibleServerFailure() = runTest {
    var attempts = 0
    val client =
      HttpClient(
        MockEngine {
          attempts += 1
          if (attempts == 1) {
            respondJson(
              """{"status":"failed","message":"temporarily unavailable"}""",
              HttpStatusCode.ServiceUnavailable,
            )
          } else {
            respondJson("""{"value":"recovered"}""")
          }
        }
      )
    val api = api(client)

    assertEquals("recovered", api.get<TestResponse>("prices").value)
    assertEquals(2, attempts)
  }

  @Test
  fun doesNotRetryClientContractFailure() = runTest {
    var attempts = 0
    val client =
      HttpClient(
        MockEngine {
          attempts += 1
          respondJson(
            """{"status":"failed","message":"invalid market"}""",
            HttpStatusCode.BadRequest,
          )
        }
      )
    val api = api(client)

    val failure = assertFailsWith<DecibelApiError> { api.get<TestResponse>("orderbook") }
    assertEquals(400, failure.statusCode)
    assertEquals("invalid market", failure.message)
    assertTrue(!failure.retryable)
    assertEquals(1, attempts)
  }

  private fun api(client: HttpClient) =
    DecibelApi(
      client,
      DecibelConfig(restBaseUrl = "https://worker.example/decibel"),
      xyz.mcxross.flare.decibel.DecibelClient.DefaultJson,
    )
}

@Serializable private data class TestResponse(val value: String)

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
  body: String,
  status: HttpStatusCode = HttpStatusCode.OK,
) =
  respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
  )
