package xyz.mcxross.flare.venue.hyperliquid

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.data.formatPrice
import xyz.mcxross.flare.hyperliquid.HyperliquidClient
import xyz.mcxross.flare.hyperliquid.model.AssetDirectory
import xyz.mcxross.flare.hyperliquid.model.AssetKind
import xyz.mcxross.flare.hyperliquid.model.BuilderFee
import xyz.mcxross.flare.hyperliquid.model.CancelStatus
import xyz.mcxross.flare.hyperliquid.model.Cloid
import xyz.mcxross.flare.hyperliquid.model.ExchangeResponse
import xyz.mcxross.flare.hyperliquid.model.HlDecimal
import xyz.mcxross.flare.hyperliquid.model.HlRounding
import xyz.mcxross.flare.hyperliquid.model.HyperliquidAsset
import xyz.mcxross.flare.hyperliquid.model.OrderGrouping
import xyz.mcxross.flare.hyperliquid.model.OrderRequest as WireOrder
import xyz.mcxross.flare.hyperliquid.model.OrderStatus
import xyz.mcxross.flare.hyperliquid.model.OrderType as WireOrderType
import xyz.mcxross.flare.hyperliquid.model.PlaceOrders
import xyz.mcxross.flare.hyperliquid.model.PlaceTwap
import xyz.mcxross.flare.hyperliquid.model.TimeInForce as WireTimeInForce
import xyz.mcxross.flare.hyperliquid.model.TpSl
import xyz.mcxross.flare.hyperliquid.model.TwapOrderStatus
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TimeInForce

/**
 * Hyperliquid's assets by coin, read from `meta` and `spotMeta` once and again when a coin isn't
 * listed yet. Asset ids never change for a listed coin, so orders can rely on them.
 */
class HyperliquidAssets(private val client: HyperliquidClient) {
  private val mutex = Mutex()
  private var directory: AssetDirectory? = null

  suspend fun asset(coin: String): HyperliquidAsset {
    mutex.withLock { directory }?.byCoin(coin)?.let { return it }
    return mutex.withLock {
      AssetDirectory(client.info.perpMeta(), client.info.spotMeta()).also { directory = it }
    }.byCoin(coin) ?: throw IllegalArgumentException("Hyperliquid doesn’t list $coin")
  }
}

/** Builds the `order` actions Flare sends. Prices and sizes stay decimal text until they're wired. */
internal object HyperliquidOrders {
  /**
   * Hyperliquid's app sends a market trigger with a limit price 10% past its trigger, so it fills
   * once triggered without filling at any price.
   */
  private val TRIGGER_SLIPPAGE: HlDecimal = HlDecimal.of(1, 1)

  /**
   * [order] as checked by the market's [HyperliquidOrderRules]: a market order is an IOC limit at
   * its slippage-bounded price. Attached take-profit and stop-loss become reduce-only trigger
   * children grouped as `normalTpsl`. The order alone carries [cloid].
   */
  fun place(asset: HyperliquidAsset, order: OrderRequest, cloid: Cloid): PlaceOrders {
    require(order.type != OrderType.TWAP) { "A TWAP isn’t an order Hyperliquid places this way" }
    val isBuy = order.side == OrderSide.BUY
    val size = validSize(asset, order.size)
    val price = validPrice(asset, "Price", order.price ?: throw IllegalArgumentException("Enter a price"))
    val timeInForce =
      if (order.type == OrderType.MARKET) WireTimeInForce.IOC
      else
        when (order.timeInForce) {
          TimeInForce.GOOD_TILL_CANCELLED -> WireTimeInForce.GTC
          TimeInForce.POST_ONLY -> WireTimeInForce.ALO
          TimeInForce.IMMEDIATE_OR_CANCEL -> WireTimeInForce.IOC
        }
    val entry =
      WireOrder(asset.id, isBuy, price, size, order.reduceOnly, WireOrderType.Limit(timeInForce), cloid)
    val exits =
      listOfNotNull(
        order.takeProfitTrigger?.let {
          exit(asset, !isBuy, size, TpSl.TAKE_PROFIT, it, order.takeProfitLimit)
        },
        order.stopLossTrigger?.let { exit(asset, !isBuy, size, TpSl.STOP_LOSS, it, order.stopLossLimit) },
      )
    require(exits.isEmpty() || asset.kind == AssetKind.PERP) {
      "Spot orders can’t carry a take profit or stop loss"
    }
    return PlaceOrders(
      orders = listOf(entry) + exits,
      grouping = if (exits.isEmpty()) OrderGrouping.NA else OrderGrouping.NORMAL_TPSL,
      builder = builderFee(order.builderAddress, order.builderFeeBps),
    )
  }

