package xyz.mcxross.flare.venue.decibel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.decibel.*
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.*
import xyz.mcxross.flare.venue.model.*
import xyz.mcxross.kaptos.*
import xyz.mcxross.kaptos.account.Account
import xyz.mcxross.kaptos.core.crypto.Ed25519PublicKey
import xyz.mcxross.kaptos.core.crypto.Ed25519Signature
import xyz.mcxross.kaptos.core.crypto.Signature
import xyz.mcxross.kaptos.model.*
import xyz.mcxross.kaptos.transaction.authenticator.AccountAuthenticator
import xyz.mcxross.kaptos.transaction.authenticator.TransactionAuthenticator
import xyz.mcxross.kaptos.transaction.instances.RawTransaction
import xyz.mcxross.kaptos.transaction.instances.SignedTransaction
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrderFeeReviewIosTest {
  @Test
  fun missingActualFeeStatementNeverFallsBackToTheSimulationEstimate() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = false
      f.includeFeeStatement = false
      val states = f.order()
      val fee = assertNotNull(assertIs<ActionState.Committed>(states.last()).networkFee)
      assertEquals(7_000uL, fee.estimate)
      assertNull(fee.actual)
    }
  }

  @Test
  fun refundsAreAggregatedBeforeClampingNetUserPaidFeeAtZero() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = false
      f.configRefund = 8_000uL
      f.entryRefund = 0uL
      val states = f.order()
      assertEquals(0uL, assertIs<ActionState.Committed>(states.last()).networkFee?.actual)
    }
  }

  @Test
  fun ownerAtTheSupportedSdkMinimumUsesActualOwnerEstimateBeforeConfirmation() = runTest {
    fixture { f ->
      f.requireConfiguration = false
      f.zeroGasOverride = 5uL
      f.realOwnerGasOverride = 10uL
      f.ownerBalance = "200000"
      f.sponsorshipAvailable = false
      val review = assertNotNull(f.trading.prepareOrderFees(listOf(ACTIONS.last())))
      assertTrue(review.ownerPaysFees)
      assertEquals(true, review.localFeeReady)
      assertEquals(1_000uL, review.estimatedFeeOctas)
      assertEquals(200_000uL, review.requiredReserveOctas)
      assertTrue(review.canSelfPay)
      assertFalse("authorize" in f.events)
      assertFalse("sign" in f.events)
      val states = f.order(listOf(ACTIONS.last()))
      assertIs<ActionState.Committed>(states.last())
      assertEquals(2_000uL, f.submitted.single().rawTxn.maxGasAmount)
      assertEquals(1, f.events.count { it == "authorize" })
    }
  }

  @Test
  fun ownerBelowTheSupportedSdkMinimumCannotAuthenticateWithAnUnsupportedBudget() = runTest {
    fixture { f ->
      f.requireConfiguration = false
      f.zeroGasOverride = 5uL
      f.realOwnerGasOverride = 10uL
      f.ownerBalance = "199999"
      f.sponsorshipAvailable = false
      val review = assertNotNull(f.trading.prepareOrderFees(listOf(ACTIONS.last())))
      assertEquals(false, review.localFeeReady)
      assertFalse(review.canSelfPay)
      assertNull(review.estimatedFeeOctas)
      assertFailsWith<IllegalStateException> {
        f.trading.confirmedOrderFees(review, true, VaultPrompt("Order", "Confirm")) {}
      }
      assertFalse("authorize" in f.events)
      assertFalse("sign" in f.events)
    }
  }

  @Test
  fun mixedSponsorshipShowsOnlyTheEntryFeePaidByTheOwner() = runTest {
    fixture { f ->
      f.onSponsor = {
        f.sponsorError =
          if (f.events.count { it == "sponsor" } == 1) null
          else AptosError.Api("not accepted", errorCode = "gas_station_http_404")
      }
      val states = f.order()
      val fee = assertNotNull(assertIs<ActionState.Committed>(states.last()).networkFee)
      assertFalse(fee.covered)
      assertEquals(5_000uL, fee.estimate)
      assertEquals(3_600uL, fee.actual)
      assertEquals(200_000uL, fee.maximum)
      assertEquals(1, f.submitted.size)
      assertEquals(1, f.events.count { it == "authorize" })
      assertEquals(4, f.events.count { it == "sign" })
    }
  }

  @Test
  fun ownerPaysForZeroAptTradingKeyWithOneConfirmationAndNoTransfer() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = false
      val before = Clock.System.now().epochSeconds.toULong()
      val states = f.order()
      val after = Clock.System.now().epochSeconds.toULong()
      assertEquals(2, states.count { it is ActionState.Committed })
      assertEquals(1, f.events.count { it == "authorize" })
      assertEquals(4, f.events.count { it == "sign" })
      assertFalse("sponsor" in f.events)
      assertEquals(0, f.freshKeyReads)
      assertEquals(2, f.submitted.size)
      f.submitted.forEach { signed ->
        assertEquals(AccountAddress.fromString("0x3"), signed.rawTxn.sender)
        val auth = assertIs<TransactionAuthenticator.FeePayer>(signed.authenticator)
        assertEquals(AccountAddress.ONE, auth.feePayerAddress)
        assertEquals(
          Ed25519PublicKey(ByteArray(32) { 0x33 }).toString(),
          assertIs<AccountAuthenticator.Ed25519>(auth.sender).publicKey.toString(),
        )
        assertEquals(
          Ed25519PublicKey(ByteArray(32) { 0x11 }).toString(),
          assertIs<AccountAuthenticator.Ed25519>(auth.feePayer).publicKey.toString(),
        )
        assertEquals(2_000uL, signed.rawTxn.maxGasAmount)
        assertEquals(100uL, signed.rawTxn.gasUnitPrice)
        assertTrue(signed.rawTxn.expirationTimestampSecs in (before + 120uL)..(after + 120uL))
        assertTrue(
          (signed.rawTxn.payload as TransactionPayload.EntryFunction).call.function.toString() in
            setOf("configure_user_settings_for_market", "place_order_to_subaccount"),
        )
      }
      assertEquals(listOf(0uL, 1uL), f.submitted.map { it.rawTxn.sequenceNumber })
      assertEquals(1, f.gasReads)
      assertTrue(
        f.simulated.first().authenticator.let {
          it is TransactionAuthenticator.FeePayer && it.feePayerAddress == AccountAddress.ZERO
        },
      )
      assertEquals(50_000uL, f.simulated.first().rawTxn.maxGasAmount)
      assertTrue(f.ownerBalance.toULong() < 5_000_000uL)
      assertEquals(7_000uL, assertIs<ActionState.Committed>(states.last()).networkFee?.estimate)
      assertEquals(4_800uL, assertIs<ActionState.Committed>(states.last()).networkFee?.actual)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun definiteSponsorRejectionFallsBackToOwnerWithoutAnotherConfirmation() = runTest {
    fixture { f ->
      val states = f.order()
      assertEquals(2, states.count { it is ActionState.Committed })
      assertEquals(1, f.events.count { it == "authorize" })
      assertEquals(6, f.events.count { it == "sign" })
      assertEquals(2, f.events.count { it == "sponsor" })
      assertTrue(f.submitted.all { it.authenticator is TransactionAuthenticator.FeePayer })
      assertEquals(0, f.freshKeyReads)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun reviewUsesCachedIdentitiesAndEstimatesEntryAlongsideConfiguration() = runTest {
    fixture { f ->
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      assertEquals("0x1", review.feePayerAddress)
      assertTrue(review.ownerPaysFees)
      assertEquals(0uL, review.tradingKeyBalance)
      assertEquals(400_000uL, review.feePayerBalance)
      assertEquals(200_000uL, review.requiredReserveOctas)
      assertTrue(review.canSelfPay)
      assertEquals(7_000uL, review.estimatedFeeOctas)
      assertEquals(10_000_000uL, review.maximumTotalOctas)
      assertEquals(3, f.simulated.size)
      assertEquals(1, f.reviewEntrySimulations)
      assertFalse("authorize" in f.events)
      assertFalse("sign" in f.events)
      assertEquals(0, f.freshKeyReads)
    }
  }

  @Test
  fun entryThatCannotBeSimulatedBeforeConfigurationKeepsTheCeiling() = runTest {
    fixture { f ->
      f.entryFailsBeforeSetup = true
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      assertNull(review.estimatedFeeOctas)
      assertEquals(10_000_000uL, review.maximumTotalOctas)
      assertTrue(review.canSelfPay)
      assertEquals(2, f.order().count { it is ActionState.Committed })
    }
  }

  @Test
  fun singleEntryReviewShowsActualSimulationEstimate() = runTest {
    fixture { f ->
      f.requireConfiguration = false
      val review = assertNotNull(f.trading.prepareOrderFees(listOf(ACTIONS.last())))
      assertEquals(5_000uL, review.estimatedFeeOctas)
      assertEquals(200_000uL, review.requiredReserveOctas)
      assertTrue(review.canSelfPay)
    }
  }

  @Test
  fun apiOnlyProfilePaysItselfWithoutOwnerKeyOrTransfer() = runTest {
    fixture { f ->
      f.preferences.registerProfile(
        AccountProfile("api", apiWalletAddress = "0x3", selectedSubaccount = "0x2"),
      )
      f.apiBalance = "400000"
      f.sponsorshipAvailable = false
      val states = f.order()
      assertEquals(2, states.count { it is ActionState.Committed })
      assertTrue(f.submitted.all { it.authenticator is TransactionAuthenticator.Ed25519 })
      assertEquals(2, f.events.count { it == "sign" })
      assertEquals(1, f.events.count { it == "authorize" })
      assertEquals(0, f.wallets.ownerReads)
    }
  }

  @Test
  fun fundedApiRemainsUsableWhenOwnerCannotPay() = runTest {
    fixture { f ->
      f.ownerBalance = "0"
      f.apiBalance = "400000"
      f.sponsorshipAvailable = false
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      assertFalse(review.ownerPaysFees)
      assertEquals("0x3", review.feePayerAddress)
      assertTrue(review.canSelfPay)
      assertEquals(2, f.order().count { it is ActionState.Committed })
      assertTrue(f.submitted.all { it.authenticator is TransactionAuthenticator.Ed25519 })
    }
  }

  @Test
  fun insufficientOrUnknownPayerBalanceNeverAuthenticatesForDirectPayment() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = false
      for (unknown in listOf(false, true)) {
        f.ownerBalance = "0"
        f.balanceUnknown = unknown
        val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
        assertFalse(review.canSelfPay)
        if (unknown) assertNull(review.feePayerBalance)
        assertFailsWith<IllegalStateException> {
          f.trading.confirmedOrderFees(review, true, VaultPrompt("Order", "Confirm")) {}
        }
      }
      assertFalse("authorize" in f.events)
      assertFalse("sign" in f.events)
    }
  }

  @Test
  fun unknownBalancesStillAllowSuccessfulSponsorship() = runTest {
    fixture { f ->
      f.balanceUnknown = true
      f.sponsorshipAvailable = true
      f.sponsorError = null
      val states = f.order(allowSelfPay = false)
      assertEquals(2, states.count { it is ActionState.Committed })
      assertTrue(f.submitted.isEmpty())
      assertEquals(1, f.events.count { it == "authorize" })
    }
  }

  @Test
  fun unknownSponsorOutcomeNeverSubmitsAnotherFormatOrStartsEntry() = runTest {
    fixture { f ->
      f.sponsorError = AptosError.Transport("response lost")
      val states = f.order()
      assertFalse(assertIs<ActionState.Failed>(states.last()).definitelyNotSubmitted)
      assertEquals(1, f.events.count { it == "sign" })
      assertTrue(f.submitted.isEmpty())
      assertEquals("RECONCILE_REQUIRED", f.journal.pending().single().state)
      assertEquals(0, f.entrySimulations)
    }
  }

  @Test
  fun entryIsRebuiltAndSimulatedAfterConfigurationAndFailureDoesNotReplayIt() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = false
      f.failEntrySimulation = true
      val states = f.order()
      assertEquals(1, states.count { it is ActionState.Committed })
      assertIs<ActionState.Failed>(states.last())
      assertEquals(1, f.submitted.size)
      assertEquals(1uL, f.sequence)
      assertEquals(2, f.events.count { it == "sign" })
      assertEquals(1, f.events.count { it == "authorize" })
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun freshRealPayerSimulationRejectsLostFundsBeforeCryptographicSigning() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = false
      f.wallets.onConfirm = { f.ownerBalance = "0" }
      val states = f.order()
      assertIs<ActionState.Failed>(states.last())
      assertEquals(1, f.events.count { it == "authorize" })
      assertFalse("sign" in f.events)
      assertTrue(f.submitted.isEmpty())
    }
  }

  @Test
  fun unknownLocalSubmissionRemainsJournaledAndDoesNotRepeatConfiguration() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = false
      f.loseSubmitResponse = true
      val states = f.order()
      assertIs<ActionState.Failed>(states.last())
      assertEquals(1, f.submitted.size)
      assertEquals(2, f.events.count { it == "sign" })
      assertEquals(0, f.entrySimulations)
      assertEquals("RECONCILE_REQUIRED", f.journal.pending().single().state)
    }
  }

  @Test
  fun changedOrderAndConsumedReviewCannotSign() = runTest {
    fixture { f ->
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      f.trading.confirmedOrderFees(review, true, VaultPrompt("Order", "Confirm")) {
        val changed = (ACTIONS.first() as TradeAction.ConfigureMarket).copy(leverage = 3)
        assertIs<ActionState.Failed>(
          f.trading.execute(changed, VaultPrompt("Order", "Confirm")).last(),
        )
      }
      assertFalse("sign" in f.events)
      assertFailsWith<IllegalStateException> {
        f.trading.confirmedOrderFees(review, true, VaultPrompt("Order", "Confirm")) {}
      }
    }
  }

  @Test
  fun aCloseFeeReviewTakesTwoNetworkRoundTrips() = runTest {
    fixture { f ->
      f.requireConfiguration = false
      f.sponsorshipAvailable = false
      f.networkDelayMs = 100
      val started = testScheduler.currentTime
      val review = assertNotNull(f.trading.prepareOrderFees(listOf(ACTIONS.last())))
      val elapsed = testScheduler.currentTime - started
      println("close fee review: ${elapsed}ms of simulated network time")
      assertEquals(5_000uL, review.estimatedFeeOctas)
      assertTrue(review.canSelfPay)
      assertTrue(elapsed <= 200, "took ${elapsed}ms")
    }
  }

  @Test
  fun aDeclinedTradingFeeIsPaidByTheOwnerWithinASimulatedBudget() = runTest {
    fixture { f ->
      f.requireConfiguration = false
      val states = f.walletFallback()
      assertIs<ActionState.Committed>(states.last())
      val signed = f.submitted.single()
      val auth = assertIs<TransactionAuthenticator.FeePayer>(signed.authenticator)
      assertEquals(AccountAddress.ONE, auth.feePayerAddress)
      assertEquals(2_000uL, signed.rawTxn.maxGasAmount)
      assertEquals(1, f.events.count { it == "sponsor" })
      assertEquals(1, f.events.count { it == "authorize" })
    }
  }

  @Test
  fun theTradingKeyPaysOnlyWhenTheOwnerCannot() = runTest {
    fixture { f ->
      f.requireConfiguration = false
      f.ownerBalance = "0"
      f.apiBalance = "400000"
      assertIs<ActionState.Committed>(f.walletFallback().last())
      val signed = f.submitted.single()
      assertIs<TransactionAuthenticator.Ed25519>(signed.authenticator)
      assertEquals(2_000uL, signed.rawTxn.maxGasAmount)
    }
  }

  @Test
  fun aFeeNobodyCanPayIsNamedOnceAndNothingIsSent() = runTest {
    fixture { f ->
      f.requireConfiguration = false
      f.ownerBalance = "0"
      val failed = assertIs<ActionState.Failed>(f.walletFallback().last())
      assertEquals("Add APT to cover network fees.", failed.message)
      assertNull(failed.selfPay)
      assertTrue(failed.definitelyNotSubmitted)
      assertTrue(f.submitted.isEmpty())
    }
  }

  @Test
  fun sponsoredOnlyActionsNeverFallBackToTheWallet() = runTest {
    fixture { f ->
      f.requireConfiguration = false
      val failed =
        assertIs<ActionState.Failed>(
          f.trading
            .execute(ACTIONS.last(), VaultPrompt("Auto trading", "Foreground authorization"))
            .toList()
            .last(),
        )
      assertNotNull(failed.selfPay)
      assertTrue(f.submitted.isEmpty())
    }
  }

  private suspend fun fixture(block: suspend (OrderFeeFixture) -> Unit) {
    val f = OrderFeeFixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
    try {
      f.initialize()
      block(f)
    } finally {
      f.aptos.close()
      f.http.close()
    }
  }
}

