package xyz.mcxross.flare.feature.portfolio

import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.SelfPayOffer

@OptIn(ExperimentalCoroutinesApi::class)
class FundingSwapControllerTest {
  @Test fun aStalledQuoteReadTimesOutAndRecoversAutomatically() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.quoteDelayMs = 60_000
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin(""); f.controller.changeInput("1")
    advanceTimeBy(20_601); runCurrent()
    assertEquals(1, f.repo.requests.size)
    f.repo.quoteDelayMs = 0
    advanceTimeBy(2_001); runCurrent()
    assertEquals(2, f.repo.requests.size)
    assertNotNull(f.controller.state.value.quote)
    assertFalse(f.controller.state.value.quoting)
  }

  @Test fun quoteFailuresRecoverAutomaticallyWithoutExposingProviderErrors() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.quoteFailuresRemaining = 2
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin(""); f.controller.changeInput("1")
    advanceTimeBy(601); runCurrent()
    assertEquals(1, f.repo.requests.size)
    assertTrue(f.controller.state.value.quoting)
    assertNull(f.controller.state.value.error)
    advanceTimeBy(2_001); runCurrent()
    assertEquals(2, f.repo.requests.size)
    advanceTimeBy(4_001); runCurrent()
    assertEquals(3, f.repo.requests.size)
    assertNotNull(f.controller.state.value.quote)
    assertFalse(f.controller.state.value.quoting)
    assertTrue(f.repo.executions.isEmpty())
  }

  @Test fun editingOrHidingTheFormCancelsAScheduledRetry() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.quoteFailuresRemaining = 1
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin(""); f.controller.changeInput("1")
    advanceTimeBy(601); runCurrent()
    f.controller.changeInput("2")
    advanceTimeBy(601); runCurrent()
    advanceTimeBy(2_001); runCurrent()
    assertEquals(listOf("100000000", "200000000"), f.repo.requests.map { it.amount })
    f.repo.quoteFailuresRemaining = 1
    f.controller.changeInput("3"); advanceTimeBy(601); runCurrent()
    f.controller.setVisible(false)
    advanceTimeBy(10_000); runCurrent()
    assertEquals(3, f.repo.requests.size)
    assertFalse(f.controller.state.value.quoting)
  }

  @Test fun insufficientFundsDoNotStartAnAutomaticQuoteRetryLoop() = runTest {
    val f = Fixture(backgroundScope)
    f.controller.open(CONTEXT); runCurrent()
    f.repo.capacity = f.repo.capacity.copy(inputBalance = 0uL)
    f.controller.begin(""); f.controller.changeInput("1")
    advanceTimeBy(30_000); runCurrent()
    assertFalse(f.controller.state.value.quoting)
    assertNotNull(f.controller.state.value.error)
    assertTrue(f.repo.requests.isEmpty())
  }

  @Test fun typingDebouncesTheRequestAndKeepsTheEditorInPlace() = runTest {
    val f = Fixture(backgroundScope)
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("")
    f.controller.changeInput("1"); advanceTimeBy(300)
    f.controller.changeInput("1.2"); advanceTimeBy(599); runCurrent()
    assertTrue(f.repo.requests.isEmpty())
    f.controller.execute(); runCurrent()
    assertTrue(f.repo.executions.isEmpty())
    advanceTimeBy(2); runCurrent()
    assertEquals("120000000", f.repo.requests.single().amount)
    assertEquals("1.2", f.controller.state.value.inputAmount)
    assertEquals(FundingSwapStep.SWAP, f.controller.state.value.step)
    assertNotNull(f.controller.state.value.quote)
  }

  @Test fun typingDuringAnInFlightRequestDiscardsTheOlderResponse() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.quoteGate = CompletableDeferred()
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("")
    f.controller.changeInput("1"); advanceTimeBy(601); runCurrent()
    assertTrue(f.controller.state.value.quoting)
    assertFalse(f.controller.state.value.working)
    f.controller.changeInput("2"); advanceTimeBy(601); runCurrent()
    f.repo.quoteGate!!.complete(Unit); runCurrent()
    assertEquals(listOf("100000000", "200000000"), f.repo.requests.map { it.amount })
    assertEquals("200000000", f.controller.state.value.quote?.request?.amount)
    assertEquals("2", f.controller.state.value.inputAmount)
  }

  @Test fun automaticQuotesPauseWhenHiddenAndResumeWithFreshInput() = runTest {
    val f = Fixture(backgroundScope)
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin(""); f.controller.changeInput("1")
    f.controller.setVisible(false)
    advanceTimeBy(1_000); runCurrent()
    assertTrue(f.repo.requests.isEmpty())
    f.controller.setVisible(true); runCurrent()
    assertEquals(1, f.repo.requests.size)
    f.controller.setVisible(false)
    advanceTimeBy(30_001); runCurrent()
    assertEquals(1, f.repo.requests.size)
    f.controller.execute(); runCurrent()
    assertTrue(f.repo.executions.isEmpty())
  }

  @Test fun zeroUsdcOffersAnAmountChoiceAndMaximumLeavesFeesForBothSteps() = runTest {
    val f = Fixture(backgroundScope)
    f.controller.open(CONTEXT); runCurrent()
    assertTrue(f.controller.state.value.canSwap)
    f.controller.begin("")
    assertEquals(FundingSwapStep.SWAP, f.controller.state.value.step)
    assertTrue(f.repo.requests.isEmpty())
    f.controller.useMaximum()
    assertEquals("9.9", f.controller.state.value.inputAmount)
    advanceTimeBy(601); runCurrent()
    assertEquals("990000000", f.repo.requests.single().amount)
    assertEquals(SwapAmountKind.EXACT_INPUT, f.repo.requests.single().kind)
  }

  @Test fun partialUsdcQuotesOnlyTheExactShortfall() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.capacity = f.repo.capacity.copy(outputBalance = 2_000_001uL)
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("10.000002"); advanceTimeBy(601); runCurrent()
    assertEquals(SwapAmountKind.EXACT_OUTPUT, f.repo.requests.single().kind)
    assertEquals("8000001", f.repo.requests.single().amount)
    assertEquals(FundingSwapStep.SWAP, f.controller.state.value.step)
  }

  @Test fun aFailedBalanceReadAndGasOnlyFundsNeverPretendToBeSwappable() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.balanceFails = true
    f.controller.open(CONTEXT); runCurrent()
    assertNull(f.controller.state.value.capacity)
    assertNotNull(f.controller.state.value.balanceError)
    assertFalse(f.controller.state.value.canSwap)
    f.repo.balanceFails = false
    f.repo.capacity = f.repo.capacity.copy(inputBalance = f.repo.capacity.networkFeeReserve)
    f.controller.refresh(); runCurrent()
    assertFalse(f.controller.state.value.canSwap)
    f.controller.begin("10"); advanceTimeBy(601); runCurrent()
    assertTrue(f.repo.requests.isEmpty())
  }

  @Test fun overBudgetAndOverPrecisionAmountsNeverReachTheProvider() = runTest {
    val f = Fixture(backgroundScope)
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("")
    for (amount in listOf("10", "0.000000001", "1..2")) {
      f.controller.changeInput(amount); advanceTimeBy(601); runCurrent()
      assertTrue(f.repo.requests.isEmpty())
      assertNull(f.controller.state.value.quote)
    }
  }

  @Test fun quotesRefreshAutomaticallyBeforeExpiryWithoutExecuting() = runTest {
    val f = Fixture(backgroundScope)
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("10"); advanceTimeBy(601); runCurrent()
    val firstId = f.controller.state.value.quote?.id
    f.repo.now += 27_001
    advanceTimeBy(27_001); runCurrent()
    assertFalse(f.controller.state.value.quoteExpired)
    assertEquals(2, f.repo.requests.size)
    assertNotEquals(firstId, f.controller.state.value.quote?.id)
    assertTrue(f.repo.executions.isEmpty())
  }

  @Test fun anOldQuoteCannotExecuteWhileItsAutomaticReplacementIsLoading() = runTest {
    val f = Fixture(backgroundScope)
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("10"); advanceTimeBy(601); runCurrent()
    f.repo.quoteGate = CompletableDeferred()
    f.repo.now += 27_001
    advanceTimeBy(27_001); runCurrent()
    assertTrue(f.controller.state.value.quoting)
    assertNotNull(f.controller.state.value.quote)
    f.controller.execute(); runCurrent()
    assertTrue(f.repo.executions.isEmpty())
    f.repo.quoteGate!!.complete(Unit); runCurrent()
    assertFalse(f.controller.state.value.quoting)
  }

  @Test fun aConfirmedSwapReturnsToTransferWithTheOriginalAmountAndNeverDeposits() = runTest {
    val f = Fixture(backgroundScope)
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("10"); advanceTimeBy(601); runCurrent()
    f.controller.execute(); runCurrent()
    assertEquals(listOf("10"), f.ready)
    assertNull(f.controller.state.value.step)
    assertTrue(f.controller.state.value.completed)
    assertEquals(10_000_000uL, f.controller.state.value.capacity?.outputBalance)
    assertEquals(listOf(FeePayment.SPONSORED), f.repo.executions)
  }

  @Test fun sponsorRejectionWaitsForExplicitSelfPay() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.result = ActionState.Failed("Sponsor unavailable", definitelyNotSubmitted = true, selfPay = SelfPayOffer(100uL))
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("10"); advanceTimeBy(601); runCurrent()
    f.controller.execute(); runCurrent()
    assertTrue(f.controller.state.value.selfPay)
    assertEquals(listOf(FeePayment.SPONSORED), f.repo.executions)
    assertTrue(f.ready.isEmpty())
    f.repo.result = ActionState.Committed("hash")
    f.controller.execute(selfPay = true); runCurrent()
    assertEquals(listOf(FeePayment.SPONSORED, FeePayment.SELF_PAY), f.repo.executions)
    assertEquals(listOf("10"), f.ready)
  }

  @Test fun automaticQuoteRenewalPreservesTheExplicitGasFallback() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.result = ActionState.Failed("Sponsor unavailable", definitelyNotSubmitted = true, selfPay = SelfPayOffer(100uL))
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin(""); f.controller.changeInput("2"); advanceTimeBy(601); runCurrent()
    f.controller.execute(); runCurrent()
    val rejectedQuote = f.controller.state.value.quote!!.id
    f.repo.now += 27_001
    advanceTimeBy(27_001); runCurrent()
    assertTrue(f.controller.state.value.selfPay)
    assertNotEquals(rejectedQuote, f.controller.state.value.quote!!.id)
    assertEquals(listOf(FeePayment.SPONSORED), f.repo.executions)
    f.repo.result = ActionState.Committed("hash")
    f.repo.executionGate = CompletableDeferred()
    f.controller.execute(selfPay = true); runCurrent()
    assertTrue(f.controller.state.value.working)
    assertTrue(f.controller.state.value.selfPay)
    assertEquals(ActionState.Simulating, f.controller.state.value.transaction)
    f.repo.executionGate!!.complete(Unit); runCurrent()
    assertEquals(listOf(FeePayment.SPONSORED, FeePayment.SELF_PAY), f.repo.executions)
    assertEquals(listOf(rejectedQuote, "quote-2"), f.repo.executedQuoteIds)
    assertTrue(f.controller.state.value.completed)
  }

  @Test fun uncertainSubmissionSurvivesClosingAndOnlyChecksStatusOnReturn() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.result = ActionState.Failed("Response lost", reference = "pending")
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("10"); advanceTimeBy(601); runCurrent()
    f.controller.execute(); runCurrent()
    assertEquals(FundingSwapStep.PENDING, f.controller.state.value.step)
    f.controller.close(); f.controller.open(CONTEXT); runCurrent()
    assertEquals(FundingSwapStep.PENDING, f.controller.state.value.step)
    f.controller.execute(selfPay = true); f.controller.begin("10"); advanceTimeBy(601); runCurrent()
    assertEquals(1, f.repo.executions.size)
    f.repo.reconciled = SwapOperationStatus.COMMITTED
    f.controller.reconcile(); runCurrent()
    assertTrue(f.controller.state.value.completed)
    assertEquals(1, f.repo.executions.size)
  }

  @Test fun aQuoteFromThePreviousProfileNeverAppearsOrExecutes() = runTest {
    val f = Fixture(backgroundScope)
    f.repo.quoteGate = CompletableDeferred()
    f.controller.open(CONTEXT); runCurrent()
    f.controller.begin("10"); advanceTimeBy(601); runCurrent()
    assertTrue(f.controller.state.value.quoting)
    assertFalse(f.controller.state.value.working)
    f.controller.open(CONTEXT.copy(profileId = "other", owner = "other")); runCurrent()
    f.repo.quoteGate!!.complete(Unit); runCurrent()
    assertNull(f.controller.state.value.quote)
    f.controller.execute(); runCurrent()
    assertTrue(f.repo.executions.isEmpty())
    assertTrue(f.ready.isEmpty())
  }

  @Test fun walletChangesAreRecheckedBeforeQuotingAndBeforeSigning() = runTest {
    val f = Fixture(backgroundScope)
    f.controller.open(CONTEXT); runCurrent()
    f.repo.capacity = f.repo.capacity.copy(outputBalance = 3_000_000uL)
    f.controller.begin("10"); advanceTimeBy(601); runCurrent()
    assertEquals("7000000", f.repo.requests.single().amount)
    f.repo.capacity = f.repo.capacity.copy(inputBalance = 0uL)
    f.controller.execute(); runCurrent()
    assertTrue(f.repo.executions.isEmpty())
  }

  private class Fixture(scope: CoroutineScope) {
    val repo = FakeSwaps()
    val ready = mutableListOf<String>()
    val controller = FundingSwapController(repo, scope, { ready += it }, now = { repo.now })
  }
}

