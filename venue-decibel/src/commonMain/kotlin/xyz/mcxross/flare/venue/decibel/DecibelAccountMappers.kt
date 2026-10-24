package xyz.mcxross.flare.venue.decibel

import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import xyz.mcxross.flare.core.toDecimalString
import xyz.mcxross.flare.decibel.api.SubaccountStatus
import xyz.mcxross.flare.decibel.model.AccountFees
import xyz.mcxross.flare.decibel.model.AccountReferralInfo
import xyz.mcxross.flare.decibel.model.AccountVaultPerformance
import xyz.mcxross.flare.decibel.model.AmpsBreakdown
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.flare.decibel.model.GlobalPointsStats
import xyz.mcxross.flare.decibel.model.Order
import xyz.mcxross.flare.decibel.model.OwnerAmpsDaily
import xyz.mcxross.flare.decibel.model.PortfolioChartPoint
import xyz.mcxross.flare.decibel.model.ProductFees
import xyz.mcxross.flare.decibel.model.ReferrerStats
import xyz.mcxross.flare.decibel.model.SpotOverview
import xyz.mcxross.flare.decibel.model.Subaccount
import xyz.mcxross.flare.decibel.model.TierInfo
import xyz.mcxross.flare.decibel.model.UserCampaigns
import xyz.mcxross.flare.decibel.model.UserClaim
import xyz.mcxross.flare.decibel.model.VaultInfo
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.venue.model.AccountOverview
import xyz.mcxross.flare.venue.model.Balances
import xyz.mcxross.flare.venue.model.Campaign
import xyz.mcxross.flare.venue.model.CampaignRewards
import xyz.mcxross.flare.venue.model.ClaimState
import xyz.mcxross.flare.venue.model.FeeRates
import xyz.mcxross.flare.venue.model.Fill
import xyz.mcxross.flare.venue.model.FillDirection
import xyz.mcxross.flare.venue.model.FundMovement
import xyz.mcxross.flare.venue.model.FundMovementKind
import xyz.mcxross.flare.venue.model.FundingPayment
import xyz.mcxross.flare.venue.model.OpenOrder
import xyz.mcxross.flare.venue.model.OrderHistoryEntry
import xyz.mcxross.flare.venue.model.OrderTrigger
import xyz.mcxross.flare.venue.model.PointsStanding
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.SeriesPoint
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.ReferralSummary
import xyz.mcxross.flare.venue.model.RewardPart
import xyz.mcxross.flare.venue.model.RewardPoints
import xyz.mcxross.flare.venue.model.RewardPointsSource
import xyz.mcxross.flare.venue.model.RewardTier
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.SpotBalance
import xyz.mcxross.flare.venue.model.SpotBalances
import xyz.mcxross.flare.venue.model.SpotReservation
import xyz.mcxross.flare.venue.model.TradingAccount
import xyz.mcxross.flare.venue.model.TradingAccountStatus
import xyz.mcxross.flare.venue.model.TradingFees
import xyz.mcxross.flare.venue.model.TradingKey
import xyz.mcxross.flare.venue.model.TradingKeyScope
import xyz.mcxross.flare.venue.model.TradingStreak
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.ManagerFee
import xyz.mcxross.flare.venue.model.VaultFact
import xyz.mcxross.flare.venue.model.VaultFactValue
import xyz.mcxross.flare.venue.model.VaultKind
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

/** The position, with the exits set for it whole or, failing those, attached to its opening order. */
internal fun DecibelPosition.toPosition(openOrders: List<OpenOrder> = emptyList()): Position {
  val exits = openOrders.exitsFor(market, closesWithBuy = !isLong)
  return Position(
    marketId = market,
    size = size,
    leverage = leverage,
    entryPrice = entryPrice,
    isolated = isIsolated,
    unrealizedFunding = unrealizedFunding,
    liquidationPrice = estimatedLiquidationPrice,
    takeProfitTriggerPrice = takeProfitTriggerPrice ?: exits.latestTrigger(ExitKind.TAKE_PROFIT),
    stopLossTriggerPrice = stopLossTriggerPrice ?: exits.latestTrigger(ExitKind.STOP_LOSS),
  )
}

