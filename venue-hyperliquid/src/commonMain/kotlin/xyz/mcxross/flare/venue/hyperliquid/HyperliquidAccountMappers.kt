package xyz.mcxross.flare.venue.hyperliquid

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.mcxross.flare.hyperliquid.api.StreamFunding
import xyz.mcxross.flare.hyperliquid.model.AccountAbstraction
import xyz.mcxross.flare.hyperliquid.model.ClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.Fill as HyperliquidFill
import xyz.mcxross.flare.hyperliquid.model.FrontendOrder
import xyz.mcxross.flare.hyperliquid.model.FundingUpdate
import xyz.mcxross.flare.hyperliquid.model.LedgerUpdate
import xyz.mcxross.flare.hyperliquid.model.OrderWithStatus
import xyz.mcxross.flare.hyperliquid.model.Portfolio
import xyz.mcxross.flare.hyperliquid.model.PortfolioPoint as HyperliquidPortfolioPoint
import xyz.mcxross.flare.hyperliquid.model.Referral
import xyz.mcxross.flare.hyperliquid.model.Side
import xyz.mcxross.flare.hyperliquid.model.SpotClearinghouseState
import xyz.mcxross.flare.hyperliquid.model.SpotMetaAndContexts
import xyz.mcxross.flare.hyperliquid.model.TwapHistoryEntry
import xyz.mcxross.flare.hyperliquid.model.TwapState
import xyz.mcxross.flare.hyperliquid.model.UserFees
import xyz.mcxross.flare.hyperliquid.model.VaultDetails
import xyz.mcxross.flare.hyperliquid.model.VaultEquity
import xyz.mcxross.flare.venue.model.AccountOverview
import xyz.mcxross.flare.venue.model.Balances
import xyz.mcxross.flare.venue.model.FeeRates
import xyz.mcxross.flare.venue.model.FeeTier
import xyz.mcxross.flare.venue.model.Fill
import xyz.mcxross.flare.venue.model.FillDirection
import xyz.mcxross.flare.venue.model.FundMovement
import xyz.mcxross.flare.venue.model.FundMovementKind
import xyz.mcxross.flare.venue.model.FundingPayment
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OpenOrder
import xyz.mcxross.flare.venue.model.OrderHistoryEntry
import xyz.mcxross.flare.venue.model.OrderTrigger
import xyz.mcxross.flare.venue.model.PortfolioChartRange
import xyz.mcxross.flare.venue.model.PortfolioMetric
import xyz.mcxross.flare.venue.model.PortfolioPoint
import xyz.mcxross.flare.venue.model.Position
import xyz.mcxross.flare.venue.model.ReferralSummary
import xyz.mcxross.flare.venue.model.RewardPart
import xyz.mcxross.flare.venue.model.Rewards
import xyz.mcxross.flare.venue.model.SpotBalance
import xyz.mcxross.flare.venue.model.SpotBalances
import xyz.mcxross.flare.venue.model.TradingFees
import xyz.mcxross.flare.venue.model.TwapOrder
import xyz.mcxross.flare.venue.model.ManagerFee
import xyz.mcxross.flare.venue.model.VaultFact
import xyz.mcxross.flare.venue.model.VaultFactValue
import xyz.mcxross.flare.venue.model.VaultKind
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

// Hyperliquid's account data as the app's exchange-neutral models. Market ids are coins.

/** USD prices of spot tokens by name, from the pairs quoted in USDC. */
internal fun SpotMetaAndContexts.tokenPrices(): Map<String, Double> {
  val contexts = contextByCoin
  val tokens = meta.tokens.associateBy { it.index }
  val prices = mutableMapOf(USDC to 1.0)
  meta.universe.forEach { pair ->
    if (pair.tokens.getOrNull(1) != USDC_TOKEN) return@forEach
    val base = tokens[pair.tokens.getOrNull(0)]?.name?.trim() ?: return@forEach
    val context = contexts[pair.name] ?: return@forEach
    val price = (context.midPx ?: context.markPx).toDoubleOrZero()
    if (price > 0.0 && base !in prices) prices[base] = price
  }
  return prices
}

