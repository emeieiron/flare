package xyz.mcxross.flare.hyperliquid

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import xyz.mcxross.flare.hyperliquid.api.DefaultStreamService
import xyz.mcxross.flare.hyperliquid.api.StreamData
import xyz.mcxross.flare.hyperliquid.api.StreamEvent
import xyz.mcxross.flare.hyperliquid.api.StreamService
import xyz.mcxross.flare.hyperliquid.api.Subscription
import xyz.mcxross.flare.hyperliquid.model.ApproveAgent
import xyz.mcxross.flare.hyperliquid.model.ApproveBuilderFee
import xyz.mcxross.flare.hyperliquid.model.AssetDirectory
import xyz.mcxross.flare.hyperliquid.model.AssetKind
import xyz.mcxross.flare.hyperliquid.model.CancelByCloidRequest
import xyz.mcxross.flare.hyperliquid.model.CancelOrders
import xyz.mcxross.flare.hyperliquid.model.CancelOrdersByCloid
import xyz.mcxross.flare.hyperliquid.model.CancelRequest
import xyz.mcxross.flare.hyperliquid.model.CancelStatus
import xyz.mcxross.flare.hyperliquid.model.CandleInterval
import xyz.mcxross.flare.hyperliquid.model.Cloid
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.hyperliquid.model.HlDecimal
import xyz.mcxross.flare.hyperliquid.model.HlRounding
import xyz.mcxross.flare.hyperliquid.model.HyperliquidAsset
import xyz.mcxross.flare.hyperliquid.model.OrderRequest
import xyz.mcxross.flare.hyperliquid.model.OrderStatus
import xyz.mcxross.flare.hyperliquid.model.OrderType
import xyz.mcxross.flare.hyperliquid.model.PlaceOrders
import xyz.mcxross.flare.hyperliquid.model.TimeInForce
import xyz.mcxross.flare.hyperliquid.model.UpdateLeverage
import xyz.mcxross.flare.hyperliquid.model.UsdSend
import xyz.mcxross.flare.hyperliquid.model.VaultTransfer
import xyz.mcxross.flare.hyperliquid.model.toHlDecimal
import xyz.mcxross.flare.hyperliquid.signing.EvmSigner
import xyz.mcxross.flare.hyperliquid.signing.FastKryptoSigner

/*
 * Opt-in tests against Hyperliquid testnet. Every test here is skipped unless its environment
 * variable is set, so the normal `jvmTest` run never touches the network:
 *
 * - FLARE_HYPERLIQUID_LIVE=1: read-only REST and WebSocket checks, and signing proofs with a fresh
 *   unfunded key (Hyperliquid rejects the actions, naming the address it recovered).
 * - FLARE_HYPERLIQUID_TEST_KEY_FILE=<path>: the funded lifecycle, signed by the 0x hex private key
 *   in that file. The key is never printed. Testnet only.
 * - FLARE_HYPERLIQUID_TEST_VAULT=1 (with the key file): also deposits into HLP, which locks the
 *   deposit for 4 days.
 */

private const val LIVE_FLAG = "FLARE_HYPERLIQUID_LIVE"
private const val KEY_FILE = "FLARE_HYPERLIQUID_TEST_KEY_FILE"
private const val VAULT_FLAG = "FLARE_HYPERLIQUID_TEST_VAULT"

private fun assumeLive() {
  assumeTrue(System.getenv(LIVE_FLAG) == "1", "Set $LIVE_FLAG=1 to run live testnet tests")
}

/** Ktor CIO with WebSockets; CIO takes the JVM's proxy settings, if any. */
private fun liveHttpClient(): HttpClient = HttpClient(CIO) { install(WebSockets) }

private fun testnetClient(http: HttpClient): HyperliquidClient {
  val client = HyperliquidClient(http, HyperliquidConfig(HyperliquidDeployment.Testnet))
  // Refuse anything but testnet: these tests sign real actions.
  check(client.config.deployment == HyperliquidDeployment.Testnet)
  check(client.config.restBaseUrl == "https://api.hyperliquid-testnet.xyz")
  return client
}

private val secureRandom = SecureRandom()

