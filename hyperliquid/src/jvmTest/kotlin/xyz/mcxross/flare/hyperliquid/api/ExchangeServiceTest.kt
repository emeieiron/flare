package xyz.mcxross.flare.hyperliquid.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.HyperliquidConfig
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment
import xyz.mcxross.flare.hyperliquid.model.ApproveAgent
import xyz.mcxross.flare.hyperliquid.model.CreateSubAccount
import xyz.mcxross.flare.hyperliquid.model.ExchangeOutcome
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.hyperliquid.model.OrderGrouping
import xyz.mcxross.flare.hyperliquid.model.OrderRequest
import xyz.mcxross.flare.hyperliquid.model.OrderStatus
import xyz.mcxross.flare.hyperliquid.model.OrderType
import xyz.mcxross.flare.hyperliquid.model.PlaceOrders
import xyz.mcxross.flare.hyperliquid.model.TimeInForce
import xyz.mcxross.flare.hyperliquid.model.TpSl
import xyz.mcxross.flare.hyperliquid.model.UsdSend
import xyz.mcxross.flare.hyperliquid.model.toHlDecimal
import xyz.mcxross.flare.hyperliquid.signing.EvmSignature
import xyz.mcxross.flare.hyperliquid.signing.EvmSigner
import xyz.mcxross.flare.hyperliquid.signing.NonceSource
import xyz.mcxross.flare.hyperliquid.signing.SdkVectors
import xyz.mcxross.flare.hyperliquid.signing.VectorCases
import xyz.mcxross.flare.hyperliquid.signing.toHex0x

/**
 * The service with a recording signer: it must hand the signer exactly the Python SDK's EIP-712
 * signing input and post exactly the SDK's body around the signature it gets back.
 */
class ExchangeServiceTest {
  private val signer = RecordingSigner()

  private val order =
    PlaceOrders(
      listOf(
        OrderRequest(
          asset = 1,
          isBuy = true,
          price = "100".toHlDecimal(),
          size = "100".toHlDecimal(),
          reduceOnly = false,
          type = OrderType.Limit(TimeInForce.GTC),
        )
      )
    )

  @Test
  fun signsAndPostsAnOrderAsTheSdkDoes() = runTest {
    // signing_test.py's test_l1_action_signing_order_matches: nonce 0, mainnet.
    val harness = Harness(HyperliquidDeployment.Mainnet, nonces = listOf(0L))

    val outcome = harness.exchange.submit(order, signer)

    assertEquals(SdkVectors.ORDER.mainnetSigningInput, signer.inputs.single())
    val request = harness.requests.single()
    assertEquals("https://api.hyperliquid.xyz/exchange", request.url)
    assertEquals(
      """{"action":{"type":"order","orders":[{"a":1,"b":true,"p":"100","s":"100","r":false,""" +
        """"t":{"limit":{"tif":"Gtc"}}}],"grouping":"na"},"nonce":0,"signature":{""" +
        """"r":"${RecordingSigner.R}","s":"${RecordingSigner.S}","v":27},"vaultAddress":null}""",
      request.body,
    )
    assertEquals(
      ExchangeOutcome.Accepted(ExchangeResponse.Orders(listOf(OrderStatus.Resting(77738308L)))),
      outcome,
    )
  }

  @Test
  fun signsWithVaultAddressAndExpiry() = runTest {
    val case = VectorCases.l1.single { it.name == "orderPositionTpslExpires" }
    val harness = Harness(HyperliquidDeployment.Mainnet, nonces = listOf(case.nonce))
    val action =
      PlaceOrders(
        listOf(
          OrderRequest(
            asset = 0,
            isBuy = false,
            price = "140.25".toHlDecimal(),
            size = "2.5".toHlDecimal(),
            reduceOnly = true,
            type = OrderType.Trigger("150".toHlDecimal(), isMarket = true, TpSl.STOP_LOSS),
          )
        ),
        grouping = OrderGrouping.POSITION_TPSL,
      )

    val signed =
      harness.exchange.sign(
        action,
        signer,
        vaultAddress = VectorCases.VAULT.uppercase().replace("0X", "0x"),
        expiresAfter = case.expiresAfter,
      )

    assertEquals(case.vector.mainnetSigningInput, signer.inputs.single())
    assertEquals(
      """"vaultAddress":"${VectorCases.VAULT}","expiresAfter":${case.expiresAfter}}""",
      signed.body().toString().substringAfter("\"v\":27},"),
    )
    assertEquals(
      "SignedExchangeRequest(type=order, signer=${SdkVectors.ADDRESS}, nonce=${case.nonce})",
      signed.toString(),
    )
    assertFalse(signed.toString().contains(RecordingSigner.R))
  }

  @Test
  fun refusesAVaultAddressWhereTheSdkSignsWithout() = runTest {
    val harness = Harness(HyperliquidDeployment.Mainnet, nonces = listOf(1L))

    assertFailsWith<IllegalArgumentException> {
      harness.exchange.sign(CreateSubAccount("x"), signer, vaultAddress = VectorCases.VAULT)
    }
    assertEquals(0, signer.inputs.size)
  }

