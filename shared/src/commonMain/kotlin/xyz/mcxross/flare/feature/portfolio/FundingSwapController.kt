package xyz.mcxross.flare.feature.portfolio

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.mcxross.flare.core.decimalUnits
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.core.toDecimalString
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
import xyz.mcxross.flare.store.isUnresolved
import xyz.mcxross.flare.venue.model.ActionState

internal data class FundingSwapContext(val profileId: String, val owner: String, val account: String?)

enum class FundingSwapStep { AMOUNT, REVIEW, PENDING }

data class FundingSwapState(
  val supported: Boolean = false,
  val capacity: SwapFundingCapacity? = null,
  val loadingBalance: Boolean = false,
  val balanceError: String? = null,
  val step: FundingSwapStep? = null,
  val inputAmount: String = "",
  val quote: SwapQuote? = null,
  val quoteExpired: Boolean = false,
  val working: Boolean = false,
  val transaction: ActionState? = null,
  val pending: SwapOperation? = null,
  val error: String? = null,
  val completed: Boolean = false,
) {
  val canSwap: Boolean get() = capacity?.maximumInput?.let { it > 0uL } == true && pending == null && step != FundingSwapStep.PENDING
  val selfPay: Boolean get() = (transaction as? ActionState.Failed)?.let {
    it.definitelyNotSubmitted && it.selfPay != null
  } == true

  /** A shortfall, never the full transfer amount when some USDC is already held. */
  fun shortfall(fundingAmount: String): ULong? {
    val wallet = capacity ?: return null
    val amount = decimalUnits(fundingAmount, "Transfer amount", wallet.output.decimals).getOrNull() ?: return null
    return if (amount > wallet.outputBalance) amount - wallet.outputBalance else 0uL
  }
}

