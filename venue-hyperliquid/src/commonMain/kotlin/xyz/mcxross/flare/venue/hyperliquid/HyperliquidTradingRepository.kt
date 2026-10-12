package xyz.mcxross.flare.venue.hyperliquid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.VenueCapabilities
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.venue.model.actionKind

/**
 * Hyperliquid's actions, journaled by [actions] before they're sent. Setup actions (trading keys
 * and builder fees) are sent now; orders and fund moves fail before signing, with the reason
 * [capabilities] gives, until they're supported.
 */
class HyperliquidTradingRepository(
  private val client: HyperliquidClient,
  private val actions: HyperliquidActions,
  private val setup: HyperliquidSetup,
  private val capabilities: VenueCapabilities = HyperliquidVenue.Capabilities,
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
        else -> unavailable("Hyperliquid can’t do this from here. Use setup to enable trading.")
      }
    emit(result)
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
}
