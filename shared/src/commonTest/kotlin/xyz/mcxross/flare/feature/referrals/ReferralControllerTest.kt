package xyz.mcxross.flare.feature.referrals

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mcxross.flare.security.VaultPrompt

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReferralControllerTest {
  @Test
  fun suggestedCodeCanBeValidatedAndRemoved() = runTest {
    val repo = FakeReferralRepository(suggested = "FLARE", validateResult = { true })
    val controller = visibleController(repo)

    assertEquals("FLARE", controller.state.value.input)
    assertTrue(controller.state.value.editable)
    advanceTimeBy(300); runCurrent()
    assertTrue(controller.state.value.canApply)

    controller.change("")
    assertEquals("", controller.state.value.input)
    assertTrue(controller.state.value.canContinue)
    assertFalse(controller.state.value.canApply)
  }

  @Test
  fun clearingSuggestedCodeDoesNotRestoreItOnRefresh() = runTest {
    val repo = FakeReferralRepository(suggested = "FLARE")
    val controller = visibleController(repo)
    controller.change("")
    advanceTimeBy(30_000); runCurrent()

    assertEquals("", controller.state.value.input)
    assertEquals(2, repo.programCalls)
  }

  @Test
  fun typingCancelsLateValidationResponse() = runTest {
    val firstValidation = CompletableDeferred<Boolean>()
    val repo = FakeReferralRepository(validateResult = { code ->
      if (code == "OLD") withContext(NonCancellable) { firstValidation.await() } else true
    })
    val controller = visibleController(repo)
    controller.change("OLD")
    advanceTimeBy(300); runCurrent()
    controller.change("NEW")
    firstValidation.complete(false)
    runCurrent()
    assertFalse(controller.state.value.valid)
    advanceTimeBy(300); runCurrent()
    assertTrue(controller.state.value.valid)
    assertTrue(controller.state.value.canApply)
    assertEquals("NEW", controller.state.value.input)
  }

  @Test
  fun changingOwnerAndProfileDiscardsStaleReads() = runTest {
    val oldStatus = CompletableDeferred<ReferralStatus>()
    var ownerBReads = 0
    val repo = FakeReferralRepository(statusResult = { owner ->
      if (owner == "owner-a") withContext(NonCancellable) { oldStatus.await() }
      else if (++ownerBReads == 1) ReferralStatus(owner, ReferralEligibility.ELIGIBLE)
      else ReferralStatus(owner, ReferralEligibility.BOUND, "CURRENT")
    })
    val controller = ReferralController(repo, backgroundScope)
    controller.context("profile-a", "owner-a")
    controller.setVisible(true)
    runCurrent()
    controller.context("profile-b", "owner-b")
    runCurrent()
    controller.context("profile-c", "owner-b")
    runCurrent()
    oldStatus.complete(ReferralStatus("owner-a", ReferralEligibility.BOUND, "STALE"))
    runCurrent()

    assertEquals("owner-b", repo.statusOwners.last())
    assertEquals(ReferralEligibility.BOUND, controller.state.value.eligibility)
    assertEquals("CURRENT", controller.state.value.appliedCode)
  }

  @Test
  fun hiddenScreenStopsFurtherReads() = runTest {
    val repo = FakeReferralRepository()
    val controller = visibleController(repo)
    controller.setVisible(false)
    advanceTimeBy(60_000); runCurrent()

    assertEquals(1, repo.programCalls)
    assertEquals(1, repo.statusOwners.size)
  }

  @Test
  fun invalidAndUnavailableValidationNeverEnableApply() = runTest {
    val invalidRepo = FakeReferralRepository(suggested = "BAD", validateResult = { false })
    val invalid = visibleController(invalidRepo)
    advanceTimeBy(300); runCurrent()
    assertFalse(invalid.state.value.canApply)

    var attempts = 0
    val unavailableRepo = FakeReferralRepository(suggested = "MAYBE", validateResult = {
      attempts++
      error("offline")
    })
    val unavailable = visibleController(unavailableRepo)
    advanceTimeBy(300); runCurrent()
    advanceTimeBy(1_000); runCurrent()
    advanceTimeBy(2_000); runCurrent()
    advanceTimeBy(4_000); runCurrent()
    assertEquals(4, attempts)
    assertFalse(unavailable.state.value.canApply)
    assertEquals("Referrals unavailable", unavailable.state.value.error)
  }

  @Test
  fun disabledProgramClearsSuggestionAndAllowsContinuation() = runTest {
    val repo = FakeReferralRepository(programResult = ReferralProgram(enabled = false, suggestedCode = "OLD"))
    val controller = visibleController(repo)

    assertFalse(controller.state.value.enabled)
    assertEquals("", controller.state.value.input)
    assertEquals(ReferralEligibility.ELIGIBLE, controller.state.value.eligibility)
    assertTrue(controller.state.value.canContinue)
  }

  @Test
  fun pendingStatusPollsWithoutApplyingUntilExplicitApply() = runTest {
    val repo = FakeReferralRepository(
      pendingCodeResult = "PENDING",
      statusResult = { ReferralStatus(it, ReferralEligibility.PENDING, "PENDING") },
      applyResult = { owner, _, _ -> ReferralStatus(owner, ReferralEligibility.PENDING, "PENDING") },
    )
    val controller = visibleController(repo)

    assertTrue(controller.state.value.pending)
    assertEquals("PENDING", controller.state.value.input)
    assertFalse(controller.state.value.canContinue)
    advanceTimeBy(30_000); runCurrent()
    assertEquals(0, repo.applyCalls)
    assertTrue(repo.statusOwners.size >= 2)

    controller.apply(VaultPrompt("Apply referral", "Confirm your identity"))
    assertEquals(1, repo.applyCalls)
  }

  @Test
  fun hidingAndShowingDuringApplyResetsAndReconcilesWithoutApplyingAgain() = runTest {
    val applyGate = CompletableDeferred<Unit>()
    var bound = false
    val repo = FakeReferralRepository(
      suggested = "FLARE",
      statusResult = { owner ->
        if (bound) ReferralStatus(owner, ReferralEligibility.BOUND, "FLARE")
        else ReferralStatus(owner, ReferralEligibility.ELIGIBLE)
      },
      validateResult = { true },
      applyResult = { owner, _, _ ->
        applyGate.await()
        bound = true
        ReferralStatus(owner, ReferralEligibility.BOUND, "FLARE")
      },
    )
    val controller = visibleController(repo)
    advanceTimeBy(300); runCurrent()
    assertTrue(controller.state.value.canApply)

    val applying = async { controller.apply(VaultPrompt("Apply referral", "Confirm your identity")) }
    runCurrent()
    assertTrue(controller.state.value.applying)

    controller.setVisible(false)
    controller.setVisible(true)
    runCurrent()
    applyGate.complete(Unit)
    runCurrent()
    runCatching { applying.await() }
    runCurrent()

    assertFalse(controller.state.value.applying)
    assertEquals(ReferralEligibility.BOUND, controller.state.value.eligibility)
    assertEquals("FLARE", controller.state.value.appliedCode)
    assertEquals(1, repo.applyCalls)
  }

  @Test
  fun pendingStatusWithVerifiedTradingAccountAllowsContinuation() = runTest {
    val repo = FakeReferralRepository(statusResult = {
      ReferralStatus(it, ReferralEligibility.PENDING, "FLARE", tradingAccount = "account-1")
    })
    val controller = visibleController(repo)

    assertTrue(controller.state.value.pending)
    assertEquals("account-1", controller.state.value.tradingAccount)
    assertTrue(controller.state.value.canContinue)
  }

  private fun TestScope.visibleController(repo: FakeReferralRepository): ReferralController {
    val controller = ReferralController(repo, backgroundScope)
    controller.context("profile", "owner")
    controller.setVisible(true)
    runCurrent()
    return controller
  }

  private class FakeReferralRepository(
    private val programResult: ReferralProgram = ReferralProgram(enabled = true, suggestedCode = ""),
    private val statusResult: suspend (String) -> ReferralStatus = {
      ReferralStatus(it, ReferralEligibility.ELIGIBLE)
    },
    private val suggested: String = programResult.suggestedCode,
    private val pendingCodeResult: String? = null,
    private val validateResult: suspend (String) -> Boolean = { true },
    private val applyResult: suspend (String, String, VaultPrompt) -> ReferralStatus = { owner, code, _ ->
      ReferralStatus(owner, ReferralEligibility.BOUND, code)
    },
  ) : ReferralRepository {
    var programCalls = 0
    var applyCalls = 0
    val statusOwners = mutableListOf<String>()

    override suspend fun program(): ReferralProgram {
      programCalls++
      return programResult.copy(suggestedCode = suggested)
    }

    override suspend fun status(owner: String): ReferralStatus {
      statusOwners += owner
      return statusResult(owner)
    }

    override suspend fun pendingCode(owner: String): String? = pendingCodeResult

    override suspend fun validate(code: String): Boolean = validateResult(code)

    override suspend fun apply(owner: String, code: String, prompt: VaultPrompt): ReferralStatus {
      applyCalls++
      return applyResult(owner, code, prompt)
    }
  }
}
