package xyz.mcxross.flare.venue.decibel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.SessionStatus
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.feature.referrals.ReferralEligibility
import xyz.mcxross.flare.feature.referrals.ReferralIntentStore
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.kaptos.account.Account

class DecibelReferralsTest {
  private val owner = "0x1"
  private val otherOwner = "0x2"
  private val code = "OC9E6E"
  private val prompt = VaultPrompt("Referral code", "Confirm your identity")

  @Test
  fun programConfigIsPublicAndReadOnly() = runTest {
    fixture { f ->
      f.engineHandler = { request ->
        assertEquals("GET", request.method.value)
        assertEquals("/referrals/config", request.url.encodedPath)
        assertEquals(null, request.headers[HttpHeaders.Authorization])
        respondJson("""{"enabled":true,"suggested_code":"$code"}""")
      }

      val program = f.repository.program()

      assertTrue(program.enabled)
      assertEquals(code, program.suggestedCode)
      assertTrue(f.sessions.ownerEnsures.isEmpty())
      assertTrue(f.sessions.bound.isEmpty())
    }
  }

  @Test
  fun statusUsesOwnerSessionAndBindsTheRequestToIt() = runTest {
    fixture { f ->
      f.engineHandler = { request ->
        assertEquals("GET", request.method.value)
        assertEquals("/referrals/status", request.url.encodedPath)
        assertEquals("Bearer test-owner-token", request.headers[HttpHeaders.Authorization])
        respondJson("""{"account":"$owner","status":"eligible"}""")
      }

      val result = f.repository.status(owner)

      assertEquals(ReferralEligibility.ELIGIBLE, result.eligibility)
      assertEquals(1, f.sessions.ownerEnsures.size)
      assertNull(f.sessions.ownerEnsures.single())
      assertEquals(listOf(SessionRole.OWNER), f.sessions.bound.map { it.role })
    }
  }

  @Test
  fun rejectsSessionForAnotherOwnerBeforeHttpOrIntentWrite() = runTest {
    fixture { f ->
      f.sessions.sessionOwner = otherOwner
      f.engineHandler = { error("A mismatched owner session must not send HTTP") }

      kotlin.test.assertFails { f.repository.apply(owner, code, prompt) }

      assertTrue(f.requestPaths.isEmpty())
      assertTrue(f.intents.writes.isEmpty())
      assertTrue(f.sessions.bound.isEmpty())
    }
  }

  @Test
  fun rejectsSameProfileOwnerChangeDuringEnsureBeforeHttpOrIntentWrite() = runTest {
    fixture { f ->
      f.sessions.onEnsureOwner = { f.preferences.setOwnerWallet(otherOwner, backupConfirmed = true) }
      f.engineHandler = { error("An owner change during authentication must not send HTTP") }

      kotlin.test.assertFails { f.repository.apply(owner, code, prompt) }

      assertEquals("owner", f.preferences.values.first().activeProfileId)
      assertEquals(otherOwner, f.preferences.values.first().ownerAddress)
      assertTrue(f.requestPaths.isEmpty())
      assertTrue(f.intents.writes.isEmpty())
      assertEquals(1, f.sessions.bound.size)
    }
  }

  @Test
  fun applyPersistsBeforePostAndSendsOnlyCodeWithOwnerSession() = runTest {
    fixture { f ->
      f.engineHandler = { request ->
        assertEquals("POST", request.method.value)
        assertEquals("/referrals/redeem", request.url.encodedPath)
        assertEquals("Bearer test-owner-token", request.headers[HttpHeaders.Authorization])
        assertEquals("flare://mobile", request.headers[HttpHeaders.Origin])
        assertEquals(code, f.intents.code(owner))
        val content = assertIs<OutgoingContent.ByteArrayContent>(request.body)
        val body = Json.parseToJsonElement(content.bytes().decodeToString()).jsonObject
        assertEquals(setOf("code"), body.keys)
        assertEquals(code, body["code"]?.jsonPrimitive?.content)
        respondJson("""{"account":"$owner","status":"pending","referral_code":"$code"}""")
      }

      val result = f.repository.apply(owner, code, prompt)

      assertEquals(ReferralEligibility.PENDING, result.eligibility)
      assertEquals(code, f.intents.code(owner))
      assertEquals(listOf(SessionRole.OWNER), f.sessions.bound.map { it.role })
    }
  }

