package xyz.mcxross.flare.aptos.swap

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.AptosConfig
import xyz.mcxross.kaptos.AptosEndpoints
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.MoveModuleBytecode
import xyz.mcxross.kaptos.model.Network
import xyz.mcxross.kaptos.model.UserTransactionResponse
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

class PanoraSwapProviderTest {
  private val request = AptosSwapRequest("0x1", SwapKind.EXACT_OUTPUT, "10000000")

  @Test
  fun capturedMainnetRouteMatchesTheWorkerPayloadHash() = runTest {
    fixture { provider ->
      val plan = provider.quote(request)
      assertEquals(FIXTURE_HASH, plan.payloadHash)
      assertEquals(1_470_229_246uL, plan.maximumInput)
      assertEquals(10_000_000uL, plan.minimumOutput)
      assertEquals(FIXTURE_HASH, digest(plan.payload().toBcs()))
      plan.requireCurrent(30_999, "0x1")
      assertFailsWith<IllegalArgumentException> { plan.requireCurrent(31_000, "0x1") }
      assertFailsWith<IllegalArgumentException> { plan.requireCurrent(1000, "0x2") }
    }
  }

  @Test
  fun rejectsTamperingEvenWhenThePayloadIsOtherwiseEncodable() = runTest {
    for ((index, value) in listOf(1 to JsonPrimitive("0x2"), 15 to JsonPrimitive("0xa"),
      17 to JsonPrimitive(1), 18 to JsonPrimitive("9999999999999999"), 0 to JsonPrimitive("0x2"))) {
      fixture(mutate = { envelope ->
        val body = envelope.getValue("response").jsonObject.toMutableMap()
        val quote = body.getValue("quotes").jsonArray.single().jsonObject.toMutableMap()
        val tx = quote.getValue("txData").jsonObject.toMutableMap()
        val args = tx.getValue("arguments").jsonArray.toMutableList()
        args[index] = value
        tx["arguments"] = JsonArray(args)
        quote["txData"] = JsonObject(tx)
        body["quotes"] = JsonArray(listOf(JsonObject(quote)))
        envelope["response"] = JsonObject(body)
      }) { provider -> assertFailsWith<IllegalArgumentException> { provider.quote(request) } }
    }
  }

  @Test
  fun rejectsStaleQuotesAndDifferentEncodingHashes() = runTest {
    fixture(mutate = { it["expiresAtMs"] = JsonPrimitive(999) }) { provider ->
      assertFailsWith<IllegalArgumentException> { provider.quote(request) }
    }
    fixture(mutate = { it["payloadHash"] = JsonPrimitive("0xwrong") }) { provider ->
      assertFailsWith<IllegalArgumentException> { provider.quote(request) }
    }
  }

  @Test
  fun effectsRequireANetReceiptInTheOwnersStore() = runTest {
    fixture { provider ->
      val plan = provider.quote(request)
      val inputStore = primaryStore("0x1", AptosSwapAssets.APT)
      val outputStore = primaryStore("0x1", AptosSwapAssets.USDC)
      val good = result("""[${event("Withdraw", inputStore, "1470229246")},${event("Deposit", outputStore, "10000000")}]""")
      plan.validateEffects(good)
      assertFailsWith<IllegalArgumentException> {
        plan.validateEffects(result("""[${event("Withdraw", inputStore, "1470229246")},${event("Deposit", primaryStore("0x2", AptosSwapAssets.USDC), "10000000")}]"""))
      }
      assertFailsWith<IllegalArgumentException> {
        plan.validateEffects(result("""[${event("Withdraw", inputStore, "1470229246")},${event("Deposit", outputStore, "9999999")}]"""))
      }
      assertFailsWith<IllegalArgumentException> {
        plan.validateEffects(result("""[${event("Withdraw", inputStore, "1470229247")},${event("Deposit", outputStore, "10000000")}]"""))
      }
    }
  }

  @Test
  fun decimalConversionRejectsRoundingAndPreservesU64() {
    assertEquals(ULong.MAX_VALUE, units("184467440737.09551615", 8))
    assertEquals("184467440737.09551615", decimal(ULong.MAX_VALUE, 8))
    for (value in listOf("0.000000001", "184467440737.09551616", "1e8", "-1")) {
      assertFailsWith<IllegalArgumentException> { units(value, 8) }
    }
  }

  private suspend fun fixture(
    mutate: (MutableMap<String, JsonElement>) -> Unit = {},
    block: suspend (PanoraSwapProvider) -> Unit,
  ) {
    val envelope = mutableMapOf<String, JsonElement>(
      "id" to JsonPrimitive("fixture"), "expiresAtMs" to JsonPrimitive(31_000),
      "payloadHash" to JsonPrimitive(FIXTURE_HASH), "authorization" to JsonPrimitive("signed-quote"),
      "request" to Json.encodeToJsonElement(request), "response" to Json.parseToJsonElement(FIXTURE_RESPONSE),
    )
    mutate(envelope)
    val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    val http = HttpClient(MockEngine(MockEngineConfig().apply {
      this.dispatcher = dispatcher
      addHandler { call ->
        assertEquals("/swap/quote", call.url.encodedPath)
        assertEquals("Bearer session", call.headers["Authorization"])
        respond(JsonObject(envelope).toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
      }
    }))
    val aptos = Aptos(AptosConfig(network = Network.CUSTOM,
      endpoints = AptosEndpoints(fullNode = "https://worker/aptos/v1"), transport = http.asAptosTransport()))
    try {
      assertIs<AptosResult.Success<Unit>>(aptos.transactions.preloadModuleAbis(Json.decodeFromString<MoveModuleBytecode>(FIXTURE_ABI)))
      block(PanoraSwapProvider(http, aptos, "https://worker", { "session" }, now = { 1000 }))
    } finally { aptos.close(); http.close() }
  }
}

private fun event(kind: String, store: String, amount: String) =
  """{"guid":{"creation_number":"0","account_address":"0x1"},"type":"0x1::fungible_asset::$kind","data":{"store":"$store","amount":"$amount"}}"""

private fun result(events: String): UserTransactionResponse = Json.decodeFromString("""{
  "type":"user_transaction","version":"1","hash":"0x1","state_change_hash":"0x0",
  "event_root_hash":"0x0","state_checkpoint_hash":null,"gas_used":"100","success":true,
  "vm_status":"Executed successfully","accumulator_root_hash":"0x0","sender":"0x1",
  "sequence_number":"0","max_gas_amount":"50000","gas_unit_price":"100",
  "expiration_timestamp_secs":"30","events":$events,"timestamp":"0"
}""")
