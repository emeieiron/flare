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
