package xyz.mcxross.flare.hyperliquid.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.hyperliquid.Fixtures
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.HyperliquidConfig
import xyz.mcxross.flare.hyperliquid.model.CandleInterval
import xyz.mcxross.flare.hyperliquid.model.Cloid
import xyz.mcxross.flare.hyperliquid.model.Side

class InfoServiceTest {
  @Test
  fun postsJsonToInfo() = runTest {
    val harness = Harness(mapOf("meta" to Fixtures.META))

    harness.info.perpMeta()

    val request = harness.requests.single()
    assertEquals(HttpMethod.Post, request.method)
    assertEquals("https://api.hyperliquid.xyz/info", request.url)
    assertEquals(ContentType.Application.Json, request.contentType?.withoutParameters())
    assertEquals("""{"type":"meta"}""", request.body)
  }

  @Test
  fun usesTheConfiguredBaseUrl() = runTest {
    val harness =
      Harness(
        mapOf("allMids" to Fixtures.ALL_MIDS),
        HyperliquidConfig(restBaseUrl = "https://proxy.example/hl"),
      )

    harness.info.allMids()

    assertEquals("https://proxy.example/hl/info", harness.requests.single().url)
  }

  @Test
  fun writesExactRequestBodies() = runTest {
    val harness =
      Harness(
        mapOf(
          "l2Book" to Fixtures.L2_BOOK,
          "candleSnapshot" to Fixtures.CANDLE_SNAPSHOT,
          "orderStatus" to Fixtures.ORDER_STATUS,
          "clearinghouseState" to Fixtures.CLEARINGHOUSE_STATE,
          "userFillsByTime" to Fixtures.USER_FILLS_BY_TIME,
          "userFunding" to Fixtures.USER_FUNDING,
          "vaultDetails" to Fixtures.VAULT_DETAILS,
          "maxBuilderFee" to Fixtures.MAX_BUILDER_FEE,
          "frontendOpenOrders" to Fixtures.FRONTEND_OPEN_ORDERS,
        )
      )
    val info = harness.info
    val mixedCase = "0xABCDEF0123456789abcdef0123456789ABCDEF01"
    val lower = "0xabcdef0123456789abcdef0123456789abcdef01"

    info.l2Book("BTC")
    info.l2Book("BTC", significantFigures = 3)
    info.l2Book("BTC", significantFigures = 5, mantissa = 2)
    info.candles("BTC", CandleInterval.ONE_HOUR, startTimeMs = 1_000L, endTimeMs = 2_000L)
    info.candles("@1035", CandleInterval.ONE_MONTH, startTimeMs = 1L, endTimeMs = 2L)
    info.orderStatus(mixedCase, oid = 62467226234L)
    info.orderStatus(mixedCase, Cloid.parse("0x0123456789ABCDEF0123456789abcdef"))
    info.clearinghouseState(" $mixedCase ")
    info.fills(mixedCase, startTimeMs = 10L)
    info.fills(mixedCase, startTimeMs = 10L, endTimeMs = 20L, aggregateByTime = true)
    info.funding(mixedCase, startTimeMs = 10L, endTimeMs = 20L)
    info.vaultDetails(Fixtures.HLP_VAULT.uppercase().replace("0X", "0x"), user = mixedCase)
    info.vaultDetails(Fixtures.HLP_VAULT)
    info.maxBuilderFee(mixedCase, builder = mixedCase)
    info.openOrders(mixedCase)

    assertEquals(
      listOf(
        """{"type":"l2Book","coin":"BTC"}""",
        """{"type":"l2Book","coin":"BTC","nSigFigs":3}""",
        """{"type":"l2Book","coin":"BTC","nSigFigs":5,"mantissa":2}""",
        """{"type":"candleSnapshot","req":{"coin":"BTC","interval":"1h","startTime":1000,"endTime":2000}}""",
        """{"type":"candleSnapshot","req":{"coin":"@1035","interval":"1M","startTime":1,"endTime":2}}""",
        """{"type":"orderStatus","user":"$lower","oid":62467226234}""",
        """{"type":"orderStatus","user":"$lower","oid":"0x0123456789abcdef0123456789abcdef"}""",
        """{"type":"clearinghouseState","user":"$lower"}""",
        """{"type":"userFillsByTime","user":"$lower","startTime":10}""",
        """{"type":"userFillsByTime","user":"$lower","startTime":10,"endTime":20,"aggregateByTime":true}""",
        """{"type":"userFunding","user":"$lower","startTime":10,"endTime":20}""",
        """{"type":"vaultDetails","vaultAddress":"${Fixtures.HLP_VAULT}","user":"$lower"}""",
        """{"type":"vaultDetails","vaultAddress":"${Fixtures.HLP_VAULT}"}""",
        """{"type":"maxBuilderFee","user":"$lower","builder":"$lower"}""",
        """{"type":"frontendOpenOrders","user":"$lower"}""",
      ),
      harness.requests.map { it.body },
    )
    assertTrue(harness.requests.all { it.url.endsWith("/info") && it.method == HttpMethod.Post })
  }

