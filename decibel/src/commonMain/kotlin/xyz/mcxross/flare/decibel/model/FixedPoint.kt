package xyz.mcxross.flare.decibel.model

import xyz.mcxross.kaptos.model.AccountAddress

private data class ParsedUnits(val value: ULong?, val error: OrderValidationError?)

fun DecimalInput.toChainUnits(field: String, decimals: Int): Result<ULong> = runCatching {
  require(decimals in 0..18) { "$field uses an unsupported precision" }
  val normalized = value.trim()
  require(normalized.isNotEmpty()) { "$field is required" }
  require(normalized.none { it == 'e' || it == 'E' }) { "$field must not use exponent notation" }
  require(normalized.count { it == '.' } <= 1) { "$field is not a decimal number" }
  require(normalized.all { it.isDigit() || it == '.' }) { "$field must be positive" }

  val parts = normalized.split('.', limit = 2)
  val whole = parts[0].ifEmpty { "0" }
  val fraction = parts.getOrElse(1) { "" }
  require(fraction.length <= decimals) { "$field supports at most $decimals decimal places" }

  val digits =
    (whole.trimStart('0').ifEmpty { "0" } + fraction.padEnd(decimals, '0')).trimStart('0').ifEmpty {
      "0"
    }
  digits.toULongOrNull() ?: error("$field exceeds u64")
}

private fun parse(
  input: DecimalInput?,
  field: String,
  decimals: Int,
): ParsedUnits {
  if (input == null) return ParsedUnits(null, null)
  val result = input.toChainUnits(field, decimals)
  return result.fold(
    onSuccess = { ParsedUnits(it, null) },
    onFailure = {
      val error =
        if (it.message?.contains("at most") == true) {
          OrderValidationError.TooPrecise(field, decimals)
        } else {
          OrderValidationError.InvalidDecimal(field, it.message ?: "Invalid decimal")
        }
      ParsedUnits(null, error)
    },
  )
}

private fun addBpsCeil(value: ULong, bps: UInt): ULong {
  val whole = value / 10_000u
  val remainder = value % 10_000u
  val multiplier = bps.toULong()
  require(whole == 0uL || multiplier <= ULong.MAX_VALUE / whole) { "Slippage exceeds u64" }
  val wholeDelta = whole * multiplier
  val remainderDelta = ((remainder * bps.toULong()) + 9_999u) / 10_000u
  require(wholeDelta <= ULong.MAX_VALUE - value) { "Slippage exceeds u64" }
  require(remainderDelta <= ULong.MAX_VALUE - value - wholeDelta) { "Slippage exceeds u64" }
  return value + wholeDelta + remainderDelta
}

private fun subtractBpsFloor(value: ULong, bps: UInt): ULong {
  val whole = value / 10_000u
  val remainder = value % 10_000u
  val delta = (whole * bps.toULong()) + ((remainder * bps.toULong()) / 10_000u)
  return value - delta
}

private fun roundUp(value: ULong, increment: ULong): ULong {
  if (increment == 0uL || value % increment == 0uL) return value
  val delta = increment - value % increment
  require(delta <= ULong.MAX_VALUE - value) { "Tick rounding exceeds u64" }
  return value + delta
}

private fun roundDown(value: ULong, increment: ULong): ULong =
  if (increment == 0uL) value else value - value % increment

