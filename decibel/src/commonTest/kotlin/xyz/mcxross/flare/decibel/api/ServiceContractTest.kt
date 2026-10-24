package xyz.mcxross.flare.decibel.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.DecibelConfig

class ServiceContractTest {
  @Test
  fun recentTradesUnwrapsDocumentedPaginationEnvelope() = runTest {
    var query = ""
    val http =
      HttpClient(
        MockEngine { request ->
          query = request.url.encodedQuery
          respondJson(
            """{"items":[{"asset_type":"perp","account":"0x1","market":"0x2","action":"OpenLong","source":"OrderFill","trade_id":"7","size":0.25,"price":50000.0,"is_profit":false,"realized_pnl_amount":0.0,"realized_funding_amount":0.0,"is_rebate":false,"fee_amount":0.1,"order_id":"9","client_order_id":"","transaction_unix_ms":1735758000000,"transaction_version":42,"counter_party_account":"0x3"}],"total_count":1}"""
          )
        }
      )
    val service = DefaultMarketDataService(api(http))

    val trades = service.trades("0x2", limit = 500, offset = 20_000)

    assertEquals("7", trades.single().tradeId)
    assertEquals("market=0x2&limit=200&offset=10000", query)
  }

  @Test
  fun subaccountsDecodeCurrentOpenApiFields() = runTest {
    val http =
      HttpClient(
        MockEngine {
          respondJson(
            """[{"subaccount_address":"0x11","primary_account_address":"0x22","custom_label":"Trading","is_primary":false,"is_active":true}]"""
          )
        }
      )
    val service = DefaultAccountDataService(api(http))

    val subaccounts = service.subaccounts("0x22")

    assertEquals("0x11", subaccounts.single().address)
    assertEquals("Trading", subaccounts.single().name)
  }

  @Test
  fun openOrdersWithoutAssetTypeOmitsParameter() = runTest {
    var query = ""
    val http =
      HttpClient(
        MockEngine { request ->
          query = request.url.encodedQuery
          respondJson("""{"items":[],"total_count":0}""")
        }
      )
    val service = DefaultAccountDataService(api(http))

    service.openOrders("0x22")

    assertEquals("account=0x22&limit=200&offset=0", query)
  }

  @Test
  fun orderHistoryWithSpotAssetTypeSendsParameter() = runTest {
    var query = ""
    val http =
      HttpClient(
        MockEngine { request ->
          query = request.url.encodedQuery
          respondJson("""{"items":[],"total_count":0}""")
        }
      )
    val service = DefaultAccountDataService(api(http))

    service.orderHistory("0x22", assetType = xyz.mcxross.flare.decibel.model.AssetType.SPOT)

    assertEquals("account=0x22&asset_type=spot&limit=100&offset=0", query)
  }

  @Test
  fun tradeHistoryWithoutAssetTypeOmitsParameter() = runTest {
    var query = ""
    val http =
      HttpClient(
        MockEngine { request ->
          query = request.url.encodedQuery
          respondJson("""{"items":[],"total_count":0}""")
        }
      )
    val service = DefaultAccountDataService(api(http))

    service.tradeHistory("0x22")

    assertEquals("account=0x22&limit=100&offset=0", query)
  }

  @Test
  fun portfolioChartDecodesPoints() = runTest {
    val http =
      HttpClient(
        MockEngine {
          respondJson(
            """[{"timestamp":1735758000000,"data_points":1250.5}]"""
          )
        }
      )
    val service = DefaultAccountDataService(api(http))

    val points = service.portfolioChart("0x22", "24h", "account_value")

    assertEquals(1, points.size)
    assertEquals(1250.5, points.first().dataPoints)
  }

  @Test
  fun fundHistoryDecodesDocumentedFundsEnvelope() = runTest {
    var query = ""
    val http =
      HttpClient(
        MockEngine { request ->
          query = request.url.encodedQuery
          respondJson(
            """{"funds":[{"movement_type":"withdrawal","amount":25.0,"balance_after":475.0,"timestamp":1760338800000,"transaction_version":6912345700},{"movement_type":"deposit","amount":500.0,"balance_after":500.0,"timestamp":1760335200000,"transaction_version":6912345678}],"total":2}"""
          )
        }
      )
    val service = DefaultAccountDataService(api(http))

    val history = service.fundHistory("0x22")

    assertEquals("account=0x22&limit=100&offset=0", query)
    assertEquals(2, history.items.size)
    assertEquals(2, history.totalCount)
    assertEquals("withdrawal", history.items.first().movementType)
    assertEquals(25.0, history.items.first().amount)
    assertEquals("deposit", history.items.last().movementType)
    assertEquals(6_912_345_678L, history.items.last().transactionVersion)
  }

