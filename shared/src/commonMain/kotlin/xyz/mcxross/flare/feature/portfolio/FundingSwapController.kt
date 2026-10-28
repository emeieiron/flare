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
import kotlinx.coroutines.withTimeoutOrNull
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

enum class FundingSwapStep { SWAP, PENDING }

data class FundingSwapState(
  val supported: Boolean = false,
  val capacity: SwapFundingCapacity? = null,
  val loadingBalance: Boolean = false,
  val balanceError: String? = null,
  val step: FundingSwapStep? = null,
  val inputAmount: String = "",
  val quote: SwapQuote? = null,
  val quoteExpired: Boolean = false,
  val quoting: Boolean = false,
  val amountKind: SwapAmountKind = SwapAmountKind.EXACT_INPUT,
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
  private var quoteJob: Job? = null
  private var quoteRevision = 0L
  private var visible = true

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
    cancelQuotes()
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
    val shortfall = state.value.shortfall(fundingAmount) ?: 0uL
    mutable.update { it.copy(step = FundingSwapStep.SWAP, error = null, completed = false, transaction = null,
      quote = null, amountKind = if (shortfall > 0uL) SwapAmountKind.EXACT_OUTPUT else SwapAmountKind.EXACT_INPUT,
      inputAmount = if (shortfall > 0uL) fundingAmount else "") }
    scheduleQuote()
  }

  fun changeInput(value: String) {
    if (state.value.working || state.value.pending != null) return
    val amount = value.filter { it in '0'..'9' || it == '.' }
    mutable.update { it.copy(inputAmount = amount, quote = null, quoteExpired = false,
      step = FundingSwapStep.SWAP, error = null, transaction = null) }
    if (state.value.amountKind == SwapAmountKind.EXACT_OUTPUT) requestedTransfer = amount
    scheduleQuote()
  }

  fun useMaximum() {
    val capacity = state.value.capacity ?: return
    if (state.value.working) return
    requestedTransfer = ""
    mutable.update { it.copy(amountKind = SwapAmountKind.EXACT_INPUT) }
    changeInput(capacity.maximumInput.toDecimalString(capacity.input.decimals))
  }

  fun setVisible(value: Boolean) {
    if (visible == value) return
    visible = value
    if (!value) cancelQuotes()
    else if (state.value.step == FundingSwapStep.SWAP && !state.value.working) scheduleQuote(0)
  }

  fun back() {
    if (state.value.working || state.value.pending != null) return
    cancelQuotes()
    mutable.update { it.copy(step = null, quote = null, transaction = null, error = null) }
  }

  private fun cancelQuotes() {
    quoteRevision++
    quoteJob?.cancel()
    expiryJob?.cancel()
    mutable.update { it.copy(quoting = false) }
  }

  /** Typing stays enabled during requests; only the newest input may publish a quote. */
  private fun scheduleQuote(debounceMs: Long = 600, attempt: Int = 0) {
    cancelQuotes()
    val key = context ?: return
    if (!visible || state.value.working || state.value.step != FundingSwapStep.SWAP) return
    val capacity = state.value.capacity ?: return
    val kind = state.value.amountKind
    val decimals = if (kind == SwapAmountKind.EXACT_INPUT) capacity.input.decimals else capacity.output.decimals
    val units = decimalUnits(state.value.inputAmount, "Swap amount", decimals).getOrNull() ?: return
    if (units == 0uL || kind == SwapAmountKind.EXACT_INPUT && units > capacity.maximumInput) return
    val version = revision
    val quoteVersion = quoteRevision
    val target = requestedTransfer
    fun latest() = current(key, version) && quoteRevision == quoteVersion
    mutable.update { it.copy(quoting = true, error = null) }
    quoteJob = scope.launch {
      var retryScheduled = false
      var retryable = true
      try {
        delay(debounceMs)
        runSuspendCatching {
          withTimeoutOrNull(20_000) {
            val fresh = checkNotNull(repository).fundingCapacity(key.owner)
            if (!latest()) return@withTimeoutOrNull true
            retryable = false
            check(fresh.maximumInput > 0uL) { "Not enough APT after network fees." }
            val requested = if (kind == SwapAmountKind.EXACT_OUTPUT) {
              val transfer = decimalUnits(target, "Transfer amount", fresh.output.decimals).getOrThrow()
              check(transfer > fresh.outputBalance) { "Your wallet has enough USDC. Return to Transfer." }
              transfer - fresh.outputBalance
            } else units
            check(kind != SwapAmountKind.EXACT_INPUT || requested <= fresh.maximumInput) { "Not enough APT after network fees." }
            retryable = true
            val quote = repository.quote(SwapRequest(key.owner, fresh.input, fresh.output, kind, requested.toString()))
            if (!latest()) return@withTimeoutOrNull true
            retryable = false
            check(quote.maximumInput.toULong() <= fresh.maximumInput) { "Not enough APT for this amount." }
            retryable = true
            check(quote.expiresAtMs > now()) { "Quote unavailable. Try again." }
            mutable.update { it.copy(capacity = fresh, quote = quote, quoteExpired = false, balanceError = null, transaction = null) }
            watchExpiry(key, version, quoteVersion, quote)
            true
          } ?: error("Quote request timed out")
        }.onFailure { error ->
          if (latest() && !error.isAuthorizationCancelled()) {
            if (retryable && visible) {
              retryScheduled = true
              mutable.update { it.copy(quote = null, error = if (attempt >= 2) "Quotes unavailable" else null) }
              expiryJob = scope.launch {
                delay((2_000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000L))
                if (latest()) scheduleQuote(0, (attempt + 1).coerceAtMost(4))
              }
            } else mutable.update {
              it.copy(quote = null, error = actionFailure(error.message, "Quote unavailable"))
            }
          }
        }
      } finally {
        if (latest()) mutable.update { it.copy(quoting = retryScheduled) }
      }
    }
  }

  private fun watchExpiry(key: FundingSwapContext, version: Long, quoteVersion: Long, quote: SwapQuote) {
    expiryJob?.cancel()
    expiryJob = scope.launch {
      delay((quote.expiresAtMs - now() - 3_000).coerceAtLeast(1_000))
      if (current(key, version) && quoteRevision == quoteVersion && state.value.quote?.id == quote.id) {
        if (!state.value.working && visible) scheduleQuote(0)
        else mutable.update { it.copy(quoteExpired = true) }
      }
    }
  }

  fun execute(selfPay: Boolean = false) {
    if (!visible || state.value.quoting || selfPay && !state.value.selfPay) return
    val quote = state.value.quote ?: return
    cancelQuotes()
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
              error = "Swap pending. Check its status.") }
          } else if (result.selfPay == null) {
            mutable.update { it.copy(error = actionFailure(result.message, "The swap didn’t go through. Try a fresh quote.")) }
          }
        }
        else -> mutable.update { it.copy(step = FundingSwapStep.PENDING, quote = null,
          error = "Swap pending. Check its status.") }
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
        error = "Still confirming. Check again shortly.") }
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
        if (current(key, version)) {
          mutable.update { it.copy(working = false) }
          if (state.value.step == FundingSwapStep.SWAP) state.value.quote?.let { quote ->
            if (quote.expiresAtMs - now() <= 3_000 || (state.value.transaction as? ActionState.Failed)?.committed == true)
              scheduleQuote(0)
            else if (visible) watchExpiry(key, version, quoteRevision, quote)
          }
        }
      }
    }
  }

  private fun current(key: FundingSwapContext, version: Long) = context == key && revision == version
}
