package xyz.mcxross.flare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.AptosConfig
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.Network
import xyz.mcxross.kaptos.model.ReplayProtection
import xyz.mcxross.kaptos.model.TransactionOptions
import xyz.mcxross.kaptos.model.TransactionPayload
import xyz.mcxross.kaptos.model.UnsignedTransaction

class PhoneTransactionConfigIosTest {
  @Test
  fun sponsoredBuildUsesPhoneGasCeilingAndExpiration() = runTest {
    val aptos =
      Aptos(
        AptosConfig(
          network = Network.TESTNET,
          transactionDefaults = FlareRuntimeConfig().transactionDefaults,
        )
      )
    try {
      val before = Clock.System.now().epochSeconds.toULong()
      val result =
        aptos.transactions.buildFeePayer(
          sender = AccountAddress.ONE,
          payload = TransactionPayload.entryFunction("0x1::coin::transfer"),
          options =
            TransactionOptions(
              gasUnitPrice = 100uL,
              replayProtection = ReplayProtection.SequenceNumber(7uL),
            ),
        )
      val transaction = assertIs<AptosResult.Success<UnsignedTransaction.FeePayer>>(result).value
      assertEquals(50_000uL, transaction.rawTransaction.maxGasAmount)
      val after = Clock.System.now().epochSeconds.toULong()
      assertTrue(
        transaction.rawTransaction.expirationTimestampSecs in (before + 60uL)..(after + 60uL)
      )
    } finally {
      aptos.close()
    }
  }
}