  @Test
  fun pendingAndTransportLossRetainIntentAndPollingNeverPosts() = runTest {
    fixture { f ->
      var postCount = 0
      f.engineHandler = { request ->
        if (request.url.encodedPath.endsWith("/redeem")) {
          postCount++
          respondJson("""{"account":"$owner","status":"pending","referral_code":"$code"}""", HttpStatusCode.Accepted)
        } else {
          assertEquals("/referrals/status", request.url.encodedPath)
          respondJson("""{"account":"$owner","status":"pending","referral_code":"$code"}""")
        }
      }

      assertEquals(ReferralEligibility.PENDING, f.repository.apply(owner, code, prompt).eligibility)
      assertEquals(ReferralEligibility.PENDING, f.repository.status(owner).eligibility)
      assertEquals(1, postCount)
      assertEquals(code, f.intents.code(owner))
    }

    fixture { f ->
      var postCount = 0
      f.engineHandler = { request ->
        assertEquals("POST", request.method.value)
        postCount++
        error("connection reset")
      }

      kotlin.test.assertFails { f.repository.apply(owner, code, prompt) }
      assertEquals(code, f.intents.code(owner))
      assertEquals(1, postCount)
    }
  }

  @Test
  fun boundResponseMarksProfileBeforeClearingIntent() = runTest {
    fixture { f ->
      f.engineHandler = { request ->
        assertEquals("POST", request.method.value)
        respondJson("""{"account":"$owner","status":"bound","referral_code":"$code"}""")
      }

      val result = f.repository.apply(owner, code, prompt)

      assertEquals(ReferralEligibility.BOUND, result.eligibility)
      assertNull(f.intents.code(owner))
      assertTrue(f.intents.openedWhenCleared)
      assertTrue(f.preferences.values.first().profiles.single { it.id == "owner" }.tradingAccountOpened)
    }
  }

  @Test
  fun wrongOwnerOrUnknownStatusCannotReportBound() = runTest {
    for (response in listOf(
      """{"account":"$otherOwner","status":"bound","referral_code":"$code"}""",
      """{"account":"$owner","status":"unexpected","referral_code":"$code"}""",
    )) {
      fixture { f ->
        f.engineHandler = { request ->
          assertEquals("POST", request.method.value)
          respondJson(response)
        }
        kotlin.test.assertFails { f.repository.apply(owner, code, prompt) }
        assertEquals(code, f.intents.code(owner))
        assertFalse(f.preferences.values.first().profiles.single { it.id == "owner" }.tradingAccountOpened)
      }
    }
  }

  @Test
  fun lateBoundResponseIsRejectedAfterProfileSwitch() = runTest {
    fixture { f ->
      val requestSeen = CompletableDeferred<Unit>()
      val releaseResponse = CompletableDeferred<Unit>()
      f.engineHandler = { request ->
        assertEquals("POST", request.method.value)
        requestSeen.complete(Unit)
        releaseResponse.await()
        respondJson("""{"account":"$owner","status":"bound","referral_code":"$code"}""")
      }

      val apply = async { runCatching { f.repository.apply(owner, code, prompt) } }
      requestSeen.await()
      f.preferences.registerProfile(AccountProfile("other", ownerAddress = otherOwner))
      f.preferences.activateProfile("other")
      releaseResponse.complete(Unit)

      val outcome = apply.await()
      assertTrue(outcome.isFailure)
      assertFalse(f.preferences.values.first().profiles.single { it.id == "other" }.tradingAccountOpened)
    }
  }

  @Test
  fun onlyDefinitiveRejectionsClearIntent() = runTest {
    for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests)) {
      fixture { f ->
        f.engineHandler = { _ -> respondJson("{}", status) }
        kotlin.test.assertFails { f.repository.apply(owner, code, prompt) }
        assertNull(f.intents.code(owner), "$status should clear the definitely rejected intent")
      }
    }

    for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.RequestTimeout)) {
      fixture { f ->
        f.engineHandler = { _ -> respondJson("{}", status) }
        kotlin.test.assertFails { f.repository.apply(owner, code, prompt) }
        assertEquals(code, f.intents.code(owner), "$status must preserve an uncertain intent")
      }
    }
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.fixture(block: suspend (ReferralFixture) -> Unit) {
  val data = object : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
      transform(data.value).also { data.value = it }
  }
  val preferences = AppPreferences(data)
  preferences.registerProfile(AccountProfile("owner", ownerAddress = "0x1"))
  val sessions = ReferralSessions()
  val wallets = ReferralWallets()
  val intents = ReferralIntents(preferences)
  var handler: ReferralHandler = { respondJson("{}") }
  val requestPaths = mutableListOf<String>()
  val engine = MockEngine(MockEngineConfig().apply {
    this.dispatcher = UnconfinedTestDispatcher(testScheduler)
    addHandler { request ->
      requestPaths += request.url.encodedPath
      handler(request)
    }
  })
  val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
  val repository = DecibelReferrals(
    client,
    DecibelRuntimeConfig(),
    FlareRuntimeConfig(),
    sessions,
    wallets,
    preferences,
    intents,
  )
  val fixture = ReferralFixture(preferences, sessions, intents, repository, requestPaths) { newHandler -> handler = newHandler }
  try {
    block(fixture)
  } finally {
    client.close()
  }
}

