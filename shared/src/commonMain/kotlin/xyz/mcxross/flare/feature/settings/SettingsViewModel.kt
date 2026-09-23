package xyz.mcxross.flare.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.TradingStreak
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlarePreferences

data class SettingsUiState(
  val preferences: FlarePreferences = FlarePreferences(),
  val profile: WalletProfile = WalletProfile(),
  val proxyUrl: String = "",
  val defaultBuilderAddress: String = "",
  val revealedSecretLabel: String? = null,
  val revealedSecret: String? = null,
  val delegations: List<Delegation> = emptyList(),
  val delegationsLoaded: Boolean = false,
  val subaccountsByOwner: Map<String, List<Subaccount>> = emptyMap(),
  val creatingSubaccount: Boolean = false,
  val referralCodeInput: String = "",
  val referralRedeemed: Boolean = false,
  val referralMessage: String? = null,
  val streak: TradingStreak? = null,
  val amps: AmpsBreakdown? = null,
  val tier: TierInfo? = null,
  val busy: Boolean = false,
  val error: String? = null,
)

sealed interface SettingsIntent {
  data object LoadDelegations : SettingsIntent

  data object LoadSubaccounts : SettingsIntent

  data class RevokeDelegate(val address: String) : SettingsIntent

  data class SelectProfile(val id: String) : SettingsIntent

  data class SelectSubaccount(val profileId: String, val subaccountAddress: String) : SettingsIntent

  data object CreateSubaccountForActiveProfile : SettingsIntent

  data class SetSlippage(val basisPoints: Int) : SettingsIntent

  data class SetBuilderFeeBps(val basisPoints: Int) : SettingsIntent

  data object ApproveBuilderFee : SettingsIntent

  data object RevokeBuilderFee : SettingsIntent

  data class ChangeReferralCode(val code: String) : SettingsIntent

