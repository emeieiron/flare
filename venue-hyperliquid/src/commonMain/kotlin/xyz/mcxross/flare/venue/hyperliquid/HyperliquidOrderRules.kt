package xyz.mcxross.flare.venue.hyperliquid

import xyz.mcxross.flare.hyperliquid.model.AssetKind
import xyz.mcxross.flare.hyperliquid.model.HlDecimal
import xyz.mcxross.flare.hyperliquid.model.HlRounding
import xyz.mcxross.flare.hyperliquid.model.HyperliquidAsset
import xyz.mcxross.flare.venue.model.MarketKind
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderRules
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TimeInForce

/**
 * A Hyperliquid market's order checks: a price has at most five significant figures and at most
 * (6 for perps, 8 for spot) − szDecimals decimals, a size at most szDecimals decimals, and an order
 * is worth at least $10. A market order is sent as an immediate-or-cancel limit order at the best
 * price moved by the slippage allowance.
 */
data class HyperliquidOrderRules(
  val coin: String,
  val symbol: String,
  val kind: MarketKind,
  val szDecimals: Int,
) : OrderRules {
  internal val asset =
    HyperliquidAsset(
      id = -1,
      coin = coin,
      kind = if (kind == MarketKind.SPOT) AssetKind.SPOT else AssetKind.PERP,
      displayName = symbol,
      szDecimals = szDecimals,
    )

  val maxPriceDecimals: Int
    get() = asset.maxPriceDecimals

  /** Hyperliquid limits an order's value, not its size. */
  override val minimumSize: String? = null

  override fun check(order: OrderRequest, orderBook: OrderBook?): OrderCheck {
    val errors = mutableListOf<String>()
    val size = size(order.size, errors)
    if (order.type == OrderType.TWAP) {
      val schedule = order.twap
      val minutes = schedule?.durationSeconds?.takeIf { it % 60 == 0L }?.div(60)
      if (minutes == null || minutes !in TWAP_MINUTES) errors += TWAP_DURATION
      if (size != null && !order.reduceOnly) {
        val price = midPrice(orderBook)
        if (price == null) {
          errors += "A reliable best bid and ask are required"
        } else {
          val value = runCatching { size * price }.getOrNull()
          if (value == null || value < TWAP_MIN_VALUE_USDC) {
            errors += "Minimum TWAP value is $$TWAP_MIN_VALUE_USDC"
          }
        }
      }
      return if (errors.isEmpty() && size != null) OrderCheck.Valid(order.copy(size = size.toString()))
      else OrderCheck.Invalid(errors)
    }
    val price =
      when (order.type) {
        OrderType.MARKET -> marketPrice(order, orderBook, errors)
        else -> {
          val text = order.price?.takeIf(String::isNotBlank)
          if (text == null) null.also { errors += "Enter a limit price" }
          else price("Price", text, errors)
        }
      }
    val takeProfitTrigger = order.takeProfitTrigger?.let { price("Take profit", it, errors) }
    val takeProfitLimit = order.takeProfitLimit?.let { price("Take profit", it, errors) }
    val stopLossTrigger = order.stopLossTrigger?.let { price("Stop loss", it, errors) }
    val stopLossLimit = order.stopLossLimit?.let { price("Stop loss", it, errors) }
    if (size != null && price != null && !order.reduceOnly) {
      val value = runCatching { size * price }.getOrNull()
      if (value == null || value < HyperliquidAsset.MIN_ORDER_VALUE_USDC) {
        errors += "Minimum order value is $${HyperliquidAsset.MIN_ORDER_VALUE_USDC}"
      }
    }
    if (errors.isNotEmpty() || size == null || price == null) return OrderCheck.Invalid(errors.distinct())
    return OrderCheck.Valid(
      order.copy(
        size = size.toString(),
        price = price.toString(),
        timeInForce =
          if (order.type == OrderType.MARKET) TimeInForce.IMMEDIATE_OR_CANCEL else order.timeInForce,
        takeProfitTrigger = takeProfitTrigger?.toString(),
        takeProfitLimit = takeProfitLimit?.toString(),
        stopLossTrigger = stopLossTrigger?.toString(),
        stopLossLimit = stopLossLimit?.toString(),
      )
    )
  }

  override fun checkExitPrices(takeProfit: String?, stopLoss: String?): String? {
    val errors = mutableListOf<String>()
    takeProfit?.let { price("Take profit", it, errors) }
    stopLoss?.let { price("Stop loss", it, errors) }
    return errors.firstOrNull()
  }

  private fun size(text: String, errors: MutableList<String>): HlDecimal? {
    val size = HlDecimal.parseOrNull(text)
    when {
      size == null -> errors += "Enter a valid size"
      size.signum <= 0 -> errors += "Enter a size"
      !asset.isValidSize(size) ->
        errors +=
          if (szDecimals == 0) "Size must be a whole number of $symbol"
          else "Size can have at most $szDecimals decimal${if (szDecimals == 1) "" else "s"}"
      else -> return size
    }
    return null
  }

  private fun price(label: String, text: String, errors: MutableList<String>): HlDecimal? {
    val price = HlDecimal.parseOrNull(text)
    when {
      price == null || price.signum <= 0 -> errors += "$label: enter a valid price"
      !asset.isValidPrice(price) ->
        errors +=
          if (maxPriceDecimals == 0) "$label can have at most 5 significant figures, or be a whole number"
          else "$label can have at most 5 significant figures and $maxPriceDecimals decimals"
      else -> return price
    }
    return null
  }

  private fun midPrice(orderBook: OrderBook?): HlDecimal? {
    if (orderBook == null || orderBook.marketId != coin) return null
    val bid = orderBook.bestBid?.let(HlDecimal::parseOrNull)?.takeIf { it.signum > 0 } ?: return null
    val ask = orderBook.bestAsk?.let(HlDecimal::parseOrNull)?.takeIf { it.signum > 0 } ?: return null
    return runCatching { (bid + ask) * HlDecimal.of(5, 1) }.getOrNull()
  }

  /** The best opposite price moved by the slippage allowance, never better than the best price. */
  private fun marketPrice(
    order: OrderRequest,
    orderBook: OrderBook?,
    errors: MutableList<String>,
  ): HlDecimal? {
    val buy = order.side == OrderSide.BUY
    val best = (if (buy) orderBook?.bestAsk else orderBook?.bestBid)?.let(HlDecimal::parseOrNull)
    if (best == null || best.signum <= 0 || orderBook?.marketId?.let { it != coin } == true) {
      errors += "A reliable best bid and ask are required"
      return null
    }
    val bps = order.slippageBps.coerceIn(0, MAX_SLIPPAGE_BPS)
    val factor = HlDecimal.of((if (buy) BPS + bps else BPS - bps).toLong(), 4)
    val bounded = asset.roundPrice(best * factor, if (buy) HlRounding.FLOOR else HlRounding.CEILING)
    val bestValid = asset.roundPrice(best, if (buy) HlRounding.CEILING else HlRounding.FLOOR)
    return if (buy) maxOf(bounded, bestValid) else minOf(bounded, bestValid)
  }

  internal companion object {
    private const val BPS = 10_000
    private const val MAX_SLIPPAGE_BPS = 5_000

    /** How long a TWAP may run, in minutes, as far as Flare's order entry goes (a day). */
    val TWAP_MINUTES = 5L..1_440L
    const val TWAP_DURATION = "A TWAP runs for 5 minutes to 24 hours, in whole minutes"

    /** Hyperliquid's smallest TWAP, by its whole value in USDC. */
    val TWAP_MIN_VALUE_USDC: HlDecimal = HlDecimal.of(100)
  }
}
