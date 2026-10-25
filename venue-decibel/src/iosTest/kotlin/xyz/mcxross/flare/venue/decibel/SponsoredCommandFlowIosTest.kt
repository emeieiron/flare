package xyz.mcxross.flare.venue.decibel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.SessionStatus
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.model.MarginMode
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.TimeInForce
import xyz.mcxross.flare.decibel.model.ValidatedOrder
import xyz.mcxross.flare.decibel.model.ValidatedTwapOrder
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.TransactionSigner
import xyz.mcxross.kaptos.core.crypto.Ed25519PublicKey
import xyz.mcxross.kaptos.core.crypto.Ed25519Signature
import xyz.mcxross.kaptos.core.crypto.PublicKey
import xyz.mcxross.kaptos.core.crypto.Signature
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.AptosConfig
import xyz.mcxross.kaptos.AptosEndpoints
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.MoveFunction
import xyz.mcxross.kaptos.model.MoveModule
import xyz.mcxross.kaptos.model.MoveModuleBytecode
import xyz.mcxross.kaptos.model.MoveVisibility
import xyz.mcxross.kaptos.model.Network
import xyz.mcxross.kaptos.model.UnsignedTransaction
import xyz.mcxross.kaptos.transaction.authenticator.AccountAuthenticator
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

/**
 * Drives every Decibel command through the real SDK and the Worker client, wired the way
 * [DefaultTradingRepository] wires them: sponsored by default, falling back to self-pay only when
 * the Worker says nothing was submitted.
 */
class SponsoredCommandFlowIosTest {
  @Test
  fun everyCommandIsSponsoredOnTheRouteOfItsSigner() = runTest {
    for ((command, route) in COMMANDS) {
      val f = Fixture(dispatcher()) { respondJson("""{"transactionHash":"$SPONSORED_HASH"}""", HttpStatusCode.OK) }
      try {
        val states = f.run(command)
        assertEquals(ActionState.Committed(SPONSORED_HASH), states.last(), "$command")
        assertEquals(listOf("/gas/sponsor/$route"), f.workerCalls, "$command")
        assertFalse(f.directSubmission, "$command")
      } finally {
        f.close()
      }
    }
  }

  @Test
  fun definiteSponsorshipRejectionsOfferSelfPay() = runTest {
    val cases =
      listOf(
        HttpStatusCode.ServiceUnavailable to """{"code":"gas_station_not_configured"}""",
        HttpStatusCode.ServiceUnavailable to """{"code":"gas_station_credentials_rejected"}""",
        HttpStatusCode.Forbidden to """{"code":"gas_station_transaction_not_allowed"}""",
        HttpStatusCode.Forbidden to """{"error":"Owner authorization required"}""",
        HttpStatusCode.BadRequest to """{"error":"Invalid transactionBytes"}""",
        HttpStatusCode.TooManyRequests to """{"error":"Rate limit exceeded"}""",
      )
    for ((command, _) in COMMANDS) for ((status, body) in cases) {
      val f = Fixture(dispatcher()) { respondJson(body, status) }
      try {
        val failed = assertIs<ActionState.Failed>(f.run(command).last(), "$command $body")
        assertTrue(failed.definitelyNotSubmitted, "$command $body")
        assertNotNull(failed.selfPay, "$command $body")
        assertEquals(1, f.workerCalls.size)
        assertFalse(f.directSubmission)
      } finally {
        f.close()
      }
    }
  }

