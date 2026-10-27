package xyz.mcxross.flare.aptos.swap

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosResult

/** Calls only Flare's quote endpoint; no provider key or provider-supplied transaction envelope is used. */
class PanoraSwapProvider(
  private val http: HttpClient,
  private val aptos: Aptos,
  workerBaseUrl: String,
  private val accessToken: suspend () -> String,
  private val origin: String = "flare://mobile",
  private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SwapProvider {
  private val endpoint = workerBaseUrl.trimEnd('/') + "/swap/quote"
  private val json = Json { ignoreUnknownKeys = true }

  init {
    require(workerBaseUrl.startsWith("https://") ||
      Regex("http://(?:127\\.0\\.0\\.1|10\\.0\\.2\\.2)(?::[0-9]{1,5})?/?").matches(workerBaseUrl))
  }

  override suspend fun quote(request: AptosSwapRequest): ValidatedSwapPlan {
    val response = http.post(endpoint) {
      bearerAuth(accessToken())
      header("Origin", origin)
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(request))
    }
    require(response.status.isSuccess()) { "Swap quote unavailable (${response.status.value})" }
    val text = response.bodyAsText()
    require(text.length <= 131_072) { "Swap quote exceeds the size limit" }
    val envelope = json.parseToJsonElement(text).jsonObject
    val echoed = json.decodeFromJsonElement(AptosSwapRequest.serializer(), envelope.getValue("request"))
    require(echoed.kind == request.kind && echoed.amount == request.amount &&
      echoed.slippageBps == request.slippageBps && sameAddress(echoed.owner, request.owner)) {
      "Swap quote does not match the request"
    }
    val expiry = envelope.string("expiresAtMs").toLong()
    require(expiry > now() && expiry <= now() + 30_000) { "Invalid swap quote expiry" }
    val body = envelope.getValue("response").jsonObject
    val from = body.getValue("fromToken").jsonObject
    val to = body.getValue("toToken").jsonObject
    require(sameAddress(from.string("address"), AptosSwapAssets.APT) && from.string("decimals") == "8")
    require(sameAddress(to.string("address"), AptosSwapAssets.USDC) && to.string("decimals") == "6")
    val quotes = body.getValue("quotes").jsonArray
    require(quotes.size == 1) { "Expected one selected swap route" }
    val quote = quotes.single().jsonObject
    require(units(quote.string("slippagePercentage"), 2) == request.slippageBps.toULong())
    val exactOut = request.kind == SwapKind.EXACT_OUTPUT
    val input = units(if (exactOut) quote.string("fromTokenAmount") else body.string("fromTokenAmount"), 8)
    val output = units(if (exactOut) body.string("toTokenAmount") else quote.string("toTokenAmount"), 6)
    val maxInput = if (exactOut) units(quote.string("maxFromTokenAmount"), 8) else input
    val minOutput = if (exactOut) output else units(quote.string("minToTokenAmount"), 6)
    require(input > 0uL && output > 0uL && maxInput >= input && minOutput in 1uL..output)
    require(maxInput <= scaled(input, 10_000 + request.slippageBps, roundUp = true)) {
      "Maximum input exceeds the requested slippage"
    }
    require(minOutput >= scaled(output, 10_000 - request.slippageBps, roundUp = false)) {
      "Minimum output exceeds the requested slippage"
    }
    require((if (exactOut) output else input) == request.amount.toULong()) { "Wrong swap quantity" }
    val feeToken = body.getValue("feeToken").jsonObject
    require(sameAddress(feeToken.string("address"), AptosSwapAssets.USDC)) { "Unsupported fee asset" }
    val fee = units(quote.string("feeTokenAmount"), 6)
    // Until a fee-bearing router version is reviewed, fail closed rather than infer fee semantics.
    require(fee == 0uL) { "Fee-bearing swap routes are not supported yet" }
    val tx = quote.getValue("txData").jsonObject
    val args = PanoraRoute.validate(tx, request, maxInput, minOutput)
    val payload = when (val result = aptos.transactions.entryFunctionPayload(
      function = AptosSwapAssets.ROUTER,
      typeArguments = PanoraRoute.typeArguments,
      arguments = args,
    )) {
      is AptosResult.Success -> result.value
      is AptosResult.Failure -> error("Unable to encode the swap route: ${result.error}")
    }
    val payloadHash = digest(payload.toBcs())
    require(envelope.string("payloadHash") == payloadHash) { "Swap payload encoding mismatch" }
    return ValidatedSwapPlan(
      envelope.string("id"), request, input, output, maxInput, minOutput, fee, expiry,
      envelope.string("authorization"), payload,
    ).also { it.requireCurrent(now(), request.owner) }
  }
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

internal fun sameAddress(left: String, right: String): Boolean =
  AccountAddress.fromString(left) == AccountAddress.fromString(right)

private fun scaled(value: ULong, numerator: Int, roundUp: Boolean): ULong {
  val whole = value / 10_000uL
  val remainder = value % 10_000uL * numerator.toULong()
  val fraction = remainder / 10_000uL + if (roundUp && remainder % 10_000uL != 0uL) 1uL else 0uL
  require(whole <= (ULong.MAX_VALUE - fraction) / numerator.toULong())
  return whole * numerator.toULong() + fraction
}