private val HASH = "0x" + "ab".repeat(32)
private val MARKET =
  Market(
    "0x4",
    "BTC",
    "BTC/USD",
    MarketKind.PERP,
    "crypto",
    2,
    8,
    40,
    false,
    DecibelOrderRules(
      "0x4",
      "BTC/USD",
      xyz.mcxross.flare.decibel.model.AssetType.PERP,
      xyz.mcxross.flare.decibel.model.MarketPrecision(
        2,
        8,
        100uL,
        1000uL,
        2000uL,
        1uL,
        100_000_000uL,
      ),
    ),
  )
private val ACTIONS =
  listOf(
    TradeAction.ConfigureMarket("0x2", "0x4", MarginMode.CROSS, 2),
    TradeAction.PlaceOrder(
      "0x2",
      MARKET,
      OrderRequest("0x4", OrderSide.BUY, OrderType.LIMIT, "0.001", "70000"),
    ),
  )

private class OrderFeeFixture(dispatcher: CoroutineDispatcher) {
  val events = mutableListOf<String>()
  val prepared = mutableListOf<String>()
  val simulatedTransactions = mutableListOf<RawTransaction>()
  val submittedTransactions = mutableListOf<RawTransaction>()
  val simulated = mutableListOf<SignedTransaction>()
  val submitted = mutableListOf<SignedTransaction>()
  var sequence = 0uL
  var ownerBalance = "400000"
  var apiBalance = "0"
  var requireConfiguration = true
  var configurationCommitted = false
  var failEntrySimulation = false
  var loseSubmitResponse = false
  var entrySimulations = 0
  var reviewEntrySimulations = 0
  var entryFailsBeforeSetup = false
  var includeFeeStatement = true
  var configRefund = 500uL
  var entryRefund = 700uL
  var committedConfigGas = 17uL
  var committedEntryGas = 43uL
  var zeroGasOverride: ULong? = null
  var realOwnerGasOverride: ULong? = null
  private val pendingRaw = mutableMapOf<String, RawTransaction>()
  val wallets = SwapTestWallet(events)
  val journal = SwapTestJournal()
  var sponsorshipAvailable: Boolean? = null
  var freshKeyReads = 0
  var balanceUnknown = false
  var balance = "100000000"
  var gasReads = 0
  var sequenceReads = 0
  var checkParallelMetadata = false
  var metadataDelayMs = 0L
  var networkDelayMs = 0L
  val gasStarted = CompletableDeferred<Unit>()
  val sequenceStarted = CompletableDeferred<Unit>()
  var sponsorError: AptosError? = AptosError.Api("not accepted", errorCode = "gas_station_http_404")
  var onSponsor: suspend () -> Unit = {}
  var sessionRestoreDelayMs = 0L
  var sessionRestores = 0
  private val data =
    object : DataStore<Preferences> {
      override val data = MutableStateFlow(emptyPreferences())

