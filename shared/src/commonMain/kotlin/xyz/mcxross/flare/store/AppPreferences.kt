package xyz.mcxross.flare.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath
import xyz.mcxross.flare.decibel.DecibelNetwork

data class FlarePreferences(
  val network: DecibelNetwork = DecibelNetwork.TESTNET,
  val chartRange: String = "DAY",
  val chartStyle: String = "CANDLESTICK",
  val showRsi: Boolean = false,
  val showMacd: Boolean = false,
  val slippageBps: Int = 50,
  val onboardingComplete: Boolean = false,
  val selectedMarket: String? = null,
  val favoriteMarkets: Set<String> = emptySet(),
  val installationId: String? = null,
  val ownerAddress: String? = null,
  val apiWalletAddress: String? = null,
  val ownerBackupConfirmed: Boolean = false,
  val selectedSubaccount: String? = null,
)

/**
 * Stores presentation preferences only. Secrets, authentication tokens, and raw account responses
 * must use their dedicated security and repository boundaries instead.
 */
class AppPreferences(private val dataStore: DataStore<Preferences>) {
  val values: Flow<FlarePreferences> =
    dataStore.data.map { preferences ->
      FlarePreferences(
        network =
          preferences[NetworkKey]?.let { value ->
            DecibelNetwork.entries.firstOrNull { it.name == value }
          } ?: DecibelNetwork.TESTNET,
        chartRange = preferences[ChartRangeKey] ?: "DAY",
        chartStyle = preferences[ChartStyleKey] ?: "CANDLESTICK",
        showRsi = preferences[ShowRsiKey] ?: false,
        showMacd = preferences[ShowMacdKey] ?: false,
        slippageBps = (preferences[SlippageBpsKey] ?: 50).coerceIn(1, 1_000),
        onboardingComplete = preferences[OnboardingCompleteKey] ?: false,
        selectedMarket = preferences[SelectedMarketKey],
        favoriteMarkets = preferences[FavoriteMarketsKey].orEmpty(),
        installationId = preferences[InstallationIdKey],
        ownerAddress = preferences[OwnerAddressKey],
        apiWalletAddress = preferences[ApiWalletAddressKey],
        ownerBackupConfirmed = preferences[OwnerBackupConfirmedKey] ?: false,
        selectedSubaccount = preferences[SelectedSubaccountKey],
      )
    }

  val favoriteMarkets: Flow<Set<String>> = values.map { it.favoriteMarkets }

  suspend fun setNetwork(network: DecibelNetwork) {
    dataStore.edit { it[NetworkKey] = network.name }
  }

  suspend fun setChartRange(rangeName: String) {
    dataStore.edit { it[ChartRangeKey] = rangeName }
  }

  suspend fun setChartStyle(styleName: String) {
    dataStore.edit { it[ChartStyleKey] = styleName }
  }

  suspend fun setShowRsi(show: Boolean) {
    dataStore.edit { it[ShowRsiKey] = show }
  }

  suspend fun setShowMacd(show: Boolean) {
    dataStore.edit { it[ShowMacdKey] = show }
  }

  suspend fun setSlippageBps(slippageBps: Int) {
    require(slippageBps in 1..1_000) { "Slippage must be between 1 and 1,000 basis points" }
    dataStore.edit { it[SlippageBpsKey] = slippageBps }
  }

  suspend fun setOnboardingComplete(complete: Boolean) {
    dataStore.edit { it[OnboardingCompleteKey] = complete }
  }

  suspend fun setSelectedMarket(marketAddress: String?) {
    dataStore.edit { preferences ->
      if (marketAddress == null) {
        preferences.remove(SelectedMarketKey)
      } else {
        preferences[SelectedMarketKey] = marketAddress
      }
    }
  }

  suspend fun setFavoriteMarkets(marketAddresses: Set<String>) {
    dataStore.edit { it[FavoriteMarketsKey] = marketAddresses }
  }

  suspend fun installationId(): String {
    values.first().installationId?.let {
      return it
    }
    val candidate = randomInstallationId()
    var resolved = candidate
    dataStore.edit { preferences ->
      resolved = preferences[InstallationIdKey] ?: candidate
      preferences[InstallationIdKey] = resolved
    }
    return resolved
  }

  suspend fun setOwnerWallet(address: String?, backupConfirmed: Boolean) {
    dataStore.edit { preferences ->
      if (address == null) preferences.remove(OwnerAddressKey)
      else preferences[OwnerAddressKey] = address
      preferences[OwnerBackupConfirmedKey] = address != null && backupConfirmed
    }
  }

  suspend fun setOwnerBackupConfirmed(confirmed: Boolean) {
    dataStore.edit { it[OwnerBackupConfirmedKey] = confirmed }
  }

  suspend fun setApiWallet(address: String?) {
    dataStore.edit { preferences ->
      if (address == null) preferences.remove(ApiWalletAddressKey)
      else preferences[ApiWalletAddressKey] = address
    }
  }

  suspend fun setSelectedSubaccount(address: String?) {
    dataStore.edit { preferences ->
      if (address == null) preferences.remove(SelectedSubaccountKey)
      else preferences[SelectedSubaccountKey] = address
    }
  }

  private companion object {
    val NetworkKey = stringPreferencesKey("network")
    val ChartRangeKey = stringPreferencesKey("chart_range")
    val ChartStyleKey = stringPreferencesKey("chart_style")
    val ShowRsiKey = booleanPreferencesKey("show_rsi")
    val ShowMacdKey = booleanPreferencesKey("show_macd")
    val SlippageBpsKey = intPreferencesKey("slippage_bps")
    val OnboardingCompleteKey = booleanPreferencesKey("onboarding_complete")
    val SelectedMarketKey = stringPreferencesKey("selected_market")
    val FavoriteMarketsKey = stringSetPreferencesKey("favorite_markets")
    val InstallationIdKey = stringPreferencesKey("installation_id")
    val OwnerAddressKey = stringPreferencesKey("owner_address")
    val ApiWalletAddressKey = stringPreferencesKey("api_wallet_address")
    val OwnerBackupConfirmedKey = booleanPreferencesKey("owner_backup_confirmed")
    val SelectedSubaccountKey = stringPreferencesKey("selected_subaccount")
  }
}

private fun randomInstallationId(): String {
  val alphabet = "0123456789abcdef"
  return buildString(32) {
    repeat(16) {
      val value = Random.Default.nextInt(256)
      append(alphabet[value ushr 4])
      append(alphabet[value and 0x0f])
    }
  }
}

fun createPreferencesDataStore(producePath: () -> String): DataStore<Preferences> =
  PreferenceDataStoreFactory.createWithPath(produceFile = { producePath().toPath() })

internal const val PreferencesFileName = "flare.preferences_pb"
