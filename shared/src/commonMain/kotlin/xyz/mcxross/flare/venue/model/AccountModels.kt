package xyz.mcxross.flare.venue.model

/**
 * What a trading account holds and is worth, as the portfolio shows it. Display only: amounts that
 * feed a transfer or an order are entered as decimal text and checked by the venue.
 */
data class AccountOverview(
  /** The perpetuals account's value: collateral plus unrealized P&L. */
  val equity: Double,
  val unrealizedPnl: Double,
  /** Maintenance margin over equity, as a fraction; liquidation starts at 1. */
  val marginRatio: Double,
  /** Collateral free to open new positions. */
  val availableToTrade: Double,
  /** Collateral that can leave the perpetuals account now. */
  val withdrawable: Double,
  /** Equity held in vaults that isn't locked; null when the venue doesn't report it. */
  val vaultEquity: Double? = null,
  val balances: Balances = Balances(),
  /**
   * Perpetuals and spot hold separate USDC, so moving USDC between them means something (see
   * [xyz.mcxross.flare.venue.VenueCapabilities.perpSpotTransfer]).
   */
  val perpSpotSeparate: Boolean = false,
)

/**
 * The account's assets. On some venues spot balances also back perpetuals (Hyperliquid's unified
 * margin): the same USDC then shows in spot and in [AccountOverview.equity], and
 * [sharedWithEquity] says how much of it to count only once.
 */
data class Balances(
  /** USDC collateral owned by the perpetuals account, margin in use included. */
  val collateral: Double? = null,
  /** Spot holdings, or null when the venue doesn't report them with the account. */
  val spot: SpotBalances? = null,
  /** Spot value already counted in [AccountOverview.equity]. */
  val sharedWithEquity: Double = 0.0,
  /**
   * USDC free to buy spot with, where the venue works it out; null means spot USDC plus what
   * perpetuals can withdraw.
   */
  val spotBuyingPower: Double? = null,
)

data class SpotBalances(
  val assets: List<SpotBalance> = emptyList(),
  /** Amounts held for resting spot orders, not included in [assets]. */
  val reserved: List<SpotReservation> = emptyList(),
  /** The holdings' total value in USD, as the venue values them. */
  val totalUsd: Double = 0.0,
)

data class SpotBalance(
  /** The venue's id for the asset, such as a token address or name. */
  val assetId: String,
  /** The asset's ticker; blank when the venue doesn't name it. */
  val symbol: String,
  val amount: Double,
  val valueUsd: Double,
)

/** An amount set aside for a resting spot order. */
data class SpotReservation(
  val marketId: String,
  val orderId: String,
  val isBuy: Boolean,
  val assetId: String,
  val amount: Double,
  val valueUsd: Double,
)

/** An open perpetual position. */
data class Position(
  val marketId: String,
  /**
   * The signed size, negative for a short, as decimal text: closing the position orders exactly
   * this size.
   */
  val size: String,
  val leverage: Int,
  val entryPrice: Double,
  val isolated: Boolean,
  /** Funding accrued on the position and not yet settled. */
  val unrealizedFunding: Double = 0.0,
  /** Null when the venue doesn't estimate one. */
  val liquidationPrice: Double? = null,
  /** The trigger price of the position's take-profit, if it has one. */
  val takeProfitTriggerPrice: Double? = null,
  /** The trigger price of the position's stop-loss, if it has one. */
  val stopLossTriggerPrice: Double? = null,
) {
  val isLong: Boolean
    get() = !size.trim().startsWith('-')

  /** [size] without its sign. */
  val absoluteSize: String
    get() = size.trim().removePrefix("-")
}

/** An exit that fires at [triggerPrice]; a market exit when [limitPrice] is null. */
data class OrderTrigger(val triggerPrice: Double, val limitPrice: Double? = null)