/**
 * The account as the portfolio shows it. Under unified or portfolio margin spot USDC backs perps,
 * so it already holds the perp account's value: that part is [Balances.sharedWithEquity], and what
 * is free is spot USDC not held as margin. Otherwise perps and spot are separate accounts.
 */
internal fun accountOverview(
  perp: ClearinghouseState,
  spot: SpotClearinghouseState?,
  abstraction: AccountAbstraction?,
  tokenPrices: Map<String, Double>,
  vaultEquities: List<VaultEquity>?,
  nowMs: Long,
): AccountOverview {
  val equity = perp.marginSummary.accountValue.toDoubleOrZero()
  val unrealizedPnl = perp.assetPositions.sumOf { it.position.unrealizedPnl.toDoubleOrZero() }
  val maintenance = perp.crossMaintenanceMarginUsed.toDoubleOrZero()
  val spotBalances = spot?.toSpotBalances(tokenPrices)
  val usdc = spot?.balances?.firstOrNull { it.token == USDC_TOKEN }
  val vaultEquity =
    vaultEquities?.filter { (it.lockedUntilTimestamp ?: 0L) <= nowMs }?.sumOf { it.equity.toDoubleOrZero() }
  if (abstraction?.sharesSpotCollateral == true && usdc != null) {
    val usdcTotal = usdc.total.toDoubleOrZero()
    val free = (usdcTotal - usdc.hold.toDoubleOrZero()).coerceAtLeast(0.0)
    return AccountOverview(
      equity = equity,
      unrealizedPnl = unrealizedPnl,
      marginRatio = if (usdcTotal > 0.0) maintenance / usdcTotal else 0.0,
      availableToTrade = free,
      withdrawable = free,
      vaultEquity = vaultEquity,
      balances =
        Balances(
          collateral = null,
          spot = spotBalances,
          sharedWithEquity = equity.coerceIn(0.0, usdcTotal),
          spotBuyingPower = free,
        ),
    )
  }
  val crossValue = perp.crossMarginSummary.accountValue.toDoubleOrZero()
  val withdrawable = perp.withdrawable.toDoubleOrZero()
  return AccountOverview(
    equity = equity,
    unrealizedPnl = unrealizedPnl,
    marginRatio = if (crossValue > 0.0) maintenance / crossValue else 0.0,
    availableToTrade = withdrawable,
    withdrawable = withdrawable,
    vaultEquity = vaultEquity,
    // Only once the mode is known: moving USDC on a unified account would be meaningless.
    perpSpotSeparate = abstraction?.sharesSpotCollateral == false,
    balances =
      Balances(
        collateral = equity - unrealizedPnl,
        spot = spotBalances,
        // Perps and spot are separate accounts: only spot USDC buys spot.
        spotBuyingPower =
          spot?.let { (usdc?.total.toDoubleOrZero() - usdc?.hold.toDoubleOrZero()).coerceAtLeast(0.0) },
      ),
  )
}

/** Every token held, valued at its USDC pair; one without a USDC market is valued at zero. */
internal fun SpotClearinghouseState.toSpotBalances(tokenPrices: Map<String, Double>): SpotBalances {
  val assets =
    balances.mapNotNull { balance ->
      val amount = balance.total.toDoubleOrZero()
      if (amount == 0.0) return@mapNotNull null
      val symbol = balance.coin.trim()
      SpotBalance(
        assetId = balance.token.toString(),
        symbol = symbol,
        amount = amount,
        valueUsd = amount * (tokenPrices[symbol] ?: 0.0),
      )
    }
  return SpotBalances(assets = assets, totalUsd = assets.sumOf { it.valueUsd })
}

