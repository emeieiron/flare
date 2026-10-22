package xyz.mcxross.flare.feature.portfolio

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import xyz.mcxross.flare.data.formatBalance
import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.venue.model.ManagerFee
import xyz.mcxross.flare.venue.model.VaultFactValue

/** A manager's own share of the vault. A real but tiny stake must not read as none. */
internal fun managerStakeText(percent: Double): String =
  if (percent > 0.0 && percent < 0.01) "<0.01% of the vault"
  else "${formatQuantity(percent, 2)}% of the vault"

/** The manager's cut as a rate, naming the interval where the venue states one. */
internal fun managerFeeRate(fee: ManagerFee): String {
  val percent = "${formatQuantity(fee.percent, 2)}%"
  return when (fee) {
    is ManagerFee.Interval -> fee.days?.let { "$percent every $it days" } ?: percent
    is ManagerFee.HighWatermark -> percent
  }
}

/**
 * What the fee mechanic means for someone about to deposit, or null when there is no fee to
 * explain. The `when` is exhaustive on purpose: a venue that charges another way cannot be added
 * without deciding what to tell people here.
 */
internal fun managerFeeNote(fee: ManagerFee): String? {
  if (fee.percent <= 0.0) return null
  val percent = "${formatQuantity(fee.percent, 2)}%"
  return when (fee) {
    is ManagerFee.Interval ->
      "The manager takes $percent of each interval's gain, with no high watermark. Depositing " +
        "part-way through an interval pays that fee on gains made before you arrived, and the " +
        "manager earns it on a recovery from an earlier loss."
    is ManagerFee.HighWatermark ->
      "The manager takes $percent of profits above your stake's previous peak."
  }
}

/** A percentage with its sign kept, as a return reads. */
internal fun signedPercent(percent: Double): String =
  (if (percent > 0.0) "+" else "") + "${formatQuantity(percent, 2)}%"

/** Green for a gain, red for a loss, and the default where it is neither. */
internal fun amountColor(amount: Double): Color =
  when {
    amount > 0.0 -> FlareColors.Positive
    amount < 0.0 -> FlareColors.Negative
    else -> Color.Unspecified
  }

/**
 * A venue-reported measure, formatted by its kind rather than by the venue. Venues say what a
 * number means; how it reads is the app's decision, so every venue reads the same way.
 */
internal fun vaultFactText(value: VaultFactValue): String =
  when (value) {
    is VaultFactValue.Money -> formatBalance(value.usd)
    is VaultFactValue.Percent ->
      if (value.signed) signedPercent(value.percent) else "${formatQuantity(value.percent, 2)}%"
    is VaultFactValue.Ratio -> formatQuantity(value.value, value.decimals)
    is VaultFactValue.Text -> value.text
  }

/** Only a signed measure earns a colour; an unsigned one is just a number. */
internal fun vaultFactColor(value: VaultFactValue): Color =
  if (value is VaultFactValue.Percent && value.signed) amountColor(value.percent)
  else Color.Unspecified