private typealias ReferralHandler = suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData

private class ReferralFixture(
  val preferences: AppPreferences,
  val sessions: ReferralSessions,
  val intents: ReferralIntents,
  val repository: DecibelReferrals,
  val requestPaths: List<String>,
  setHandler: (ReferralHandler) -> Unit,
) {
  private val updateHandler = setHandler
  var engineHandler: ReferralHandler
    get() = error("Handler getter is not used")
    set(value) = updateHandler(value)
}

private class ReferralIntents(private val preferences: AppPreferences) : ReferralIntentStore {
  private val values = mutableMapOf<String, String>()
  val writes = mutableListOf<Pair<String, String?>>()
  var openedWhenCleared = false
  override suspend fun code(owner: String): String? = values[owner.lowercase()]
  override suspend fun save(owner: String, code: String?) {
    writes += owner to code
    if (code == null) {
      openedWhenCleared = preferences.values.first().profiles.single { it.id == "owner" }.tradingAccountOpened
      values.remove(owner.lowercase())
    } else values[owner.lowercase()] = code
  }
}

private class ReferralSessions : AptosSessionRepository {
  val ownerEnsures = mutableListOf<String?>()
  val bound = mutableListOf<SessionStatus>()
  var sessionOwner = "0x1"
  var onEnsureOwner: suspend () -> Unit = {}
  private val ownerSession get() = SessionStatus(SessionRole.OWNER, sessionOwner, expiresAt = Long.MAX_VALUE)
  override val status: StateFlow<SessionStatus?> = MutableStateFlow(ownerSession).asStateFlow()
  override fun <T> bind(status: SessionStatus, operation: Flow<T>): Flow<T> {
    bound += status
    return operation
  }
  override suspend fun accessToken(): String = "test-owner-token"
  override suspend fun ensureOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus {
    ownerEnsures += subaccount
    onEnsureOwner()
    return ownerSession
  }
  override suspend fun authenticateOwner(subaccount: String?, prompt: VaultPrompt): SessionStatus = ownerSession
  override suspend fun authenticateApi(subaccount: String, prompt: VaultPrompt): SessionStatus = error("Not used")
  override suspend fun verifyApiCredential(account: Account, subaccount: String): SessionStatus = error("Not used")
  override suspend fun ensureTrading(subaccount: String, prompt: VaultPrompt): SessionStatus = error("Not used")
  override suspend fun useAnonymous(): SessionStatus = error("Not used")
  override suspend fun invalidate() = Unit
}

private class ReferralWallets : AptosSigningWallet {
  override val authorizationGeneration: Long = 1
  override fun requireAuthorization(generation: Long) = Unit
  override val profile: Flow<WalletProfile> = flowOf(WalletProfile(ownerAddress = "0x1"))
  override suspend fun createOwner(prompt: VaultPrompt): OwnerBackup = error("Not used")
  override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String = error("Not used")
  override suspend fun confirmOwnerBackup() = error("Not used")
  override suspend fun createApiWallet(prompt: VaultPrompt): String = error("Not used")
  override suspend fun importApiWallet(key: String, prompt: VaultPrompt, verify: suspend (address: String) -> Unit): String = error("Not used")
  override suspend fun importAptosApiWallet(key: String, prompt: VaultPrompt, verify: suspend (Account) -> Unit): String = error("Not used")
  override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String = error("Not used")
  override suspend fun exportApiWallet(prompt: VaultPrompt): String = error("Not used")
  override suspend fun removeOwner(prompt: VaultPrompt) = error("Not used")
  override suspend fun removeApiWallet(prompt: VaultPrompt) = error("Not used")
  override fun lock() = Unit
  override suspend fun <T> withOwnerAccount(prompt: VaultPrompt, block: suspend (Account) -> T): T = error("Not used")
  override suspend fun <T> withApiAccount(prompt: VaultPrompt, block: suspend (Account) -> T): T = error("Not used")
}

private fun MockRequestHandleScope.respondJson(
  content: String,
  status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
  content = content,
  status = status,
  headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)
