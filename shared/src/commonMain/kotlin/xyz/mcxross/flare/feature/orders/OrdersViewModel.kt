package xyz.mcxross.flare.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mcxross.flare.core.ReadOperation
import xyz.mcxross.flare.core.ReadRecoveryDiagnostics
import xyz.mcxross.flare.core.isReadAccessFailure
import xyz.mcxross.flare.core.readAccessMessage
import xyz.mcxross.flare.core.readBackoffMs
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSetupGate
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.SetupNeed
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.data.gatedBy
import xyz.mcxross.flare.data.failureMessage
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OpenOrderRef
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.TwapOrder

enum class OrdersSection(val label: String) {
  OPEN("Open"),
  TWAP("TWAP"),
  ORDERS("Orders"),
  TRADES("Trades"),
  FUNDING("Funding"),
  TRANSFERS("Transfers"),
}

data class OrdersUiState(
  /** What the venue supports; screens hide the rest. */
  val capabilities: VenueCapabilities = VenueCapabilities.All,
  /** What the wallet still needs when its setup was left for later; its actions wait meanwhile. */
  val setupNeed: SetupNeed? = null,
  val profile: WalletProfile = WalletProfile(),
  val marketSymbols: Map<String, String> = emptyMap(),
  val spotMarkets: Set<String> = emptySet(),
  val account: AccountSnapshot = AccountSnapshot(),
  val history: AccountHistorySnapshot = AccountHistorySnapshot(),
  val activeTwaps: List<TwapOrder> = emptyList(),
  val twapHistory: List<TwapOrder> = emptyList(),
  val section: OrdersSection = OrdersSection.OPEN,
  val transaction: ActionState? = null,
  val busy: Boolean = false,
  val error: String? = null,
  val readUnavailable: Boolean = false,
  val readAccessError: String? = null,
  val lastCancelOrderId: String? = null,
  val lastCancelTwapId: String? = null,
)

sealed interface OrdersIntent {
  data class SelectSection(val section: OrdersSection) : OrdersIntent

  data object LoadMore : OrdersIntent

  data class Cancel(val market: String, val orderId: String, val isTpSl: Boolean) : OrdersIntent

  data class CancelTwap(val market: String, val twapId: String) : OrdersIntent

  data object CancelAll : OrdersIntent

  data object RefreshTwaps : OrdersIntent

  data object RetryAccount : OrdersIntent
}

