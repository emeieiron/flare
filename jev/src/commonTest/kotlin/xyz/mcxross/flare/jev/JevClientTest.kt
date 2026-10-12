package xyz.mcxross.flare.jev

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import xyz.mcxross.flare.strategies.*

class JevClientTest {
  private val query =
    DecisionQuery(
      buildJsonObject { put("rsi", 42) },
      mapOf(
        "direction" to
          DecisionQuestion.Choice("Direction?", mapOf("buy" to "Up", "wait" to "Unclear"))
      ),
    )
  private val good =
    """{"model":"jev-1.13.0","answers":{"direction":{"type":"choice","choice":"buy","probabilities":{"buy":0.8,"wait":0.2},"confidence":0.6}}}"""

  private fun testEngine(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
  ) =
    MockEngine(
      MockEngineConfig().apply {
        dispatcher = Dispatchers.Unconfined
        addHandler(handler)
      }
    )

  private fun mock(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    HttpClient(
      testEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
    )

  @Test
  fun sendsPinnedTypedRequestAndParsesChoice() = runTest {
    val transport =
      HttpClient(
        testEngine { request ->
          assertEquals("https://api.typesafe.ai/v1/systemone", request.url.toString())
          assertEquals("Bearer test-only-key", request.headers[HttpHeaders.Authorization])
          val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
          assertEquals("jev-1.13.0", body["model"]!!.jsonPrimitive.content)
          assertEquals(
            "choice",
            body["questions"]!!.jsonObject["direction"]!!.jsonObject["type"]!!.jsonPrimitive.content,
          )
          respond(good, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
      )
    val client = JevClient(transport)
    try {
      val result =
        client.evaluate(query, "test-only-key").answers["direction"] as DecisionAnswer.Choice
      assertEquals(0.6, result.confidence)
      assertEquals(0.8, result.probabilities["buy"])
    } finally {
      client.close()
      transport.close()
    }
  }

  @Test
  fun parsesNoulWithoutInventingConfidenceAndOrderedScore() {
    val transport = mock(good)
    val client = JevClient(transport)
    try {
      val questions =
        DecisionQuery(
          buildJsonObject {},
          mapOf(
            "yes" to DecisionQuestion.Noul("Yes?"),
            "score" to DecisionQuestion.Score("Rate", listOf("low", "high")),
          ),
        )
      val response =
        """{"model":"jev-1.13.0","answers":{"yes":{"type":"noul","noul":0.9},"score":{"type":"score","score":0.75,"legend":{"0":"low","1":"high"},"probabilities":{"0":0.25,"1":0.75},"confidence":0.4}}}"""
      val answers = client.parseAnswers(questions, response)
      assertEquals(DecisionAnswer.Noul(0.9), answers.answers["yes"])
      assertEquals(
        DecisionAnswer.Score(0.75, listOf(0.25, 0.75), 0.4, listOf("low", "high")),
        answers.answers["score"],
      )
      assertEquals(
        JevFailure.INVALID_RESPONSE,
        assertFailsWith<JevException> {
            client.parseAnswers(
              questions,
              response.replace("\"noul\":0.9", "\"noul\":0.9,\"confidence\":0.8"),
            )
          }
          .failure,
      )
    } finally {
      client.close()
      transport.close()
    }
  }

  @Test
  fun rejectsMalformedTypesKeysRangesModelsAndDistributions() {
    val transport = mock(good)
    val client = JevClient(transport)
    try {
      listOf(
          "{}",
          good.replace("jev-1.13.0", "jev-latest"),
          good.replace("\"choice\",", "\"score\","),
          good.replace("\"buy\":0.8", "\"buy\":1.8"),
          good.replace("\"buy\":0.8", "\"buy\":0.7"),
          good.replace("\"confidence\":0.6", "\"confidence\":\"0.6\""),
          good.replace("\"choice\":\"buy\"", "\"choice\":\"wait\""),
          good.replace("\"wait\":0.2", "\"sell\":0.2"),
        )
        .forEach { body ->
          assertEquals(
            JevFailure.INVALID_RESPONSE,
            assertFailsWith<JevException> { client.parseAnswers(query, body) }.failure,
          )
        }
    } finally {
      client.close()
      transport.close()
    }
  }

  @Test
  fun errorsAndRedirectsAreSanitizedAndDoNotLeakKeys() = runTest {
    for ((status, expected) in
      listOf(
        HttpStatusCode.Unauthorized to JevFailure.AUTHENTICATION,
        HttpStatusCode.UnprocessableEntity to JevFailure.INVALID_REQUEST,
        HttpStatusCode.InternalServerError to JevFailure.UNAVAILABLE,
        HttpStatusCode.Found to JevFailure.UNAVAILABLE,
      )) {
      var calls = 0
      val transport =
        HttpClient(
          testEngine {
            calls++
            respond(
              "secret-provider-body",
              status,
              headersOf(HttpHeaders.Location, "https://evil.example"),
            )
          }
        )
      val client = JevClient(transport)
      try {
        val exception = assertFailsWith<JevException> { client.evaluate(query, "secret-key") }
        assertEquals(expected, exception.failure)
        assertFalse(exception.toString().contains("secret"))
        assertNull(exception.cause)
        assertEquals(1, calls)
      } finally {
        client.close()
        transport.close()
      }
    }
  }

  @Test
  fun transientResponsesRetryWithBoundedBackoff() = runTest {
    var calls = 0
    val transport =
      HttpClient(
        testEngine {
          calls++
          respond(
            if (calls < 3) "overload" else good,
            if (calls == 1) HttpStatusCode.TooManyRequests
            else if (calls == 2) HttpStatusCode(529, "Overloaded") else HttpStatusCode.OK,
          )
        }
      )
    val client = JevClient(transport)
    try {
      client.evaluate(query, "test-key")
      assertEquals(3, calls)
    } finally {
      client.close()
      transport.close()
    }
  }

  @Test
  fun overloadStopsAfterThreeAttemptsAndNetworkErrorsStaySanitized() = runTest {
    var calls = 0
    val transport =
      HttpClient(
        testEngine {
          calls++
          respond("sensitive", HttpStatusCode.TooManyRequests)
        }
      )
    val client = JevClient(transport)
    try {
      assertEquals(
        JevFailure.RATE_LIMITED,
        assertFailsWith<JevException> { client.evaluate(query, "test-key") }.failure,
      )
      assertEquals(3, calls)
      assertEquals(
        JevFailure.INVALID_REQUEST,
        assertFailsWith<JevException> { client.evaluate(query, "bad" + 128.toChar()) }.failure,
      )
      assertEquals(3, calls)
    } finally {
      client.close()
      transport.close()
    }
    val brokenTransport = HttpClient(testEngine { error("secret-key sensitive provider details") })
    val brokenClient = JevClient(brokenTransport)
    try {
      val failure = assertFailsWith<JevException> { brokenClient.evaluate(query, "test-key") }
      assertEquals(JevFailure.NETWORK, failure.failure)
      assertFalse(failure.toString().contains("sensitive"))
      assertNull(failure.cause)
    } finally {
      brokenClient.close()
      brokenTransport.close()
    }
  }

  @Test
  fun timeoutAndCancellationRemainDistinct() = runTest {
    val transport =
      HttpClient(
        testEngine {
          delay(5000)
          respond(good)
        }
      )
    val client = JevClient(transport, 100)
    try {
      assertEquals(
        JevFailure.TIMEOUT,
        assertFailsWith<JevException> { client.evaluate(query, "test-key") }.failure,
      )
      assertFailsWith<CancellationException> {
        withTimeout(10) { client.evaluate(query, "test-key") }
      }
    } finally {
      client.close()
      transport.close()
    }
  }
}