/** Open positions, with the take-profit and stop-loss set on each from [orders]. */
internal fun ClearinghouseState.toPositions(orders: List<FrontendOrder>): List<Position> =
  assetPositions.mapNotNull { entry ->
    val position = entry.position
    if (position.szi.toDoubleOrZero() == 0.0) return@mapNotNull null
    val exits = orders.exitsFor(position.coin, closesWithBuy = position.szi.trim().startsWith('-'))
    Position(
      marketId = position.coin,
      size = position.szi,
      leverage = position.leverage.value,
      entryPrice = position.entryPx.toDoubleOrZero(),
      isolated = !position.leverage.isCross,
      liquidationPrice = position.liquidationPx?.toDoubleOrNull(),
      takeProfitTriggerPrice = exits.filter { it.isTakeProfit }.shownExit()?.triggerPx?.toDoubleOrNull(),
      stopLossTriggerPrice = exits.filter { it.isStopLoss }.shownExit()?.triggerPx?.toDoubleOrNull(),
    )
  }

/**
 * The take-profits and stop-losses guarding the position in [coin]: reduce-only triggers on its
 * closing side, set for the position (`positionTpsl`) or attached to the order that opened it
 * (`normalTpsl`, listed as plain reduce-only triggers once that order fills). Exits of an order
 * still resting don't guard the position yet.
 */
internal fun List<FrontendOrder>.exitsFor(coin: String, closesWithBuy: Boolean): List<FrontendOrder> {
  val waiting = flatMap { it.children }.map { it.oid }.toSet()
  return filter {
    it.coin == coin && it.reduceOnly && (it.isTakeProfit || it.isStopLoss) &&
      (it.side == Side.BUY) == closesWithBuy && it.oid !in waiting
  }
}

/** A position-level exit first, otherwise the latest one attached to an order. */
private fun List<FrontendOrder>.shownExit(): FrontendOrder? =
  firstOrNull { it.isPositionTpsl } ?: maxByOrNull { it.timestamp }

internal val FrontendOrder.isTakeProfit: Boolean
  get() = orderType.startsWith("Take Profit")

internal val FrontendOrder.isStopLoss: Boolean
  get() = orderType.startsWith("Stop")

private val FrontendOrder.isMarket: Boolean
  get() = orderType.endsWith("Market")

private fun FrontendOrder.toTrigger(): OrderTrigger? =
  triggerPx.toDoubleOrNull()?.takeIf { it > 0.0 }?.let {
    OrderTrigger(triggerPrice = it, limitPrice = if (isMarket) null else limitPx.toDoubleOrNull())
  }

internal fun FrontendOrder.toOpenOrder(): OpenOrder =
  OpenOrder(
    marketId = coin,
    orderId = oid.toString(),
    clientOrderId = cloid,
    kind = marketKindOf(coin),
    isBuy = side == Side.BUY,
    orderType = orderType,
    // A market trigger's limit price is only its slippage bound.
    price = if (isMarket) null else limitPx.toDoubleOrNull(),
    originalSize = origSz.toDoubleOrNull(),
    remainingSize = sz.toDoubleOrNull(),
    reduceOnly = reduceOnly,
    isPositionTpSl = isPositionTpsl,
    triggerCondition = if (isTrigger) triggerCondition.takeUnless { it == "N/A" }.orEmpty() else "",
    triggerPrice = if (isTrigger) triggerPx.toDoubleOrNull()?.takeIf { it > 0.0 } else null,
    // Hyperliquid sizes an exit set for the whole position at 0.
    coversPosition = isPositionTpsl,
    takeProfit = children.firstOrNull { it.isTakeProfit }?.toTrigger(),
    stopLoss = children.firstOrNull { it.isStopLoss }?.toTrigger(),
    timeMs = timestamp,
  )

internal fun OrderWithStatus.toOrderHistoryEntry(): OrderHistoryEntry =
  OrderHistoryEntry(order = order.toOpenOrder(), status = orderStatusLabel(status))

