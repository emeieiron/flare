package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.decibel.model.AccountFees
import xyz.mcxross.flare.decibel.model.AccountVaultPerformance
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.PortfolioChartPoint
import xyz.mcxross.flare.decibel.model.ProductFees
import xyz.mcxross.flare.decibel.model.SpotOverview
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.VaultInfo
import xyz.mcxross.flare.venue.model.AccountOverview
import xyz.mcxross.flare.venue.model.Balances
import xyz.mcxross.flare.venue.model.FeeRates
import xyz.mcxross.flare.venue.model.Fill
import xyz.mcxross.flare.venue.model.FillDirection
import xyz.mcxross.flare.venue.model.FundMovement
import xyz.mcxross.flare.venue.model.FundMovementKind
import xyz.mcxross.flare.venue.model.FundingPayment
import xyz.mcxross.flare.venue.model.OpenOrder
import xyz.mcxross.flare.venue.model.OrderHistoryEntry
import xyz.mcxross.flare.venue.model.OrderTrigger
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.RewardPart
import xyz.mcxross.flare.venue.model.RewardPoints
import xyz.mcxross.flare.venue.model.RewardPointsSource
import xyz.mcxross.flare.venue.model.RewardTier
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.SpotBalance
import xyz.mcxross.flare.venue.model.SpotBalances
import xyz.mcxross.flare.venue.model.SpotReservation
import xyz.mcxross.flare.venue.model.TradingFees
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TradingKeyScope
import xyz.mcxross.flare.venue.model.TradingStreak
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary
import xyz.mcxross.flare.decibel.model.AccountOverview as DecibelAccountOverview
import xyz.mcxross.flare.decibel.model.FundMovement as DecibelFundMovement
import xyz.mcxross.flare.decibel.model.FundingPayment as DecibelFundingPayment
import xyz.mcxross.flare.decibel.model.MarketTrade as DecibelMarketTrade
import xyz.mcxross.flare.decibel.model.Position as DecibelPosition
import xyz.mcxross.flare.decibel.model.TradingStreak as DecibelTradingStreak
import xyz.mcxross.flare.decibel.model.TwapOrder as DecibelTwapOrder

// Decibel's account data as the app's exchange-neutral models.

/** Decibel's perpetuals and spot balances are separate, so nothing is counted twice. */
internal fun DecibelAccountOverview.toAccountOverview(): AccountOverview =
  AccountOverview(
    equity = equityBalance,
    unrealizedPnl = unrealizedPnl,
    marginRatio = crossMarginRatio,
    availableToTrade = availableToTrade,
    withdrawable = crossWithdrawableBalance,
    vaultEquity = freeVaultEquity,
    balances = Balances(collateral = crossUsdcBalance, spot = spot?.toSpotBalances()),
  )

private fun SpotOverview.toSpotBalances(): SpotBalances =
  SpotBalances(
    assets = positions.map { SpotBalance(it.assetAddress, it.symbol, it.amount, it.valueUsd) },
    reserved =
      reservations.map {
        SpotReservation(it.market, it.orderId, it.isBuy, it.assetAddress, it.amount, it.valueUsd)
      },
    totalUsd = totalUsd,
  )

internal fun DecibelPosition.toPosition(): Position =
  Position(
    marketId = market,
    size = size,
    leverage = leverage,
    entryPrice = entryPrice,
    isolated = isIsolated,
    unrealizedFunding = unrealizedFunding,
    liquidationPrice = estimatedLiquidationPrice,
    takeProfitTriggerPrice = takeProfitTriggerPrice,
    stopLossTriggerPrice = stopLossTriggerPrice,
  )

internal fun Order.toOpenOrder(): OpenOrder =
  OpenOrder(
    marketId = market,
    orderId = orderId,
    clientOrderId = clientOrderId,
    kind = assetType.toMarketKind(),
    isBuy = isBuy,
    orderType = orderType,
    direction = orderDirection,
    price = price,
    originalSize = originalSize,
    remainingSize = remainingSize,
    reduceOnly = isReduceOnly,
    isPositionTpSl = isTpSl,
    triggerCondition = triggerCondition,
    takeProfit = takeProfitTriggerPrice?.let { OrderTrigger(it, takeProfitLimitPrice) },
    stopLoss = stopLossTriggerPrice?.let { OrderTrigger(it, stopLossLimitPrice) },
    timeMs = unixMs,
  )

internal fun Order.toOrderHistoryEntry(): OrderHistoryEntry =
  OrderHistoryEntry(order = toOpenOrder(), status = status)

internal fun DecibelMarketTrade.toFill(): Fill =
  Fill(
    // Decibel leaves some fills without a trade id; these fields together still tell them apart.
    id = tradeId.ifBlank { "$transactionVersion:$market:$account:$price:$size" },
    marketId = market,
    kind = assetType.toMarketKind(),
    direction =
      when (action) {
        "OpenLong" -> FillDirection.OPEN_LONG
        "CloseLong" -> FillDirection.CLOSE_LONG
        "OpenShort" -> FillDirection.OPEN_SHORT
        "CloseShort" -> FillDirection.CLOSE_SHORT
        else -> FillDirection.OTHER
      },
    action = action,
    size = size,
    price = price,
    realizedPnl = realizedPnlAmount,
    fee = feeAmount,
    timeMs = transactionUnixMs,
  )

