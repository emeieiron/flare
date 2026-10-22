package xyz.mcxross.flare.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.RewardsRepository
import xyz.mcxross.flare.data.VaultsRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.CredentialFormat
import xyz.mcxross.flare.data.CredentialParser
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

const val SETUP_OWNER = "0xowner"
const val SETUP_PHRASE = "abandon abandon about"
val SetupBackupWords = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")

class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
  override val data = MutableStateFlow(initial)

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
    transform(data.value).also { data.value = it }
}

/**
 * Keys held by the device for setup tests. With [preferences] it saves each wallet's profile, as the
 * venues' wallet repositories do.
 */
class SetupWallets(private val preferences: AppPreferences? = null) : WalletRepository {
  override val profile = MutableStateFlow(WalletProfile())
  override val authorizationGeneration: Long = 0L

  override fun requireAuthorization(generation: Long) {}

  override suspend fun createOwner(prompt: VaultPrompt): OwnerBackup {
    preferences?.registerProfile(AccountProfile("owner_$SETUP_OWNER", ownerAddress = SETUP_OWNER, createdInApp = true))
    profile.value = profile.value.copy(ownerAddress = SETUP_OWNER, ownerBackupConfirmed = false)
    return OwnerBackup(SETUP_OWNER, SetupBackupWords)
  }

  override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String {
    preferences?.registerProfile(
      AccountProfile("owner_$SETUP_OWNER", ownerAddress = SETUP_OWNER, ownerBackupConfirmed = true)
    )
    profile.value = profile.value.copy(ownerAddress = SETUP_OWNER, ownerBackupConfirmed = true)
    return SETUP_OWNER
  }

  override suspend fun confirmOwnerBackup() {
    preferences?.setOwnerBackupConfirmed(true)
    profile.value = profile.value.copy(ownerBackupConfirmed = true)
  }

  override suspend fun createApiWallet(prompt: VaultPrompt): String {
    preferences?.setApiWallet("0xapi")
    profile.value = profile.value.copy(apiWalletAddress = "0xapi")
    return "0xapi"
  }

  override suspend fun importApiWallet(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (address: String) -> Unit,
  ): String = error("Not used")

  override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String = SetupBackupWords.joinToString(" ")

  override suspend fun exportApiWallet(prompt: VaultPrompt): String = error("Not used")

  override suspend fun removeOwner(prompt: VaultPrompt) {}

  override suspend fun removeApiWallet(prompt: VaultPrompt) {}

  override fun lock() {}
}

object SetupCredentials : CredentialParser {
  override fun detect(input: String) = CredentialFormat.RECOVERY_PHRASE

  override fun normalize(input: String) = input

  override fun keyAddress(input: String): String? = null

  override fun sameAddress(first: String, second: String) = first == second

  override fun isAddress(input: String) = input.startsWith("0x")
}

/**
 * The account side of setup, recording what it was asked in [events]. [awaitingDeposit] stands for
 * an exchange that enables trading only once the account is funded; [depositCheckGate], when set,
 * holds the deposit check until it completes. With [preferences] it saves the selected account.
 */
open class SetupAccounts(private val preferences: AppPreferences? = null) :
  AccountRepository, VaultsRepository, RewardsRepository {
  val events = mutableListOf<String>()
  val listed =
    listOf(
      TradingAccount("0xa1", SETUP_OWNER, primary = true),
      TradingAccount("0xa2", SETUP_OWNER),
    )
  override val snapshot: StateFlow<AccountSnapshot> = MutableStateFlow(AccountSnapshot())
  override val history: StateFlow<AccountHistorySnapshot> = MutableStateFlow(AccountHistorySnapshot())

  var awaitingDeposit = false
  var approveResult: ActionState = ActionState.Committed("0x")
  var depositCheckFails = false
  var depositChecks = 0
  var depositCheckGate: CompletableDeferred<Unit>? = null

  override suspend fun delegations(): List<TradingKey> = emptyList()

  override suspend fun revokeDelegation(address: String, prompt: VaultPrompt) = error("Not used")

  override suspend fun restoreTrading() {}

  override suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt) {}

  override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount> {
    events += "discover"
    return listed
  }

  override suspend fun subaccounts(owner: String): List<TradingAccount> = listed

  override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) {
    events += "select $subaccount"
    preferences?.setSelectedSubaccount(subaccount)
  }

  override suspend fun createSubaccount(prompt: VaultPrompt, feePayment: FeePayment) = error("Not used")

  override suspend fun openTradingAccount(prompt: VaultPrompt): String {
    events += "open"
    return "0xa1"
  }

  override suspend fun delegateApiWallet(prompt: VaultPrompt, feePayment: FeePayment) = error("Not used")

  override suspend fun prepareTradingWallet(prompt: VaultPrompt, feePayment: FeePayment) {
    events += "prepare"
  }

  override suspend fun awaitsFirstDeposit(): Boolean {
    depositChecks++
    depositCheckGate?.await()
    check(!depositCheckFails) { "Offline" }
    return awaitingDeposit
  }

  override fun depositUsdc(amount: String, prompt: VaultPrompt, feePayment: FeePayment) = emptyFlow<ActionState>()

  override fun withdrawUsdc(amount: String, destination: String?, prompt: VaultPrompt, feePayment: FeePayment) =
    emptyFlow<ActionState>()

  override suspend fun approveBuilderFee(
    builderAddress: String,
    feeBps: UInt,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    product: MarketKind,
  ): ActionState {
    events += "approve builder"
    return approveResult
  }

  override suspend fun revokeBuilderFee(
    builderAddress: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    product: MarketKind,
  ) = error("Not used")

  override suspend fun portfolioChart(range: PortfolioChartRange, metric: PortfolioMetric) =
    emptyList<PortfolioPoint>()

  override suspend fun rewards(): Rewards? = null

  override suspend fun redeemReferralCode(code: String) = false

  override suspend fun activeTwaps() = emptyList<TwapOrder>()

  override suspend fun twapHistory(limit: Int) = emptyList<TwapOrder>()

  override suspend fun vaults(limit: Int) = emptyList<VaultSummary>()

  override suspend fun vaultPositions() = emptyList<VaultPosition>()

  override fun contribute(vaultAddress: String, amount: String, prompt: VaultPrompt, feePayment: FeePayment) =
    emptyFlow<ActionState>()

  override fun redeem(vaultAddress: String, shares: String, prompt: VaultPrompt, feePayment: FeePayment) =
    emptyFlow<ActionState>()

  override suspend fun refresh() {}

  override suspend fun refreshHistory() {}

  override suspend fun loadMoreHistory(kind: AccountHistoryKind) {}

  override fun startLive() {}
}

object SetupTrading : TradingRepository {
  override val pendingTransactions: Flow<List<PendingTransaction>> = MutableStateFlow(emptyList())

  override fun execute(
    action: TradeAction,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<ActionState> = emptyFlow()

  override suspend fun transactionStatus(reference: String) = ActionState.Pending(reference)

  override suspend fun tradingKeyFeeBalance() = 0uL

  override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt) = emptyFlow<ActionState>()

  override suspend fun reconcilePending() = ReconciliationResult(0, 0)
}
