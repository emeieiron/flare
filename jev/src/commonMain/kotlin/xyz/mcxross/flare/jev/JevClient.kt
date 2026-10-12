package xyz.mcxross.flare.jev

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.mcxross.flare.strategies.DecisionAnswer
import xyz.mcxross.flare.strategies.DecisionAnswers
import xyz.mcxross.flare.strategies.DecisionQuery
import xyz.mcxross.flare.strategies.DecisionQuestion

enum class JevFailure {
  INVALID_REQUEST,
  AUTHENTICATION,
  RATE_LIMITED,
  UNAVAILABLE,
  INVALID_RESPONSE,
  TIMEOUT,
  NETWORK,
}

class JevException(val failure: JevFailure) : Exception("Jev request failed: ${failure.name}")

class JevClient(httpClient: HttpClient, private val timeoutMs: Long = 10_000) {
  private val client =
    httpClient.config {
      followRedirects = false
      expectSuccess = false
    }

  init {
    require(timeoutMs in 1..60_000)
  }

  fun close() = client.close()

  suspend fun evaluate(query: DecisionQuery, apiKey: String): DecisionAnswers {
    if (
      query.model != "jev-1.13.0" ||
        apiKey.isBlank() ||
        apiKey.length > 4096 ||
        apiKey.any { it.isWhitespace() || it.code < 32 || it.code in 127..159 } ||
        query.questions.isEmpty() ||
        query.questions.size > 8
    )
      throw JevException(JevFailure.INVALID_REQUEST)
    val body = requestBody(query)
    if (body.toString().length > 32_000) throw JevException(JevFailure.INVALID_REQUEST)
    try {
      return withTimeout(timeoutMs) {
        var attempt = 0
        while (true) {
          val response =
            client.post("https://api.typesafe.ai/v1/systemone") {
              header(HttpHeaders.Authorization, "Bearer $apiKey")
              contentType(ContentType.Application.Json)
              setBody(body.toString())
            }
          val status = response.status.value
          if (status == 429 || status == 529) {
            if (attempt >= 2)
              throw JevException(
                if (status == 429) JevFailure.RATE_LIMITED else JevFailure.UNAVAILABLE
              )
            // Retry only bounded transient overload responses; the outer deadline includes backoff.
            delay(250L shl attempt++)
            continue
          }
          if (status !in 200..299)
            throw JevException(
              when (status) {
                401,
                403 -> JevFailure.AUTHENTICATION
                422 -> JevFailure.INVALID_REQUEST
                in 300..399 -> JevFailure.UNAVAILABLE
                in 500..599 -> JevFailure.UNAVAILABLE
                else -> JevFailure.INVALID_RESPONSE
              }
            )
          val bytes = response.bodyAsChannel().readRemaining(128_001).readByteArray()
          if (bytes.size > 128_000) throw JevException(JevFailure.INVALID_RESPONSE)
          val text = bytes.decodeToString(throwOnInvalidSequence = true)
          return@withTimeout parseAnswers(query, text)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
      }
    } catch (e: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      throw JevException(JevFailure.TIMEOUT)
    } catch (e: CancellationException) {
      throw e
    } catch (e: JevException) {
      throw e
    } catch (_: Exception) {
      throw JevException(JevFailure.NETWORK)
    }
  }

  private fun requestBody(query: DecisionQuery): JsonObject = buildJsonObject {
    put("model", query.model)
    put("state", query.state)
    put(
      "questions",
      buildJsonObject {
        query.questions.forEach { (id, question) ->
          if (
            id.isBlank() ||
              id.length > 80 ||
              question.instructions.isBlank() ||
              question.instructions.length > 4000
          )
            throw JevException(JevFailure.INVALID_REQUEST)
          put(
            id,
            buildJsonObject {
              put(
                "type",
                when (question) {
                  is DecisionQuestion.Choice -> "choice"
                  is DecisionQuestion.Noul -> "noul"
                  is DecisionQuestion.Score -> "score"
                },
              )
              put("instructions", question.instructions)
              when (question) {
                is DecisionQuestion.Choice -> {
                  if (
                    question.criteria.size !in 2..16 ||
                      question.criteria.any { it.key.isBlank() || it.value.isBlank() }
                  )
                    throw JevException(JevFailure.INVALID_REQUEST)
                  put(
                    "criteria",
                    buildJsonObject {
                      question.criteria.forEach { (key, value) -> put(key, value) }
                    },
                  )
                }
                is DecisionQuestion.Score -> {
                  if (question.criteria.size !in 2..10 || question.criteria.any { it.isBlank() })
                    throw JevException(JevFailure.INVALID_REQUEST)
                  put("criteria", JsonArray(question.criteria.map(::JsonPrimitive)))
                }
                is DecisionQuestion.Noul -> Unit
              }
            },
          )
        }
      },
    )
  }

  internal fun parseAnswers(query: DecisionQuery, text: String): DecisionAnswers {
    try {
      val root = Json.parseToJsonElement(text).jsonObject
      require(root["model"]!!.jsonPrimitive.content == query.model)
      val answers = root["answers"]!!.jsonObject
      require(answers.keys == query.questions.keys)
      fun number(value: JsonElement?): Double =
        value!!
          .jsonPrimitive
          .let {
            require(!it.isString)
            it.double
          }
          .also { require(it.isFinite()) }
      fun unit(value: JsonElement?): Double = number(value).also { require(it in 0.0..1.0) }
      fun distribution(answer: JsonObject, keys: Set<String>): Map<String, Double> =
        answer["probabilities"]!!.jsonObject.let { values ->
          require(values.keys == keys)
          values.mapValues { unit(it.value) }.also { require(abs(it.values.sum() - 1.0) <= 0.001) }
        }
      return DecisionAnswers(
        query.model,
        query.questions.mapValues { (id, question) ->
          val answer = answers[id]!!.jsonObject
          val type = answer["type"]!!.jsonPrimitive.content
          when (question) {
            is DecisionQuestion.Choice -> {
              require(type == "choice")
              val choice = answer["choice"]!!.jsonPrimitive.content
              require(choice in question.criteria)
              val probabilities = distribution(answer, question.criteria.keys)
              require(probabilities.getValue(choice) == probabilities.values.max())
              DecisionAnswer.Choice(choice, probabilities, unit(answer["confidence"]))
            }
            is DecisionQuestion.Noul -> {
              require(type == "noul" && "confidence" !in answer)
              DecisionAnswer.Noul(unit(answer["noul"]))
            }
            is DecisionQuestion.Score -> {
              require(type == "score")
              val keys = question.criteria.indices.map { it.toString() }.toSet()
              val legend = answer["legend"]!!.jsonObject
              require(
                legend.keys == keys &&
                  question.criteria.indices.all {
                    legend[it.toString()]!!.jsonPrimitive.content == question.criteria[it]
                  }
              )
              val probabilities = distribution(answer, keys)
              val score = number(answer["score"])
              require(score in 0.0..question.criteria.lastIndex.toDouble())
              val expected =
                question.criteria.indices.sumOf { it * probabilities.getValue(it.toString()) }
              require(abs(score - expected) <= 0.02)
              DecisionAnswer.Score(
                score,
                question.criteria.indices.map { probabilities.getValue(it.toString()) },
                unit(answer["confidence"]),
                question.criteria,
              )
            }
          }
        },
      )
    } catch (_: Exception) {
      throw JevException(JevFailure.INVALID_RESPONSE)
    }
  }
}