/** A working order, or an order as history records it (see [OrderHistoryEntry]). */
data class OpenOrder(
  val marketId: String,
  val orderId: String,
  val clientOrderId: String? = null,
  val kind: MarketKind = MarketKind.PERP,
  val isBuy: Boolean,
  /** The venue's name for the order type, such as `Limit`; blank when it gives none. */
  val orderType: String = "",
  /** The venue's description of what the order does, such as `Open Long`; blank when none. */
  val direction: String = "",
  /** The limit price; null for a market order. */
  val price: Double? = null,
  val originalSize: Double? = null,
  val remainingSize: Double? = null,
  val reduceOnly: Boolean = false,
  /** A take-profit or stop-loss attached to a position, cancelled as such. */
  val isPositionTpSl: Boolean = false,
  /** What makes a trigger order fire, in the venue's words; blank for an order that rests. */
  val triggerCondition: String = "",
  /** The price that sets a trigger order off; null for an order that rests. */
  val triggerPrice: Double? = null,
  /** An exit that closes the whole position, whatever its size when it fires. */
  val coversPosition: Boolean = false,
  /** The take-profit that comes with the order once it fills. */
  val takeProfit: OrderTrigger? = null,
  /** The stop-loss that comes with the order once it fills. */
  val stopLoss: OrderTrigger? = null,
  val timeMs: Long,
)

/** An order in the account's history and where it ended up. */
data class OrderHistoryEntry(
  val order: OpenOrder,
  /** The venue's word for the order's state, such as `Filled` or `Cancelled`. */
  val status: String,
)

enum class FillDirection {
  OPEN_LONG,
  CLOSE_LONG,
  OPEN_SHORT,
  CLOSE_SHORT,
  /** Anything else; [Fill.action] says what in the venue's words. */
  OTHER,
}

/** One of the account's trades. Display only. */
data class Fill(
  /** Stable across reloads and the live stream, so a fill is never listed twice. */
  val id: String,
  val marketId: String,
  val kind: MarketKind = MarketKind.PERP,
  val direction: FillDirection,
  /** The venue's own name for the fill. */
  val action: String,
  val size: Double,
  val price: Double,
  val realizedPnl: Double = 0.0,
  val fee: Double = 0.0,
  val timeMs: Long,
)

/** A funding payment on a position. Display only. */
data class FundingPayment(
  val marketId: String,
  /** The venue's label for the payment; blank when it gives none. */
  val action: String,
  val size: Double,
  /** Positive when the account received funding, negative when it paid. */
  val amount: Double,
  val fee: Double = 0.0,
  val timeMs: Long,
  /** The trade that settled it, when the venue says; otherwise [action] describes it. */
  val direction: FillDirection = FillDirection.OTHER,
)

enum class FundMovementKind(
  /** Whether the movement adds to the account. */
  val incoming: Boolean,
) {
  DEPOSIT(true),
  WITHDRAWAL(false),
  TRANSFER_IN(true),
  TRANSFER_OUT(false),
  VAULT_DEPOSIT(false),
  VAULT_WITHDRAWAL(true),
  REWARD(true),
}

/** Money moving in or out of the account. Display only. */
data class FundMovement(
  val kind: FundMovementKind,
  val amount: Double,
  val assetSymbol: String,
  /** The venue's word for its state, such as `confirmed`. */
  val status: String,
  /** The venue's reference for it, such as a transaction hash. */
  val reference: String? = null,
  /** When it happened; 0 when the venue didn't say. */
  val timeMs: Long,
)

/** The span a portfolio chart covers. */
enum class PortfolioChartRange(val label: String) {
  DAY_1("1D"),
  WEEK_1("1W"),
  MONTH_1("1M"),
  ALL("ALL"),
}

/** What a portfolio chart plots. */
enum class PortfolioMetric(val label: String) {
  EQUITY("Net value"),
  PNL("Realized P&L"),
}

/** One point of a portfolio chart, in USD. */
/** One point of a time series: a portfolio's value, a day's points, a vault's NAV. */
data class SeriesPoint(val timeMs: Long, val value: Double)

/** A product's fee rates, as fractions of notional. */
data class FeeRates(val makerRate: Double, val takerRate: Double)

/** The account's fee rates. */
data class TradingFees(val perp: FeeRates? = null, val spot: FeeRates? = null)

/** A TWAP order: one order split into slices over time. */
data class TwapOrder(
  val id: String,
  val marketId: String,
  val isBuy: Boolean,
  val totalSize: Double,
  val executedSize: Double,
  /** The venue's word for its state, such as `active`. */
  val status: String,
  val durationSeconds: Long,
  /** How often a slice is placed. */
  val frequencySeconds: Long,
)

/** Who runs a vault: the venue itself, or one of its users. */
enum class VaultKind {
  /** The venue's own vault, such as Decibel's DLP or Hyperliquid's HLP. */
  PROTOCOL,
  USER,
}

/**
 * How a vault's manager is paid. The mechanic decides what a depositor has to be told before they
 * contribute, so it is a type rather than a number: a venue with a new one won't compile until the
 * screen that explains fees accounts for it.
 */
