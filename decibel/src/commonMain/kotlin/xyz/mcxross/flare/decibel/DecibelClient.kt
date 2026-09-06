package xyz.mcxross.flare.decibel

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.decibel.api.AccountDataService
import xyz.mcxross.flare.decibel.api.DecibelApi
import xyz.mcxross.flare.decibel.api.DecibelStreamService
import xyz.mcxross.flare.decibel.api.DecibelTradingService
import xyz.mcxross.flare.decibel.api.DefaultAccountDataService
import xyz.mcxross.flare.decibel.api.DefaultDecibelStreamService
import xyz.mcxross.flare.decibel.api.DefaultDecibelTradingService
import xyz.mcxross.flare.decibel.api.DefaultMarketDataService
import xyz.mcxross.flare.decibel.api.MarketDataService
import xyz.mcxross.kaptos.Aptos

class DecibelClient(
  httpClient: HttpClient,
  aptosClient: Aptos,
  val config: DecibelConfig = DecibelConfig(),
  json: Json = DefaultJson,
) {
  private val api = DecibelApi(httpClient, config, json)

  val markets: MarketDataService = DefaultMarketDataService(api)
  val accounts: AccountDataService = DefaultAccountDataService(api)
  val stream: DecibelStreamService = DefaultDecibelStreamService(httpClient, config, json)
  val trading: DecibelTradingService = DefaultDecibelTradingService(aptosClient, config.deployment)

  companion object {
    val DefaultJson = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
      isLenient = false
    }
  }
}