  @Test
  fun validatesArgumentsBeforeSending() = runTest {
    val harness = Harness(emptyMap())
    val info = harness.info

    assertFailsWith<IllegalArgumentException> { info.l2Book("BTC", significantFigures = 1) }
    assertFailsWith<IllegalArgumentException> { info.l2Book("BTC", significantFigures = 6) }
    assertFailsWith<IllegalArgumentException> { info.l2Book("BTC", mantissa = 2) }
    assertFailsWith<IllegalArgumentException> {
      info.l2Book("BTC", significantFigures = 4, mantissa = 2)
    }
    assertFailsWith<IllegalArgumentException> {
      info.l2Book("BTC", significantFigures = 5, mantissa = 3)
    }
    assertFailsWith<IllegalArgumentException> {
      info.candles("BTC", CandleInterval.ONE_MINUTE, startTimeMs = 2L, endTimeMs = 2L)
    }
    assertFailsWith<IllegalArgumentException> {
      info.fills(Fixtures.USER, startTimeMs = 3L, endTimeMs = 2L)
    }
    listOf(
        "",
        "0x123",
        "1000000000000000000000000000000000000000001",
        "0x10000000000000000000000000000000000000001",
        "0x100000000000000000000000000000000000000g",
      )
      .forEach { address ->
        assertFailsWith<IllegalArgumentException>(address) { info.clearinghouseState(address) }
      }
    assertFailsWith<IllegalArgumentException> { info.maxBuilderFee(Fixtures.USER, "0x1") }
    assertTrue(harness.requests.isEmpty())
  }

  @Test
  fun decodesMarketFixtures() = runTest {
    val info =
      Harness(
          mapOf(
            "meta" to Fixtures.META,
            "spotMeta" to Fixtures.SPOT_META,
            "allMids" to Fixtures.ALL_MIDS,
            "l2Book" to Fixtures.L2_BOOK,
            "candleSnapshot" to Fixtures.CANDLE_SNAPSHOT,
            "recentTrades" to Fixtures.RECENT_TRADES,
          )
        )
        .info

    val meta = info.perpMeta()
    assertEquals(18, meta.universe.size)
    assertEquals("BTC", meta.universe[3].name)
    assertTrue(meta.universe[5].isDelisted)
    assertEquals("strictIsolated", meta.universe[17].marginMode)
    assertTrue(meta.universe[17].isIsolatedOnly)
    assertEquals(0, meta.collateralToken)

    val spot = info.spotMeta()
    assertEquals(listOf(0, 1, 2, 3, 5, 1035), spot.universe.map { it.index })
    assertEquals("PURR/USDC", spot.universe[0].name)
    assertEquals(-2, spot.tokens.first { it.name == "USDC" }.evmContract?.evmExtraWeiDecimals)

    val mids = info.allMids()
    assertEquals("83108.5", mids["BTC"])
    assertEquals("0.5", mids["#102170"])
    assertEquals("39.141", mids["@1035"])

    val book = info.l2Book("BTC")
    assertEquals("83108.0", book.bids.first().px)
    assertEquals("83109.0", book.asks.first().px)
    assertEquals(1791768456652L, book.time)

    val candles = info.candles("BTC", CandleInterval.ONE_HOUR, 0L, 1L)
    assertEquals(3, candles.size)
    assertEquals(1791745200000L, candles.first().openTimeMs)
    assertEquals("82607.0", candles.first().open)
    assertEquals(386, candles.first().trades)
    assertEquals("1h", candles.first().interval)

    val trades = info.recentTrades("BTC")
    assertEquals(Side.BUY, trades.first().side)
    assertEquals(553125847652298L, trades.first().tid)
    assertEquals(listOf(Fixtures.UNIFIED_USER, Fixtures.USER), trades.first().users)
  }

