package xyz.mcxross.flare.hyperliquid.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

enum class HlRounding {
  /** Toward zero. */
  DOWN,
  /** Away from zero. */
  UP,
  /** Toward negative infinity. */
  FLOOR,
  /** Toward positive infinity. */
  CEILING,
  HALF_UP,
  HALF_EVEN,
}

/**
 * An exact decimal, `unscaled × 10^-scale`, always normalized: no trailing fractional zeros and a
 * non-negative scale, so equal values are equal objects and [toString] is Hyperliquid's wire form.
 *
 * Hyperliquid sends and accepts every price and size as a decimal string. This keeps them exact
 * without `Double` and without a big-number dependency. Values are limited to 18 significant
 * digits and 18 decimal places, far beyond any Hyperliquid price, size or notional; arithmetic that
 * would exceed that throws [ArithmeticException] instead of losing precision.
 */
class HlDecimal private constructor(val unscaled: Long, val scale: Int) : Comparable<HlDecimal> {

  val signum: Int
    get() = unscaled.compareTo(0L)

  val isZero: Boolean
    get() = unscaled == 0L

  val isInteger: Boolean
    get() = scale == 0

  /** Digits after the decimal point, ignoring trailing zeros. */
  val decimalPlaces: Int
    get() = scale

  /**
   * Significant figures: every digit from the first non-zero one, except an integer's trailing
   * zeros, which are taken as not significant (`123000` has 3).
   */
  val significantFigures: Int
    get() {
      if (unscaled == 0L) return 0
      var magnitude = absoluteUnscaled()
      if (scale == 0) {
        while (magnitude % 10uL == 0uL) magnitude /= 10uL
      }
      return digitCount(magnitude)
    }

  operator fun unaryMinus(): HlDecimal = of(negateExact(unscaled), scale)

  fun abs(): HlDecimal = if (unscaled < 0L) -this else this

  operator fun plus(other: HlDecimal): HlDecimal {
    val targetScale = maxOf(scale, other.scale)
    return of(addExact(rescaled(targetScale), other.rescaled(targetScale)), targetScale)
  }

  operator fun minus(other: HlDecimal): HlDecimal = this + (-other)

  operator fun times(other: HlDecimal): HlDecimal =
    of(multiplyExact(unscaled, other.unscaled), scale + other.scale)

  /**
   * Rounds to at most [decimals] places. A negative [decimals] rounds to tens, hundreds and so on,
   * which significant-figure rounding of large integers needs.
   */
  fun roundToDecimals(decimals: Int, rounding: HlRounding): HlDecimal {
    if (decimals >= scale) return this
    val drop = scale - decimals
    val divisor = if (drop <= MAX_SCALE) POWERS_OF_TEN[drop] else null
    val quotient = if (divisor == null) 0L else unscaled / divisor
    val remainder = if (divisor == null) unscaled else unscaled % divisor
    val roundedQuotient =
      if (remainder == 0L) {
        quotient
      } else {
        val sign = if (unscaled < 0L) -1L else 1L
        val awayFromZero =
          when (rounding) {
            HlRounding.DOWN -> false
            HlRounding.UP -> true
            HlRounding.FLOOR -> sign < 0L
            HlRounding.CEILING -> sign > 0L
            HlRounding.HALF_UP,
            HlRounding.HALF_EVEN -> {
              // A divisor past 10^18 dwarfs any remainder, so it is always below half.
              val half =
                if (divisor == null) -1
                else (absolute(remainder) * 2uL).compareTo(divisor.toULong())
              when {
                half > 0 -> true
                half < 0 -> false
                rounding == HlRounding.HALF_UP -> true
                else -> quotient % 2L != 0L
              }
            }
          }
        if (awayFromZero) addExact(quotient, sign) else quotient
      }
    return if (decimals >= 0) {
      of(roundedQuotient, decimals)
    } else {
      of(multiplyExact(roundedQuotient, powerOfTen(-decimals)), 0)
    }
  }

  /** Rounds to at most [figures] significant figures. */
  fun roundToSignificantFigures(figures: Int, rounding: HlRounding): HlDecimal {
    require(figures > 0) { "At least one significant figure is required" }
    if (unscaled == 0L) return this
    val digits = digitCount(absoluteUnscaled())
    return roundToDecimals(figures - digits + scale, rounding)
  }

  override fun compareTo(other: HlDecimal): Int {
    // Whole parts first, then fractions at a shared scale: a fraction is below 10^scale, so
    // widening it to at most 18 places cannot overflow the way rescaling the whole value can.
    val wholeThis = unscaled / POWERS_OF_TEN[scale]
    val wholeOther = other.unscaled / POWERS_OF_TEN[other.scale]
    if (wholeThis != wholeOther) return wholeThis.compareTo(wholeOther)
    val targetScale = maxOf(scale, other.scale)
    val fractionThis = (unscaled % POWERS_OF_TEN[scale]) * POWERS_OF_TEN[targetScale - scale]
    val fractionOther =
      (other.unscaled % POWERS_OF_TEN[other.scale]) * POWERS_OF_TEN[targetScale - other.scale]
    return fractionThis.compareTo(fractionOther)
  }

  override fun equals(other: Any?): Boolean =
    other is HlDecimal && unscaled == other.unscaled && scale == other.scale

  override fun hashCode(): Int = 31 * unscaled.hashCode() + scale