/** Funding policy consumes swaps without moving deposits or chain mechanics into the swap SDK. */
internal class FundingSwapController(
  private val repository: SwapRepository?,
  private val scope: CoroutineScope,
  private val onReadyToTransfer: (String) -> Unit,
  private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
  private val mutable = MutableStateFlow(FundingSwapState())
  val state = mutable.asStateFlow()
  private var context: FundingSwapContext? = null
  private var revision = 0L
  private var refreshJob: Job? = null
  private var expiryJob: Job? = null
  private var requestedTransfer = ""
  private var lastRequest: SwapRequest? = null

  fun open(value: FundingSwapContext?) {
    close()
    if (repository == null || value == null) return
    context = value
    mutable.value = FundingSwapState(supported = true)
    refresh()
  }

  fun close() {
    revision++
    context = null
    refreshJob?.cancel()
    expiryJob?.cancel()
    lastRequest = null
    requestedTransfer = ""
    mutable.value = FundingSwapState()
  }

  fun refresh() {
    val key = context ?: return
    val version = revision
    refreshJob?.cancel()
    refreshJob = scope.launch {
      mutable.update { it.copy(loadingBalance = true, balanceError = null) }
      runSuspendCatching {
        val capacity = checkNotNull(repository).fundingCapacity(key.owner)
        val pending = repository.operations.first().firstOrNull { it.profileId == key.profileId && it.isUnresolved }
        if (current(key, version)) mutable.update { it.copy(capacity = capacity, loadingBalance = false,
          pending = pending, step = if (pending != null) FundingSwapStep.PENDING else it.step) }
      }.onFailure {
        if (current(key, version)) mutable.update { it.copy(capacity = null, loadingBalance = false,
          balanceError = "Your wallet balances couldn’t be refreshed. Try again.") }
      }
    }
  }

  fun begin(fundingAmount: String) {
    if (state.value.working || !state.value.canSwap) return
    requestedTransfer = fundingAmount
    mutable.update { it.copy(step = FundingSwapStep.AMOUNT, error = null, completed = false, transaction = null) }
    val shortfall = state.value.shortfall(fundingAmount) ?: 0uL
    if (shortfall > 0uL) prepare(SwapAmountKind.EXACT_OUTPUT, shortfall.toString())
  }

  fun changeInput(value: String) {
    if (state.value.working || state.value.pending != null) return
    val amount = value.filter { it in '0'..'9' || it == '.' }
    mutable.update { it.copy(inputAmount = amount, quote = null, quoteExpired = false,
      step = FundingSwapStep.AMOUNT, error = null, transaction = null) }
    expiryJob?.cancel()
  }

  fun useMaximum() {
    val capacity = state.value.capacity ?: return
    changeInput(capacity.maximumInput.toDecimalString(capacity.input.decimals))
  }

  fun review() {
    val capacity = state.value.capacity ?: return
    val amount = decimalUnits(state.value.inputAmount, "APT amount", capacity.input.decimals).getOrNull()
    if (amount == null || amount == 0uL) {
      mutable.update { it.copy(error = "Enter an APT amount to swap.") }
      return
    }
    prepare(SwapAmountKind.EXACT_INPUT, amount.toString())
  }

  fun refreshQuote() {
    val request = lastRequest ?: return
    prepare(request.kind, request.amount)
  }

  fun back() {
    if (state.value.working || state.value.pending != null) return
    expiryJob?.cancel()
    lastRequest = null
    mutable.update { it.copy(step = null, quote = null, transaction = null, error = null) }
  }

  private fun prepare(kind: SwapAmountKind, amount: String) = work { key, version ->
    val capacity = checkNotNull(repository).fundingCapacity(key.owner)
    if (!current(key, version)) return@work
    mutable.update { it.copy(capacity = capacity, balanceError = null) }
    check(capacity.maximumInput > 0uL) { "Keep some APT for network fees before swapping." }
    val requestedUnits = if (kind == SwapAmountKind.EXACT_OUTPUT) {
      val transfer = decimalUnits(requestedTransfer, "Transfer amount", capacity.output.decimals).getOrThrow()
      check(transfer > capacity.outputBalance) { "Your wallet already has enough USDC. Return to Transfer." }
      transfer - capacity.outputBalance
    } else amount.toULong()
    if (kind == SwapAmountKind.EXACT_INPUT) check(requestedUnits <= capacity.maximumInput) {
      "Try a smaller APT amount so network fees stay covered."
    }
    val request = SwapRequest(key.owner, capacity.input, capacity.output, kind, requestedUnits.toString())
    lastRequest = request
    val quote = repository.quote(request)
    if (!current(key, version)) return@work
    check(quote.maximumInput.toULong() <= capacity.maximumInput) {
      "There isn’t enough APT for this amount and network fees. Try a smaller amount."
    }
    check(quote.expiresAtMs > now()) { "The quote expired. Get a fresh quote." }
    mutable.update { it.copy(quote = quote, quoteExpired = false, step = FundingSwapStep.REVIEW, transaction = null) }
    expiryJob?.cancel()
    expiryJob = scope.launch {
      delay((quote.expiresAtMs - now()).coerceAtLeast(0))
      if (current(key, version) && state.value.quote?.id == quote.id) mutable.update { it.copy(quoteExpired = true) }
    }
  }

  fun execute(selfPay: Boolean = false) {
    if (selfPay && !state.value.selfPay) return
    val quote = state.value.quote ?: return
    work { key, version ->
      check(!state.value.quoteExpired && quote.expiresAtMs > now()) { "The quote expired. Get a fresh quote." }
      val capacity = checkNotNull(repository).fundingCapacity(key.owner)
      if (!current(key, version)) return@work
      check(quote.maximumInput.toULong() <= capacity.maximumInput) {
        "Your available APT changed. Get a fresh quote for a smaller amount."
      }
      repository.execute(quote.id, VaultPrompt("Swap APT for USDC", "Confirm your identity"),
        if (selfPay) FeePayment.SELF_PAY else FeePayment.SPONSORED).collect { transaction ->
        if (current(key, version)) mutable.update { it.copy(transaction = transaction) }
      }
      if (!current(key, version)) return@work
      when (val result = state.value.transaction) {
        is ActionState.Committed -> complete(quote, key, version)
        is ActionState.Failed -> {
          if (!result.definitelyNotSubmitted && !result.committed) {
            val pending = repository.operations.first().firstOrNull { it.id == quote.id }
            mutable.update { it.copy(pending = pending, step = FundingSwapStep.PENDING, quote = null,
              error = "Your swap is still being checked. Check its status before starting another.") }
          } else if (result.selfPay == null) {
            mutable.update { it.copy(error = actionFailure(result.message, "The swap didn’t go through. Try a fresh quote.")) }
          }
        }
        else -> mutable.update { it.copy(step = FundingSwapStep.PENDING, quote = null,
          error = "Your swap is still being checked. Check its status before starting another.") }
      }
    }
  }

  fun reconcile() = work { key, version ->
    val pending = state.value.pending
    val operations = checkNotNull(repository).reconcile()
    if (!current(key, version)) return@work
    val result = operations.firstOrNull { it.id == pending?.id }
      ?: operations.firstOrNull { it.profileId == key.profileId && it.isUnresolved }
    when (result?.status) {
      SwapOperationStatus.COMMITTED -> complete(result.quote, key, version)
      SwapOperationStatus.ABORTED, SwapOperationStatus.NOT_SUBMITTED -> {
        mutable.update { it.copy(pending = null, step = null, transaction = null,
          error = "The swap didn’t go through. You can try again.") }
        refresh()
      }
      else -> mutable.update { it.copy(pending = result ?: pending,
        error = "The swap hasn’t confirmed yet. Check again in a moment.") }
    }
  }

  private suspend fun complete(quote: SwapQuote, key: FundingSwapContext, version: Long) {
    val capacity = runSuspendCatching { checkNotNull(repository).fundingCapacity(key.owner) }.getOrNull()
    if (!current(key, version)) return
    val amount = requestedTransfer.takeIf { decimalUnits(it, "Transfer amount", quote.request.output.decimals).getOrNull()?.let { n -> n > 0uL } == true }
      ?: (capacity?.outputBalance ?: quote.minimumOutput.toULong()).toDecimalString(quote.request.output.decimals)
    mutable.update { it.copy(capacity = capacity, pending = null, step = null, quote = null, transaction = null,
      completed = true, error = null, balanceError = if (capacity == null) "Swap confirmed. Refresh your wallet balance to continue." else null) }
    onReadyToTransfer(amount)
  }

  private fun work(block: suspend (FundingSwapContext, Long) -> Unit) {
    val key = context ?: return
    if (state.value.working) return
    val version = revision
    refreshJob?.cancel()
    mutable.update { it.copy(working = true, loadingBalance = false, error = null) }
    scope.launch {
      try {
        runSuspendCatching { block(key, version) }.onFailure { error ->
          if (current(key, version) && !error.isAuthorizationCancelled()) mutable.update {
            it.copy(error = actionFailure(error.message, "The swap couldn’t be prepared. Try again."))
          }
        }
      } finally {
        if (current(key, version)) mutable.update { it.copy(working = false) }
      }
    }
  }

  private fun current(key: FundingSwapContext, version: Long) = context == key && revision == version
}
