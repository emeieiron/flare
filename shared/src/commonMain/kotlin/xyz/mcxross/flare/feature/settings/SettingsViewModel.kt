package xyz.mcxross.flare.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSetupGate
import xyz.mcxross.flare.data.SetupNeed
import xyz.mcxross.flare.data.gatedBy
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlarePreferences
import xyz.mcxross.flare.store.WithdrawalContinuation
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingKey

data class SettingsUiState(
  /** What the venue supports; screens hide the rest. */
  val capabilities: VenueCapabilities = VenueCapabilities.All,
  /** What the wallet still needs when its setup was left for later; its actions wait meanwhile. */
  val setupNeed: SetupNeed? = null,
  val preferences: FlarePreferences = FlarePreferences(),
  val profile: WalletProfile = WalletProfile(),
  val proxyUrl: String = "",
  val defaultBuilderAddress: String = "",
  val revealedSecretLabel: String? = null,
  val revealedSecret: String? = null,
  val delegations: List<TradingKey> = emptyList(),
  val delegationsLoaded: Boolean = false,
  val subaccountsByOwner: Map<String, List<TradingAccount>> = emptyMap(),
  val creatingSubaccount: Boolean = false,
  val referralCodeInput: String = "",
  val referralRedeemed: Boolean = false,
  val referralMessage: String? = null,
  val rewards: Rewards? = null,
  val withdrawDestination: String = "",
  /** Sending to another account on the venue rather than withdrawing off it. */
  val withdrawOnVenue: Boolean = false,
  val withdrawAmount: String = "",
  val withdrawTransaction: ActionState? = null,
  val withdrawing: Boolean = false,
  /** Why the last withdrawal attempt failed, as reported; screens phrase it for people. */
  val withdrawError: String? = null,
  /** What the trading account can withdraw right now, when known. */
  val withdrawable: Double? = null,
  /** A withdrawal that was interrupted part-way and must be finished before another starts. */
  val pendingWithdrawal: WithdrawalContinuation? = null,
  val busy: Boolean = false,
  /** The product whose builder approval is being written on-chain. */
  val builderPending: MarketKind? = null,
  /** A builder change Flare couldn't sponsor, which the wallet can pay for once the person agrees. */
  val builderSelfPay: BuilderSelfPay? = null,
  val error: String? = null,
)

/** [basisPoints] is the rate being approved, or null for a revocation. */
data class BuilderSelfPay(val product: MarketKind, val basisPoints: Int?, val estimate: ULong)

sealed interface SettingsIntent {
  data object LoadDelegations : SettingsIntent

  data object LoadSubaccounts : SettingsIntent

  data class RevokeDelegate(val address: String) : SettingsIntent

  data class SelectProfile(val id: String) : SettingsIntent

  data class SelectSubaccount(val profileId: String, val subaccountAddress: String) : SettingsIntent

  data object CreateSubaccountForActiveProfile : SettingsIntent

  data class ChangeWithdrawDestination(val address: String) : SettingsIntent

  data class ChangeWithdrawAmount(val amount: String) : SettingsIntent

  /** Whether the USDC goes to another account on the venue instead of leaving it. */
  data class SetWithdrawOnVenue(val onVenue: Boolean) : SettingsIntent

  /** Opens the withdrawal, picking up an interrupted one where it stopped. */
  data object OpenWithdraw : SettingsIntent

  data object SubmitWithdraw : SettingsIntent

  /** Retries a withdrawal Flare couldn't sponsor, with the wallet paying the network fee. */
  data object ConfirmWithdrawSelfPay : SettingsIntent

  /** Leaves a failed attempt to change the recipient or amount. */
  data object EditWithdraw : SettingsIntent

  data object DismissWithdraw : SettingsIntent

  data class SetSlippage(val basisPoints: Int) : SettingsIntent

  data class SetConfirmTransactions(val confirm: Boolean) : SettingsIntent

  /** Perpetuals and spot each hold their own rate and on-chain approval. */
  data class SetBuilderFeeBps(val product: MarketKind, val basisPoints: Int) : SettingsIntent

  data class RevokeBuilderFee(val product: MarketKind) : SettingsIntent

