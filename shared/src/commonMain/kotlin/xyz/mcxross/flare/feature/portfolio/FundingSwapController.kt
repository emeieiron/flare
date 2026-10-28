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
    expiryJob?.cancel()
    cancelQuotes()
    requestedTransfer = ""
    mutable.value = FundingSwapState()
  }

  fun refresh() {
    val key = context ?: return
    if (refreshJob?.isActive == true) return
    val version = revision
    val balanceVersion = ++balanceRevision
    refreshJob = scope.launch {
      mutable.update { it.copy(loadingBalance = true, balanceCurrent = it.balanceCurrent &&
        it.balanceUpdatedAtMs?.let { checked -> now() - checked in 0L..30_000L } == true, balanceError = null) }
      runSuspendCatching {
        val source = checkNotNull(repository)
        val capacity = withTimeoutOrNull(20_000) { source.fundingCapacity(key.owner) }
          ?: error("Balance unavailable")
        val pending = source.operations.first().firstOrNull { it.profileId == key.profileId && it.isUnresolved }
        if (current(key, version) && balanceRevision == balanceVersion) mutable.update { it.copy(capacity = capacity,
          loadingBalance = false, balanceCurrent = true, balanceUpdatedAtMs = now(),
          pending = pending, step = if (pending != null) FundingSwapStep.PENDING else it.step) }
      }.onFailure {
        if (current(key, version) && balanceRevision == balanceVersion) mutable.update { it.copy(capacity = null,
          loadingBalance = false, balanceCurrent = false, balanceUpdatedAtMs = null, balanceError = "Wallet balance unavailable") }
      }
    }
  }

  fun observeCapacity(key: FundingSwapContext, capacity: SwapFundingCapacity) {
    if (context != key || capacity.owner != key.owner) return
    balanceRevision++
    refreshJob?.cancel()
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
      cancelQuotes()
      mutable.update { it.copy(balanceCurrent = false) }
    }
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
            check(fresh.maximumInput > 0uL) { "Available to swap: 0 APT after gas." }
            check(kind != SwapAmountKind.EXACT_INPUT || units <= fresh.maximumInput) {
              "Available to swap: ${fresh.maximumInput.toDecimalString(fresh.input.decimals)} APT after gas."
            }
            retryable = true
            val quote = repository.quote(SwapRequest(key.owner, fresh.input, fresh.output, kind, units.toString()))
            if (!latest()) return@withTimeoutOrNull true
            retryable = false
            check(quote.maximumInput.toULong() <= fresh.maximumInput) {
              "Requires ${quote.maximumInput.toULong().toDecimalString(fresh.input.decimals)} APT; ${fresh.maximumInput.toDecimalString(fresh.input.decimals)} APT available after gas."
            }
            retryable = true
            check(quote.expiresAtMs > now()) { "Quote unavailable. Try again." }
            mutable.update { it.copy(quote = quote, quoteExpired = false, transaction = null) }
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
      SwapOperationStatus.COMMITTED -> complete(key, version)
      SwapOperationStatus.ABORTED, SwapOperationStatus.NOT_SUBMITTED -> {
        mutable.update { it.copy(pending = null, step = null, transaction = null,
          error = "The swap didn’t go through. You can try again.") }
        refresh()
      }
      else -> mutable.update { it.copy(pending = result ?: pending,
        error = "Still confirming. Check again shortly.") }
    }
  }

  private suspend fun complete(key: FundingSwapContext, version: Long) {
    val capacity = runSuspendCatching { checkNotNull(repository).fundingCapacity(key.owner) }.getOrNull()
    if (!current(key, version)) return
    mutable.update { it.copy(capacity = capacity, balanceCurrent = capacity != null, balanceUpdatedAtMs = if (capacity != null) now() else null,
      pending = null, step = null, quote = null, transaction = null, selfPayOffer = null,
      completed = true, error = null, balanceError = if (capacity == null) "Swap confirmed. Refresh your wallet balance to continue." else null) }
    onReadyToTransfer(requestedTransfer)
  }

  private fun work(block: suspend (FundingSwapContext, Long) -> Unit) {
    val key = context ?: return
    if (state.value.working) return
    val version = revision
    balanceRevision++
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