  /**
   * Take-profit and stop-loss for a whole position (`positionTpsl`): a size of zero follows the
   * position as it changes, as Hyperliquid's app sends it. [closesWithBuy] is true for a short.
   */
  fun positionExits(
    asset: HyperliquidAsset,
    closesWithBuy: Boolean,
    takeProfit: Exit?,
    stopLoss: Exit?,
    cloid: Cloid,
  ): PlaceOrders {
    require(asset.kind == AssetKind.PERP) { "Only perpetual positions have exits" }
    val orders =
      listOfNotNull(
        takeProfit?.let { exit(asset, closesWithBuy, it.size(asset), TpSl.TAKE_PROFIT, it.trigger, it.limit) },
        stopLoss?.let { exit(asset, closesWithBuy, it.size(asset), TpSl.STOP_LOSS, it.trigger, it.limit) },
      )
    require(orders.isNotEmpty()) { "Enter a take-profit or stop-loss price" }
    return PlaceOrders(
      orders = listOf(orders.first().copy(cloid = cloid)) + orders.drop(1),
      grouping = OrderGrouping.POSITION_TPSL,
    )
  }

  /**
   * [order]'s TWAP over its whole minutes. Hyperliquid places a slice every 30 seconds, so the
   * entered interval has no say, and builder fees don't apply to TWAPs.
   */
  fun twap(asset: HyperliquidAsset, order: OrderRequest): PlaceTwap {
    require(order.type == OrderType.TWAP) { "Not a TWAP order" }
    val schedule = order.twap ?: throw IllegalArgumentException("Choose how long the TWAP runs")
    require(schedule.durationSeconds > 0 && schedule.durationSeconds % 60 == 0L) {
      "A TWAP runs for whole minutes"
    }
    val minutes = (schedule.durationSeconds / 60).toInt()
    require(minutes in HyperliquidOrderRules.TWAP_MINUTES) { HyperliquidOrderRules.TWAP_DURATION }
    return PlaceTwap(
      asset = asset.id,
      isBuy = order.side == OrderSide.BUY,
      size = validSize(asset, order.size),
      reduceOnly = order.reduceOnly,
      minutes = minutes,
      randomize = false,
    )
  }

  /** An exit as entered; no [size] means the whole position. */
  data class Exit(val trigger: String, val limit: String? = null, val size: String? = null) {
    fun size(asset: HyperliquidAsset): HlDecimal = size?.let { validSize(asset, it) } ?: HlDecimal.ZERO
  }

  /**
   * A reduce-only trigger. Without a limit of its own (or one equal to the trigger, as the screens
   * enter it) it's a market trigger, bounded at [TRIGGER_SLIPPAGE] past the trigger.
   */
  private fun exit(
    asset: HyperliquidAsset,
    isBuy: Boolean,
    size: HlDecimal,
    tpsl: TpSl,
    triggerText: String,
    limitText: String?,
  ): WireOrder {
    val label = if (tpsl == TpSl.TAKE_PROFIT) "Take profit" else "Stop loss"
    val trigger = validPrice(asset, label, triggerText)
    val limit = limitText?.takeIf(String::isNotBlank)?.let { validPrice(asset, label, it) }
    val isMarket = limit == null || limit.compareTo(trigger) == 0
    val price = if (isMarket) triggerBound(asset, trigger, isBuy) else checkNotNull(limit)
    return WireOrder(
      asset.id,
      isBuy,
      price,
      size,
      reduceOnly = true,
      type = WireOrderType.Trigger(trigger, isMarket, tpsl),
    )
  }

  /** The worst price a market trigger may fill at, rounded to stay inside the bound. */
  fun triggerBound(asset: HyperliquidAsset, trigger: HlDecimal, isBuy: Boolean): HlDecimal {
    val factor = if (isBuy) HlDecimal.ONE + TRIGGER_SLIPPAGE else HlDecimal.ONE - TRIGGER_SLIPPAGE
    val bound = asset.roundPrice(trigger * factor, if (isBuy) HlRounding.FLOOR else HlRounding.CEILING)
    require(bound.signum > 0) { "That trigger price is too small" }
    return bound
  }

  /** Hyperliquid counts builder fees in tenths of a basis point; no builder means no fee field. */
  fun builderFee(address: String?, feeBps: Int?): BuilderFee? {
    val builder = address?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val bps = feeBps?.takeIf { it > 0 } ?: return null
    return BuilderFee(builder, bps * 10)
  }

