package xyz.mcxross.flare.venue.decibel

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.aptos.swap.AptosSwapAssets
import xyz.mcxross.flare.aptos.swap.AptosSwapRequest
import xyz.mcxross.flare.aptos.swap.SwapKind
import xyz.mcxross.flare.aptos.swap.SwapProvider
import xyz.mcxross.flare.aptos.swap.ValidatedSwapPlan
import xyz.mcxross.flare.aptos.swap.validateEffects
import xyz.mcxross.flare.aptos.transaction.AptosTransactionExecutor
import xyz.mcxross.flare.aptos.transaction.AptosTransactionState
import xyz.mcxross.flare.aptos.transaction.ExternalFeePayerSubmitter
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.*
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.*
import xyz.mcxross.flare.venue.Network
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.SelfPayOffer
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.AccountAsset
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.TransactionOptions
import xyz.mcxross.kaptos.util.APTOS_COIN

/** Adapts headless swaps to Flare's owner authorization and durable transaction lifecycle. */
class AptosSwapRepository(
  private val provider: SwapProvider,
  private val executor: AptosTransactionExecutor,
  private val aptos: Aptos,
  private val wallets: AptosSigningWallet,
  private val sessions: SessionRepository,
  private val gas: GasSponsorshipRepository,
  private val trading: TradingRepository,
  private val preferences: AppPreferences,
  private val store: SwapOperationStore,
  private val journal: TransactionJournalDao,
  private val gate: AptosSubmissionGate,
  private val runtime: FlareRuntimeConfig,
  private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SwapRepository {
  private val plans = mutableMapOf<String, Pair<String, ValidatedSwapPlan>>()
  private val plansMutex = Mutex()

  override val operations: Flow<List<SwapOperation>> = store.operations.combine(preferences.values) { saved, selected ->
    saved.filter { it.profileId == selected.activeProfileId }
  }

  override suspend fun quote(request: SwapRequest): SwapQuote {
    require(runtime.network == Network.MAINNET && request.input == APT && request.output == USDC) {
      "Only Aptos-mainnet APT to native USDC is supported"
    }
    val selected = preferences.values.first()
    val owner = checkNotNull(selected.ownerAddress) { "Use a device that holds the owner key" }
    require(owner.sameAptosAddress(request.owner)) { "The swap belongs to another wallet" }
    val generation = wallets.authorizationGeneration
    wallets.requireAuthorization(generation)
    val prompt = VaultPrompt("Get swap quote", "Confirm your identity")
    try {
      val session = sessions.authenticateOwner(selected.selectedSubaccount, prompt)
      return sessions.bind(session, flow {
        val plan = provider.quote(AptosSwapRequest(owner,
          if (request.kind == SwapAmountKind.EXACT_INPUT) SwapKind.EXACT_INPUT else SwapKind.EXACT_OUTPUT,
          request.amount, request.slippageBps))
        wallets.requireAuthorization(generation)
        check(preferences.values.first().activeProfileId == selected.activeProfileId) { "The selected wallet changed" }
        plan.requireCurrent(now(), owner)
        plansMutex.withLock {
          plans.entries.removeAll { it.value.second.expiresAtMs <= now() }
          check(plans.size < 32) { "Too many active swap quotes" }
          plans[plan.id] = selected.activeProfileId to plan
        }
        emit(plan.toQuote())
      }).first()
    } finally { restoreTrading(prompt) }
  }

  override fun execute(quoteId: String, prompt: VaultPrompt, feePayment: FeePayment): Flow<ActionState> = flow {
    if (!gate.mutex.tryLock()) {
      emit(ActionState.Failed("Another transaction is already being authorized", definitelyNotSubmitted = true))
      return@flow
    }
    var operation: SwapOperation? = null
    var submissionStarted = false
    try {
      require(runtime.network == Network.MAINNET)
      val selected = preferences.values.first()
      val owner = checkNotNull(selected.ownerAddress) { "Use a device that holds the owner key" }
      val retained = plansMutex.withLock { plans[quoteId] } ?: error("Request a fresh swap quote")
      require(retained.first == selected.activeProfileId) { "The quote belongs to another profile" }
      val plan = retained.second
      plan.requireCurrent(now(), owner)
      reconcileLocked()
      check(store.operations.first().none { it.profileId == selected.activeProfileId && it.isUnresolved }) {
        "An earlier swap is unresolved. Reconcile it before trying again."
      }
      check(store.operations.first().none { it.id == quoteId && it.status != SwapOperationStatus.NOT_SUBMITTED }) {
        "This swap quote has already been executed"
      }
      trading.reconcilePending()
      check(journal.pending().none { it.profileId == selected.activeProfileId && it.network == runtime.network.name }) {
        "An earlier transaction is unresolved. Check Activity before trying again."
      }
      val generation = wallets.authorizationGeneration
      wallets.requireAuthorization(generation)
      val session = sessions.authenticateOwner(selected.selectedSubaccount, prompt.copy(requireFreshAuthorization = false))
      emitAll(sessions.bind(session, flow {
        wallets.withOwnerAccount(prompt.copy(requireFreshAuthorization = false)) { account ->
          check(account.accountAddress.toString().sameAptosAddress(owner) && session.walletAddress?.sameAptosAddress(owner) == true)
          var authorized = false
          executor.execute(
            signer = account,
            payload = { AptosResult.Success(plan.payload()) },
            externalFeePayer = if (feePayment == FeePayment.SPONSORED)
              ExternalFeePayerSubmitter { gas.submitSwap(it, plan.sponsorshipAuthorization) } else null,
            options = TransactionOptions(maxGasAmount = 50_000uL,
              expirationTimestampSecs = (plan.expiresAtMs / 1000).toULong()),
            validateSimulation = { result ->
              plan.validateEffects(result)
              val price = result.gasUnitPrice.toULong()
              val limit = result.maxGasAmount.toULong()
              require(price in 1uL..1_000uL && limit == 50_000uL) { "Swap gas exceeds the supported limits" }
              val reserve = price * limit
              val balance = when (val read = aptos.accounts.getBalance(account.accountAddress, AccountAsset.coin(APTOS_COIN))) {
                is AptosResult.Success -> read.value
                is AptosResult.Failure -> error("Unable to verify the APT balance")
              }
              require(balance >= reserve && plan.maximumInput <= balance - reserve) {
                "Keep enough APT to cover the swap and its maximum network fee"
              }
            },
            beforeSign = {
              wallets.requireAuthorization(generation)
              val current = preferences.values.first()
              check(current.activeProfileId == selected.activeProfileId && current.ownerAddress?.sameAptosAddress(owner) == true) {
                "The selected wallet changed. Review the swap again."
              }
              plan.requireCurrent(now(), owner)
              if (!authorized) { wallets.confirmIdentity(prompt.copy(requireFreshAuthorization = true)); authorized = true }
              wallets.requireAuthorization(generation)
              val afterAuthorization = preferences.values.first()
              check(afterAuthorization.activeProfileId == selected.activeProfileId &&
                afterAuthorization.ownerAddress?.sameAptosAddress(owner) == true) {
                "The selected wallet changed. Review the swap again."
              }
              plan.requireCurrent(now(), owner)
            },
            onPrepared = { reference ->
              operation = SwapOperation(plan.id, selected.activeProfileId, plan.toQuote(),
                SwapOperationStatus.PREPARED, reference, now())
              store.write(checkNotNull(operation))
              journal.upsert(PendingTransactionEntity(reference, runtime.network.name,
                journalOperation("SWAP", selected.activeProfileId), "PREPARED", now(), now()))
            },
          ).collect { state ->
            when (state) {
              AptosTransactionState.Submitting -> {
                // Persist first. A storage failure here proves nothing was sent.
                operation = update(checkNotNull(operation), SwapOperationStatus.SUBMITTING)
                submissionStarted = true
              }
              is AptosTransactionState.Pending -> {
                val previous = checkNotNull(operation)
                operation = update(previous, SwapOperationStatus.PENDING, state.hash)
                if (previous.reference != state.hash) check(journal.replaceHash(previous.reference, state.hash, "PENDING", now()) == 1)
              }
              is AptosTransactionState.Committed -> {
                operation = update(checkNotNull(operation), SwapOperationStatus.COMMITTED, state.hash)
                journal.remove(state.hash)
                journal.remove(checkNotNull(operation).preparedReference)
              }
              is AptosTransactionState.Failed -> operation?.let { old ->
                val status = when {
                  state.committed -> SwapOperationStatus.ABORTED
                  state.definitelyNotSubmitted || !submissionStarted -> SwapOperationStatus.NOT_SUBMITTED
                  else -> SwapOperationStatus.PENDING
                }
                operation = update(old, status, state.hash ?: old.reference, state.message)
                if (status == SwapOperationStatus.NOT_SUBMITTED || status == SwapOperationStatus.ABORTED) {
                  journal.remove(old.reference)
                  journal.remove(old.preparedReference)
                }
              }
              else -> Unit
            }
            emit((if (state is AptosTransactionState.Failed && !submissionStarted)
              state.copy(definitelyNotSubmitted = true) else state).toActionState())
          }
        }
      }))
    } catch (cancelled: CancellationException) {
      if (!submissionStarted) operation?.let { withContext(NonCancellable) { recordNotSubmitted(it, "Authorization interrupted") } }
      throw cancelled
    } catch (error: Exception) {
      if (!submissionStarted) operation?.let { recordNotSubmitted(it, error.message) }
      emit(ActionState.Failed(error.message ?: "Swap execution failed", operation?.reference,
        definitelyNotSubmitted = !submissionStarted))
    } finally {
      try { restoreTrading(prompt) } finally { gate.mutex.unlock() }
    }
  }

  override suspend fun reconcile(): List<SwapOperation> {
    if (!gate.mutex.tryLock()) return operations.first()
    return try { reconcileLocked() } finally { gate.mutex.unlock() }
  }

  private suspend fun reconcileLocked(): List<SwapOperation> {
    val selected = preferences.values.first()
    val pending = store.operations.first().filter { it.profileId == selected.activeProfileId && it.isUnresolved }
    if (pending.isEmpty()) return operations.first()
    val prompt = VaultPrompt("Check swap status", "Confirm your identity")
    val session = sessions.authenticateOwner(selected.selectedSubaccount, prompt)
    try {
      sessions.bind(session, flow {
        for (operation in pending) when (val status = trading.transactionStatus(operation.reference)) {
          is ActionState.Committed -> {
            update(operation, SwapOperationStatus.COMMITTED, status.reference)
            journal.remove(operation.preparedReference)
          }
          is ActionState.Failed -> if (status.committed) {
            update(operation, SwapOperationStatus.ABORTED, status.reference ?: operation.reference, status.message)
            journal.remove(operation.preparedReference)
          }
          else -> Unit
        }
        emit(Unit)
      }).first()
    } finally { restoreTrading(prompt) }
    return operations.first()
  }

  private suspend fun update(old: SwapOperation, status: SwapOperationStatus,
    reference: String = old.reference, message: String? = null): SwapOperation =
    old.copy(status = status, reference = reference, updatedAtMs = now(), message = message).also { store.write(it) }

  private suspend fun recordNotSubmitted(operation: SwapOperation, message: String?) {
    update(operation, SwapOperationStatus.NOT_SUBMITTED, message = message)
    journal.remove(operation.reference)
    journal.remove(operation.preparedReference)
  }

  private suspend fun restoreTrading(prompt: VaultPrompt) {
    try {
      wallets.requireAuthorization(wallets.authorizationGeneration)
      val current = preferences.values.first()
      if (current.apiWalletAddress != null && current.selectedSubaccount != null)
        sessions.ensureTrading(checkNotNull(current.selectedSubaccount), prompt.copy(requireFreshAuthorization = false))
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { /* Renew on the next trading action. */ }
  }


  companion object {
    val APT = SwapAsset("aptos:1", AptosSwapAssets.APT, 8)
    val USDC = SwapAsset("aptos:1", AptosSwapAssets.USDC, 6)
  }
}

private fun ValidatedSwapPlan.toQuote() = SwapQuote(id, "panora",
  SwapRequest(request.owner, AptosSwapRepository.APT, AptosSwapRepository.USDC,
    if (request.kind == SwapKind.EXACT_INPUT) SwapAmountKind.EXACT_INPUT else SwapAmountKind.EXACT_OUTPUT,
    request.amount, request.slippageBps), expectedInput.toString(), expectedOutput.toString(),
  maximumInput.toString(), minimumOutput.toString(), emptyList(), expiresAtMs)

private fun AptosTransactionState.toActionState(): ActionState = when (this) {
  AptosTransactionState.Simulating -> ActionState.Simulating
  AptosTransactionState.AwaitingAuthorization -> ActionState.AwaitingAuthorization
  AptosTransactionState.Submitting -> ActionState.Submitting
  is AptosTransactionState.Pending -> ActionState.Pending(hash)
  is AptosTransactionState.Committed -> ActionState.Committed(hash)
  is AptosTransactionState.Failed -> ActionState.Failed(message, hash, committed, definitelyNotSubmitted,
    selfPayEstimateOctas?.let { SelfPayOffer(it, selfPayReserveOctas) })
}
