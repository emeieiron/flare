package xyz.mcxross.flare.hyperliquid.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.HyperliquidConfig
import xyz.mcxross.flare.hyperliquid.HyperliquidDeployment
import xyz.mcxross.flare.hyperliquid.model.OrderRequest
import xyz.mcxross.flare.hyperliquid.model.OrderType
import xyz.mcxross.flare.hyperliquid.model.PlaceOrders
import xyz.mcxross.flare.hyperliquid.model.TimeInForce
import xyz.mcxross.flare.hyperliquid.model.UsdSend
import xyz.mcxross.flare.hyperliquid.model.toHlDecimal
import xyz.mcxross.flare.hyperliquid.signing.FastKryptoSigner
import xyz.mcxross.flare.hyperliquid.signing.NonceSource
import xyz.mcxross.flare.hyperliquid.signing.SdkVectors
import xyz.mcxross.flare.hyperliquid.signing.VectorCases

/** End to end with [FastKryptoSigner]: the posted body is the Python SDK's, signature included. */
class ExchangeServiceSigningTest {
  private val signer = FastKryptoSigner.fromHex(SdkVectors.PRIVATE_KEY)

  @Test
  fun postsTheSdkOrderBody() = runTest {
    // signing_test.py's test_l1_action_signing_order_matches: nonce 0, mainnet.
    val order =
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

    val body = post(HyperliquidDeployment.Mainnet, nonce = 0L) { submit(order, signer) }

    assertEquals(
      """{"action":{"type":"order","orders":[{"a":1,"b":true,"p":"100","s":"100","r":false,""" +
        """"t":{"limit":{"tif":"Gtc"}}}],"grouping":"na"},"nonce":0,"signature":{""" +
        """"r":"0xd65369825a9df5d80099e513cce430311d7d26ddf477f5b3a33d2806b100d78e",""" +
        """"s":"0x2b54116ff64054968aa237c20ca9ff68000f977c93289157748a3162b6ea940e","v":28},""" +
        """"vaultAddress":null}""",
      body,
    )
  }

  @Test
  fun postsTheSdkUsdSendBody() = runTest {
    // signing_test.py's test_sign_usd_transfer_action, time 1687816341423, signed for mainnet.
    val body =
      post(HyperliquidDeployment.Mainnet, nonce = 1_687_816_341_423L) {
        submit(UsdSend(VectorCases.DESTINATION, "1".toHlDecimal()), signer)
      }

    assertEquals(
      """{"action":{"type":"usdSend","signatureChainId":"0x66eee","hyperliquidChain":"Mainnet",""" +
        """"destination":"0x5e9ee1089755c3435139848e47e6635505d5a13a","amount":"1",""" +
        """"time":1687816341423},"nonce":1687816341423,"signature":{""" +
        """"r":"0x9adbd066e8ae671f06b5d18e51fd2ec11b5443d64b13718028c7ff06905c71fb",""" +
        """"s":"0x1eba4172075f8a8037481400ae86ed56c5e5f672e3db084528a8e3b2daeff976","v":28},""" +
        """"vaultAddress":null}""",
      body,
    )
  }

  @Test
  fun signsWithTheCallersNonceOverTheClients() = runTest {
    val action = UsdSend(VectorCases.DESTINATION, "1".toHlDecimal())
    val fromSource = post(HyperliquidDeployment.Mainnet, nonce = 1_687_816_341_423L) { submit(action, signer) }

    val explicit =
      post(HyperliquidDeployment.Mainnet, nonce = 1L) {
        send(sign(action, signer, nonce = 1_687_816_341_423L))
      }

    assertEquals(fromSource, explicit)
  }

  private suspend fun post(
    deployment: HyperliquidDeployment,
    nonce: Long,
    send: suspend ExchangeService.() -> Unit,
  ): String {
    var body: String? = null
    val exchange =
      HyperliquidClient(
          HttpClient(
            MockEngine { request ->
              body = (request.body as TextContent).text
              respond(
                """{"status":"ok","response":{"type":"default"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
              )
            }
          ),
          HyperliquidConfig(deployment),
          nonces = NonceSource { nonce },
        )
        .exchange
    exchange.send()
    return checkNotNull(body)
  }
}