fun OrderDraft.validate(
  market: Market,
  orderBook: OrderBook? = null,
): OrderValidationResult {
  val precision = market.precision
  val errors = mutableListOf<OrderValidationError>()
  val metadataAddress = parseAddress(market.address, "market metadata", errors)
  val draftAddress = parseAddress(marketAddress, "order market", errors)
  if (metadataAddress != null && draftAddress != null && metadataAddress != draftAddress) {
    errors += OrderValidationError.MarketMismatch("order market", market.address, marketAddress)
  }
  val parsedSize = parse(size, "size", precision.sizeDecimals)
  parsedSize.error?.let(errors::add)

  val rawPrice =
    when (type) {
      OrderType.LIMIT -> {
        if (limitPrice == null) errors += OrderValidationError.MissingLimitPrice
        parse(limitPrice, "price", precision.priceDecimals)
      }
      OrderType.MARKET -> {
        val bookAddress = orderBook?.let { parseAddress(it.market, "order book market", errors) }
        val reliableBook = orderBook?.takeIf {
          metadataAddress != null && bookAddress != null && metadataAddress == bookAddress
        }
        if (
          orderBook != null &&
            metadataAddress != null &&
            bookAddress != null &&
            metadataAddress != bookAddress
        ) {
          errors +=
            OrderValidationError.MarketMismatch(
              "order book market",
              market.address,
              orderBook.market,
            )
        }
        val reference = if (side == OrderSide.BUY) reliableBook?.bestAsk else reliableBook?.bestBid
        if (reference == null && orderBook == null) {
          errors += OrderValidationError.MissingMarketPrice
          ParsedUnits(null, null)
        } else if (reference == null) {
          ParsedUnits(null, null)
        } else {
          parse(DecimalInput(reference), "market price", precision.priceDecimals)
        }
      }
    }
  rawPrice.error?.let(errors::add)

  val sizeUnits = parsedSize.value
  if (sizeUnits != null) {
    if (sizeUnits < precision.minimumSize) {
      errors += OrderValidationError.BelowMinimum("size", precision.minimumSize)
    }
    if (precision.lotSize > 0u && sizeUnits % precision.lotSize != 0uL) {
      errors += OrderValidationError.NotAligned("size", precision.lotSize)
    }
  }

  val priceUnits =
    rawPrice.value?.let { value ->
      runCatching {
        if (type == OrderType.MARKET) {
          if (side == OrderSide.BUY) {
            roundUp(addBpsCeil(value, slippage.value), precision.tickSize)
          } else {
            roundDown(subtractBpsFloor(value, slippage.value), precision.tickSize)
          }
        } else {
          value
        }
      }
        .getOrElse {
          errors += OrderValidationError.Overflow("price")
          null
        }
    }
  if (priceUnits != null) {
    if (priceUnits < precision.minimumPrice) {
      errors += OrderValidationError.BelowMinimum("price", precision.minimumPrice)
    }
    if (priceUnits > precision.maximumPrice) {
      errors += OrderValidationError.AboveMaximum("price", precision.maximumPrice)
    }
    if (precision.tickSize > 0u && priceUnits % precision.tickSize != 0uL) {
      errors += OrderValidationError.NotAligned("price", precision.tickSize)
    }
  }

  val optionalPrices =
    listOf(
        "stop price" to stopPrice,
        "take-profit trigger" to takeProfitTriggerPrice,
        "take-profit limit" to takeProfitLimitPrice,
        "stop-loss trigger" to stopLossTriggerPrice,
        "stop-loss limit" to stopLossLimitPrice,
      )
      .associate { (field, input) ->
        val parsed = parse(input, field, precision.priceDecimals)
        parsed.error?.let(errors::add)
        val units = parsed.value
        if (units != null) {
          if (units < precision.minimumPrice) {
            errors += OrderValidationError.BelowMinimum(field, precision.minimumPrice)
          }
          if (units > precision.maximumPrice) {
            errors += OrderValidationError.AboveMaximum(field, precision.maximumPrice)
          }
          if (precision.tickSize > 0u && units % precision.tickSize != 0uL) {
            errors += OrderValidationError.NotAligned(field, precision.tickSize)
          }
        }
        field to units
      }

  if (errors.isNotEmpty() || priceUnits == null || sizeUnits == null) {
    return OrderValidationResult(errors = errors)
  }
  return OrderValidationResult(
    value =
      ValidatedOrder(
        marketAddress = marketAddress,
        side = side,
        price = priceUnits,
        size = sizeUnits,
        timeInForce =
          if (type == OrderType.MARKET) TimeInForce.IMMEDIATE_OR_CANCEL else timeInForce,
        reduceOnly = reduceOnly,
        clientOrderId = clientOrderId?.takeIf(String::isNotBlank),
        stopPrice = optionalPrices["stop price"],
        takeProfitTriggerPrice = optionalPrices["take-profit trigger"],
        takeProfitLimitPrice = optionalPrices["take-profit limit"],
        stopLossTriggerPrice = optionalPrices["stop-loss trigger"],
        stopLossLimitPrice = optionalPrices["stop-loss limit"],
      )
  )
}

private fun parseAddress(
  value: String,
  field: String,
  errors: MutableList<OrderValidationError>,
): AccountAddress? = runCatching {
  AccountAddress.fromString(value)
}
  .getOrElse {
    errors +=
      OrderValidationError.InvalidMarketAddress(
        field = field,
        reason = it.message ?: "Invalid Aptos address",
      )
    null
  }

fun ULong.toDecimalString(decimals: Int): String {
  require(decimals >= 0)
  if (decimals == 0) return toString()
  val digits = toString().padStart(decimals + 1, '0')
  val whole = digits.dropLast(decimals)
  val fraction = digits.takeLast(decimals).trimEnd('0')
  return if (fraction.isEmpty()) whole else "$whole.$fraction"
}