/** A fresh key that exists only in memory. */
private fun freshSigner(): FastKryptoSigner {
  val key = ByteArray(FastKryptoSigner.PRIVATE_KEY_SIZE).also(secureRandom::nextBytes)
  try {
    return FastKryptoSigner(key)
  } finally {
    key.fill(0)
  }
}

/** An address nobody holds a key for. */
private fun randomAddress(): String =
  "0x" + ByteArray(20).also(secureRandom::nextBytes).joinToString("") { "%02x".format(it) }

/** REST reads against testnet. */
class HyperliquidTestnetReadTest {
  @Test
  fun marketsBooksCandlesAndRoles() = runBlocking {
    assumeLive()
    liveHttpClient().use { http ->
      val info = testnetClient(http).info

      val perps = info.perpMetaAndContexts()
      assertTrue(perps.meta.universe.isNotEmpty())
      // Perp contexts are aligned with the universe by position.
      assertEquals(perps.meta.universe.size, perps.contexts.size)
      val spots = info.spotMetaAndContexts()
      assertTrue(spots.meta.universe.isNotEmpty())
      assertTrue(spots.contexts.isNotEmpty())

      val assets = AssetDirectory(perps.meta, spots.meta)
      val btc = assets.requireCoin("BTC")
      assertEquals(AssetKind.PERP, btc.kind)
      assertEquals(btc, assets.byId(btc.id))
      val btcMark = perps.contextByCoin.getValue("BTC").markPx.toHlDecimal()
      assertTrue(btcMark.signum > 0)
      println(
        "Testnet: ${assets.perps.size} perps, ${assets.spots.size} spot pairs; " +
          "BTC id ${btc.id}, szDecimals ${btc.szDecimals}, mark $btcMark"
      )

      val book = info.l2Book("BTC")
      assertEquals("BTC", book.coin)
      assertTrue(book.bids.isNotEmpty() && book.asks.isNotEmpty(), "BTC book has both sides")
      assertTrue(book.bids.first().px.toHlDecimal() < book.asks.first().px.toHlDecimal())

      val now = Clock.System.now().toEpochMilliseconds()
      val candles = info.candles("BTC", CandleInterval.ONE_HOUR, now - 24 * HOUR_MS, now)
      assertTrue(candles.isNotEmpty(), "BTC has hourly candles for the last day")
      assertTrue(candles.all { it.coin == "BTC" && it.interval == "1h" })
      assertTrue(candles.zipWithNext().all { (a, b) -> a.openTimeMs < b.openTimeMs })

      val role = info.userRole(randomAddress())
      assertEquals("missing", role.role)
      assertTrue(role.isMissing)
    }
  }

  private companion object {
    const val HOUR_MS = 3_600_000L
  }
}

/** The WebSocket stream against testnet. */
class HyperliquidTestnetStreamTest {
  @Test
  fun streamsMidsAndBook() = runBlocking {
    assumeLive()
    liveHttpClient().use { http ->
      val stream = testnetClient(http).stream
      val subscriptions =
        setOf(Subscription.AllMids, Subscription.L2Book("BTC"), Subscription.Trades("BTC"))
      val events = mutableListOf<StreamEvent>()
      var mids: StreamData.Mids? = null
      var book: StreamData.Book? = null
      // `first` stops collecting, which closes the socket.
      withTimeout(STREAM_TIMEOUT_MS) {
        stream.subscribe(subscriptions).first { event ->
          events += event
          when (event) {
            is StreamEvent.Rejected -> fail("Hyperliquid rejected a subscription: ${event.reason}")
            is StreamEvent.Disconnected -> fail("Disconnected: ${event.reason}")
            is StreamEvent.Message ->
              when (val data = event.data) {
                is StreamData.Mids -> mids = data
                is StreamData.Book -> book = data
                is StreamData.Malformed -> fail("Undecodable ${event.channel}: ${data.reason}")
                else -> Unit
              }
            is StreamEvent.Connected -> Unit
          }
          mids != null && book != null
        }
      }
      assertEquals(StreamEvent.Connected(subscriptions), events.first())
      assertTrue(assertNotNull(mids).mids.getValue("BTC").toHlDecimal().signum > 0)
      val btcBook = assertNotNull(book).book
      assertEquals("BTC", btcBook.coin)
      assertTrue(btcBook.bids.isNotEmpty() || btcBook.asks.isNotEmpty())
      val channels = events.filterIsInstance<StreamEvent.Message>().map { it.channel }.distinct()
      println("Stream: ${events.size} events until mids and book; channels $channels")
    }
  }

