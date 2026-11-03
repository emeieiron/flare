package xyz.mcxross.flare.venue.decibel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.decibel.*
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.model.*
import xyz.mcxross.kaptos.Aptos

/**
 * Real account/session repositories; only transport, platform vault and transaction execution fake.
 */
class AccountSessionScopeIosTest {
  @Test
  fun freshOwnerSessionCanCheckDelegationAndAuthenticateTheTradingKey() = runTest {
    fixture { f ->
      f.sessions.ensureOwner(ACCOUNT, PROMPT)
      f.accounts.prepareTradingWallet(PROMPT)
      assertEquals(listOf("owner:$ACCOUNT"), f.delegationBearers)
      assertEquals(1, f.apiAuthentications)
    }
  }

  @Test
  fun completedReferralReadCannotLeaveSetupUsingTheOwnerWalletScope() = runTest {
    fixture { f ->
      f.sessions.ensureOwner(ACCOUNT, PROMPT)
      f.sessions.ensureOwner(null, PROMPT) // A referral poll completed before Enable was pressed.
      f.accounts.prepareTradingWallet(PROMPT)
      assertEquals(listOf("owner:$ACCOUNT"), f.delegationBearers)
      assertEquals(1, f.apiAuthentications)
    }
  }

  @Test
  fun referralScopeSwitchDuringTokenLookupCannotReplaceTheDelegationBearer() = runTest {
    fixture { f ->
      f.sessions.ensureOwner(ACCOUNT, PROMPT)
      f.beforeNextToken = { this@runTest.async { f.sessions.ensureOwner(null, PROMPT) }.await() }
      f.accounts.prepareTradingWallet(PROMPT)
      assertEquals(listOf("owner:$ACCOUNT"), f.delegationBearers)
      assertEquals(1, f.apiAuthentications)
    }
  }

  @Test
  fun delegatedApiOnlyProfileReadsWithoutRequestingAnOwnerCredential() = runTest {
    fixture { f ->
      f.sessions.authenticateApi(ACCOUNT, PROMPT)
      f.preferences.setOwnerWallet(null, backupConfirmed = false)
      f.expectedDelegationRole = "api"
      f.accounts.prepareTradingWallet(PROMPT)
      assertEquals(listOf("api:$ACCOUNT"), f.delegationBearers)
      assertEquals(2, f.apiAuthentications)
    }
  }

  @Test
  fun confirmingTheTradingKeyDoesNotWaitForAccountReads() = runTest {
    fixture { f ->
      f.sessions.ensureOwner(ACCOUNT, PROMPT)
      val reads = CompletableDeferred<Unit>()
      f.accountReads = reads
      // Returns while the overview read is still held open.
      f.accounts.prepareTradingWallet(PROMPT)
      assertEquals(1, f.apiAuthentications)
      reads.complete(Unit)
    }
  }

  @Test
  fun tradingAccountsComeOldestFirstSoTheirNumbersHold() = runTest {
    fixture { f ->
      val newest = "0x" + "77".repeat(32)
      f.listed = listOf(newest, ACCOUNT)
      f.sessions.ensureOwner(null, PROMPT)
      val owner = f.preferences.values.first().ownerAddress!!
      assertEquals(listOf(ACCOUNT, newest), f.accounts.subaccounts(owner).map { it.address })
    }
  }

  @Test
  fun selectingAnAccountDoesNotWaitForItsReads() = runTest {
    fixture { f ->
      val reads = CompletableDeferred<Unit>()
      f.accountReads = reads
      // Returns while the overview read is still held open.
      f.accounts.selectTradingAccount(ACCOUNT, PROMPT)
      assertEquals(ACCOUNT, f.preferences.values.first().selectedSubaccount)
      reads.complete(Unit)
    }
  }

  @Test
  fun discoveryPinsOwnerScopeEvenWhenAnApiSessionBecomesTheDefault() = runTest {
    fixture { f ->
      f.beforeNextToken = {
        this@runTest.async { f.sessions.authenticateApi(ACCOUNT, PROMPT) }.await()
      }
      assertEquals(ACCOUNT, f.accounts.discoverOwnerSubaccounts(PROMPT).single().address)
      assertEquals(listOf("owner:wallet"), f.discoveryBearers)
    }
  }