  @Test
  fun streakAndAmpsDecodeFields() = runTest {
    var streakQuery = ""
    val httpStreak =
      HttpClient(
        MockEngine { request ->
          streakQuery = request.url.encodedQuery
          // `/streaks/account` answers in camelCase, unlike the rest of the API.
          respondJson(
            """{"owner":"0x22","currentStreak":5,"graceDaysAvailable":2,"graceDaysUsed":1,""" +
              """"qualifyingDates":["2026-09-20","2026-09-21"],"streakAmpsEstimate":12.0}"""
          )
        }
      )
    val streakService = DefaultAccountDataService(api(httpStreak))
    val streak = assertNotNull(streakService.streak("0x22"))
    assertEquals(5, streak.streakCount)
    assertEquals(2, streak.graceDaysRemaining)
    assertEquals(listOf("2026-09-20", "2026-09-21"), streak.qualifyingDates)
    // It is scoped by owner, not by account: the proxy rejects it otherwise.
    assertEquals("owner=0x22", streakQuery)

    val httpAmps =
      HttpClient(
        MockEngine {
          respondJson(
            """{"total_amps":1500.0,"trading_amps":1000.0,"referral_amps":500.0,"daily_amps":50.0,"rank":42}"""
          )
        }
      )
    val ampsService = DefaultAccountDataService(api(httpAmps))
    val amps = ampsService.amps("0xowner")
    assertEquals(1500.0, amps.totalAmps)
    assertEquals(42, amps.rank)
  }

  @Test
  fun referralCodeDecode() = runTest {
    val http =
      HttpClient(
        MockEngine {
          respondJson(
            """{"referral_code":"FLARE2026","is_valid":true,"is_active":true}"""
          )
        }
      )
    val service = DefaultAccountDataService(api(http))
    val info = service.verifyReferralCode("FLARE2026")
    assertEquals("FLARE2026", info.code)
    assertTrue(info.valid)
    assertTrue(info.active)
  }

  @Test
  fun unavailableRewardsDoNotBecomeDefaultBalancesOrTiers() = runTest {
    val missing = DefaultAccountDataService(api(HttpClient(MockEngine {
      respondJson("{}", HttpStatusCode.NotFound)
    })))
    assertNull(missing.streak("0x22"))
    assertNull(missing.tier("0x22"))
    assertNull(missing.referrerStats("0x22"))
    assertNull(missing.accountReferral("0x22"))
    val denied = DefaultAccountDataService(api(HttpClient(MockEngine {
      respondJson("{}", HttpStatusCode.Forbidden)
    })))
    assertFailsWith<DecibelApiError> { denied.amps("0xowner") }
    assertFailsWith<DecibelApiError> { denied.streak("0x22") }
    assertFailsWith<DecibelApiError> { denied.tier("0x22") }
    assertFailsWith<DecibelApiError> { denied.referrerStats("0x22") }
    assertFailsWith<DecibelApiError> { denied.accountReferral("0x22") }
  }

  @Test
  fun referralReadsCarryTheOwnerInThePath() = runTest {
    val paths = mutableListOf<String>()
    val http =
      HttpClient(
        MockEngine { request ->
          paths += request.url.encodedPath
          if ("referrals/stats/" in request.url.encodedPath) {
            respondJson(
              """{"referrer_account":"0x22","total_referrals":3,"total_codes_created":1,""" +
                """"is_affiliate":false,"codes":[],"volume_threshold_met":false}"""
            )
          } else {
            respondJson(
              """{"account":"0x22","referrer_account":"0x33","referral_code":"FRIEND",""" +
                """"is_affiliate_referral":false,"referred_at_ms":1735758000000,"is_active":true}"""
            )
          }
        }
      )
    val service = DefaultAccountDataService(api(http))

    val stats = assertNotNull(service.referrerStats("0x22"))
    assertEquals(3, stats.totalReferrals)
    assertTrue(stats.codes.isEmpty())
    assertFalse(stats.volumeThresholdMet)
    val referral = assertNotNull(service.accountReferral("0x22"))
    assertEquals("FRIEND", referral.referralCode)
    assertEquals("0x33", referral.referrerAccount)
    // The proxy scopes both by the owner in the path, not by a parameter.
    assertTrue(paths[0].endsWith("/referrals/stats/0x22"), paths[0])
    assertTrue(paths[1].endsWith("/referrals/account/0x22"), paths[1])
  }

  private fun api(client: HttpClient) =
    DecibelApi(
      client,
      DecibelConfig(restBaseUrl = "https://worker.example/decibel"),
      DecibelClient.DefaultJson,
    )
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
  body: String,
  status: HttpStatusCode = HttpStatusCode.OK,
) =
  respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
  )
