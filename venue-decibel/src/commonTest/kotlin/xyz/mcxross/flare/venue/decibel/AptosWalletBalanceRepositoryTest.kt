package xyz.mcxross.flare.venue.decibel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.AptosConfig
import xyz.mcxross.kaptos.AptosEndpoints
import xyz.mcxross.kaptos.model.Network
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

class AptosWalletBalanceRepositoryTest {
  @Test
  fun readsNativeAptAndMainnetUsdcAtTheRequestedWalletWithExactPrecision() = runTest {
    val paths = mutableListOf<String>()
    val http =
      HttpClient(
        MockEngine { request ->
          paths += request.url.encodedPath
          val units =
            if (request.url.encodedPath.endsWith("AptosCoin")) ULong.MAX_VALUE.toString()
            else "1234567"
          respond("\"$units\"", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        },
      )
    val aptos = aptos(http)
    try {
      val repository = AptosWalletBalanceRepository(aptos, DecibelDeployment.Mainnet)
      assertEquals("184467440737.09551615", repository.balance("0x1", "APT"))
      assertEquals("1.234567", repository.balance("0x1", "USDC"))
      assertEquals(
        listOf(
          "/v1/accounts/0x1/balance/0x1::aptos_coin::AptosCoin",
          "/v1/accounts/0x1/balance/${DecibelDeployment.Mainnet.usdcMetadataAddress}",
        ),
        paths,
      )
    } finally {
      aptos.close()
      http.close()
    }
  }

  @Test
  fun missingConnectivityIsNeverReportedAsAZeroBalance() = runTest {
    val http =
      HttpClient(
        MockEngine {
          respond(
            """{"message":"Unavailable","error_code":"internal_error"}""",
            status = HttpStatusCode.ServiceUnavailable,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        },
      )
    val aptos = aptos(http)
    try {
      val repository = AptosWalletBalanceRepository(aptos, DecibelDeployment.Mainnet)
      assertFailsWith<IllegalStateException> { repository.balance("0x1", "APT") }
    } finally {
      aptos.close()
      http.close()
    }
  }

  private fun aptos(http: HttpClient) =
    Aptos(
      AptosConfig(
        network = Network.CUSTOM,
        endpoints = AptosEndpoints(fullNode = "https://aptos.test/v1"),
        transport = http.asAptosTransport(),
      ),
    )
}