/** Hyperliquid's order statuses in words; one it adds later reads as split camel case. */
internal fun orderStatusLabel(status: String): String =
  when (status) {
    "open" -> "Open"
    "filled" -> "Filled"
    "canceled" -> "Canceled"
    "triggered" -> "Triggered"
    "rejected" -> "Rejected"
    "marginCanceled" -> "Canceled: insufficient margin"
    "reduceOnlyCanceled" -> "Canceled: reduce-only"
    "siblingFilledCanceled" -> "Canceled: other exit filled"
    "selfTradeCanceled" -> "Canceled: self-trade"
    "liquidatedCanceled" -> "Canceled: liquidated"
    "scheduledCancel" -> "Canceled: scheduled"
    "badAloPxRejected" -> "Rejected: post-only would have crossed"
    "iocCancelRejected" -> "Rejected: couldn’t fill immediately"
    "minTradeNtlRejected" -> "Rejected: below the minimum value"
    "perpMarginRejected" -> "Rejected: insufficient margin"
    "insufficientSpotBalanceRejected" -> "Rejected: insufficient balance"
    else -> {
      val words = status.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().split(' ')
      val outcome = words.last()
      if (words.size > 1 && (outcome == "canceled" || outcome == "rejected")) {
        "${outcome.replaceFirstChar { it.uppercase() }}: ${words.dropLast(1).joinToString(" ")}"
      } else {
        words.joinToString(" ").replaceFirstChar { it.uppercase() }
      }
    }
  }

internal fun HyperliquidFill.toFill(): Fill =
  Fill(
    id = tid.toString(),
    marketId = coin,
    kind = marketKindOf(coin),
    direction =
      when (dir) {
        "Open Long" -> FillDirection.OPEN_LONG
        "Close Long" -> FillDirection.CLOSE_LONG
        "Open Short" -> FillDirection.OPEN_SHORT
        "Close Short" -> FillDirection.CLOSE_SHORT
        else -> FillDirection.OTHER
      },
    action = dir,
    size = sz.toDoubleOrZero(),
    price = px.toDoubleOrZero(),
    realizedPnl = closedPnl.toDoubleOrZero(),
    fee = fee.toDoubleOrZero(),
    timeMs = time,
  )

internal fun FundingUpdate.toFundingPayment(): FundingPayment =
  fundingPayment(delta.coin, delta.szi, delta.usdc, time)

internal fun StreamFunding.toFundingPayment(): FundingPayment? =
  time?.let { fundingPayment(coin, szi, usdc, it) }

private fun fundingPayment(coin: String, szi: String, usdc: String, timeMs: Long) =
  FundingPayment(
    marketId = coin,
    action = if (szi.trim().startsWith('-')) "Short funding" else "Long funding",
    size = szi.toDoubleOrZero().let { if (it < 0) -it else it },
    amount = usdc.toDoubleOrZero(),
    timeMs = timeMs,
  )

/**
 * A ledger entry as money moving in or out of [account]; null for entries that move nothing, such
 * as liquidations, staking and lending. A move between the account's perps and spot shows as a
 * transfer whose status says which way it went.
 */
internal fun LedgerUpdate.toFundMovement(account: String): FundMovement? {
  val incomingTo = { destination: String? -> destination.equals(account, ignoreCase = true) }
  fun movement(kind: FundMovementKind, amount: String?, symbol: String = USDC, status: String = COMPLETED) =
    amount?.toDoubleOrNull()?.let {
      FundMovement(kind, it, symbol, status, reference = hash, timeMs = time)
    }
  return when (delta.type) {
    "deposit" -> movement(FundMovementKind.DEPOSIT, delta.usdc)
    "withdraw" -> movement(FundMovementKind.WITHDRAWAL, delta.usdc)
    "internalTransfer",
    "subAccountTransfer" ->
      movement(
        if (incomingTo(delta.destination)) FundMovementKind.TRANSFER_IN
        else FundMovementKind.TRANSFER_OUT,
        delta.usdc,
      )
    "spotTransfer",
    "send" ->
      movement(
        if (incomingTo(delta.destination)) FundMovementKind.TRANSFER_IN
        else FundMovementKind.TRANSFER_OUT,
        delta.amount,
        delta.token?.trim() ?: USDC,
      )
    "accountClassTransfer" ->
      if (delta.toPerp == true) movement(FundMovementKind.TRANSFER_IN, delta.usdc, status = "to perps")
      else movement(FundMovementKind.TRANSFER_OUT, delta.usdc, status = "to spot")
    "vaultCreate",
    "vaultDeposit" -> movement(FundMovementKind.VAULT_DEPOSIT, delta.usdc)
    "vaultWithdraw" ->
      movement(FundMovementKind.VAULT_WITHDRAWAL, delta.netWithdrawnUsd ?: delta.requestedUsd)
    "vaultDistribution" -> movement(FundMovementKind.VAULT_WITHDRAWAL, delta.usdc)
    else -> null
  }
}

