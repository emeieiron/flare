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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import xyz.mcxross.flare.core.ReadOperation
import xyz.mcxross.flare.core.ReadRecoveryDiagnostics
import xyz.mcxross.flare.core.readBackoffMs
import xyz.mcxross.flare.core.decimalUnits
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.core.toDecimalString
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.design.actionFailure
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.isAuthorizationCancelled
import xyz.mcxross.flare.store.isUnresolved
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.SelfPayOffer

internal data class FundingSwapContext(val profileId: String, val owner: String, val account: String?)

enum class FundingSwapStep { SWAP, PENDING }

data class FundingSwapState(
  val supported: Boolean = false,
  val capacity: SwapFundingCapacity? = null,
  val loadingBalance: Boolean = false,
  val balanceCurrent: Boolean = false,
  val balanceUpdatedAtMs: Long? = null,
  val balanceError: String? = null,
  val step: FundingSwapStep? = null,
  val inputAmount: String = "",
  val quote: SwapQuote? = null,
  val quoteExpired: Boolean = false,
  val quoting: Boolean = false,
  val amountKind: SwapAmountKind = SwapAmountKind.EXACT_INPUT,
  val working: Boolean = false,
  val transaction: ActionState? = null,
  val selfPayOffer: SelfPayOffer? = null,
  val pending: SwapOperation? = null,
  val error: String? = null,
  val completed: Boolean = false,
) {
  val canSwap: Boolean get() = balanceCurrent && balanceError == null &&
    capacity?.maximumInput?.let { it > 0uL } == true && pending == null && step != FundingSwapStep.PENDING
  val selfPay: Boolean get() = selfPayOffer != null

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
  private var balanceRetryJob: Job? = null
  private var statusJob: Job? = null
  private var balanceFailures = 0
  private var expiryJob: Job? = null
  private var requestedTransfer = ""
  private var quoteJob: Job? = null
  private var quoteRevision = 0L
  private var visible = true
  private var balanceRevision = 0L

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
    balanceRetryJob?.cancel()
    statusJob?.cancel()
    balanceFailures = 0
    expiryJob?.cancel()
    cancelQuotes()
    requestedTransfer = ""
    mutable.value = FundingSwapState()
  }

  fun refresh() {
    val key = context ?: return
    if (!visible || state.value.working || refreshJob?.isActive == true) return
    balanceRetryJob?.cancel()
    val version = revision
    val balanceVersion = ++balanceRevision
    refreshJob = scope.launch {
      mutable.update { it.copy(loadingBalance = true, balanceCurrent = it.balanceCurrent &&
        it.balanceUpdatedAtMs?.let { checked -> now() - checked in 0L..30_000L } == true) }
      val read = withTimeoutOrNull(10_000) { runSuspendCatching {
        val source = checkNotNull(repository)
        val pending = source.operations.first().firstOrNull { it.profileId == key.profileId && it.isUnresolved }
        currentCoroutineContext().ensureActive()
        if (!visible || !current(key, version) || balanceRevision != balanceVersion) return@runSuspendCatching
        if (pending != null) {
          mutable.update { it.copy(loadingBalance = false, balanceCurrent = false, pending = pending, step = FundingSwapStep.PENDING) }
          pollStatus(immediate = true)
          return@runSuspendCatching
        }
        val capacity = source.fundingCapacity(key.owner)
        currentCoroutineContext().ensureActive()
        if (visible && current(key, version) && balanceRevision == balanceVersion) mutable.update { it.copy(capacity = capacity,
          loadingBalance = false, balanceCurrent = true, balanceUpdatedAtMs = now(), balanceError = null) }
      } }
      if (!current(key, version) || balanceRevision != balanceVersion) return@launch
      if (read != null && read.isSuccess) {
        balanceFailures = 0
        ReadRecoveryDiagnostics.recovered(ReadOperation.SWAP_BALANCE)
      } else {
        balanceFailures++
        ReadRecoveryDiagnostics.failed(ReadOperation.SWAP_BALANCE, balanceFailures, read?.exceptionOrNull(), read == null)
        mutable.update { it.copy(loadingBalance = false, balanceCurrent = false, balanceUpdatedAtMs = null,
          balanceError = if (balanceFailures >= 3) "Wallet balance unavailable" else null) }
        balanceRetryJob = scope.launch {
          delay(readBackoffMs(balanceFailures))
          if (visible && current(key, version)) refresh()
        }
      }
    }
  }

  fun observeCapacity(key: FundingSwapContext, capacity: SwapFundingCapacity) {
    if (context != key || capacity.owner != key.owner) return
    balanceRevision++
    refreshJob?.cancel()
    balanceRetryJob?.cancel()
    balanceFailures = 0
    mutable.update { it.copy(capacity = capacity, loadingBalance = false, balanceCurrent = true,
      balanceUpdatedAtMs = now(), balanceError = null) }
  }

  fun begin(fundingAmount: String) {
    if (state.value.working || !state.value.canSwap) return
    requestedTransfer = fundingAmount
    val shortfall = state.value.shortfall(fundingAmount) ?: 0uL
    mutable.update { it.copy(step = FundingSwapStep.SWAP, error = null, completed = false, transaction = null, selfPayOffer = null,
      quote = null, amountKind = if (shortfall > 0uL) SwapAmountKind.EXACT_OUTPUT else SwapAmountKind.EXACT_INPUT,
      inputAmount = if (shortfall > 0uL) shortfall.toDecimalString(checkNotNull(it.capacity).output.decimals) else "") }
    scheduleQuote()
  }

  fun changeInput(value: String, kind: SwapAmountKind? = null) {
    if (state.value.working || state.value.pending != null) return
    val amount = value.filter { it in '0'..'9' || it == '.' }
    mutable.update { it.copy(inputAmount = amount, amountKind = kind ?: it.amountKind, quote = null, quoteExpired = false,
      step = FundingSwapStep.SWAP, error = null, transaction = null, selfPayOffer = null) }
    scheduleQuote()
  }

  fun useMaximum() {
    val capacity = state.value.capacity ?: return
    if (state.value.working) return
    mutable.update { it.copy(amountKind = SwapAmountKind.EXACT_INPUT) }
    changeInput(capacity.maximumInput.toDecimalString(capacity.input.decimals))
  }

  fun setVisible(value: Boolean) {
    if (visible == value) return
    visible = value
    if (!value) {
      refreshJob?.cancel()
      balanceRetryJob?.cancel()
      statusJob?.cancel()
      cancelQuotes()
      mutable.update { it.copy(balanceCurrent = false) }
    }
    else if (state.value.step == FundingSwapStep.PENDING) pollStatus(immediate = true)
    else if (state.value.step == FundingSwapStep.SWAP && !state.value.working) scheduleQuote(0)
    else if (context != null && !state.value.working) refresh()
  }

  fun back() {
    if (state.value.working || state.value.pending != null) return
    cancelQuotes()
    mutable.update { it.copy(step = null, quote = null, transaction = null, selfPayOffer = null, error = null) }
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
            observeCapacity(key, fresh)
            retryable = false
            check(fresh.maximumInput > 0uL) { "Available to swap: 0.00 APT after fees." }
            check(kind != SwapAmountKind.EXACT_INPUT || units <= fresh.maximumInput) {
              "Available to swap: ${formatTokenBalance(fresh.maximumInput.toDecimalString(fresh.input.decimals))} APT after fees."
            }
            retryable = true
            val quote = repository.quote(SwapRequest(key.owner, fresh.input, fresh.output, kind, units.toString()))
            if (!latest()) return@withTimeoutOrNull true
            retryable = false
            check(quote.maximumInput.toULong() <= fresh.maximumInput) {
              "Requires ${quote.maximumInput.toULong().toDecimalString(fresh.input.decimals)} APT; ${formatTokenBalance(fresh.maximumInput.toDecimalString(fresh.input.decimals))} APT available after fees."
            }
            retryable = true
            check(quote.expiresAtMs > now()) { "Quote unavailable. Try again." }
            ReadRecoveryDiagnostics.recovered(ReadOperation.SWAP_QUOTE)
            mutable.update { it.copy(quote = quote, quoteExpired = false, transaction = null) }
            watchExpiry(key, version, quoteVersion, quote)
            true
          } ?: error("Quote request timed out")
        }.onFailure { error ->
          ReadRecoveryDiagnostics.failed(ReadOperation.SWAP_QUOTE, attempt + 1, error)
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
    val networkFee = quote.networkFee ?: return
    cancelQuotes()
    work { key, version ->
      check(!state.value.quoteExpired && quote.expiresAtMs > now()) { "The quote expired. Get a fresh quote." }
      val capacity = checkNotNull(repository).fundingCapacity(key.owner)
      if (!current(key, version)) return@work
      observeCapacity(key, capacity)
      check(quote.maximumInput.toULong() <= capacity.maximumInput) {
        "Your available APT changed. Get a fresh quote for a smaller amount."
      }
      repository.execute(quote.id, VaultPrompt("Swap APT for USDC", "Confirm your identity"),
        if (selfPay) FeePayment.SELF_PAY else networkFee.payment).collect { transaction ->
        if (current(key, version)) mutable.update { it.copy(transaction = transaction,
          selfPayOffer = (transaction as? ActionState.Failed)?.takeIf { failure -> failure.definitelyNotSubmitted }?.selfPay
            ?: it.selfPayOffer) }
      }
      if (!current(key, version)) return@work
      when (val result = state.value.transaction) {
        is ActionState.Committed -> complete(key, version)
        is ActionState.Failed -> {
          if (!result.definitelyNotSubmitted && !result.committed) {
            val pending = repository.operations.first().firstOrNull { it.id == quote.id }
            mutable.update { it.copy(pending = pending, step = FundingSwapStep.PENDING, quote = null,
              error = null) }
          } else if (result.selfPay == null) {
            mutable.update { it.copy(error = actionFailure(result.message, "The swap didn’t go through. Try a fresh quote.")) }
          }
        }
        else -> mutable.update { it.copy(step = FundingSwapStep.PENDING, quote = null,
          error = null) }
      }
    }
  }

  fun reconcile() { statusJob?.cancel(); pollStatus(immediate = true) }

  /** Reconciliation only reads status; it never executes a quote or holds the sheet busy. */
  private fun pollStatus(immediate: Boolean = false) {
    val key = context ?: return
    if (!visible || state.value.step != FundingSwapStep.PENDING || statusJob?.isActive == true) return
    val version = revision
    val expectedId = state.value.pending?.id
    statusJob = scope.launch {
      if (!immediate) delay(1_000)
      var attempt = 0
      var failures = 0
      while (visible && current(key, version) && state.value.step == FundingSwapStep.PENDING && currentCoroutineContext().isActive) {
        val read = withTimeoutOrNull(10_000) { runSuspendCatching { checkNotNull(repository).reconcile() } }
        currentCoroutineContext().ensureActive()
        if (!visible || !current(key, version) || state.value.step != FundingSwapStep.PENDING) return@launch
        attempt++
        if (read == null || read.isFailure) {
          failures++
          ReadRecoveryDiagnostics.failed(ReadOperation.SWAP_STATUS, failures, read?.exceptionOrNull(), read == null)
          if (failures >= 3) mutable.update { it.copy(error = "Swap status unavailable") }
        } else {
          failures = 0
          ReadRecoveryDiagnostics.recovered(ReadOperation.SWAP_STATUS)
          val operations = read.getOrThrow()
          val result = if (expectedId != null) operations.firstOrNull { it.id == expectedId && it.profileId == key.profileId }
            else operations.firstOrNull { it.profileId == key.profileId && it.isUnresolved }
          when (result?.status) {
            SwapOperationStatus.COMMITTED -> { complete(key, version); return@launch }
            SwapOperationStatus.ABORTED, SwapOperationStatus.NOT_SUBMITTED -> {
              mutable.update { it.copy(pending = null, step = null, transaction = null,
                error = "The swap didn’t go through. You can try again.") }
              refresh()
              return@launch
            }
            else -> mutable.update { it.copy(pending = result ?: it.pending, error = null) }
          }
        }
        delay(readBackoffMs(attempt.coerceAtLeast(2)))
      }
    }
  }

  private fun complete(key: FundingSwapContext, version: Long) {
    if (!current(key, version)) return
    mutable.update { it.copy(balanceCurrent = false, balanceUpdatedAtMs = null,
      pending = null, step = null, quote = null, transaction = null, selfPayOffer = null,
      completed = true, error = null, balanceError = null) }
    onReadyToTransfer(requestedTransfer)
    balanceRetryJob?.cancel()
    balanceRetryJob = scope.launch {
      yield()
      if (visible && current(key, version)) refresh()
    }
  }

  private fun work(block: suspend (FundingSwapContext, Long) -> Unit) {
    val key = context ?: return
    if (state.value.working) return
    val version = revision
    balanceRevision++
    refreshJob?.cancel()
    balanceRetryJob?.cancel()
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
          if (state.value.step == FundingSwapStep.PENDING) pollStatus(immediate = true)
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