  /**
   * Hyperliquid closes a connection it has not heard from for 60 s. Two quiet connections run side
   * by side for [QUIET_WINDOW_MS]: the one that pings every 30 s must stay up, and a control that
   * never pings must be dropped, which shows the window really is long enough to need the ping.
   */
  @Test
  fun pingKeepsAQuietConnectionOpen() = runBlocking {
    assumeLive()
    liveHttpClient().use { http ->
      val config = testnetClient(http).config
      val json = HyperliquidClient.DefaultJson
      // Order updates of an account that never trades: no messages after subscribing.
      val quiet = setOf(Subscription.OrderUpdates(randomAddress()))
      val (pinged, silent) =
        coroutineScope {
          val pinged = async { eventsFor(DefaultStreamService(http, config, json), quiet) }
          val silent = async {
            eventsFor(DefaultStreamService(http, config, json, pingIntervalMs = NEVER_MS), quiet)
          }
          pinged.await() to silent.await()
        }
      println("Pinged connection: $pinged")
      println("Silent connection: $silent")
      assertEquals(listOf<StreamEvent>(StreamEvent.Connected(quiet)), pinged)
      assertIs<StreamEvent.Connected>(silent.first())
      assertTrue(
        silent.any { it is StreamEvent.Disconnected },
        "The connection that never pings is closed within ${QUIET_WINDOW_MS / 1_000} s",
      )
    }
  }

  private suspend fun eventsFor(
    stream: StreamService,
    subscriptions: Set<Subscription>,
  ): List<StreamEvent> {
    val events = mutableListOf<StreamEvent>()
    withTimeoutOrNull(QUIET_WINDOW_MS) { stream.subscribe(subscriptions).collect { events += it } }
    return events
  }

  private companion object {
    const val STREAM_TIMEOUT_MS = 30_000L
    const val QUIET_WINDOW_MS = 70_000L
    const val NEVER_MS = 3_600_000L
  }
}

/**
 * Signing proofs with a fresh, unfunded key. Hyperliquid recovers the signer from the signature
 * before anything else, then rejects the action because that account does not exist, naming the
 * recovered address. Neither action names the signer in its payload, so an address in the reply
 * that equals the key's own address can only come from a correct signature over the right bytes:
 * a wrong hash, domain or `v` recovers some other address.
 *
 * Testnet's replies (October 2026), for the record:
 * - L1 (`cancel`): `User or API Wallet 0x… does not exist.`
 * - user-signed (`approveAgent`, `usdSend`): `Must deposit before performing actions. User: 0x…`
 */
class HyperliquidTestnetSigningTest {
  @Test
  fun l1ActionRecoversTheSignersAddress() = runBlocking {
    assumeLive()
    liveHttpClient().use { http ->
      val client = testnetClient(http)
      val signer = freshSigner()
      val btc = AssetDirectory(client.info.perpMeta(), client.info.spotMeta()).requireCoin("BTC")

      // Order id 1 was never this account's; the signature is checked before the order is.
      val outcome = client.exchange.submit(CancelOrders(listOf(CancelRequest(btc.id, 1L))), signer)

      println("cancel signed by ${signer.address}: $outcome")
      assertRejectedNaming(outcome, signer)
    }
  }

  @Test
  fun userSignedActionsRecoverTheSignersAddress() = runBlocking {
    assumeLive()
    liveHttpClient().use { http ->
      val client = testnetClient(http)
      val signer = freshSigner()

      val approve = client.exchange.submit(ApproveAgent(randomAddress(), "flare-test"), signer)
      println("approveAgent signed by ${signer.address}: $approve")
      assertRejectedNaming(approve, signer)

      val send = client.exchange.submit(UsdSend(randomAddress(), HlDecimal.ONE), signer)
      println("usdSend signed by ${signer.address}: $send")
      assertRejectedNaming(send, signer)
    }
  }

