package xyz.mcxross.flare.data

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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.AccountOpenOrders
import xyz.mcxross.flare.decibel.api.AccountOverviewTopic
import xyz.mcxross.flare.decibel.api.AccountPositions
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.DecibelStreamData
import xyz.mcxross.flare.decibel.api.DecibelStreamTopic
import xyz.mcxross.flare.decibel.api.OrderUpdates
import xyz.mcxross.flare.decibel.api.StreamEvent
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.api.UserTrades
import xyz.mcxross.flare.decibel.model.AccountOverview
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.FundingPayment
import xyz.mcxross.flare.decibel.model.MarketTrade
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.Page
import xyz.mcxross.flare.decibel.model.Position
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.decibel.model.toChainUnits
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences

data class AccountSnapshot(
  val account: String? = null,
  val overview: AccountOverview? = null,
  val positions: List<Position> = emptyList(),
  val openOrders: List<Order> = emptyList(),
  val loading: Boolean = false,
  val stale: Boolean = true,
  val error: String? = null,
)

enum class AccountHistoryKind {
  ORDERS,
  TRADES,
  FUNDING,
}

data class AccountHistorySnapshot(
  val account: String? = null,
  val orders: List<Order> = emptyList(),
  val trades: List<MarketTrade> = emptyList(),
  val funding: List<FundingPayment> = emptyList(),
  val ordersHasMore: Boolean = false,
  val tradesHasMore: Boolean = false,
  val fundingHasMore: Boolean = false,
  val loading: Boolean = false,
  val loadingMore: Boolean = false,
  val stale: Boolean = true,
  val error: String? = null,
)

interface AccountRepository {
  val snapshot: StateFlow<AccountSnapshot>
  val history: StateFlow<AccountHistorySnapshot>

  suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<Subaccount>

  suspend fun connectOwner(subaccount: String, prompt: VaultPrompt)

  suspend fun connectApi(subaccount: String, prompt: VaultPrompt)

  suspend fun createSubaccount(prompt: VaultPrompt): TransactionState

  suspend fun delegateApiWallet(prompt: VaultPrompt): TransactionState

  suspend fun prepareTradingWallet(prompt: VaultPrompt)

