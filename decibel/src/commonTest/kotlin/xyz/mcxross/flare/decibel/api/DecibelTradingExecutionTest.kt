package xyz.mcxross.flare.decibel.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.flare.decibel.model.*
import xyz.mcxross.kaptos.*
import xyz.mcxross.kaptos.account.TransactionSigner
import xyz.mcxross.kaptos.core.crypto.*
import xyz.mcxross.kaptos.model.*
import xyz.mcxross.kaptos.transaction.authenticator.AccountAuthenticator
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

/** Runs the real Kaptos build, BCS, hash, submit and polling paths against controlled HTTP. */
class DecibelTradingExecutionTest {
  @Test
  fun directSubmissionJournalsBeforeWriteAndConfirms() = runTest {
    fixture { f ->
      val states = f.execute()
      assertEquals(listOf("simulate", "sign", "journal", "submit", "confirm"), f.events)
      assertEquals(TransactionState.Committed(f.hash), states.last())
      assertEquals(
        listOf(
          TransactionState.Simulating,
          TransactionState.AwaitingAuthorization,
          TransactionState.Submitting,
          TransactionState.Pending(f.hash),
          TransactionState.Committed(f.hash),
        ),
        states,
      )
    }
  }

  @Test
  fun emptySimulationNeverSignsOrSubmits() = runTest {
    fixture { f ->
      f.simulation = "[]"
      assertIs<TransactionState.Failed>(f.execute().last())
      assertEquals(listOf("simulate"), f.events)
    }
  }

  @Test
  fun abortedSimulationNeverSignsOrSubmits() = runTest {
    fixture { f ->
      f.simulation = "[${userResponse(success = false)}]"
      assertIs<TransactionState.Failed>(f.execute().last())
      assertEquals(listOf("simulate"), f.events)
    }
  }

  @Test
  fun journalFailurePreventsNetworkWrite() = runTest {
    fixture { f ->
      val states =
        f.service
          .execute(
            f.signer,
            DecibelCommand.CreateSubaccount,
            onPrepared = { error("disk full") },
          )
          .toList()
      assertEquals("disk full", assertIs<TransactionState.Failed>(states.last()).message)
      assertEquals(listOf("simulate", "sign"), f.events)
    }
  }

  @Test
  fun journalCancellationPropagatesWithoutSubmission() = runTest {
    fixture { f ->
      assertFailsWith<CancellationException> {
        f.service
          .execute(
            f.signer,
            DecibelCommand.CreateSubaccount,
            onPrepared = { throw CancellationException("locked") },
          )
          .toList()
      }
      assertEquals(listOf("simulate", "sign"), f.events)
    }
  }

  @Test
  fun committedAbortIsFinalRatherThanUncertain() = runTest {
    fixture { f ->
      f.confirmation = { userResponse(it, success = false) }
      val failed = assertIs<TransactionState.Failed>(f.execute().last())
      assertTrue(failed.committed)
      assertEquals(f.hash, failed.hash)
    }
  }

  @Test
  fun mismatchedConfirmationCannotFinalizeJournal() = runTest {
    fixture { f ->
      f.confirmation = { userResponse(OTHER_HASH) }
      val failed = assertIs<TransactionState.Failed>(f.execute().last())
      assertFalse(failed.committed)
      assertEquals(f.hash, failed.hash)
    }
  }

  @Test
  fun mismatchedSubmissionNeverPollsOrOffersSelfPay() = runTest {
    fixture { f ->
      f.submitHash = OTHER_HASH
      val failed = assertIs<TransactionState.Failed>(f.execute().last())
      assertFalse(failed.definitelyNotSubmitted)
      assertNull(failed.selfPayEstimateOctas)
      assertFalse("confirm" in f.events)
    }
  }