internal fun Order.toOpenOrder(): OpenOrder {
  val order = OpenOrder(
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
  val trigger = order.ownTrigger()
  // An exit's own trigger isn't one that comes with it.
  val ownKind = order.exitKind.takeIf { isReduceOnly }
  return order.copy(
    triggerPrice = trigger,
    takeProfit =
      order.takeProfit.takeUnless { ownKind == ExitKind.TAKE_PROFIT && it?.triggerPrice == trigger },
    stopLoss = order.stopLoss.takeUnless { ownKind == ExitKind.STOP_LOSS && it?.triggerPrice == trigger },
    // An exit set for the whole position carries no size of its own.
    coversPosition = isTpSl && (remainingSize ?: 0.0) == 0.0 && (originalSize ?: 0.0) == 0.0,
  )
}

internal fun Order.toOrderHistoryEntry(): OrderHistoryEntry =
  OrderHistoryEntry(order = toOpenOrder(), status = cancellationNote()?.let { "$status: $it" } ?: status)

/**
 * Why Decibel cancelled the order, in a few words; null when it didn't or the person did. An order
 * the account couldn't pay for is cancelled after its transaction confirms, with only this to say so.
 */
private fun Order.cancellationNote(): String? {
  if (!status.lowercase().startsWith("cancel")) return null
  val reason = cancellationReason.filter(Char::isLetter).lowercase()
  val detail = details.trim()
  return when {
    detail.contains("margin", ignoreCase = true) -> "insufficient margin"
    detail.contains("balance", ignoreCase = true) -> "insufficient balance"
    reason.isEmpty() || reason == "ordercancelledbyuser" -> null
    reason == "postonlyviolation" -> "post-only would have crossed"
    reason == "iocviolation" -> "couldn’t fill immediately"
    reason == "reduceonlyviolation" -> "reduce-only"
    reason == "disallowedselftrading" -> "self-trade"
    reason == "deadmansswitchexpired" -> "scheduled"
    detail.isNotEmpty() -> detail.replaceFirstChar(Char::lowercase)
    else ->
      cancellationReason.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
  }
}

internal fun DecibelMarketTrade.toFill(): Fill =
  Fill(
    // Decibel leaves some fills without a trade id; these fields together still tell them apart.
    id = tradeId.ifBlank { "$transactionVersion:$market:$account:$price:$size" },
    marketId = market,
    kind = assetType.toMarketKind(),
    direction = decibelDirection(action),
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
    direction = decibelDirection(action),
  )

/** Decibel writes trade actions as `CloseLong` and, in its documentation, `Close Long`. */
private fun decibelDirection(action: String): FillDirection =
  when (action.filterNot { it.isWhitespace() }) {
    "OpenLong" -> FillDirection.OPEN_LONG
    "CloseLong" -> FillDirection.CLOSE_LONG
    "OpenShort" -> FillDirection.OPEN_SHORT
    "CloseShort" -> FillDirection.CLOSE_SHORT
    else -> FillDirection.OTHER
  }

/** Decibel's fund history: indexed USDC deposits, withdrawals and rewards, all settled. */
internal fun DecibelFundMovement.toFundMovement(): FundMovement =
  FundMovement(
    kind =
      when (movementType.lowercase()) {
        "deposit" -> FundMovementKind.DEPOSIT
        "reward" -> FundMovementKind.REWARD
        else -> FundMovementKind.WITHDRAWAL
      },
    amount = kotlin.math.abs(amount),
    assetSymbol = "USDC",
    status = "confirmed",
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

/**
 * Decibel names the plotted value differently by endpoint version; any of them will do. Its
 * account value is perps only, and spot holdings come beside it, so they are added back.
 */
internal fun PortfolioChartPoint.toPortfolioPoint(metric: PortfolioMetric): SeriesPoint =
  SeriesPoint(
    timeMs = timestamp,
    value =
      when (metric) {
        PortfolioMetric.EQUITY -> (dataPoints ?: accountValue ?: value) + (spotValue ?: 0.0)
        PortfolioMetric.PNL -> dataPoints ?: realizedPnl ?: value
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
    totalSize = originalSize,
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
    kind = if (isProtocol) VaultKind.PROTOCOL else VaultKind.USER,
    totalValue = totalAum,
    sharePrice = sharePrice,
    // Decibel crystallizes on an interval it doesn't report the length of.
    fee = ManagerFee.Interval(performanceFeePercent),
    // A lockup Decibel reports as zero means none; one it leaves out is unknown.
    lockupSeconds = lockdownPeriodS ?: lockupSecondsFallback.takeIf { it > 0 },
    // Decibel reports no manager stake. `manager_cash_pct` is not one: every vault with no open
    // position reads exactly 100, including vaults with a hundred depositors.
    managerStakePercent = null,
    depositorCount = depositors,
    performance = decibelVaultPerformance(),
    isActive = isActive,
  )

/** What Decibel measures about a vault beyond the common set, in the order its dashboard shows. */
private fun VaultInfo.decibelVaultPerformance(): List<VaultFact> = buildList {
  // Decibel reports these as percentages already, unlike Hyperliquid's fractional APR.
  apr?.let { add(VaultFact("APR", VaultFactValue.Percent(it, signed = true))) }
  pastMonthReturn?.let { add(VaultFact("Past month", VaultFactValue.Percent(it, signed = true))) }
  allTimeReturn?.let { add(VaultFact("All time", VaultFactValue.Percent(it, signed = true))) }
  maxDrawdown?.let { add(VaultFact("Max drawdown", VaultFactValue.Percent(it))) }
  sharpeRatio?.let { add(VaultFact("Sharpe ratio", VaultFactValue.Ratio(it))) }
  // A fraction, unlike the returns above.
  weeklyWinRate12w?.let { add(VaultFact("Weekly win rate", VaultFactValue.Percent(it * 100.0))) }
  // A vault that hasn't traded reports zero volume; that is nothing to show.
  volume30d?.takeIf { it > 0.0 }?.let { add(VaultFact("30-day volume", VaultFactValue.Money(it))) }
  // How much of the vault is sitting in cash rather than deployed: 100% means nothing is open.
  managerCashPct?.let { add(VaultFact("Held in cash", VaultFactValue.Percent(it))) }
}

internal fun AccountVaultPerformance.toVaultPosition(): VaultPosition =
  VaultPosition(
    vault = vault.toVaultSummary(),
    shares = currentNumShares,
    value = currentValue,
    returnPercent = returnsPercent,
    sharePrice = effectiveSharePrice,
    deposited = totalDeposited,
    withdrawn = totalWithdrawn,
    earned = allTimeEarned,
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
 * Decibel lists each permission a key holds on its own, so one key that trades everything shows
 * up as a perps grant and a spot grant. Each key becomes one entry with what it can trade in all.
 */
internal fun List<Delegation>.toTradingKeys(): List<TradingKey> =
  groupBy { it.delegate.lowercase() }.values.mapNotNull { grants ->
    val perps = grants.firstOrNull { it.canTradeAllPerpMarkets }
    val spot = grants.firstOrNull { it.canTradeAllSpotMarkets }
    val oneMarket = grants.firstOrNull { it.permissionType == "TradePerpsOnMarket" }
    val expiries = listOfNotNull(perps, spot).ifEmpty { listOfNotNull(oneMarket) }
    val first = expiries.firstOrNull() ?: return@mapNotNull null
    val expiresAtMs =
      if (expiries.any { it.expirationTimeSeconds == null }) null
      else expiries.mapNotNull { it.expirationTimeSeconds }.minOrNull()?.times(1_000L)
    when {
      perps != null && spot != null ->
        TradingKey(first.delegate, TradingKeyScope.ALL_MARKETS, null, expiresAtMs)
      else -> first.toTradingKey().copy(expiresAtMs = expiresAtMs)
    }
  }

internal fun Subaccount.toTradingAccount(): TradingAccount =
  TradingAccount(
    address = address,
    owner = owner,
    label = customLabel,
    primary = isPrimary,
    active = isActive,
  )

internal fun SubaccountStatus.toTradingAccountStatus(): TradingAccountStatus =
  when (this) {
    SubaccountStatus.ACTIVE -> TradingAccountStatus.ACTIVE
    SubaccountStatus.INACTIVE -> TradingAccountStatus.INACTIVE
    SubaccountStatus.NOT_SUBACCOUNT -> TradingAccountStatus.NOT_A_TRADING_ACCOUNT
  }

/**
 * Decibel's rewards: Amps, the trading streak, the tier, campaigns and referrals, each read on its
 * own. A part that failed is marked unavailable so the screens keep what they showed for it.
 */
internal fun decibelRewards(
  streak: Result<DecibelTradingStreak?>,
  amps: Result<AmpsBreakdown?>,
  tier: Result<TierInfo?>,
  campaigns: Result<UserCampaigns?> = Result.success(null),
  referral: Result<ReferralSummary?> = Result.success(null),
  nowMs: Long = Clock.System.now().toEpochMilliseconds(),
  global: GlobalPointsStats? = null,
): Rewards =
  Rewards(
    points = amps.getOrNull()?.toRewardPoints(global),
    streak =
      streak.getOrNull()?.let {
        TradingStreak(
          qualifyingDates =
            it.qualifyingDates.mapNotNullTo(mutableSetOf()) { date ->
              runCatching { LocalDate.parse(date) }.getOrNull()
            },
          days = it.streakCount,
          graceDaysRemaining = it.graceDaysRemaining,
        )
      },
    tier =
      tier.getOrNull()?.let {
        RewardTier(name = it.tier, percentile = it.percentile, feeDiscountBps = it.feeDiscountBps)
      },
    campaigns = campaigns.getOrNull()?.toCampaignRewards(nowMs),
    referral = referral.getOrNull(),
    unavailable =
      buildSet {
        if (amps.isFailure) add(RewardPart.POINTS)
        if (streak.isFailure) add(RewardPart.STREAK)
        if (tier.isFailure) add(RewardPart.TIER)
        if (campaigns.isFailure) add(RewardPart.CAMPAIGNS)
        if (referral.isFailure) add(RewardPart.REFERRAL)
      },
  )

private fun AmpsBreakdown.toRewardPoints(global: GlobalPointsStats?): RewardPoints =
  RewardPoints(
    total = totalAmps,
    rank = rank,
    standing =
      global?.takeIf { it.totalUsers > 0 }?.let {
        PointsStanding(holders = it.totalUsers, distributed = it.totalAmpsDistributed)
      },
    sources =
      listOf(
        RewardPointsSource("Trading", tradingAmps),
        RewardPointsSource("Streaks", streakAmps),
        RewardPointsSource("Vaults", vaultAmps),
        RewardPointsSource("Referrals", referralAmps),
        RewardPointsSource("Bonuses", bonusAmps),
      ),
  )

/**
 * The account as a referrer and as a referral. Decibel withholds a trader's code until the account
 * passes its lifetime volume gate; an affiliate sees theirs regardless. Null [stats] is an account
 * that has never referred anyone.
 */
internal fun decibelReferral(stats: ReferrerStats?, referral: AccountReferralInfo?): ReferralSummary {
  val code = stats?.codes?.firstOrNull(String::isNotBlank)
  return ReferralSummary(
    code = code,
    referredBy = referral?.referralCode?.takeIf(String::isNotBlank),
    referredCount = stats?.totalReferrals,
    codeUnlocked = code != null || stats?.volumeThresholdMet == true || stats?.isAffiliate == true,
    affiliate = stats?.isAffiliate == true,
  )
}

/**
 * Decibel's campaign allocations. Amounts stay exact decimal text from chain units: a claim signs
 * them, so nothing here becomes a Double. [nowMs] decides whether a window has closed.
 */
internal fun UserCampaigns.toCampaignRewards(nowMs: Long): CampaignRewards =
  CampaignRewards(
    readyToClaim = readyToClaim.toUsdcText(),
    lifetimeEarned = lifetimeEarned.toUsdcText(),
    campaigns = claims.map { it.toCampaign(nowMs) },
  )

private fun UserClaim.toCampaign(nowMs: Long): Campaign {
  val claimEndMs = claimEndTsSec * 1_000
  val claimStartMs = claimStartTsSec * 1_000
  val state =
    when {
      claimedAmount > 0 || claimedAtTsSec != null -> ClaimState.CLAIMED
      !hasAllocation -> ClaimState.NOT_ELIGIBLE
      claimEndTsSec > 0 && nowMs > claimEndMs -> ClaimState.EXPIRED
      readyToClaim > 0 && nowMs >= claimStartMs -> ClaimState.READY
      else -> ClaimState.IN_PROGRESS
    }
  val units =
    when (state) {
      ClaimState.CLAIMED -> claimedAmount
      ClaimState.READY -> readyToClaim
      else -> claimableAmount
    }
  return Campaign(
    id = campaignId.toString(),
    title = title,
    detail = description.orEmpty(),
    state = state,
    amount = units.takeIf { it > 0 }?.toUsdcText(),
    claimableUntilMs = claimEndMs.takeIf { claimEndTsSec > 0 },
    claimedAtMs = claimedAtTsSec?.let { it * 1_000 },
    claimReference = claimTxHash,
  )
}

/** Chain units to exact decimal text; Decibel pays campaigns in USDC's six decimals. */
private fun Long.toUsdcText(): String = coerceAtLeast(0L).toULong().toDecimalString(USDC_DECIMALS)

private const val USDC_DECIMALS = 6

/** Decibel returns the season newest first; a chart reads oldest first. */
internal fun OwnerAmpsDaily.toPointsHistory(): List<SeriesPoint> =
  days.map { SeriesPoint(timeMs = it.dayStartUnixMs, value = it.totalAmps) }.sortedBy { it.timeMs }
