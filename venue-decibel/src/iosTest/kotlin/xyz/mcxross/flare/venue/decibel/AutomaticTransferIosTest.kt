package xyz.mcxross.flare.venue.decibel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.decibel.*
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.*
import xyz.mcxross.flare.venue.model.*
import xyz.mcxross.kaptos.*
import xyz.mcxross.kaptos.model.*
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticTransferIosTest {
  @Test
  fun definiteRejectionFallsBackOnceWithOneAuthorizationAndTheSameGasBudget() = runTest {
    fixture { f ->
      val states = f.run()
      val result = assertIs<ActionState.Committed>(states.last())
      assertTrue(
        states.indexOfFirst { it is ActionState.FeeEstimated } <
          states.indexOf(ActionState.AwaitingAuthorization),
      )
      assertTrue(states.none { it is ActionState.Failed })
      assertFalse(result.networkFee!!.covered)
      assertEquals(NetworkFee(200uL, 5_000_000uL), result.networkFee)
      assertEquals(
        listOf("simulate", "authorize", "sign", "sponsor", "simulate", "sign", "submit", "confirm"),
        f.events,
      )
      assertEquals(1, f.gasReads)
      assertTrue(f.journal.pending().isEmpty())
      assertEquals(2, f.prepared.size)
      assertTrue(f.prepared.first().startsWith("sponsor:"))
      assertFalse(f.prepared.last().startsWith("sponsor:"))
    }
  }

  @Test
  fun automaticFallbackCannotBeEnabledForANonTransferCommand() = runTest {
    fixture { f ->
      val result = assertIs<ActionState.Failed>(f.run(DecibelCommand.CreateSubaccount).last())
      assertTrue(result.definitelyNotSubmitted)
      assertTrue(f.events.isEmpty())
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun acceptedSponsorshipReportsCoverageWithoutDirectSubmission() = runTest {
    fixture { f ->
      f.sponsorError = null
      val result = assertIs<ActionState.Committed>(f.run().last())
      assertTrue(result.networkFee!!.covered)
      assertEquals(1, f.events.count { it == "authorize" })
      assertFalse("submit" in f.events)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun uncertainSponsorshipIsJournaledWithoutAutomaticSelfPay() = runTest {
    fixture { f ->
      f.sponsorError = AptosError.Transport("response lost")
      val result = assertIs<ActionState.Failed>(f.run().last())
      assertFalse(result.definitelyNotSubmitted)
      assertFalse("submit" in f.events)
      assertEquals(1, f.events.count { it == "sign" })
      assertEquals("RECONCILE_REQUIRED", f.journal.pending().single().state)
    }
  }

  @Test
  fun anInsufficientGasReserveNeverTriggersTheFallback() = runTest {
    fixture { f ->
      f.balance = "1000"
      val result = assertIs<ActionState.Failed>(f.run().last())
      assertTrue(result.definitelyNotSubmitted)
      assertNull(result.selfPay)
      assertFalse("submit" in f.events)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun aChangedForegroundVisitStopsTheFallbackBeforeAnotherSignature() = runTest {
    fixture { f ->
      f.onSponsor = { f.wallets.generation++ }
      val result = assertIs<ActionState.Failed>(f.run().last())
      assertTrue(result.definitelyNotSubmitted)
      assertEquals(1, f.events.count { it == "sign" })
      assertFalse("submit" in f.events)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun backgroundFeeEstimationNeverAuthorizesSignsOrSubmits() = runTest {
    fixture { f ->
      assertEquals(NetworkFee(200uL, 5_000_000uL), f.trading.estimateCommandFee(COMMAND))
      assertEquals(listOf("simulate"), f.events)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun warmedFeeReadsReusePublicIdentityAndPriceWhileRefreshingSequenceAndSimulation() = runTest {
    fixture { f ->
      f.trading.warmFundingFees()
      assertTrue(f.events.isEmpty())
      assertEquals(1, f.wallets.ownerReads)
      assertEquals(NetworkFee(200uL, 5_000_000uL), f.trading.estimateCommandFee(COMMAND))
      assertEquals(
        NetworkFee(200uL, 5_000_000uL),
        f.trading.estimateCommandFee(COMMAND.copy(amount = 2_000_000uL)),
      )
      assertEquals(1, f.wallets.ownerReads)
      assertEquals(1, f.gasReads)
      assertEquals(2, f.sequenceReads)
      assertEquals(listOf("simulate", "simulate"), f.events)
      f.wallets.generation++
      assertEquals(NetworkFee(200uL, 5_000_000uL), f.trading.estimateCommandFee(COMMAND))
      assertEquals(2, f.wallets.ownerReads)
      assertEquals(3, f.sequenceReads)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun signingFetchesFreshMetadataAfterABackgroundFeeRead() = runTest {
    fixture { f ->
      assertIs<NetworkFee>(f.trading.estimateCommandFee(COMMAND))
      // The mock price jumps on the next read; its old simulation must be rejected before signing.
      assertIs<ActionState.Failed>(f.run().last())
      assertEquals(2, f.gasReads)
      assertEquals(listOf("simulate", "simulate"), f.events)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun coldMetadataReadsOverlapAndOnlyTheFeePriceIsCached() = runTest {
    fixture { f ->
      var now = 0L
      f.checkParallelMetadata = true
      f.metadataDelayMs = 400
      val metadata = FundingFeeMetadata(f.aptos, now = { now })
      val started = testScheduler.currentTime
      val first = withTimeout(1_000) { metadata.options(AccountAddress.ONE) }
      assertEquals(400L, testScheduler.currentTime - started)
      assertEquals(100uL, first.gasUnitPrice)
      metadata.options(AccountAddress.ONE)
      assertEquals(1, f.gasReads)
      assertEquals(2, f.sequenceReads)
      now = 10_000
      assertEquals(900uL, metadata.options(AccountAddress.ONE).gasUnitPrice)
      assertEquals(2, f.gasReads)
      assertEquals(3, f.sequenceReads)
      assertEquals(0, f.wallets.ownerReads)
      assertTrue(f.events.isEmpty())
    }
  }

  @Test
  fun aConfirmedFundingCommandDoesNotWaitForTradingSessionRestoration() = runTest {
    fixture { f ->
      f.preferences.setApiWallet("0x3")
      f.sessionRestoreDelayMs = 60_000
      assertIs<ActionState.Committed>(withTimeout(1_000) { f.run() }.last())
      assertEquals(0, f.sessionRestores)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun delegatingToThisDevicesTradingKeyLeavesItsSignInToSetup() = runTest {
    // Setup signs the device's trading key in right after delegating to it; another key's
    // delegation still switches back to the device's trading session.
    for ((key, restores) in listOf("0x3" to 0, "0x4" to 1)) {
      fixture { f ->
        f.preferences.setApiWallet("0x3")
        f.sponsorError = null
        val states =
          f.run(
            DecibelCommand.DelegateTrading("0x2", key),
            VaultPrompt("Enable trading", "Confirm your identity"),
            FeePayment.SPONSORED,
          )
        assertIs<ActionState.Committed>(states.last())
        assertEquals(restores, f.sessionRestores, key)
      }
    }
  }

  private suspend fun fixture(block: suspend (TransferFixture) -> Unit) {
    val f = TransferFixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
    try {
      f.initialize()
      block(f)
    } finally {
      f.aptos.close()
      f.http.close()
    }
  }
}

private val COMMAND =
  DecibelCommand.Deposit("0x2", DecibelDeployment.Mainnet.usdcMetadataAddress, 1_000_000uL)
private val HASH = "0x" + "ab".repeat(32)

private class TransferFixture(dispatcher: CoroutineDispatcher) {
  val events = mutableListOf<String>()
  val prepared = mutableListOf<String>()
  val wallets = SwapTestWallet(events)
  val journal = SwapTestJournal()
  var balance = "100000000"
  var gasReads = 0
  var sequenceReads = 0
  var checkParallelMetadata = false
  var metadataDelayMs = 0L
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
            val path = request.url.encodedPath
            val body =
              when {
                path.endsWith("/estimate_gas_price") -> {
                  gasStarted.complete(Unit)
                  if (checkParallelMetadata) sequenceStarted.await()
                  delay(metadataDelayMs)
                  """{"gas_estimate":${if (++gasReads == 1) 100 else 900}}"""
                }
                path.contains("/balance/") -> "\"$balance\""
                path.contains("/accounts/") -> {
                  sequenceReads++
                  sequenceStarted.complete(Unit)
                  if (checkParallelMetadata) gasStarted.await()
                  delay(metadataDelayMs)
                  """{"sequence_number":"0","authentication_key":"0x1"}"""
                }
                path.endsWith("/transactions/simulate") -> {
                  events += "simulate"
                  "[${response(HASH)}]"
                }
                path.endsWith("/transactions") && request.method == HttpMethod.Post -> {
                  events += "submit"
                  val row = journal.pending().single()
                  assertEquals("SUBMITTING", row.state)
                  assertFalse(row.hash.startsWith("sponsor:"))
                  response(row.hash).replace("user_transaction", "pending_transaction")
                }
                path.contains("/transactions/by_hash/") -> {
                  events += "confirm"
                  response(path.substringAfterLast('/'))
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
      override suspend fun submit(
        request: ExternalFeePayerRequest,
        ownerOnly: Boolean,
      ): AptosResult<String> {
        assertTrue(ownerOnly)
        assertEquals("SUBMITTING", journal.pending().single().state)
        events += "sponsor"
        onSponsor()
        return sponsorError?.let { AptosResult.Failure(it) } ?: AptosResult.Success(HASH)
      }

      override suspend fun resolve(fingerprint: String): AptosResult<String?> = error("Not used")
    }
  val trading =
    DefaultTradingRepository(
      TransferDatabase(journal),
      client,
      aptos,
      wallets,
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
                "delegate_all_trading_to_for_subaccount",
                MoveVisibility.PUBLIC,
                true,
                false,
                emptyList(),
                listOf("&signer", "address", "address", "0x1::option::Option<u64>"),
                emptyList(),
              ),
            ),
            emptyList(),
          ),
        ),
      ),
    )
  }

  suspend fun run(
    command: DecibelCommand = COMMAND,
    prompt: VaultPrompt =
      VaultPrompt("Transfer", "Confirm your identity", requireFreshAuthorization = true),
    feePayment: FeePayment = FeePayment.AUTOMATIC,
  ) =
    trading.executeCommand(command, prompt, feePayment, onPrepared = { prepared += it }).toList()

  private fun response(hash: String) =
    """{"type":"user_transaction","version":"1","hash":"$hash","state_change_hash":"0x0","event_root_hash":"0x0","state_checkpoint_hash":null,"gas_used":"2","success":true,"vm_status":"Executed successfully","accumulator_root_hash":"0x0","sender":"0x1","sequence_number":"0","max_gas_amount":"50000","gas_unit_price":"100","expiration_timestamp_secs":"9999999999","events":[],"timestamp":"0"}"""
}

private class TransferDatabase(private val journal: TransactionJournalDao) : FlareDatabase() {
  private val generated = FlareDatabaseConstructor.initialize()

  override fun marketCacheDao() = generated.marketCacheDao()

  override fun transactionJournalDao() = journal

  override fun assetCatalogDao() = generated.assetCatalogDao()

  override suspend fun clearAllTables() = Unit
}
