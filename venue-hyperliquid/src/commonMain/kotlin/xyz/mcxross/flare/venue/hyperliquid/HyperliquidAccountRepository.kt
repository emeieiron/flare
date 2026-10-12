package xyz.mcxross.flare.venue.hyperliquid

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.HyperliquidNetwork
import xyz.mcxross.flare.hyperliquid.api.StreamData
import xyz.mcxross.flare.hyperliquid.api.StreamEvent
import xyz.mcxross.flare.hyperliquid.api.Subscription
import xyz.mcxross.flare.hyperliquid.model.AccountAbstraction
import xyz.mcxross.flare.hyperliquid.model.ClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.FrontendOrder
import xyz.mcxross.flare.hyperliquid.model.SpotClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.TwapState
import xyz.mcxross.flare.hyperliquid.model.VaultEquity
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.Fill
import xyz.mcxross.flare.venue.model.FundMovement
import xyz.mcxross.flare.venue.model.FundingPayment
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderHistoryEntry
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingFees
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

/**
 * The wallet's own address is its Hyperliquid account, read over `/info` and kept live by the
 * account streams, which send a snapshot on every connection. Actions fail before anything is sent
 * until trading is enabled.
 */
class HyperliquidAccountRepository(
  private val client: HyperliquidClient,
  private val preferences: AppPreferences,
) : AccountRepository {
  private val mutableSnapshot = MutableStateFlow(AccountSnapshot())
  private val mutableHistory = MutableStateFlow(AccountHistorySnapshot())
  override val snapshot: StateFlow<AccountSnapshot> = mutableSnapshot.asStateFlow()
  override val history: StateFlow<AccountHistorySnapshot> = mutableHistory.asStateFlow()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val refreshMutex = Mutex()
  private val historyMutex = Mutex()
  private val stateMutex = Mutex()
  private var streamJob: Job? = null
  private var streamingAccount: String? = null

  /** The account's raw state, merged from REST reads and the streams. Guarded by [stateMutex]. */
  private var state = RawState(account = null)
  private var tokenPrices: Map<String, Double> = mapOf(USDC to 1.0)
  private var tokenPricesAtMs = 0L

  /** Every order in the latest `historicalOrders`, newest first; the history shows a page at a time. */
  private var orderHistory: List<OrderHistoryEntry> = emptyList()
  /** How far back each time-paged history has been read. */
  private val oldestLoaded = mutableMapOf<AccountHistoryKind, Long>()

  private val protocolVault: String =
    when (client.config.deployment.network) {
      HyperliquidNetwork.MAINNET -> MAINNET_HLP
      HyperliquidNetwork.TESTNET -> TESTNET_HLP
    }

  private suspend fun currentAccount(): String? =
    preferences.values.first().let { it.selectedSubaccount ?: it.ownerAddress }

  override suspend fun refresh() = refreshMutex.withLock {
    val account = currentAccount()
    if (account == null) {
      mutableSnapshot.value = AccountSnapshot(error = NO_ACCOUNT)
      return@withLock
    }
    if (!mutableSnapshot.value.account.sameAs(account)) mutableSnapshot.value = AccountSnapshot(account = account)
    mutableSnapshot.update { it.copy(account = account, loading = true, error = null) }
    runSuspendCatching { readAccount(account) }
      .onSuccess { read ->
        if (!currentAccount().sameAs(account)) return@onSuccess
        stateMutex.withLock {
          val previous = state.takeIf { it.account.sameAs(account) }
          state =
            RawState(
              account = account,
              perp = read.perp,
              spot = read.spot,
              orders = read.orders,
              abstraction = read.abstraction ?: previous?.abstraction,
              vaultEquities = read.vaultEquities ?: previous?.vaultEquities,
              twaps = previous?.twaps,
            )
          publish()
        }
      }
      .onFailure { error ->
        mutableSnapshot.update {
          it.copy(loading = false, stale = true, error = error.message ?: "Account data is unavailable")
        }
      }
    Unit
  }

  private suspend fun readAccount(account: String): AccountRead = coroutineScope {
    val perp = async { client.info.clearinghouseState(account) }
    val spot = async { client.info.spotClearinghouseState(account) }
    val orders = async { client.info.openOrders(account) }
    val abstraction = async { runSuspendCatching { client.info.userAbstraction(account) }.getOrNull() }
    val vaults = async { runSuspendCatching { client.info.userVaultEquities(account) }.getOrNull() }
    val prices = async { refreshTokenPrices() }
    prices.await()
    AccountRead(perp.await(), spot.await(), orders.await(), abstraction.await(), vaults.await())
  }

  /** Spot prices value the holdings; they're re-read at most once a minute. */
  private suspend fun refreshTokenPrices() {
    val now = nowMs()
    if (now - tokenPricesAtMs < PRICE_REFRESH_MS) return
    runSuspendCatching { client.info.spotMetaAndContexts().tokenPrices() }
      .onSuccess {
        tokenPrices = it
        tokenPricesAtMs = now
      }
  }

  /** Rebuilds the snapshot from [state]; the caller holds [stateMutex]. */
  private fun publish() {
    val current = state
    val perp = current.perp ?: return
    mutableSnapshot.value =
      AccountSnapshot(
        account = current.account,
        overview =
          accountOverview(perp, current.spot, current.abstraction, tokenPrices, current.vaultEquities, nowMs()),
        positions = perp.toPositions(current.orders),
        openOrders = current.orders.map { it.toOpenOrder() },
        stale = false,
      )
  }

  private suspend fun mergeState(user: String, change: (RawState) -> RawState) =
    stateMutex.withLock {
      if (!state.account.sameAs(user) || state.perp == null) return@withLock
      state = change(state)
      publish()
    }

  override suspend fun refreshHistory() = historyMutex.withLock {
    val account = currentAccount()
    if (account == null) {
      mutableHistory.value = AccountHistorySnapshot(error = NO_ACCOUNT)
      return@withLock
    }
    if (!mutableHistory.value.account.sameAs(account)) mutableHistory.value = AccountHistorySnapshot(account = account)
    mutableHistory.update { it.copy(account = account, loading = true, error = null) }
    val end = nowMs()
    runSuspendCatching {
      coroutineScope {
        val orders = async { client.info.historicalOrders(account) }
        val trades = async { fillsPage(account, end) }
        val funding = async { fundingPage(account, end) }
        val transfers = async { ledgerPage(account, end) }
        HistoryRead(orders.await(), trades.await(), funding.await(), transfers.await())
      }
    }
      .onSuccess { read ->
        if (!currentAccount().sameAs(account)) return@onSuccess
        orderHistory =
          read.orders.sortedByDescending { it.statusTimestamp }.map { it.toOrderHistoryEntry() }
        oldestLoaded[AccountHistoryKind.TRADES] = read.trades.oldestMs ?: end
        oldestLoaded[AccountHistoryKind.FUNDING] = read.funding.oldestMs ?: end
        oldestLoaded[AccountHistoryKind.TRANSFERS] = read.transfers.oldestMs ?: end
        mutableHistory.value =
          AccountHistorySnapshot(
            account = account,
            orders = orderHistory.take(HISTORY_PAGE_SIZE),
            trades = read.trades.items,
            funding = read.funding.items,
            transfers = read.transfers.items,
            ordersHasMore = orderHistory.size > HISTORY_PAGE_SIZE,
            tradesHasMore = read.trades.hasMore,
            fundingHasMore = read.funding.hasMore,
            transfersHasMore = read.transfers.hasMore,
            stale = false,
          )
      }
      .onFailure { error ->
        mutableHistory.update {
          it.copy(loading = false, stale = true, error = error.message ?: "Account history is unavailable")
        }
      }
    Unit
  }

  private suspend fun fillsPage(account: String, endMs: Long): HistoryPage<Fill> =
    latestPage(endMs, HISTORY_PAGE_SIZE, FILLS_CAP, { it.time }) { start, end ->
        client.info.fills(account, start, end)
      }
      .let { page -> HistoryPage(page.items.map { it.toFill() }, page.hasMore, page.oldestMs) }

  private suspend fun fundingPage(account: String, endMs: Long): HistoryPage<FundingPayment> =
    latestPage(endMs, HISTORY_PAGE_SIZE, FUNDING_CAP, { it.time }) { start, end ->
        client.info.funding(account, start, end)
      }
      .let { page -> HistoryPage(page.items.map { it.toFundingPayment() }, page.hasMore, page.oldestMs) }

  private suspend fun ledgerPage(account: String, endMs: Long): HistoryPage<FundMovement> =
    latestPage(endMs, HISTORY_PAGE_SIZE, LEDGER_CAP, { it.time }) { start, end ->
        client.info.ledgerUpdates(account, start, end)
      }
      .let { page ->
        HistoryPage(page.items.mapNotNull { it.toFundMovement(account) }, page.hasMore, page.oldestMs)
      }

  override suspend fun loadMoreHistory(kind: AccountHistoryKind) = historyMutex.withLock {
    val current = mutableHistory.value
    val account = current.account ?: return@withLock
    val shouldLoad =
      when (kind) {
        AccountHistoryKind.ORDERS -> current.ordersHasMore
        AccountHistoryKind.TRADES -> current.tradesHasMore
        AccountHistoryKind.FUNDING -> current.fundingHasMore
        AccountHistoryKind.TRANSFERS -> current.transfersHasMore
      }
    if (!shouldLoad || current.loading || current.loadingMore) return@withLock
    if (kind == AccountHistoryKind.ORDERS) {
      val shown = (current.orders.size + HISTORY_PAGE_SIZE).coerceAtMost(orderHistory.size)
      mutableHistory.update {
        it.copy(orders = orderHistory.take(shown), ordersHasMore = shown < orderHistory.size)
      }
      return@withLock
    }
    mutableHistory.update { it.copy(loadingMore = true, error = null) }
    val end = oldestLoaded[kind] ?: nowMs()
    runSuspendCatching {
      when (kind) {
        AccountHistoryKind.TRADES -> fillsPage(account, end)
        AccountHistoryKind.FUNDING -> fundingPage(account, end)
        else -> ledgerPage(account, end)
      }
    }
      .onSuccess { page ->
        page.oldestMs?.let { oldestLoaded[kind] = it }
        mutableHistory.update { history ->
          @Suppress("UNCHECKED_CAST")
          when (kind) {
            AccountHistoryKind.TRADES -> {
              val merged = (history.trades + page.items as List<Fill>).distinctBy { it.id }
              history.copy(
                trades = merged,
                tradesHasMore = page.hasMore && merged.size > history.trades.size,
                loadingMore = false,
              )
            }
            AccountHistoryKind.FUNDING -> {
              val merged = (history.funding + page.items as List<FundingPayment>).distinct()
              history.copy(
                funding = merged,
                fundingHasMore = page.hasMore && merged.size > history.funding.size,
                loadingMore = false,
              )
            }
            else -> {
              val merged = (history.transfers + page.items as List<FundMovement>).distinct()
              history.copy(
                transfers = merged,
                transfersHasMore = page.hasMore && page.oldestMs != null && page.oldestMs < end,
                loadingMore = false,
              )
            }
          }
        }
      }
      .onFailure { error ->
        mutableHistory.update {
          it.copy(loadingMore = false, error = error.message ?: "Unable to load more account history")
        }
      }
    Unit
  }

  override fun startLive() {
    val account = mutableSnapshot.value.account ?: return
    if (streamJob?.isActive == true && streamingAccount.sameAs(account)) return
    streamJob?.cancel()
    streamingAccount = account
    streamJob = scope.launch { collectAccountStream(account) }
  }

  private suspend fun collectAccountStream(account: String) {
    var lastRecoveryAtMs = 0L
    val subscriptions =
      setOf(
        Subscription.ClearinghouseState(account),
        Subscription.OpenOrders(account),
        Subscription.SpotState(account),
        Subscription.TwapStates(account),
        Subscription.UserFills(account),
        Subscription.UserFundings(account),
        Subscription.LedgerUpdates(account),
      )
    client.stream.subscribe(subscriptions).collect { event ->
      when (event) {
        // Account streams start with snapshots; history and the reads they don't carry catch up here.
        is StreamEvent.Connected -> {
          refresh()
          refreshHistory()
        }
        is StreamEvent.Message -> {
          if (applyStreamData(account, event.data)) return@collect
          val now = nowMs()
          if (event.data is StreamData.Malformed && now - lastRecoveryAtMs >= RECOVERY_INTERVAL_MS) {
            lastRecoveryAtMs = now
            refresh()
          }
        }
        is StreamEvent.Rejected -> markStale(event.reason)
        is StreamEvent.Disconnected -> markStale(event.reason ?: "Account stream disconnected")
      }
    }
  }

  /** Merges one stream message into the account; false when it wasn't the account's data. */
  internal suspend fun applyStreamData(account: String, data: StreamData): Boolean {
    when (data) {
      is StreamData.Clearinghouse -> {
        if (!data.user.sameAs(account)) return false
        mergeState(account) { it.copy(perp = data.state) }
      }
      is StreamData.OpenOrders -> {
        if (!data.user.sameAs(account)) return false
        mergeState(account) { it.copy(orders = data.orders) }
      }
      is StreamData.SpotState -> {
        if (!data.user.sameAs(account)) return false
        mergeState(account) { it.copy(spot = data.state) }
      }
      is StreamData.TwapStates -> {
        if (!data.user.sameAs(account)) return false
        stateMutex.withLock {
          if (state.account.sameAs(account)) state = state.copy(twaps = data.states)
        }
      }
      // Snapshots repeat what the history read; only new entries are merged.
      is StreamData.UserFills -> {
        if (!data.user.sameAs(account)) return false
        if (!data.isSnapshot) mergeHistory(account) { history ->
          history.copy(
            trades = (data.fills.map { it.toFill() } + history.trades)
              .distinctBy { it.id }
              .sortedByDescending { it.timeMs },
          )
        }
      }
      is StreamData.UserFundings -> {
        if (!data.user.sameAs(account)) return false
        if (!data.isSnapshot) mergeHistory(account) { history ->
          history.copy(
            funding = (data.fundings.mapNotNull { it.toFundingPayment() } + history.funding)
              .distinct()
              .sortedByDescending { it.timeMs },
          )
        }
      }
      is StreamData.LedgerUpdates -> {
        if (!data.user.sameAs(account)) return false
        if (!data.isSnapshot) {
          mergeHistory(account) { history ->
            history.copy(
              transfers = (data.updates.mapNotNull { it.toFundMovement(account) } + history.transfers)
                .distinct()
                .sortedByDescending { it.timeMs },
            )
          }
          // Vault stakes aren't streamed; a ledger change is when they move.
          refresh()
        }
      }
      else -> return false
    }
    return true
  }

  private fun mergeHistory(account: String, change: (AccountHistorySnapshot) -> AccountHistorySnapshot) {
    mutableHistory.update { if (it.account.sameAs(account)) change(it).copy(stale = false, error = null) else it }
  }

  private fun markStale(reason: String) {
    mutableSnapshot.update { it.copy(stale = true, error = reason) }
    mutableHistory.update { it.copy(stale = true, error = reason) }
  }

  override suspend fun restoreTrading() {
    val account = currentAccount()
    if (account == null) {
      streamJob?.cancel()
      streamingAccount = null
      mutableSnapshot.value = AccountSnapshot()
      mutableHistory.value = AccountHistorySnapshot()
      return
    }
    if (!mutableSnapshot.value.account.sameAs(account)) {
      streamJob?.cancel()
      streamingAccount = null
    }
    refresh()
    refreshHistory()
    startLive()
  }

  override suspend fun portfolioChart(
    range: PortfolioChartRange,
    metric: PortfolioMetric,
  ): List<PortfolioPoint> {
    val account = currentAccount() ?: return emptyList()
    return client.info.portfolio(account).chart(range, metric)
  }

  override suspend fun fees(): TradingFees? {
    val account = currentAccount() ?: return null
    return client.info.userFees(account).toTradingFees()
  }

  /** Hyperliquid's fee tier and referral aren't shown yet. */
  override suspend fun rewards(): Rewards? = null

  override suspend fun redeemReferralCode(code: String): Boolean =
    throw UnsupportedOperationException("Referral codes aren’t available on Hyperliquid yet.")

  /** From the TWAP stream once it has reported, otherwise from history. */
  override suspend fun activeTwaps(): List<TwapOrder> {
    val account = currentAccount() ?: return emptyList()
    val live = stateMutex.withLock { state.takeIf { it.account.sameAs(account) }?.twaps }
    if (live != null) return live.map { (id, twap) -> twap.toTwapOrder(id) }
    return client.info.twapHistory(account).latestTwaps().filter { it.status == TWAP_ACTIVE }
  }

  override suspend fun twapHistory(limit: Int): List<TwapOrder> {
    val account = currentAccount() ?: return emptyList()
    return client.info.twapHistory(account).latestTwaps().filter { it.status != TWAP_ACTIVE }.take(limit)
  }

  /**
   * Hyperliquid lists no vault directory over its API, so the list is HLP, the protocol vault,
   * plus any vault the account has a stake in.
   */
  override suspend fun vaults(limit: Int): List<VaultSummary> {
    val account = currentAccount()
    val staked = account?.let { client.info.userVaultEquities(it) }.orEmpty().map { it.vaultAddress }
    return (listOf(protocolVault) + staked)
      .distinctBy { it.lowercase() }
      .take(limit)
      .map { address -> vaultSummary(address) }
  }

  override suspend fun vaultPositions(): List<VaultPosition> {
    val account = currentAccount() ?: return emptyList()
    return coroutineScope {
      client.info.userVaultEquities(account)
        .map { equity ->
          async {
            val details = client.info.vaultDetails(equity.vaultAddress, account)
            equity.toVaultPosition(details.toVaultSummary(details.isProtocolVault), details)
          }
        }
        .map { it.await() }
    }
  }

  private suspend fun vaultSummary(address: String): VaultSummary =
    client.info.vaultDetails(address).let { it.toVaultSummary(it.isProtocolVault) }

  private val xyz.mcxross.flare.hyperliquid.model.VaultDetails.isProtocolVault: Boolean
    get() = vaultAddress.equals(protocolVault, ignoreCase = true)

  override suspend fun selectTradingAccount(subaccount: String, prompt: VaultPrompt) {
    require(subaccount.isNotBlank()) { "Select a trading account" }
    preferences.setSelectedSubaccount(subaccount)
    refresh()
    refreshHistory()
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

  private data class RawState(
    val account: String?,
    val perp: ClearinghouseState? = null,
    val spot: SpotClearinghouseState? = null,
    val orders: List<FrontendOrder> = emptyList(),
    val abstraction: AccountAbstraction? = null,
    val vaultEquities: List<VaultEquity>? = null,
    /** Running TWAPs, once the stream has reported them. */
    val twaps: List<Pair<Long, TwapState>>? = null,
  )

  private class AccountRead(
    val perp: ClearinghouseState,
    val spot: SpotClearinghouseState,
    val orders: List<FrontendOrder>,
    val abstraction: AccountAbstraction?,
    val vaultEquities: List<VaultEquity>?,
  )

  private class HistoryRead(
    val orders: List<xyz.mcxross.flare.hyperliquid.model.OrderWithStatus>,
    val trades: HistoryPage<Fill>,
    val funding: HistoryPage<FundingPayment>,
    val transfers: HistoryPage<FundMovement>,
  )

  private companion object {
    const val NO_ACCOUNT = "No trading account is selected"
    const val HISTORY_PAGE_SIZE = 50
    const val FILLS_CAP = 2000
    const val FUNDING_CAP = 500
    const val LEDGER_CAP = 2000
    const val PRICE_REFRESH_MS = 60_000L
    const val RECOVERY_INTERVAL_MS = 30_000L
    const val TWAP_ACTIVE = "activated"
    const val MAINNET_HLP = "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"
    const val TESTNET_HLP = "0xa15099a30bbf2e68942d6f4c43d70d04faeab0a0"

    fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
  }
}

/** Hyperliquid addresses are case-insensitive: the API answers in lower case, the wallet in EIP-55. */
internal fun String?.sameAs(other: String?): Boolean =
  this != null && other != null && equals(other, ignoreCase = true)

internal const val ACTIONS_UNAVAILABLE = "Trading on Hyperliquid isn’t available in Flare yet."

internal fun unavailable(): ActionState.Failed =
  ActionState.Failed(ACTIONS_UNAVAILABLE, definitelyNotSubmitted = true)