class OrdersViewModel(
  private val accounts: AccountRepository,
  private val wallets: WalletRepository,
  private val trading: TradingRepository,
  private val markets: MarketsRepository,
  venue: VenueDescriptor,
  private val setupGate: AccountSetupGate? = null,
) : ViewModel() {
  private val capabilities = venue.capabilities
  private val local = MutableStateFlow(OrdersUiState(capabilities = capabilities))

  init {
    setupGate?.let { gate ->
      viewModelScope.launch {
        gate.need.collect { need ->
          local.update { it.copy(setupNeed = need, capabilities = capabilities.gatedBy(need)) }
        }
      }
    }
    viewModelScope.launch {
      accounts.snapshot.collect {
        if (local.value.section == OrdersSection.TWAP) {
          refreshTwaps()
        }
      }
    }
  }

  val uiState: StateFlow<OrdersUiState> =
    combine(local, wallets.profile, accounts.snapshot, accounts.history) {
        state,
        profile,
        account,
        history ->
        state.copy(profile = profile, account = account, history = history)
      }
      .combine(markets.catalog) { state, catalog ->
        state.copy(
          marketSymbols = catalog.quotes.associate { it.market.id to it.market.symbol },
          spotMarkets =
            catalog.quotes
              .filter { it.market.kind == MarketKind.SPOT }
              .map { it.market.id }
              .toSet(),
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrdersUiState())

  suspend fun followHistoryWhileVisible() {
    data class Context(
      val section: OrdersSection,
      val owner: String?,
      val api: String?,
      val account: String?,
    )
    combine(
        local.map { it.section }.distinctUntilChanged(),
        wallets.profile,
        accounts.snapshot.map { it.account }.distinctUntilChanged(),
      ) { section, wallet, account ->
        Context(section, wallet.ownerAddress, wallet.apiWalletAddress, account)
      }
      .distinctUntilChanged()
      .collectLatest { context ->
        val history = context.section !in listOf(OrdersSection.OPEN, OrdersSection.TWAP)
        accounts.setHistoryVisible(history)
        local.update { it.copy(readUnavailable = false, readAccessError = null) }
        try {
          if (context.account == null || context.owner == null && context.api == null)
            awaitCancellation()
          var failures = 0
          var first = true
          while (currentCoroutineContext().isActive) {
            val snapshot = accounts.snapshot.value
            val priorHistory = accounts.history.value
            val needsRead =
              snapshot.stale ||
                snapshot.error != null ||
                history && (first || priorHistory.stale || priorHistory.error != null)
            if (needsRead) {
              val generation = wallets.authorizationGeneration
              val result =
                withTimeoutOrNull(10_000) {
                  runSuspendCatching {
                    wallets.requireAuthorization(generation)
                    if (snapshot.stale || snapshot.error != null) accounts.restoreTrading()
                    else if (history) accounts.refreshHistory()
                  }
                }
              currentCoroutineContext().ensureActive()
              if (wallets.authorizationGeneration != generation) awaitCancellation()
              val current = accounts.snapshot.value
              val currentHistory = accounts.history.value
              val failed =
                result == null ||
                  result.isFailure ||
                  current.stale ||
                  current.error != null ||
                  history && (currentHistory.stale || currentHistory.error != null)
              if (failed) {
                failures++
                ReadRecoveryDiagnostics.failed(
                  ReadOperation.ACTIVITY,
                  failures,
                  result?.exceptionOrNull(),
                  result == null,
                )
                val access = result?.exceptionOrNull()?.takeIf { it.isReadAccessFailure() }
                local.update {
                  it.copy(
                    readUnavailable = failures >= 3 || access != null,
                    readAccessError = access?.readAccessMessage(),
                  )
                }
              } else {
                failures = 0
                ReadRecoveryDiagnostics.recovered(ReadOperation.ACTIVITY)
                local.update { it.copy(readUnavailable = false, readAccessError = null) }
              }
            }
            first = false
            delay(if (failures > 0) readBackoffMs(failures) else 15_000)
          }
        } finally {
          accounts.setHistoryVisible(false)
        }
      }
  }

  fun onIntent(intent: OrdersIntent) {
    when (intent) {
      OrdersIntent.RetryAccount ->
        viewModelScope.launch {
          runSuspendCatching { accounts.restoreTrading() }
        }
      is OrdersIntent.SelectSection -> {
        local.update { it.copy(section = intent.section) }
        if (intent.section == OrdersSection.TWAP) {
          refreshTwaps()
        }
      }
      OrdersIntent.LoadMore -> loadMore()
      is OrdersIntent.Cancel -> cancel(intent.market, intent.orderId, intent.isTpSl)
      is OrdersIntent.CancelTwap -> cancelTwap(intent.market, intent.twapId)
      OrdersIntent.CancelAll -> cancelAll()
      OrdersIntent.RefreshTwaps -> refreshTwaps()
    }
  }

  private fun loadMore() =
    launchAction("More activity couldn’t be loaded.") {
      val kind =
        when (local.value.section) {
          OrdersSection.OPEN,
          OrdersSection.TWAP -> return@launchAction
          OrdersSection.ORDERS -> AccountHistoryKind.ORDERS
          OrdersSection.TRADES -> AccountHistoryKind.TRADES
          OrdersSection.FUNDING -> AccountHistoryKind.FUNDING
          OrdersSection.TRANSFERS -> AccountHistoryKind.TRANSFERS
        }
      accounts.loadMoreHistory(kind)
    }

  private fun cancel(market: String, orderId: String, isTpSl: Boolean) =
    launchAction("Your order is still open.") {
      local.update { it.copy(lastCancelOrderId = orderId) }
      val isSpot =
        markets.catalog.value.quotes.firstOrNull { it.market.id == market }?.market?.kind ==
          MarketKind.SPOT
      val prompt =
        VaultPrompt(if (isTpSl) "Cancel TP/SL" else "Cancel order", "Confirm your identity")
      confirmedWithAccountRead(prompt) {
        val subaccount = checkNotNull(uiState.value.account.account)
        trading
          .execute(
            if (isTpSl) {
              TradeAction.CancelPositionTpSl(subaccount, market, orderId)
            } else {
              TradeAction.CancelOrder(
                subaccount,
                market,
                orderId,
                if (isSpot) MarketKind.SPOT else MarketKind.PERP,
              )
            },
            prompt,
            FeePayment.WALLET_FALLBACK,
          )
          .collect { state -> local.update { it.copy(transaction = state) } }
      }
      when (val state = local.value.transaction) {
        is ActionState.Committed -> {
          accounts.refresh()
          accounts.refreshHistory()
        }
        is ActionState.Failed -> error(state.failureMessage())
        else -> Unit
      }
    }

  private fun cancelTwap(market: String, twapId: String) =
    launchAction("Your TWAP order wasn’t cancelled.") {
      val subaccount = checkNotNull(uiState.value.account.account)
      local.update { it.copy(lastCancelTwapId = twapId) }
      trading
        .execute(
          TradeAction.CancelTwapOrder(subaccount, market, twapId),
          VaultPrompt("Cancel TWAP order", "Confirm your identity"),
          FeePayment.WALLET_FALLBACK,
        )
        .collect { state -> local.update { it.copy(transaction = state) } }
      when (val state = local.value.transaction) {
        is ActionState.Committed -> {
          refreshTwaps()
        }
        is ActionState.Failed -> error(state.failureMessage())
        else -> Unit
      }
    }

  private fun cancelAll() =
    launchAction("Some orders couldn’t be cancelled.") {
      if (uiState.value.account.openOrders.isEmpty()) return@launchAction
      var batchFailure: ActionState.Failed? = null
      confirmedWithAccountRead(VaultPrompt("Cancel all orders", "Confirm your identity")) {
        val openOrders = uiState.value.account.openOrders
        if (openOrders.isEmpty()) return@confirmedWithAccountRead
        val subaccount = checkNotNull(uiState.value.account.account)
        if (capabilities.batchCancel) {
          trading
            .execute(
              TradeAction.CancelOrders(
                subaccount,
                openOrders.map { OpenOrderRef(it.marketId, it.orderId) },
              ),
              VaultPrompt("Cancel all orders", "Confirm your identity"),
              FeePayment.WALLET_FALLBACK,
            )
            .collect { state -> local.update { it.copy(transaction = state) } }
          batchFailure = local.value.transaction as? ActionState.Failed
          return@confirmedWithAccountRead
        }
        for (order in openOrders) {
          val isSpot =
            markets.catalog.value.quotes
              .firstOrNull { it.market.id == order.marketId }
              ?.market
              ?.kind == MarketKind.SPOT || order.kind == MarketKind.SPOT
          val action =
            if (order.isPositionTpSl) {
              TradeAction.CancelPositionTpSl(subaccount, order.marketId, order.orderId)
            } else {
              TradeAction.CancelOrder(
                subaccount,
                order.marketId,
                order.orderId,
                if (isSpot) MarketKind.SPOT else MarketKind.PERP,
              )
            }
          trading
            .execute(
              action,
              VaultPrompt("Cancel order", "Confirm your identity"),
              FeePayment.WALLET_FALLBACK,
            )
            .collect { state -> local.update { it.copy(transaction = state) } }
        }
      }
      accounts.refresh()
      accounts.refreshHistory()
      batchFailure?.let { error(it.failureMessage()) }
    }

  /**
   * Asks for confirmation at once and re-reads the account meanwhile; [block] runs, and so signs,
   * only after both.
   */
  private suspend fun confirmedWithAccountRead(prompt: VaultPrompt, block: suspend () -> Unit) =
    coroutineScope {
      val accountRead = async { runSuspendCatching { accounts.refresh() } }
      trading.confirmedAction(prompt) {
        accountRead.await().getOrThrow()
        block()
      }
    }

  fun refreshTwaps() {
    if (!capabilities.twap) return
    viewModelScope.launch {
      val active = runCatching { accounts.activeTwaps() }.getOrDefault(emptyList())
      val history = runCatching { accounts.twapHistory() }.getOrDefault(emptyList())
      local.update { it.copy(activeTwaps = active, twapHistory = history) }
    }
  }

  /** [outcome] states what did not happen, so a failure reads as a result instead of a log line. */
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
}
