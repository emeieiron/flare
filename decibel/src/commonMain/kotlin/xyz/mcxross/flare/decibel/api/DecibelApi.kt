package xyz.mcxross.flare.decibel.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.decibel.DecibelConfig

data class DecibelApiError(
  val statusCode: Int,
  override val message: String,
  val retryable: Boolean,
) : Exception(message)

internal class DecibelApi(
  private val client: HttpClient,
  private val config: DecibelConfig,
  val json: Json,
) {
  suspend inline fun <reified T> get(
    path: String,
    crossinline parameters: HttpRequestBuilder.() -> Unit = {},
  ): T {
    var attempt = 0
    var lastError: DecibelApiError? = null
    while (attempt < DECIBEL_MAX_ATTEMPTS) {
      val response =
        try {
          client.get(config.restBaseUrl) {
            url { appendPathSegments("api", "v1", path.trimStart('/')) }
            header(HttpHeaders.Origin, config.origin)
            config.accessToken()?.let { bearerAuth(it) }
            parameters()
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          attempt += 1
          lastError =
            DecibelApiError(
              statusCode = 0,
              message = error.message ?: "Decibel request transport failed",
              retryable = true,
            )
          if (attempt >= DECIBEL_MAX_ATTEMPTS) throw lastError
          delay(retryDelayMs(attempt))
          continue
        }
      val body = response.bodyAsText()
      if (response.status.value in 200..299) {
        try {
          return json.decodeFromString(body)
        } catch (error: SerializationException) {
          throw DecibelApiError(
            statusCode = response.status.value,
            message = "Decibel returned an incompatible response for $path: ${error.message}",
            retryable = false,
          )
        }
      }

      val retryable = response.status in DECIBEL_RETRYABLE_STATUSES
      lastError =
        DecibelApiError(
          statusCode = response.status.value,
          message = decodeDecibelMessage(json, body, response.status),
          retryable = retryable,
        )
      attempt += 1
      if (!retryable || attempt >= DECIBEL_MAX_ATTEMPTS) throw lastError
      val retryAfterMs =
        response.headers[HttpHeaders.RetryAfter]
          ?.toLongOrNull()
          ?.coerceIn(0L, DECIBEL_MAX_RETRY_DELAY_MS / 1_000L)
          ?.times(1_000L)
      delay(retryAfterMs ?: retryDelayMs(attempt))
    }
    throw checkNotNull(lastError)
  }
}

@kotlinx.serialization.Serializable
internal data class ErrorEnvelope(val status: String = "failed", val message: String)

@PublishedApi internal const val DECIBEL_MAX_ATTEMPTS = 3

@PublishedApi
internal val DECIBEL_RETRYABLE_STATUSES =
  setOf(
    HttpStatusCode.TooManyRequests,
    HttpStatusCode.InternalServerError,
    HttpStatusCode.BadGateway,
    HttpStatusCode.ServiceUnavailable,
    HttpStatusCode.GatewayTimeout,
  )

@PublishedApi internal const val DECIBEL_MAX_RETRY_DELAY_MS = 10_000L

@PublishedApi internal fun retryDelayMs(attempt: Int): Long = 250L shl (attempt - 1).coerceIn(0, 5)

@PublishedApi
internal fun decodeDecibelMessage(json: Json, body: String, status: HttpStatusCode): String =
  runCatching {
    val error = json.decodeFromString<ErrorEnvelope>(body)
    error.message
  }
  .getOrDefault("Decibel request failed with HTTP ${status.value}")