  @Test
  fun uncertainSponsorshipOutcomesStayPendingWithoutSelfPay() = runTest {
    val cases: List<MockRequestHandleScope.() -> HttpResponseData> =
      listOf(
        { respondJson("""{"error":"Unable to journal sponsored transaction"}""", HttpStatusCode.ServiceUnavailable) },
        { respondJson("""{"code":"gas_station_transaction_not_allowed"}""", HttpStatusCode.ServiceUnavailable) },
        { respondJson("""{"error":"Gas Station request outcome is unknown"}""", HttpStatusCode.BadGateway) },
        { respondJson("""{"error":"timeout"}""", HttpStatusCode.GatewayTimeout) },
        { respondJson("""{"transactionHash":"invalid"}""", HttpStatusCode.OK) },
        { throw RuntimeException("connection reset after submit") },
      )
    for ((command, _) in COMMANDS) for ((index, case) in cases.withIndex()) {
      val f = Fixture(dispatcher(), case)
      try {
        val failed = assertIs<ActionState.Failed>(f.run(command).last(), "$command $index")
        assertFalse(failed.definitelyNotSubmitted, "$command $index")
        assertNull(failed.selfPay, "$command $index")
        assertTrue(failed.reference.orEmpty().startsWith("sponsor:"), "$command $index")
        assertEquals(1, f.workerCalls.size, "$command $index")
        assertFalse(f.directSubmission)
      } finally {
        f.close()
      }
    }
  }
}

private suspend fun dispatcher() = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher

private class Fixture(
  dispatcher: CoroutineDispatcher,
  private val worker: MockRequestHandleScope.() -> HttpResponseData,
) {
  val workerCalls = mutableListOf<String>()
  var directSubmission = false

  private val http =
    HttpClient(
      MockEngine(
        MockEngineConfig().apply {
          this.dispatcher = dispatcher
          addHandler { request -> handle(request) }
        }
      )
    ) {
      install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
  private val aptos =
    Aptos(
      AptosConfig(
        network = Network.TESTNET,
        endpoints = AptosEndpoints(fullNode = "https://fullnode.invalid/v1"),
        transport = http.asAptosTransport(),
      )
    )
  private val client = DecibelClient(http, aptos)
  private val sponsorship =
    WorkerGasSponsorshipRepository(http, FlareRuntimeConfig(), DecibelRuntimeConfig(), Sessions)

  private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
    val path = request.url.encodedPath
    if (path.startsWith("/gas/sponsor/")) {
      workerCalls += path
      return worker()
    }
    val body =
      when {
        path.endsWith("/estimate_gas_price") -> """{"gas_estimate":100}"""
        path.contains("/accounts/") -> """{"sequence_number":"0","authentication_key":"0x1"}"""
        path.endsWith("/transactions/simulate") -> "[${userResponse(SPONSORED_HASH)}]"
        path.endsWith("/transactions") && request.method == HttpMethod.Post -> {
          directSubmission = true
          error("Sponsored commands must never be submitted directly")
        }
        path.contains("/transactions/by_hash/") -> userResponse(path.substringAfterLast('/'))
        else -> error("Unexpected request: $path")
      }
    return respondJson(body, HttpStatusCode.OK)
  }

  suspend fun run(command: DecibelCommand): List<ActionState> {
    check(aptos.transactions.preloadModuleAbis(ENTRY_ABI) is AptosResult.Success)
    check(aptos.transactions.preloadModuleAbis(SPOT_ENTRY_ABI) is AptosResult.Success)
    return client.trading
      .execute(signer = Signer, command = command, externalFeePayer = sponsorship.submitterFor(command))
      .toList()
      .map { it.toActionState() }
  }

  fun close() {
    aptos.close()
    http.close()
  }
}

private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode) =
  respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

private val SPONSORED_HASH = "0x" + "ab".repeat(32)

private fun userResponse(hash: String) =
  """{"type":"user_transaction","version":"1","hash":"$hash","state_change_hash":"$SPONSORED_HASH","event_root_hash":"$SPONSORED_HASH","state_checkpoint_hash":null,"gas_used":"2","success":true,"vm_status":"Executed successfully","accumulator_root_hash":"$SPONSORED_HASH","sender":"0x1","sequence_number":"0","max_gas_amount":"2000000","gas_unit_price":"100","expiration_timestamp_secs":"9999999999","events":[],"timestamp":"1"}"""

