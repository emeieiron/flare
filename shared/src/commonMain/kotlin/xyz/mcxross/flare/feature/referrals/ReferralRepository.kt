package xyz.mcxross.flare.feature.referrals

import xyz.mcxross.flare.security.VaultPrompt

data class ReferralProgram(val enabled: Boolean = false, val suggestedCode: String = "")
enum class ReferralEligibility { ELIGIBLE, BOUND, INELIGIBLE, PENDING }
data class ReferralStatus(
  val owner: String,
  val eligibility: ReferralEligibility,
  val code: String? = null,
  val tradingAccount: String? = null,
)

/** Optional venue integration. Removing its binding leaves account setup and trading independent. */
interface ReferralRepository {
  suspend fun program(): ReferralProgram
  suspend fun status(owner: String): ReferralStatus
  suspend fun pendingCode(owner: String): String? = null
  suspend fun validate(code: String): Boolean
  suspend fun apply(owner: String, code: String, prompt: VaultPrompt): ReferralStatus
}
