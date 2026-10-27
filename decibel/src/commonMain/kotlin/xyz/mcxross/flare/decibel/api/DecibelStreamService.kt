package xyz.mcxross.flare.decibel.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.decibel.DecibelConfig

sealed interface StreamEvent {
  data class Connected(val topics: Set<DecibelStreamTopic>) : StreamEvent

  data class Message(
    val topic: DecibelStreamTopic?,
    val payload: JsonObject,
    val sequence: ULong?,
    val data: DecibelStreamData,
  ) : StreamEvent

  data class SequenceGap(
    val topic: DecibelStreamTopic?,
    val expected: ULong,
    val received: ULong,
  ) : StreamEvent

  data class Rejected(val topic: DecibelStreamTopic?, val reason: String) : StreamEvent

  data class Disconnected(val reason: String?) : StreamEvent
}

interface DecibelStreamService {
  fun subscribe(topics: Set<DecibelStreamTopic>): Flow<StreamEvent>
}

internal class DefaultDecibelStreamService(
  private val client: HttpClient,
  private val config: DecibelConfig,
  private val json: Json,
) : DecibelStreamService {
  override fun subscribe(topics: Set<DecibelStreamTopic>): Flow<StreamEvent> = channelFlow {
    require(topics.isNotEmpty()) { "At least one Decibel topic is required" }
    require(topics.size <= 150) { "Decibel permits at most 150 topics per connection" }
    val topicByWireValue = topics.associateBy(DecibelStreamTopic::wireValue)
    val reconnectBackoff = StreamReconnectBackoff()
    val lastSequenceByTopic = mutableMapOf<String, ULong>()

    while (currentCoroutineContext().isActive) {
      var connectedAt: TimeMark? = null
      try {
        val token = config.accessToken()
        client.webSocket(
          urlString = config.webSocketUrl,
          request = {
            header(HttpHeaders.Origin, config.origin)
            token?.let {
              bearerAuth(it)
              header(HttpHeaders.SecWebSocketProtocol, "decibel, $it")
            }
          },
        ) {
          connectedAt = TimeSource.Monotonic.markNow()
          // Connected repositories perform a REST backfill, establishing a new baseline.
          lastSequenceByTopic.clear()
          topics.forEach {
            send(json.encodeToString(Subscription("subscribe", it.wireValue)))
          }
          this@channelFlow.send(StreamEvent.Connected(topics))
          for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val payload = json.parseToJsonElement(frame.readText()) as? JsonObject ?: continue
            val rawTopic = payload["topic"]?.jsonPrimitive?.contentOrNull
            val topic = rawTopic?.let(topicByWireValue::get)
            val success = payload["success"]?.jsonPrimitive?.booleanOrNull
            if (success != null) {
              if (!success) {
                this@channelFlow.send(
                  StreamEvent.Rejected(
                    topic = topic,
                    reason =
                      payload["error"]?.jsonPrimitive?.contentOrNull
                        ?: "Decibel rejected the subscription",
                  )
                )
              }
              continue
            }
            val sequence =
              (payload["sequence_number"] ?: payload["sequence"])
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toULongOrNull()
            val sequenceKey = rawTopic ?: "__connection__"
            val previousSequence = lastSequenceByTopic[sequenceKey]
            if (sequence != null) {
              when (classifySequence(previousSequence, sequence)) {
                SequenceDisposition.STALE -> continue
                SequenceDisposition.GAP ->
                  this@channelFlow.send(
                    StreamEvent.SequenceGap(
                      topic,
                      checkNotNull(previousSequence) + 1uL,
                      sequence,
                    )
                  )
                SequenceDisposition.FIRST,
                SequenceDisposition.NEXT -> Unit
              }
              lastSequenceByTopic[sequenceKey] = sequence
            }
            this@channelFlow.send(
              StreamEvent.Message(
                topic = topic,
                payload = payload,
                sequence = sequence,
                data = decodeStreamData(topic, payload, json),
              )
            )
          }
        }
        send(StreamEvent.Disconnected("WebSocket closed"))
      } catch (error: Throwable) {
        if (!currentCoroutineContext().isActive) throw error
        send(StreamEvent.Disconnected(error.message))
      }
      delay(reconnectBackoff.nextDelay(connectedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L).milliseconds)
    }
  }
}

internal class StreamReconnectBackoff {
  private var attempt = 0

  fun nextDelay(connectedForMs: Long): Long {
    // A proxy handshake can succeed even when its upstream immediately rejects the connection.
    if (connectedForMs >= 30_000L) attempt = 0
    val base = 500L shl attempt
    attempt = (attempt + 1).coerceAtMost(6)
    return base + Random.nextLong(0L, base / 2L)
  }
}

internal enum class SequenceDisposition {
  FIRST,
  NEXT,
  GAP,
  STALE,
}

internal fun classifySequence(previous: ULong?, received: ULong): SequenceDisposition =
  when {
    previous == null -> SequenceDisposition.FIRST
    received <= previous -> SequenceDisposition.STALE
    received == previous + 1uL -> SequenceDisposition.NEXT
    else -> SequenceDisposition.GAP
  }

@Serializable private data class Subscription(val method: String, val topic: String)
