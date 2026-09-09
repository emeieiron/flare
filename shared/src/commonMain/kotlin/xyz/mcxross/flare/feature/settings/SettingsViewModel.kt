package xyz.mcxross.flare.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlarePreferences

data class SettingsUiState(
  val preferences: FlarePreferences = FlarePreferences(),
  val profile: WalletProfile = WalletProfile(),
  val sessionRole: SessionRole? = null,
  val proxyUrl: String = "",
  val revealedSecretLabel: String? = null,
  val revealedSecret: String? = null,
  val busy: Boolean = false,
  val error: String? = null,
)

sealed interface SettingsIntent {
  data class SetSlippage(val basisPoints: Int) : SettingsIntent

  data object ExportOwner : SettingsIntent

  data object ExportApi : SettingsIntent

  data object HideSecret : SettingsIntent

  data object RemoveOwner : SettingsIntent

  data object RemoveApi : SettingsIntent

  data object Lock : SettingsIntent
}

class SettingsViewModel(
  private val preferences: AppPreferences,
  private val wallets: WalletRepository,
  private val accounts: AccountRepository,
  private val sessions: SessionRepository,
  runtime: FlareRuntimeConfig,
) : ViewModel() {
  private val local = MutableStateFlow(SettingsUiState(proxyUrl = runtime.workerBaseUrl))
  private var secretClearJob: Job? = null

  val uiState: StateFlow<SettingsUiState> =
    combine(local, preferences.values, wallets.profile, sessions.status) {
        state,
        persisted,
        profile,
        session ->
        state.copy(
          preferences = persisted,
          profile = profile,
          sessionRole = session?.role,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

  fun onIntent(intent: SettingsIntent) {
    when (intent) {
      is SettingsIntent.SetSlippage ->
        launchAction { preferences.setSlippageBps(intent.basisPoints) }
      SettingsIntent.ExportOwner ->
        reveal("Account recovery details") {
          wallets.exportOwnerMnemonic(
            VaultPrompt(
              "Show account recovery details",
              "This secret controls funding and delegation. Keep it offline.",
            )
          )
        }
      SettingsIntent.ExportApi ->
        reveal("API wallet private key") {
          wallets.exportApiWallet(
            VaultPrompt(
              "Export API wallet",
              "This AIP-80 key can trade for its delegated subaccount.",
            )
          )
        }
      SettingsIntent.HideSecret -> hideSecret()
      SettingsIntent.RemoveOwner ->
        launchAction {
          wallets.removeOwner(
            VaultPrompt(
              "Remove owner wallet",
              "Confirm removal from this device. Your recovery phrase or private key is required to restore it.",
            )
          )
          accounts.disconnect()
        }
      SettingsIntent.RemoveApi ->
        launchAction {
          wallets.removeApiWallet(
            VaultPrompt(
              "Remove API wallet",
              "Confirm removal of the delegated trading key from this device.",
            )
          )
          accounts.disconnect()
        }
      SettingsIntent.Lock -> launchAction { accounts.disconnect() }
    }
  }

  private fun reveal(label: String, read: suspend () -> String) = launchAction {
    val value = read()
    local.update { it.copy(revealedSecretLabel = label, revealedSecret = value) }
    secretClearJob?.cancel()
    secretClearJob = viewModelScope.launch {
      delay(SECRET_REVEAL_MS)
      hideSecret()
    }
  }

  private fun hideSecret() {
    secretClearJob?.cancel()
    local.update { it.copy(revealedSecretLabel = null, revealedSecret = null) }
  }

  private fun launchAction(block: suspend () -> Unit) {
    if (local.value.busy) return
    viewModelScope.launch {
      local.update { it.copy(busy = true, error = null) }
      runSuspendCatching { block() }
        .onFailure { error ->
          local.update { it.copy(error = error.message ?: "The settings action failed") }
        }
      local.update { it.copy(busy = false) }
    }
  }

  private companion object {
    const val SECRET_REVEAL_MS = 60_000L
  }
}
