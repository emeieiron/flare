package xyz.mcxross.flare.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import xyz.mcxross.flare.data.AccountSnapshot
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.MarketDetailsRepository
import xyz.mcxross.flare.data.MarketsRepository
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.data.TradingSigner
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.OrderDraft
import xyz.mcxross.flare.decibel.model.OrderSide
import xyz.mcxross.flare.decibel.model.OrderType
import xyz.mcxross.flare.decibel.model.SlippageBps
import xyz.mcxross.flare.decibel.model.absoluteSize
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.decibel.model.toChainUnits
import xyz.mcxross.flare.decibel.model.validate
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences

enum class FundingMode {
  DEPOSIT,
  WITHDRAW,
}

data class PortfolioUiState(
  val profile: WalletProfile = WalletProfile(),
  val account: AccountSnapshot = AccountSnapshot(),
  val sessionRole: SessionRole? = null,
  val pendingTransactions: List<PendingTransaction> = emptyList(),
  val fundingMode: FundingMode? = null,
  val fundingAmount: String = "",
  val fundingTransaction: TransactionState? = null,
  val managedPositionMarket: String? = null,
  val takeProfitInput: String = "",
  val stopLossInput: String = "",
  val positionTransaction: TransactionState? = null,
  val apiWalletNeedsTopUp: Boolean = false,
  val suggestedTopUpOctas: ULong? = null,
  val topUpTransaction: TransactionState? = null,
  val busy: Boolean = false,
  val actionError: String? = null,
) {
  val isLive: Boolean
    get() = account.overview != null && !account.stale
}

sealed interface PortfolioIntent {
  data object Unlock : PortfolioIntent

  data object Refresh : PortfolioIntent

  data object Lock : PortfolioIntent

  data class OpenFunding(val mode: FundingMode) : PortfolioIntent

  data class ChangeFundingAmount(val value: String) : PortfolioIntent

  data object SubmitFunding : PortfolioIntent

  data object ConfirmSelfPay : PortfolioIntent

  data object CloseFunding : PortfolioIntent

  data class ManagePosition(val market: String) : PortfolioIntent

  data object DismissPositionManagement : PortfolioIntent

  data object ClosePosition : PortfolioIntent

  data class ChangeTakeProfit(val value: String) : PortfolioIntent

  data class ChangeStopLoss(val value: String) : PortfolioIntent

  data object SubmitTpSl : PortfolioIntent

  data object ConfirmPositionSelfPay : PortfolioIntent

  data object TopUpApiWallet : PortfolioIntent
}