private object Signer : TransactionSigner {
  private val auth =
    AccountAuthenticator.Ed25519(
      Ed25519PublicKey(ByteArray(32) { 0x11 }),
      Ed25519Signature(ByteArray(64) { 0x22 }),
    )
  override val accountAddress = AccountAddress.ONE
  override val publicKey: PublicKey = auth.publicKey

  override suspend fun signBytes(message: ByteArray): AptosResult<Signature> =
    AptosResult.Success(auth.signature)

  override suspend fun signText(message: String) = signBytes(message.encodeToByteArray())

  override suspend fun signTransaction(
    transaction: UnsignedTransaction
  ): AptosResult<AccountAuthenticator> = AptosResult.Success(auth)

  override fun verifySignature(message: ByteArray, signature: Signature) = true
}

private object Sessions : SessionRepository {
  override val status =
    kotlinx.coroutines.flow.MutableStateFlow<SessionStatus?>(
      SessionStatus(role = SessionRole.OWNER, expiresAt = Long.MAX_VALUE)
    )

  override fun <T> bind(status: SessionStatus, operation: kotlinx.coroutines.flow.Flow<T>) =
    operation

  override suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt) = error("Not used")

  override suspend fun accessToken() = "test-token"

  override suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt) =
    error("Not used")

  override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt) = error("Not used")

  override suspend fun useAnonymous() = error("Not used")

  override suspend fun invalidate() = Unit
}

private const val OWNER = "owner"
private const val TRADING = "trading"
private const val MAX_U128 = "340282366920938463463374607431768211455"
private val ORDER =
  ValidatedOrder("0x22", OrderSide.BUY, 10uL, 1uL, TimeInForce.GOOD_TILL_CANCELLED, false, null, null, null, null, null, null)

/** Each command and the Worker route its signer uses; the Worker allowlists it on that route only. */
private val COMMANDS: List<Pair<DecibelCommand, String>> =
  listOf(
    DecibelCommand.CreateSubaccount to OWNER,
    DecibelCommand.Deposit("0x11", "0x33", 1uL) to OWNER,
    DecibelCommand.Withdraw("0x11", "0x33", 1uL) to OWNER,
    DecibelCommand.TransferCollateral("0x11", "0x33", "0x66", 1uL) to OWNER,
    DecibelCommand.DelegateTrading("0x11", "0x44") to OWNER,
    DecibelCommand.RevokeDelegation("0x11", "0x44") to OWNER,
    DecibelCommand.ApproveMaxBuilderFee("0x11", "0x55", 500uL) to OWNER,
    DecibelCommand.RevokeMaxBuilderFee("0x11", "0x55") to OWNER,
    DecibelCommand.ApproveMaxSpotBuilderFee("0x11", "0x55", 500uL) to OWNER,
    DecibelCommand.RevokeMaxSpotBuilderFee("0x11", "0x55") to OWNER,
    DecibelCommand.ContributeToVault("0x11", "0x77", "0x33", 1uL) to OWNER,
    DecibelCommand.RedeemFromVault("0x11", "0x77", 1uL) to OWNER,
    DecibelCommand.ConfigureMarket("0x11", "0x22", MarginMode.CROSS, 1u) to TRADING,
    DecibelCommand.PlaceOrder("0x11", ORDER) to TRADING,
    DecibelCommand.PlaceSpotOrder("0x11", ORDER) to TRADING,
    DecibelCommand.PlaceTwapOrder("0x11", ValidatedTwapOrder("0x22", OrderSide.BUY, 10uL)) to TRADING,
    DecibelCommand.CancelOrder("0x11", "0x22", MAX_U128) to TRADING,
    DecibelCommand.CancelSpotOrder("0x11", "0x22", MAX_U128) to TRADING,
    DecibelCommand.CancelTwapOrder("0x11", "0x22", MAX_U128) to TRADING,
    DecibelCommand.SetPositionTpSl("0x11", "0x22", stopLossTrigger = 1uL) to TRADING,
    DecibelCommand.CancelPositionTpSl("0x11", "0x22", "1") to TRADING,
  )

