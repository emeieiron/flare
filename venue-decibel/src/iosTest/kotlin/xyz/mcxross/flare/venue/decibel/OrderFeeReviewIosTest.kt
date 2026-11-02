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
import xyz.mcxross.kaptos.model.*
import xyz.mcxross.kaptos.transaction.instances.RawTransaction
import xyz.mcxross.kaptos.transaction.instances.SignedTransaction
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrderFeeReviewIosTest {
  @Test
  fun knownUnavailableSponsorshipPaysDirectlyWithOneConfirmationAndNoGasStationCalls() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = false
      val before = Clock.System.now().epochSeconds.toULong()
      val states = f.order()
      val after = Clock.System.now().epochSeconds.toULong()
      assertEquals(2, f.simulatedTransactions.size)
      assertEquals(2, f.submittedTransactions.size)
      val first = f.simulatedTransactions.first()
      assertEquals(50_000uL, first.maxGasAmount)
      assertEquals(100uL, first.gasUnitPrice)
      assertTrue(first.expirationTimestampSecs in (before + 120uL)..(after + 120uL))
      assertTrue(
        (f.simulatedTransactions + f.submittedTransactions).all {
          it.maxGasAmount == first.maxGasAmount &&
            it.gasUnitPrice == first.gasUnitPrice &&
            it.expirationTimestampSecs == first.expirationTimestampSecs
        },
      )
      assertEquals(1, f.gasReads)
      assertEquals(2, states.count { it is ActionState.Committed })
      assertEquals(1, f.events.count { it == "authorize" })
      assertEquals(2, f.events.count { it == "sign" })
      assertEquals(2, f.events.count { it == "submit" })
      assertFalse("sponsor" in f.events)
      assertEquals(0, f.freshKeyReads)
      assertTrue(
        states.filterIsInstance<ActionState.Committed>().all { it.networkFee?.covered == false },
      )
    }
  }

  @Test
  fun knownUnavailableSponsorshipRequiresFundedExplicitFeeConsentBeforeAuthentication() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = false
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      assertEquals(false, review.sponsorshipAvailable)
      assertFailsWith<IllegalStateException> {
        f.trading.confirmedOrderFees(review, false, VaultPrompt("Order", "Confirm")) {}
      }
      assertTrue(f.events.isEmpty())
      f.balance = "0"
      val unfunded = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      assertFailsWith<IllegalStateException> {
        f.trading.confirmedOrderFees(unfunded, true, VaultPrompt("Order", "Confirm")) {}
      }
      assertTrue(f.events.isEmpty())
    }
  }

  @Test
  fun availableSponsorshipAllowsSponsorOnlyConfirmation() = runTest {
    fixture { f ->
      f.sponsorshipAvailable = true
      f.sponsorError = null
      f.balance = "0"
      val states = f.order(allowSelfPay = false)
      assertEquals(2, states.count { it is ActionState.Committed })
      assertEquals(1, f.events.count { it == "authorize" })
      assertEquals(2, f.events.count { it == "sponsor" })
      assertFalse("submit" in f.events)
    }
  }

  @Test
  fun reviewReadsPublicMetadataWithoutKeysOrConfirmation() = runTest {
    fixture { f ->
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      assertEquals(5_000_000uL, review.maximumPerTransactionOctas)
      assertEquals(10_000_000uL, review.maximumTotalOctas)
      assertEquals(100_000_000uL, review.tradingKeyBalance)
      assertEquals(0, f.wallets.ownerReads)
      assertTrue(f.events.isEmpty())
    }
  }

  @Test
  fun leverageAndOrderFallbackUseOneWalletConfirmation() = runTest {
    fixture { f ->
      val states = f.order()
      assertEquals(2, states.count { it is ActionState.Committed })
      assertEquals(1, f.events.count { it == "authorize" })
      assertEquals(4, f.events.count { it == "sign" })
      assertEquals(2, f.events.count { it == "submit" })
      assertEquals(0, f.freshKeyReads)
      assertTrue(f.journal.pending().isEmpty())
    }
  }

  @Test
  fun sponsorOnlyNeedsNoAptAndNeverSubmitsDirectly() = runTest {
    fixture { f ->
      f.balance = "0"
      val states = f.order(allowSelfPay = false)
      assertTrue(states.last() is ActionState.Failed)
      assertFalse("submit" in f.events)
      assertEquals(1, f.events.count { it == "authorize" })
    }
  }

  @Test
  fun unknownBalanceRemainsUnknownAndSponsorshipCanSucceed() = runTest {
    fixture { f ->
      f.balanceUnknown = true
      f.sponsorError = null
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      assertNull(review.tradingKeyBalance)
      val result =
        f.trading.confirmedOrderFees(review, false, VaultPrompt("Order", "Confirm")) {
          f.trading.execute(ACTIONS.first(), VaultPrompt("Order", "Confirm")).last()
        }
      assertIs<ActionState.Committed>(result)
      assertEquals(1, f.events.count { it == "authorize" })
    }
  }

  @Test
  fun fundedSponsorOnlyReviewNeverFallsBackWithoutConsent() = runTest {
    fixture { f ->
      val states = f.order(allowSelfPay = false)
      assertIs<ActionState.Failed>(states.last())
      assertFalse("submit" in f.events)
      assertEquals(1, f.events.count { it == "authorize" })
    }
  }

  @Test
  fun insufficientBalanceRetainsReserveForAnExplicitTopUp() = runTest {
    fixture { f ->
      f.balance = "0"
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      val result =
        f.trading.confirmedOrderFees(review, false, VaultPrompt("Order", "Confirm")) {
          f.trading.execute(ACTIONS.first(), VaultPrompt("Order", "Confirm")).last()
        }
      assertEquals(5_000_000uL, assertIs<ActionState.Failed>(result).selfPay?.reserve)
      assertFalse("submit" in f.events)
    }
  }

  @Test
  fun apiOnlyProfileCanReviewAndSponsorAnOrder() = runTest {
    fixture { f ->
      f.preferences.registerProfile(
        AccountProfile("api", apiWalletAddress = "0x1", selectedSubaccount = "0x2"),
      )
      f.sponsorError = null
      val states = f.order()
      assertEquals(2, states.count { it is ActionState.Committed })
      assertEquals(1, f.events.count { it == "authorize" })
      assertFalse("submit" in f.events)
    }
  }

  @Test
  fun unknownSubmissionDoesNotRetryAndKeepsJournal() = runTest {
    fixture { f ->
      f.sponsorError = AptosError.Transport("response lost")
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      val result =
        f.trading.confirmedOrderFees(review, true, VaultPrompt("Order", "Confirm")) {
          f.trading.execute(ACTIONS.first(), VaultPrompt("Order", "Confirm")).last()
        }
      assertFalse(assertIs<ActionState.Failed>(result).definitelyNotSubmitted)
      assertFalse("submit" in f.events)
      assertEquals(1, f.events.count { it == "sign" })
      assertEquals("RECONCILE_REQUIRED", f.journal.pending().single().state)
    }
  }

  @Test
  fun insufficientAggregateReserveCannotAuthorizeFallback() = runTest {
    fixture { f ->
      f.balance = "5000000"
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      assertFailsWith<IllegalStateException> {
        f.trading.confirmedOrderFees(review, true, VaultPrompt("Order", "Confirm")) {}
      }
      assertTrue(f.events.isEmpty())
    }
  }

  @Test
  fun changedOrderAndReusedReviewCannotSign() = runTest {
    fixture { f ->
      val review = assertNotNull(f.trading.prepareOrderFees(ACTIONS))
      f.trading.confirmedOrderFees(review, true, VaultPrompt("Order", "Confirm")) {
        val changed = (ACTIONS.first() as TradeAction.ConfigureMarket).copy(leverage = 3)
        assertIs<ActionState.Failed>(
          f.trading.execute(changed, VaultPrompt("Order", "Confirm")).last(),
        )
      }
      assertEquals(0, f.events.count { it == "sign" })
      assertFailsWith<IllegalStateException> {
        f.trading.confirmedOrderFees(review, true, VaultPrompt("Order", "Confirm")) {}
      }
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
                path.contains("/balance/") ->
                  if (balanceUnknown) error("balance unavailable") else "\"$balance\""
                path.contains("/accounts/") -> {
                  sequenceReads++
                  sequenceStarted.complete(Unit)
                  if (checkParallelMetadata) gasStarted.await()
                  delay(metadataDelayMs)
                  """{"sequence_number":"0","authentication_key":"0x1"}"""
                }
                path.endsWith("/transactions/simulate") -> {
                  events += "simulate"
                  simulatedTransactions +=
                    SignedTransaction.fromBcs(
                        (request.body as OutgoingContent.ByteArrayContent).bytes(),
                      )
                      .rawTxn
                  "[${response(HASH)}]"
                }
                path.endsWith("/transactions") && request.method == HttpMethod.Post -> {
                  events += "submit"
                  submittedTransactions +=
                    SignedTransaction.fromBcs(
                        (request.body as OutgoingContent.ByteArrayContent).bytes(),
                      )
                      .rawTxn
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
        return SessionStatus(SessionRole.API, "0x1", subaccount, Long.MAX_VALUE)
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
          return wallets.withOwnerAccount(prompt, block)
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
    preferences.setApiWallet("0x1")
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
      actions.flatMap {
        trading.execute(it, VaultPrompt("Order", "Confirm your identity")).toList()
      }
    }
  }

  private fun response(hash: String) =
    """{"type":"user_transaction","version":"1","hash":"$hash","state_change_hash":"0x0","event_root_hash":"0x0","state_checkpoint_hash":null,"gas_used":"2","success":true,"vm_status":"Executed successfully","accumulator_root_hash":"0x0","sender":"0x1","sequence_number":"0","max_gas_amount":"50000","gas_unit_price":"100","expiration_timestamp_secs":"9999999999","events":[],"timestamp":"0"}"""
}

private class OrderFeeDatabase(private val journal: TransactionJournalDao) : FlareDatabase() {
  private val generated = FlareDatabaseConstructor.initialize()

  override fun marketCacheDao() = generated.marketCacheDao()

  override fun transactionJournalDao() = journal

  override fun assetCatalogDao() = generated.assetCatalogDao()

  override suspend fun clearAllTables() = Unit
}