  fun depositUsdc(
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<TransactionState>

  fun withdrawUsdc(
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<TransactionState>

  suspend fun refresh()

  suspend fun refreshHistory()

  suspend fun loadMoreHistory(kind: AccountHistoryKind)

  fun startLive()

  suspend fun disconnect()
}

class DefaultAccountRepository(
  private val client: DecibelClient,
  private val sessions: SessionRepository,
  private val trading: TradingRepository,
  private val wallets: WalletRepository,
  private val preferences: AppPreferences,
) : AccountRepository {
  private val mutableSnapshot = MutableStateFlow(AccountSnapshot())
  private val mutableHistory = MutableStateFlow(AccountHistorySnapshot())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val refreshMutex = Mutex()
  private val historyMutex = Mutex()
  private val tradingWalletSetup = TradingWalletSetup()
  private var streamJob: Job? = null
  private var streamingAccount: String? = null
  override val snapshot: StateFlow<AccountSnapshot> = mutableSnapshot.asStateFlow()
  override val history: StateFlow<AccountHistorySnapshot> = mutableHistory.asStateFlow()

  override suspend fun discoverOwnerSubaccounts(prompt: VaultPrompt): List<Subaccount> {
    val owner =
      wallets.profile.first().ownerAddress
        ?: error("An owner wallet is required to discover subaccounts")
    sessions.authenticateOwner(subaccount = null, prompt = prompt)
    return client.accounts.subaccounts(owner).filter(Subaccount::isActive)
  }

  override suspend fun connectOwner(subaccount: String, prompt: VaultPrompt) {
    require(subaccount.isNotBlank()) { "Select a Decibel subaccount" }
    sessions.authenticateOwner(subaccount = subaccount, prompt = prompt)
    preferences.setSelectedSubaccount(subaccount)
    trading.reconcilePending()
    refresh()
    refreshHistory()
    startLive()
  }

  override suspend fun connectApi(subaccount: String, prompt: VaultPrompt) {
    require(subaccount.isNotBlank()) { "Enter the delegated Decibel subaccount" }
    sessions.authenticateApi(subaccount = subaccount, prompt = prompt)
    preferences.setSelectedSubaccount(subaccount)
    trading.reconcilePending()
    refresh()
    refreshHistory()
    startLive()
  }

  override suspend fun createSubaccount(prompt: VaultPrompt): TransactionState {
    sessions.authenticateOwner(null, prompt.copy(requireFreshAuthorization = true))
    var terminal: TransactionState = TransactionState.Failed("Subaccount transaction did not start")
    trading
      .execute(
        DecibelCommand.CreateSubaccount,
        TradingSigner.OWNER,
        prompt.copy(requireFreshAuthorization = true),
      )
      .collect { state ->
        terminal = state
      }
    return terminal
  }

  override suspend fun delegateApiWallet(prompt: VaultPrompt): TransactionState {
    val profile = wallets.profile.first()
    val subaccount =
      preferences.values.first().selectedSubaccount
        ?: error("Select a Decibel subaccount before delegating an API wallet")
    val apiAddress = profile.apiWalletAddress ?: error("Create or import an API wallet first")
    sessions.authenticateOwner(subaccount, prompt.copy(requireFreshAuthorization = true))
    var terminal: TransactionState = TransactionState.Failed("Delegation transaction did not start")
    trading
      .execute(
        command = DecibelCommand.DelegateTrading(subaccount, apiAddress),
        signer = TradingSigner.OWNER,
        prompt = prompt.copy(requireFreshAuthorization = true),
      )
      .collect { state -> terminal = state }
    return terminal
  }

  override suspend fun prepareTradingWallet(prompt: VaultPrompt) {
    val subaccount =
      preferences.values.first().selectedSubaccount
        ?: error("Select a Decibel subaccount before preparing trading")
    tradingWalletSetup.prepare(
      object : TradingWalletSetupActions {
        override suspend fun authorizeOwner() {
          sessions.authenticateOwner(subaccount, prompt.copy(requireFreshAuthorization = true))
        }

        override suspend fun existingWallet() = wallets.profile.first().apiWalletAddress

        override suspend fun createWallet() = wallets.createApiWallet(prompt)

        override suspend fun isDelegated(address: String): Boolean =
          client.accounts.delegations(subaccount).any {
            it.delegate.sameAptosAddress(address) &&
              it.canTradeAllPerpMarkets &&
              (it.expirationTimeSeconds?.let { expiry ->
                expiry == 0L || expiry > Clock.System.now().epochSeconds
              } ?: true)
          }

        override suspend fun requireNoPendingTransactions() {
          trading.reconcilePending()
          check(trading.pendingTransactions.first().isEmpty()) {
            "Resolve pending transactions before submitting another delegation"
          }
        }

        override suspend fun delegate() = delegateApiWallet(prompt)

        override suspend fun connectApi() =
          this@DefaultAccountRepository.connectApi(subaccount, prompt)
      }
    )
  }

  override fun depositUsdc(
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<TransactionState> = fundingFlow(amount, prompt, deposit = true, feePayment)

  override fun withdrawUsdc(
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<TransactionState> = fundingFlow(amount, prompt, deposit = false, feePayment)

  private fun fundingFlow(
    amount: String,
    prompt: VaultPrompt,
    deposit: Boolean,
    feePayment: FeePayment,
  ): Flow<TransactionState> = flow {
    val subaccount =
      preferences.values.first().selectedSubaccount ?: error("Select a Decibel subaccount first")
    val units = DecimalInput(amount).toChainUnits("USDC amount", USDC_DECIMALS).getOrThrow()
    require(units > 0uL) { "USDC amount must be greater than zero" }
    sessions.authenticateOwner(
      subaccount = subaccount,
      prompt = prompt.copy(requireFreshAuthorization = true),
    )
    val command =
      if (deposit) {
        DecibelCommand.Deposit(
          subaccount = subaccount,
          assetMetadata = client.config.deployment.usdcMetadataAddress,
          amount = units,
        )
      } else {
        DecibelCommand.Withdraw(
          subaccount = subaccount,
          assetMetadata = client.config.deployment.usdcMetadataAddress,
          amount = units,
        )
      }
    emitAll(
      trading.execute(
        command = command,
        signer = TradingSigner.OWNER,
        prompt = prompt.copy(requireFreshAuthorization = false),
        feePayment = feePayment,
      )
    )
  }

  override suspend fun refresh() = refreshMutex.withLock {
    val account = preferences.values.first().selectedSubaccount
    if (account == null) {
      mutableSnapshot.value = AccountSnapshot(error = "No Decibel subaccount is selected")
      return@withLock
    }
    if (sessions.status.value?.role == SessionRole.ANONYMOUS || sessions.status.value == null) {
      mutableSnapshot.value =
        AccountSnapshot(account = account, error = "Unlock the wallet to load live account data")
      return@withLock
    }
    mutableSnapshot.update { it.copy(account = account, loading = true, error = null) }
    runSuspendCatching {
      coroutineScope {
        val overview = async { client.accounts.overview(account) }
        val positions = async { client.accounts.positions(account) }
        val orders = async { client.accounts.openOrders(account) }
        Triple(overview.await(), positions.await(), orders.await())
      }
    }
      .onSuccess { (overview, positions, orders) ->
        mutableSnapshot.value =
          AccountSnapshot(
            account = account,
            overview = overview,
            positions = positions.filterNot(Position::isDeleted),
            openOrders = orders.items,
            stale = false,
          )
      }
      .onFailure { error ->
        mutableSnapshot.update {
          it.copy(
            loading = false,
            stale = true,
            error = error.message ?: "Account data is unavailable",
          )
        }
      }
  }

  override suspend fun refreshHistory() = historyMutex.withLock {
    val account = preferences.values.first().selectedSubaccount
    if (account == null) {
      mutableHistory.value = AccountHistorySnapshot(error = "No Decibel subaccount is selected")
      return@withLock
    }
    if (sessions.status.value?.role == SessionRole.ANONYMOUS || sessions.status.value == null) {
      mutableHistory.value =
        AccountHistorySnapshot(
          account = account,
          error = "Unlock the wallet to load account history",
        )
      return@withLock
    }
    mutableHistory.update { it.copy(account = account, loading = true, error = null) }
    runSuspendCatching {
      coroutineScope {
        val orders = async { client.accounts.orderHistory(account, HISTORY_PAGE_SIZE, 0) }
        val trades = async { client.accounts.tradeHistory(account, HISTORY_PAGE_SIZE, 0) }
        val funding = async { client.accounts.fundingHistory(account, HISTORY_PAGE_SIZE, 0) }
        Triple(orders.await(), trades.await(), funding.await())
      }
    }
      .onSuccess { (orders, trades, funding) ->
        mutableHistory.value =
          AccountHistorySnapshot(
            account = account,
            orders = orders.items,
            trades = trades.items,
            funding = funding.items,
            ordersHasMore = orders.hasMore(0),
            tradesHasMore = trades.hasMore(0),
            fundingHasMore = funding.hasMore(0),
            stale = false,
          )
      }
      .onFailure { error ->
        mutableHistory.update {
          it.copy(
            loading = false,
            stale = true,
            error = error.message ?: "Account history is unavailable",
          )
        }
      }
  }

  override suspend fun loadMoreHistory(kind: AccountHistoryKind) = historyMutex.withLock {
    val current = mutableHistory.value
    val account = current.account ?: return@withLock
    val shouldLoad =
      when (kind) {
        AccountHistoryKind.ORDERS -> current.ordersHasMore
        AccountHistoryKind.TRADES -> current.tradesHasMore
        AccountHistoryKind.FUNDING -> current.fundingHasMore
      }
    if (!shouldLoad || current.loading || current.loadingMore) return@withLock
    mutableHistory.update { it.copy(loadingMore = true, error = null) }
    runSuspendCatching {
      when (kind) {
        AccountHistoryKind.ORDERS ->
          HistoryResult.Orders(
            client.accounts.orderHistory(account, HISTORY_PAGE_SIZE, current.orders.size)
          )
        AccountHistoryKind.TRADES ->
          HistoryResult.Trades(
            client.accounts.tradeHistory(account, HISTORY_PAGE_SIZE, current.trades.size)
          )
        AccountHistoryKind.FUNDING ->
          HistoryResult.Funding(
            client.accounts.fundingHistory(account, HISTORY_PAGE_SIZE, current.funding.size)
          )
      }
    }
      .onSuccess { result ->
        mutableHistory.update { state ->
          when (result) {
            is HistoryResult.Orders ->
              state.copy(
                orders = state.orders + result.page.items,
                ordersHasMore = result.page.hasMore(state.orders.size),
                loadingMore = false,
              )
            is HistoryResult.Trades ->
              state.copy(
                trades = state.trades + result.page.items,
                tradesHasMore = result.page.hasMore(state.trades.size),
                loadingMore = false,
              )
            is HistoryResult.Funding ->
              state.copy(
                funding = state.funding + result.page.items,
                fundingHasMore = result.page.hasMore(state.funding.size),
                loadingMore = false,
              )
          }
        }
      }
      .onFailure { error ->
        mutableHistory.update {
          it.copy(
            loadingMore = false,
            error = error.message ?: "Unable to load more account history",
          )
        }
      }
  }

  override fun startLive() {
    val account = mutableSnapshot.value.account ?: return
    if (
      sessions.status.value?.role == null || sessions.status.value?.role == SessionRole.ANONYMOUS
    ) {
      return
    }
    if (streamJob?.isActive == true && streamingAccount == account) return
    streamJob?.cancel()
    streamingAccount = account
    streamJob = scope.launch { collectAccountStream(account) }
  }

  private suspend fun collectAccountStream(account: String) {
    var lastBackfillAtMs = 0L
    var lastHistoryBackfillAtMs = 0L
    val userTrades = UserTrades(account)
    val topics: Set<DecibelStreamTopic> =
      setOf(
        AccountOverviewTopic(account),
        AccountPositions(account),
        AccountOpenOrders(account),
        OrderUpdates(account),
        userTrades,
      )
    client.stream.subscribe(topics).collect { event ->
      when (event) {
        is StreamEvent.Connected -> {
          refresh()
          refreshHistory()
          lastBackfillAtMs = Clock.System.now().toEpochMilliseconds()
          lastHistoryBackfillAtMs = lastBackfillAtMs
        }
        is StreamEvent.Message -> {
          val now = Clock.System.now().toEpochMilliseconds()
          when (val data = event.data) {
            is DecibelStreamData.AccountOverviewValue ->
              mutableSnapshot.update {
                it.copy(overview = data.value, stale = false, error = null)
              }
            is DecibelStreamData.AccountPositionsValue ->
              mutableSnapshot.update {
                it.copy(
                  positions = data.values.filterNot(Position::isDeleted),
                  stale = false,
                  error = null,
                )
              }
            is DecibelStreamData.AccountOpenOrdersValue ->
              mutableSnapshot.update {
                it.copy(openOrders = data.values, stale = false, error = null)
              }
            is DecibelStreamData.MarketTrades ->
              if (event.topic == userTrades) {
                mutableHistory.update { current ->
                  current.copy(
                    trades =
                      (data.values + current.trades)
                        .distinctBy(MarketTrade::stableIdentity)
                        .sortedByDescending(MarketTrade::transactionUnixMs)
                        .take(HISTORY_PAGE_SIZE),
                    stale = false,
                    error = null,
                  )
                }
              }
            else -> Unit
          }
          if (
            (event.topic is OrderUpdates || event.data is DecibelStreamData.Malformed) &&
              now - lastBackfillAtMs >= BACKFILL_INTERVAL_MS
          ) {
            refresh()
            lastBackfillAtMs = now
          }
          if (
            event.topic is OrderUpdates && now - lastHistoryBackfillAtMs >= BACKFILL_INTERVAL_MS
          ) {
            refreshHistory()
            lastHistoryBackfillAtMs = now
          }
        }
        is StreamEvent.SequenceGap -> {
          refresh()
          refreshHistory()
          lastBackfillAtMs = Clock.System.now().toEpochMilliseconds()
          lastHistoryBackfillAtMs = lastBackfillAtMs
        }
        is StreamEvent.Rejected -> {
          mutableSnapshot.update { it.copy(stale = true, error = event.reason) }
          mutableHistory.update { it.copy(stale = true, error = event.reason) }
        }
        is StreamEvent.Disconnected ->
          (event.reason ?: "Account stream disconnected").let { reason ->
            mutableSnapshot.update { it.copy(stale = true, error = reason) }
            mutableHistory.update { it.copy(stale = true, error = reason) }
          }
      }
    }
  }

  override suspend fun disconnect() {
    streamJob?.cancel()
    streamJob = null
    streamingAccount = null
    try {
      sessions.invalidate()
    } finally {
      wallets.lock()
      mutableSnapshot.update { it.copy(stale = true, error = "Wallet session is locked") }
      mutableHistory.update { it.copy(stale = true, error = "Wallet session is locked") }
    }
  }

  private companion object {
    const val BACKFILL_INTERVAL_MS = 2_000L
    const val USDC_DECIMALS = 6
    const val HISTORY_PAGE_SIZE = 50
  }
}

private fun MarketTrade.stableIdentity(): String = tradeId.ifBlank {
  "$transactionVersion:$market:$account:$price:$size"
}

private sealed interface HistoryResult {
  data class Orders(val page: Page<Order>) : HistoryResult

  data class Trades(val page: Page<MarketTrade>) : HistoryResult

  data class Funding(val page: Page<FundingPayment>) : HistoryResult
}

private fun <T> Page<T>.hasMore(offset: Int): Boolean {
  val knownTotal = totalCount
  return items.size == 50 && (knownTotal == null || (offset + items.size).toLong() < knownTotal)
}
