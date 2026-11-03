package xyz.mcxross.flare.decibel.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
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
      service(
          """{"message":"busy","error_code":"internal_error"}""",
          HttpStatusCode.ServiceUnavailable,
        )
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
          service(body, HttpStatusCode.OK).delegations(address),
        )
        .value

    assertEquals(
      listOf(
        "TradePerpsAllMarkets",
        "TradeVaultTokens",
        "TradeSpotAllMarkets",
        "TradePerpsOnMarket",
      ),
      delegations.map { it.permissionType },
    )
    assertEquals(
      "0x40d8b4894f4632d0304b8f894e94e0d447dcdb596dfd08b0bcc0d26845e6477e",
      delegations.first().delegate,
    )
    assertEquals(null, delegations.first().expirationTimeSeconds)
    assertEquals("0x5d", delegations.last().permissionMarket)
    assertEquals(1_736_326_800L, delegations.last().expirationTimeSeconds)
  }

  @Test
  fun createdSubaccountComesFromDecibelsOwnEvent() = runTest {
    val decibel = DecibelDeployment.Mainnet.packageAddress
    val other = "0x" + "bb".repeat(32)
    val transaction =
      userTransaction(
        event("0x1::object::TransferEvent", """{"object":"$other"}"""),
        event("0x99::dex_accounts::SubaccountCreatedEvent", """{"subaccount":"$other"}"""),
        event(
          "$decibel::dex_accounts::SubaccountCreatedEvent",
          """{"__variant__":"V1","is_primary":false,"owner":"0x7a","seed":{"vec":[]},"subaccount":"$address"}""",
        ),
      )
    val created =
      service(transaction, HttpStatusCode.OK, VIEW_OR_TRANSACTION, testDispatcher())
        .createdSubaccount(HASH)
    assertEquals(address, assertIs<AptosResult.Success<String?>>(created).value)
  }

  @Test
  fun aTransactionWithoutTheEventNamesNoAccount() = runTest {
    val transaction =
      userTransaction(event("0x1::object::TransferEvent", """{"object":"$address"}"""))
    val created =
      service(transaction, HttpStatusCode.OK, VIEW_OR_TRANSACTION, testDispatcher())
        .createdSubaccount(HASH)
    assertEquals(null, assertIs<AptosResult.Success<String?>>(created).value)
  }

  @Test
  fun builderApprovalReadsEachRegistry() = runTest {
    val builder = "0x" + "d5".repeat(32)
    var requested = ""
    val service = service("""[{"vec":["1000"]}]""", HttpStatusCode.OK, onBody = { requested = it })
    val approved = service.approvedBuilderFee(address, builder, spot = false)
    assertEquals(1_000L, assertIs<AptosResult.Success<Long?>>(approved).value)
    assertTrue(requested.contains("::builder_code_registry::get_approved_max_fee"), requested)

    val none =
      service("""[{"vec":[]}]""", HttpStatusCode.OK).approvedBuilderFee(address, builder, true)
    assertEquals(null, assertIs<AptosResult.Success<Long?>>(none).value)
  }

  private fun event(type: String, data: String) =
    """{"guid":{"creation_number":"0","account_address":"0x0"},"sequence_number":"0","type":"$type","data":$data}"""

  private fun userTransaction(vararg events: String) =
    """{"type":"user_transaction","version":"1","hash":"$HASH","state_change_hash":"$HASH","event_root_hash":"$HASH","state_checkpoint_hash":null,"gas_used":"2","success":true,"vm_status":"Executed successfully","accumulator_root_hash":"$HASH","sender":"0x1","sequence_number":"0","max_gas_amount":"2000000","gas_unit_price":"100","expiration_timestamp_secs":"9999999999","events":[${events.joinToString(",")}],"timestamp":"1"}"""

  private suspend fun status(body: String, code: HttpStatusCode = HttpStatusCode.OK) =
    assertIs<AptosResult.Success<SubaccountStatus>>(service(body, code).status(address)).value

  private fun service(
    body: String,
    code: HttpStatusCode,
    expectedPath: (String) -> Boolean = { it == "/v1/view" },
    // A transaction wait times out in virtual time unless the engine runs on the test's scheduler.
    dispatcher: CoroutineDispatcher? = null,
    onBody: (String) -> Unit = {},
  ): SubaccountService {
    val http =
      HttpClient(
        MockEngine(
          MockEngineConfig().apply {
            dispatcher?.let { this.dispatcher = it }
            addHandler { request ->
              assertTrue(expectedPath(request.url.encodedPath), request.url.encodedPath)
              onBody(
                when (val content = request.body) {
                  is TextContent -> content.text
                  is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                  else -> ""
                },
              )
              respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
            }
          },
        ),
      ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
      }
    val aptos =
      Aptos(
        AptosConfig(
          network = Network.MAINNET,
          endpoints = AptosEndpoints(fullNode = "https://test.invalid/v1"),
          transport = http.asAptosTransport(),
        ),
      )
    return DefaultSubaccountService(aptos, DecibelDeployment.Mainnet)
  }
}

private val HASH = "0x" + "ab".repeat(32)
private val VIEW_OR_TRANSACTION: (String) -> Boolean = { it.startsWith("/v1/transactions/") }

private suspend fun testDispatcher() =
  coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