  /** Retries the builder change Flare couldn't sponsor, with the wallet paying the network fee. */
  data object ConfirmBuilderSelfPay : SettingsIntent

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
  /** The venue's backend session, signed out when a key is removed; null when it has none. */
  private val sessions: SessionRepository?,
  venue: VenueDescriptor,
  private val credentials: CredentialParser,
  private val setupGate: AccountSetupGate? = null,
) : ViewModel() {
  private val capabilities = venue.capabilities
  private val chainName = venue.chain.name
  private val withdrawalNetwork = venue.withdrawal?.network ?: chainName
  private val maxBuilderFeeBps = venue.maxBuilderFeeBps
  private val local =
    MutableStateFlow(
      SettingsUiState(
        capabilities = capabilities,
        proxyUrl = venue.proxyUrl.orEmpty(),
        defaultBuilderAddress = venue.builderAddress.orEmpty(),
      )
    )
  private var secretClearJob: Job? = null

  init {
    setupGate?.let { gate ->
      viewModelScope.launch {
        gate.need.collect { need ->
          local.update { it.copy(setupNeed = need, capabilities = capabilities.gatedBy(need)) }
        }
      }
    }
    viewModelScope.launch {
      if (capabilities.rewards) {
        // Parts that fail to load are simply left out here.
        val rewards = runSuspendCatching { accounts.rewards() }.getOrNull()
        local.update { it.copy(rewards = rewards) }
      }
      loadSubaccounts()
    }
  }

  val uiState: StateFlow<SettingsUiState> =
    combine(local, preferences.values, wallets.profile, accounts.snapshot) {
        state, persisted, profile, snapshot ->
        state.copy(
          preferences = persisted,
          profile = profile,
          withdrawable = snapshot.overview?.withdrawable,
          pendingWithdrawal =
            persisted.profiles.firstOrNull { it.id == persisted.activeProfileId }?.withdrawal,
        )
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
          check(result is ActionState.Committed) { "Revoking access failed. Try again." }
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
            check(result is ActionState.Committed) {
              (result as? ActionState.Failed)?.message ?: "Subaccount transaction failed. Try again."
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
      is SettingsIntent.SetWithdrawOnVenue ->
        local.update {
          if (it.withdrawing) it else it.copy(withdrawOnVenue = intent.onVenue, withdrawError = null)
        }
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
            withdrawOnVenue = false,
            withdrawAmount = "",
            withdrawTransaction = null,
            withdrawError = null,
            withdrawing = false,
          )
        }
      SettingsIntent.OpenWithdraw -> {
        val pending = uiState.value.pendingWithdrawal
        local.update {
          it.copy(
            withdrawDestination = pending?.destination ?: it.withdrawDestination,
            withdrawAmount = pending?.amount ?: it.withdrawAmount,
            withdrawTransaction = null,
            withdrawError = null,
          )
        }
        // The balance shown beside the amount should be the current one.
        viewModelScope.launch { runSuspendCatching { accounts.refresh() } }
      }
      SettingsIntent.SubmitWithdraw -> submitWithdraw(FeePayment.SPONSORED)
      SettingsIntent.ConfirmWithdrawSelfPay -> submitWithdraw(FeePayment.SELF_PAY)
      SettingsIntent.EditWithdraw ->
        local.update {
          if (it.withdrawing) it else it.copy(withdrawTransaction = null, withdrawError = null)
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
        setBuilderRate(intent.product, intent.basisPoints, FeePayment.SPONSORED)
      is SettingsIntent.RevokeBuilderFee -> revokeBuilder(intent.product, FeePayment.SPONSORED)
      SettingsIntent.ConfirmBuilderSelfPay ->
        local.value.builderSelfPay?.let { offer ->
          val rate = offer.basisPoints
          if (rate == null) revokeBuilder(offer.product, FeePayment.SELF_PAY)
          else setBuilderRate(offer.product, rate, FeePayment.SELF_PAY)
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
          sessions?.invalidate()
          accounts.restoreTrading()
        }
      SettingsIntent.RemoveApi ->
        launchAction("The trading key is still on this device.") {
          wallets.removeApiWallet(VaultPrompt("Remove trading key", "Confirm your identity"))
          sessions?.invalidate()
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
  private fun setBuilderRate(product: MarketKind, basisPoints: Int, feePayment: FeePayment) =
    launchAction("Your builder fee setting didn’t change.") {
      local.update { it.copy(builderSelfPay = null) }
      val current = preferences.values.first()
      val approved =
        if (product == MarketKind.PERP) current.builderApproved else current.spotBuilderApproved
      // A rate only applies once its product is approved on-chain.
      if (basisPoints > 0 && !approved && !approveBuilder(product, basisPoints, feePayment)) {
        return@launchAction
      }
      when (product) {
        MarketKind.PERP -> preferences.setBuilderFeeBps(basisPoints)
        MarketKind.SPOT -> preferences.setSpotBuilderFeeBps(basisPoints)
      }
    }

  private fun revokeBuilder(product: MarketKind, feePayment: FeePayment) =
    launchAction("Builder support wasn’t revoked.") {
      local.update { it.copy(builderSelfPay = null) }
      val current = preferences.values.first()
      withBuilderPending(product) {
        val result =
          accounts.revokeBuilderFee(
            builderAddress = current.builderAddress ?: local.value.defaultBuilderAddress,
            prompt = VaultPrompt("Revoke builder support", "Confirm your identity"),
            feePayment = feePayment,
            product = product,
          )
        check(result is ActionState.Committed || offerBuilderSelfPay(result, product, null)) {
          (result as? ActionState.Failed)?.message ?: "Revoking builder support failed. Try again."
        }
      }
    }

  /**
   * Approves Flare's builder up to the venue's cap, so later rate changes need no new approval.
   * False when Flare couldn't sponsor it and the wallet's offer to pay is waiting for the person.
   */
  private suspend fun approveBuilder(
    product: MarketKind,
    basisPoints: Int,
    feePayment: FeePayment,
  ): Boolean = withBuilderPending(product) {
    val result =
      accounts.approveBuilderFee(
        builderAddress = local.value.defaultBuilderAddress,
        feeBps = maxBuilderFeeBps.toUInt(),
        prompt = VaultPrompt("Approve builder support", "Confirm your identity"),
        feePayment = feePayment,
        product = product,
      )
    if (result is ActionState.Committed) return@withBuilderPending true
    check(offerBuilderSelfPay(result, product, basisPoints)) {
      (result as? ActionState.Failed)?.message ?: "Approving builder support failed. Try again."
    }
    false
  }

  /** Offers self-pay only for a sponsorship that definitely wasn't submitted. */
  private fun offerBuilderSelfPay(result: ActionState, product: MarketKind, basisPoints: Int?): Boolean {
    val estimate =
      (result as? ActionState.Failed)?.selfPay?.estimate?.takeIf { capabilities.sponsoredFees }
        ?: return false
    local.update { it.copy(builderSelfPay = BuilderSelfPay(product, basisPoints, estimate)) }
    return true
  }

  private suspend fun <T> withBuilderPending(product: MarketKind, block: suspend () -> T): T {
    local.update { it.copy(builderPending = product) }
    return try {
      block()
    } finally {
      local.update { it.copy(builderPending = null) }
    }
  }

  private fun submitWithdraw(feePayment: FeePayment) {
    val current = local.value
    if (current.withdrawing || current.withdrawTransaction is ActionState.Committed) return
    val dest = current.withdrawDestination.trim()
    val amt = current.withdrawAmount.trim()
    if (!credentials.isAddress(dest)) {
      val network = if (current.withdrawOnVenue) chainName else withdrawalNetwork
      local.update { it.copy(withdrawError = "Enter a valid $network address.") }
      return
    }
    if (amt.toDoubleOrNull()?.let { it.isFinite() && it > 0 } != true) {
      local.update { it.copy(withdrawError = "Enter an amount greater than zero.") }
      return
    }
    viewModelScope.launch {
      local.update { it.copy(withdrawing = true, withdrawError = null, withdrawTransaction = null) }
      try {
        val prompt = VaultPrompt("Withdraw USDC", "Confirm your identity")
        val flow =
          if (current.withdrawOnVenue) accounts.transferUsdc(amt, dest, VaultPrompt("Send USDC", "Confirm your identity"))
          else accounts.withdrawUsdc(amt, dest, prompt, feePayment)
        flow.collect { tx ->
          local.update { it.copy(withdrawTransaction = tx) }
        }
        when (val terminal = local.value.withdrawTransaction) {
          is ActionState.Committed -> {
            accounts.refresh()
            loadSubaccounts()
          }
          // A fee Flare can't sponsor is a choice for the person, not a failure.
          is ActionState.Failed ->
            if (terminal.selfPay?.estimate == null) {
              local.update { it.copy(withdrawError = terminal.message) }
            }
          else -> Unit
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Backing out of the identity prompt returns to the review without complaint.
        if (e.isAuthorizationCancelled()) local.update { it.copy(withdrawTransaction = null) }
        else local.update { it.copy(withdrawError = e.message ?: "Your withdrawal didn’t go through.") }
      } finally {
        local.update { it.copy(withdrawing = false) }
      }
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
    // Venues without trading accounts show the selected account alone.
    if (!capabilities.subaccounts) return
    viewModelScope.launch {
      val profiles = preferences.values.first().profiles
      val map = mutableMapOf<String, List<TradingAccount>>()
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
