package xyz.mcxross.flare.store

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "markets")
data class MarketEntity(
  @PrimaryKey val marketAddress: String,
  val payloadJson: String,
  val updatedAtMs: Long,
)

@Entity(tableName = "candles", primaryKeys = ["marketAddress", "interval", "openTimeMs"])
data class CandleEntity(
  val marketAddress: String,
  val interval: String,
  val openTimeMs: Long,
  val closeTimeMs: Long,
  val open: Double,
  val high: Double,
  val low: Double,
  val close: Double,
  val volume: Double,
)

@Entity(tableName = "selected_markets")
data class SelectedMarketEntity(
  @PrimaryKey val marketAddress: String,
  val selectedAtMs: Long,
)

@Entity(tableName = "pending_transactions")
data class PendingTransactionEntity(
  @PrimaryKey val hash: String,
  val network: String,
  val operation: String,
  val state: String,
  val createdAtMs: Long,
  val updatedAtMs: Long,
)

@Dao
interface MarketCacheDao {
  @Query("SELECT * FROM markets ORDER BY marketAddress")
  fun observeMarkets(): Flow<List<MarketEntity>>

  @Query("SELECT * FROM markets ORDER BY marketAddress") suspend fun markets(): List<MarketEntity>

  @Upsert suspend fun upsertMarkets(markets: List<MarketEntity>)

  @Query("DELETE FROM markets") suspend fun clearMarkets()

  @Query(
    "SELECT * FROM candles WHERE marketAddress = :market AND interval = :interval " +
      "AND openTimeMs BETWEEN :startTimeMs AND :endTimeMs ORDER BY openTimeMs"
  )
  suspend fun candles(
    market: String,
    interval: String,
    startTimeMs: Long,
    endTimeMs: Long,
  ): List<CandleEntity>

  @Upsert suspend fun upsertCandles(candles: List<CandleEntity>)

  @Query("DELETE FROM candles WHERE closeTimeMs < :cutoffMs")
  suspend fun deleteCandlesBefore(cutoffMs: Long)

  @Upsert suspend fun selectMarket(market: SelectedMarketEntity)

  @Query("SELECT * FROM selected_markets ORDER BY selectedAtMs DESC LIMIT 1")
  fun observeSelectedMarket(): Flow<SelectedMarketEntity?>
}

@Dao
interface TransactionJournalDao {
  @Upsert suspend fun upsert(transaction: PendingTransactionEntity)

  @Query("SELECT * FROM pending_transactions ORDER BY createdAtMs")
  fun observePending(): Flow<List<PendingTransactionEntity>>

  @Query("SELECT * FROM pending_transactions ORDER BY createdAtMs")
  suspend fun pending(): List<PendingTransactionEntity>

  @Query("SELECT * FROM pending_transactions WHERE hash = :hash LIMIT 1")
  suspend fun find(hash: String): PendingTransactionEntity?

  @Query(
    "UPDATE pending_transactions SET hash = :newHash, state = :state, " +
      "updatedAtMs = :updatedAtMs WHERE hash = :oldHash"
  )
  suspend fun replaceHash(
    oldHash: String,
    newHash: String,
    state: String,
    updatedAtMs: Long,
  ): Int

  @Query("DELETE FROM pending_transactions WHERE hash = :hash") suspend fun remove(hash: String)
}

@Database(
  entities =
    [
      MarketEntity::class,
      CandleEntity::class,
      SelectedMarketEntity::class,
      PendingTransactionEntity::class,
    ],
  version = 1,
  exportSchema = true,
)
@ConstructedBy(FlareDatabaseConstructor::class)
abstract class FlareDatabase : RoomDatabase() {
  abstract fun marketCacheDao(): MarketCacheDao

  abstract fun transactionJournalDao(): TransactionJournalDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object FlareDatabaseConstructor : RoomDatabaseConstructor<FlareDatabase> {
  override fun initialize(): FlareDatabase
}

fun buildFlareDatabase(builder: RoomDatabase.Builder<FlareDatabase>): FlareDatabase =
  builder.setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()
