package xyz.mcxross.flare.decibel.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.AptosConfig
import xyz.mcxross.kaptos.AptosEndpoints
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.Network
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

class SubaccountServiceTest {
  private val address = "0x923993825adb8c6d1f3b4feba23ab05e01ae2b20708d50a222c8ecf13a81bd3f"

  @Test
  fun activeSubaccount() = runTest {
    assertEquals(SubaccountStatus.ACTIVE, status("[true]"))
  }

  @Test
  fun inactiveSubaccount() = runTest {
    assertEquals(SubaccountStatus.INACTIVE, status("[false]"))
  }

  @Test
  fun abortMeansNotASubaccount() = runTest {
    val abort =
      """{"message":"VMError with status ABORTED at location Module dex_accounts","error_code":"invalid_input","vm_error_code":4016}"""
    assertEquals(SubaccountStatus.NOT_SUBACCOUNT, status(abort, HttpStatusCode.BadRequest))
  }

  @Test
  fun otherFailuresStayUnknown() = runTest {
    val result =
      service("""{"message":"busy","error_code":"internal_error"}""", HttpStatusCode.ServiceUnavailable)
        .status(address)
    assertIs<AptosResult.Failure>(result)
  }

  @Test
  fun delegationsListEachPermissionOfEachKey() = runTest {
    // view_delegated_permissions for a Flare trading account on testnet after delegate_all_trading.
    val body =
      """[{"__variant__":"SortedVectorMap","entries":[{"key":"0x40d8b4894f4632d0304b8f894e94e0d447dcdb596dfd08b0bcc0d26845e6477e","value":{"__variant__":"V1","perms":{"__variant__":"SortedVectorMap","entries":[""" +
        """{"key":{"__variant__":"TradePerpsAllMarkets"},"value":{"__variant__":"UnlimitedVia","authorized_by":"0x234cf440eae9ec89cc8ccb826e03f2f66c1bf1eaaa0f2c39f53afaac967c9e9b"}},""" +
        """{"key":{"__variant__":"TradeVaultTokens"},"value":{"__variant__":"UnlimitedVia","authorized_by":"0x234cf440eae9ec89cc8ccb826e03f2f66c1bf1eaaa0f2c39f53afaac967c9e9b"}},""" +
        """{"key":{"__variant__":"TradeSpotAllMarkets"},"value":{"__variant__":"UnlimitedVia","authorized_by":"0x234cf440eae9ec89cc8ccb826e03f2f66c1bf1eaaa0f2c39f53afaac967c9e9b"}}]}}},""" +
        """{"key":"0x77","value":{"__variant__":"V1","perms":{"__variant__":"SortedVectorMap","entries":[""" +
        """{"key":{"__variant__":"TradePerpsOnMarket","market":{"inner":"0x5d"}},"value":{"__variant__":"UnlimitedUntil","_0":"1736326800"}}]}}}]}]"""
    val delegations =
      assertIs<AptosResult.Success<List<xyz.mcxross.flare.decibel.model.Delegation>>>(
        service(body, HttpStatusCode.OK).delegations(address)
      ).value

    assertEquals(
      listOf("TradePerpsAllMarkets", "TradeVaultTokens", "TradeSpotAllMarkets", "TradePerpsOnMarket"),
      delegations.map { it.permissionType },
    )
    assertEquals("0x40d8b4894f4632d0304b8f894e94e0d447dcdb596dfd08b0bcc0d26845e6477e", delegations.first().delegate)
    assertEquals(null, delegations.first().expirationTimeSeconds)
    assertEquals("0x5d", delegations.last().permissionMarket)
    assertEquals(1_736_326_800L, delegations.last().expirationTimeSeconds)
  }

  private suspend fun status(body: String, code: HttpStatusCode = HttpStatusCode.OK) =
    assertIs<AptosResult.Success<SubaccountStatus>>(service(body, code).status(address)).value

  private fun service(body: String, code: HttpStatusCode): SubaccountService {
    val http =
      HttpClient(
        MockEngine { request ->
          assertEquals("/v1/view", request.url.encodedPath)
          respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
        }
      ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
      }
    val aptos =
      Aptos(
        AptosConfig(
          network = Network.TESTNET,
          endpoints = AptosEndpoints(fullNode = "https://test.invalid/v1"),
          transport = http.asAptosTransport(),
        )
      )
    return DefaultSubaccountService(aptos, DecibelDeployment.Testnet)
  }
}
