package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.decibel.model.AssetType
import xyz.mcxross.flare.decibel.model.DecimalInput
import xyz.mcxross.flare.decibel.model.MarketPrecision
import xyz.mcxross.flare.decibel.model.OrderDraft
import xyz.mcxross.flare.decibel.model.OrderValidationError
import xyz.mcxross.flare.decibel.model.SlippageBps
import xyz.mcxross.flare.decibel.model.ValidatedOrder
import xyz.mcxross.flare.decibel.model.ValidatedTwapOrder
import xyz.mcxross.flare.decibel.model.toChainUnits
import xyz.mcxross.flare.decibel.model.toDecimalString
import xyz.mcxross.flare.venue.model.OrderBook
import xyz.mcxross.flare.venue.model.OrderCheck
import xyz.mcxross.flare.venue.model.OrderRequest
import xyz.mcxross.flare.venue.model.OrderRules
import xyz.mcxross.flare.venue.model.OrderSide
import xyz.mcxross.flare.venue.model.OrderType
import xyz.mcxross.flare.venue.model.TimeInForce
import xyz.mcxross.flare.decibel.model.Market as DecibelMarket
import xyz.mcxross.flare.decibel.model.OrderBook as DecibelOrderBook
import xyz.mcxross.flare.decibel.model.OrderSide as DecibelOrderSide
import xyz.mcxross.flare.decibel.model.OrderType as DecibelOrderType
import xyz.mcxross.flare.decibel.model.TimeInForce as DecibelTimeInForce
import xyz.mcxross.flare.decibel.model.validate as decibelValidate
import xyz.mcxross.flare.decibel.model.validateTwap as decibelValidateTwap

/**
 * A Decibel market's order checks: Decibel's exact chain-unit validation, with its errors written in
 * the market's own units. A valid order comes back with its prices and sizes as Decibel will
 * receive them, so placing it validates to the same chain units again.
 */
