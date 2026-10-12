package xyz.mcxross.flare.hyperliquid.api

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.mcxross.flare.hyperliquid.HyperliquidConfig

data class HyperliquidApiError(
  val statusCode: Int,
  override val message: String,
  val retryable: Boolean,
) : Exception(message)

internal class HyperliquidApi(
  private val client: HttpClient,
  private val config: HyperliquidConfig,
  val json: Json,
) {
  /** Reads from `/info`, retrying rate limits and server failures. */
  suspend fun info(body: JsonObject): JsonElement = post(INFO_PATH, body, retry = true)

  /**
   * Sends to `/exchange` once. A signed action is never retried here: after a transport failure
   * its outcome is unknown, and the caller reconciles it instead of risking a second execution.
   */
  suspend fun exchange(body: JsonObject): JsonElement = post(EXCHANGE_PATH, body, retry = false)

  private suspend fun post(path: String, body: JsonObject, retry: Boolean): JsonElement {
    val maxAttempts = if (retry) HYPERLIQUID_MAX_ATTEMPTS else 1
    var attempt = 0
    var lastError: HyperliquidApiError? = null
    val payload = json.encodeToString(JsonObject.serializer(), body)
    while (attempt < maxAttempts) {
      val response =
        try {
          client.post(config.restBaseUrl) {
            url { appendPathSegments(path) }
            contentType(ContentType.Application.Json)
            setBody(payload)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          attempt += 1
          lastError =
            HyperliquidApiError(
              statusCode = 0,
              message = error.message ?: "Hyperliquid request transport failed",
              retryable = true,
            )
          if (attempt >= maxAttempts) throw lastError
          delay(retryDelayMs(attempt))
          continue
        }
      val text = response.bodyAsText()
      if (response.status.value in 200..299) {
        try {
          return json.parseToJsonElement(text)
        } catch (error: SerializationException) {
          throw HyperliquidApiError(
            statusCode = response.status.value,
            message = "Hyperliquid returned malformed JSON from /$path",
            retryable = false,
          )
        }
      }

      val retryable = response.status in HYPERLIQUID_RETRYABLE_STATUSES
      lastError =
        HyperliquidApiError(
          statusCode = response.status.value,
          message = describeFailure(path, response.status, text),
          retryable = retryable,
        )
      attempt += 1
      if (!retryable || attempt >= maxAttempts) throw lastError
      val retryAfterMs =
        response.headers[HttpHeaders.RetryAfter]
          ?.toLongOrNull()
          ?.coerceIn(0L, HYPERLIQUID_MAX_RETRY_DELAY_MS / 1_000L)
          ?.times(1_000L)
      delay(retryAfterMs ?: retryDelayMs(attempt))
    }
    throw checkNotNull(lastError)
  }

  /** Decodes [element] as [T], naming [what] when the shape is not what Flare expects. */
  inline fun <reified T> decode(element: JsonElement, what: String): T =
    try {
      json.decodeFromJsonElement<T>(element)
    } catch (error: SerializationException) {
      throw HyperliquidApiError(
        statusCode = 200,
        message = "Hyperliquid returned an incompatible $what: ${error.message}",
        retryable = false,
      )
    } catch (error: IllegalArgumentException) {
      throw HyperliquidApiError(
        statusCode = 200,
        message = "Hyperliquid returned an incompatible $what: ${error.message}",
        retryable = false,
      )
    }

  private companion object {
    const val INFO_PATH = "info"
    const val EXCHANGE_PATH = "exchange"
  }
}

internal const val HYPERLIQUID_MAX_ATTEMPTS = 3

internal val HYPERLIQUID_RETRYABLE_STATUSES =
  setOf(
    HttpStatusCode.TooManyRequests,
    HttpStatusCode.InternalServerError,
    HttpStatusCode.BadGateway,
    HttpStatusCode.ServiceUnavailable,
    HttpStatusCode.GatewayTimeout,
  )

internal const val HYPERLIQUID_MAX_RETRY_DELAY_MS = 10_000L

internal fun retryDelayMs(attempt: Int): Long = 250L shl (attempt - 1).coerceIn(0, 5)

/**
 * Hyperliquid answers bad requests with a short plain-text reason. Only that leading text is kept,
 * so a failure never carries a full response body into logs.
 */
private fun describeFailure(path: String, status: HttpStatusCode, body: String): String {
  val reason = body.trim().take(MAX_ERROR_TEXT).ifEmpty { null }
  return if (reason == null || reason == "null") {
    "Hyperliquid /$path failed with HTTP ${status.value}"
  } else {
    "Hyperliquid /$path failed with HTTP ${status.value}: $reason"
  }
}

private const val MAX_ERROR_TEXT = 160