private val PACKAGE = DecibelDeployment.Mainnet.packageAddress
private const val OBJECT = "0x1::object::Object"
private val SUBACCOUNT = "$OBJECT<$PACKAGE::dex_accounts::Subaccount>"
private val PERP = "$OBJECT<$PACKAGE::perp_market::PerpMarket>"
private val SPOT = "$OBJECT<$PACKAGE::spot_market::SpotMarket>"
private const val ASSET = "$OBJECT<0x1::fungible_asset::Metadata>"
private const val OPT_U64 = "0x1::option::Option<u64>"
private const val OPT_ADDRESS = "0x1::option::Option<address>"
private const val OPT_STRING = "0x1::option::Option<0x1::string::String>"

private fun entry(name: String, vararg params: String) =
  MoveFunction(
    name = name,
    visibility = MoveVisibility.PUBLIC,
    isEntry = true,
    isView = false,
    genericTypeParams = emptyList(),
    params = listOf("&signer", *params),
    `return` = emptyList(),
  )

private fun module(name: String, vararg functions: MoveFunction) =
  MoveModuleBytecode(
    bytecode = "0x",
    abi = MoveModule(PACKAGE, name, emptyList(), functions.toList(), emptyList()),
  )

private val ENTRY_ABI =
  module(
    "dex_accounts_entry",
    entry("create_new_subaccount"),
    entry("deposit_to_subaccount_at", "address", ASSET, "u64"),
    entry("withdraw_from_cross_collateral", SUBACCOUNT, ASSET, "u64"),
    entry("delegate_all_trading_to_for_subaccount", "address", "address", OPT_U64),
    entry("revoke_delegation", SUBACCOUNT, "address"),
    entry("approve_max_builder_fee_for_subaccount", SUBACCOUNT, "address", "u64"),
    entry("revoke_max_builder_fee_for_subaccount", SUBACCOUNT, "address"),
    entry("configure_user_settings_for_market", "address", PERP, "bool", "u8"),
    entry(
      "place_order_to_subaccount",
      SUBACCOUNT, PERP, "u64", "u64", "bool", "u8", "bool", OPT_STRING,
      OPT_U64, OPT_U64, OPT_U64, OPT_U64, OPT_U64, OPT_ADDRESS, OPT_U64,
    ),
    entry("cancel_order_to_subaccount", SUBACCOUNT, "u128", PERP),
    entry("cancel_tp_sl_order_for_position", SUBACCOUNT, PERP, "u128"),
    entry(
      "place_tp_sl_order_for_position",
      SUBACCOUNT, PERP, OPT_U64, OPT_U64, OPT_U64, OPT_U64, OPT_U64, OPT_U64, OPT_ADDRESS, OPT_U64,
    ),
    entry(
      "place_twap_order_to_subaccount_v2",
      SUBACCOUNT, PERP, "u64", "bool", "bool", OPT_STRING, "u64", "u64", OPT_ADDRESS, OPT_U64,
    ),
    entry("cancel_twap_orders_to_subaccount", SUBACCOUNT, PERP, "u128"),
    entry("contribute_to_vault", SUBACCOUNT, "address", ASSET, "u64"),
    entry("redeem_from_vault", SUBACCOUNT, "address", "u64"),
  )

private val SPOT_ENTRY_ABI =
  module(
    "dex_accounts_spot_entry",
    entry(
      "place_spot_order_to_subaccount",
      SUBACCOUNT, SPOT, "u64", "u64", "bool", "u8", OPT_ADDRESS, OPT_U64,
    ),
    entry("cancel_spot_order_to_subaccount", SUBACCOUNT, SPOT, "u128"),
    entry("approve_max_spot_builder_fee_for_subaccount", SUBACCOUNT, "address", "u64"),
    entry("revoke_max_spot_builder_fee_for_subaccount", SUBACCOUNT, "address"),
  )