  @Test
  fun explicitSponsorRejectionsAllowSelfPayButAmbiguousFailuresDoNot() = runTest {
    for (code in listOf(400, 401, 403, 404, 422, 429, 500, 502, 504)) {
      fixture { f ->
        val states =
          f.execute(
            ExternalFeePayerSubmitter {
              assertTrue(f.hash.startsWith("sponsor:"))
              AptosResult.Failure(AptosError.Api("rejected", errorCode = "gas_station_http_$code"))
            }
          )
        val failed = assertIs<TransactionState.Failed>(states.last())
        assertEquals(code < 500, failed.definitelyNotSubmitted)
        assertEquals(if (code < 500) 200uL else null, failed.selfPayEstimateOctas)
        assertFalse("submit" in f.events)
        assertFalse("confirm" in f.events)
      }
    }
  }

  @Test
  fun sponsoredSuccessReplacesFingerprintWithConfirmedHash() = runTest {
    fixture { f ->
      val states = f.execute(ExternalFeePayerSubmitter { AptosResult.Success(OTHER_HASH) })
      assertTrue(f.hash.startsWith("sponsor:"))
      assertEquals(TransactionState.Pending(OTHER_HASH), states[3])
      assertEquals(TransactionState.Committed(OTHER_HASH), states.last())
    }
  }

  @Test
  fun invalidSponsorHashNeverPolls() = runTest {
    fixture { f ->
      val failed =
        assertIs<TransactionState.Failed>(
          f.execute(ExternalFeePayerSubmitter { AptosResult.Success("invalid") }).last()
        )
      assertFalse(failed.definitelyNotSubmitted)
      assertFalse("confirm" in f.events)
    }
  }

  @Test
  fun allAccountAndTradeCommandsRunThroughTheSubmissionLifecycle() = runTest {
    val commands =
      listOf(
        DecibelCommand.CreateSubaccount,
        DecibelCommand.Deposit("0x11", "0x33", 1uL),
        DecibelCommand.Withdraw("0x11", "0x33", 1uL),
        DecibelCommand.DelegateTrading("0x11", "0x44"),
        DecibelCommand.RevokeDelegation("0x11", "0x44"),
        DecibelCommand.ConfigureMarket("0x11", "0x22", MarginMode.CROSS, 1u),
        DecibelCommand.PlaceOrder(
          "0x11",
          ValidatedOrder(
            "0x22",
            OrderSide.SELL,
            10uL,
            1uL,
            TimeInForce.POST_ONLY,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
          ),
        ),
        DecibelCommand.CancelOrder("0x11", "0x22", "340282366920938463463374607431768211455"),
        DecibelCommand.SetPositionTpSl("0x11", "0x22", stopLossTrigger = 1uL),
        DecibelCommand.CancelPositionTpSl("0x11", "0x22", "1"),
      )
    for (command in commands) fixture { f ->
      assertIs<TransactionState.Committed>(f.execute(command = command).last())
      assertEquals(1, f.events.count { it == "submit" })
    }
  }

  @Test
  fun multipleSimulationResultsNeverAuthorize() = runTest {
    fixture { f ->
      f.simulation = "[${userResponse()},${userResponse()}]"
      assertIs<TransactionState.Failed>(f.execute().last())
      assertEquals(listOf("simulate"), f.events)
    }
  }

  @Test
  fun nonUserConfirmationCannotFinalize() = runTest {
    fixture { f ->
      f.confirmation = {
        userResponse(it).replace("user_transaction", "state_checkpoint_transaction")
      }
      val failed = assertIs<TransactionState.Failed>(f.execute().last())
      assertFalse(failed.committed)
      assertEquals(f.hash, failed.hash)
    }
  }

  @Test
  fun transportFailureFromSponsorNeverOffersAnotherSubmission() = runTest {
    fixture { f ->
      val failure =
        assertIs<TransactionState.Failed>(
          f.execute(
              ExternalFeePayerSubmitter {
                AptosResult.Failure(AptosError.Transport("response lost"))
              }
            )
            .last()
        )
      assertFalse(failure.definitelyNotSubmitted)
      assertNull(failure.selfPayEstimateOctas)
      assertFalse("submit" in f.events)
    }
  }

