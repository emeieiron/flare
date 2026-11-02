package xyz.mcxross.flare.aptos.transaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import xyz.mcxross.kaptos.model.UserTransactionResponse

class CommittedFeeTest {
  @Test
  fun readsGrossChargeAndRefundSeparatelyWithoutAddingStorageChargeAgain() {
    assertEquals(CommittedFee(4_300uL, 700uL), receipt().verifiedFee())
  }

  @Test
  fun refundMayExceedGrossChargeWithoutUnsignedSubtraction() {
    assertEquals(CommittedFee(4_300uL, 8_000uL), receipt(refund = "8000").verifiedFee())
  }

  @Test
  fun missingMalformedInconsistentOrDuplicateStatementsRemainUnknown() {
    assertNull(receipt(events = "").verifiedFee())
    assertNull(receipt(refund = "invalid").verifiedFee())
    assertNull(receipt(statementGas = "42").verifiedFee())
    assertNull(receipt(events = "$STATEMENT,$STATEMENT").verifiedFee())
    assertNull(
      receipt(events = STATEMENT.replace("0x1::transaction_fee", "0x2::transaction_fee"))
        .verifiedFee(),
    )
  }

  @Test
  fun overflowingOrImpossibleReceiptGasRemainsUnknown() {
    assertNull(
      receipt(
          gas = "18446744073709551615",
          statementGas = "18446744073709551615",
          maximum = "18446744073709551615",
        )
        .verifiedFee(),
    )
    assertNull(receipt(maximum = "42").verifiedFee())
  }

  private fun receipt(
    gas: String = "43",
    statementGas: String = gas,
    refund: String = "700",
    maximum: String = "2000",
    events: String? = null,
  ): UserTransactionResponse {
    val statement =
      STATEMENT.replace("\"43\"", "\"$statementGas\"").replace("\"700\"", "\"$refund\"")
    return Json.decodeFromString<UserTransactionResponse>(
      """{"version":"1","hash":"0x1","state_change_hash":"0x0","event_root_hash":"0x0","state_checkpoint_hash":null,"gas_used":"$gas","success":true,"vm_status":"Executed successfully","accumulator_root_hash":"0x0","sender":"0x3","sequence_number":"0","max_gas_amount":"$maximum","gas_unit_price":"100","expiration_timestamp_secs":"9999999999","events":[${events ?: statement}],"timestamp":"0"}""",
    )
  }
}

private const val STATEMENT =
  """{"guid":{},"type":"0x1::transaction_fee::FeeStatement","data":{"total_charge_gas_units":"43","execution_gas_units":"20","io_gas_units":"3","storage_fee_octas":"2000","storage_fee_refund_octas":"700"}}"""
