package xyz.mcxross.flare.venue.hyperliquid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

/**
 * The wallet's own address is its Hyperliquid account. Its balances, positions, orders and history
 * aren't read yet, so the snapshots name the account and say the data is unavailable, and every
 * action fails before anything is sent.
 */
class HyperliquidAccountRepository(private val preferences: AppPreferences) : AccountRepository {
  private val mutableSnapshot = MutableStateFlow(AccountSnapshot())
  private val mutableHistory = MutableStateFlow(AccountHistorySnapshot())
  override val snapshot: StateFlow<AccountSnapshot> = mutableSnapshot.asStateFlow()
  override val history: StateFlow<AccountHistorySnapshot> = mutableHistory.asStateFlow()

  override suspend fun refresh() {
    val saved = preferences.values.first()
    val account = saved.selectedSubaccount ?: saved.ownerAddress
    mutableSnapshot.value =
      AccountSnapshot(account = account, error = account?.let { ACCOUNT_UNAVAILABLE })
    mutableHistory.value =
      AccountHistorySnapshot(account = account, error = account?.let { ACCOUNT_UNAVAILABLE })
  }

  override suspend fun refreshHistory() = refresh()

  override suspend fun restoreTrading() = refresh()

  override suspend fun loadMoreHistory(kind: AccountHistoryKind) {}

  override fun startLive() {}

  override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) {
    require(subaccount.isNotBlank()) { "Select a trading account" }
    preferences.setSelectedSubaccount(subaccount)
    refresh()
  }

  /** Hyperliquid trades from the wallet's own address. */
  override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount> =
    listOfNotNull(preferences.values.first().ownerAddress?.let(::ownAccount))

  override suspend fun subaccounts(owner: String): List<TradingAccount> = listOf(ownAccount(owner))

  private fun ownAccount(owner: String) = TradingAccount(owner, owner, primary = true)

  override suspend fun delegations(): List<TradingKey> = emptyList()

  override suspend fun revokeDelegation(address: String, prompt: VaultPrompt): ActionState = unavailable()

  override suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt) =
    throw UnsupportedOperationException(TRADING_KEYS_UNAVAILABLE)

  override suspend fun createSubaccount(prompt: VaultPrompt, feePayment: FeePayment): ActionState =
    unavailable()

  override suspend fun openTradingAccount(prompt: VaultPrompt): String =
    throw UnsupportedOperationException("Hyperliquid accounts need no opening.")

  override suspend fun delegateApiWallet(prompt: VaultPrompt, feePayment: FeePayment): ActionState =
    unavailable()

  override suspend fun prepareTradingWallet(prompt: VaultPrompt, feePayment: FeePayment) =
    throw UnsupportedOperationException(TRADING_KEYS_UNAVAILABLE)

  override fun depositUsdc(amount: String, prompt: VaultPrompt, feePayment: FeePayment): Flow<ActionState> =
    flowOf(unavailable())

  override fun withdrawUsdc(
    amount: String,
    destination: String?,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<ActionState> = flowOf(unavailable())

  override suspend fun approveBuilderFee(
    builderAddress: String,
    feeBps: UInt,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    product: MarketKind,
  ): ActionState = unavailable()

  override suspend fun revokeBuilderFee(
    builderAddress: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    product: MarketKind,
  ): ActionState = unavailable()

  override suspend fun portfolioChart(
    range: PortfolioChartRange,
    metric: PortfolioMetric,
  ): List<PortfolioPoint> = throw UnsupportedOperationException(ACCOUNT_UNAVAILABLE)

  override suspend fun rewards(): Rewards? = null

  override suspend fun redeemReferralCode(code: String): Boolean =
    throw UnsupportedOperationException("Referral codes aren’t available on Hyperliquid yet.")

  override suspend fun activeTwaps(): List<TwapOrder> = throw UnsupportedOperationException(ACCOUNT_UNAVAILABLE)

  override suspend fun twapHistory(limit: Int): List<TwapOrder> =
    throw UnsupportedOperationException(ACCOUNT_UNAVAILABLE)

  override suspend fun vaults(limit: Int): List<VaultSummary> =
    throw UnsupportedOperationException(ACCOUNT_UNAVAILABLE)

  override suspend fun vaultPositions(): List<VaultPosition> =
    throw UnsupportedOperationException(ACCOUNT_UNAVAILABLE)

  override fun contributeToVault(
    vaultAddress: String,
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<ActionState> = flowOf(unavailable())

  override fun redeemFromVault(
    vaultAddress: String,
    shares: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<ActionState> = flowOf(unavailable())
}

internal const val ACCOUNT_UNAVAILABLE = "Hyperliquid account data isn’t available in Flare yet."

internal const val ACTIONS_UNAVAILABLE = "Trading on Hyperliquid isn’t available in Flare yet."

internal fun unavailable(): ActionState.Failed =
  ActionState.Failed(ACTIONS_UNAVAILABLE, definitelyNotSubmitted = true)
