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
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlarePreferences

data class SettingsUiState(
  val preferences: FlarePreferences = FlarePreferences(),
  val profile: WalletProfile = WalletProfile(),
  val proxyUrl: String = "",
  val revealedSecretLabel: String? = null,
  val revealedSecret: String? = null,
  val delegations: List<Delegation> = emptyList(),
  val delegationsLoaded: Boolean = false,
  val busy: Boolean = false,
  val error: String? = null,
)

sealed interface SettingsIntent {
  data object LoadDelegations : SettingsIntent

  data class RevokeDelegate(val address: String) : SettingsIntent

  data class SelectProfile(val id: String) : SettingsIntent

  data class SetSlippage(val basisPoints: Int) : SettingsIntent

  data object ExportOwner : SettingsIntent

  data object ExportApi : SettingsIntent

  data object HideSecret : SettingsIntent

  data object RemoveOwner : SettingsIntent

  data object RemoveApi : SettingsIntent
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
    combine(local, preferences.values, wallets.profile) { state, persisted, profile ->
        state.copy(preferences = persisted, profile = profile)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

  fun onIntent(intent: SettingsIntent) {
    when (intent) {
      SettingsIntent.LoadDelegations ->
        launchAction("Authorized keys couldn’t be loaded.") {
          local.update { it.copy(delegations = emptyList(), delegationsLoaded = false) }
          val delegates = accounts.delegations()
          local.update { it.copy(delegations = delegates, delegationsLoaded = true) }
        }
      is SettingsIntent.RevokeDelegate ->
        launchAction("Trading access is unchanged.") {
          val result =
            accounts.revokeDelegation(
              intent.address,
              VaultPrompt("Revoke trading access", "Confirm your identity"),
            )
          check(result is TransactionState.Committed) { "Revoking access failed. Try again." }
          val delegates = accounts.delegations()
          local.update { it.copy(delegations = delegates) }
        }
      is SettingsIntent.SelectProfile ->
        launchAction("That account couldn’t be opened.") {
          hideSecret()
          preferences.activateProfile(intent.id)
          accounts.restoreTrading()
        }
      is SettingsIntent.SetSlippage ->
        launchAction("Your slippage setting didn’t change.") {
          preferences.setSlippageBps(intent.basisPoints)
        }
      SettingsIntent.ExportOwner ->
        reveal("Recovery phrase") {
          wallets.exportOwnerMnemonic(VaultPrompt("Show recovery phrase", "Confirm your identity"))
        }
      SettingsIntent.ExportApi ->
        reveal("Trading key") {
          wallets.exportApiWallet(VaultPrompt("Show trading key", "Confirm your identity"))
        }
      SettingsIntent.HideSecret -> hideSecret()
      SettingsIntent.RemoveOwner ->
        launchAction("The owner key is still on this device.") {
          wallets.removeOwner(VaultPrompt("Remove owner key", "Confirm your identity"))
          sessions.invalidate()
          accounts.restoreTrading()
        }
      SettingsIntent.RemoveApi ->
        launchAction("The trading key is still on this device.") {
          wallets.removeApiWallet(VaultPrompt("Remove trading key", "Confirm your identity"))
          sessions.invalidate()
          accounts.restoreTrading()
        }
    }
  }

  private fun reveal(label: String, read: suspend () -> String) =
    launchAction("That secret couldn’t be shown.") {
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

  /** [outcome] states what did not happen, so a failure reads as a result instead of a log line. */
  private fun launchAction(outcome: String, block: suspend () -> Unit) {
    if (local.value.busy) return
    viewModelScope.launch {
      local.update { it.copy(busy = true, error = null) }
      try {
        runSuspendCatching { block() }
          .onFailure { error ->
            local.update { it.copy(error = actionFailure(error.message, outcome)) }
          }
      } finally {
        local.update { it.copy(busy = false) }
      }
    }
  }

  private companion object {
    const val SECRET_REVEAL_MS = 60_000L
  }
}