internal val PortfolioChartRange.hyperliquidPeriod: String
  get() =
    when (this) {
      PortfolioChartRange.DAY_1 -> "day"
      PortfolioChartRange.WEEK_1 -> "week"
      PortfolioChartRange.MONTH_1 -> "month"
      PortfolioChartRange.ALL -> "allTime"
    }

/** The whole account's history over [range]: account value, or P&L since the range began. */
internal fun Portfolio.chart(range: PortfolioChartRange, metric: PortfolioMetric): List<PortfolioPoint> {
  val period = this[range.hyperliquidPeriod] ?: return emptyList()
  val points =
    when (metric) {
      PortfolioMetric.EQUITY -> period.accountValueHistory
      PortfolioMetric.PNL -> period.pnlHistory
    }
  return points.map(HyperliquidPortfolioPoint::toPortfolioPoint)
}

private fun HyperliquidPortfolioPoint.toPortfolioPoint() = PortfolioPoint(timeMs, value.toDoubleOrZero())

internal fun UserFees.toTradingFees(): TradingFees =
  TradingFees(
    perp = FeeRates(makerRate = userAddRate.toDoubleOrZero(), takerRate = userCrossRate.toDoubleOrZero()),
    spot =
      if (userSpotAddRate != null && userSpotCrossRate != null) {
        FeeRates(makerRate = userSpotAddRate.toDoubleOrZero(), takerRate = userSpotCrossRate.toDoubleOrZero())
      } else null,
  )

/**
 * The account's place on the fee schedule. Its volume is the 14 complete days `dailyUserVlm` lists
 * before today, taker plus maker, as Hyperliquid's app sums it; the tier is the highest volume
 * tier that volume reaches, Tier 0 below the first.
 */
internal fun UserFees.toFeeTier(): FeeTier {
  val volume =
    dailyUserVlm.dropLast(1).takeLast(FEE_VOLUME_DAYS).sumOf {
      it.userCross.toDoubleOrZero() + it.userAdd.toDoubleOrZero()
    }
  val tier = feeSchedule?.tiers?.vip.orEmpty().count { volume >= it.ntlCutoff.toDoubleOrZero() }
  val rates = toTradingFees()
  return FeeTier(
    name = "Tier $tier",
    volumeUsd = volume,
    perp = checkNotNull(rates.perp),
    spot = rates.spot,
    stakingDiscount = activeStakingDiscount?.discount.toDoubleOrZero().takeIf { it > 0.0 },
    referralDiscount = activeReferralDiscount.toDoubleOrZero().takeIf { it > 0.0 },
  )
}

/** Rewards from what loaded; a part that didn't is listed as unavailable, both failing throws. */
internal fun hyperliquidRewards(feeTier: Result<FeeTier>, referral: Result<ReferralSummary>): Rewards {
  feeTier.exceptionOrNull()?.let { error -> if (referral.isFailure) throw error }
  return Rewards(
    feeTier = feeTier.getOrNull(),
    referral = referral.getOrNull(),
    unavailable =
      setOfNotNull(
        RewardPart.FEE_TIER.takeIf { feeTier.isFailure },
        RewardPart.REFERRAL.takeIf { referral.isFailure },
      ),
  )
}

/** The account's own code once it has made one, who referred it, and what referring earned. */
internal fun Referral.toReferralSummary(): ReferralSummary =
  ReferralSummary(
    code =
      referrerState?.takeIf { it.stage == "ready" }?.data?.get("code")
        ?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?.takeIf(String::isNotBlank),
    referredBy = referredBy?.code,
    earnedUsd = claimedRewards.toDoubleOrZero() + unclaimedRewards.toDoubleOrZero(),
  )

/** A running TWAP, as the `twapStates` stream reports it. */
internal fun TwapState.toTwapOrder(id: Long, status: String = "activated"): TwapOrder =
  TwapOrder(
    id = id.toString(),
    marketId = coin,
    isBuy = side == Side.BUY,
    totalSize = sz.toDoubleOrZero(),
    executedSize = executedSz.toDoubleOrZero(),
    status = status,
    durationSeconds = minutes * 60L,
    frequencySeconds = TWAP_SLICE_SECONDS,
  )

