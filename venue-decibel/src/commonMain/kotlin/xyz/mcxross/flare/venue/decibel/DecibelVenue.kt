package xyz.mcxross.flare.venue.decibel

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import xyz.mcxross.flare.core.DecibelRuntimeConfig
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.DefaultAccountRepository
import xyz.mcxross.flare.data.DefaultTradingRepository
import xyz.mcxross.flare.data.DefaultWalletRepository
import xyz.mcxross.flare.data.GasSponsorshipRepository
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.data.WorkerGasSponsorshipRepository
import xyz.mcxross.flare.data.WorkerSessionRepository
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.DecibelConfig
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.Venue
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.VenueExplorer
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.AptosConfig
import xyz.mcxross.kaptos.AptosEndpointConfig
import xyz.mcxross.kaptos.AptosEndpoints
import xyz.mcxross.kaptos.model.Network as AptosNetwork
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

/**
 * Decibel, on Aptos, reached through the Flare Worker: it holds the node and gas credentials and
 * authenticates the app's session.
 */
class DecibelVenue(
  private val config: DecibelRuntimeConfig = DecibelRuntimeConfig(),
  network: Network = Network.TESTNET,
) : Venue {
  override val descriptor: VenueDescriptor =
    VenueDescriptor(
      id = "decibel",
      displayName = "Decibel",
      network = network,
      capabilities = Capabilities,
      explorer =
        VenueExplorer(
          name = "Aptos Explorer",
          transactionUrlTemplate =
            "https://explorer.aptoslabs.com/txn/${VenueExplorer.REFERENCE}" +
              "?network=${network.name.lowercase()}",
        ),
    )

  override fun module(): Module = decibelModule(config)

  companion object {
    /** Everything the app does on Decibel today. */
    val Capabilities =
      VenueCapabilities(
        spot = true,
        twap = true,
        vaults = true,
        subaccounts = true,
        rewards = true,
        sponsoredFees = true,
        networkFeeTopUp = true,
        inAppDeposit = true,
      )
  }
}

/**
 * Decibel's repositories. Markets, charts and market details live in this module; the rest are
 * still implemented in `:shared`. They read the common bindings from `flareModule`: the
 * [HttpClient], [FlareRuntimeConfig], storage and the wallet vault.
 */
private fun decibelModule(config: DecibelRuntimeConfig) = module {
  single { config }
  singleOf(::DefaultWalletRepository) { bind<WalletRepository>() }
  singleOf(::WorkerSessionRepository) { bind<SessionRepository>() }
  single {
    val runtime = get<FlareRuntimeConfig>()
    val sessionRepository = get<SessionRepository>()
    Aptos(
      AptosConfig(
        network = AptosNetwork.CUSTOM,
        transactionDefaults = config.transactionDefaults,
        endpoints = AptosEndpoints(fullNode = config.aptosFullnodeUrl),
        fullNode =
          AptosEndpointConfig(
            requestHeaders = {
              mapOf(
                HttpHeaders.Authorization to "Bearer ${sessionRepository.accessToken()}",
                HttpHeaders.Origin to runtime.appOrigin,
              )
            }
          ),
        transport = get<HttpClient>().asAptosTransport(),
      )
    )
  }
  single {
    val runtime = get<FlareRuntimeConfig>()
    val sessionRepository = get<SessionRepository>()
    DecibelClient(
      httpClient = get(),
      aptosClient = get(),
      config =
        DecibelConfig(
          deployment = DecibelDeployment.forNetwork(runtime.network.toDecibelNetwork()),
          restBaseUrl = config.decibelRestUrl,
          webSocketUrl = config.decibelWebSocketUrl,
          accessToken = { sessionRepository.accessToken() },
        ),
    )
  }
  singleOf(::DefaultMarketsRepository) { bind<MarketsRepository>() }
  singleOf(::DefaultChartRepository) { bind<ChartRepository>() }
  singleOf(::DefaultMarketDetailsRepository) { bind<MarketDetailsRepository>() }
  singleOf(::WorkerGasSponsorshipRepository) { bind<GasSponsorshipRepository>() }
  singleOf(::DefaultTradingRepository) { bind<TradingRepository>() }
  singleOf(::DefaultAccountRepository) { bind<AccountRepository>() }
}
