package xyz.mcxross.flare.venue.decibel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import xyz.mcxross.flare.aptos.swap.*
import xyz.mcxross.flare.aptos.transaction.AptosTransactionExecutor
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.*
import xyz.mcxross.flare.venue.Network as VenueNetwork
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.kaptos.*
import xyz.mcxross.kaptos.account.Account
import xyz.mcxross.kaptos.core.crypto.*
import xyz.mcxross.kaptos.model.*
import xyz.mcxross.kaptos.transaction.authenticator.AccountAuthenticator
import xyz.mcxross.kaptos.transport.ktor.asAptosTransport

/** Uses the native chain runtime, as SponsoredCommandFlowIosTest does; host Android tests cannot load its JNI library. */
class AptosSwapRepositoryIosTest {
  @Test
  fun fundingCapacityReadsExactBalancesAndRetainsBothGasBudgetsWithoutSigning() = runTest {
    fixture { f ->
      val capacity = f.repository.fundingCapacity("0x1")
      assertEquals(f.balance.toULong(), capacity.inputBalance)
      assertEquals(f.balance.toULong(), capacity.outputBalance)
      assertEquals((50_000uL + f.aptos.settings.transactionDefaults.maxGasAmount) * 100uL, capacity.networkFeeReserve)
      assertEquals(capacity.inputBalance - capacity.networkFeeReserve, capacity.maximumInput)
      assertTrue(f.events.isEmpty())
    }
  }

  @Test
  fun sponsoredSwapJournalsBeforeSubmissionAndCannotExecuteTwice() = runTest {
    fixture { f ->
      val quote = f.repository.quote(REQUEST)
      val states = f.repository.execute(quote.id, PROMPT).toList()
      assertIs<ActionState.Committed>(states.last(), states.toString())
      assertEquals(listOf("simulate", "authorize", "sign", "sponsor", "confirm"), f.events)
      assertEquals(SwapOperationStatus.COMMITTED, f.store.operations.first().single().status)
      assertTrue(f.journal.pending().isEmpty())
      assertIs<ActionState.Failed>(f.repository.execute(quote.id, PROMPT).last())
      assertEquals(1, f.events.count { it == "sponsor" })
    }
  }

  @Test
  fun unknownSubmissionSurvivesRestartAndIsReconciledWithoutResending() = runTest {
    fixture { f ->
      f.sponsorFailure = AptosError.Transport("response lost")
      val quote = f.repository.quote(REQUEST)
      val failed = assertIs<ActionState.Failed>(f.repository.execute(quote.id, PROMPT).last())
      assertFalse(failed.definitelyNotSubmitted)
      assertNull(failed.selfPay)
      assertEquals(SwapOperationStatus.PENDING, f.store.operations.first().single().status)
      val restarted = f.newRepository()
      assertEquals(SwapOperationStatus.PENDING, restarted.reconcile().single().status)
      assertIs<ActionState.Failed>(f.repository.execute(quote.id, PROMPT).last())
      f.trading.statusResult = ActionState.Committed(HASH)
      assertEquals(SwapOperationStatus.COMMITTED, restarted.reconcile().single().status)
      assertTrue(f.journal.pending().isEmpty())
      assertEquals(1, f.events.count { it == "sponsor" })
    }
  }

  @Test
  fun definiteSponsorRejectionPermitsOnlyAnExplicitSelfPaidRetry() = runTest {
    fixture { f ->
      f.sponsorFailure = AptosError.Api("unavailable", errorCode = "gas_station_not_configured")
      val quote = f.repository.quote(REQUEST)
      val failed = assertIs<ActionState.Failed>(f.repository.execute(quote.id, PROMPT).last())
      assertTrue(failed.definitelyNotSubmitted, failed.toString())
      assertNotNull(failed.selfPay)
      assertEquals(SwapOperationStatus.NOT_SUBMITTED, f.store.operations.first().single().status)
      assertIs<ActionState.Committed>(f.repository.execute(quote.id, PROMPT, FeePayment.SELF_PAY).last())
      assertEquals(1, f.events.count { it == "sponsor" })
      assertEquals(1, f.events.count { it == "submit" })
    }
  }

  @Test
  fun insufficientGasReserveAndExpiredAuthorizationNeverSign() = runTest {
    fixture { f ->
      val quote = f.repository.quote(REQUEST)
      f.balance = "1470229246"
      assertIs<ActionState.Failed>(f.repository.execute(quote.id, PROMPT).last())
      assertEquals(listOf("simulate"), f.events, f.repository.operations.first().toString())
    }
    fixture { f ->
      val quote = f.repository.quote(REQUEST)
      f.wallets.onConfirm = { f.now += 31000 }
      assertIs<ActionState.Failed>(f.repository.execute(quote.id, PROMPT).last())
      assertEquals(listOf("simulate", "authorize"), f.events)
    }
  }