sealed interface ManagerFee {
  /** The manager's share of profits, as a percentage. */
  val percent: Double

  /**
   * Charged at the end of each interval on the gain since that interval began, with no high
   * watermark. A stake that joins mid-interval pays on gains that predate it, and a manager earns
   * on a recovery that hasn't made earlier depositors whole. [days] and [nextChargeAtMs] are null
   * where the venue doesn't report them.
   */
  data class Interval(
    override val percent: Double,
    val days: Int? = null,
    val nextChargeAtMs: Long? = null,
  ) : ManagerFee

  /** Charged only on profits above the stake's own previous peak. */
  data class HighWatermark(override val percent: Double) : ManagerFee
}

/**
 * One measure a venue reports about a vault beyond the common set. The venue says what it has and
 * what kind of number it is; the screens decide how to show it, so a venue can be as detailed as
 * its exchange without `:shared` learning anything venue-specific.
 */
data class VaultFact(val label: String, val value: VaultFactValue)

sealed interface VaultFactValue {
  /** A USD amount. */
  data class Money(val usd: Double) : VaultFactValue

  /** Already in percent units: 12.5 means 12.5%. [signed] shows a leading + on gains. */
  data class Percent(val percent: Double, val signed: Boolean = false) : VaultFactValue

  /** A bare number, such as a ratio or a multiple. */
  data class Ratio(val value: Double, val decimals: Int = 2) : VaultFactValue

  data class Text(val text: String) : VaultFactValue
}

/** A vault the account can deposit into. Display only. */
data class VaultSummary(
  /** The venue's id for the vault, which deposits and redemptions name. */
  val address: String,
  val name: String,
  val manager: String = "",
  val description: String = "",
  val kind: VaultKind = VaultKind.USER,
  /** Assets under management, in USD. */
  val totalValue: Double = 0.0,
  val sharePrice: Double = 1.0,
  val fee: ManagerFee = ManagerFee.Interval(0.0),
  /** How long a deposit stays locked; null when the venue doesn't say. */
  val lockupSeconds: Long? = null,
  /**
   * What share of the vault the manager holds themselves, as a percentage. The manager's own money
   * at risk; null where the venue doesn't report it.
   */
  val managerStakePercent: Double? = null,
  /** How many accounts hold a stake; null where the venue doesn't report it. */
  val depositorCount: Int? = null,
  /** The venue's own performance and risk measures, in the order it reports them. */
  val performance: List<VaultFact> = emptyList(),
  val isActive: Boolean = true,
)

/** The account's stake in a vault. Display only. */
data class VaultPosition(
  val vault: VaultSummary,
  val shares: Double,
  /** What the stake is worth now, in USD. */
  val value: Double,
  /** All-time return, as a percentage. */
  val returnPercent: Double = 0.0,
  /** The share price the stake is valued at. */
  val sharePrice: Double = vault.sharePrice,
  /** Until when the latest deposit is locked, if the venue locks deposits. */
  val lockedUntilMs: Long? = null,
  /** Everything ever put in, in USD; null where the venue doesn't report it. */
  val deposited: Double? = null,
  /** Everything ever taken out, in USD; null where the venue doesn't report it. */
  val withdrawn: Double? = null,
  /** What the stake has made, in USD; null where the venue doesn't report it. */
  val earned: Double? = null,
)

/** What a key may trade for the account. */
enum class TradingKeyScope {
  ALL_MARKETS,
  PERP_MARKETS,
  SPOT_MARKETS,
  /** One market, named by [TradingKey.marketId]. */
  ONE_MARKET,
}

/** A key allowed to trade for the account, such as this device's trading key. */
data class TradingKey(
  val address: String,
  val scope: TradingKeyScope,
  val marketId: String? = null,
  /** When the permission ends; null when it doesn't. */
  val expiresAtMs: Long? = null,
)

/**
 * The account's standing in the venue's programs: Decibel's Amps, streak and tier, or a fee tier
 * and referral elsewhere. Shown only where the venue has rewards (`VenueCapabilities.rewards`).
 */