      override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
        transform(data.value).also { data.value = it }
    }
  val preferences = AppPreferences(data)
  val http =
    HttpClient(
      MockEngine(
        MockEngineConfig().apply {
          this.dispatcher = dispatcher
          addHandler { request ->
            delay(networkDelayMs)
            val path = request.url.encodedPath
            val body =
              when {
                path.endsWith("/estimate_gas_price") -> {
                  gasStarted.complete(Unit)
                  if (checkParallelMetadata) sequenceStarted.await()
                  delay(metadataDelayMs)
                  """{"gas_estimate":100}""".also { gasReads++ }
                }
                path.contains("/balance/") ->
                  if (balanceUnknown) error("balance unavailable")
                  else {
                    val address =
                      AccountAddress.fromString(
                        path.substringAfter("/accounts/").substringBefore('/'),
                      )
                    "\"${if (address == AccountAddress.ONE) ownerBalance else apiBalance}\""
                  }
                path.contains("/accounts/") -> {
                  sequenceReads++
                  sequenceStarted.complete(Unit)
                  if (checkParallelMetadata) gasStarted.await()
                  delay(metadataDelayMs)
                  """{"sequence_number":"$sequence","authentication_key":"0x3"}"""
                }
                path.endsWith("/transactions/simulate") -> {
                  events += "simulate"
                  val signed =
                    SignedTransaction.fromBcs(
                      (request.body as OutgoingContent.ByteArrayContent).bytes(),
                    )
                  simulated += signed
                  simulatedTransactions += signed.rawTxn
                  val raw = signed.rawTxn
                  val entry =
                    (raw.payload as TransactionPayload.EntryFunction).call.function.toString() ==
                      "place_order_to_subaccount"
                  val beforeSetup = entry && requireConfiguration && !configurationCommitted
                  if (entry && "authorize" !in events) reviewEntrySimulations++
                  else if (entry) {
                    entrySimulations++
                    check(!beforeSetup) { "Entry simulated before leverage was applied" }
                  }
                  val payer =
                    (signed.authenticator as? TransactionAuthenticator.FeePayer)?.feePayerAddress
                      ?: raw.sender
                  val reserveFits =
                    payer == AccountAddress.ZERO ||
                      raw.maxGasAmount * raw.gasUnitPrice <=
                        (if (payer == AccountAddress.ONE) ownerBalance else apiBalance).toULong()
                  val gas =
                    when (payer) {
                      AccountAddress.ZERO -> zeroGasOverride
                      AccountAddress.ONE -> realOwnerGasOverride
                      else -> null
                    } ?: if (entry) 50uL else 20uL
                  val success =
                    reserveFits &&
                      gas <= raw.maxGasAmount &&
                      !(entry && failEntrySimulation) &&
                      !(beforeSetup && entryFailsBeforeSetup)
                  "[${response(HASH, raw, gas, success)}]"
                }
                path.endsWith("/transactions") && request.method == HttpMethod.Post -> {
                  events += "submit"
                  val signed =
                    SignedTransaction.fromBcs(
                      (request.body as OutgoingContent.ByteArrayContent).bytes(),
                    )
                  submitted += signed
                  submittedTransactions += signed.rawTxn
                  val row = journal.pending().single()
                  assertEquals("SUBMITTING", row.state)
                  assertFalse(row.hash.startsWith("sponsor:"))
                  pendingRaw[row.hash] = signed.rawTxn
                  if (loseSubmitResponse) error("Submit response lost")
                  response(row.hash, signed.rawTxn)
                    .replace("user_transaction", "pending_transaction")
                }
                path.contains("/transactions/by_hash/") -> {
                  events += "confirm"
                  val hash = path.substringAfterLast('/')
                  val raw = pendingRaw[hash]
                  sequence++
                  configurationCommitted = true
                  if (raw != null) {
                    val signed = submitted.first { it.rawTxn == raw }
                    val payer =
                      (signed.authenticator as? TransactionAuthenticator.FeePayer)?.feePayerAddress
                        ?: raw.sender
                    val entry =
                      (raw.payload as TransactionPayload.EntryFunction).call.function.toString() ==
                        "place_order_to_subaccount"
                    val gas =
                      if (payer == AccountAddress.ONE)
                        realOwnerGasOverride ?: (if (entry) 50uL else 20uL)
                      else if (entry) 50uL else 20uL
                    val cost = gas * raw.gasUnitPrice
                    if (payer == AccountAddress.ONE)
                      ownerBalance = (ownerBalance.toULong() - cost).toString()
                    else apiBalance = (apiBalance.toULong() - cost).toString()
                  }
                  val isEntry =
                    (raw?.payload as? TransactionPayload.EntryFunction)
                      ?.call
                      ?.function
                      ?.toString() == "place_order_to_subaccount"
                  response(hash, raw, if (isEntry) committedEntryGas else committedConfigGas)
                }
                else -> error("Unexpected path $path")
              }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
          }
        },
      ),
    ) {
      install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
  val aptos =
    Aptos(
      AptosConfig(
        network = Network.MAINNET,
        endpoints = AptosEndpoints(fullNode = "https://worker/v1"),
        transactionDefaults = DecibelRuntimeConfig().transactionDefaults,
        transport = http.asAptosTransport(),
      ),
    )
  private val client = DecibelClient(http, aptos)
  private val sessions =
    object : SessionRepository {
      override val status = MutableStateFlow<SessionStatus?>(null)

      override fun <T> bind(status: SessionStatus, operation: Flow<T>) = operation

      override suspend fun accessToken() = "session"

      override suspend fun authenticateOwner(
        subaccount: String?,
        prompt: VaultPrompt,
      ): SessionStatus {
        if (prompt.requireFreshAuthorization) wallets.confirmIdentity(prompt)
        return SessionStatus(SessionRole.OWNER, "0x1", subaccount, Long.MAX_VALUE).also {
          status.value = it
        }
      }

      override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus =
        error("Owner only")

      override suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus {
        sessionRestores++
        delay(sessionRestoreDelayMs)
        return SessionStatus(SessionRole.API, "0x3", subaccount, Long.MAX_VALUE)
      }

      override suspend fun useAnonymous(): SessionStatus = error("Not used")

      override suspend fun invalidate() = Unit
    }
  private val gas =
    object : GasSponsorshipRepository {
      override suspend fun tradingSponsorshipAvailable(): Boolean? = sponsorshipAvailable

      override suspend fun submit(
        request: ExternalFeePayerRequest,
        ownerOnly: Boolean,
      ): AptosResult<String> {
        assertFalse(ownerOnly)
        assertEquals("SUBMITTING", journal.pending().single().state)
        events += "sponsor"
        onSponsor()
        return sponsorError?.let { AptosResult.Failure(it) } ?: AptosResult.Success(HASH)
      }

      override suspend fun resolve(fingerprint: String): AptosResult<String?> = error("Not used")
    }
  val trading =
    DefaultTradingRepository(
      OrderFeeDatabase(journal),
      client,
      aptos,
      object : AptosSigningWallet by wallets {
        override suspend fun <T> withApiAccount(
          prompt: VaultPrompt,
          block: suspend (xyz.mcxross.kaptos.account.Account) -> T,
        ): T {
          if (prompt.requireFreshAuthorization) freshKeyReads++
          return block(
            object : Account() {
              override val publicKey = Ed25519PublicKey(ByteArray(32) { 0x33 })
              override val accountAddress = AccountAddress.fromString("0x3")
              override val signingScheme = SigningScheme.Ed25519
              override val isPrivateKeyCleared = true

              override fun clearPrivateKey() = Unit

              override fun sign(message: HexInput): Signature = error("Not used")

              override fun signTransactionSignature(tx: UnsignedTransaction): Signature =
                error("Not used")

              override fun verifySignature(message: HexInput, signature: Signature) = true

              override fun signWithAuthenticator(message: HexInput): AccountAuthenticator {
                events += "sign"
                return AccountAuthenticator.Ed25519(
                  publicKey,
                  Ed25519Signature(ByteArray(64) { 0x44 }),
                )
              }
            },
          )
        }

        override suspend fun <T> withOwnerAccount(
          prompt: VaultPrompt,
          block: suspend (xyz.mcxross.kaptos.account.Account) -> T,
        ): T {
          if (prompt.requireFreshAuthorization) freshKeyReads++
          return wallets.withOwnerAccount(prompt, block)
        }
      },
      sessions,
      gas,
      FlareRuntimeConfig(),
      preferences,
      AptosSubmissionGate(),
    )

  suspend fun initialize() {
    preferences.registerProfile(
      AccountProfile("owner", ownerAddress = "0x1", selectedSubaccount = "0x2"),
    )
    preferences.setApiWallet("0x3")
    val fn =
      MoveFunction(
        "deposit_to_subaccount_at",
        MoveVisibility.PUBLIC,
        true,
        false,
        emptyList(),
        listOf("&signer", "address", "0x1::object::Object<0x1::fungible_asset::Metadata>", "u64"),
        emptyList(),
      )
    assertIs<AptosResult.Success<Unit>>(
      aptos.transactions.preloadModuleAbis(
        MoveModuleBytecode(
          "0x",
          MoveModule(
            DecibelDeployment.Mainnet.packageAddress,
            "dex_accounts_entry",
            emptyList(),
            listOf(
              fn,
              MoveFunction(
                "configure_user_settings_for_market",
                MoveVisibility.PUBLIC,
                true,
                false,
                emptyList(),
                listOf("&signer", "address", "address", "bool", "u8"),
                emptyList(),
              ),
              MoveFunction(
                "place_order_to_subaccount",
                MoveVisibility.PUBLIC,
                true,
                false,
                emptyList(),
                listOf(
                  "&signer",
                  "address",
                  "address",
                  "u64",
                  "u64",
                  "bool",
                  "u8",
                  "bool",
                  "0x1::option::Option<0x1::string::String>",
                  "0x1::option::Option<u64>",
                  "0x1::option::Option<u64>",
                  "0x1::option::Option<u64>",
                  "0x1::option::Option<u64>",
                  "0x1::option::Option<u64>",
                  "0x1::option::Option<address>",
                  "0x1::option::Option<u64>",
                ),
                emptyList(),
              ),
            ),
            emptyList(),
          ),
        ),
      ),
    )
  }

  suspend fun order(
    actions: List<TradeAction> = ACTIONS,
    allowSelfPay: Boolean = true,
  ): List<ActionState> {
    val review = assertNotNull(trading.prepareOrderFees(actions))
    return trading.confirmedOrderFees(
      review,
      allowSelfPay,
      VaultPrompt("Confirm order", "Confirm your identity"),
    ) {
      val states = mutableListOf<ActionState>()
      for (action in actions) {
        val phase = trading.execute(action, VaultPrompt("Order", "Confirm your identity")).toList()
        states += phase
        if (phase.last() !is ActionState.Committed) break
      }
      states
    }
  }

  suspend fun walletFallback(): List<ActionState> =
    trading
      .execute(
        ACTIONS.last(),
        VaultPrompt("Close position", "Confirm your identity"),
        FeePayment.WALLET_FALLBACK,
      )
      .toList()

  private fun response(
    hash: String,
    raw: RawTransaction? = null,
    gas: ULong = 20uL,
    success: Boolean = true,
  ): String {
    val entry =
      (raw?.payload as? TransactionPayload.EntryFunction)?.call?.function?.toString() ==
        "place_order_to_subaccount"
    val refund = if (entry) entryRefund else configRefund
    val events =
      if (includeFeeStatement)
        """{"guid":{},"type":"0x1::transaction_fee::FeeStatement","data":{"total_charge_gas_units":"$gas","storage_fee_refund_octas":"$refund","storage_fee_octas":"0"}}"""
      else ""
    return """{"type":"user_transaction","version":"1","hash":"$hash","state_change_hash":"0x0","event_root_hash":"0x0","state_checkpoint_hash":null,"gas_used":"$gas","success":$success,"vm_status":"${if (success) "Executed successfully" else "Insufficient gas reserve or unapplied leverage"}","accumulator_root_hash":"0x0","sender":"${raw?.sender ?: "0x3"}","sequence_number":"${raw?.sequenceNumber ?: sequence}","max_gas_amount":"${raw?.maxGasAmount ?: 2000uL}","gas_unit_price":"${raw?.gasUnitPrice ?: 100uL}","expiration_timestamp_secs":"${raw?.expirationTimestampSecs ?: 9999999999uL}","events":[$events],"timestamp":"0"}"""
  }
}

private class OrderFeeDatabase(private val journal: TransactionJournalDao) : FlareDatabase() {
  private val generated = FlareDatabaseConstructor.initialize()

  override fun marketCacheDao() = generated.marketCacheDao()

  override fun transactionJournalDao() = journal

  override fun assetCatalogDao() = generated.assetCatalogDao()

  override suspend fun clearAllTables() = Unit
}