  private fun validSize(asset: HyperliquidAsset, text: String): HlDecimal {
    val size = HlDecimal.parseOrNull(text)
    require(size != null && asset.isValidSize(size)) {
      "Size can have at most ${asset.szDecimals} decimals"
    }
    return size
  }

  private fun validPrice(asset: HyperliquidAsset, label: String, text: String): HlDecimal {
    val price = HlDecimal.parseOrNull(text)
    require(price != null && asset.isValidPrice(price)) {
      "$label can have at most 5 significant figures and ${asset.maxPriceDecimals} decimals"
    }
    return price
  }

  fun requireKind(asset: HyperliquidAsset, kind: MarketKind) {
    val expected = if (kind == MarketKind.SPOT) AssetKind.SPOT else AssetKind.PERP
    require(asset.kind == expected) { "${asset.displayName} isn’t a ${kind.name.lowercase()} market" }
  }
}

/** What Hyperliquid's answers to Flare's actions mean for the person. Null: it can't tell. */
internal object HyperliquidResults {
  /**
   * The answer to an `order` action whose orders are [labels], in order. The first order decides
   * the outcome; an exit that failed beside it is said in the detail. An order resting or filled
   * is committed under its oid.
   */
  fun orders(response: ExchangeResponse, reference: String, labels: List<String>): ActionState? {
    val statuses = (response as? ExchangeResponse.Orders)?.statuses ?: return null
    if (statuses.isEmpty() || statuses.size > labels.size) return null
    if (statuses.any { it is OrderStatus.Unrecognized }) return null
    val failures =
      statuses.mapIndexedNotNull { index, status ->
        (status as? OrderStatus.Error)?.let { "${labels[index]}: ${it.message}" }
      }
    if (failures.size == statuses.size) {
      return ActionState.Failed(
        (statuses.first() as OrderStatus.Error).message,
        reference,
        committed = true,
      )
    }
    val first = statuses.first()
    if (first is OrderStatus.Error) {
      return ActionState.Failed(first.message, reference, committed = true)
    }
    val outcome =
      when (first) {
        is OrderStatus.Resting -> first.oid.toString() to "Your order is on the book."
        is OrderStatus.Filled ->
          first.oid.toString() to "Filled ${first.totalSize} at ${first.averagePrice.toDoubleOrNull()?.let(::formatPrice) ?: first.averagePrice}."
        else -> reference to null
      }
    val problems = failures.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = "Not set: ")
    val detail = listOfNotNull(outcome.second, problems).joinToString(" ").ifEmpty { null }
    return ActionState.Committed(outcome.first, detail)
  }

  /** The answer to a `cancel` or `cancelByCloid`: every order gone, some, or none. */
  fun cancels(response: ExchangeResponse, reference: String): ActionState? {
    val statuses = (response as? ExchangeResponse.Cancels)?.statuses ?: return null
    if (statuses.isEmpty() || statuses.any { it is CancelStatus.Unrecognized }) return null
    val errors = statuses.filterIsInstance<CancelStatus.Error>()
    return when (errors.size) {
      0 -> ActionState.Committed(reference)
      statuses.size -> ActionState.Failed(errors.first().message, reference, committed = true)
      else ->
        ActionState.Committed(
          reference,
          "Canceled ${statuses.size - errors.size} of ${statuses.size}. ${errors.first().message}",
        )
    }
  }

  /** A TWAP that answers `running` is committed; its id comes with the TWAP list. */
  fun twapPlaced(response: ExchangeResponse, reference: String): ActionState? =
    when (val status = (response as? ExchangeResponse.TwapPlaced)?.status) {
      is TwapOrderStatus.Running -> ActionState.Committed(reference, "Your TWAP is running.")
      is TwapOrderStatus.Error -> ActionState.Failed(status.message, reference, committed = true)
      else -> null
    }

  fun twapCanceled(response: ExchangeResponse, reference: String): ActionState? =
    when (val status = (response as? ExchangeResponse.TwapCanceled)?.status) {
      CancelStatus.Success -> ActionState.Committed(reference)
      is CancelStatus.Error -> ActionState.Failed(status.message, reference, committed = true)
      else -> null
    }

  /** `updateLeverage` answers `default` once it's applied. */
  fun default(response: ExchangeResponse, reference: String): ActionState? =
    if (response == ExchangeResponse.Default) ActionState.Committed(reference) else null
}
