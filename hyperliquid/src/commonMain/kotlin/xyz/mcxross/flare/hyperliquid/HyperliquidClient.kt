package xyz.mcxross.flare.hyperliquid

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.hyperliquid.api.DefaultInfoService
import xyz.mcxross.flare.hyperliquid.api.DefaultStreamService
import xyz.mcxross.flare.hyperliquid.api.HyperliquidApi
import xyz.mcxross.flare.hyperliquid.api.InfoService
import xyz.mcxross.flare.hyperliquid.api.StreamService

/**
 * Entry point to Hyperliquid's public API. [httpClient] needs the WebSockets plugin installed for
 * [stream]; reads need no credentials.
 */
class HyperliquidClient(
  httpClient: HttpClient,
  val config: HyperliquidConfig = HyperliquidConfig(),
  json: Json = DefaultJson,
) {
  private val api = HyperliquidApi(httpClient, config, json)

  val info: InfoService = DefaultInfoService(api)
  val stream: StreamService = DefaultStreamService(httpClient, config, json)

  companion object {
    val DefaultJson = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      isLenient = false
    }
  }
}