class PortfolioViewModel(
  private val accounts: AccountRepository,
  private val wallets: WalletRepository,
  private val sessions: SessionRepository,
  private val preferences: AppPreferences,
  private val trading: TradingRepository,
  private val markets: MarketsRepository,
  private val marketDetails: MarketDetailsRepository,
) : ViewModel() {
  private val local = MutableStateFlow(PortfolioUiState())
  private var lastPositionCommand: DecibelCommand? = null

  val uiState: StateFlow<PortfolioUiState> =
    combine(
        local,
        wallets.profile,
        accounts.snapshot,
        sessions.status,
        trading.pendingTransactions,
      ) { state, profile, account, session, pending ->
        state.copy(
          profile = profile,
          account = account,
          sessionRole = session?.role,
          pendingTransactions = pending,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUiState())

  fun onIntent(intent: PortfolioIntent) {
    when (intent) {
      PortfolioIntent.Unlock -> unlock()
      PortfolioIntent.Refresh ->
        launchAction {
          trading.reconcilePending()
          accounts.refresh()
        }
      PortfolioIntent.Lock -> launchAction { accounts.disconnect() }
      is PortfolioIntent.OpenFunding ->
        local.update {
          it.copy(
            fundingMode = intent.mode,
            fundingAmount = "",
            fundingTransaction = null,
            actionError = null,
          )
        }
      is PortfolioIntent.ChangeFundingAmount ->
        local.update {
          it.copy(
            fundingAmount =
              intent.value.filter { character -> character.isDigit() || character == '.' },
            fundingTransaction = null,
            actionError = null,
          )
        }
      PortfolioIntent.SubmitFunding -> submitFunding(FeePayment.SPONSORED)
      PortfolioIntent.ConfirmSelfPay -> submitFunding(FeePayment.SELF_PAY)
      PortfolioIntent.CloseFunding ->
        local.update {
          it.copy(fundingMode = null, fundingAmount = "", fundingTransaction = null)
        }
      is PortfolioIntent.ManagePosition ->
        local.update {
          it.copy(
            managedPositionMarket = intent.market,
            takeProfitInput = "",
            stopLossInput = "",
            positionTransaction = null,
            actionError = null,
          )
        }
      PortfolioIntent.DismissPositionManagement ->
        local.update {
          it.copy(
            managedPositionMarket = null,
            takeProfitInput = "",
            stopLossInput = "",
            positionTransaction = null,
          )
        }
      PortfolioIntent.ClosePosition -> closePosition(FeePayment.SPONSORED)
      is PortfolioIntent.ChangeTakeProfit ->
        local.update {
          it.copy(takeProfitInput = decimalCharacters(intent.value), positionTransaction = null)
        }
      is PortfolioIntent.ChangeStopLoss ->
        local.update {
          it.copy(stopLossInput = decimalCharacters(intent.value), positionTransaction = null)
        }
      PortfolioIntent.SubmitTpSl -> setTpSl(FeePayment.SPONSORED)
      PortfolioIntent.ConfirmPositionSelfPay ->
        lastPositionCommand?.let { executePositionCommand(it, FeePayment.SELF_PAY) }
      PortfolioIntent.TopUpApiWallet -> topUpApiWallet()
    }
  }

  private fun closePosition(feePayment: FeePayment) = launchAction {
    val position = managedPosition()
    val quote = marketQuote(position.market)
    marketDetails.refresh(position.market)
    val details = marketDetails.details.value
    require(!details.stale && details.orderBook != null) {
      "A live order book is required to close a position"
    }
    val slippage = preferences.values.first().slippageBps
    val validated =
      OrderDraft(
          marketAddress = position.market,
          side = if (position.isLong) OrderSide.SELL else OrderSide.BUY,
          type = OrderType.MARKET,
          size = DecimalInput(position.absoluteSize),
          reduceOnly = true,
          slippage = SlippageBps(slippage.toUInt()),
        )
        .validate(quote.market, details.orderBook)
        .value ?: error("The exact position size cannot be aligned to the current market precision")
    val subaccount = checkNotNull(uiState.value.account.account)
    executePositionCommandInternal(
      DecibelCommand.PlaceOrder(subaccount, validated),
      feePayment,
    )
  }

  private fun setTpSl(feePayment: FeePayment) = launchAction {
    val position = managedPosition()
    val quote = marketQuote(position.market)
    val state = local.value
    val takeProfit = optionalPrice(state.takeProfitInput, quote.market.precision.priceDecimals)
    val stopLoss = optionalPrice(state.stopLossInput, quote.market.precision.priceDecimals)
    require(takeProfit != null || stopLoss != null) { "Enter a take-profit or stop-loss price" }
    listOfNotNull(takeProfit, stopLoss).forEach { units ->
      require(
        quote.market.precision.tickSize == 0uL || units % quote.market.precision.tickSize == 0uL
      ) {
        "TP/SL prices must align to tick size ${quote.market.precision.tickSize}"
      }
    }
    val subaccount = checkNotNull(uiState.value.account.account)
    executePositionCommandInternal(
      DecibelCommand.SetPositionTpSl(
        subaccount = subaccount,
        market = position.market,
        takeProfitTrigger = takeProfit,
        takeProfitLimit = takeProfit,
        stopLossTrigger = stopLoss,
        stopLossLimit = stopLoss,
      ),
      feePayment,
    )
  }

  private fun executePositionCommand(command: DecibelCommand, feePayment: FeePayment) =
    launchAction {
      executePositionCommandInternal(command, feePayment)
    }

  private suspend fun executePositionCommandInternal(
    command: DecibelCommand,
    feePayment: FeePayment,
  ) {
    ensureTradingSession()
    lastPositionCommand = command
    val profile = wallets.profile.first()
    val signer = if (profile.apiWalletAddress != null) TradingSigner.API else TradingSigner.OWNER
    local.update { it.copy(apiWalletNeedsTopUp = false, suggestedTopUpOctas = null) }
    trading
      .execute(
        command = command,
        signer = signer,
        prompt = VaultPrompt("Manage position", "Review and authorize the Decibel transaction."),
        feePayment = feePayment,
      )
      .collect { transaction -> local.update { it.copy(positionTransaction = transaction) } }
    when (val terminal = local.value.positionTransaction) {
      is TransactionState.Committed -> accounts.refresh()
      is TransactionState.Failed -> {
        val estimate = terminal.selfPayEstimateOctas
        if (estimate == null) {
          error(terminal.message)
        } else if (signer == TradingSigner.API && profile.ownerAddress != null) {
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
    val subaccount =
      preferences.values.first().selectedSubaccount ?: error("Set up a Decibel subaccount first")
    accounts.connectOwner(
      subaccount,
      VaultPrompt(
        "Authorize API-wallet top-up",
        "Confirm the owner wallet before transferring APT to the API wallet.",
        requireFreshAuthorization = true,
      ),
    )
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

  private suspend fun ensureTradingSession() {
    val profile = wallets.profile.first()
    val expected =
      profile.apiWalletAddress ?: profile.ownerAddress ?: error("Set up a wallet first")
    if (sessions.status.value?.walletAddress?.equals(expected, ignoreCase = true) == true) return
    val subaccount =
      preferences.values.first().selectedSubaccount ?: error("Set up a Decibel subaccount first")
    if (profile.apiWalletAddress != null) {
      accounts.connectApi(
        subaccount,
        VaultPrompt("Unlock trading wallet", "Authorize position management."),
      )
    } else {
      accounts.connectOwner(
        subaccount,
        VaultPrompt("Unlock owner wallet", "Authorize position management."),
      )
    }
  }

  private suspend fun marketQuote(market: String) =
    (markets.catalog.value.quotes.firstOrNull { it.market.address == market }
      ?: run {
        markets.refresh()
        markets.catalog.value.quotes.firstOrNull { it.market.address == market }
      }) ?: error("Market metadata is unavailable")

  private fun managedPosition() =
    uiState.value.account.positions.firstOrNull { it.market == local.value.managedPositionMarket }
      ?: error("Select an open position")

  private fun optionalPrice(value: String, decimals: Int): ULong? =
    value.takeIf(String::isNotBlank)?.let {
      DecimalInput(it).toChainUnits("TP/SL price", decimals).getOrThrow()
    }

  private fun submitFunding(feePayment: FeePayment) = launchAction {
    val state = local.value
    val mode = state.fundingMode ?: error("Choose deposit or withdrawal")
    val prompt =
      VaultPrompt(
        title = if (mode == FundingMode.DEPOSIT) "Deposit Aptos USDC" else "Withdraw Aptos USDC",
        subtitle = "Owner authorization is required for collateral movement.",
      )
    val flow =
      if (mode == FundingMode.DEPOSIT) {
        accounts.depositUsdc(state.fundingAmount, prompt, feePayment)
      } else {
        accounts.withdrawUsdc(state.fundingAmount, prompt, feePayment)
      }
    flow.collect { transaction ->
      local.update { it.copy(fundingTransaction = transaction) }
    }
    when (val terminal = local.value.fundingTransaction) {
      is TransactionState.Committed -> {
        accounts.refresh()
        local.update { it.copy(fundingAmount = "") }
      }
      is TransactionState.Failed -> {
        if (terminal.selfPayEstimateOctas == null) error(terminal.message)
      }
      else -> Unit
    }
  }

  private fun unlock() = launchAction {
    val state = uiState.value
    val subaccount =
      preferences.values.first().selectedSubaccount ?: error("Set up a Decibel subaccount first")
    if (state.profile.apiWalletAddress != null) {
      accounts.connectApi(
        subaccount,
        VaultPrompt(
          title = "Unlock trading account",
          subtitle = "Authorize the delegated API wallet for five minutes.",
        ),
      )
    } else {
      accounts.connectOwner(
        subaccount,
        VaultPrompt(
          title = "Unlock owner account",
          subtitle = "Authorize live account data for five minutes.",
        ),
      )
    }
  }

  private fun launchAction(block: suspend () -> Unit) {
    if (local.value.busy) return
    viewModelScope.launch {
      local.update { it.copy(busy = true, actionError = null) }
      runSuspendCatching { block() }
        .onFailure { error ->
          local.update {
            it.copy(actionError = error.message ?: "The account action failed")
          }
        }
      local.update { it.copy(busy = false) }
    }
  }
}

private fun decimalCharacters(value: String): String =
  value
    .filter { it.isDigit() || it == '.' }
    .let { filtered ->
      val dot = filtered.indexOf('.')
      if (dot < 0) filtered else filtered.take(dot + 1) + filtered.drop(dot + 1).replace(".", "")
    }

private fun suggestedApiTopUp(estimate: ULong): ULong {
  val buffered = if (estimate <= ULong.MAX_VALUE / 3uL) estimate * 3uL else estimate
  return maxOf(1_000_000uL, buffered)
}