  @Test
  fun signsAndPostsUserSignedActionsWithTheirNonce() = runTest {
    // signing_test.py's test_sign_usd_transfer_action, time 1687816341423, signed for mainnet.
    val harness = Harness(HyperliquidDeployment.Mainnet, nonces = listOf(1_687_816_341_423L))

    harness.exchange.submit(UsdSend(VectorCases.DESTINATION, "1".toHlDecimal()), signer)

    assertEquals(SdkVectors.USD_SEND.signingInput, signer.inputs.single())
    assertEquals(
      """{"action":{"type":"usdSend","signatureChainId":"0x66eee","hyperliquidChain":"Mainnet",""" +
        """"destination":"0x5e9ee1089755c3435139848e47e6635505d5a13a","amount":"1",""" +
        """"time":1687816341423},"nonce":1687816341423,"signature":{""" +
        """"r":"${RecordingSigner.R}","s":"${RecordingSigner.S}","v":27},"vaultAddress":null}""",
      harness.requests.single().body,
    )
  }

  @Test
  fun takesANewNonceForEverySignature() = runTest {
    val harness = Harness(HyperliquidDeployment.Mainnet, nonces = listOf(10L, 11L))

    val first = harness.exchange.sign(ApproveAgent(VectorCases.DESTINATION, "flare"), signer)
    val second = harness.exchange.sign(order, signer)

    assertEquals(listOf(10L, 11L), listOf(first.nonce, second.nonce))
  }

  @Test
  fun reportsStatusErrAsRejected() = runTest {
    val harness =
      Harness(
        HyperliquidDeployment.Mainnet,
        reply = Reply(body = """{"status":"err","response":"Bad nonce"}"""),
      )

    assertEquals(ExchangeOutcome.Rejected("Bad nonce"), harness.exchange.submit(order, signer))
  }

  @Test
  fun reportsClientErrorsAsRejected() = runTest {
    val harness =
      Harness(
        HyperliquidDeployment.Mainnet,
        reply = Reply(HttpStatusCode.UnprocessableEntity, "Failed to deserialize the JSON body"),
      )

    assertIs<ExchangeOutcome.Rejected>(harness.exchange.submit(order, signer))
    assertEquals(1, harness.requests.size)
  }

  @Test
  fun reportsServerErrorsAsUnknownWithoutRetrying() = runTest {
    val harness =
      Harness(
        HyperliquidDeployment.Mainnet,
        reply = Reply(HttpStatusCode.BadGateway, "bad gateway"),
      )

    assertIs<ExchangeOutcome.Unknown>(harness.exchange.submit(order, signer))
    assertEquals(1, harness.requests.size)
  }

  @Test
  fun reportsTransportFailuresAsUnknown() = runTest {
    val harness = Harness(HyperliquidDeployment.Mainnet, reply = null)

    val outcome = harness.exchange.submit(order, signer)

    assertIs<ExchangeOutcome.Unknown>(outcome)
    assertFalse(outcome.reason.contains(RecordingSigner.R))
    assertEquals(1, harness.requests.size)
  }

  @Test
  fun reportsNonJsonRepliesAsUnknown() = runTest {
    val harness = Harness(HyperliquidDeployment.Mainnet, reply = Reply(body = "<html>oops</html>"))

    assertIs<ExchangeOutcome.Unknown>(harness.exchange.submit(order, signer))
  }

  /** Records each signing input (as hex) and returns a fixed signature. */
  private class RecordingSigner : EvmSigner {
    val inputs = mutableListOf<String>()

    override val address: String = SdkVectors.ADDRESS

    override suspend fun signTypedData(signingInput: ByteArray): EvmSignature {
      inputs += signingInput.toHex0x()
      return EvmSignature(R, S, 27)
    }

    companion object {
      val R = "0x" + "1".repeat(64)
      val S = "0x" + "2".repeat(64)
    }
  }

  private class Reply(
    val status: HttpStatusCode = HttpStatusCode.OK,
    val body: String =
      """{"status":"ok","response":{"type":"order",""" +
        """"data":{"statuses":[{"resting":{"oid":77738308}}]}}}""",
  )

  private class RecordedRequest(val url: String, val body: String)

  /** [reply] `null` fails the connection after recording the request. */
  private class Harness(
    deployment: HyperliquidDeployment,
    nonces: List<Long> = listOf(1L),
    reply: Reply? = Reply(),
  ) {
    val requests = mutableListOf<RecordedRequest>()

    private val remaining = ArrayDeque(nonces)

    val exchange: ExchangeService =
      HyperliquidClient(
          HttpClient(
            MockEngine { request ->
              val body = (request.body as TextContent).text
              requests += RecordedRequest(request.url.toString(), body)
              if (reply == null) throw IOException("connection reset")
              respond(
                reply.body,
                reply.status,
                headersOf(HttpHeaders.ContentType, "application/json"),
              )
            }
          ),
          HyperliquidConfig(deployment),
          nonces = NonceSource { remaining.removeFirst() },
        )
        .exchange
  }
}
