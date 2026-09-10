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
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.TradingSigner
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences

enum class OrdersSection(val label: String) {
  OPEN("Open"),
  ORDERS("Orders"),
  TRADES("Trades"),
  FUNDING("Funding"),
}

data class OrdersUiState(
  val profile: WalletProfile = WalletProfile(),
  val marketSymbols: Map<String, String> = emptyMap(),
  val account: AccountSnapshot = AccountSnapshot(),
  val history: AccountHistorySnapshot = AccountHistorySnapshot(),
  val section: OrdersSection = OrdersSection.OPEN,
  val transaction: TransactionState? = null,
  val busy: Boolean = false,
  val error: String? = null,
  val sessionWalletAddress: String? = null,
  val lastCancelMarket: String? = null,
  val lastCancelOrderId: String? = null,
  val lastCancelIsTpSl: Boolean = false,
  val apiWalletNeedsTopUp: Boolean = false,
  val suggestedTopUpOctas: ULong? = null,
  val topUpTransaction: TransactionState? = null,
)

sealed interface OrdersIntent {
  data object Refresh : OrdersIntent

  data class SelectSection(val section: OrdersSection) : OrdersIntent

  data object LoadMore : OrdersIntent

  data class Cancel(val market: String, val orderId: String, val isTpSl: Boolean) : OrdersIntent

  data object ConfirmSelfPay : OrdersIntent

  data object TopUpApiWallet : OrdersIntent
}

class OrdersViewModel(
  private val accounts: AccountRepository,
  private val wallets: WalletRepository,
  private val sessions: SessionRepository,
  private val trading: TradingRepository,
  private val preferences: AppPreferences,
  private val markets: MarketsRepository,
) : ViewModel() {
  private val local = MutableStateFlow(OrdersUiState())
  val uiState: StateFlow<OrdersUiState> =
    combine(local, wallets.profile, accounts.snapshot, accounts.history, sessions.status) {
        state,
        profile,
        account,
        history,
        session ->
        state.copy(
          profile = profile,
          account = account,
          history = history,
          sessionWalletAddress = session?.walletAddress,
        )
      }
      .combine(markets.catalog) { state, catalog ->
        state.copy(
          marketSymbols = catalog.quotes.associate { it.market.address to it.market.symbol }
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrdersUiState())

  fun onIntent(intent: OrdersIntent) {
    when (intent) {
      OrdersIntent.Refresh ->
        launchAction {
          accounts.refresh()
          accounts.refreshHistory()
        }
      is OrdersIntent.SelectSection -> local.update { it.copy(section = intent.section) }
      OrdersIntent.LoadMore -> loadMore()
      is OrdersIntent.Cancel ->
        cancel(intent.market, intent.orderId, intent.isTpSl, FeePayment.SPONSORED)
      OrdersIntent.ConfirmSelfPay -> {
        val state = local.value
        if (state.lastCancelMarket != null && state.lastCancelOrderId != null) {
          cancel(
            state.lastCancelMarket,
            state.lastCancelOrderId,
            state.lastCancelIsTpSl,
            FeePayment.SELF_PAY,
          )
        }
      }
      OrdersIntent.TopUpApiWallet -> topUpApiWallet()
    }
  }

  private fun loadMore() = launchAction {
    val kind =
      when (local.value.section) {
        OrdersSection.OPEN -> return@launchAction
        OrdersSection.ORDERS -> AccountHistoryKind.ORDERS
        OrdersSection.TRADES -> AccountHistoryKind.TRADES
        OrdersSection.FUNDING -> AccountHistoryKind.FUNDING
      }
    accounts.loadMoreHistory(kind)
  }

  private fun cancel(market: String, orderId: String, isTpSl: Boolean, feePayment: FeePayment) =
    launchAction {
      accounts.restoreTrading()
      val subaccount = checkNotNull(uiState.value.account.account)
      val signer =
        if (uiState.value.profile.apiWalletAddress != null) TradingSigner.API
        else TradingSigner.OWNER
      local.update {
        it.copy(
          lastCancelMarket = market,
          lastCancelOrderId = orderId,
          lastCancelIsTpSl = isTpSl,
          apiWalletNeedsTopUp = false,
          suggestedTopUpOctas = null,
        )
      }
      trading
        .execute(
          if (isTpSl) {
            DecibelCommand.CancelPositionTpSl(subaccount, market, orderId)
          } else {
            DecibelCommand.CancelOrder(subaccount, market, orderId)
          },
          signer,
          VaultPrompt(
            if (isTpSl) "Cancel TP/SL" else "Cancel order",
            "Confirm the Decibel cancel transaction.",
          ),
          feePayment,
        )
        .collect { state -> local.update { it.copy(transaction = state) } }
      when (val state = local.value.transaction) {
        is TransactionState.Committed -> {
          accounts.refresh()
          accounts.refreshHistory()
        }
        is TransactionState.Failed -> {
          val estimate = state.selfPayEstimateOctas
          if (estimate == null) {
            error(state.message)
          } else if (signer == TradingSigner.API && uiState.value.profile.ownerAddress != null) {
            val balance = runSuspendCatching { trading.apiWalletAptBalance() }.getOrNull()
            if (balance != null && balance < estimate) {
              local.update {
                it.copy(
                  apiWalletNeedsTopUp = true,
                  suggestedTopUpOctas = suggestedApiTopUp(estimate),
                )
              }
            }
          }
        }
        else -> Unit
      }
    }

  private fun topUpApiWallet() = launchAction {
    val amount = local.value.suggestedTopUpOctas ?: error("No API-wallet top-up is required")
    trading
      .topUpApiWallet(
        amount,
        VaultPrompt(
          "Top up API wallet",
          "Transfer the displayed APT amount from the owner wallet.",
          requireFreshAuthorization = true,
        ),
      )
      .collect { transaction -> local.update { it.copy(topUpTransaction = transaction) } }
    when (val terminal = local.value.topUpTransaction) {
      is TransactionState.Committed ->
        local.update { it.copy(apiWalletNeedsTopUp = false, suggestedTopUpOctas = null) }
      is TransactionState.Failed -> error(terminal.message)
      else -> Unit
    }
  }

  private fun launchAction(block: suspend () -> Unit) {
    if (local.value.busy) return
    viewModelScope.launch {
      local.update { it.copy(busy = true, error = null) }
      try {
        runSuspendCatching { block() }
          .onFailure { error ->
            local.update { it.copy(error = error.message ?: "The order action failed") }
          }
      } finally {
        local.update { it.copy(busy = false) }
      }
    }
  }
}

private fun suggestedApiTopUp(estimate: ULong): ULong {
  val buffered = if (estimate <= ULong.MAX_VALUE / 3uL) estimate * 3uL else estimate
  return maxOf(1_000_000uL, buffered)
}