  @Test
  fun sharedSubmissionGateAndProfileChangesPreventSigning() = runTest {
    fixture { f ->
      val quote = f.repository.quote(REQUEST)
      f.gate.mutex.lock()
      try { assertIs<ActionState.Failed>(f.repository.execute(quote.id, PROMPT).last()) }
      finally { f.gate.mutex.unlock() }
      assertTrue(f.events.isEmpty())
      f.wallets.onConfirm = { f.preferences.registerProfile(AccountProfile("other", ownerAddress = "0x2")) }
      assertIs<ActionState.Failed>(f.repository.execute(quote.id, PROMPT).last())
      assertFalse("sign" in f.events)
    }
  }

  private suspend fun fixture(block: suspend (SwapFixture) -> Unit) {
    val f = SwapFixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
    try { f.initialize(); block(f) } finally { f.aptos.close(); f.http.close() }
  }
}

private val PROMPT = VaultPrompt("Swap", "Confirm swap")
private val REQUEST = SwapRequest("0x1", AptosSwapRepository.APT, AptosSwapRepository.USDC, SwapAmountKind.EXACT_OUTPUT, "10000000")
private val HASH = "0x" + "ab".repeat(32)

private class SwapFixture(dispatcher: CoroutineDispatcher) {
  val events = mutableListOf<String>()
  var now = Clock.System.now().toEpochMilliseconds()
  var balance = "100000000000"
  var sponsorFailure: AptosError? = null
  private val data = object : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
  }
  val preferences = AppPreferences(data)
  val store = PreferencesSwapOperationStore(data)
  val journal = SwapTestJournal()
  val gate = AptosSubmissionGate()
  val wallets = SwapTestWallet(events)
  val trading = SwapTestTrading()
  val http = HttpClient(MockEngine(MockEngineConfig().apply {
    this.dispatcher = dispatcher
    addHandler { call ->
      val path = call.url.encodedPath
      val body = when {
        path == "/swap/quote" -> buildJsonObject {
          put("id", "fixture"); put("request", Json.encodeToJsonElement(AptosSwapRequest("0x1", SwapKind.EXACT_OUTPUT, "10000000")))
          put("response", Json.parseToJsonElement(FIXTURE_RESPONSE)); put("payloadHash", FIXTURE_HASH)
          put("expiresAtMs", now + 30000); put("authorization", "signed-quote")
        }.toString()
        path.endsWith("/estimate_gas_price") -> """{"gas_estimate":100}"""
        path.contains("/balance/") -> "\"$balance\""
        path.contains("/accounts/") -> """{"sequence_number":"0","authentication_key":"0x1"}"""
        path.endsWith("/transactions/simulate") -> { events += "simulate"; "[${transaction(HASH)}]" }
        path.contains("/transactions/by_hash/") -> { events += "confirm"; transaction(path.substringAfterLast('/')) }
        path.endsWith("/transactions") -> {
          events += "submit"
          assertEquals(SwapOperationStatus.SUBMITTING, store.operations.first().single().status)
          val hash = journal.pending().single().hash
          transaction(hash).replace("user_transaction", "pending_transaction")
        }
        else -> error("Unexpected request $path")
      }
      respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }
  })) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
  val aptos = Aptos(AptosConfig(network = Network.MAINNET,
    endpoints = AptosEndpoints(fullNode = "https://worker/v1"), transport = http.asAptosTransport()))
  private val sessions = object : SessionRepository {
    override val status = MutableStateFlow<SessionStatus?>(null)
    override fun <T> bind(status: SessionStatus, operation: Flow<T>) = operation
    override suspend fun accessToken() = "session"
    override suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt) =
      SessionStatus(SessionRole.OWNER, "0x1", subaccount, now + 60000).also { status.value = it }
    override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus = error("Not used")
    override suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus = error("Not used")
    override suspend fun useAnonymous(): SessionStatus = error("Not used")
    override suspend fun invalidate() = Unit
  }
  private val gas = object : GasSponsorshipRepository {
    override suspend fun submit(request: ExternalFeePayerRequest, ownerOnly: Boolean): AptosResult<String> = error("Not used")
    override suspend fun resolve(fingerprint: String): AptosResult<String?> = error("Not used")
    override suspend fun submitSwap(request: ExternalFeePayerRequest, authorization: String): AptosResult<String> {
      assertEquals("signed-quote", authorization)
      assertEquals(SwapOperationStatus.SUBMITTING, store.operations.first().single().status)
      assertTrue(journal.pending().single().hash.startsWith("sponsor:"))
      events += "sponsor"
      return sponsorFailure?.let { AptosResult.Failure(it) } ?: AptosResult.Success(HASH)
    }
  }
  lateinit var repository: AptosSwapRepository
  suspend fun initialize() {
    preferences.registerProfile(AccountProfile("owner", ownerAddress = "0x1"))
    aptos.transactions.preloadModuleAbis(Json.decodeFromString<MoveModuleBytecode>(FIXTURE_ABI))
    repository = newRepository()
  }
  fun newRepository() = AptosSwapRepository(
    PanoraSwapProvider(http, aptos, "https://worker", { "session" }, now = { now }),
    AptosTransactionExecutor(aptos), aptos, wallets, sessions, gas, trading, preferences,
    store, journal, gate, FlareRuntimeConfig(VenueNetwork.MAINNET), now = { now },
  )
  private fun transaction(hash: String): String {
    fun primary(metadata: String) = AccountAddress(sha3Hash(AccountAddress.ONE.data +
      AccountAddress.fromString(metadata).data + byteArrayOf(0xfc.toByte()))).toString()
    fun event(kind: String, store: String, amount: String) = """{"guid":{},"type":"0x1::fungible_asset::$kind","data":{"store":"$store","amount":"$amount"}}"""
    return """{"type":"user_transaction","version":"1","hash":"$hash","state_change_hash":"0x0",
      "event_root_hash":"0x0","state_checkpoint_hash":null,"gas_used":"100","success":true,
      "vm_status":"Executed successfully","accumulator_root_hash":"0x0","sender":"0x1","sequence_number":"0",
      "max_gas_amount":"50000","gas_unit_price":"100","expiration_timestamp_secs":"${now / 1000 + 30}",
      "events":[${event("Withdraw", primary(AptosSwapAssets.APT), "1470229246")},${event("Deposit", primary(AptosSwapAssets.USDC), "10000000")}],"timestamp":"0"}"""
  }
}

