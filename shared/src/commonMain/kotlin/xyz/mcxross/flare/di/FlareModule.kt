package xyz.mcxross.flare.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room3.RoomDatabase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.onClose
import org.koin.dsl.module
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.AccountSetupGate
import xyz.mcxross.flare.data.AssetCatalogRepository
import xyz.mcxross.flare.data.DefaultAssetCatalogRepository
import xyz.mcxross.flare.feature.markets.MarketsViewModel
import xyz.mcxross.flare.feature.onboarding.OnboardingViewModel
import xyz.mcxross.flare.feature.orders.OrdersViewModel
import xyz.mcxross.flare.feature.portfolio.PortfolioViewModel
import xyz.mcxross.flare.feature.settings.DirectJevKeyChecker
import xyz.mcxross.flare.feature.settings.JevKeyChecker
import xyz.mcxross.flare.feature.settings.JevSettingsViewModel
import xyz.mcxross.flare.feature.settings.SettingsViewModel
import xyz.mcxross.flare.feature.auto.AutoTradingRuntime
import xyz.mcxross.flare.feature.auto.AutoTradingViewModel
import xyz.mcxross.flare.feature.strategies.StrategiesViewModel
import xyz.mcxross.flare.feature.trade.TradeViewModel
import xyz.mcxross.flare.jev.JevClient
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.JevCredentials
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlareDatabase
import xyz.mcxross.flare.store.buildFlareDatabase
import xyz.mcxross.flare.strategies.DecisionModel
import xyz.mcxross.flare.venue.Venue

/**
 * The bindings every Flare build shares: networking, storage, the wallet vault, the asset catalog
 * and the ViewModels. The exchange's repositories come from [Venue.module], loaded alongside.
 */
fun flareModule(
  venue: Venue,
  runtime: FlareRuntimeConfig,
  databaseBuilder: RoomDatabase.Builder<FlareDatabase>,
  preferences: DataStore<Preferences>,
  walletVault: ForegroundWalletVault,
) = module {
  includes(venue.module())
  // Screens read what the venue supports, and how it names things, from its descriptor.
  single { venue.descriptor }
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
  single { get<FlareDatabase>().assetCatalogDao() }
  single { preferences }
  single { walletVault }
  singleOf(::AppPreferences)
  singleOf(::JevCredentials)
  single(named("jev")) {
    HttpClient {
      followRedirects = false
      install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
      }
      install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 10_000
      }
    }
  } onClose { it?.close() }
  single { JevClient(get(named("jev"))) } onClose { it?.close() }
  single<DecisionModel> {
    val credentials = get<JevCredentials>()
    val client = get<JevClient>()
    DecisionModel { query -> credentials.withKey { key -> client.evaluate(query, key) } }
  }
  single<JevKeyChecker> { DirectJevKeyChecker(get(named("jev"))) }
  viewModelOf(::JevSettingsViewModel)
  single { AutoTradingRuntime(get(), get(), get(), get(), get(), get(), get(), get(), get()) } onClose { it?.close() }
  viewModelOf(::AutoTradingViewModel)
  viewModel { StrategiesViewModel(get(), get(), get(), get(), get(), get()) }
  single { AccountSetupGate(get(), get()) }
  singleOf(::DefaultAssetCatalogRepository) { bind<AssetCatalogRepository>() }
  viewModelOf(::MarketsViewModel)
  viewModelOf(::OnboardingViewModel)
  viewModelOf(::PortfolioViewModel)
  viewModelOf(::OrdersViewModel)
  viewModelOf(::TradeViewModel)
  // Only venues with a backend session (Decibel's Worker) bind a SessionRepository.
  viewModel { SettingsViewModel(get(), get(), get(), getOrNull(), get(), get(), get()) }
}
