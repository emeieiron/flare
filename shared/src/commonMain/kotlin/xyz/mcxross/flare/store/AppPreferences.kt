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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import xyz.mcxross.flare.decibel.DecibelNetwork

@Serializable
data class WithdrawalContinuation(
  val subaccount: String,
  val destination: String,
  val amount: String,
  val withdrawalReference: String? = null,
  val withdrawalCommitted: Boolean = false,
  val transferReference: String? = null,
)

@Serializable
data class AccountProfile(
  val id: String,
  val withdrawal: WithdrawalContinuation? = null,
  val creationReference: String? = null,
  val ownerAddress: String? = null,
  val apiWalletAddress: String? = null,
  val ownerBackupConfirmed: Boolean = false,
  val selectedSubaccount: String? = null,
  val onboardingComplete: Boolean = false,
)

data class FlarePreferences(
  val activeProfileId: String = "legacy",
  val profiles: List<AccountProfile> = emptyList(),
  val network: DecibelNetwork = DecibelNetwork.TESTNET,
  val chartRange: String = "DAY",
  val chartStyle: String = "LINE",
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
        activeProfileId = preferences[ActiveProfileKey] ?: "legacy",
        profiles = profiles(preferences),
        network =
          preferences[NetworkKey]?.let { value ->
            DecibelNetwork.entries.firstOrNull { it.name == value }
          } ?: DecibelNetwork.TESTNET,
        chartRange = preferences[ChartRangeKey] ?: "DAY",
        chartStyle = preferences[ChartStyleKey] ?: "LINE",
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
    dataStore.edit {
      it[OnboardingCompleteKey] = complete
      saveActiveProfile(it)
    }
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
      saveActiveProfile(preferences)
    }
  }

  suspend fun setOwnerBackupConfirmed(confirmed: Boolean) {
    dataStore.edit {
      it[OwnerBackupConfirmedKey] = confirmed
      saveActiveProfile(it)
    }
  }

  suspend fun setApiWallet(address: String?) {
    dataStore.edit { preferences ->
      if (address == null) preferences.remove(ApiWalletAddressKey)
      else preferences[ApiWalletAddressKey] = address
      saveActiveProfile(preferences)
    }
  }

  suspend fun setSelectedSubaccount(address: String?) {
    dataStore.edit { preferences ->
      if (address == null) preferences.remove(SelectedSubaccountKey)
      else preferences[SelectedSubaccountKey] = address
      saveActiveProfile(preferences)
    }
  }

  suspend fun setCreationReference(profileId: String, reference: String?) {
    dataStore.edit { preferences ->
      preferences[ProfilesKey] =
        Json.encodeToString(
          profiles(preferences).map {
            if (it.id == profileId) it.copy(creationReference = reference) else it
          }
        )
    }
  }

  suspend fun setWithdrawal(profileId: String, withdrawal: WithdrawalContinuation?) {
    dataStore.edit { preferences ->
      val updated =
        profiles(preferences).map {
          if (it.id == profileId) it.copy(withdrawal = withdrawal) else it
        }
      preferences[ProfilesKey] = Json.encodeToString(updated)
    }
  }

  suspend fun activateProfile(id: String) {
    dataStore.edit { preferences ->
      saveActiveProfile(preferences)
      val profile = profiles(preferences).first { it.id == id }
      applyProfile(preferences, profile)
    }
  }

  suspend fun registerProfile(profile: AccountProfile) {
    dataStore.edit { preferences ->
      saveActiveProfile(preferences)
      val existing = profiles(preferences)
      val resolved = existing.firstOrNull { it.id == profile.id } ?: profile
      preferences[ProfilesKey] =
        Json.encodeToString(
          if (existing.any { it.id == resolved.id }) existing else existing + resolved
        )
      applyProfile(preferences, resolved)
    }
  }

  private fun profiles(preferences: Preferences): List<AccountProfile> =
    preferences[ProfilesKey]?.let { Json.decodeFromString<List<AccountProfile>>(it) }
      ?: listOfNotNull(
        snapshotProfile(preferences).takeIf {
          it.ownerAddress != null || it.apiWalletAddress != null
        }
      )

  private fun snapshotProfile(preferences: Preferences) =
    AccountProfile(
      id = preferences[ActiveProfileKey] ?: "legacy",
      ownerAddress = preferences[OwnerAddressKey],
      apiWalletAddress = preferences[ApiWalletAddressKey],
      ownerBackupConfirmed = preferences[OwnerBackupConfirmedKey] ?: false,
      selectedSubaccount = preferences[SelectedSubaccountKey],
      onboardingComplete = preferences[OnboardingCompleteKey] ?: false,
    )

  private fun saveActiveProfile(
    preferences: androidx.datastore.preferences.core.MutablePreferences
  ) {
    val snapshot = snapshotProfile(preferences)
    val prior = profiles(preferences).firstOrNull { it.id == snapshot.id }
    val current =
      snapshot.copy(withdrawal = prior?.withdrawal, creationReference = prior?.creationReference)
    val existing = profiles(preferences)
    val valid = current.ownerAddress != null || current.apiWalletAddress != null
    val updated =
      if (existing.any { it.id == current.id }) {
        existing.mapNotNull { if (it.id == current.id) current.takeIf { valid } else it }
      } else existing + listOfNotNull(current.takeIf { valid })
    preferences[ProfilesKey] = Json.encodeToString(updated)
  }

  private fun applyProfile(
    preferences: androidx.datastore.preferences.core.MutablePreferences,
    profile: AccountProfile,
  ) {
    preferences[ActiveProfileKey] = profile.id
    if (profile.ownerAddress == null) preferences.remove(OwnerAddressKey)
    else preferences[OwnerAddressKey] = profile.ownerAddress
    if (profile.apiWalletAddress == null) preferences.remove(ApiWalletAddressKey)
    else preferences[ApiWalletAddressKey] = profile.apiWalletAddress
    if (profile.selectedSubaccount == null) preferences.remove(SelectedSubaccountKey)
    else preferences[SelectedSubaccountKey] = profile.selectedSubaccount
    preferences[OwnerBackupConfirmedKey] = profile.ownerBackupConfirmed
    preferences[OnboardingCompleteKey] = profile.onboardingComplete
  }

  private companion object {
    val ProfilesKey = stringPreferencesKey("account_profiles_v1")
    val ActiveProfileKey = stringPreferencesKey("active_profile_id")
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