internal fun DecibelFundingPayment.toFundingPayment(): FundingPayment =
  FundingPayment(
    marketId = market,
    action = action,
    size = size,
    amount = realizedFundingAmount,
    fee = feeAmount,
    timeMs = transactionUnixMs,
  )

/** Decibel's fund history lists deposits and withdrawals; anything not a deposit went out. */
internal fun DecibelFundMovement.toFundMovement(): FundMovement =
  FundMovement(
    kind =
      if (type.lowercase().contains("deposit")) FundMovementKind.DEPOSIT
      else FundMovementKind.WITHDRAWAL,
    amount = amount,
    assetSymbol = assetSymbol,
    status = status,
    reference = transactionHash,
    timeMs = timestamp,
  )

internal val PortfolioChartRange.decibelWireValue: String
  get() =
    when (this) {
      PortfolioChartRange.DAY_1 -> "1D"
      PortfolioChartRange.WEEK_1 -> "1W"
      PortfolioChartRange.MONTH_1 -> "1M"
      PortfolioChartRange.ALL -> "ALL"
    }

internal val PortfolioMetric.decibelWireValue: String
  get() =
    when (this) {
      PortfolioMetric.EQUITY -> "account_value"
      PortfolioMetric.PNL -> "pnl"
    }

/** Decibel names the plotted value differently by endpoint version; any of them will do. */
internal fun PortfolioChartPoint.toPortfolioPoint(metric: PortfolioMetric): PortfolioPoint =
  PortfolioPoint(
    timeMs = timestamp,
    value =
      dataPoints
        ?: when (metric) {
          PortfolioMetric.EQUITY -> accountValue ?: value
          PortfolioMetric.PNL -> realizedPnl ?: value
        },
  )

internal fun AccountFees.toTradingFees(): TradingFees =
  TradingFees(perp = perp?.toFeeRates(), spot = spot?.toFeeRates())

private fun ProductFees.toFeeRates() = FeeRates(makerRate = makerRate, takerRate = takerRate)

internal fun DecibelTwapOrder.toTwapOrder(): TwapOrder =
  TwapOrder(
    id = twapId,
    marketId = market,
    isBuy = isBuy,
    totalSize = totalSize,
    executedSize = executedSize,
    status = status,
    durationSeconds = durationSeconds,
    frequencySeconds = frequencySeconds,
  )

internal fun VaultInfo.toVaultSummary(): VaultSummary =
  VaultSummary(
    address = address,
    name = name,
    manager = manager,
    description = description,
    totalValue = totalAum,
    sharePrice = sharePrice,
    performanceFeeBps = performanceFeeBps,
    performanceFeePercent = performanceFeePercent,
    // A lockup Decibel reports as zero means none; one it leaves out is unknown.
    lockupSeconds = lockdownPeriodS ?: lockupSecondsFallback.takeIf { it > 0 },
    isActive = isActive,
  )

internal fun AccountVaultPerformance.toVaultPosition(): VaultPosition =
  VaultPosition(
    vault = vault.toVaultSummary(),
    shares = currentNumShares,
    value = currentValue,
    returnPercent = returnsPercent,
    sharePrice = effectiveSharePrice,
  )

internal fun Delegation.toTradingKey(): TradingKey =
  TradingKey(
    address = delegate,
    scope =
      when {
        canTradeAllPerpMarkets -> TradingKeyScope.PERP_MARKETS
        canTradeAllSpotMarkets -> TradingKeyScope.SPOT_MARKETS
        else -> TradingKeyScope.ONE_MARKET
      },
    marketId = permissionMarket,
    expiresAtMs = expirationTimeSeconds?.let { it * 1_000L },
  )

/**
 * Decibel's rewards: Amps, the trading streak and the tier, each read on its own. A part that
 * failed is marked unavailable so the screens keep what they showed for it.
 */
internal fun decibelRewards(
  streak: Result<DecibelTradingStreak?>,
  amps: Result<AmpsBreakdown?>,
  tier: Result<TierInfo?>,
): Rewards =
  Rewards(
    points = amps.getOrNull()?.toRewardPoints(),
    streak =
      streak.getOrNull()?.let {
        TradingStreak(
          days = it.streakCount,
          longestDays = it.longestStreak,
          graceDaysRemaining = it.graceDaysRemaining,
        )
      },
    tier =
      tier.getOrNull()?.let {
        RewardTier(name = it.tier, percentile = it.percentile, feeDiscountBps = it.feeDiscountBps)
      },
    unavailable =
      buildSet {
        if (amps.isFailure) add(RewardPart.POINTS)
        if (streak.isFailure) add(RewardPart.STREAK)
        if (tier.isFailure) add(RewardPart.TIER)
      },
  )

private fun AmpsBreakdown.toRewardPoints(): RewardPoints =
  RewardPoints(
    total = totalAmps,
    rank = rank,
    sources =
      listOf(
        RewardPointsSource("Trading", tradingAmps),
        RewardPointsSource("Streaks", streakAmps),
        RewardPointsSource("Vaults", vaultAmps),
        RewardPointsSource("Referrals", referralAmps),
        RewardPointsSource("Bonuses", bonusAmps),
      ),
  )