  private suspend fun fixture(block: suspend (ExecutionFixture) -> Unit) {
    val f = ExecutionFixture()
    try {
      assertIs<AptosResult.Success<Unit>>(f.aptos.transactions.preloadModuleAbis(DECIBEL_ENTRY_ABI))
      block(f)
    } finally {
      f.aptos.close()
      f.http.close()
    }
  }
}

private class ExecutionFixture {
  val events = mutableListOf<String>()
  var hash = ""
  var submitHash: String? = null
  var simulation = "[${userResponse()}]"
  var confirmation: (String) -> String = { userResponse(it) }
  val http =
    HttpClient(
      MockEngine { request ->
        val path = request.url.encodedPath
        val body =
          when {
            path.endsWith("/estimate_gas_price") -> """{"gas_estimate":100}"""
            path.contains("/accounts/") -> """{"sequence_number":"0","authentication_key":"0x1"}"""
            path.endsWith("/transactions/simulate") -> {
              events += "simulate"
              simulation
            }
            path.endsWith("/transactions") -> {
              events += "submit"
              assertTrue(hash.isNotBlank(), "Signed hash must be journaled before submission")
              pendingResponse(submitHash ?: hash)
            }
            path.contains("/transactions/by_hash/") -> {
              events += "confirm"
              confirmation(path.substringAfterLast('/'))
            }
            else -> error("Unexpected HTTP request: $path")
          }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
      }
    ) {
      install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
  val aptos =
    Aptos(
      AptosConfig(
        network = Network.TESTNET,
        endpoints = AptosEndpoints(fullNode = "https://test.invalid/v1"),
        transport = http.asAptosTransport(),
      )
    )
  val service = DefaultDecibelTradingService(aptos, DecibelDeployment.Testnet)
  // Deterministic external signer: tests the transaction protocol, not native key storage.
  val signer =
    object : TransactionSigner {
      val auth =
        AccountAuthenticator.Ed25519(
          Ed25519PublicKey(ByteArray(32) { 0x11 }),
          Ed25519Signature(ByteArray(64) { 0x22 }),
        )
      override val accountAddress = AccountAddress.ONE
      override val publicKey: PublicKey = auth.publicKey

      override suspend fun signBytes(message: ByteArray): AptosResult<Signature> =
        AptosResult.Success(auth.signature)

      override suspend fun signText(message: String) = signBytes(message.encodeToByteArray())

      override suspend fun signTransaction(
        transaction: UnsignedTransaction
      ): AptosResult<AccountAuthenticator> {
        events += "sign"
        return AptosResult.Success(auth)
      }

      override fun verifySignature(message: ByteArray, signature: Signature) = true
    }

  suspend fun execute(
    sponsor: ExternalFeePayerSubmitter? = null,
    command: DecibelCommand = DecibelCommand.CreateSubaccount,
  ) =
    service
      .execute(
        signer,
        command,
        externalFeePayer = sponsor,
        onPrepared = {
          events += "journal"
          hash = it
        },
      )
      .toList()
}

private val OTHER_HASH = "0x" + "ab".repeat(32)

private fun pendingResponse(hash: String) =
  """{"type":"pending_transaction","hash":"$hash","sender":"0x1","sequence_number":"0","max_gas_amount":"2000000","gas_unit_price":"100","expiration_timestamp_secs":"9999999999"}"""

private fun userResponse(hash: String = OTHER_HASH, success: Boolean = true) =
  """{"type":"user_transaction","version":"1","hash":"$hash","state_change_hash":"$OTHER_HASH","event_root_hash":"$OTHER_HASH","state_checkpoint_hash":null,"gas_used":"2","success":$success,"vm_status":"${if (success) "Executed successfully" else "Move abort"}","accumulator_root_hash":"$OTHER_HASH","sender":"0x1","sequence_number":"0","max_gas_amount":"2000000","gas_unit_price":"100","expiration_timestamp_secs":"9999999999","events":[],"timestamp":"1"}"""