data class DecibelOrderRules(
  val marketId: String,
  val name: String,
  val kind: AssetType,
  val precision: MarketPrecision,
) : OrderRules {
  private val symbol: String
    get() = name.substringBefore('/').substringBefore('-').uppercase()

  override val minimumSize: String
    get() = precision.minimumSize.toDecimalString(precision.sizeDecimals)

  override fun check(order: OrderRequest, orderBook: OrderBook?): OrderCheck =
    if (order.type == OrderType.TWAP && kind != AssetType.SPOT) {
      val schedule = checkNotNull(order.twap) { "Choose a TWAP schedule" }
      val result =
        order
          .toDraft()
          .decibelValidateTwap(
            toDecibelMarket(),
            schedule.frequencySeconds.toULong(),
            schedule.durationSeconds.toULong(),
          )
      result.value?.takeIf { result.isValid }?.let { OrderCheck.Valid(order.resolved(it)) }
        ?: OrderCheck.Invalid(result.errors.map { it.message() })
    } else {
      val result = order.toDraft().decibelValidate(toDecibelMarket(), orderBook?.toDecibelOrderBook())
      result.value?.takeIf { result.isValid }?.let { OrderCheck.Valid(order.resolved(it)) }
        ?: OrderCheck.Invalid(result.errors.map { it.message() })
    }

  override fun checkExitPrices(takeProfit: String?, stopLoss: String?): String? =
    runCatching {
        val units =
          listOfNotNull(takeProfit, stopLoss).map {
            DecimalInput(it).toChainUnits("TP/SL price", precision.priceDecimals).getOrThrow()
          }
        units.forEach { value ->
          require(precision.tickSize == 0uL || value % precision.tickSize == 0uL) {
            "TP/SL prices must align to tick size ${precision.tickSize}"
          }
        }
      }
      .exceptionOrNull()
      ?.let { it.message ?: "Check the take-profit and stop-loss prices" }

  /** The order a checked [order] stands for, in chain units. */
  internal fun validated(order: OrderRequest): ValidatedOrder {
    val price = checkNotNull(order.price) { "The order has no price" }
    val draft =
      order.toDraft().copy(
        type = DecibelOrderType.LIMIT,
        limitPrice = DecimalInput(price),
        timeInForce = order.timeInForce.toDecibel(),
      )
    val result = draft.decibelValidate(toDecibelMarket())
    return result.value?.takeIf { result.isValid }
      ?: error(result.errors.joinToString("\n") { it.message() })
  }

  internal fun validatedTwap(order: OrderRequest): ValidatedTwapOrder {
    val schedule = checkNotNull(order.twap) { "Choose a TWAP schedule" }
    val result =
      order
        .toDraft()
        .decibelValidateTwap(
          toDecibelMarket(),
          schedule.frequencySeconds.toULong(),
          schedule.durationSeconds.toULong(),
        )
    return result.value?.takeIf { result.isValid }
      ?: error(result.errors.joinToString("\n") { it.message() })
  }

  internal fun priceUnits(field: String, price: String): ULong =
    DecimalInput(price).toChainUnits(field, precision.priceDecimals).getOrThrow()

  internal fun sizeUnits(field: String, size: String): ULong =
    DecimalInput(size).toChainUnits(field, precision.sizeDecimals).getOrThrow()

  private fun OrderRequest.toDraft(): OrderDraft =
    OrderDraft(
      marketAddress = marketId,
      side = side.toDecibel(),
      type = type.toDecibel(),
      size = DecimalInput(size),
      limitPrice = price?.takeIf(String::isNotBlank)?.let(::DecimalInput),
      timeInForce = timeInForce.toDecibel(),
      reduceOnly = reduceOnly,
      slippage = SlippageBps(slippageBps.toUInt()),
      clientOrderId = clientOrderId,
      takeProfitTriggerPrice = takeProfitTrigger?.let(::DecimalInput),
      takeProfitLimitPrice = takeProfitLimit?.let(::DecimalInput),
      stopLossTriggerPrice = stopLossTrigger?.let(::DecimalInput),
      stopLossLimitPrice = stopLossLimit?.let(::DecimalInput),
      builderAddress = builderAddress,
      builderFeeBps = builderFeeBps?.toUInt(),
    )

  private fun OrderRequest.resolved(order: ValidatedOrder): OrderRequest =
    copy(
      size = order.size.toDecimalString(precision.sizeDecimals),
      price = order.price.toDecimalString(precision.priceDecimals),
      timeInForce = order.timeInForce.toNeutral(),
      clientOrderId = order.clientOrderId,
      takeProfitTrigger = order.takeProfitTriggerPrice?.price(),
      takeProfitLimit = order.takeProfitLimitPrice?.price(),
      stopLossTrigger = order.stopLossTriggerPrice?.price(),
      stopLossLimit = order.stopLossLimitPrice?.price(),
      builderAddress = order.builderAddress,
      builderFeeBps = order.builderFeeUnits?.let { (it / 100uL).toInt() },
    )

  private fun OrderRequest.resolved(order: ValidatedTwapOrder): OrderRequest =
    copy(
      size = order.size.toDecimalString(precision.sizeDecimals),
      clientOrderId = order.clientOrderId,
      builderAddress = order.builderAddress,
      builderFeeBps = order.builderFeeUnits?.let { (it / 100uL).toInt() },
    )

  private fun ULong.price(): String = toDecimalString(precision.priceDecimals)

  /** Validation never reads the fields left at zero or blank here. */
  private fun toDecibelMarket(): DecibelMarket =
    DecibelMarket(
      assetType = kind,
      address = marketId,
      name = name,
      sizeDecimals = precision.sizeDecimals,
      maxLeverage = 0,
      tickSize = precision.tickSize,
      minSize = precision.minimumSize,
      lotSize = precision.lotSize,
      maxOpenInterest = 0.0,
      priceDecimals = precision.priceDecimals,
      mode = "",
      unrealizedPnlHaircutBps = 0,
      category = "",
      minPrice = precision.minimumPrice,
      maxPrice = precision.maximumPrice,
      isIsolatedOnly = false,
    )

  /**
   * Validation speaks in the market's own units: sizes in the asset, prices in dollars, never the
   * raw on-chain integers the checks compare.
   */
  internal fun OrderValidationError.message(): String {
    val asset = symbol.substringBefore('/')
    fun label(field: String) =
      when (field) {
        "size" -> "Size"
        "price" -> "Price"
        "take-profit trigger", "take-profit limit" -> "Take profit"
        "stop-loss trigger", "stop-loss limit" -> "Stop loss"
        else -> field.replaceFirstChar(Char::uppercase)
      }
    fun amount(field: String, units: ULong): String =
      if (field == "size") "${units.plainDecimal(precision.sizeDecimals)} $asset"
      else "$${units.plainDecimal(precision.priceDecimals)}"
    return when (this) {
      is OrderValidationError.InvalidDecimal -> "${label(field)}: $reason"
      is OrderValidationError.InvalidMarketAddress -> "${label(field)}: $reason"
      is OrderValidationError.InvalidBuilderAddress -> "${label(field)}: $reason"
      is OrderValidationError.InvalidBuilderFee -> "${label(field)}: $reason"
      is OrderValidationError.MarketMismatch -> "${label(field)} doesn’t match the selected market"
      is OrderValidationError.Overflow -> "${label(field)} is too large"
      is OrderValidationError.TooPrecise ->
        "${label(field)} can have at most $allowedDecimals decimal${if (allowedDecimals == 1) "" else "s"}"
      is OrderValidationError.NotAligned ->
        "${label(field)} must be in steps of ${amount(field, increment)}"
      is OrderValidationError.BelowMinimum ->
        if (field == "size") "Minimum size is ${amount(field, minimum)}"
        else "${label(field)} must be at least ${amount(field, minimum)}"
      is OrderValidationError.AboveMaximum ->
        "${label(field)} can be at most ${amount(field, maximum)}"
      OrderValidationError.MissingLimitPrice -> "Enter a limit price"
      OrderValidationError.MissingMarketPrice -> "A reliable best bid and ask are required"
    }
  }
}

