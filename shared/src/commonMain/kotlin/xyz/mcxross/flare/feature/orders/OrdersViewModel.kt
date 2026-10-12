package xyz.mcxross.flare.feature.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.data.AccountHistoryKind
import xyz.mcxross.flare.data.AccountHistorySnapshot
import xyz.mcxross.flare.data.AccountRepository
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.data.tradingKeyTopUpFor
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.VenueDescriptor
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
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
  val lastCancelMarket: String? = null,
  val lastCancelOrderId: String? = null,
  val lastCancelIsTpSl: Boolean = false,
  val lastCancelTwapMarket: String? = null,
  val lastCancelTwapId: String? = null,
  val apiWalletNeedsTopUp: Boolean = false,
  val suggestedTopUpOctas: ULong? = null,
  val topUpTransaction: ActionState? = null,
) {
  /**
   * What the wallet would pay for the last action's network fee once the venue's sponsor declined
   * it; null when it wasn't declined, or the venue has no sponsored fees.
   */
  val selfPayEstimate: ULong?
    get() =
      if (capabilities.sponsoredFees) (transaction as? ActionState.Failed)?.selfPay?.estimate
      else null
}

sealed interface OrdersIntent {
  data class SelectSection(val section: OrdersSection) : OrdersIntent

  data object LoadMore : OrdersIntent

  data class Cancel(val market: String, val orderId: String, val isTpSl: Boolean) : OrdersIntent

  data class CancelTwap(val market: String, val twapId: String) : OrdersIntent

  data object CancelAll : OrdersIntent

  data object ConfirmSelfPay : OrdersIntent

  data object TopUpApiWallet : OrdersIntent

  data object RefreshTwaps : OrdersIntent

  data object RetryAccount : OrdersIntent
}

