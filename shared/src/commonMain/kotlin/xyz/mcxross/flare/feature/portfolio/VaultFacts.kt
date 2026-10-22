package xyz.mcxross.flare.feature.portfolio

import xyz.mcxross.flare.data.formatQuantity
import xyz.mcxross.flare.venue.model.ManagerFee

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
