package xyz.mcxross.flare.strategies

object AutoAmounts {
  fun normalize(value: String): String = ExactAmount.parse(value).toString()

  fun add(left: String, right: String): String =
    (ExactAmount.parse(left) + ExactAmount.parse(right)).toString()

  fun subtract(left: String, right: String): String =
    (ExactAmount.parse(left) - ExactAmount.parse(right)).toString()

  fun multiply(left: String, right: String): String =
    (ExactAmount.parse(left) * ExactAmount.parse(right)).toString()

  fun compare(left: String, right: String): Int =
    ExactAmount.parse(left).compareTo(ExactAmount.parse(right))
}

internal class ExactAmount private constructor(val coefficient: Long, val scale: Int) :
  Comparable<ExactAmount> {
  val sign: Int
    get() = coefficient.compareTo(0)

  fun abs(): ExactAmount = if (coefficient < 0) -this else this

  operator fun unaryMinus(): ExactAmount = create(-coefficient, scale)

  operator fun minus(other: ExactAmount): ExactAmount = this + -other

  operator fun plus(other: ExactAmount): ExactAmount {
    val sharedScale = maxOf(scale, other.scale)
    val left = scaled(sharedScale)
    val right = other.scaled(sharedScale)
    require(if (right > 0) left <= Long.MAX_VALUE - right else left >= -Long.MAX_VALUE - right) {
      "Amount overflow"
    }
    return create(left + right, sharedScale)
  }

  operator fun times(other: ExactAmount): ExactAmount {
    var left = coefficient
    var right = other.coefficient
    var resultScale = scale + other.scale
    while (resultScale > 0 && left != 0L && right != 0L) {
      when {
        left % 2 == 0L && right % 5 == 0L -> {
          left /= 2
          right /= 5
          resultScale--
        }
        left % 5 == 0L && right % 2 == 0L -> {
          left /= 5
          right /= 2
          resultScale--
        }
        else -> break
      }
    }
    require(
      left == 0L || right == 0L || kotlin.math.abs(left) <= Long.MAX_VALUE / kotlin.math.abs(right)
    ) {
      "Amount overflow"
    }
    return create(left * right, resultScale)
  }

  /** Basis allocation truncates toward zero to at most 18 decimals; residual basis stays open. */
  fun divide(other: ExactAmount): ExactAmount {
    require(other.coefficient != 0L) { "Zero divisor" }
    val commonScale = maxOf(scale, other.scale)
    val numerator = kotlin.math.abs(scaled(commonScale))
    val denominator = kotlin.math.abs(other.scaled(commonScale))
    var result = numerator / denominator
    var remainder = numerator % denominator
    var outputScale = 0
    while (remainder != 0L && outputScale < 18) {
      if (remainder > Long.MAX_VALUE / 10) break
      val next = remainder * 10
      val digit = next / denominator
      if (result > (Long.MAX_VALUE - digit) / 10) break
      result = result * 10 + digit
      remainder = next % denominator
      outputScale++
    }
    require(result != 0L || numerator == 0L) { "Amount below supported precision" }
    return create(if (sign == other.sign) result else -result, outputScale)
  }

  private fun scaled(target: Int): Long {
    var result = coefficient
    repeat(target - scale) {
      require(kotlin.math.abs(result) <= Long.MAX_VALUE / 10) { "Amount overflow" }
      result *= 10
    }
    return result
  }

  override fun compareTo(other: ExactAmount): Int {
    if (sign != other.sign) return sign.compareTo(other.sign)
    val commonScale = maxOf(scale, other.scale)
    val left = kotlin.math.abs(coefficient).toString() + "0".repeat(commonScale - scale)
    val right =
      kotlin.math.abs(other.coefficient).toString() + "0".repeat(commonScale - other.scale)
    val comparison =
      if (left.length != right.length) left.length.compareTo(right.length)
      else left.compareTo(right)
    return if (sign < 0) -comparison else comparison
  }

  override fun equals(other: Any?): Boolean =
    other is ExactAmount && coefficient == other.coefficient && scale == other.scale

  override fun hashCode(): Int = 31 * coefficient.hashCode() + scale

  override fun toString(): String {
    val digits = kotlin.math.abs(coefficient).toString().padStart(scale + 1, '0')
    val number = if (scale == 0) digits else digits.dropLast(scale) + "." + digits.takeLast(scale)
    return if (coefficient < 0) "-$number" else number
  }

  companion object {
    val ZERO = create(0, 0)

    fun parse(value: String): ExactAmount {
      require(value.length <= 80 && value.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?"))) {
        "Invalid decimal amount"
      }
      val negative = value.startsWith('-')
      val parts = value.removePrefix("-").split('.')
      var scale = parts.getOrNull(1)?.length ?: 0
      require(scale <= 18) { "More than 18 decimal places" }
      var digits = (parts[0] + parts.getOrElse(1) { "" }).trimStart('0')
      while (scale > 0 && digits.endsWith('0')) {
        digits = digits.dropLast(1)
        scale--
      }
      val coefficient =
        if (digits.isEmpty()) 0L else digits.toLongOrNull() ?: error("Amount overflow")
      return create(if (negative) -coefficient else coefficient, scale)
    }

    private fun create(coefficient: Long, scale: Int): ExactAmount {
      require(coefficient != Long.MIN_VALUE) { "Amount overflow" }
      if (coefficient == 0L) return ExactAmount(0, 0)
      var normalized = coefficient
      var precision = scale
      while (precision > 0 && normalized % 10 == 0L) {
        normalized /= 10
        precision--
      }
      require(precision in 0..18) { "More than 18 decimal places" }
      return ExactAmount(normalized, precision)
    }
  }
}
