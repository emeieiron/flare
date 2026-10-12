package xyz.mcxross.flare.hyperliquid.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import xyz.mcxross.flare.hyperliquid.HyperliquidConfig

sealed interface StreamEvent {
  /** Subscriptions were sent on a new connection; snapshots follow, so state can be rebuilt. */
  data class Connected(val subscriptions: Set<Subscription>) : StreamEvent

  data class Message(val channel: String, val data: StreamData) : StreamEvent

  /** Hyperliquid refused a request on this connection. */
  data class Rejected(val reason: String) : StreamEvent

  data class Disconnected(val reason: String?) : StreamEvent
}

interface StreamService {
  /**
   * Subscribes on one connection and reconnects with backoff until collection stops. Hyperliquid
   * has no sequence numbers: after [StreamEvent.Connected], callers re-read state over REST or rely
   * on the snapshot the user streams send first.
   */
  fun subscribe(subscriptions: Set<Subscription>): Flow<StreamEvent>
}

internal class DefaultStreamService(
  private val client: HttpClient,
  private val config: HyperliquidConfig,
  private val json: Json,
  private val pingIntervalMs: Long = PING_INTERVAL_MS,
) : StreamService {
  override fun subscribe(subscriptions: Set<Subscription>): Flow<StreamEvent> = channelFlow {
    require(subscriptions.isNotEmpty()) { "At least one Hyperliquid subscription is required" }
    require(subscriptions.size <= MAX_SUBSCRIPTIONS) {
      "Hyperliquid permits at most $MAX_SUBSCRIPTIONS subscriptions"
    }
    require(subscriptions.mapNotNull { it.user?.lowercase() }.toSet().size <= MAX_USERS) {
      "Hyperliquid permits at most $MAX_USERS users across user subscriptions"
    }
    val subscribeMessages =
      subscriptions.map { subscription ->
        json.encodeToString(
          JsonObject.serializer(),
          buildJsonObject {
            put("method", "subscribe")
            put("subscription", subscription.wire)
          },
        )
      }
    var reconnectAttempt = 0

    while (currentCoroutineContext().isActive) {
      try {
        client.webSocket(urlString = config.webSocketUrl) {
          subscribeMessages.forEach { send(it) }
          this@channelFlow.send(StreamEvent.Connected(subscriptions))
          reconnectAttempt = 0
          // Hyperliquid closes a connection it has not heard from for 60 seconds.
          val keepAlive = launch {
            while (isActive) {
              delay(pingIntervalMs)
              send(PING_MESSAGE)
            }
          }
          try {
            for (frame in incoming) {
              if (frame !is Frame.Text) continue
              val payload =
                runCatching { json.parseToJsonElement(frame.readText()) as? JsonObject }.getOrNull()
                  ?: continue
              val channel = (payload["channel"] as? JsonPrimitive)?.contentOrNull ?: continue
              when (channel) {
                PONG_CHANNEL,
                SUBSCRIPTION_RESPONSE_CHANNEL -> Unit
                ERROR_CHANNEL ->
                  this@channelFlow.send(
                    StreamEvent.Rejected(
                      (payload["data"] as? JsonPrimitive)?.contentOrNull
                        ?: "Hyperliquid rejected a request"
                    )
                  )
                else ->
                  this@channelFlow.send(
                    StreamEvent.Message(channel, decodeStreamData(channel, payload["data"], json))
                  )
              }
            }
          } finally {
            keepAlive.cancel()
          }
        }
        send(StreamEvent.Disconnected("WebSocket closed"))
      } catch (error: Throwable) {
        if (!currentCoroutineContext().isActive) throw error
        send(StreamEvent.Disconnected(error.message))
      }
      val base = 500L shl reconnectAttempt.coerceAtMost(6)
      val jitter = Random.nextLong(0L, (base / 2L).coerceAtLeast(1L))
      delay((base + jitter).milliseconds)
      reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
    }
  }

  private companion object {
    const val PING_INTERVAL_MS = 30_000L
    const val MAX_SUBSCRIPTIONS = 1000
    const val MAX_USERS = 10
    const val PING_MESSAGE = """{"method":"ping"}"""
    const val PONG_CHANNEL = "pong"
    const val SUBSCRIPTION_RESPONSE_CHANNEL = "subscriptionResponse"
    const val ERROR_CHANNEL = "error"
  }
}