  private suspend fun fixture(block: suspend (ScopeFixture) -> Unit) {
    val data =
      object : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
          transform(state.value).also { state.value = it }
      }
    val preferences = AppPreferences(data)
    val wallets = DefaultWalletRepository(ForegroundWalletVault(IosTestMemoryVault()), preferences)
    val owner =
      wallets.importOwner(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        PROMPT,
      )
    val api = wallets.createApiWallet(PROMPT)
    preferences.setSelectedSubaccount(ACCOUNT)
    val f = ScopeFixture()
    f.preferences = preferences
    var requestedWallet = ""
    var requestedScope: String? = null
    val expires = Clock.System.now().toEpochMilliseconds() + 55 * 60_000
    val http =
      HttpClient(
        MockEngine { request ->
          val path = request.url.encodedPath.substringAfterLast('/')
          val body =
            when (path) {
              "challenge" -> {
                val value = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                requestedWallet = value.getValue("walletAddress").jsonPrimitive.content
                requestedScope = value["subaccount"]?.jsonPrimitive?.contentOrNull
                """{"challenge":"FLARE_AUTH_V1\ntest","expiresAt":$expires}"""
              }
              "session" -> {
                val role = if (requestedWallet == owner) "owner" else "api"
                val scope = requestedScope?.let { Json.encodeToString(it) } ?: "null"
                """{"token":"$role:${requestedScope ?: "wallet"}","role":"$role","walletAddress":"$requestedWallet","subaccount":$scope,"expiresAt":$expires}"""
              }
              "delegations" -> {
                val bearer = request.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")
                f.delegationBearers += bearer
                if (bearer != "${f.expectedDelegationRole}:$ACCOUNT") {
                  return@MockEngine respond(
                    """{"error":"Subaccount scope mismatch"}""",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                  )
                }
                """[{"delegated_account":"$api","permission_type":"TradePerpsAllMarkets"}]"""
              }
              "subaccounts" -> {
                val bearer = request.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")
                f.discoveryBearers += bearer
                if (bearer != "owner:wallet") {
                  return@MockEngine respond(
                    """{"error":"Owner scope mismatch"}""",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                  )
                }
                f.listed.joinToString(",", "[", "]") {
                  """{"subaccount_address":"$it","primary_account_address":"$owner","is_primary":${it == ACCOUNT}}"""
                }
              }
              "account_overviews" -> {
                f.accountReads?.await()
                "{}"
              }
              "account_positions" -> "[]"
              "open_orders",
              "order_history",
              "trade_history",
              "funding_rate_history" -> """{"items":[],"total_count":0}"""
              "account_fund_history" -> """{"funds":[],"total":0}"""
              else -> "{}"
            }
          respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        },
      ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
      }
    val workerSessions =
      WorkerSessionRepository(
        http,
        FlareRuntimeConfig(Network.MAINNET),
        DecibelRuntimeConfig("https://worker.example"),
        preferences,
        wallets,
      )
    f.sessions =
      object : AptosSessionRepository by workerSessions {
        override suspend fun authenticateApi(
          subaccount: String,
          prompt: VaultPrompt,
        ): SessionStatus {
          val status = workerSessions.authenticateApi(subaccount, prompt)
          // A binding around all of setup would shadow this API token with its owner token.
          assertEquals("api:$ACCOUNT", workerSessions.accessToken())
          f.apiAuthentications++
          return status
        }
      }
    val aptos = Aptos()
    val client =
      DecibelClient(
        http,
        aptos,
        DecibelConfig(
          restBaseUrl = "https://worker.example/decibel",
          accessToken = {
            val interleave = f.beforeNextToken
            f.beforeNextToken = null
            interleave?.invoke()
            f.sessions.accessToken()
          },
        ),
      )
    f.accounts = DefaultAccountRepository(client, f.sessions, ScopeTrading(), wallets, preferences)
    f.accounts.setForeground(false)
    try {
      block(f)
    } finally {
      f.accounts.setForeground(false)
      http.close()
      aptos.close()
    }
  }
}

private class ScopeFixture {
  lateinit var preferences: AppPreferences
  var expectedDelegationRole = "owner"
  lateinit var sessions: AptosSessionRepository
  lateinit var accounts: DefaultAccountRepository
  var beforeNextToken: (suspend () -> Unit)? = null
  val delegationBearers = mutableListOf<String>()
  val discoveryBearers = mutableListOf<String>()
  var apiAuthentications = 0
  var accountReads: CompletableDeferred<Unit>? = null
  /** What Decibel lists for the wallet, newest first as it does. */
  var listed = listOf(ACCOUNT)
}

private class ScopeTrading : DecibelTrading {
  override val pendingTransactions = flowOf(emptyList<PendingTransaction>())

  override fun execute(
    action: TradeAction,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<ActionState> = error("No transaction expected")

  override fun executeCommand(
    command: DecibelCommand,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<ActionState> = error("Existing delegation must be reused")

  override suspend fun transactionStatus(reference: String): ActionState = error("Not used")

  override suspend fun tradingKeyFeeBalance() = 0uL

  override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt): Flow<ActionState> =
    error("Not used")

  override suspend fun reconcilePending() = ReconciliationResult(0, 0)
}

private const val ACCOUNT = "0x2"
private val PROMPT = VaultPrompt("Test", "Authorize")
