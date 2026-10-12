package xyz.mcxross.flare.venue.model

enum class OrderSide {
  BUY,
  SELL,
}

enum class OrderType {
  LIMIT,
  MARKET,
  TWAP,
}

enum class TimeInForce {
  GOOD_TILL_CANCELLED,
  POST_ONLY,
  IMMEDIATE_OR_CANCEL,
}

enum class MarginMode {
  CROSS,
  ISOLATED,
}

/** How a TWAP order is spread out. */
data class TwapSchedule(val frequencySeconds: Long, val durationSeconds: Long)

/**
 * An order as the person entered it. Prices and sizes are decimal text in the market's own units
 * (dollars, the base asset), never floating point.
 */
data class OrderRequest(
  val marketId: String,
  val side: OrderSide,
  val type: OrderType,
  val size: String,
  /** The limit price; once checked by [OrderRules], the price the order is sent at. */
  val price: String? = null,
  val timeInForce: TimeInForce = TimeInForce.GOOD_TILL_CANCELLED,
  val reduceOnly: Boolean = false,
  /** How far a market order may fill from the best price, in basis points. */
  val slippageBps: Int = 50,
  val clientOrderId: String? = null,
  val takeProfitTrigger: String? = null,
  val takeProfitLimit: String? = null,
  val stopLossTrigger: String? = null,
  val stopLossLimit: String? = null,
  val builderAddress: String? = null,
  val builderFeeBps: Int? = null,
  /** Set for a TWAP order. */
  val twap: TwapSchedule? = null,
)

/**
 * How an exchange checks a market's orders. Every [Market] carries its venue's rules, so screens
 * never compare raw price or size steps themselves. Messages are written for people.
 */
interface OrderRules {
  /** The smallest order size as decimal text, or null when the exchange sets none. */
  val minimumSize: String?

  /**
   * Checks [order] and resolves what the exchange will receive: a market order gets its
   * slippage-bounded price from [orderBook]'s best level.
   */
  fun check(order: OrderRequest, orderBook: OrderBook? = null): OrderCheck

  /** Checks a position's take-profit and stop-loss prices; null when both can be sent. */
  fun checkExitPrices(takeProfit: String?, stopLoss: String?): String?
}

sealed interface OrderCheck {
  /** [order] is what the exchange will receive. */
  data class Valid(val order: OrderRequest) : OrderCheck

  data class Invalid(val errors: List<String>) : OrderCheck
}

/** What the person asks the exchange to do. Amounts and prices are decimal text. */
sealed interface TradeAction {
  /** The trading account the action is for; null when it creates one. */
  val account: String?

  /** A limit, market or TWAP order, as checked by the market's [OrderRules]. */
  data class PlaceOrder(
    override val account: String,
    val market: Market,
    val order: OrderRequest,
  ) : TradeAction

  data class CancelOrder(
    override val account: String,
    val marketId: String,
    val orderId: String,
    val kind: MarketKind = MarketKind.PERP,
  ) : TradeAction

  data class SetPositionTpSl(
    override val account: String,
    val market: Market,
    val takeProfitTrigger: String? = null,
    val takeProfitLimit: String? = null,
    val takeProfitSize: String? = null,
    val stopLossTrigger: String? = null,
    val stopLossLimit: String? = null,
    val stopLossSize: String? = null,
  ) : TradeAction

  /**
   * Cancels several open orders in one request. Only venues with
   * [xyz.mcxross.flare.venue.VenueCapabilities.batchCancel] accept it.
   */
  data class CancelOrders(override val account: String, val orders: List<OpenOrderRef>) :
    TradeAction

  data class CancelPositionTpSl(
    override val account: String,
    val marketId: String,
    val orderId: String,
  ) : TradeAction

  /** Sets a market's leverage and margin mode. */
  data class ConfigureMarket(
    override val account: String,
    val marketId: String,
    val marginMode: MarginMode,
    val leverage: Int,
  ) : TradeAction

  data class CancelTwapOrder(
    override val account: String,
    val marketId: String,
    val twapId: String,
  ) : TradeAction

  /** Lets [tradingKey] trade for the account. */
  data class EnableTrading(
    override val account: String,
    val tradingKey: String,
    val expiresAtSeconds: Long? = null,
  ) : TradeAction

  data class RevokeTrading(override val account: String, val tradingKey: String) : TradeAction

  /** Lets [builder] charge up to [maxFeeBps] on [kind] orders. */
  data class ApproveBuilderFee(
    override val account: String,
    val builder: String,
    val maxFeeBps: Int,
    val kind: MarketKind = MarketKind.PERP,
  ) : TradeAction

