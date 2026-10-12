package xyz.mcxross.flare.hyperliquid

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.hyperliquid.api.DefaultExchangeService
import xyz.mcxross.flare.hyperliquid.api.DefaultInfoService
import xyz.mcxross.flare.hyperliquid.api.DefaultStreamService
import xyz.mcxross.flare.hyperliquid.api.ExchangeService
import xyz.mcxross.flare.hyperliquid.api.HyperliquidApi
import xyz.mcxross.flare.hyperliquid.api.InfoService
import xyz.mcxross.flare.hyperliquid.api.StreamService
import xyz.mcxross.flare.hyperliquid.signing.ClockNonceSource
import xyz.mcxross.flare.hyperliquid.signing.NonceSource

/**
 * Entry point to Hyperliquid's API. [httpClient] needs the WebSockets plugin installed for
 * [stream]; reads need no credentials. [exchange] signs with the caller's keys and takes its
 * nonces from [nonces], which must be strictly increasing for each signer.
 */
class HyperliquidClient(
  httpClient: HttpClient,
  val config: HyperliquidConfig = HyperliquidConfig(),
  json: Json = DefaultJson,
  nonces: NonceSource = ClockNonceSource(),
) {
  private val api = HyperliquidApi(httpClient, config, json)

  val info: InfoService = DefaultInfoService(api)
  val stream: StreamService = DefaultStreamService(httpClient, config, json)
  val exchange: ExchangeService = DefaultExchangeService(api, config.deployment, nonces)

  companion object {
    val DefaultJson = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      isLenient = false
    }
  }
}
