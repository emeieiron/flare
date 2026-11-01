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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

/**
 * Shares one transport across logical subscribers. Private feeds belong to one active account;
 * subscribing for a different account replaces the previous account's private collectors.
 */
interface DecibelStreamService {
  fun subscribe(topics: Set<DecibelStreamTopic>): Flow<StreamEvent>

  fun setEnabled(enabled: Boolean) {}

  fun close() {}
}

internal interface StreamSocket {
  suspend fun send(message: String)

  suspend fun receive(): String?
}

internal fun interface StreamTransport {
  suspend fun connect(token: String?, block: suspend StreamSocket.() -> Unit)
}

private class KtorStreamTransport(
  private val client: HttpClient,
  private val config: DecibelConfig,
) : StreamTransport {
  override suspend fun connect(token: String?, block: suspend StreamSocket.() -> Unit) {
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
      val session = this
      block(
        object : StreamSocket {
          override suspend fun send(message: String) = session.send(message)

          override suspend fun receive(): String? {
            for (frame in session.incoming) if (frame is Frame.Text) return frame.readText()
            return null
          }
        },
      )
    }
  }
}

internal class DefaultDecibelStreamService(
  client: HttpClient,
  private val config: DecibelConfig,
  private val json: Json,
  private val transport: StreamTransport = KtorStreamTransport(client, config),
  parentScope: CoroutineScope? = null,
) : DecibelStreamService {
  private val parentContext = parentScope?.coroutineContext ?: client.coroutineContext
  private val serviceJob = SupervisorJob()
  private val scope = CoroutineScope(parentContext + serviceJob)
  private val activeConnection = MutableStateFlow<Job?>(null)

  private class Subscriber(
    val topics: Set<DecibelStreamTopic>,
    val token: String?,
  ) {
    val events = Channel<StreamEvent>(64)
    val privateAccount =
      topics
        .filterIsInstance<AccountStreamTopic>()
        .map { it.wireValue.substringAfter(':') }
        .distinct()
        .singleOrNull()
  }

  private sealed interface Command {
    data class Add(val subscriber: Subscriber) : Command

    data class Remove(val subscriber: Subscriber) : Command

    data object StateChanged : Command

    data class Idle(val generation: Long) : Command

    data class Opened(val generation: Long, val token: String?, val outgoing: Channel<String>) :
      Command

    data class Received(val generation: Long, val text: String) : Command

    data class Closed(val generation: Long, val reason: String) : Command
  }

  private val commands = Channel<Command>(64)
  private val enabled = MutableStateFlow(true)
  private val actor = scope.launch(start = CoroutineStart.UNDISPATCHED) { manageSubscriptions() }

  init {
    // Do not attach an idle supervisor as a child: HttpClient.close() waits for its children.
    parentContext[Job]?.invokeOnCompletion { serviceJob.cancel() }
    actor.invokeOnCompletion { serviceJob.cancel() }
  }

  override fun subscribe(topics: Set<DecibelStreamTopic>): Flow<StreamEvent> = channelFlow {
    require(topics.isNotEmpty()) { "At least one Decibel topic is required" }
    require(topics.size <= 150) { "Decibel permits at most 150 topics per connection" }
    require(
      topics
        .filterIsInstance<AccountStreamTopic>()
        .map { it.wireValue.substringAfter(':') }
        .distinct()
        .size <= 1,
    ) {
      "An account subscription must use one authenticated account"
    }
    val subscriber =
      Subscriber(
        topics.toSet(),
        if (topics.any { it is AccountStreamTopic }) config.accessToken() else null,
      )
    try {
      commands.send(Command.Add(subscriber))
      for (event in subscriber.events) send(event)
    } finally {
      subscriber.events.cancel()
      // Cleanup must reach the actor even when cancellation occurs with its queue full.
      scope.launch {
        val removed = commands.trySend(Command.Remove(subscriber))
        if (!removed.isSuccess && !removed.isClosed) commands.send(Command.Remove(subscriber))
      }
    }
  }

  override fun setEnabled(enabled: Boolean) {
    this.enabled.value = enabled
    if (!enabled) activeConnection.value?.cancel()
    commands.trySend(Command.StateChanged)
  }

  override fun close() {
    scope.cancel()
  }

  private suspend fun manageSubscriptions() {
    val subscribers = linkedSetOf<Subscriber>()
    val lastSequenceByTopic = mutableMapOf<String, ULong>()
    var generation = 0L
    var connection: Job? = null
    var idle: Job? = null
    var outgoing: Channel<String>? = null
    var connectionToken: String? = null
    var active = enabled.value

    fun topics(): Set<DecibelStreamTopic> =
      subscribers.flatMap { it.topics }.distinctBy { it.wireValue }.toSet()

    fun stopConnection() {
      generation++
      connection?.cancel()
      connection = null
      activeConnection.value = null
      outgoing = null
      connectionToken = null
      lastSequenceByTopic.clear()
    }

    fun startConnection(token: String? = null) {
      if (!active || subscribers.isEmpty() || connection?.isActive == true) return
      val currentGeneration = generation
      connection = scope.launch { connect(currentGeneration, token) }
      activeConnection.value = connection
      if (!enabled.value) connection?.cancel()
    }

    suspend fun deliver(subscriber: Subscriber, event: StreamEvent) {
      // Each logical subscriber has its own ordered buffer; unrelated topics are never delivered.
      val sent = subscriber.events.trySend(event)
      if (sent.isSuccess || sent.isClosed) return
      try {
        subscriber.events.send(event)
      } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) throw cancelled
      } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {}
    }

    suspend fun broadcast(event: StreamEvent, topic: DecibelStreamTopic? = null) {
      for (subscriber in subscribers) {
        if (topic == null || subscriber.topics.any { it.wireValue == topic.wireValue })
          deliver(subscriber, event)
      }
    }

    suspend fun sendSubscription(method: String, topic: DecibelStreamTopic) {
      outgoing?.trySend(json.encodeToString(Subscription(method, topic.wireValue)))
    }

    try {
      for (command in commands) {
        if (active != enabled.value) {
          active = enabled.value
          stopConnection()
          if (active) startConnection()
          else broadcast(StreamEvent.Disconnected("Live data paused in background"))
        } else if (active && connection?.isCancelled == true) {
          // Pause/resume may happen before this actor sees the intermediate disabled state.
          stopConnection()
          startConnection()
        }
        when (command) {
          is Command.Add -> {
            idle?.cancel()
            val previous = topics()
            val subscriber = command.subscriber
            val retained = subscribers.filter {
              subscriber.privateAccount == null ||
                it.privateAccount == null ||
                it.privateAccount == subscriber.privateAccount
            }
            val prospective =
              (retained.flatMap { it.topics } + subscriber.topics).distinctBy { it.wireValue }
            if (prospective.size > 150) {
              subscriber.events.close(IllegalArgumentException("Maximum active topic count is 150"))
              continue
            }
            if (subscriber.privateAccount != null) {
              val replaced = subscribers.filter {
                it.privateAccount != null && it.privateAccount != subscriber.privateAccount
              }
              replaced.forEach {
                subscribers.remove(it)
                it.events.close()
              }
              if (
                replaced.isNotEmpty() || (connection != null && connectionToken != subscriber.token)
              ) {
                stopConnection()
              }
            }
            subscribers += subscriber
            if (outgoing != null) {
              topics()
                .filter { added -> previous.none { it.wireValue == added.wireValue } }
                .forEach { sendSubscription("subscribe", it) }
              deliver(subscriber, StreamEvent.Connected(subscriber.topics))
            }
            startConnection(subscriber.token)
          }
          is Command.Remove -> {
            val previous = topics()
            subscribers -= command.subscriber
            val remaining = topics()
            previous
              .filter { removed -> remaining.none { it.wireValue == removed.wireValue } }
              .forEach {
                sendSubscription("unsubscribe", it)
                lastSequenceByTopic.remove(it.wireValue)
              }
            if (subscribers.isEmpty()) {
              val currentGeneration = generation
              idle?.cancel()
              idle = scope.launch {
                delay(2_000L)
                commands.send(Command.Idle(currentGeneration))
              }
            }
          }
          is Command.Idle ->
            if (command.generation == generation && subscribers.isEmpty()) stopConnection()
          is Command.Opened ->
            if (command.generation == generation && active) {
              outgoing = command.outgoing
              connectionToken = command.token
              lastSequenceByTopic.clear()
              topics().forEach { sendSubscription("subscribe", it) }
              subscribers.forEach { deliver(it, StreamEvent.Connected(it.topics)) }
            }
          is Command.Closed ->
            if (command.generation == generation) {
              outgoing = null
              broadcast(StreamEvent.Disconnected(command.reason))
            }
          is Command.Received ->
            if (command.generation == generation && active) {
              // A malformed envelope must not terminate the broker for every feed.
              val payload =
                runCatching { json.parseToJsonElement(command.text) as? JsonObject }.getOrNull()
                  ?: continue
              val rawTopic =
                payload["topic"]
                  ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                  ?.contentOrNull
              val topic = topics().firstOrNull { it.wireValue == rawTopic }
              val success =
                payload["success"]
                  ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                  ?.booleanOrNull
              if (success != null) {
                if (!success)
                  broadcast(
                    StreamEvent.Rejected(
                      topic,
                      payload["error"]
                        ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                        ?.contentOrNull ?: "Decibel rejected the subscription",
                    ),
                    topic,
                  )
                continue
              }
              if (topic == null) continue
              val sequence =
                (payload["sequence_number"] ?: payload["sequence"])
                  ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                  ?.contentOrNull
                  ?.toULongOrNull()
              if (sequence != null) {
                when (classifySequence(lastSequenceByTopic[rawTopic], sequence)) {
                  SequenceDisposition.STALE -> continue
                  SequenceDisposition.GAP ->
                    broadcast(
                      StreamEvent.SequenceGap(
                        topic,
                        checkNotNull(lastSequenceByTopic[rawTopic]) + 1uL,
                        sequence,
                      ),
                      topic,
                    )
                  else -> Unit
                }
                lastSequenceByTopic[topic.wireValue] = sequence
              }
              broadcast(
                StreamEvent.Message(
                  topic,
                  payload,
                  sequence,
                  decodeStreamData(topic, payload, json),
                ),
                topic,
              )
            }
          Command.StateChanged -> Unit
        }
      }
    } finally {
      idle?.cancel()
      stopConnection()
      subscribers.forEach { it.events.close() }
      commands.cancel()
    }
  }

  private suspend fun connect(generation: Long, initialToken: String?) {
    val reconnectBackoff = StreamReconnectBackoff()
    var first = true
    while (currentCoroutineContext().isActive) {
      var connectedAt: TimeMark? = null
      try {
        val token = if (first && initialToken != null) initialToken else config.accessToken()
        first = false
        transport.connect(token) socket@{
          connectedAt = TimeSource.Monotonic.markNow()
          coroutineScope {
            val socket = this@socket
            val outgoing = Channel<String>(Channel.UNLIMITED)
            val writer = launch {
              for (message in outgoing) socket.send(message)
            }
            try {
              commands.send(Command.Opened(generation, token, outgoing))
              while (true) {
                val message = socket.receive() ?: break
                commands.send(Command.Received(generation, message))
              }
            } finally {
              writer.cancel()
              outgoing.close()
            }
          }
        }
        commands.send(Command.Closed(generation, "WebSocket closed"))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        commands.send(Command.Closed(generation, error.message ?: "Live connection unavailable"))
      }
      delay(
        reconnectBackoff
          .nextDelay(connectedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L)
          .milliseconds,
      )
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