  /** Plain notation without trailing zeros, e.g. `0.0012`, `-83108`, `1.5`. */
  override fun toString(): String {
    if (scale == 0) return unscaled.toString()
    val digits = absoluteUnscaled().toString().padStart(scale + 1, '0')
    val split = digits.length - scale
    val sign = if (unscaled < 0L) "-" else ""
    return sign + digits.substring(0, split) + "." + digits.substring(split)
  }

  /**
   * The form Hyperliquid's signed actions use: plain notation, trailing zeros stripped and at most
   * eight decimal places. Anything finer cannot be sent, so it is rejected rather than rounded.
   */
  fun toWire(): String {
    if (scale > WIRE_MAX_DECIMALS) {
      throw IllegalArgumentException(
        "$this has more than $WIRE_MAX_DECIMALS decimal places and cannot be sent"
      )
    }
    return toString()
  }

  private fun absoluteUnscaled(): ULong = absolute(unscaled)

  private fun rescaled(targetScale: Int): Long =
    if (targetScale == scale) unscaled
    else multiplyExact(unscaled, powerOfTen(targetScale - scale))

  companion object {
    /** The most decimal places a value may carry. */
    const val MAX_SCALE: Int = 18

    /** The most decimal places Hyperliquid's wire format carries. */
    const val WIRE_MAX_DECIMALS: Int = 8

    val ZERO: HlDecimal = HlDecimal(0L, 0)
    val ONE: HlDecimal = HlDecimal(1L, 0)

    private val POWERS_OF_TEN: LongArray =
      LongArray(MAX_SCALE + 1).also {
        it[0] = 1L
        for (index in 1..MAX_SCALE) it[index] = it[index - 1] * 10L
      }

    /** `unscaled × 10^-scale`, normalized. */
    fun of(unscaled: Long, scale: Int): HlDecimal {
      if (unscaled == 0L) return ZERO
      var value = unscaled
      var normalizedScale = scale
      while (normalizedScale > 0 && value % 10L == 0L) {
        value /= 10L
        normalizedScale -= 1
      }
      if (normalizedScale < 0) {
        value = multiplyExact(value, powerOfTen(-normalizedScale))
        normalizedScale = 0
      }
      if (normalizedScale > MAX_SCALE) {
        throw ArithmeticException("Decimal exceeds $MAX_SCALE places")
      }
      return HlDecimal(value, normalizedScale)
    }

    fun of(value: Long): HlDecimal = of(value, 0)

    fun of(value: Int): HlDecimal = of(value.toLong(), 0)

    /**
     * Parses plain decimal notation: an optional `-`, digits, and an optional fraction. Exponents,
     * `+`, grouping separators and anything past 18 significant digits are rejected.
     */
    fun parse(text: String): HlDecimal =
      parseOrNull(text) ?: throw IllegalArgumentException("Invalid decimal: \"$text\"")

    fun parseOrNull(text: String): HlDecimal? {
      val trimmed = text.trim()
      val negative = trimmed.startsWith('-')
      val body = if (negative) trimmed.substring(1) else trimmed
      if (body.isEmpty() || body.count { it == '.' } > 1) return null
      if (!body.all { it.isAsciiDigit() || it == '.' }) return null
      val whole = body.substringBefore('.')
      val fraction = body.substringAfter('.', "").trimEnd('0')
      if (whole.isEmpty() && body.substringAfter('.', "").isEmpty()) return null
      if (fraction.length > MAX_SCALE) return null
      val digits = (whole + fraction).trimStart('0').ifEmpty { "0" }
      if (digits.length > MAX_SCALE) return null
      val magnitude = digits.toLongOrNull() ?: return null
      return of(if (negative) -magnitude else magnitude, fraction.length)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun powerOfTen(exponent: Int): Long {
      if (exponent > MAX_SCALE) throw ArithmeticException("Decimal exceeds 18 digits")
      return POWERS_OF_TEN[exponent]
    }
  }
}

fun String.toHlDecimal(): HlDecimal = HlDecimal.parse(this)

fun String.toHlDecimalOrNull(): HlDecimal? = HlDecimal.parseOrNull(this)

private fun absolute(value: Long): ULong =
  if (value < 0L) (value.inv().toULong() + 1uL) else value.toULong()

private fun digitCount(value: ULong): Int {
  var remaining = value
  var count = 1
  while (remaining >= 10uL) {
    remaining /= 10uL
    count += 1
  }
  return count
}

private fun addExact(left: Long, right: Long): Long {
  val result = left + right
  if (((left xor result) and (right xor result)) < 0L) {
    throw ArithmeticException("Decimal exceeds 18 digits")
  }
  return result
}

private fun negateExact(value: Long): Long {
  if (value == Long.MIN_VALUE) throw ArithmeticException("Decimal exceeds 18 digits")
  return -value
}

private fun multiplyExact(left: Long, right: Long): Long {
  if (left == 0L || right == 0L) return 0L
  val result = left * right
  if (
    result / right != left ||
      (left == -1L && right == Long.MIN_VALUE) ||
      (right == -1L && left == Long.MIN_VALUE)
  ) {
    throw ArithmeticException("Decimal exceeds 18 digits")
  }
  return result
}

/**
 * Decodes a decimal sent as either a JSON string or a JSON number, keeping its exact text. Vault
 * details send some figures (`apr`, `maxWithdrawable`) as numbers and the rest as strings.
 */
object DecimalTextSerializer : KSerializer<String> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("HyperliquidDecimalText", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): String =
    (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content ?: decoder.decodeString()

  override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
