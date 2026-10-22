package xyz.mcxross.flare.venue.decibel

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.strategies.StrategyMarketFeed
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.VaultsRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.DecibelConfig
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.Venue
import xyz.mcxross.flare.venue.VaultMinimums
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueChain
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
      chain =
        VenueChain(
          name = "Aptos",
          feeToken = "APT",
          feeTokenDecimals = 8,
          minimumFeeTopUp = 1_000_000uL,
          depositAssets = listOf("USDC", "APT"),
        ),
      capabilities = Capabilities,
      explorer =
        VenueExplorer(
          name = "Aptos Explorer",
          transactionUrlTemplate =
            "https://explorer.aptoslabs.com/txn/${VenueExplorer.REFERENCE}" +
              "?network=${network.name.lowercase()}",
        ),
      proxyUrl = config.workerBaseUrl,
      builderAddress = config.defaultBuilderAddress,
      builderFeeBps = config.defaultBuilderFeeBps.toInt(),
      maxBuilderFeeBps = 10,
      rewardPointsName = "Amps",
      vaultMinimums = VaultMinimums(deposit = 10.0, redemptionShares = 5.0, vaults = "DLP vaults"),
    )

  override fun module(): Module = decibelModule(config)

  companion object {
    /** Everything the app does on Decibel today. Perpetuals and spot share one margin. */
    val Capabilities =
      VenueCapabilities(
        spot = true,
        twap = true,
        subaccounts = true,
        rewards = true,
        sponsoredFees = true,
        networkFeeTopUp = true,
        inAppDeposit = true,
        perpSpotTransfer = false,
      )
  }
}

/**
 * Decibel's repositories: markets, charts, market details, accounts, trading, the wallet, the
 * Worker session and gas sponsorship. They read the common bindings from `flareModule`: the [HttpClient], [FlareRuntimeConfig], storage and
 * the wallet vault.
 */
private fun decibelModule(config: DecibelRuntimeConfig) = module {
  single { config }
  single<CredentialParser> { DecibelCredentialParser }
  // One wallet and one session, each seen through the neutral and the Decibel interface.
  singleOf(::DefaultWalletRepository) {
    bind<WalletRepository>()
    bind<AptosSigningWallet>()
  }
  singleOf(::WorkerSessionRepository) {
    bind<SessionRepository>()
    bind<AptosSessionRepository>()
  }
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
  single<StrategyMarketFeed> { DecibelStrategyMarketFeed(get(), get()) }
  singleOf(::DefaultChartRepository) { bind<ChartRepository>() }
  singleOf(::DefaultMarketDetailsRepository) { bind<MarketDetailsRepository>() }
  singleOf(::WorkerGasSponsorshipRepository) { bind<GasSponsorshipRepository>() }
  singleOf(::DefaultTradingRepository) {
    bind<TradingRepository>()
    bind<DecibelTrading>()
  }
  singleOf(::DefaultAccountRepository) {
    bind<AccountRepository>()
    bind<VaultsRepository>()
  }
}