private class SwapTestWallet(private val events: MutableList<String>) : AptosSigningWallet {
  var onConfirm: suspend () -> Unit = {}
  override val authorizationGeneration = 0L
  override fun requireAuthorization(generation: Long) { check(generation == 0L) }
  override val profile = flowOf(WalletProfile(ownerAddress = "0x1"))
  override suspend fun confirmIdentity(prompt: VaultPrompt) { events += "authorize"; onConfirm() }
  override suspend fun <T> withOwnerAccount(prompt: VaultPrompt, block: suspend (Account) -> T): T = block(object : Account() {
    override val publicKey = Ed25519PublicKey(ByteArray(32) { 0x11 })
    override val accountAddress = AccountAddress.ONE
    override val signingScheme = SigningScheme.Ed25519
    override val isPrivateKeyCleared = true
    override fun clearPrivateKey() = Unit
    override fun sign(message: HexInput): Signature = error("Not used")
    override fun signTransactionSignature(tx: UnsignedTransaction): Signature = error("Not used")
    override fun verifySignature(message: HexInput, signature: Signature) = true
    override fun signWithAuthenticator(message: HexInput): AccountAuthenticator {
      events += "sign"
      return AccountAuthenticator.Ed25519(publicKey, Ed25519Signature(ByteArray(64) { 0x22 }))
    }
  })
  override suspend fun <T> withApiAccount(prompt: VaultPrompt, block: suspend (Account) -> T): T = error("Owner only")
  override suspend fun importAptosApiWallet(key: String, prompt: VaultPrompt, verify: suspend (Account) -> Unit): String = error("Not used")
  override suspend fun createOwner(prompt: VaultPrompt): OwnerBackup = error("Not used")
  override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String = error("Not used")
  override suspend fun confirmOwnerBackup() = Unit
  override suspend fun createApiWallet(prompt: VaultPrompt): String = error("Not used")
  override suspend fun importApiWallet(key: String, prompt: VaultPrompt, verify: suspend (String) -> Unit): String = error("Not used")
  override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String = error("Not used")
  override suspend fun exportApiWallet(prompt: VaultPrompt): String = error("Not used")
  override suspend fun removeOwner(prompt: VaultPrompt) = Unit
  override suspend fun removeApiWallet(prompt: VaultPrompt) = Unit
  override fun lock() = Unit
}

private class SwapTestTrading : TradingRepository {
  var statusResult: ActionState = ActionState.Pending("unknown")
  override val pendingTransactions = flowOf(emptyList<PendingTransaction>())
  override fun execute(action: TradeAction, prompt: VaultPrompt, feePayment: FeePayment, onPrepared: suspend (String) -> Unit): Flow<ActionState> = error("Not used")
  override suspend fun transactionStatus(reference: String) = statusResult
  override suspend fun tradingKeyFeeBalance() = 0uL
  override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt): Flow<ActionState> = error("Not used")
  override suspend fun reconcilePending() = ReconciliationResult(0, 0)
}

private class SwapTestJournal : TransactionJournalDao {
  private val rows = MutableStateFlow<List<PendingTransactionEntity>>(emptyList())
  override suspend fun upsert(transaction: PendingTransactionEntity) { rows.value = rows.value.filterNot { it.hash == transaction.hash } + transaction }
  override fun observePending(): Flow<List<PendingTransactionEntity>> = rows
  override suspend fun pending() = rows.value
  override suspend fun find(hash: String) = rows.value.firstOrNull { it.hash == hash }
  override suspend fun remove(hash: String) { rows.value = rows.value.filterNot { it.hash == hash } }
  override suspend fun deleteOtherNetworks(network: String) { rows.value = rows.value.filter { it.network == network } }
  override suspend fun replaceHash(oldHash: String, newHash: String, state: String, updatedAtMs: Long): Int {
    val old = find(oldHash) ?: return 0
    remove(oldHash); upsert(old.copy(hash = newHash, state = state, updatedAtMs = updatedAtMs)); return 1
  }
}
