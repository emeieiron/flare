package xyz.mcxross.flare.data

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.StateFlow
import xyz.mcxross.flare.venue.model.Market

data class MarketQuote(
  val market: Market,
  val markPrice: Double,
  val changePercent24h: Double,
  val volume24h: Double,
  val openInterest: Double,
  val favorite: Boolean = false,
  val fundingRateBps: Double? = null,
)

data class MarketCatalog(
  val loading: Boolean = true,
  val quotes: List<MarketQuote> = emptyList(),
  val stale: Boolean = false,
  val error: String? = null,
  val updatedAtMs: Long? = null,
)

interface MarketsRepository {
  val catalog: StateFlow<MarketCatalog>

  suspend fun refresh()

  suspend fun connectLive()

  suspend fun toggleFavorite(marketAddress: String)
}

fun formatPrice(value: Double): String =
  when {
    !value.isFinite() || value <= 0.0 -> "—"
    value >= 1_000.0 -> "\$${groupDigits(fixed(value, 2))}"
    value >= 1.0 -> "\$${fixed(value, 3)}"
    else -> "\$${fixed(value, 5)}"
  }

fun formatPercent(value: Double): String = "${fixed(abs(value), 2)}%"

fun formatCompact(value: Double): String {
  val magnitude = abs(value)
  return when {
    magnitude >= 1_000_000_000 -> "\$${fixed(value / 1_000_000_000, 2)}B"
    magnitude >= 1_000_000 -> "\$${fixed(value / 1_000_000, 2)}M"
    magnitude >= 1_000 -> "\$${fixed(value / 1_000, 2)}K"
    else -> "\$${fixed(value, 2)}"
  }
}

private fun fixed(value: Double, decimals: Int): String {
  val factor = pow10(decimals)
  if (abs(value) > Long.MAX_VALUE.toDouble() / factor) return value.toString()
  val scaled = (value * factor).roundToLong()
  val whole = scaled / factor
  val fraction = abs(scaled % factor).toString().padStart(decimals, '0')
  val sign = if (scaled < 0 && whole == 0L) "-" else ""
  return sign + if (decimals == 0) whole.toString() else "$whole.$fraction"
}

private fun pow10(exponent: Int): Long {
  var result = 1L
  repeat(exponent) { result *= 10L }
  return result
}

/** Account balances include zero and losses, unlike market quotes that require a positive price. */
fun formatBalance(value: Double): String =
  if (!value.isFinite()) "—"
  else if (value != 0.0 && abs(value) < 0.005) {
    (if (value < 0) "−" else "") + "<\$0.01"
  } else (if (value < 0) "−" else "") + "\$" + groupDigits(fixed(abs(value), 2))

/** Signed money. A value that rounds away is flat, never a signed "<$0.01". */
fun formatSignedBalance(value: Double): String =
  when {
    !value.isFinite() -> "—"
    abs(value) < 0.005 -> "\$0.00"
    value > 0.0 -> "+" + formatBalance(value)
    else -> formatBalance(value)
  }

/** Display only. Transaction inputs continue to use exact decimal strings and chain units. */
fun formatQuantity(value: Double, decimals: Int = 8): String {
  if (!value.isFinite()) return "—"
  val precision = decimals.coerceIn(0, 12)
  val formatted = fixed(value, precision)
  return if (precision == 0) formatted else formatted.trimEnd('0').trimEnd('.')
}

/** A whole count with its thousands grouped, like a points total. Counts are never negative. */
fun formatCount(value: Double): String = if (!value.isFinite()) "—" else groupDigits(fixed(abs(value), 0))

private fun groupDigits(number: String): String {
  val parts = number.split('.')
  val whole = parts.first().reversed().chunked(3).joinToString(",").reversed()
  return whole + if (parts.size > 1) "." + parts[1] else ""
}