data class Rewards(
  val points: RewardPoints? = null,
  val streak: TradingStreak? = null,
  val tier: RewardTier? = null,
  val referral: ReferralSummary? = null,
  /** The account's trading-fee tier, where the venue sets fees by volume and staking. */
  val feeTier: FeeTier? = null,
  /** Rewards the venue pays out on request; null where it runs no such programme. */
  val campaigns: CampaignRewards? = null,
  /** Parts that couldn't be loaded this time. Screens keep what they showed for them. */
  val unavailable: Set<RewardPart> = emptySet(),
) {
  /** True when no part of the rewards has anything to show yet. */
  val isEmpty: Boolean
    get() =
      points == null && streak == null && tier == null && referral == null && feeTier == null &&
        campaigns?.hasAnything != true

  /** These rewards, with the parts that failed to load taken from [previous]. */
  fun keepingUnavailableFrom(previous: Rewards?): Rewards =
    copy(
      points = if (RewardPart.POINTS in unavailable) previous?.points else points,
      streak = if (RewardPart.STREAK in unavailable) previous?.streak else streak,
      tier = if (RewardPart.TIER in unavailable) previous?.tier else tier,
      referral = if (RewardPart.REFERRAL in unavailable) previous?.referral else referral,
      feeTier = if (RewardPart.FEE_TIER in unavailable) previous?.feeTier else feeTier,
      campaigns = if (RewardPart.CAMPAIGNS in unavailable) previous?.campaigns else campaigns,
    )
}

enum class RewardPart {
  POINTS,
  STREAK,
  TIER,
  REFERRAL,
  FEE_TIER,
  CAMPAIGNS,
}

/** Where the account stands on the venue's fee schedule. Rates are fractions of notional. */
data class FeeTier(
  /** Such as "Tier 1". */
  val name: String,
  /** The volume the tier is set by, in USD, such as the last 14 days'. */
  val volumeUsd: Double,
  /** What the account pays now, discounts and rebates included; negative is a rebate. */
  val perp: FeeRates,
  val spot: FeeRates? = null,
  /** The share of fees taken off for staking, as a fraction; null when none. */
  val stakingDiscount: Double? = null,
  /** The share of fees taken off for being referred, as a fraction; null when none. */
  val referralDiscount: Double? = null,
)

/**
 * A reward the venue has allocated and pays out on request, such as a rebate or a milestone. The
 * amounts are exact decimal text because a claim signs them; nothing here is parsed to a Double.
 */
data class Campaign(
  val id: String,
  val title: String,
  val detail: String = "",
  val state: ClaimState,
  /** What this allocation is worth, as exact decimal text; null when there is no allocation. */
  val amount: String? = null,
  val assetSymbol: String = "USDC",
  /** When the claim window closes. An allocation lapses after it, unclaimed. */
  val claimableUntilMs: Long? = null,
  val claimedAtMs: Long? = null,
  /** The transaction that paid it, where the venue reports one. */
  val claimReference: String? = null,
)

/** Where an allocation stands, in the order a person moves through it. */
enum class ClaimState {
  /** The account earned nothing from this one. */
  NOT_ELIGIBLE,
  /** Still accruing, or published but not yet open to claim. */
  IN_PROGRESS,
  READY,
  CLAIMED,
  /** The claim window closed before it was taken. */
  EXPIRED,
}

/** Everything the venue has allocated to the account, and what is claimable right now. */
data class CampaignRewards(
  /** Exact decimal text, summed across campaigns ready to claim. */
  val readyToClaim: String,
  val lifetimeEarned: String,
  val assetSymbol: String = "USDC",
  val campaigns: List<Campaign> = emptyList(),
) {
  val hasAnything: Boolean
    get() = campaigns.isNotEmpty()
}

/** Reward points earned, such as Decibel's Amps; the venue descriptor names them. */
data class RewardPoints(
  val total: Double,
  val rank: Int? = null,
  /** Where the points came from, in the venue's order. */
  val sources: List<RewardPointsSource> = emptyList(),
)

data class RewardPointsSource(val label: String, val amount: Double)

/** Consecutive trading days. */
data class TradingStreak(
  val days: Int,
  val longestDays: Int = 0,
  val graceDaysRemaining: Int = 0,
)

/** The account's tier, such as a rewards tier or a fee tier. */
data class RewardTier(
  val name: String,
  val percentile: Double? = null,
  val feeDiscountBps: Int? = null,
)

/** The account's referral standing. */
data class ReferralSummary(
  /** The account's own code, if it has one. */
  val code: String? = null,
  /** Who referred the account, if anyone. */
  val referredBy: String? = null,
  /** Rewards earned from referrals, in USD. */
  val earnedUsd: Double? = null,
)