  @Test
  fun decodesPerpMetaAndContextsAlignedByPosition() = runTest {
    val info = Harness(mapOf("metaAndAssetCtxs" to Fixtures.META_AND_ASSET_CTXS)).info

    val result = info.perpMetaAndContexts()

    assertEquals(result.meta.universe.size, result.contexts.size)
    val byCoin = result.contextByCoin
    assertEquals("83109.0", byCoin.getValue("BTC").markPx)
    assertEquals("83108.5", byCoin.getValue("BTC").midPx)
    assertEquals(listOf("82981.9", "83148.3"), byCoin.getValue("BTC").impactPxs)
    val matic = byCoin.getValue("MATIC")
    assertNull(matic.midPx)
    assertNull(matic.premium)
    assertNull(matic.impactPxs)
    assertEquals("0.37035", matic.markPx)
  }

  @Test
  fun decodesSpotMetaAndContextsByCoin() = runTest {
    val info = Harness(mapOf("spotMetaAndAssetCtxs" to Fixtures.SPOT_META_AND_ASSET_CTXS)).info

    val result = info.spotMetaAndContexts()

    assertEquals(6, result.meta.universe.size)
    assertEquals(7, result.contexts.size)
    assertEquals("4.6252", result.contextByCoin.getValue("PURR/USDC").markPx)
    assertNull(result.contextByCoin.getValue("@1").midPx)
    assertTrue("#113990" in result.contextByCoin)
    assertTrue("@7" in result.contextByCoin, "contexts may name pairs the meta omits")
  }

  @Test
  fun rejectsAMetaAndContextsResponseThatIsNotAPair() = runTest {
    val info = Harness(mapOf("metaAndAssetCtxs" to "[${Fixtures.META}]")).info

    val failure = assertFailsWith<HyperliquidApiError> { info.perpMetaAndContexts() }
    assertFalse(failure.retryable)
  }

  @Test
  fun decodesAccountFixtures() = runTest {
    val info =
      Harness(
          mapOf(
            "clearinghouseState" to Fixtures.CLEARINGHOUSE_STATE,
            "spotClearinghouseState" to Fixtures.SPOT_CLEARINGHOUSE_STATE,
            "frontendOpenOrders" to Fixtures.FRONTEND_OPEN_ORDERS,
            "userFillsByTime" to Fixtures.USER_FILLS_BY_TIME,
            "userFunding" to Fixtures.USER_FUNDING,
            "userNonFundingLedgerUpdates" to Fixtures.USER_NON_FUNDING_LEDGER_UPDATES,
            "historicalOrders" to Fixtures.HISTORICAL_ORDERS,
            "portfolio" to Fixtures.PORTFOLIO,
            "userFees" to Fixtures.USER_FEES,
            "userRole" to Fixtures.USER_ROLE,
          )
        )
        .info
    val user = Fixtures.USER

    val state = info.clearinghouseState(user)
    assertEquals("105084.741241", state.marginSummary.accountValue)
    assertEquals("78440.500153", state.withdrawable)
    val btc = state.assetPositions.first().position
    assertEquals("BTC", btc.coin)
    assertEquals("0.76666", btc.szi)
    assertTrue(btc.isLong)
    assertTrue(btc.leverage.isCross)
    assertEquals(5, btc.leverage.value)
    assertNull(btc.liquidationPx)
    assertEquals("-15056.939406", btc.cumFunding?.allTime)

    val spot = info.spotClearinghouseState(user)
    assertEquals("0.00825041", spot.balances.first { it.coin == "USDC" }.total)
    assertEquals(1105, spot.balances.first { it.coin == "HYPE" }.token)

    val order = info.openOrders(user).first()
    assertEquals(Side.BUY, order.side)
    assertEquals("2529.2", order.limitPx)
    assertEquals(62467226234L, order.oid)
    assertEquals("Alo", order.tif)
    assertNull(order.cloid)

    val fill = info.fills(user, 0L).first()
    assertEquals("-0.000349", fill.fee)
    assertEquals("Open Long", fill.dir)
    assertFalse(fill.crossed)
    assertNull(fill.twapId)

    val funding = info.funding(user, 0L).first()
    assertEquals("BTC", funding.delta.coin)
    assertEquals("-0.0006659079", funding.delta.fundingRate)
    assertNull(funding.delta.nSamples)

    val ledger = info.ledgerUpdates(user, 0L)
    assertEquals(
      listOf("internalTransfer", "send", "cStakingTransfer"),
      ledger.map { it.delta.type },
    )
    assertEquals(user, ledger[0].delta.destination)
    assertEquals("999.0", ledger[1].delta.amount)
    assertEquals(1784361909247L, ledger[1].delta.nonce)
    assertEquals(true, ledger[2].delta.isDeposit)

    val history = info.historicalOrders(user)
    assertEquals(listOf("canceled", "open", "badAloPxRejected"), history.map { it.status })

    val portfolio = info.portfolio(user)
    assertEquals(
      setOf("day", "week", "month", "allTime", "perpDay", "perpWeek", "perpMonth", "perpAllTime"),
      portfolio.periods.keys,
    )
    val first = portfolio["day"]!!.accountValueHistory.first()
    assertEquals(1791677160046L, first.timeMs)
    assertEquals("102605.807678", first.value)

    val fees = info.userFees(user)
    assertEquals("0.00035", fees.userCrossRate)
    assertEquals("-0.00003", fees.userAddRate)
    assertEquals("0.00045", fees.feeSchedule?.cross)
    assertEquals(2, fees.dailyUserVlm.size)
    val vip = fees.feeSchedule?.tiers?.vip.orEmpty()
    assertEquals(6, vip.size)
    assertEquals("5000000.0", vip.first().ntlCutoff)
    assertEquals("0.00012", vip.first().add)
    assertEquals("-0.00003", fees.feeSchedule?.tiers?.mm?.last()?.add)

    assertEquals("user", info.userRole(user).role)
  }

