package xyz.mcxross.flare.venue.decibel

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.dsl.module
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.feature.referrals.ReferralIntentStore
import xyz.mcxross.flare.feature.referrals.referralStorageModule
import xyz.mcxross.flare.feature.referrals.ReferralEligibility
import xyz.mcxross.flare.feature.referrals.ReferralProgram
import xyz.mcxross.flare.feature.referrals.ReferralRepository
import xyz.mcxross.flare.feature.referrals.ReferralStatus
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences

/** The sole binding for Decibel's optional affiliate integration. */
internal fun decibelReferralsModule() = module {
  includes(referralStorageModule())
  single<ReferralRepository> { DecibelReferrals(get(), get(), get(), get(), get(), get(), get()) }
}

class DecibelReferrals(
  private val client: HttpClient,
  private val config: DecibelRuntimeConfig,
  private val runtime: FlareRuntimeConfig,
  private val sessions: AptosSessionRepository,
  private val wallets: AptosSigningWallet,
  private val preferences: AppPreferences,
  private val intents: ReferralIntentStore,
) : ReferralRepository {
  // Serializes explicit submissions from onboarding and Settings on this device.
  private val mutex = Mutex()
  private val base get() = config.workerBaseUrl.trimEnd('/') + "/referrals"

  override suspend fun program(): ReferralProgram {
    val response = withTimeout(5_000) { client.get("$base/config") { header(HttpHeaders.Origin, runtime.appOrigin) } }
    check(response.status.isSuccess()) { "Referrals unavailable" }
    val value = response.body<ProgramResponse>()
    return ReferralProgram(value.enabled, if (value.enabled) value.suggestedCode.orEmpty() else "")
  }

  override suspend fun validate(code: String): Boolean {
    val normalized = normalizeCode(code)
    val token = sessions.accessToken()
    val response = withTimeout(5_000) { client.get("$base/code/$normalized") {
      header(HttpHeaders.Origin, runtime.appOrigin)
      header(HttpHeaders.Authorization, "Bearer $token")
    } }
    check(response.status.isSuccess()) { "Referrals unavailable" }
    val value = response.body<CodeResponse>()
    check(value.code == normalized) { "Referrals unavailable" }
    return value.valid && value.active
  }

  override suspend fun status(owner: String): ReferralStatus = asOwner(owner, ReadPrompt) {
    val token = sessions.accessToken()
    val response = withTimeout(15_000) { client.get("$base/status") {
      header(HttpHeaders.Origin, runtime.appOrigin)
      header(HttpHeaders.Authorization, "Bearer $token")
    } }
    check(response.status.isSuccess()) { "Referrals unavailable" }
    val live = response.body<StatusResponse>().status(owner)
    val attempt = intents.code(owner)
    when {
      live.eligibility == ReferralEligibility.BOUND -> { markOpened(owner); intents.save(owner, null); live }
      attempt != null && live.eligibility != ReferralEligibility.PENDING ->
        ReferralStatus(owner, ReferralEligibility.PENDING, attempt, live.tradingAccount)
      else -> live
    }
  }

  override suspend fun pendingCode(owner: String): String? = intents.code(owner)

  override suspend fun apply(owner: String, code: String, prompt: VaultPrompt): ReferralStatus = mutex.withLock {
    val normalized = normalizeCode(code)
    asOwner(owner, prompt) {
      val prior = intents.code(owner)
      check(prior == null || prior == normalized) { "Referral confirmation pending" }
      val token = sessions.accessToken()
      // The public intent survives transport loss or a process restart. It is not a success flag.
      intents.save(owner, normalized)
      val response = withTimeout(20_000) { client.post("$base/redeem") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Origin, runtime.appOrigin)
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(ApplyRequest(normalized))
      } }
      if (!response.status.isSuccess()) {
        if (response.status.value in setOf(400, 401, 403, 404, 422, 429)) intents.save(owner, null)
        error(when (response.status.value) {
          400, 422 -> "Code unavailable"
          409 -> "This account cannot apply that code"
          else -> "Referrals unavailable"
        })
      }
      response.body<StatusResponse>().status(owner).also {
        check(it.code == normalized) { "Referral confirmation pending" }
        if (it.eligibility == ReferralEligibility.BOUND) { markOpened(owner); intents.save(owner, null) }
      }
    }
  }

  private suspend fun <T> asOwner(owner: String, prompt: VaultPrompt, action: suspend () -> T): T {
    val saved = preferences.values.first()
    val profile = saved.activeProfileId
    check(saved.ownerAddress?.sameAptosAddress(owner) == true) { "The selected wallet changed" }
    wallets.requireAuthorization(wallets.authorizationGeneration)
    val session = sessions.ensureOwner(null, prompt)
    currentCoroutineContext().ensureActive()
    check(session.role == xyz.mcxross.flare.data.SessionRole.OWNER && session.walletAddress?.sameAptosAddress(owner) == true) {
      "The selected wallet changed"
    }
    val generation = wallets.authorizationGeneration
    return sessions.bind(session, flow {
      wallets.requireAuthorization(generation)
      val current = preferences.values.first()
      check(current.activeProfileId == profile && current.ownerAddress?.sameAptosAddress(owner) == true) { "The selected wallet changed" }
      val result = action()
      wallets.requireAuthorization(generation)
      val latest = preferences.values.first()
      check(latest.activeProfileId == profile && latest.ownerAddress?.sameAptosAddress(owner) == true) { "The selected wallet changed" }
      emit(result)
    }).first()
  }

  private suspend fun markOpened(owner: String) {
    preferences.values.first().profiles.filter { it.ownerAddress?.sameAptosAddress(owner) == true }
      .forEach { preferences.setTradingAccountOpened(it.id) }
  }

  private companion object { val ReadPrompt = VaultPrompt("Referral code", "Confirm your identity") }
}

private fun normalizeCode(value: String): String = value.trim().also {
  require(Regex("[A-Za-z0-9_-]{1,128}").matches(it)) { "Code unavailable" }
}
@Serializable private data class ProgramResponse(val enabled: Boolean, @SerialName("suggested_code") val suggestedCode: String? = null)
@Serializable private data class CodeResponse(@SerialName("referral_code") val code: String, @SerialName("is_valid") val valid: Boolean, @SerialName("is_active") val active: Boolean)
@Serializable private data class ApplyRequest(val code: String)
@Serializable private data class StatusResponse(
  val account: String,
  val status: String,
  @SerialName("referral_code") val code: String? = null,
  @SerialName("trading_account") val tradingAccount: String? = null,
) {
  fun status(owner: String): ReferralStatus {
    check(account.sameAptosAddress(owner)) { "The selected wallet changed" }
    val eligibility = ReferralEligibility.entries.firstOrNull { it.name.equals(status, true) }
      ?: error("Referrals unavailable")
    if (eligibility in setOf(ReferralEligibility.BOUND, ReferralEligibility.PENDING)) check(!code.isNullOrBlank()) { "Referrals unavailable" }
    return ReferralStatus(owner, eligibility, code, tradingAccount)
  }
}
