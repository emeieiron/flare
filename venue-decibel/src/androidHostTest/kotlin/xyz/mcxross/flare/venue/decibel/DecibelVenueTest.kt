package xyz.mcxross.flare.venue.decibel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.ChartRepository
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.design.TransactionExplorer
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.UnavailableWalletVault
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.AssetCatalogDao
import xyz.mcxross.flare.store.FlareDatabase
import xyz.mcxross.flare.store.FlareDatabaseConstructor
import xyz.mcxross.flare.store.MarketCacheDao
import xyz.mcxross.flare.store.PendingTransactionEntity
import xyz.mcxross.flare.store.TransactionJournalDao
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.kaptos.Aptos

class DecibelVenueTest {
  private val http = HttpClient()

  @AfterTest fun closeClient() = http.close()

  @Test
  fun moduleResolvesEveryDecibelRepository() {
    val venue = DecibelVenue(DecibelRuntimeConfig(workerBaseUrl = "https://worker.example"))
    val koin = koinWith(venue)

    assertEquals("https://worker.example", koin.get<DecibelRuntimeConfig>().workerBaseUrl)
    koin.get<Aptos>()
    koin.get<DecibelClient>()
    listOf(
        WalletRepository::class,
        AptosSigningWallet::class,
        CredentialParser::class,
        SessionRepository::class,
        AptosSessionRepository::class,
        MarketsRepository::class,
        ChartRepository::class,
        MarketDetailsRepository::class,
        GasSponsorshipRepository::class,
        TradingRepository::class,
        AccountRepository::class,
      )
      .forEach { type -> assertTrue(type.isInstance(koin.get<Any>(type)), "$type") }
  }

  @Test
  fun walletAndSessionAreOneInstanceBehindBothInterfaces() {
    val koin = koinWith(DecibelVenue())

    assertSame(koin.get<WalletRepository>(), koin.get<AptosSigningWallet>())
    assertIs<DefaultWalletRepository>(koin.get<WalletRepository>())
    assertSame(koin.get<SessionRepository>(), koin.get<AptosSessionRepository>())
    assertIs<WorkerSessionRepository>(koin.get<SessionRepository>())
    assertSame(DecibelCredentialParser, koin.get<CredentialParser>())
  }

  @Test
  fun descriptorReflectsWhatTheAppDoesOnDecibel() {
    val descriptor = DecibelVenue(network = Network.MAINNET).descriptor

    assertEquals("decibel", descriptor.id)
    assertEquals(Network.MAINNET, descriptor.network)
    with(descriptor.capabilities) {
      assertTrue(
        spot && twap && vaults && subaccounts && rewards && sponsoredFees && networkFeeTopUp &&
          inAppDeposit
      )
    }
  }

  @Test
  fun explorerLinksMatchTheAppsTransactionExplorer() {
    Network.entries.forEach { network ->
      assertEquals(
        TransactionExplorer(network).url("0xabc"),
        DecibelVenue(network = network).descriptor.explorer.transactionUrl("0xabc"),
      )
    }
  }

  @Test
  fun workerMustUseHttpsOutsideLocalDevelopment() {
    assertFailsWith<IllegalArgumentException> {
      DecibelVenue(DecibelRuntimeConfig(workerBaseUrl = "http://worker.example"))
    }
  }

  /**
   * The venue's module next to stand-ins for the common bindings `flareModule` provides. Nothing is
   * opened or sent: resolving only constructs the repositories.
   */
  private fun koinWith(venue: DecibelVenue): Koin {
    val preferences =
      object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
          transform(data.value).also { data.value = it }
      }
    val common = module {
      single { FlareRuntimeConfig(network = venue.descriptor.network) }
      single { http }
      single<FlareDatabase> { UnopenedDatabase() }
      single { get<FlareDatabase>().marketCacheDao() }
      single { AppPreferences(preferences) }
      single { ForegroundWalletVault(UnavailableWalletVault()) }
    }
    return koinApplication { modules(common, venue.module()) }.koin
  }
}

/**
 * Room's generated database, never initialized, so it has no connection or invalidation tracker.
 * Its DAOs build queries lazily; the journal's observer would reach the tracker at once, so an
 * empty journal stands in for it.
 */
private class UnopenedDatabase : FlareDatabase() {
  private val generated = FlareDatabaseConstructor.initialize()

  override fun marketCacheDao(): MarketCacheDao = generated.marketCacheDao()

  override fun transactionJournalDao(): TransactionJournalDao = EmptyJournal

  override fun assetCatalogDao(): AssetCatalogDao = generated.assetCatalogDao()

  override suspend fun clearAllTables() = Unit
}

private object EmptyJournal : TransactionJournalDao {
  override suspend fun upsert(transaction: PendingTransactionEntity) = Unit

  override fun observePending(): Flow<List<PendingTransactionEntity>> = flowOf(emptyList())

  override suspend fun pending(): List<PendingTransactionEntity> = emptyList()

  override suspend fun find(hash: String): PendingTransactionEntity? = null

  override suspend fun replaceHash(
    oldHash: String,
    newHash: String,
    state: String,
    updatedAtMs: Long,
  ): Int = 0

  override suspend fun remove(hash: String) = Unit
}