  @Test
  fun decodesUnifiedAccountFixtures() = runTest {
    val info =
      Harness(
          mapOf(
            "clearinghouseState" to Fixtures.CLEARINGHOUSE_STATE_UNIFIED,
            "spotClearinghouseState" to Fixtures.SPOT_CLEARINGHOUSE_STATE_UNIFIED,
            "frontendOpenOrders" to Fixtures.FRONTEND_OPEN_ORDERS_EMPTY,
          )
        )
        .info

    val state = info.clearinghouseState(Fixtures.UNIFIED_USER)
    assertEquals("76225.8910252024", state.assetPositions.first().position.liquidationPx)
    val usdc = info.spotClearinghouseState(Fixtures.UNIFIED_USER).balances.first()
    assertEquals(usdc.total, usdc.hold)
    assertTrue(info.openOrders(Fixtures.UNIFIED_USER).isEmpty())
  }

  @Test
  fun readsTheAccountAbstraction() = runTest {
    var mode = Fixtures.USER_ABSTRACTION
    val harness = Harness(responder = { mode })

    val standard = harness.info.userAbstraction(Fixtures.USER)
    assertEquals("default", standard.mode)
    assertFalse(standard.sharesSpotCollateral)
    assertEquals(
      """{"type":"userAbstraction","user":"${Fixtures.USER}"}""",
      harness.requests.single().body,
    )

    mode = Fixtures.USER_ABSTRACTION_UNIFIED
    assertTrue(harness.info.userAbstraction(Fixtures.UNIFIED_USER).sharesSpotCollateral)
  }

  @Test
  fun decodesTriggerOrdersTwapsAndLedgerKinds() = runTest {
    val info =
      Harness(
          mapOf(
            "frontendOpenOrders" to Fixtures.FRONTEND_OPEN_ORDERS_TRIGGERS,
            "twapHistory" to Fixtures.TWAP_HISTORY_ENTRIES,
            "userNonFundingLedgerUpdates" to Fixtures.LEDGER_UPDATES_KINDS,
          )
        )
        .info

    val orders = info.openOrders(Fixtures.USER)
    assertEquals("Take Profit Market", orders[0].orderType)
    assertNull(orders[0].tif)
    assertTrue(orders[2].isPositionTpsl)
    assertEquals("0.0", orders[2].sz)
    assertEquals(listOf("Take Profit Market", "Stop Market"), orders[3].children.map { it.orderType })

    val twaps = info.twapHistory(Fixtures.USER)
    assertEquals(1774612361L, twaps.first().time)
    assertEquals("terminated", twaps.first().status.status)
    assertEquals(14656L, twaps.first().twapId)
    assertEquals(5, twaps.first().state.minutes)

    val ledger = info.ledgerUpdates(Fixtures.USER, 0L)
    assertEquals(
      listOf("deposit", "withdraw", "spotTransfer", "accountClassTransfer", "vaultDeposit", "vaultWithdraw"),
      ledger.map { it.delta.type },
    )
    assertEquals("1.0", ledger[1].delta.fee)
    assertEquals(true, ledger[3].delta.toPerp)
    assertEquals(Fixtures.HLP_VAULT, ledger[4].delta.vault)
    assertEquals("5.0", ledger[5].delta.netWithdrawnUsd)
  }