  private fun assertRejectedNaming(outcome: ExchangeOutcome, signer: EvmSigner) {
    val reason = assertIs<ExchangeOutcome.Rejected>(outcome).reason
    val addresses = ADDRESS.findAll(reason).map { it.value.lowercase() }.toList()
    assertEquals(listOf(signer.address), addresses, "Recovered address in: $reason")
  }

  private companion object {
    val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
  }
}

/**
 * The funded lifecycle, signed by the key in [KEY_FILE] on testnet: leverage, an order placed and
 * canceled by the owner, an agent approved, trading and then deregistered, and a builder fee
 * approved and revoked. Orders are bought far below the market and always canceled.
 */
class HyperliquidTestnetFundedTest {
  @Test
  fun ownerAgentAndBuilderLifecycle() = runBlocking {
    val keyFile = System.getenv(KEY_FILE)
    assumeTrue(!keyFile.isNullOrBlank(), "Set $KEY_FILE for the funded testnet lifecycle")
    val owner = FastKryptoSigner.fromHex(Files.readString(Path.of(keyFile!!)).trim())
    liveHttpClient().use { http ->
      val client = testnetClient(http)
      val info = client.info
      val exchange = client.exchange
      println("Owner: ${owner.address}")

      val role = info.userRole(owner.address)
      assertEquals("user", role.role, "The owner needs a funded testnet account")

      val btc = AssetDirectory(info.perpMeta(), info.spotMeta()).requireCoin("BTC")
      val leverage = exchange.submit(UpdateLeverage(btc.id, isCross = true, leverage = 3), owner)
      println("updateLeverage: $leverage")
      assertEquals(ExchangeOutcome.Accepted(ExchangeResponse.Default), leverage)

      // Owner: place, check, cancel, check.
      placeAndCancel(client, owner, btc, placer = owner)

      // Agent: approve, trade, (deposit into HLP), deregister.
      val agent = freshSigner()
      val agentsBefore = info.extraAgents(owner.address)
      println("Agents before: ${agentsBefore.map { it.name to it.address }}")
      val approve = exchange.submit(ApproveAgent(agent.address, AGENT_NAME), owner)
      println("approveAgent ${agent.address}: $approve")
      assertEquals(ExchangeOutcome.Accepted(ExchangeResponse.Default), approve)
      val agentsWith = info.extraAgents(owner.address)
      println("Agents with ${agent.address}: ${agentsWith.map { it.name to it.address }}")
      assertTrue(agentsWith.any { it.address.lowercase() == agent.address })

      placeAndCancel(client, owner, btc, placer = agent)

      if (System.getenv(VAULT_FLAG) == "1") {
        // Signed by the agent: confirms an API wallet may sign vaultTransfer (PR 2's question).
        // HLP locks this deposit for 4 days; it is not withdrawn here.
        val deposit =
          exchange.submit(
            VaultTransfer.ofUsdc(TESTNET_HLP, isDeposit = true, amount = HLP_DEPOSIT_USDC),
            agent,
          )
        println("vaultTransfer of $HLP_DEPOSIT_USDC USDC into HLP: $deposit")
        assertEquals(ExchangeOutcome.Accepted(ExchangeResponse.Default), deposit)
      }

      // Revoke by approving a throwaway replacement under the same name; its key is discarded.
      val replacement = freshSigner()
      val revoke = exchange.submit(ApproveAgent(replacement.address, AGENT_NAME), owner)
      println("approveAgent replacement ${replacement.address}: $revoke")
      assertEquals(ExchangeOutcome.Accepted(ExchangeResponse.Default), revoke)
      val agentsAfter = info.extraAgents(owner.address)
      println("Agents after: ${agentsAfter.map { it.name to it.address }}")
      assertTrue(agentsAfter.none { it.address.lowercase() == agent.address })
      assertTrue(agentsAfter.any { it.address.lowercase() == replacement.address })
      val stale =
        exchange.submit(
          CancelOrdersByCloid(listOf(CancelByCloidRequest(btc.id, Cloid.random()))),
          agent,
        )
      println("Deregistered agent cancel: $stale")
      assertIs<ExchangeOutcome.Rejected>(stale)

      // Builder fee: approve 0.01% (10 tenths of a basis point), then revoke with "0%".
      val builder = System.getenv("FLARE_HYPERLIQUID_TEST_BUILDER") ?: DUMMY_BUILDER
      val approveFee = exchange.submit(ApproveBuilderFee(builder, "0.01%"), owner)
      println("approveBuilderFee 0.01%: $approveFee")
      assertEquals(ExchangeOutcome.Accepted(ExchangeResponse.Default), approveFee)
      assertEquals(10, info.maxBuilderFee(owner.address, builder))
      val revokeFee = exchange.submit(ApproveBuilderFee.revoke(builder), owner)
      println("approveBuilderFee 0%: $revokeFee")
      assertEquals(ExchangeOutcome.Accepted(ExchangeResponse.Default), revokeFee)
      assertEquals(0, info.maxBuilderFee(owner.address, builder))
    }
  }

