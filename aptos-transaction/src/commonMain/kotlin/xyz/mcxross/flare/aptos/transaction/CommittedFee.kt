package xyz.mcxross.flare.aptos.transaction

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mcxross.kaptos.model.UserTransactionResponse

/** Charges and refunds are kept separate until all user-paid transaction receipts are known. */
internal data class CommittedFee(val gasChargeOctas: ULong, val storageRefundOctas: ULong)

private val FEE_STATEMENT = Regex("^0x0*1::transaction_fee::FeeStatement$")

/** The fee statement's storage charge is already part of gas_used; only its refund is separate. */
internal fun UserTransactionResponse.verifiedFee(): CommittedFee? {
  val gas = gasUsed.toULongOrNull() ?: return null
  val price = gasUnitPrice.toULongOrNull() ?: return null
  val maximum = maxGasAmount.toULongOrNull() ?: return null
  if (gas > maximum || price != 0uL && gas > ULong.MAX_VALUE / price) return null
  val statement =
    events.singleOrNull { it.type?.matches(FEE_STATEMENT) == true }?.data as? JsonObject
      ?: return null
  fun amount(key: String) = (statement[key] as? JsonPrimitive)?.content?.toULongOrNull()
  if (amount("total_charge_gas_units") != gas) return null
  val refund = amount("storage_fee_refund_octas") ?: return null
  return CommittedFee(gas * price, refund)
}