  @Test
  fun decodesOrderStatusByOidAndCloid() = runTest {
    var known = true
    val harness =
      Harness(
        responder = { if (known) Fixtures.ORDER_STATUS else Fixtures.ORDER_STATUS_UNKNOWN }
      )

    val status = harness.info.orderStatus(Fixtures.USER, oid = 62467226234L)
    assertTrue(status.isKnown)
    assertEquals("canceled", status.order?.status)
    assertEquals(62467226234L, status.order?.order?.oid)
    assertEquals(1791768491623L, status.order?.statusTimestamp)

    known = false
    val unknown = harness.info.orderStatus(Fixtures.USER, Cloid.parse("0x" + "ab".repeat(16)))
    assertFalse(unknown.isKnown)
    assertEquals("unknownOid", unknown.status)
    assertNull(unknown.order)
  }

  @Test
  fun readsTheLeverageSettingOfAPerp() = runTest {
    val harness = Harness(mapOf("activeAssetData" to Fixtures.ACTIVE_ASSET_DATA))

    val data = harness.info.activeAssetData(Fixtures.HLP_VAULT.uppercase().replace("0X", "0x"), "ETH")

    assertEquals("""{"type":"activeAssetData","user":"${Fixtures.HLP_VAULT}","coin":"ETH"}""", harness.requests.single().body)
    assertEquals("ETH", data.coin)
    assertTrue(data.leverage.isCross)
    assertEquals(20, data.leverage.value)
    assertEquals("2559.3", data.markPx)
  }

  @Test
  fun decodesSetupAndVaultFixtures() = runTest {
    var vault = Fixtures.VAULT_DETAILS
    var role = Fixtures.USER_ROLE_MISSING
    val harness =
      Harness(
        responder = { type ->
          when (type) {
            "userRole" -> role
            "extraAgents" -> Fixtures.EXTRA_AGENTS
            "maxBuilderFee" -> Fixtures.MAX_BUILDER_FEE
            "vaultDetails" -> vault
            "userVaultEquities" -> Fixtures.USER_VAULT_EQUITIES
            "twapHistory" -> Fixtures.TWAP_HISTORY
            "subAccounts" -> Fixtures.SUB_ACCOUNTS
            "referral" -> Fixtures.REFERRAL
            else -> error("Unexpected $type")
          }
        }
      )
    val info = harness.info
    val user = Fixtures.USER

    assertTrue(info.userRole(user).isMissing)
    role = Fixtures.USER_ROLE
    assertFalse(info.userRole(user).isMissing)

    val agent = info.extraAgents(user).single()
    assertEquals("grid_bot", agent.name)
    assertEquals(1799909269142L, agent.validUntil)

    assertEquals(0, info.maxBuilderFee(user, Fixtures.UNIFIED_USER))

    val hlp = info.vaultDetails(Fixtures.HLP_VAULT)
    assertEquals(Fixtures.HLP_VAULT, hlp.vaultAddress)
    assertEquals("0.05069748912465818", hlp.apr)
    assertEquals("0.0000006645107064126772", hlp.leaderFraction)
    assertEquals("0.0", hlp.leaderCommission)
    assertEquals("0.0", hlp.maxWithdrawable)
    assertNull(hlp.followerState)
    assertEquals("parent", hlp.relationship?.type)
    assertEquals(7, hlp.relationship?.data?.childAddresses?.size)
    assertEquals(2, hlp.followers.size)
    assertEquals(8, hlp.portfolio?.periods?.size)
    assertTrue(hlp.allowDeposits)

    vault = Fixtures.VAULT_DETAILS_FOLLOWER
    val follower = info.vaultDetails(Fixtures.HLP_VAULT, user).followerState!!
    assertEquals("1070.1020487389", follower.vaultEquity)
    assertEquals(1765926140668L, follower.lockupUntil)

    val equity = info.userVaultEquities(user).single()
    assertEquals(Fixtures.HLP_VAULT, equity.vaultAddress)
    assertEquals("1070.102048", equity.equity)
    assertEquals(1765926140668L, equity.lockedUntilTimestamp)

    assertTrue(info.twapHistory(user).isEmpty())
    assertTrue(info.subAccounts(user).isEmpty(), "null means no sub-accounts")

    val referral = info.referral(user)
    assertNull(referral.referredBy)
    assertEquals("125674407.5799999982", referral.cumVlm)
    assertEquals("needToCreateCode", referral.referrerState?.stage)
  }