  /** A post-only buy at half the mid, worth just over the minimum, signed by [placer]. */
  private suspend fun placeAndCancel(
    client: HyperliquidClient,
    owner: EvmSigner,
    btc: HyperliquidAsset,
    placer: EvmSigner,
  ) {
    val mid = client.info.allMids().getValue(btc.coin).toHlDecimal()
    val price = btc.roundPrice(mid * "0.5".toHlDecimal(), HlRounding.DOWN)
    val size =
      BigDecimal(ORDER_VALUE_USDC.toString())
        .divide(BigDecimal(price.toString()), btc.szDecimals, RoundingMode.UP)
        .toPlainString()
        .toHlDecimal()
    assertTrue(btc.isValidPrice(price) && btc.isValidSize(size))
    assertTrue(price * size >= HyperliquidAsset.MIN_ORDER_VALUE_USDC)
    val cloid = Cloid.random()
    val order =
      OrderRequest(
        asset = btc.id,
        isBuy = true,
        price = price,
        size = size,
        reduceOnly = false,
        type = OrderType.Limit(TimeInForce.ALO),
        cloid = cloid,
      )
    var canceled = false
    try {
      val placed = client.exchange.submit(PlaceOrders(listOf(order)), placer)
      println("order $size BTC @ $price by ${placer.address}: $placed")
      val response = assertIs<ExchangeOutcome.Accepted>(placed).response
      val statuses = assertIs<ExchangeResponse.Orders>(response).statuses
      val resting = assertIs<OrderStatus.Resting>(statuses.single())
      assertEquals(cloid, resting.cloid)

      val open = client.info.orderStatus(owner.address, cloid)
      assertEquals("open", assertNotNull(open.order).status)
      assertEquals(resting.oid, open.order!!.order.oid)

      val cancel =
        client.exchange.submit(
          CancelOrdersByCloid(listOf(CancelByCloidRequest(btc.id, cloid))),
          placer,
        )
      println("cancelByCloid by ${placer.address}: $cancel")
      assertEquals(
        ExchangeOutcome.Accepted(ExchangeResponse.Cancels(listOf(CancelStatus.Success))),
        cancel,
      )
      canceled = true

      val closed = client.info.orderStatus(owner.address, cloid)
      assertEquals("canceled", assertNotNull(closed.order).status)
    } finally {
      if (!canceled) {
        // Best effort, by the owner, which can cancel whatever its agent placed.
        val cleanup =
          client.exchange.submit(
            CancelOrdersByCloid(listOf(CancelByCloidRequest(btc.id, cloid))),
            owner,
          )
        println("Cleanup cancel of $cloid: $cleanup")
        delay(CLEANUP_SETTLE_MS)
      }
    }
  }

  private companion object {
    const val AGENT_NAME = "flare-test"
    const val CLEANUP_SETTLE_MS = 1_000L

    /** Comfortably above the $10 minimum after rounding. */
    val ORDER_VALUE_USDC: HlDecimal = HlDecimal.of(11)

    /** Testnet HLP (`vaultDetails` names it "Hyperliquidity Provider (HLP)"). */
    const val TESTNET_HLP = "0xa15099a30bbf2e68942d6f4c43d70d04faeab0a0"

    /** The smallest HLP deposit is not documented; the reply says so if 5 USDC is too little. */
    val HLP_DEPOSIT_USDC: HlDecimal = HlDecimal.of(5)

    /** A fixed address, so repeated runs reuse one builder approval slot. */
    const val DUMMY_BUILDER = "0x00000000000000000000000000000000000f1a7e"
  }
}
