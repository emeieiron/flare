package xyz.mcxross.flare.feature.referrals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import xyz.mcxross.flare.core.ReadOperation
import xyz.mcxross.flare.core.ReadRecoveryDiagnostics
import xyz.mcxross.flare.core.readBackoffMs
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled

data class ReferralUiState(
  val profileId: String? = null,
  val owner: String? = null,
  val suggestedCode: String = "",
  val tradingAccount: String? = null,
  val enabled: Boolean = false,
  val loading: Boolean = false,
  val input: String = "",
  val eligibility: ReferralEligibility? = null,
  val appliedCode: String? = null,
  val validating: Boolean = false,
  val valid: Boolean = false,
  val applying: Boolean = false,
  val error: String? = null,
) {
  val pending: Boolean
    get() = eligibility == ReferralEligibility.PENDING

  val editable: Boolean
    get() = enabled && eligibility == ReferralEligibility.ELIGIBLE && !applying

  val canApply: Boolean
    get() = editable && input.isNotBlank() && valid && !validating

  val canContinue: Boolean
    get() =
      !applying &&
        when {
          pending -> tradingAccount != null
          !enabled || input.isBlank() -> true
          eligibility == ReferralEligibility.BOUND ||
            eligibility == ReferralEligibility.INELIGIBLE -> true
          else -> canApply
        }
}

/** Shared form policy; background work is restricted to reads and stops with the owning screen. */
class ReferralController(
  private val repository: ReferralRepository?,
  private val scope: CoroutineScope,
) {
  private val mutable = MutableStateFlow(ReferralUiState())
  val state = mutable.asStateFlow()
  private var owner: String? = null
  private var profile: String? = null
  private var visible = false
  private var revision = 0L
  private var inputRevision = 0L
  private var contextRevision = 0L
  private var touched = false
  private var reads: Job? = null
  private var validation: Job? = null

  fun context(profileId: String, wallet: String?) {
    if (profile == profileId && owner == wallet) return
    profile = profileId
    owner = wallet
    revision++
    contextRevision++
    inputRevision++
    reads?.cancel()
    validation?.cancel()
    touched = false
    mutable.value = ReferralUiState(profileId = profileId, owner = wallet)
    if (visible) startReads()
  }

  fun setVisible(value: Boolean) {
    if (visible == value) return
    visible = value
    revision++
    reads?.cancel()
    validation?.cancel()
    if (value) startReads()
  }

  fun change(value: String) {
    if (!state.value.editable) return
    touched = true
    inputRevision++
    validation?.cancel()
    mutable.update {
      it.copy(input = value.take(128), valid = false, validating = false, error = null)
    }
    validate()
  }

  private fun validate() {
    val source = repository ?: return
    val code = state.value.input.trim()
    val version = revision
    val inputVersion = inputRevision
    if (code.isEmpty() || !visible || !state.value.editable) return
    validation?.cancel()
    validation = scope.launch {
      delay(300)
      mutable.update { it.copy(validating = true) }
      var failures = 0
      while (currentCoroutineContext().isActive && visible) {
        val result = runSuspendCatching { withTimeout(5_000) { source.validate(code) } }
        if (version != revision || inputVersion != inputRevision) return@launch
        currentCoroutineContext().ensureActive()
        result.getOrNull()?.let { valid ->
          ReadRecoveryDiagnostics.recovered(ReadOperation.REFERRALS)
          mutable.update {
            it.copy(
              valid = valid,
              validating = false,
              error = if (valid) null else "Code unavailable",
            )
          }
          return@launch
        }
        failures++
        ReadRecoveryDiagnostics.failed(ReadOperation.REFERRALS, failures, result.exceptionOrNull())
        if (failures >= 3)
          mutable.update { it.copy(validating = false, error = "Referrals unavailable") }
        delay(readBackoffMs(failures))
      }
    }
  }

  private fun startReads() {
    val source = repository ?: return
    val wallet = owner ?: return
    val version = revision
    reads = scope.launch {
      mutable.update { it.copy(loading = true) }
      var failures = 0
      while (visible && currentCoroutineContext().isActive) {
        val result = runSuspendCatching {
          val program = withTimeout(5_000) { source.program() }
          val status = withTimeout(15_000) { source.status(wallet) }
          program to status
        }
        if (version != revision || owner != wallet) return@launch
        currentCoroutineContext().ensureActive()
        result
          .onSuccess { (program, status) ->
            failures = 0
            ReadRecoveryDiagnostics.recovered(ReadOperation.REFERRALS)
            val prior = state.value
            val input =
              when {
                status.eligibility == ReferralEligibility.PENDING -> status.code.orEmpty()
                !program.enabled -> ""
                !touched -> program.suggestedCode
                else -> prior.input
              }
            mutable.update {
              it.copy(
                enabled = program.enabled,
                loading = false,
                input = input,
                suggestedCode = program.suggestedCode,
                tradingAccount = status.tradingAccount,
                eligibility = status.eligibility,
                appliedCode = status.code,
                error = null,
                valid = prior.valid && prior.input == input,
              )
            }
            if (input != prior.input || !state.value.valid && validation?.isActive != true)
              validate()
          }
          .onFailure { error ->
            failures++
            ReadRecoveryDiagnostics.failed(ReadOperation.REFERRALS, failures, error)
            if (failures >= 3)
              mutable.update { it.copy(loading = false, error = "Referrals unavailable") }
          }
        delay(if (failures == 0) 30_000 else readBackoffMs(failures))
      }
    }
  }

  /**
   * Explicit user action only; uncertain outcomes stay pending and are reconciled through reads.
   */
  suspend fun apply(prompt: VaultPrompt): ReferralStatus {
    val source = checkNotNull(repository) { "Referrals unavailable" }
    val wallet = checkNotNull(owner) { "Use your owner wallet" }
    val version = revision
    val contextVersion = contextRevision
    val current = state.value
    check(!current.applying && (current.canApply || current.pending)) { "Check your referral code" }
    val code = current.input.trim()
    validation?.cancel()
    reads?.cancel()
    mutable.update { it.copy(applying = true, error = null) }
    try {
      val result = source.apply(wallet, code, prompt)
      currentCoroutineContext().ensureActive()
      check(contextRevision == contextVersion) { "The selected wallet changed" }
      if (revision == version) {
        mutable.update {
          it.copy(
            eligibility = result.eligibility,
            appliedCode = result.code,
            tradingAccount = result.tradingAccount,
            error =
              if (result.eligibility == ReferralEligibility.PENDING) "Referral confirmation pending"
              else null,
          )
        }
      }
      return result
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      if (owner == wallet && revision == version && !error.isAuthorizationCancelled())
        mutable.update {
          it.copy(error = actionFailure(error.message, "Your referral code wasn’t applied."))
        }
      throw error
    } finally {
      if (contextRevision == contextVersion) {
        mutable.update { it.copy(applying = false) }
        if (visible) {
          reads?.cancel()
          startReads()
        }
      }
    }
  }
}