  data class RevokeBuilderFee(
    override val account: String,
    val builder: String,
    val kind: MarketKind = MarketKind.PERP,
  ) : TradeAction

  /** Moves USDC from the owner wallet into the account. */
  data class Deposit(override val account: String, val amount: String) : TradeAction

  /** Moves USDC from the account back to the owner wallet. */
  data class Withdraw(override val account: String, val amount: String) : TradeAction

  /** Sends USDC from the account to [destination]. */
  data class Transfer(
    override val account: String,
    val destination: String,
    val amount: String,
  ) : TradeAction

  data class VaultDeposit(
    override val account: String,
    val vault: String,
    val amount: String,
  ) : TradeAction

  data class VaultWithdraw(
    override val account: String,
    val vault: String,
    val shares: String,
  ) : TradeAction

  data object CreateTradingAccount : TradeAction {
    override val account: String? = null
  }

  /**
   * Moves USDC between perpetuals and spot margin, where an exchange keeps them apart. Only venues
   * with [xyz.mcxross.flare.venue.VenueCapabilities.perpSpotTransfer] accept it.
   */
  data class MovePerpSpot(
    override val account: String,
    val amount: String,
    val toSpot: Boolean,
  ) : TradeAction
}

/** An open order, as a cancel names it. */
data class OpenOrderRef(val marketId: String, val orderId: String)

/** What an action does, so a venue can say which of its actions the app can send. */
enum class ActionKind {
  /** Orders, cancels, exits and leverage. */
  TRADING,
  /** Placing and canceling TWAP orders. */
  TWAP,
  /** Deposits, withdrawals, transfers and vault moves. */
  FUNDS,
  /** Enabling and revoking trading, builder fees and opening trading accounts. */
  SETUP,
}

val TradeAction.actionKind: ActionKind
  get() =
    when (this) {
      is TradeAction.PlaceOrder ->
        if (order.type == OrderType.TWAP) ActionKind.TWAP else ActionKind.TRADING
      is TradeAction.CancelTwapOrder -> ActionKind.TWAP
      is TradeAction.CancelOrder,
      is TradeAction.CancelOrders,
      is TradeAction.SetPositionTpSl,
      is TradeAction.CancelPositionTpSl,
      is TradeAction.ConfigureMarket -> ActionKind.TRADING
      is TradeAction.Deposit,
      is TradeAction.Withdraw,
      is TradeAction.Transfer,
      is TradeAction.VaultDeposit,
      is TradeAction.VaultWithdraw,
      is TradeAction.MovePerpSpot -> ActionKind.FUNDS
      is TradeAction.EnableTrading,
      is TradeAction.RevokeTrading,
      is TradeAction.ApproveBuilderFee,
      is TradeAction.RevokeBuilderFee,
      TradeAction.CreateTradingAccount -> ActionKind.SETUP
    }

/**
 * What the network fee would cost the wallet when the venue's sponsor declined it, in the smallest
 * unit of the chain's fee token (octas on Aptos). Only venues with sponsored fees offer it.
 */
data class SelfPayOffer(
  val estimate: ULong,
  /** What the sender must hold to pay: the network may set more than the fee aside first. */
  val reserve: ULong? = null,
)

/** Where an action stands, from the first check to its final outcome. */
sealed interface ActionState {
  data object Simulating : ActionState

  data object AwaitingAuthorization : ActionState

  data object Submitting : ActionState

  /** [reference] is the transaction hash, or the exchange's own action reference. */
  data class Pending(val reference: String) : ActionState

  /** [detail] says what came of it, where the exchange tells, such as an order's fill. */
  data class Committed(val reference: String, val detail: String? = null) : ActionState

  data class Failed(
    val message: String,
    val reference: String? = null,
    /** The action reached the exchange and was rejected there. */
    val committed: Boolean = false,
    val definitelyNotSubmitted: Boolean = false,
    /** Set when the wallet may pay the fee itself and try again. */
    val selfPay: SelfPayOffer? = null,
  ) : ActionState
}

/** One of an owner's trading accounts (Decibel subaccounts). */
data class TradingAccount(
  val address: String,
  val owner: String,
  val label: String? = null,
  val primary: Boolean = false,
  val active: Boolean = true,
)

/** What an address is, as far as the exchange's trading accounts are concerned. */
enum class TradingAccountStatus {
  ACTIVE,
  INACTIVE,
  NOT_A_TRADING_ACCOUNT,
}