class OrdersViewModel(
  private val accounts: AccountRepository,
  wallets: WalletRepository,
  private val trading: TradingRepository,
  private val markets: MarketsRepository,
  venue: VenueDescriptor,
) : ViewModel() {
  private val capabilities = venue.capabilities
  private val local = MutableStateFlow(OrdersUiState(capabilities = capabilities))

  init {
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

  fun onIntent(intent: OrdersIntent) {
    when (intent) {
      OrdersIntent.RetryAccount -> viewModelScope.launch {
        runSuspendCatching { accounts.restoreTrading() }
      }
      is OrdersIntent.SelectSection -> {
        local.update { it.copy(section = intent.section) }
        if (intent.section == OrdersSection.TWAP) {
          refreshTwaps()
        }
      }
      OrdersIntent.LoadMore -> loadMore()
      is OrdersIntent.Cancel ->
        cancel(intent.market, intent.orderId, intent.isTpSl, FeePayment.SPONSORED)
      is OrdersIntent.CancelTwap ->
        cancelTwap(intent.market, intent.twapId, FeePayment.SPONSORED)
      OrdersIntent.CancelAll -> cancelAll()
      OrdersIntent.ConfirmSelfPay -> {
        val state = local.value
        if (state.lastCancelMarket != null && state.lastCancelOrderId != null) {
          cancel(
            state.lastCancelMarket,
            state.lastCancelOrderId,
            state.lastCancelIsTpSl,
            FeePayment.SELF_PAY,
          )
        } else if (state.lastCancelTwapMarket != null && state.lastCancelTwapId != null) {
          cancelTwap(
            state.lastCancelTwapMarket,
            state.lastCancelTwapId,
            FeePayment.SELF_PAY,
          )
        }
      }
      OrdersIntent.TopUpApiWallet -> topUpApiWallet()
      OrdersIntent.RefreshTwaps -> refreshTwaps()
    }
  }

  private fun loadMore() =
    launchAction("More activity couldn’t be loaded.") {
      val kind =
        when (local.value.section) {
          OrdersSection.OPEN, OrdersSection.TWAP -> return@launchAction
          OrdersSection.ORDERS -> AccountHistoryKind.ORDERS
          OrdersSection.TRADES -> AccountHistoryKind.TRADES
          OrdersSection.FUNDING -> AccountHistoryKind.FUNDING
          OrdersSection.TRANSFERS -> AccountHistoryKind.TRANSFERS
        }
      accounts.loadMoreHistory(kind)
    }

  private fun cancel(market: String, orderId: String, isTpSl: Boolean, feePayment: FeePayment) =
    launchAction("Your order is still open.") {
      accounts.refresh()
      val subaccount = checkNotNull(uiState.value.account.account)
      local.update {
        it.copy(
          lastCancelMarket = market,
          lastCancelOrderId = orderId,
          lastCancelIsTpSl = isTpSl,
          apiWalletNeedsTopUp = false,
          suggestedTopUpOctas = null,
        )
      }
      val isSpot =
        markets.catalog.value.quotes
          .firstOrNull { it.market.id == market }
          ?.market
          ?.kind == MarketKind.SPOT
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
          VaultPrompt(if (isTpSl) "Cancel TP/SL" else "Cancel order", "Confirm your identity"),
          feePayment,
        )
        .collect { state -> local.update { it.copy(transaction = state) } }
      when (val state = local.value.transaction) {
        is ActionState.Committed -> {
          accounts.refresh()
          accounts.refreshHistory()
        }
        is ActionState.Failed -> {
          if (state.selfPay == null) error(state.message)
          if (capabilities.networkFeeTopUp) {
            trading.tradingKeyTopUpFor(state, uiState.value.profile)?.let { topUp ->
              local.update { it.copy(apiWalletNeedsTopUp = true, suggestedTopUpOctas = topUp) }
            }
          }
        }
        else -> Unit
      }
    }

  private fun topUpApiWallet() =
    launchAction("The network fee wasn’t covered.") {
      val amount = local.value.suggestedTopUpOctas ?: error("No network-fee top-up is required")
      trading
        .topUpTradingKey(
          amount,
          VaultPrompt(
            "Cover network fees",
            "Confirm your identity",
            requireFreshAuthorization = true,
          ),
        )
        .collect { transaction -> local.update { it.copy(topUpTransaction = transaction) } }
      when (val terminal = local.value.topUpTransaction) {
        is ActionState.Committed ->
          local.update { it.copy(apiWalletNeedsTopUp = false, suggestedTopUpOctas = null) }
        is ActionState.Failed -> error(terminal.message)
        else -> Unit
      }
    }

  private fun cancelTwap(market: String, twapId: String, feePayment: FeePayment) =
    launchAction("Your TWAP order wasn’t cancelled.") {
      val subaccount = checkNotNull(uiState.value.account.account)
      local.update {
        it.copy(
          lastCancelTwapMarket = market,
          lastCancelTwapId = twapId,
          apiWalletNeedsTopUp = false,
          suggestedTopUpOctas = null,
        )
      }
      trading
        .execute(
          TradeAction.CancelTwapOrder(subaccount, market, twapId),
          VaultPrompt("Cancel TWAP order", "Confirm your identity"),
          feePayment,
        )
        .collect { state -> local.update { it.copy(transaction = state) } }
      when (val state = local.value.transaction) {
        is ActionState.Committed -> {
          refreshTwaps()
        }
        is ActionState.Failed -> {
          if (state.selfPay == null) error(state.message)
          if (capabilities.networkFeeTopUp) {
            trading.tradingKeyTopUpFor(state, uiState.value.profile)?.let { topUp ->
              local.update { it.copy(apiWalletNeedsTopUp = true, suggestedTopUpOctas = topUp) }
            }
          }
        }
        else -> Unit
      }
    }

  private fun cancelAll() =
    launchAction("Some orders couldn’t be cancelled.") {
      accounts.refresh()
      val openOrders = uiState.value.account.openOrders
      if (openOrders.isEmpty()) return@launchAction
      val subaccount = checkNotNull(uiState.value.account.account)
      trading.confirmedAction(VaultPrompt("Cancel all orders", "Confirm your identity")) {
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
              FeePayment.SPONSORED,
            )
            .collect { state -> local.update { it.copy(transaction = state) } }
        }
      }
      accounts.refresh()
      accounts.refreshHistory()
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
