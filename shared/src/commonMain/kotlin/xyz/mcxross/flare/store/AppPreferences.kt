package xyz.mcxross.flare.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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

/**
 * Identifies the profile migrated from an installation that predates multiple accounts. Its secrets
 * keep their original vault keys, so this identifier must never change.
 */
const val LEGACY_PROFILE_ID = "legacy"

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
  val spotWatchlistSeeded: Boolean = false,
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
      val profiles = preferences.profiles()
      val active = preferences.activeProfile(profiles)
      FlarePreferences(
        activeProfileId = active?.id ?: LEGACY_PROFILE_ID,
        profiles = profiles,
        network =
          preferences[NetworkKey]?.let { value ->
            DecibelNetwork.entries.firstOrNull { it.name == value }
          } ?: DecibelNetwork.TESTNET,
        chartRange = preferences[ChartRangeKey] ?: "DAY",
        chartStyle = preferences[ChartStyleKey] ?: "LINE",
        showRsi = preferences[ShowRsiKey] ?: false,
        showMacd = preferences[ShowMacdKey] ?: false,
        slippageBps = (preferences[SlippageBpsKey] ?: 50).coerceIn(1, 1_000),
        selectedMarket = preferences[SelectedMarketKey],
        favoriteMarkets = preferences[FavoriteMarketsKey].orEmpty(),
        spotWatchlistSeeded = preferences[SpotWatchlistSeededKey] ?: false,
        installationId = preferences[InstallationIdKey],
        ownerAddress = active?.ownerAddress,
        apiWalletAddress = active?.apiWalletAddress,
        ownerBackupConfirmed = active?.ownerBackupConfirmed ?: false,
        selectedSubaccount = active?.selectedSubaccount,
        onboardingComplete = active?.onboardingComplete ?: false,
      )
    }

  val favoriteMarkets: Flow<Set<String>> = values.map { it.favoriteMarkets }
  val spotWatchlistSeeded: Flow<Boolean> = values.map { it.spotWatchlistSeeded }

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

  suspend fun setSelectedMarket(marketAddress: String?) {
    dataStore.edit { preferences ->
      if (marketAddress == null) preferences.remove(SelectedMarketKey)
      else preferences[SelectedMarketKey] = marketAddress
    }
  }

  suspend fun setFavoriteMarkets(marketAddresses: Set<String>) {
    dataStore.edit { it[FavoriteMarketsKey] = marketAddresses }
  }

  suspend fun setSpotWatchlistSeeded(seeded: Boolean) {
    dataStore.edit { it[SpotWatchlistSeededKey] = seeded }
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

  suspend fun setOwnerWallet(address: String?, backupConfirmed: Boolean) = updateActiveProfile {
    it.copy(ownerAddress = address, ownerBackupConfirmed = address != null && backupConfirmed)
  }

  suspend fun setOwnerBackupConfirmed(confirmed: Boolean) = updateActiveProfile {
    it.copy(ownerBackupConfirmed = confirmed)
  }

  suspend fun setApiWallet(address: String?) = updateActiveProfile {
    it.copy(apiWalletAddress = address)
  }

  suspend fun setSelectedSubaccount(address: String?) = updateActiveProfile {
    it.copy(selectedSubaccount = address)
  }

  suspend fun setOnboardingComplete(complete: Boolean) = updateActiveProfile {
    it.copy(onboardingComplete = complete)
  }

  suspend fun setCreationReference(profileId: String, reference: String?) =
    updateProfile(profileId) { it.copy(creationReference = reference) }

  suspend fun setWithdrawal(profileId: String, withdrawal: WithdrawalContinuation?) =
    updateProfile(profileId) { it.copy(withdrawal = withdrawal) }

  /** Switching profiles only changes which stored profile is active; none of them are rewritten. */
  suspend fun activateProfile(id: String) {
    dataStore.edit { preferences ->
      val profiles = preferences.profiles()
      require(profiles.any { it.id == id }) { "Unknown account profile" }
      preferences.writeProfiles(profiles)
      preferences[ActiveProfileKey] = id
    }
  }

  /** Adds [profile] when this device does not know it yet, then makes it the active one. */
  suspend fun registerProfile(profile: AccountProfile) {
    dataStore.edit { preferences ->
      val profiles = preferences.profiles()
      preferences.writeProfiles(
        if (profiles.any { it.id == profile.id }) profiles else profiles + profile
      )
      preferences[ActiveProfileKey] = profile.id
    }
  }

  private suspend fun updateActiveProfile(transform: (AccountProfile) -> AccountProfile) {
    dataStore.edit { preferences ->
      val profiles = preferences.profiles()
      val active = preferences.activeProfile(profiles) ?: AccountProfile(LEGACY_PROFILE_ID)
      preferences[ActiveProfileKey] = active.id
      preferences.writeProfiles(profiles.replacing(transform(active)))
    }
  }

  private suspend fun updateProfile(id: String, transform: (AccountProfile) -> AccountProfile) {
    dataStore.edit { preferences ->
      val profiles = preferences.profiles()
      val profile = profiles.firstOrNull { it.id == id } ?: return@edit
      preferences.writeProfiles(profiles.replacing(transform(profile)))
    }
  }

  /**
   * Profiles are the single source of truth. Installations created before profiles existed are
   * migrated from their flat keys on the first read, and those keys are dropped on the first write.
   */
  private fun Preferences.profiles(): List<AccountProfile> =
    this[ProfilesKey]?.let { Json.decodeFromString<List<AccountProfile>>(it) }
      ?: listOfNotNull(migratedProfile())

  private fun Preferences.migratedProfile(): AccountProfile? {
    val owner = this[OwnerAddressKey]
    val api = this[ApiWalletAddressKey]
    if (owner == null && api == null) return null
    return AccountProfile(
      id = LEGACY_PROFILE_ID,
      ownerAddress = owner,
      apiWalletAddress = api,
      ownerBackupConfirmed = this[OwnerBackupConfirmedKey] ?: false,
      selectedSubaccount = this[SelectedSubaccountKey],
      onboardingComplete = this[OnboardingCompleteKey] ?: false,
    )
  }

  private fun Preferences.activeProfile(profiles: List<AccountProfile>): AccountProfile? =
    profiles.firstOrNull { it.id == this[ActiveProfileKey] } ?: profiles.firstOrNull()

  /** A profile without keys holds nothing worth remembering, so removing both removes it. */
  private fun MutablePreferences.writeProfiles(profiles: List<AccountProfile>) {
    this[ProfilesKey] =
      Json.encodeToString(
        profiles.filter { it.ownerAddress != null || it.apiWalletAddress != null }
      )
    listOf(OwnerAddressKey, ApiWalletAddressKey, SelectedSubaccountKey).forEach(::remove)
    listOf(OwnerBackupConfirmedKey, OnboardingCompleteKey).forEach(::remove)
  }

  private fun List<AccountProfile>.replacing(profile: AccountProfile): List<AccountProfile> =
    if (any { it.id == profile.id }) map { if (it.id == profile.id) profile else it }
    else this + profile

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
    val SpotWatchlistSeededKey = booleanPreferencesKey("spot_watchlist_seeded")
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
