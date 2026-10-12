package xyz.mcxross.flare.venue.hyperliquid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.AssetKind
import xyz.mcxross.flare.hyperliquid.model.CancelByCloidRequest
import xyz.mcxross.flare.hyperliquid.model.CancelOrders
import xyz.mcxross.flare.hyperliquid.model.CancelOrdersByCloid
import xyz.mcxross.flare.hyperliquid.model.CancelRequest
import xyz.mcxross.flare.hyperliquid.model.CancelTwap
import xyz.mcxross.flare.hyperliquid.model.Cloid
import xyz.mcxross.flare.hyperliquid.model.HyperliquidAsset
import xyz.mcxross.flare.hyperliquid.model.L1Action
import xyz.mcxross.flare.hyperliquid.model.UpdateLeverage
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarginMode
import xyz.mcxross.flare.venue.model.OpenOrderRef
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.actionKind

/**
 * Hyperliquid's actions, journaled by [actions] before they're sent. Orders, TWAPs, cancels, exits,
 * leverage and vault moves are signed by the device's agent key, for a sub-account through its
 * `vaultAddress`; setup actions (trading keys and builder fees) and the other fund moves by the
 * owner. Kinds of action [capabilities] holds back fail before signing, with its reason.
 */
class HyperliquidTradingRepository(
  private val client: HyperliquidClient,
  private val actions: HyperliquidActions,
  private val setup: HyperliquidSetup,
  private val funds: HyperliquidFunds,
  private val assets: HyperliquidAssets = HyperliquidAssets(client),
  private val capabilities: VenueCapabilities = HyperliquidVenue.Capabilities,
  private val newCloid: () -> Cloid = { Cloid.random() },
) : TradingRepository {
  override val pendingTransactions: Flow<List<PendingTransaction>> = actions.pendingTransactions

  override fun execute(
    action: TradeAction,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<ActionState> = flow {
    capabilities.unavailableReason(action.actionKind)?.let {
      emit(unavailable(it))
      return@flow
    }
    emit(ActionState.AwaitingAuthorization)
    val result =
      when (action) {
        is TradeAction.RevokeTrading -> setup.revoke(action.tradingKey, prompt)
        is TradeAction.ApproveBuilderFee ->
          setup.approveBuilderFee(action.builder, action.maxFeeBps.toUInt(), prompt)
        is TradeAction.RevokeBuilderFee -> setup.revokeBuilderFee(action.builder, prompt)
        is TradeAction.PlaceOrder -> beforeSending { placeOrder(action, prompt) }
        is TradeAction.CancelOrder ->
          beforeSending { cancel(action.account, listOf(OpenOrderRef(action.marketId, action.orderId)), prompt) }
        is TradeAction.CancelPositionTpSl ->
          beforeSending { cancel(action.account, listOf(OpenOrderRef(action.marketId, action.orderId)), prompt) }
        is TradeAction.CancelOrders -> beforeSending { cancel(action.account, action.orders, prompt) }
        is TradeAction.ConfigureMarket -> beforeSending { configureMarket(action, prompt) }
        is TradeAction.SetPositionTpSl -> beforeSending { setPositionTpSl(action, prompt) }
        is TradeAction.CancelTwapOrder -> beforeSending { cancelTwap(action, prompt) }
        is TradeAction.Withdraw -> funds.withdraw(action.account, null, action.amount, prompt)
        is TradeAction.Transfer -> funds.send(action.account, action.destination, action.amount, prompt)
        is TradeAction.MovePerpSpot ->
          funds.movePerpSpot(action.account, action.amount, toPerp = !action.toSpot, prompt)
        is TradeAction.VaultDeposit -> funds.vault(action.account, action.vault, true, action.amount, prompt)
        is TradeAction.VaultWithdraw -> funds.vault(action.account, action.vault, false, action.shares, prompt)
        is TradeAction.Deposit -> unavailable(DEPOSIT_BY_ADDRESS)
        is TradeAction.EnableTrading,
        TradeAction.CreateTradingAccount ->
          unavailable("Hyperliquid can’t do this from here. Use setup to enable trading.")
      }
    emit(result)
  }

  /** Anything that fails before [block] reaches [actions] was never sent. */
  private suspend fun beforeSending(block: suspend () -> ActionState): ActionState =
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      ActionState.Failed(error.message ?: "The action wasn’t sent", definitelyNotSubmitted = true)
    }

  private suspend fun placeOrder(action: TradeAction.PlaceOrder, prompt: VaultPrompt): ActionState {
    val asset = assets.asset(action.market.id)
    HyperliquidOrders.requireKind(asset, action.market.kind)
    if (action.order.type == OrderType.TWAP) return placeTwap(action, asset, prompt)
    val cloid = newCloid()
    val orders = HyperliquidOrders.place(asset, action.order, cloid)
    val labels =
      listOf("Order") +
        listOfNotNull(
          action.order.takeProfitTrigger?.let { "take profit" },
          action.order.stopLossTrigger?.let { "stop loss" },
        )
    return actions.sendAsAgent(
      orders,
      prompt,
      cloid,
      actions.vaultFor(action.account),
      record = { lapses -> JournaledAction.PlaceOrder(action.account, lapses) },
    ) { response, reference ->
      HyperliquidResults.orders(response, reference, labels)
    }
  }

  private suspend fun placeTwap(
    action: TradeAction.PlaceOrder,
    asset: HyperliquidAsset,
    prompt: VaultPrompt,
  ): ActionState {
    val twap = HyperliquidOrders.twap(asset, action.order)
    return actions.sendAsAgent(
      twap,
      prompt,
      vaultAddress = actions.vaultFor(action.account),
      record = { lapses ->
        JournaledAction.PlaceTwap(action.account, asset.coin, twap.isBuy, twap.size.toWire(), twap.minutes, lapses)
      },
    ) { response, reference ->
      HyperliquidResults.twapPlaced(response, reference)
    }
  }

  private suspend fun cancelTwap(action: TradeAction.CancelTwapOrder, prompt: VaultPrompt): ActionState {
    val asset = assets.asset(action.marketId)
    val twapId = action.twapId.toLongOrNull() ?: throw IllegalArgumentException("This TWAP can’t be canceled")
    return actions.sendAsAgent(
      CancelTwap(asset.id, twapId),
      prompt,
      vaultAddress = actions.vaultFor(action.account),
      record = { lapses -> JournaledAction.CancelTwap(action.account, twapId, lapses) },
    ) { response, reference ->
      HyperliquidResults.twapCanceled(response, reference)
    }
  }

  /** Cancels by oid; an order known only by its cloid is canceled by cloid. */
  private suspend fun cancel(account: String, orders: List<OpenOrderRef>, prompt: VaultPrompt): ActionState {
    require(orders.isNotEmpty()) { "No orders to cancel" }
    val byOid = mutableListOf<CancelRequest>()
    val byCloid = mutableListOf<CancelByCloidRequest>()
    for (order in orders) {
      val asset = assets.asset(order.marketId).id
      val oid = order.orderId.toLongOrNull()
      if (oid != null) byOid += CancelRequest(asset, oid)
      else byCloid += CancelByCloidRequest(asset, Cloid.parse(order.orderId))
    }
    val results = mutableListOf<ActionState>()
    if (byOid.isNotEmpty()) {
      results += sendCancel(account, CancelOrders(byOid), byOid.map { it.oid.toString() }, prompt)
    }
    if (byCloid.isNotEmpty() && results.none { it !is ActionState.Committed }) {
      results += sendCancel(account, CancelOrdersByCloid(byCloid), byCloid.map { it.cloid.hex }, prompt)
    }
    return results.firstOrNull { it !is ActionState.Committed } ?: results.last()
  }

  private suspend fun sendCancel(
    account: String,
    action: L1Action,
    orders: List<String>,
    prompt: VaultPrompt,
  ): ActionState =
    actions.sendAsAgent(
      action,
      prompt,
      vaultAddress = actions.vaultFor(account),
      record = { lapses -> JournaledAction.CancelOrders(account, orders, lapses) },
    ) { response, reference ->
      HyperliquidResults.cancels(response, reference)
    }

  /**
   * Sets leverage, sent only when it changes something. The screens offer no margin mode, so a
   * request for cross keeps the mode the market already has; isolated-only markets stay isolated.
   */
  private suspend fun configureMarket(action: TradeAction.ConfigureMarket, prompt: VaultPrompt): ActionState {
    val asset = assets.asset(action.marketId)
    require(asset.kind == AssetKind.PERP) { "Spot has no leverage" }
    val maxLeverage = asset.maxLeverage ?: 1
    require(action.leverage in 1..maxLeverage) { "Choose leverage from 1× to $maxLeverage×" }
    val current = client.info.activeAssetData(action.account, asset.coin).leverage
    val isCross =
      !asset.isIsolatedOnly && action.marginMode == MarginMode.CROSS && current.isCross
    if (current.value == action.leverage && current.isCross == isCross) {
      return ActionState.Committed(NOTHING_SENT, "Leverage is already ${action.leverage}×.")
    }
    return actions.sendAsAgent(
      UpdateLeverage(asset.id, isCross, action.leverage),
      prompt,
      vaultAddress = actions.vaultFor(action.account),
      record = { lapses ->
        JournaledAction.UpdateLeverage(action.account, asset.coin, isCross, action.leverage, lapses)
      },
    ) { response, reference ->
      HyperliquidResults.default(response, reference)
    }
  }

  /**
   * Places the position's new exits, then cancels the ones they replace. Until the new ones are in,
   * the old ones stay, so the position is never left without the exits it had.
   */
  private suspend fun setPositionTpSl(action: TradeAction.SetPositionTpSl, prompt: VaultPrompt): ActionState {
    val coin = action.market.id
    val asset = assets.asset(coin)
    val position =
      client.info.clearinghouseState(action.account).assetPositions
        .map { it.position }
        .firstOrNull { it.coin == coin && it.szi.toDoubleOrZero() != 0.0 }
        ?: throw IllegalArgumentException("Open a position in ${asset.displayName} first")
    val takeProfit =
      action.takeProfitTrigger?.let {
        HyperliquidOrders.Exit(it, action.takeProfitLimit, action.takeProfitSize)
      }
    val stopLoss =
      action.stopLossTrigger?.let { HyperliquidOrders.Exit(it, action.stopLossLimit, action.stopLossSize) }
    val closesWithBuy = position.szi.trim().startsWith('-')
    // Every exit of the kinds being set goes, whichever grouping placed it, so none is left twice.
    val replaced =
      client.info.openOrders(action.account).exitsFor(coin, closesWithBuy).filter { order ->
        (takeProfit != null && order.isTakeProfit) || (stopLoss != null && order.isStopLoss)
      }
    val cloid = newCloid()
    val orders =
      HyperliquidOrders.positionExits(
        asset,
        closesWithBuy = closesWithBuy,
        takeProfit = takeProfit,
        stopLoss = stopLoss,
        cloid = cloid,
      )
    val labels = listOfNotNull(takeProfit?.let { "Take profit" }, stopLoss?.let { "Stop loss" })
    val placed =
      actions.sendAsAgent(
        orders,
        prompt,
        cloid,
        actions.vaultFor(action.account),
        record = { lapses -> JournaledAction.PlaceOrder(action.account, lapses) },
      ) { response, reference ->
        HyperliquidResults.orders(response, reference, labels)
      }
    if (placed !is ActionState.Committed || replaced.isEmpty()) return placed
    val cancelled =
      cancel(action.account, replaced.map { OpenOrderRef(coin, it.oid.toString()) }, prompt)
    return if (cancelled is ActionState.Committed) placed
    else
      placed.copy(
        detail =
          listOfNotNull(placed.detail, "Your earlier exits are still open; cancel them in Orders.")
            .joinToString(" ")
      )
  }

  override suspend fun transactionStatus(reference: String): ActionState = actions.status(reference)

  /** Hyperliquid charges no network fee, so there's no fee token to hold. */
  override suspend fun tradingKeyFeeBalance(): ULong = 0uL

  override suspend fun ownerFeeBalance(): ULong = 0uL

  /** The spot balance free to trade: what [accountAddress] holds of [symbol], less what orders hold. */
  override suspend fun baseAssetBalance(accountAddress: String, symbol: String): Double {
    val balance =
      client.info.spotClearinghouseState(accountAddress).balances.firstOrNull {
        it.coin.trim().equals(symbol, ignoreCase = true)
      } ?: return 0.0
    return (balance.total.toDoubleOrZero() - balance.hold.toDoubleOrZero()).coerceAtLeast(0.0)
  }

  override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt): Flow<ActionState> = flow {
    emit(unavailable("Hyperliquid charges no network fee."))
  }

  override suspend fun reconcilePending(): ReconciliationResult = actions.reconcile()

  private companion object {
    /** The reference of an action that needed nothing sent. */
    const val NOTHING_SENT = ""
  }
}
