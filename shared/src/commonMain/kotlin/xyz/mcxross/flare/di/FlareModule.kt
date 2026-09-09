package xyz.mcxross.flare.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room3.RoomDatabase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.DefaultAccountRepository
import xyz.mcxross.flare.data.DefaultChartRepository
import xyz.mcxross.flare.data.DefaultMarketDetailsRepository
import xyz.mcxross.flare.data.DefaultMarketsRepository
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
import xyz.mcxross.flare.feature.markets.MarketsViewModel
import xyz.mcxross.flare.feature.onboarding.OnboardingViewModel
import xyz.mcxross.flare.feature.orders.OrdersViewModel
import xyz.mcxross.flare.feature.portfolio.PortfolioViewModel
import xyz.mcxross.flare.feature.settings.SettingsViewModel
import xyz.mcxross.flare.feature.trade.TradeViewModel
import xyz.mcxross.flare.security.WalletVault
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlareDatabase
import xyz.mcxross.flare.store.buildFlareDatabase
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.AptosConfig
import xyz.mcxross.kaptos.AptosEndpointConfig
import xyz.mcxross.kaptos.AptosEndpoints
import xyz.mcxross.kaptos.model.Network
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

fun flareModule(
  runtime: FlareRuntimeConfig,
  databaseBuilder: RoomDatabase.Builder<FlareDatabase>,
  preferences: DataStore<Preferences>,
  walletVault: WalletVault,
) = module {
  single { runtime }
  single {
    HttpClient {
      install(ContentNegotiation) {
        json(
          Json {
            ignoreUnknownKeys = true
            explicitNulls = false
          }
        )
      }
      install(WebSockets)
    }
  }
  single { buildFlareDatabase(databaseBuilder) }
  single { get<FlareDatabase>().marketCacheDao() }
  single { preferences }
  single { walletVault }
  singleOf(::AppPreferences)
  singleOf(::DefaultWalletRepository) { bind<WalletRepository>() }
  singleOf(::WorkerSessionRepository) { bind<SessionRepository>() }
  single {
    val sessionRepository = get<SessionRepository>()
    Aptos(
      AptosConfig(
        network = Network.CUSTOM,
        transactionDefaults = runtime.transactionDefaults,
        endpoints = AptosEndpoints(fullNode = runtime.aptosFullnodeUrl),
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
    val sessionRepository = get<SessionRepository>()
    DecibelClient(
      httpClient = get(),
      aptosClient = get(),
      config =
        DecibelConfig(
          deployment = DecibelDeployment.forNetwork(runtime.network),
          restBaseUrl = runtime.decibelRestUrl,
          webSocketUrl = runtime.decibelWebSocketUrl,
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
  viewModelOf(::MarketsViewModel)
  viewModelOf(::OnboardingViewModel)
  viewModelOf(::PortfolioViewModel)
  viewModelOf(::OrdersViewModel)
  viewModelOf(::TradeViewModel)
  viewModelOf(::SettingsViewModel)
}