/**
 * Each TWAP once, at its latest status, newest first. History lists an entry per status change;
 * entries from before TWAP ids existed are told apart by when they started.
 */
internal fun List<TwapHistoryEntry>.latestTwaps(): List<TwapOrder> =
  groupBy { it.twapId?.toString() ?: "${it.state.coin}:${it.state.timestamp}" }
    .map { (id, entries) ->
      val latest = entries.maxBy { it.time }
      latest.state.toTwapOrder(0L, latest.status.status).copy(id = id) to latest.time
    }
    .sortedByDescending { it.second }
    .map { it.first }

/** A vault as the vault list shows it. Hyperliquid vaults have no shares: a stake is its value. */
internal fun VaultDetails.toVaultSummary(isProtocolVault: Boolean): VaultSummary =
  VaultSummary(
    address = vaultAddress,
    name = name,
    manager = leader,
    description = description,
    kind = if (isProtocolVault) VaultKind.PROTOCOL else VaultKind.USER,
    totalValue = portfolio?.get("day")?.accountValueHistory?.lastOrNull()?.value.toDoubleOrZero(),
    sharePrice = 1.0,
    // Hyperliquid takes its commission on profits above a depositor's own peak.
    fee = ManagerFee.HighWatermark(leaderCommission.toDoubleOrZero() * 100.0),
    lockupSeconds = if (isProtocolVault) PROTOCOL_VAULT_LOCKUP_SECONDS else USER_VAULT_LOCKUP_SECONDS,
    // A protocol vault has no manager staking their own money, so the leader's sliver isn't one.
    managerStakePercent =
      if (isProtocolVault) null
      else leaderFraction.toDoubleOrZero().takeIf { it > 0.0 }?.let { it * 100.0 },
    // `followers` is a capped page, not a count, so Hyperliquid reports no depositor total.
    depositorCount = null,
    // Hyperliquid reports APR as a fraction.
    performance =
      apr.toDoubleOrZero().takeIf { it > 0.0 }
        ?.let { listOf(VaultFact("APR", VaultFactValue.Percent(it * 100.0))) }
        .orEmpty(),
    isActive = !isClosed && allowDeposits,
  )

internal fun VaultEquity.toVaultPosition(vault: VaultSummary, details: VaultDetails?): VaultPosition {
  val value = equity.toDoubleOrZero()
  val follower = details?.followerState
  val allTimePnl = follower?.allTimePnl?.toDoubleOrNull()
  val invested = allTimePnl?.let { value - it }
  return VaultPosition(
    vault = vault,
    shares = value,
    value = value,
    returnPercent = if (invested != null && invested > 0.0) allTimePnl / invested * 100.0 else 0.0,
    sharePrice = 1.0,
    lockedUntilMs = (lockedUntilTimestamp ?: follower?.lockupUntil)?.takeIf { it > 0L },
    // Hyperliquid reports the stake's profit, not what went in and out of it.
    earned = allTimePnl,
  )
}

/** Spot coins are `PURR/USDC` or `@<index>`; perps are plain names, `dex:NAME` on builder DEXs. */
internal fun marketKindOf(coin: String): MarketKind =
  if (coin.startsWith("@") || '/' in coin) MarketKind.SPOT else MarketKind.PERP

internal fun String?.toDoubleOrZero(): Double = this?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

internal const val USDC = "USDC"
internal const val USDC_TOKEN = 0
private const val COMPLETED = "completed"

private const val FEE_VOLUME_DAYS = 14

/** Hyperliquid places a TWAP slice every 30 seconds. */
private const val TWAP_SLICE_SECONDS = 30L

/** Hyperliquid locks HLP deposits for 4 days and other vault deposits for 1 day. */
private const val PROTOCOL_VAULT_LOCKUP_SECONDS = 4 * 24 * 60 * 60L
private const val USER_VAULT_LOCKUP_SECONDS = 24 * 60 * 60L
