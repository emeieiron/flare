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
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.TradingStreak
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
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
  val withdrawDestination: String = "",
  val withdrawAmount: String = "",
  val withdrawTransaction: TransactionState? = null,
  val withdrawing: Boolean = false,
  val withdrawError: String? = null,
  val busy: Boolean = false,
  /** The product whose builder approval is being written on-chain. */
  val builderPending: AssetType? = null,
  val error: String? = null,
)

sealed interface SettingsIntent {
  data object LoadDelegations : SettingsIntent

  data object LoadSubaccounts : SettingsIntent

  data class RevokeDelegate(val address: String) : SettingsIntent

  data class SelectProfile(val id: String) : SettingsIntent

  data class SelectSubaccount(val profileId: String, val subaccountAddress: String) : SettingsIntent

  data object CreateSubaccountForActiveProfile : SettingsIntent

  data class ChangeWithdrawDestination(val address: String) : SettingsIntent

  data class ChangeWithdrawAmount(val amount: String) : SettingsIntent

  data object SubmitWithdraw : SettingsIntent

  data object DismissWithdraw : SettingsIntent

  data class SetSlippage(val basisPoints: Int) : SettingsIntent

  data class SetConfirmTransactions(val confirm: Boolean) : SettingsIntent

  /** Perpetuals and spot each hold their own rate and on-chain approval. */
  data class SetBuilderFeeBps(val product: AssetType, val basisPoints: Int) : SettingsIntent

  data class RevokeBuilderFee(val product: AssetType) : SettingsIntent

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
      is SettingsIntent.ChangeWithdrawDestination ->
        local.update { it.copy(withdrawDestination = intent.address, withdrawError = null) }
      is SettingsIntent.ChangeWithdrawAmount ->
        local.update {
          it.copy(
            withdrawAmount = intent.amount.filter { c -> c.isDigit() || c == '.' },
            withdrawError = null,
          )
        }
      SettingsIntent.DismissWithdraw ->
        local.update {
          if (it.withdrawing) it else it.copy(
            withdrawDestination = "",
            withdrawAmount = "",
            withdrawTransaction = null,
            withdrawError = null,
            withdrawing = false,
          )
        }
      SettingsIntent.SubmitWithdraw -> {
        if (local.value.withdrawing || local.value.withdrawTransaction is TransactionState.Committed) return
        val dest = local.value.withdrawDestination.trim()
        val amt = local.value.withdrawAmount.trim()
        if (dest.isBlank()) {
          local.update { it.copy(withdrawError = "Enter a recipient Aptos address") }
          return
        }
        if (amt.toDoubleOrNull()?.let { it.isFinite() && it > 0 } != true) {
          local.update { it.copy(withdrawError = "Enter an amount greater than zero") }
          return
        }
        viewModelScope.launch {
          local.update { it.copy(withdrawing = true, withdrawError = null) }
          try {
            val prompt = VaultPrompt("Withdraw USDC", "Confirm your identity")
            accounts.withdrawUsdc(amt, dest, prompt, FeePayment.SPONSORED)
              .collect { tx ->
                local.update { it.copy(withdrawTransaction = tx) }
              }
            when (val terminal = local.value.withdrawTransaction) {
              is TransactionState.Committed -> {
                accounts.refresh()
                loadSubaccounts()
              }
              is TransactionState.Failed -> {
                local.update { it.copy(withdrawError = terminal.message) }
              }
              else -> Unit
            }
          } catch (e: Exception) {
            local.update { it.copy(withdrawError = e.message ?: "Withdrawal failed") }
          } finally {
            local.update { it.copy(withdrawing = false) }
          }
        }
      }
      is SettingsIntent.SetConfirmTransactions ->
        launchAction("Your confirmation setting didn’t change.") {
          // Relaxing protection needs the person present; turning it back on never does.
          if (!intent.confirm) {
            wallets.confirmIdentity(VaultPrompt("Turn off confirmations", "Confirm your identity"))
          }
          preferences.setConfirmTransactions(intent.confirm)
        }
      is SettingsIntent.SetSlippage ->
        launchAction("Your slippage setting didn’t change.") {
          preferences.setSlippageBps(intent.basisPoints)
        }
      is SettingsIntent.SetBuilderFeeBps ->
        launchAction("Your builder fee setting didn’t change.") {
          val current = preferences.values.first()
          val approved =
            if (intent.product == AssetType.PERP) current.builderApproved
            else current.spotBuilderApproved
          // A rate only applies once its product is approved on-chain.
          if (intent.basisPoints > 0 && !approved) approveBuilder(intent.product)
          when (intent.product) {
            AssetType.PERP -> preferences.setBuilderFeeBps(intent.basisPoints)
            AssetType.SPOT -> preferences.setSpotBuilderFeeBps(intent.basisPoints)
          }
        }
      is SettingsIntent.RevokeBuilderFee ->
        launchAction("Builder support wasn’t revoked.") {
          val current = preferences.values.first()
          withBuilderPending(intent.product) {
            val result =
              accounts.revokeBuilderFee(
                builderAddress = current.builderAddress ?: runtime.defaultBuilderAddress,
                prompt = VaultPrompt("Revoke builder support", "Confirm your identity"),
                product = intent.product,
              )
            check(result is TransactionState.Committed) {
              (result as? TransactionState.Failed)?.message
                ?: "Revoking builder support failed. Try again."
            }
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
  /** Approves Flare's builder up to the protocol cap, so later rate changes need no new approval. */
  private suspend fun approveBuilder(product: AssetType) = withBuilderPending(product) {
    val result =
      accounts.approveBuilderFee(
        builderAddress = runtime.defaultBuilderAddress,
        feeBps = 10u,
        prompt = VaultPrompt("Approve builder support", "Confirm your identity"),
        product = product,
      )
    check(result is TransactionState.Committed) {
      (result as? TransactionState.Failed)?.message ?: "Approving builder support failed. Try again."
    }
  }

  private suspend fun withBuilderPending(product: AssetType, block: suspend () -> Unit) {
    local.update { it.copy(builderPending = product) }
    try {
      block()
    } finally {
      local.update { it.copy(builderPending = null) }
    }
  }

  private fun launchAction(outcome: String, block: suspend () -> Unit) {
    if (local.value.busy) return
    viewModelScope.launch {
      local.update { it.copy(busy = true, error = null) }
      try {
        runSuspendCatching { block() }
          .onFailure { error ->
            if (!error.isAuthorizationCancelled())
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