  data object RedeemReferralCode : SettingsIntent

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
  private val runtime: FlareRuntimeConfig,
) : ViewModel() {
  private val local =
    MutableStateFlow(
      SettingsUiState(
        proxyUrl = runtime.workerBaseUrl,
        defaultBuilderAddress = runtime.defaultBuilderAddress,
      )
    )
  private var secretClearJob: Job? = null

  init {
    viewModelScope.launch {
      val streak = runSuspendCatching { accounts.tradingStreak() }.getOrNull()
      val amps = runSuspendCatching { accounts.ampsBreakdown() }.getOrNull()
      val tier = runSuspendCatching { accounts.tierInfo() }.getOrNull()
      local.update { it.copy(streak = streak, amps = amps, tier = tier) }
      loadSubaccounts()
    }
  }

  val uiState: StateFlow<SettingsUiState> =
    combine(local, preferences.values, wallets.profile) { state, persisted, profile ->
        state.copy(preferences = persisted, profile = profile)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

  fun onIntent(intent: SettingsIntent) {
    when (intent) {
      SettingsIntent.LoadSubaccounts -> loadSubaccounts()
      is SettingsIntent.ChangeReferralCode ->
        local.update { it.copy(referralCodeInput = intent.code, referralMessage = null) }
      SettingsIntent.RedeemReferralCode ->
        launchAction("Redeeming referral code failed.") {
          val code = local.value.referralCodeInput.trim()
          check(code.isNotBlank()) { "Enter a referral code" }
          val success = accounts.redeemReferralCode(code)
          if (success) {
            local.update {
              it.copy(
                referralRedeemed = true,
                referralMessage = "Referral code redeemed successfully!",
              )
            }
          } else {
            local.update {
              it.copy(
                referralMessage = "Invalid referral code or unable to redeem.",
              )
            }
          }
        }
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
          loadSubaccounts()
        }
      is SettingsIntent.SelectSubaccount ->
        launchAction("Selecting trading subaccount failed.") {
          val current = preferences.values.first()
          if (current.activeProfileId != intent.profileId) {
            preferences.activateProfile(intent.profileId)
          }
          val prompt = VaultPrompt("Select trading subaccount", "Confirm your identity")
          accounts.selectTradingAccount(intent.subaccountAddress, prompt)
          runSuspendCatching { accounts.prepareTradingWallet(prompt) }
          loadSubaccounts()
        }
      SettingsIntent.CreateSubaccountForActiveProfile ->
        launchAction("Creating trading subaccount failed.") {
          val current = preferences.values.first()
          val owner = current.ownerAddress ?: error("An owner wallet is required to create a subaccount")
          local.update { it.copy(creatingSubaccount = true) }
          try {
            val before =
              runSuspendCatching { accounts.subaccounts(owner) }.getOrDefault(emptyList()).map { it.address }.toSet()
            val prompt = VaultPrompt("Create trading subaccount", "Confirm your identity")
            val result = accounts.createSubaccount(prompt)
            check(result is TransactionState.Committed) {
              (result as? TransactionState.Failed)?.message ?: "Subaccount transaction failed. Try again."
            }
            var newAddress: String? = null
            repeat(10) {
              delay(1500)
              val after = runSuspendCatching { accounts.subaccounts(owner) }.getOrDefault(emptyList())
              val created = after.firstOrNull { it.address !in before }
              if (created != null) {
                newAddress = created.address
                return@repeat
              }
            }
            val targetAddress = newAddress ?: accounts.subaccounts(owner).lastOrNull()?.address
            if (targetAddress != null) {
              accounts.selectTradingAccount(targetAddress, prompt)
              runSuspendCatching { accounts.prepareTradingWallet(prompt) }
            }
            loadSubaccounts()
          } finally {
            local.update { it.copy(creatingSubaccount = false) }
          }
        }
      is SettingsIntent.SetSlippage ->
        launchAction("Your slippage setting didn’t change.") {
          preferences.setSlippageBps(intent.basisPoints)
        }
      is SettingsIntent.SetBuilderFeeBps ->
        launchAction("Your builder fee setting didn’t change.") {
          val current = preferences.values.first()
          val targetAddress = runtime.defaultBuilderAddress
          if (intent.basisPoints == 0) {
            preferences.setBuilderFeeBps(0)
          } else {
            if (!current.builderApproved) {
              val result =
                accounts.approveBuilderFee(
                  builderAddress = targetAddress,
                  feeBps = 10u,
                  prompt = VaultPrompt("Approve builder support", "Confirm your identity"),
                )
              check(result is TransactionState.Committed) {
                (result as? TransactionState.Failed)?.message
                  ?: "Approving builder support failed. Try again."
              }
            }
            preferences.setBuilderFeeBps(intent.basisPoints)
          }
        }
      SettingsIntent.ApproveBuilderFee ->
        launchAction("Builder support wasn’t approved.") {
          val current = preferences.values.first()
          val targetAddress = runtime.defaultBuilderAddress
          val result =
            accounts.approveBuilderFee(
              builderAddress = targetAddress,
              feeBps = 10u,
              prompt = VaultPrompt("Approve builder support", "Confirm your identity"),
            )
          check(result is TransactionState.Committed) {
            (result as? TransactionState.Failed)?.message
              ?: "Approving builder support failed. Try again."
          }
          preferences.setBuilderFeeBps(if (current.builderFeeBps > 0) current.builderFeeBps else 5)
        }
      SettingsIntent.RevokeBuilderFee ->
        launchAction("Builder support wasn’t revoked.") {
          val current = preferences.values.first()
          val targetAddress = current.builderAddress ?: runtime.defaultBuilderAddress
          val result =
            accounts.revokeBuilderFee(
              builderAddress = targetAddress,
              prompt = VaultPrompt("Revoke builder support", "Confirm your identity"),
            )
          check(result is TransactionState.Committed) {
            (result as? TransactionState.Failed)?.message
              ?: "Revoking builder support failed. Try again."
          }
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

  private fun loadSubaccounts() {
    viewModelScope.launch {
      val profiles = preferences.values.first().profiles
      val map = mutableMapOf<String, List<Subaccount>>()
      for (p in profiles) {
        val owner = p.ownerAddress ?: continue
        runSuspendCatching {
          accounts.subaccounts(owner)
        }.onSuccess { list ->
          map[owner] = list
        }
      }
      local.update { it.copy(subaccountsByOwner = map) }
    }
  }

  private companion object {
    const val SECRET_REVEAL_MS = 60_000L
  }
}