private val CONTEXT = FundingSwapContext("profile", "owner", "account")
private val INPUT = SwapAsset("chain", "apt", 8)
private val OUTPUT = SwapAsset("chain", "usdc", 6)

private class FakeSwaps : SwapRepository {
  var now = 100_000L
  var capacity = SwapFundingCapacity("owner", INPUT, OUTPUT, "APT", "USDC", 1_000_000_000uL, 0uL, 10_000_000uL)
  var balanceFails = false
  var quoteGate: CompletableDeferred<Unit>? = null
  var quoteFailuresRemaining = 0
  var quoteDelayMs = 0L
  var executionGate: CompletableDeferred<Unit>? = null
  var result: ActionState = ActionState.Committed("hash")
  var reconciled: SwapOperationStatus? = null
  val requests = mutableListOf<SwapRequest>()
  val executions = mutableListOf<FeePayment>()
  val executedQuoteIds = mutableListOf<String>()
  override val operations = MutableStateFlow<List<SwapOperation>>(emptyList())
  private lateinit var quoted: SwapQuote
  override suspend fun fundingCapacity(owner: String): SwapFundingCapacity {
    check(!balanceFails)
    return capacity.copy(owner = owner)
  }
  override suspend fun quote(request: SwapRequest): SwapQuote {
    requests += request
    delay(quoteDelayMs)
    if (quoteFailuresRemaining > 0) { quoteFailuresRemaining--; error("Invalid swap quote expiry") }
    quoteGate?.let { withContext(NonCancellable) { it.await() } }
    val input = if (request.kind == SwapAmountKind.EXACT_INPUT) request.amount.toULong() else request.amount.toULong() * 10uL
    val output = if (request.kind == SwapAmountKind.EXACT_OUTPUT) request.amount.toULong() else request.amount.toULong() / 10uL
    return SwapQuote("quote-${requests.size}", "fake", request, input.toString(), output.toString(), input.toString(),
      output.toString(), emptyList(), now + 30_000).also { quoted = it }
  }
  override fun execute(quoteId: String, prompt: VaultPrompt, feePayment: FeePayment): Flow<ActionState> = flow {
    executions += feePayment
    executedQuoteIds += quoteId
    executionGate?.let { emit(ActionState.Simulating); it.await() }
    if (result is ActionState.Committed) capacity = capacity.copy(outputBalance = capacity.outputBalance + quoted.minimumOutput.toULong())
    else if ((result as? ActionState.Failed)?.definitelyNotSubmitted == false) operations.value = listOf(
      SwapOperation(quoteId, CONTEXT.profileId, quoted, SwapOperationStatus.PENDING, "pending", now))
    emit(result)
  }
  override suspend fun reconcile(): List<SwapOperation> {
    reconciled?.let { status -> operations.value = operations.value.map { it.copy(status = status) } }
    return operations.value
  }
}