  @Test
  fun retriesServerFailureThenSucceeds() = runTest {
    var attempts = 0
    val client =
      HyperliquidClient(
        HttpClient(
          MockEngine {
            attempts += 1
            if (attempts == 1) {
              respond("", HttpStatusCode.ServiceUnavailable)
            } else {
              respondJson(Fixtures.ALL_MIDS)
            }
          }
        )
      )

    assertEquals("83108.5", client.info.allMids()["BTC"])
    assertEquals(2, attempts)
  }

  @Test
  fun givesUpAfterThreeRateLimitedAttempts() = runTest {
    var attempts = 0
    val client =
      HyperliquidClient(
        HttpClient(
          MockEngine {
            attempts += 1
            respond(
              "",
              HttpStatusCode.TooManyRequests,
              headersOf(HttpHeaders.RetryAfter, "1"),
            )
          }
        )
      )

    val failure = assertFailsWith<HyperliquidApiError> { client.info.allMids() }
    assertEquals(429, failure.statusCode)
    assertTrue(failure.retryable)
    assertEquals(3, attempts)
  }

  @Test
  fun doesNotRetryARejectedRequest() = runTest {
    var attempts = 0
    val reason = "Failed to deserialize the JSON body into the target type"
    val client =
      HyperliquidClient(
        HttpClient(
          MockEngine {
            attempts += 1
            respond(
              reason,
              HttpStatusCode.UnprocessableEntity,
              headersOf(HttpHeaders.ContentType, "text/plain; charset=utf-8"),
            )
          }
        )
      )

    val failure = assertFailsWith<HyperliquidApiError> { client.info.perpMeta() }
    assertEquals(422, failure.statusCode)
    assertFalse(failure.retryable)
    assertEquals("Hyperliquid /info failed with HTTP 422: $reason", failure.message)
    assertEquals(1, attempts)
  }

  @Test
  fun reportsAnIncompatibleShapeWithoutRetrying() = runTest {
    var attempts = 0
    val client =
      HyperliquidClient(
        HttpClient(
          MockEngine {
            attempts += 1
            respondJson("""{"universe":"not a list"}""")
          }
        )
      )

    val failure = assertFailsWith<HyperliquidApiError> { client.info.perpMeta() }
    assertFalse(failure.retryable)
    assertTrue(failure.message.startsWith("Hyperliquid returned an incompatible perp metadata"))
    assertEquals(1, attempts)
  }
}

private class RecordedRequest(
  val method: HttpMethod,
  val url: String,
  val contentType: ContentType?,
  val body: String,
)

/** A client whose `/info` answers come from [responder], keyed by the request's `type`. */
private class Harness(
  config: HyperliquidConfig = HyperliquidConfig(),
  responder: (String) -> String,
) {
  constructor(
    responses: Map<String, String>,
    config: HyperliquidConfig = HyperliquidConfig(),
  ) : this(config, { type -> responses[type] ?: error("No fixture for $type") })

  val requests = mutableListOf<RecordedRequest>()

  val info: InfoService =
    HyperliquidClient(
        HttpClient(
          MockEngine { request ->
            val content = request.body as TextContent
            requests +=
              RecordedRequest(
                method = request.method,
                url = request.url.toString(),
                contentType = content.contentType,
                body = content.text,
              )
            val body = Json.parseToJsonElement(content.text).jsonObject
            val type = body.getValue("type").jsonPrimitive.content
            respondJson(responder(type))
          }
        ),
        config,
      )
      .info
}

private fun MockRequestHandleScope.respondJson(body: String) =
  respond(
    content = body,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
  )
