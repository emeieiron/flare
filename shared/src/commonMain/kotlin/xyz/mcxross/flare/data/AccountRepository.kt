package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.model.AccountOverview
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.Fill
import xyz.mcxross.flare.venue.model.FundMovement
import xyz.mcxross.flare.venue.model.FundingPayment
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OpenOrder
import xyz.mcxross.flare.venue.model.OrderHistoryEntry
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingAccountStatus
import xyz.mcxross.flare.venue.model.TradingFees
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

data class AccountSnapshot(
  val account: String? = null,
  val overview: AccountOverview? = null,
  val positions: List<Position> = emptyList(),
  val openOrders: List<OpenOrder> = emptyList(),
  val loading: Boolean = false,
  val stale: Boolean = true,
  val error: String? = null,
)

enum class AccountHistoryKind {
  ORDERS,
  TRADES,
  FUNDING,
  TRANSFERS,
}

data class AccountHistorySnapshot(
  val account: String? = null,
  val orders: List<OrderHistoryEntry> = emptyList(),
  val trades: List<Fill> = emptyList(),
  val funding: List<FundingPayment> = emptyList(),
  val transfers: List<FundMovement> = emptyList(),
  val ordersHasMore: Boolean = false,
  val tradesHasMore: Boolean = false,
  val fundingHasMore: Boolean = false,
  val transfersHasMore: Boolean = false,
  val loading: Boolean = false,
  val loadingMore: Boolean = false,
  val stale: Boolean = true,
  val error: String? = null,
)

/**
 * The selected trading account: what it holds, its history, and the actions that set it up and move
 * its funds, all in the neutral models of `xyz.mcxross.flare.venue.model`.
 */
interface AccountRepository {
  val snapshot: StateFlow<AccountSnapshot>
  val history: StateFlow<AccountHistorySnapshot>

  /** Lists the keys allowed to trade for the selected account. */
  suspend fun delegations(): List<TradingKey>

  suspend fun revokeDelegation(address: String, prompt: VaultPrompt): ActionState

  /**
   * Re-establishes the session the selected account needs and reloads its data. Safe to call
   * whenever the app regains signing authority; it never prompts while the vault is open.
   */
  suspend fun restoreTrading()

  suspend fun importTradingKey(key: String, subaccount: String, prompt: VaultPrompt)

  suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<TradingAccount>

  suspend fun subaccounts(owner: String): List<TradingAccount>

  /** Null when the chain couldn't be asked. */
  suspend fun tradingAccountStatus(address: String): TradingAccountStatus? = null

  /** Selects the trading account to use and loads it with whichever key this device holds. */
  suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt)

  suspend fun createSubaccount(
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): ActionState

  /**
   * Opens the trading account of a wallet Flare created and returns its address. It happens exactly
   * once: an earlier attempt that went through is found and reused, never repeated, and a new account
   * is waited for until the exchange lists it. Flare sponsors the fee, since a new wallet has none.
   * A transaction that didn't go through throws [SetupTransactionException], with its progress saved.
   */
  suspend fun openTradingAccount(prompt: VaultPrompt): String

  /**
   * Runs setup's on-chain steps as one confirmed action. An exchange may keep opening an account,
   * enabling trading and approving builder fees as separate transactions, so instead of one
   * transaction the person confirms once: every owner signature inside [block] rests on that
   * confirmation rather than asking again.
   */
  suspend fun <T> confirmedSetup(prompt: VaultPrompt, block: suspend () -> T): T = block()

  suspend fun delegateApiWallet(
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): ActionState

  suspend fun prepareTradingWallet(
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  )

  fun depositUsdc(
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<ActionState>

  /** Withdraws to the owner wallet, or to [destination] through a reviewed follow-up transfer. */
  fun withdrawUsdc(
    amount: String,
    destination: String?,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<ActionState>

  /** Perpetuals and spot keep separate on-chain approvals; [product] picks which one. */
  suspend fun approveBuilderFee(
    builderAddress: String,
    feeBps: UInt,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
    product: MarketKind = MarketKind.PERP,
  ): ActionState

  suspend fun revokeBuilderFee(
    builderAddress: String,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
    product: MarketKind = MarketKind.PERP,
  ): ActionState

  suspend fun portfolioChart(
    range: PortfolioChartRange = PortfolioChartRange.DAY_1,
    metric: PortfolioMetric = PortfolioMetric.EQUITY,
  ): List<PortfolioPoint>

  suspend fun fees(): TradingFees? = null

  /**
   * The account's rewards, or null when there are none to show. Parts that failed to load are
   * listed in [Rewards.unavailable], so the others still show.
   */
  suspend fun rewards(): Rewards?

  suspend fun redeemReferralCode(code: String): Boolean

  suspend fun activeTwaps(): List<TwapOrder>

  suspend fun twapHistory(limit: Int = 50): List<TwapOrder>

  suspend fun vaults(limit: Int = 50): List<VaultSummary>

  /** The selected account's stakes in vaults. */
  suspend fun vaultPositions(): List<VaultPosition>

  fun contributeToVault(
    vaultAddress: String,
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<ActionState>

  fun redeemFromVault(
    vaultAddress: String,
    shares: String,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<ActionState>

  suspend fun refresh()

  suspend fun refreshHistory()

  suspend fun loadMoreHistory(kind: AccountHistoryKind)

  fun startLive()
}

/** A setup transaction that didn't go through; [transaction] says how far it got. */
class SetupTransactionException(val transaction: ActionState) :
  IllegalStateException(
    (transaction as? ActionState.Failed)?.let { failure ->
      "Enabling trading failed" + (failure.reference?.let { ". Transaction: $it" } ?: "")
    } ?: "Enabling trading is still pending"
  )
