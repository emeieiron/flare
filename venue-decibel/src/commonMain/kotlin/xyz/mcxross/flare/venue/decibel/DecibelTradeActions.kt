package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.toChainUnits
import xyz.mcxross.flare.venue.model.MarginMode
import xyz.mcxross.flare.venue.model.Market
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.flare.decibel.model.MarginMode as DecibelMarginMode

/** The Decibel transaction for [this] action. USDC moves through [usdcMetadata]. */
internal fun TradeAction.toDecibelCommand(usdcMetadata: String): DecibelCommand =
  when (this) {
    is TradeAction.PlaceOrder -> {
      val rules = market.decibelRules()
      if (market.kind == MarketKind.SPOT) DecibelCommand.PlaceSpotOrder(account, rules.validated(order))
      else if (order.type == OrderType.TWAP)
        DecibelCommand.PlaceTwapOrder(account, rules.validatedTwap(order))
      else DecibelCommand.PlaceOrder(account, rules.validated(order))
    }
    is TradeAction.CancelOrder ->
      if (kind == MarketKind.SPOT) DecibelCommand.CancelSpotOrder(account, marketId, orderId)
      else DecibelCommand.CancelOrder(account, marketId, orderId)
    is TradeAction.SetPositionTpSl -> {
      val rules = market.decibelRules()
      DecibelCommand.SetPositionTpSl(
        subaccount = account,
        market = market.id,
        takeProfitTrigger = takeProfitTrigger?.let { rules.priceUnits("TP/SL price", it) },
        takeProfitLimit = takeProfitLimit?.let { rules.priceUnits("TP/SL price", it) },
        takeProfitSize = takeProfitSize?.let { rules.sizeUnits("TP/SL size", it) },
        stopLossTrigger = stopLossTrigger?.let { rules.priceUnits("TP/SL price", it) },
        stopLossLimit = stopLossLimit?.let { rules.priceUnits("TP/SL price", it) },
        stopLossSize = stopLossSize?.let { rules.sizeUnits("TP/SL size", it) },
      )
    }
    is TradeAction.CancelPositionTpSl -> DecibelCommand.CancelPositionTpSl(account, marketId, orderId)
    is TradeAction.ConfigureMarket -> {
      require(leverage in 1..UByte.MAX_VALUE.toInt()) { "Choose leverage within this market’s limit" }
      DecibelCommand.ConfigureMarket(
        account,
        marketId,
        when (marginMode) {
          MarginMode.CROSS -> DecibelMarginMode.CROSS
          MarginMode.ISOLATED -> DecibelMarginMode.ISOLATED
        },
        leverage.toUByte(),
      )
    }
    is TradeAction.CancelTwapOrder -> DecibelCommand.CancelTwapOrder(account, marketId, twapId)
    is TradeAction.EnableTrading ->
      DecibelCommand.DelegateTrading(account, tradingKey, expiresAtSeconds?.toULong())
    is TradeAction.RevokeTrading -> DecibelCommand.RevokeDelegation(account, tradingKey)
    is TradeAction.ApproveBuilderFee -> {
      require(maxFeeBps in 1..10) { "Builder fee must be between 1 and 10 bps (up to 0.10%)" }
      val units = maxFeeBps.toULong() * 100uL
      if (kind == MarketKind.SPOT) DecibelCommand.ApproveMaxSpotBuilderFee(account, builder, units)
      else DecibelCommand.ApproveMaxBuilderFee(account, builder, units)
    }
    is TradeAction.RevokeBuilderFee ->
      if (kind == MarketKind.SPOT) DecibelCommand.RevokeMaxSpotBuilderFee(account, builder)
      else DecibelCommand.RevokeMaxBuilderFee(account, builder)
    is TradeAction.Deposit -> DecibelCommand.Deposit(account, usdcMetadata, usdcUnits(amount))
    is TradeAction.Withdraw -> DecibelCommand.Withdraw(account, usdcMetadata, usdcUnits(amount))
    is TradeAction.Transfer ->
      DecibelCommand.TransferCollateral(account, usdcMetadata, destination, usdcUnits(amount))
    is TradeAction.VaultDeposit ->
      DecibelCommand.ContributeToVault(
        account,
        vault,
        usdcMetadata,
        usdcUnits(amount, "Contribution amount"),
      )
    is TradeAction.VaultWithdraw -> {
      val units = DecimalInput(shares).toChainUnits("Redemption shares", 6).getOrThrow()
      require(units > 0uL) { "Enter a share count greater than zero" }
      DecibelCommand.RedeemFromVault(account, vault, units)
    }
    TradeAction.CreateTradingAccount -> DecibelCommand.CreateSubaccount
    is TradeAction.MovePerpSpot ->
      error("Decibel doesn’t keep perpetuals and spot margin apart")
  }

private fun Market.decibelRules(): DecibelOrderRules =
  rules as? DecibelOrderRules ?: error("Market details are unavailable. Try again.")

/** USDC has 6 decimals on Decibel. */
private fun usdcUnits(amount: String, field: String = "USDC amount"): ULong {
  val units = DecimalInput(amount).toChainUnits(field, 6).getOrThrow()
  require(units > 0uL) { "Enter an amount greater than zero" }
  return units
}