/** On-chain units as a readable decimal: 1000000000 at 8 decimals reads "10". */
private fun ULong.plainDecimal(decimals: Int): String =
  toDecimalString(decimals).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }

internal fun DecibelMarket.orderRules(): DecibelOrderRules =
  DecibelOrderRules(marketId = address, name = name, kind = assetType, precision = precision)

private fun OrderBook.toDecibelOrderBook(): DecibelOrderBook =
  DecibelOrderBook(
    market = marketId,
    timestamp = "",
    bids = bids.map { listOf(it.price, it.size) },
    asks = asks.map { listOf(it.price, it.size) },
  )

internal fun OrderSide.toDecibel(): DecibelOrderSide =
  when (this) {
    OrderSide.BUY -> DecibelOrderSide.BUY
    OrderSide.SELL -> DecibelOrderSide.SELL
  }

private fun OrderType.toDecibel(): DecibelOrderType =
  when (this) {
    OrderType.LIMIT -> DecibelOrderType.LIMIT
    OrderType.MARKET -> DecibelOrderType.MARKET
    OrderType.TWAP -> DecibelOrderType.TWAP
  }

private fun TimeInForce.toDecibel(): DecibelTimeInForce =
  when (this) {
    TimeInForce.GOOD_TILL_CANCELLED -> DecibelTimeInForce.GOOD_TILL_CANCELLED
    TimeInForce.POST_ONLY -> DecibelTimeInForce.POST_ONLY
    TimeInForce.IMMEDIATE_OR_CANCEL -> DecibelTimeInForce.IMMEDIATE_OR_CANCEL
  }

private fun DecibelTimeInForce.toNeutral(): TimeInForce =
  when (this) {
    DecibelTimeInForce.GOOD_TILL_CANCELLED -> TimeInForce.GOOD_TILL_CANCELLED
    DecibelTimeInForce.POST_ONLY -> TimeInForce.POST_ONLY
    DecibelTimeInForce.IMMEDIATE_OR_CANCEL -> TimeInForce.IMMEDIATE_OR_CANCEL
  }
