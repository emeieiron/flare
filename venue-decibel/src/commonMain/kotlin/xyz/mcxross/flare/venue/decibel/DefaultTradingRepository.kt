package xyz.mcxross.flare.venue.decibel

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.mcxross.flare.aptos.transaction.AptosSimulationIdentity
import xyz.mcxross.flare.aptos.transaction.AptosTransactionExecutor
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.core.toDecimalString
import xyz.mcxross.flare.data.ExactAssetBalance
import xyz.mcxross.flare.data.FeePayment
import xyz.mcxross.flare.data.OrderFeeReview
import xyz.mcxross.flare.data.PendingTransaction
import xyz.mcxross.flare.data.ReconciliationResult
import xyz.mcxross.flare.data.SessionRepository
import xyz.mcxross.flare.data.SessionRole
import xyz.mcxross.flare.data.TradingRepository
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.decibel.model.isLong
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlareDatabase
import xyz.mcxross.flare.store.PendingTransactionEntity
import xyz.mcxross.flare.store.TransactionJournalDao
import xyz.mcxross.flare.store.journalOperation
import xyz.mcxross.flare.store.operationName
import xyz.mcxross.flare.store.profileId
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.NetworkFee
import xyz.mcxross.flare.venue.model.OpenOrder
import xyz.mcxross.flare.venue.model.SelfPayOffer
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.Account
import xyz.mcxross.kaptos.account.AccountAsset
import xyz.mcxross.kaptos.account.TransactionSigner
import xyz.mcxross.kaptos.core.crypto.PublicKey
import xyz.mcxross.kaptos.core.crypto.Signature
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosError
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.ReplayProtection
import xyz.mcxross.kaptos.model.SimulationOptions
import xyz.mcxross.kaptos.model.TransactionOptions
import xyz.mcxross.kaptos.model.UnsignedTransaction
import xyz.mcxross.kaptos.model.UserTransactionResponse
import xyz.mcxross.kaptos.model.WaitForTransactionOptions
import xyz.mcxross.kaptos.util.APTOS_COIN

/** The key an action needs: the owner key for custody, the device trading key for trading. */
internal enum class TradingSigner {
  OWNER,
  API,
}

/** Decibel's trading, which the account repository also drives with Decibel's own commands. */
interface DecibelTrading : TradingRepository {
  suspend fun warmFundingFees() = Unit

  suspend fun estimateCommandFee(command: DecibelCommand): NetworkFee? = null

  fun executeCommand(
    command: DecibelCommand,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
    onPrepared: suspend (String) -> Unit = {},
  ): Flow<ActionState>
}

/** Marks work the person already confirmed, so the signatures inside it don't ask again. */
private class ConfirmedAction : AbstractCoroutineContextElement(ConfirmedAction) {
  companion object Key : CoroutineContext.Key<ConfirmedAction>
}

/** A fee payer in simulation, which needs only its address and public key; it never signs. */
private class SimulatedFeePayer(identity: AptosSimulationIdentity) : TransactionSigner {
  override val accountAddress: AccountAddress = identity.address
  override val publicKey: PublicKey = identity.publicKey

  private fun refused() = AptosResult.Failure(AptosError.Validation("A simulated fee payer can't sign"))

  override suspend fun signBytes(message: ByteArray): AptosResult<Signature> = refused()

  override suspend fun signText(message: String): AptosResult<Signature> = refused()

  override suspend fun signTransaction(
    transaction: UnsignedTransaction,
  ): AptosResult<xyz.mcxross.kaptos.transaction.authenticator.AccountAuthenticator> = refused()

  override fun verifySignature(message: ByteArray, signature: Signature): Boolean = false
}

/** One reviewed order may contain a configuration transaction followed by its entry. */
private class ConfirmedOrderFees(
  val review: OrderFeeReview,
  val commands: List<DecibelCommand>,
  val profileId: String,
  val subaccount: String,
  val generation: Long,
  val options: TransactionOptions,
  val firstPhaseOptions: TransactionOptions,
  val allowSelfPay: Boolean,
  val localFeePayer: Account? = null,
) : AbstractCoroutineContextElement(ConfirmedOrderFees) {
  var nextCommand = 0
  var completedCommands = 0
  val phaseFees = mutableMapOf<Int, NetworkFee>()
  val committedFees = mutableMapOf<Int, Pair<ULong, ULong>?>()

  fun totalFee(): NetworkFee {
    val covered = phaseFees.values.all { it.covered }
    val charged = if (covered) phaseFees.values else phaseFees.values.filterNot { it.covered }
    val paidPhases = phaseFees.filterValues { !it.covered }.keys
    var charges = 0uL
    var refunds = 0uL
    var verified = true
    for (phase in paidPhases) {
      val actual = committedFees[phase]
      if (
        actual == null ||
          charges > ULong.MAX_VALUE - actual.first ||
          refunds > ULong.MAX_VALUE - actual.second
      ) {
        verified = false
        break
      }
      charges += actual.first
      refunds += actual.second
    }
    val actual =
      if (verified && completedCommands == commands.size) {
        if (charges > refunds) charges - refunds else 0uL
      } else null
    return NetworkFee(
      charged.fold(0uL) { total, fee -> total + fee.estimate },
      charged.fold(0uL) { total, fee -> total + fee.maximum },
      covered,
      actual,
    )
  }

  fun reservedMaximum(): ULong = phaseFees.values.fold(0uL) { total, fee -> total + fee.maximum }

  companion object Key : CoroutineContext.Key<ConfirmedOrderFees>
}

class DefaultTradingRepository(
  database: FlareDatabase,
  private val client: DecibelClient,
  private val aptos: Aptos,
  private val wallets: AptosSigningWallet,
  private val sessions: SessionRepository,
  private val gasSponsorship: GasSponsorshipRepository,
  private val runtime: FlareRuntimeConfig,
  private val preferences: AppPreferences,
  submissionGate: AptosSubmissionGate,
  private val feeMetadata: FundingFeeMetadata = FundingFeeMetadata(aptos),
) : DecibelTrading {
  private val journal: TransactionJournalDao = database.transactionJournalDao()
  private val submissionMutex = submissionGate.mutex

  /** Reviews awaiting confirmation, by id: each is consumed once, and only the latest few stay. */
  private val orderFeeReviews = LinkedHashMap<String, ConfirmedOrderFees>()
  private val orderFeeReviewMutex = Mutex()
  private var orderFeeReviewCounter = 0L

  override suspend fun prepareOrderFees(actions: List<TradeAction>): OrderFeeReview? =
    prepareOrderFees { actions }

  override suspend fun prepareOrderFees(
    actions: suspend () -> List<TradeAction>,
  ): OrderFeeReview? {
    val selected = preferences.values.first()
    val api = selected.apiWalletAddress ?: return null
    val subaccount = selected.selectedSubaccount ?: return null
    val generation = wallets.authorizationGeneration
    wallets.requireAuthorization(generation)
    val prompt =
      VaultPrompt("Review network fees", "Confirm your identity", requireFreshAuthorization = false)
    val apiAddress = AccountAddress.fromString(api)
    // Every read runs at once, and each simulation starts as soon as its own inputs are in.
    val reviewed: List<TradeAction>
    val commands: List<DecibelCommand>
    val owner: String?
    val price: ULong
    val options: TransactionOptions
    val sponsorshipAvailable: Boolean?
    val apiBalance: ULong?
    val ownerBalance: ULong?
    val first: NetworkFee
    val entry: NetworkFee?
    val ownerQuote: Pair<NetworkFee, TransactionOptions>?
    val apiQuote: Pair<NetworkFee, TransactionOptions>?
    coroutineScope {
      // The order may still be reading its price bound while the account is read.
      val pending = async {
        actions().also {
          require(
            it.size in 1..2 &&
              it.last() is TradeAction.PlaceOrder &&
              it.dropLast(1).all { action -> action is TradeAction.ConfigureMarket },
          ) {
            "Review one order and its optional leverage change"
          }
        }
      }
      val pendingCommands = async {
        pending
          .await()
          .map { it.toDecibelCommand(client.config.deployment.usdcMetadataAddress) }
          .also {
            require(
              it.all { command ->
                command.requiredSigner() == TradingSigner.API &&
                  command.subaccountAddress()?.sameAptosAddress(subaccount) == true
              },
            ) {
              "The reviewed order does not match the selected account"
            }
          }
      }
      val apiIdentity = async {
        wallets.withApiAccount(prompt) { account ->
          check(account.accountAddress.toString().sameAptosAddress(api)) {
            "The trading key changed"
          }
          AptosSimulationIdentity(account.accountAddress, account.publicKey)
        }
      }
      // Simulating the owner as fee payer needs only its public key, which stays cached.
      val ownerIdentity = async {
        selected.ownerAddress?.let { address ->
          try {
            simulationIdentity(
              FeeContext(selected.activeProfileId, address, subaccount, generation),
            )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: xyz.mcxross.flare.security.WalletVaultException.Unavailable) {
            null
          }
        }
      }
      val gasPrice = async { feeMetadata.price(apiAddress) }
      val nextSequence = async {
        when (val result = aptos.accounts.get(apiAddress)) {
          is AptosResult.Success -> result.value.sequenceNumber
          is AptosResult.Failure -> null
        }
      }
      val sponsorship = async { gasSponsorship.tradingSponsorshipAvailable() }
      val apiFunds = async { feeBalance(api) }
      val ownerFunds = async { selected.ownerAddress?.let { feeBalance(it) } }
      val reviewOptions = async {
        TransactionOptions(
          maxGasAmount = 50_000uL,
          gasUnitPrice = gasPrice.await(),
          expirationTimestampSecs = Clock.System.now().epochSeconds.toULong() + 120uL,
        )
      }
      // Simulations reuse the sequence just read; signing reads its own.
      val simulated = async {
        reviewOptions
          .await()
          .copy(replayProtection = nextSequence.await()?.let(ReplayProtection::SequenceNumber))
      }
      // Estimate through the unsigned zero-placeholder fee payer, so a temporary 50k gas probe
      // does not demand 0.05 APT before a much smaller signed reserve can be calculated.
      val firstFee = async {
        orderCommandFee(apiIdentity.await(), pendingCommands.await().first(), simulated.await())
      }
      val entryFee = async {
        val phases = pendingCommands.await()
        if (phases.size > 1)
          entryFeeBeforeSetup(apiIdentity.await(), phases.last(), simulated.await())
        else null
      }
      val ownerFee = async {
        ownerIdentity.await()?.let { key ->
          localOrderQuote(
            apiIdentity.await(),
            pendingCommands.await().first(),
            simulated.await(),
            ownerFunds.await(),
            SimulatedFeePayer(key),
          )
        }
      }
      // The trading key pays only when the owner can't; an unfunded key costs no request.
      val apiFee = async {
        localOrderQuote(
          apiIdentity.await(),
          pendingCommands.await().first(),
          simulated.await(),
          apiFunds.await(),
          null,
        )
      }
      reviewed = pending.await()
      commands = pendingCommands.await()
      owner = ownerIdentity.await()?.let { selected.ownerAddress }
      price = gasPrice.await()
      options = reviewOptions.await()
      sponsorshipAvailable = sponsorship.await()
      apiBalance = apiFunds.await()
      ownerBalance = ownerFunds.await()
      first = firstFee.await()
      entry = entryFee.await()
      ownerQuote = ownerFee.await()
      apiQuote = if (ownerQuote == null) apiFee.await() else null.also { apiFee.cancel() }
    }
    val ownerPays = ownerQuote != null || (apiQuote == null && owner != null)
    val localQuote = ownerQuote ?: apiQuote
    val firstOptions =
      localQuote?.second?.copy(replayProtection = null) ?: bufferedOrderOptions(options, first)
    val reserve = localQuote?.second?.maxGasAmount?.times(price)
    val payer = if (ownerPays) checkNotNull(owner) else api
    val payerBalance = if (ownerPays) ownerBalance else apiBalance
    wallets.requireAuthorization(generation)
    val current = preferences.values.first()
    check(
      current.activeProfileId == selected.activeProfileId &&
        current.selectedSubaccount == subaccount &&
        current.apiWalletAddress == api &&
        current.ownerAddress == selected.ownerAddress,
    ) {
      "The selected account changed. Review the order again."
    }
    val maximum = 50_000uL * price
    val review =
      OrderFeeReview(
        reviewId = "order-fees:${++orderFeeReviewCounter}",
        actions = reviewed.toList(),
        maximumPerTransactionOctas = maximum,
        maximumTotalOctas = maximum * commands.size.toULong(),
        tradingKeyBalance = apiBalance,
        tradingKeyAddress = api,
        sponsorshipAvailable = sponsorshipAvailable,
        estimatedFeeOctas =
          (localQuote?.first?.estimate ?: first.estimate.takeIf { sponsorshipAvailable != false })
            ?.let { if (commands.size == 1) it else entry?.estimate?.plus(it) },
        feePayerAddress = payer,
        feePayerBalance = payerBalance,
        ownerPaysFees = ownerPays,
        requiredReserveOctas = reserve,
        localFeeReady = localQuote != null,
      )
    val plan =
      ConfirmedOrderFees(
        review,
        commands,
        selected.activeProfileId,
        subaccount,
        generation,
        options,
        firstOptions,
        false,
      )
    orderFeeReviewMutex.withLock {
      orderFeeReviews[review.reviewId] = plan
      while (orderFeeReviews.size > PENDING_FEE_REVIEWS) {
        orderFeeReviews.remove(orderFeeReviews.keys.first())
      }
    }
    return review
  }

  private suspend fun feeBalance(address: String): ULong? =
    when (
      val result =
        aptos.accounts.getBalance(
          AccountAddress.fromString(address),
          AccountAsset.coin(APTOS_COIN),
        )
    ) {
      is AptosResult.Success -> result.value
      is AptosResult.Failure -> null
    }

  private suspend fun orderCommandFee(
    identity: AptosSimulationIdentity,
    command: DecibelCommand,
    options: TransactionOptions,
    localFeePayer: TransactionSigner? = null,
    externalFeePayer: Boolean = true,
  ): NetworkFee {
    val payload =
      when (val result = client.trading.payload(command)) {
        is AptosResult.Success -> result.value
        is AptosResult.Failure -> error(result.error.toString())
      }
    return when (
      val result =
        feeExecutor.prepare(
          identity,
          payload,
          feePayer = localFeePayer,
          externalFeePayer = externalFeePayer,
          options = options,
        )
    ) {
      is AptosResult.Success ->
        NetworkFee(result.value.estimatedGasCostOctas, result.value.maximumGasCostOctas)
      is AptosResult.Failure -> error(result.error.toString())
    }
  }

  /**
   * The entry's gas does not depend on the pending leverage, so simulating it against current
   * state prices it closely. Display only: the signed entry is simulated again after setup.
   */
  private suspend fun entryFeeBeforeSetup(
    identity: AptosSimulationIdentity,
    command: DecibelCommand,
    options: TransactionOptions,
  ): NetworkFee? =
    try {
      orderCommandFee(identity, command, options)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }

  private class WalletFee(val address: String, val ownerPays: Boolean, val options: TransactionOptions)

  /**
   * Who pays when Flare can't: the owner's wallet, else the trading key, each only within a budget
   * its exact transaction simulates under.
   */
  private suspend fun walletFeeFor(
    signer: Account,
    command: DecibelCommand,
    owner: String?,
    prompt: VaultPrompt,
  ): WalletFee? {
    val identity = AptosSimulationIdentity(signer.accountAddress, signer.publicKey)
    val options =
      TransactionOptions(
        maxGasAmount = 50_000uL,
        gasUnitPrice = feeMetadata.price(identity.address),
        expirationTimestampSecs = Clock.System.now().epochSeconds.toULong() + 120uL,
      )
    if (owner != null) {
      val quote =
        try {
          wallets.withOwnerAccount(prompt.copy(requireFreshAuthorization = false)) { account ->
            check(account.accountAddress.toString().sameAptosAddress(owner)) {
              "The owner key changed"
            }
            localOrderQuote(identity, command, options, feeBalance(owner), account)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: xyz.mcxross.flare.security.WalletVaultException.Unavailable) {
          null
        }
      if (quote != null) return WalletFee(owner, ownerPays = true, quote.second)
    }
    val api = signer.accountAddress.toString()
    return localOrderQuote(identity, command, options, feeBalance(api), null)?.let {
      WalletFee(api, ownerPays = false, it.second)
    }
  }

  private suspend fun localOrderQuote(
    identity: AptosSimulationIdentity,
    command: DecibelCommand,
    options: TransactionOptions,
    balance: ULong?,
    owner: TransactionSigner?,
  ): Pair<NetworkFee, TransactionOptions>? {
    val price = checkNotNull(options.gasUnitPrice)
    val affordable =
      balance
        ?.div(price)
        ?.let { minOf(it, checkNotNull(options.maxGasAmount)) }
        ?.takeIf { it >= SDK_MIN_GAS_AMOUNT } ?: return null
    return try {
      val probe = options.copy(maxGasAmount = affordable)
      val fee = orderCommandFee(identity, command, probe, owner, externalFeePayer = false)
      // A lower gas limit leaves the same execution, and signing simulates the exact transaction.
      val tight = bufferedOrderOptions(probe, fee)
      NetworkFee(fee.estimate, checkNotNull(tight.maxGasAmount) * price) to tight
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }
  }

  private fun bufferedOrderOptions(
    options: TransactionOptions,
    fee: NetworkFee,
  ): TransactionOptions {
    val price = checkNotNull(options.gasUnitPrice)
    val used = fee.estimate / price
    val limit = minOf(checkNotNull(options.maxGasAmount), maxOf(SDK_MIN_GAS_AMOUNT, used * 2uL))
    return options.copy(maxGasAmount = limit)
  }

  override suspend fun <T> confirmedOrderFees(
    review: OrderFeeReview,
    allowSelfPay: Boolean,
    prompt: VaultPrompt,
    block: suspend () -> T,
  ): T {
    // A reviewed permission can be consumed only once.
    val plan = orderFeeReviewMutex.withLock { orderFeeReviews.remove(review.reviewId) }
    check(plan != null && plan.review == review) {
      "Review network fees again before confirming the order"
    }
    verifyOrderFees(plan)
    check(review.sponsorshipAvailable != false || allowSelfPay) {
      "Trading sponsorship is unavailable. Review and allow the APT network fee before confirming."
    }
    if (allowSelfPay)
      check(review.canSelfPay) {
        "The fee-paying wallet does not have enough APT for this transaction"
      }
    suspend fun runConfirmed(owner: Account?): T {
      val confirmed =
        ConfirmedOrderFees(
          review,
          plan.commands,
          plan.profileId,
          plan.subaccount,
          plan.generation,
          plan.options,
          plan.firstPhaseOptions,
          allowSelfPay,
          owner,
        )
      verifyOrderFees(confirmed)
      return withContext(confirmed) { block() }
    }
    return confirmedAction(prompt) {
      if (review.ownerPaysFees)
        wallets.withOwnerAccount(prompt.copy(requireFreshAuthorization = false)) { owner ->
          check(owner.accountAddress.toString().sameAptosAddress(review.feePayerAddress)) {
            "The fee-paying wallet changed"
          }
          runConfirmed(owner)
        }
      else runConfirmed(null)
    }
  }

  private suspend fun verifyOrderFees(plan: ConfirmedOrderFees) {
    wallets.requireAuthorization(plan.generation)
    val selected = preferences.values.first()
    check(
      selected.activeProfileId == plan.profileId &&
        selected.selectedSubaccount == plan.subaccount &&
        selected.apiWalletAddress == plan.review.tradingKeyAddress &&
        (!plan.review.ownerPaysFees || selected.ownerAddress == plan.review.feePayerAddress),
    ) {
      "The selected account changed. Review the order again."
    }
    check(
      Clock.System.now().epochSeconds.toULong() <
        checkNotNull(plan.options.expirationTimestampSecs),
    ) {
      "The network fee review expired. Review the order again."
    }
  }

  private data class FeeContext(
    val profileId: String,
    val owner: String,
    val account: String?,
    val generation: Long,
  )

  private val feeIdentityMutex = Mutex()
  private var feeIdentity: Pair<FeeContext, AptosSimulationIdentity>? = null
  private val feeExecutor = AptosTransactionExecutor(aptos)

  private suspend fun simulationIdentity(context: FeeContext): AptosSimulationIdentity =
    feeIdentityMutex.withLock {
      wallets.requireAuthorization(context.generation)
      feeIdentity?.takeIf { it.first == context }?.second
        ?: wallets.withOwnerAccount(
          VaultPrompt("Transfer", "Confirm your identity"),
        ) { account ->
          check(account.accountAddress.toString().sameAptosAddress(context.owner))
          AptosSimulationIdentity(account.accountAddress, account.publicKey).also {
            feeIdentity = context to it
          }
        }
    }

  override suspend fun warmFundingFees() {
    val selected = preferences.values.first()
    val owner = selected.ownerAddress ?: return
    val account = selected.selectedSubaccount ?: return
    val context =
      FeeContext(selected.activeProfileId, owner, account, wallets.authorizationGeneration)
    wallets.requireAuthorization(context.generation)
    val session = sessions.ensureOwner(account, VaultPrompt("Transfer", "Confirm your identity"))
    sessions
      .bind(
        session,
        flow {
          coroutineScope {
            val identity = async { simulationIdentity(context) }
            val payload = async {
              client.trading.payload(
                DecibelCommand.Deposit(account, client.config.deployment.usdcMetadataAddress, 1uL),
              )
            }
            val price = async {
              feeMetadata.price(xyz.mcxross.kaptos.model.AccountAddress.fromString(owner))
            }
            identity.await()
            payload.await()
            price.await()
          }
          wallets.requireAuthorization(context.generation)
          emit(Unit)
        },
      )
      .first()
  }

  override val pendingTransactions: Flow<List<PendingTransaction>> =
    journal
      .observePending()
      .combine(preferences.values) { entries, saved ->
        entries
          .filter { it.profileId == saved.activeProfileId && it.network == runtime.network.name }
          .map(PendingTransactionEntity::toDomain)
      }
      .distinctUntilChanged()

  override fun execute(
    action: TradeAction,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<ActionState> = flow {
    val command =
      try {
        action.toDecibelCommand(client.config.deployment.usdcMetadataAddress)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        emit(
          ActionState.Failed(
            error.message ?: "This action isn’t available",
            definitelyNotSubmitted = true,
          ),
        )
        return@flow
      }
    if (command is DecibelCommand.SetPositionTpSl) {
      emitAll(setPositionTpSl(command, prompt, feePayment, onPrepared))
    } else {
      emitAll(executeCommand(command, prompt, feePayment, onPrepared))
    }
  }

  private fun setPositionTpSl(
    command: DecibelCommand.SetPositionTpSl,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<ActionState> {
    val kinds =
      setOfNotNull(
        ExitKind.TAKE_PROFIT.takeIf { command.takeProfitTrigger != null },
        ExitKind.STOP_LOSS.takeIf { command.stopLossTrigger != null },
      )
    return ExitReplacement(
        openExits = { exitsOf(command.subaccount, command.market, kinds, prompt) },
        cancel = { exit ->
          // Part of the change already confirmed, so it doesn't ask again.
          withContext(ConfirmedAction()) {
            val asExit =
              DecibelCommand.CancelPositionTpSl(command.subaccount, command.market, exit.orderId)
            val asOrder =
              DecibelCommand.CancelOrder(command.subaccount, command.market, exit.orderId)
            // Exits Decibel keeps for the position cancel as exits, ones on the book as orders. One
            // refused before it was ever sent, as in simulation, costs nothing, so the other is
            // tried.
            val (first, second) = if (exit.isPositionTpSl) asExit to asOrder else asOrder to asExit
            val result = executeCommand(first, prompt, feePayment).last()
            if (result is ActionState.Failed && result.reference == null && !result.committed) {
              executeCommand(second, prompt, feePayment).last()
            } else {
              result
            }
          }
        },
      )
      .replace(executeCommand(command, prompt, feePayment, onPrepared))
  }

  /** The open exits of [kinds] guarding the position in [market], read just now. */
  private suspend fun exitsOf(
    subaccount: String,
    market: String,
    kinds: Set<ExitKind>,
    prompt: VaultPrompt,
  ): List<OpenOrder> {
    val session = sessions.ensureTrading(subaccount, prompt.copy(requireFreshAuthorization = false))
    return sessions
      .bind(
        session,
        flow {
          val position =
            client.accounts.positions(subaccount, market).firstOrNull { !it.isDeleted }
              ?: return@flow emit(emptyList())
          val orders = client.accounts.openOrders(subaccount).items.map { it.toOpenOrder() }
          emit(
            orders.exitsFor(market, closesWithBuy = !position.isLong).filter {
              it.exitKind in kinds
            },
          )
        },
      )
      .first()
  }

  override suspend fun estimateCommandFee(command: DecibelCommand): NetworkFee? {
    require(command.isFundingTransfer())
    val selected = preferences.values.first()
    val owner = selected.ownerAddress ?: return null
    val generation = wallets.authorizationGeneration
    wallets.requireAuthorization(generation)
    val session =
      sessions.ensureOwner(
        command.subaccountAddress(),
        VaultPrompt("Transfer", "Confirm your identity"),
      )
    val context =
      FeeContext(selected.activeProfileId, owner, selected.selectedSubaccount, generation)
    return sessions
      .bind(
        session,
        flow {
          val fee = coroutineScope {
            val identity = async { simulationIdentity(context) }
            val payload = async { client.trading.payload(command) }
            val options = async {
              feeMetadata.options(xyz.mcxross.kaptos.model.AccountAddress.fromString(owner))
            }
            val data =
              when (val result = payload.await()) {
                is AptosResult.Success -> result.value
                is AptosResult.Failure -> return@coroutineScope null
              }
            when (
              val result =
                feeExecutor.prepare(
                  identity.await(),
                  data,
                  externalFeePayer = true,
                  options = options.await(),
                )
            ) {
              is AptosResult.Success ->
                NetworkFee(result.value.estimatedGasCostOctas, result.value.maximumGasCostOctas)
              is AptosResult.Failure -> null
            }
          }
          wallets.requireAuthorization(generation)
          val current = preferences.values.first()
          check(
            current.activeProfileId == selected.activeProfileId &&
              current.selectedSubaccount == selected.selectedSubaccount,
          )
          emit(fee)
        },
      )
      .first()
  }

  override fun executeCommand(
    command: DecibelCommand,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<ActionState> = flow {
    // An action the person confirmed while an earlier one is still settling waits its turn: the
    // next transaction's sequence number exists only once that one lands.
    submissionMutex.lock()

    var preparedHash: String? = null
    try {
      val selected = preferences.values.first()
      val orderPlan = currentCoroutineContext()[ConfirmedOrderFees]
      val orderPhase = orderPlan?.nextCommand ?: 0
      if (orderPlan != null) {
        check(orderPlan.completedCommands == orderPhase) {
          "An earlier order phase did not complete. Review the order again."
        }
        verifyOrderFees(orderPlan)
        check(orderPlan.commands.getOrNull(orderPlan.nextCommand) == command) {
          "The order changed after network fees were reviewed"
        }
        check(feePayment == FeePayment.SPONSORED) { "Use the reviewed order fee choice" }
        orderPlan.nextCommand++
      }
      val automatic = feePayment == FeePayment.AUTOMATIC
      val walletFallback = feePayment == FeePayment.WALLET_FALLBACK
      val boundedFallback = automatic || walletFallback || orderPlan?.allowSelfPay == true
      require(!automatic || command.isFundingTransfer()) {
        "Automatic fee fallback is limited to transfers"
      }
      require(!walletFallback || command.requiredSigner() == TradingSigner.API) {
        "Wallet fee fallback is limited to trading actions"
      }
      val generation = wallets.authorizationGeneration
      wallets.requireAuthorization(generation)
      val signer = command.requiredSigner()
      when (signer) {
        TradingSigner.OWNER ->
          check(selected.ownerAddress != null) { "Use a device that holds the owner key" }
        TradingSigner.API ->
          check(selected.apiWalletAddress != null) { "Finish setting up trading on this device" }
      }
      val subaccount = command.subaccountAddress()
      require(
        subaccount == null || selected.selectedSubaccount?.sameAptosAddress(subaccount) == true,
      ) {
        "The selected account changed. Review the action again."
      }
      // Trading keys sign silently unless the person asked to confirm each action (the default).
      // Owner actions already authenticate on their own, every time.
      if (
        signer == TradingSigner.API &&
          selected.confirmTransactions &&
          currentCoroutineContext()[ConfirmedAction] == null
      ) {
        wallets.confirmIdentity(prompt)
      }
      val activeSession =
        if (signer == TradingSigner.API) {
          sessions.ensureTrading(
            checkNotNull(subaccount),
            prompt.copy(requireFreshAuthorization = false),
          )
        } else {
          sessions.authenticateOwner(
            subaccount,
            if (automatic) prompt.copy(requireFreshAuthorization = false) else prompt,
          )
        }
      emitAll(
        sessions.bind(
          activeSession,
          flow {
            reconcilePending()
            check(
              pendingTransactions.first().none {
                it.operation == command.journalName() || it.operation == "SWAP"
              },
            ) {
              "An earlier transaction is pending. Check Activity before trying again."
            }
            withSigner(signer, prompt.copy(requireFreshAuthorization = false)) { account ->
              check(
                activeSession.walletAddress?.equals(
                  account.accountAddress.toString(),
                  ignoreCase = true,
                ) == true,
              ) {
                "The authenticated Worker session does not match the selected signing wallet"
              }
              var payment =
                if (orderPlan?.review?.sponsorshipAvailable == false) {
                  check(orderPlan.allowSelfPay) {
                    "Allow the reviewed APT network fee before confirming"
                  }
                  FeePayment.SELF_PAY
                } else if (automatic || walletFallback) FeePayment.SPONSORED else feePayment
              val initialOptions =
                if (orderPlan != null) {
                  if (orderPhase == 0) orderPlan.firstPhaseOptions
                  else {
                    val identity =
                      AptosSimulationIdentity(account.accountAddress, account.publicKey)
                    val baseline = orderCommandFee(identity, command, orderPlan.options)
                    val local =
                      if (orderPlan.allowSelfPay)
                        localOrderQuote(
                          identity,
                          command,
                          orderPlan.options,
                          feeBalance(orderPlan.review.feePayerAddress),
                          orderPlan.localFeePayer,
                        )
                      else null
                    if (payment == FeePayment.SELF_PAY)
                      checkNotNull(local) {
                          "The fee-paying wallet cannot cover this transaction's network fee"
                        }
                        .second
                    else local?.second ?: bufferedOrderOptions(orderPlan.options, baseline)
                  }
                } else if (automatic)
                  TransactionOptions(
                    expirationTimestampSecs = Clock.System.now().epochSeconds.toULong() + 60uL,
                  )
                else null
              var fallbackOptions: TransactionOptions? = null
              var fee: NetworkFee? = null
              var authorized = false
              suspend fun verifySelection() {
                wallets.requireAuthorization(generation)
                val current = preferences.values.first()
                check(
                  current.activeProfileId == selected.activeProfileId &&
                    current.selectedSubaccount == selected.selectedSubaccount,
                ) {
                  "The selected account changed. Review the action again."
                }
              }
              var walletQuote: WalletFee? = null
              while (true) {
                var retrySelfPay = false
                suspend fun attempt(localFeePayer: Account?) =
                  client.trading
                    .execute(
                      signer = account,
                      command = command,
                      feePayer = localFeePayer,
                      externalFeePayer =
                        if (payment == FeePayment.SPONSORED) gasSponsorship.submitterFor(command)
                        else null,
                      options =
                        if (payment == FeePayment.SELF_PAY) fallbackOptions ?: initialOptions
                        else initialOptions,
                      onSimulation = { result ->
                        if (automatic || orderPlan != null) {
                          val price = result.gasUnitPrice.toULong()
                          val limit = result.maxGasAmount.toULong()
                          val used = result.gasUsed.toULong()
                          require(price in 1uL..1_000uL && limit <= 50_000uL && used <= limit) {
                            "Transfer fees exceed the supported limits"
                          }
                          val currentFee = NetworkFee(used * price, limit * price)
                          if (orderPlan != null) {
                            check(
                              price == orderPlan.options.gasUnitPrice &&
                                limit <= checkNotNull(orderPlan.options.maxGasAmount) &&
                                currentFee.maximum <= orderPlan.review.maximumPerTransactionOctas,
                            ) {
                              "The order network fee exceeds the reviewed limit"
                            }
                          }
                          if (fallbackOptions == null) {
                            fallbackOptions =
                              TransactionOptions(
                                maxGasAmount = limit,
                                gasUnitPrice = price,
                                expirationTimestampSecs = initialOptions?.expirationTimestampSecs,
                              )
                          } else {
                            check(currentFee.maximum <= checkNotNull(fee).maximum) {
                              "The transfer fee changed. Try again."
                            }
                          }
                          fee = currentFee
                          if (orderPlan != null) {
                            val balance = feeBalance(orderPlan.review.feePayerAddress)
                            if (payment == FeePayment.SELF_PAY)
                              check(balance != null && balance >= currentFee.maximum) {
                                "The fee-paying wallet does not have enough APT for this transaction"
                              }
                            orderPlan.phaseFees[orderPhase] =
                              currentFee.copy(covered = payment == FeePayment.SPONSORED)
                            check(
                              orderPlan.totalFee().maximum <= orderPlan.review.maximumTotalOctas,
                            ) {
                              "The order network fee exceeds the reviewed total"
                            }
                          }
                          emit(ActionState.FeeEstimated(orderPlan?.totalFee() ?: currentFee))
                        }
                      },
                      beforeSign = {
                        verifySelection()
                        if (orderPlan != null) verifyOrderFees(orderPlan)
                        if (automatic && prompt.requireFreshAuthorization && !authorized) {
                          wallets.confirmIdentity(prompt.copy(requireFreshAuthorization = true))
                          authorized = true
                        }
                        verifySelection()
                        if (automatic)
                          check(
                            Clock.System.now().epochSeconds.toULong() <
                              checkNotNull(fallbackOptions?.expirationTimestampSecs),
                          ) {
                            "The transfer expired. Try again."
                          }
                      },
                      onPrepared = { hash ->
                        check(journal.find(hash) == null) {
                          "This signed transaction is already pending reconciliation"
                        }
                        val now = Clock.System.now().toEpochMilliseconds()
                        journal.upsert(
                          PendingTransactionEntity(
                            hash = hash,
                            network = runtime.network.name,
                            operation =
                              journalOperation(command.journalName(), selected.activeProfileId),
                            state = JournalState.PREPARED,
                            createdAtMs = now,
                            updatedAtMs = now,
                          ),
                        )
                        preparedHash = hash
                        onPrepared(hash)
                      },
                    )
                    .collect { state ->
                      val hash = preparedHash
                      when (state) {
                        TransactionState.Submitting -> hash?.updateState(JournalState.SUBMITTING)
                        is TransactionState.Pending -> {
                          if (hash != null && !hash.equals(state.hash, ignoreCase = true)) {
                            check(
                              journal.replaceHash(
                                oldHash = hash,
                                newHash = state.hash,
                                state = JournalState.PENDING,
                                updatedAtMs = Clock.System.now().toEpochMilliseconds(),
                              ) == 1,
                            ) {
                              "The pending transaction journal entry is unavailable"
                            }
                            preparedHash = state.hash
                          } else {
                            hash?.updateState(JournalState.PENDING)
                          }
                        }
                        is TransactionState.Committed -> {
                          hash?.let { journal.remove(it) }
                          if (orderPlan != null) {
                            orderPlan.completedCommands = orderPhase + 1
                            orderPlan.committedFees[orderPhase] =
                              state.gasChargeOctas?.let { charge ->
                                state.storageRefundOctas?.let { refund -> charge to refund }
                              }
                          }
                        }
                        is TransactionState.Failed -> {
                          if (state.committed || state.definitelyNotSubmitted) {
                            hash?.let { journal.remove(it) }
                          } else {
                            hash?.updateState(JournalState.RECONCILE_REQUIRED)
                          }
                        }
                        TransactionState.Simulating,
                        TransactionState.AwaitingAuthorization -> Unit
                      }
                      val failure = state as? TransactionState.Failed
                      // The wallet fallback checks its payer by simulating the exact transaction.
                      val eligible =
                        failure?.selfPayEstimateOctas?.takeUnless { walletFallback }?.let { estimate ->
                          val required = failure.selfPayReserveOctas ?: estimate
                          when (
                            val balance =
                              aptos.accounts.getBalance(
                                orderPlan?.review?.feePayerAddress?.let(AccountAddress::fromString)
                                  ?: account.accountAddress,
                                AccountAsset.coin(APTOS_COIN),
                              )
                          ) {
                            is AptosResult.Success -> balance.value >= required
                            is AptosResult.Failure -> false
                          }
                        }
                      if (
                        boundedFallback &&
                          payment == FeePayment.SPONSORED &&
                          failure != null &&
                          failure.definitelyNotSubmitted &&
                          !failure.committed &&
                          failure.selfPayEstimateOctas != null &&
                          (walletFallback || eligible == true)
                      ) {
                        retrySelfPay = true
                      } else {
                        val mapped = state.toActionState()
                        val networkFee =
                          orderPlan?.totalFee()
                            ?: fee?.copy(covered = payment == FeePayment.SPONSORED)
                        emit(
                          when {
                            (automatic || orderPlan != null) && mapped is ActionState.Pending ->
                              mapped.copy(networkFee = networkFee)
                            (automatic || orderPlan != null) && mapped is ActionState.Committed ->
                              mapped.copy(networkFee = networkFee)
                            automatic && mapped is ActionState.Failed ->
                              mapped.copy(
                                selfPay = null,
                                message =
                                  if (eligible == false && failure?.definitelyNotSubmitted == true)
                                    "Fees require up to ${failure.selfPayReserveOctas?.toDecimalString(8) ?: "—"} APT"
                                  else mapped.message,
                              )
                            else -> mapped
                          },
                        )
                      }
                    }
                val payer = walletQuote
                if (payer?.ownerPays == true)
                  wallets.withOwnerAccount(prompt.copy(requireFreshAuthorization = false)) { owner ->
                    check(owner.accountAddress.toString().sameAptosAddress(payer.address)) {
                      "The fee-paying wallet changed"
                    }
                    attempt(owner)
                  }
                else attempt(orderPlan?.localFeePayer?.takeIf { payment == FeePayment.SELF_PAY })
                if (!retrySelfPay) break
                preparedHash = null
                verifySelection()
                if (walletFallback) {
                  val quote = walletFeeFor(account, command, selected.ownerAddress, prompt)
                  if (quote == null) {
                    emit(ActionState.Failed(WALLET_FEE_UNAVAILABLE, definitelyNotSubmitted = true))
                    break
                  }
                  walletQuote = quote
                  fallbackOptions = quote.options
                }
                payment = FeePayment.SELF_PAY
              }
            }
          },
        ),
      )
    } catch (cancelled: CancellationException) {
      preparedHash?.updateState(JournalState.RECONCILE_REQUIRED)
      throw cancelled
    } catch (error: Throwable) {
      preparedHash?.updateState(JournalState.RECONCILE_REQUIRED)
      emit(
        ActionState.Failed(
          error.message ?: "Transaction authorization failed",
          reference = preparedHash,
          definitelyNotSubmitted = preparedHash == null,
        ),
      )
    } finally {
      if (command.requiredSigner() == TradingSigner.OWNER && !command.isFundingTransfer()) {
        // Funding commands release their gate immediately; the next action obtains its own session.
        try {
          wallets.requireAuthorization(wallets.authorizationGeneration)
          val saved = preferences.values.first()
          val selected = saved.selectedSubaccount
          if (saved.apiWalletAddress != null && selected != null) {
            sessions.ensureTrading(selected, prompt.copy(requireFreshAuthorization = false))
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          /* Retry session renewal with the next action. */
        } finally {
          submissionMutex.unlock()
        }
      } else submissionMutex.unlock()
    }
  }

  override suspend fun <T> confirmedAction(prompt: VaultPrompt, block: suspend () -> T): T {
    if (
      currentCoroutineContext()[ConfirmedAction] == null &&
        preferences.values.first().confirmTransactions
    ) {
      wallets.confirmIdentity(prompt)
    }
    return withContext(ConfirmedAction()) { block() }
  }

  override suspend fun transactionStatus(reference: String): ActionState {
    val hash =
      if (reference.startsWith(SPONSOR_REFERENCE_PREFIX)) {
        when (
          val result = gasSponsorship.resolve(reference.removePrefix(SPONSOR_REFERENCE_PREFIX))
        ) {
          is AptosResult.Success -> result.value ?: return ActionState.Pending(reference)
          is AptosResult.Failure -> return ActionState.Pending(reference)
        }
      } else reference
    return when (
      val result =
        aptos.transactions.waitForTransaction(
          hash,
          WaitForTransactionOptions(timeoutSecs = 10, checkSuccess = false),
        )
    ) {
      is AptosResult.Failure -> ActionState.Pending(reference)
      is AptosResult.Success -> {
        val response = result.value as? UserTransactionResponse
        if (response == null || !response.hash.equals(hash, true)) ActionState.Pending(reference)
        else {
          journal.remove(reference)
          if (hash != reference) journal.remove(hash)
          if (response.success) ActionState.Committed(hash)
          else ActionState.Failed(response.vmStatus, hash, committed = true)
        }
      }
    }
  }

  override suspend fun tradingKeyFeeBalance(): ULong {
    val address =
      wallets.profile.first().apiWalletAddress ?: error("This device has no trading key")
    return when (
      val result =
        aptos.accounts.getBalance(AccountAddress.fromString(address), AccountAsset.coin(APTOS_COIN))
    ) {
      is AptosResult.Success -> result.value
      is AptosResult.Failure -> error(result.error.toString())
    }
  }

  override suspend fun ownerFeeBalance(): ULong {
    val address = wallets.profile.first().ownerAddress ?: error("This device has no owner key")
    return when (
      val result =
        aptos.accounts.getBalance(AccountAddress.fromString(address), AccountAsset.coin(APTOS_COIN))
    ) {
      is AptosResult.Success -> result.value
      is AptosResult.Failure -> error(result.error.toString())
    }
  }

  override suspend fun assetBalanceDecimal(
    accountAddress: String,
    symbol: String,
  ): ExactAssetBalance {
    val token = symbol.substringBefore('/').trim().uppercase()
    val address = AccountAddress.fromString(accountAddress)
    val asset: AccountAsset
    val decimals: Int
    if (token == "USDC") {
      asset = AccountAsset.fungibleAsset(client.config.deployment.usdcMetadataAddress)
      decimals = 6
    } else {
      val context =
        client.markets.spotAssetContexts().firstOrNull {
          it.name.substringBefore('/').trim().equals(token, ignoreCase = true)
        } ?: error("Balance information for $token is unavailable")
      asset = AccountAsset.fungibleAsset(context.baseAssetAddress)
      decimals = context.baseDecimals
    }
    require(decimals in 0..18) { "Unsupported asset precision" }
    val amount =
      when (val result = aptos.accounts.getBalance(address, asset)) {
        is AptosResult.Success -> result.value.toDecimalString(decimals)
        is AptosResult.Failure -> error("Couldn’t update your $token balance")
      }
    return ExactAssetBalance(amount, amount)
  }

  override suspend fun baseAssetBalance(accountAddress: String, symbol: String): Double =
    assetBalanceDecimal(accountAddress, symbol).available.toDouble()

  override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt): Flow<ActionState> = flow {
    require(amount > 0uL) { "APT top-up amount must be greater than zero" }
    val session =
      sessions.authenticateOwner(
        preferences.values.first().selectedSubaccount,
        prompt.copy(requireFreshAuthorization = true),
      )
    if (session.role != SessionRole.OWNER) {
      emit(ActionState.Failed("Confirm your identity to send APT from your wallet"))
      return@flow
    }
    if (!submissionMutex.tryLock()) {
      emit(ActionState.Failed("Another transaction is already being authorized"))
      return@flow
    }

    var preparedHash: String? = null
    try {
      val recipient =
        wallets.profile.first().apiWalletAddress ?: error("This device has no trading key")
      wallets.withOwnerAccount(prompt.copy(requireFreshAuthorization = false)) { owner ->
        check(
          session.walletAddress?.equals(owner.accountAddress.toString(), ignoreCase = true) == true,
        ) {
          "The owner session does not match the owner signing wallet"
        }
        emit(ActionState.Simulating)
        val transaction =
          when (
            val result =
              aptos.coins.buildTransfer(
                sender = owner.accountAddress,
                recipient = AccountAddress.fromString(recipient),
                amount = amount,
              )
          ) {
            is AptosResult.Success -> result.value
            is AptosResult.Failure -> {
              emit(ActionState.Failed(result.error.toString()))
              return@withOwnerAccount
            }
          }
        when (
          val result =
            aptos.transactions.simulate(
              transaction = transaction,
              senderPublicKey = owner.publicKey,
              options = SimulationOptions(estimateGasUnitPrice = true),
            )
        ) {
          is AptosResult.Failure -> {
            emit(ActionState.Failed(result.error.toString()))
            return@withOwnerAccount
          }
          is AptosResult.Success -> {
            if (result.value.size != 1) {
              emit(ActionState.Failed("Expected exactly one transaction simulation result"))
              return@withOwnerAccount
            }
            val failed = result.value.firstOrNull { !it.success }
            if (failed != null) {
              emit(ActionState.Failed(failed.vmStatus))
              return@withOwnerAccount
            }
          }
        }

        emit(ActionState.AwaitingAuthorization)
        val authenticator =
          when (val result = aptos.transactions.sign(owner, transaction)) {
            is AptosResult.Success -> result.value
            is AptosResult.Failure -> {
              emit(ActionState.Failed(result.error.toString()))
              return@withOwnerAccount
            }
          }
        val hash =
          when (
            val result =
              aptos.transactions.userTransactionHash(
                transaction = transaction,
                senderAuthenticator = authenticator,
              )
          ) {
            is AptosResult.Success -> result.value
            is AptosResult.Failure -> {
              emit(ActionState.Failed(result.error.toString()))
              return@withOwnerAccount
            }
          }
        val now = Clock.System.now().toEpochMilliseconds()
        check(journal.find(hash) == null) { "This transfer is already pending reconciliation" }
        journal.upsert(
          PendingTransactionEntity(
            hash = hash,
            network = runtime.network.name,
            operation =
              journalOperation("TOP_UP_API_WALLET", preferences.values.first().activeProfileId),
            state = JournalState.PREPARED,
            createdAtMs = now,
            updatedAtMs = now,
          ),
        )
        preparedHash = hash

        emit(ActionState.Submitting)
        hash.updateState(JournalState.SUBMITTING)
        val pending =
          when (
            val result =
              aptos.transactions.submit(
                transaction = transaction,
                senderAuthenticator = authenticator,
              )
          ) {
            is AptosResult.Success -> result.value
            is AptosResult.Failure -> {
              hash.updateState(JournalState.RECONCILE_REQUIRED)
              emit(ActionState.Failed(result.error.toString(), reference = hash))
              return@withOwnerAccount
            }
          }
        if (!pending.hash.equals(hash, ignoreCase = true)) {
          hash.updateState(JournalState.RECONCILE_REQUIRED)
          emit(
            ActionState.Failed("Aptos returned a mismatched top-up transaction hash", hash),
          )
          return@withOwnerAccount
        }
        hash.updateState(JournalState.PENDING)
        emit(ActionState.Pending(hash))
        when (
          val result =
            aptos.transactions.waitForTransaction(
              hash,
              WaitForTransactionOptions(checkSuccess = false),
            )
        ) {
          is AptosResult.Failure -> {
            hash.updateState(JournalState.RECONCILE_REQUIRED)
            emit(ActionState.Failed(result.error.toString(), reference = hash))
          }
          is AptosResult.Success -> {
            val response = result.value as? UserTransactionResponse
            if (response == null || !response.hash.equals(hash, ignoreCase = true)) {
              hash.updateState(JournalState.RECONCILE_REQUIRED)
              emit(ActionState.Failed("Unexpected top-up transaction response", reference = hash))
            } else {
              journal.remove(hash)
              if (response.success) {
                emit(ActionState.Committed(hash))
              } else {
                emit(
                  ActionState.Failed(
                    message = response.vmStatus,
                    reference = hash,
                    committed = true,
                  ),
                )
              }
            }
          }
        }
      }
    } catch (cancelled: CancellationException) {
      preparedHash?.updateState(JournalState.RECONCILE_REQUIRED)
      throw cancelled
    } catch (error: Throwable) {
      preparedHash?.updateState(JournalState.RECONCILE_REQUIRED)
      emit(
        ActionState.Failed(
          error.message ?: "The network-fee top-up failed",
          reference = preparedHash,
        ),
      )
    } finally {
      submissionMutex.unlock()
    }
  }

  override suspend fun reconcilePending(): ReconciliationResult {
    var finalized = 0
    var unresolved = 0
    journal.pending().forEach { entry ->
      if (
        entry.network != runtime.network.name ||
          entry.profileId != preferences.values.first().activeProfileId
      )
        return@forEach
      val transactionHash =
        if (entry.hash.startsWith(SPONSOR_REFERENCE_PREFIX)) {
          when (
            val resolved = gasSponsorship.resolve(entry.hash.removePrefix(SPONSOR_REFERENCE_PREFIX))
          ) {
            is AptosResult.Failure -> null
            is AptosResult.Success -> resolved.value
          }?.takeIf { hash ->
            journal.replaceHash(
              oldHash = entry.hash,
              newHash = hash,
              state = JournalState.RECONCILING,
              updatedAtMs = Clock.System.now().toEpochMilliseconds(),
            ) == 1
          }
        } else {
          entry.hash
        }
      if (transactionHash == null) {
        entry.hash.updateState(JournalState.RECONCILE_REQUIRED)
        unresolved += 1
        return@forEach
      }
      transactionHash.updateState(JournalState.RECONCILING)
      when (
        val result =
          aptos.transactions.waitForTransaction(
            transactionHash,
            WaitForTransactionOptions(timeoutSecs = 10, checkSuccess = false),
          )
      ) {
        is AptosResult.Success -> {
          val response = result.value
          if (
            response is UserTransactionResponse &&
              response.hash.equals(transactionHash, ignoreCase = true)
          ) {
            journal.remove(transactionHash)
            finalized += 1
          } else {
            transactionHash.updateState(JournalState.RECONCILE_REQUIRED)
            unresolved += 1
          }
        }
        is AptosResult.Failure -> {
          transactionHash.updateState(JournalState.RECONCILE_REQUIRED)
          unresolved += 1
        }
      }
    }
    return ReconciliationResult(finalized = finalized, unresolved = unresolved)
  }

  private suspend fun <T> withSigner(
    signer: TradingSigner,
    prompt: VaultPrompt,
    block: suspend (Account) -> T,
  ): T =
    when (signer) {
      TradingSigner.OWNER -> wallets.withOwnerAccount(prompt, block)
      TradingSigner.API -> wallets.withApiAccount(prompt, block)
    }

  private suspend fun String.updateState(state: String) {
    val current = journal.find(this) ?: return
    journal.upsert(
      current.copy(state = state, updatedAtMs = Clock.System.now().toEpochMilliseconds()),
    )
  }

  private companion object {
    // Kaptos 1.0.1 rejects smaller explicit limits; preserve its supported transaction API.
    const val SDK_MIN_GAS_AMOUNT = 2_000uL
    const val SPONSOR_REFERENCE_PREFIX = "sponsor:"
    const val WALLET_FEE_UNAVAILABLE = "Add APT to cover network fees."
    // An order's and a close's reviews, prepared ahead on different screens, both stay usable.
    const val PENDING_FEE_REVIEWS = 4
  }
}

private object JournalState {
  const val PREPARED = "PREPARED"
  const val SUBMITTING = "SUBMITTING"
  const val PENDING = "PENDING"
  const val RECONCILING = "RECONCILING"
  const val RECONCILE_REQUIRED = "RECONCILE_REQUIRED"
}

/**
 * The capability boundary: creating accounts, moving collateral, and changing who may trade stay
 * with the owner key. Everything a trader does during a session uses the device trading key.
 */
internal fun DecibelCommand.requiredSigner(): TradingSigner =
  when (this) {
    DecibelCommand.CreateSubaccount,
    is DecibelCommand.Deposit,
    is DecibelCommand.Withdraw,
    is DecibelCommand.TransferCollateral,
    is DecibelCommand.DelegateTrading,
    is DecibelCommand.RevokeDelegation,
    is DecibelCommand.ApproveMaxBuilderFee,
    is DecibelCommand.RevokeMaxBuilderFee,
    is DecibelCommand.ApproveMaxSpotBuilderFee,
    is DecibelCommand.RevokeMaxSpotBuilderFee,
    is DecibelCommand.ContributeToVault,
    is DecibelCommand.PledgeCollateral,
    is DecibelCommand.RedeemFromVault -> TradingSigner.OWNER
    is DecibelCommand.ConfigureMarket,
    is DecibelCommand.PlaceOrder,
    is DecibelCommand.PlaceSpotOrder,
    is DecibelCommand.PlaceTwapOrder,
    is DecibelCommand.CancelOrder,
    is DecibelCommand.CancelSpotOrder,
    is DecibelCommand.CancelTwapOrder,
    is DecibelCommand.CancelPositionTpSl,
    is DecibelCommand.SetPositionTpSl -> TradingSigner.API
  }

private fun DecibelCommand.journalName(): String =
  when (this) {
    DecibelCommand.CreateSubaccount -> "CREATE_SUBACCOUNT"
    is DecibelCommand.Deposit -> "DEPOSIT"
    is DecibelCommand.Withdraw -> "WITHDRAW"
    is DecibelCommand.TransferCollateral -> "TRANSFER_COLLATERAL"
    is DecibelCommand.PledgeCollateral -> "PLEDGE_COLLATERAL"
    is DecibelCommand.DelegateTrading -> "DELEGATE_TRADING"
    is DecibelCommand.RevokeDelegation -> "REVOKE_DELEGATION"
    is DecibelCommand.ConfigureMarket -> "CONFIGURE_MARKET"
    is DecibelCommand.PlaceOrder -> "PLACE_ORDER"
    is DecibelCommand.PlaceSpotOrder -> "PLACE_SPOT_ORDER"
    is DecibelCommand.PlaceTwapOrder -> "PLACE_TWAP_ORDER"
    is DecibelCommand.CancelOrder -> "CANCEL_ORDER"
    is DecibelCommand.CancelSpotOrder -> "CANCEL_SPOT_ORDER"
    is DecibelCommand.CancelTwapOrder -> "CANCEL_TWAP_ORDER"
    is DecibelCommand.CancelPositionTpSl -> "CANCEL_POSITION_TP_SL"
    is DecibelCommand.SetPositionTpSl -> "SET_POSITION_TP_SL"
    is DecibelCommand.ApproveMaxBuilderFee -> "APPROVE_MAX_BUILDER_FEE"
    is DecibelCommand.RevokeMaxBuilderFee -> "REVOKE_MAX_BUILDER_FEE"
    is DecibelCommand.ApproveMaxSpotBuilderFee -> "APPROVE_MAX_SPOT_BUILDER_FEE"
    is DecibelCommand.RevokeMaxSpotBuilderFee -> "REVOKE_MAX_SPOT_BUILDER_FEE"
    is DecibelCommand.ContributeToVault -> "CONTRIBUTE_TO_VAULT"
    is DecibelCommand.RedeemFromVault -> "REDEEM_FROM_VAULT"
  }

private fun PendingTransactionEntity.toDomain(): PendingTransaction =
  PendingTransaction(
    hash = hash,
    network = network,
    operation = operationName,
    state = state,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
  )

internal fun DecibelCommand.subaccountAddress(): String? =
  when (this) {
    DecibelCommand.CreateSubaccount -> null
    is DecibelCommand.Deposit -> subaccount
    is DecibelCommand.Withdraw -> subaccount
    is DecibelCommand.TransferCollateral -> subaccount
    is DecibelCommand.PledgeCollateral -> subaccount
    is DecibelCommand.DelegateTrading -> subaccount
    is DecibelCommand.RevokeDelegation -> subaccount
    is DecibelCommand.ConfigureMarket -> subaccount
    is DecibelCommand.PlaceOrder -> subaccount
    is DecibelCommand.PlaceSpotOrder -> subaccount
    is DecibelCommand.PlaceTwapOrder -> subaccount
    is DecibelCommand.CancelOrder -> subaccount
    is DecibelCommand.CancelSpotOrder -> subaccount
    is DecibelCommand.CancelTwapOrder -> subaccount
    is DecibelCommand.CancelPositionTpSl -> subaccount
    is DecibelCommand.SetPositionTpSl -> subaccount
    is DecibelCommand.ApproveMaxBuilderFee -> subaccount
    is DecibelCommand.RevokeMaxBuilderFee -> subaccount
    is DecibelCommand.ApproveMaxSpotBuilderFee -> subaccount
    is DecibelCommand.RevokeMaxSpotBuilderFee -> subaccount
    is DecibelCommand.ContributeToVault -> subaccount
    is DecibelCommand.RedeemFromVault -> subaccount
  }

internal fun TransactionState.toActionState(): ActionState =
  when (this) {
    TransactionState.Simulating -> ActionState.Simulating
    TransactionState.AwaitingAuthorization -> ActionState.AwaitingAuthorization
    TransactionState.Submitting -> ActionState.Submitting
    is TransactionState.Pending -> ActionState.Pending(hash)
    is TransactionState.Committed -> ActionState.Committed(hash)
    is TransactionState.Failed ->
      ActionState.Failed(
        message = message,
        reference = hash,
        committed = committed,
        definitelyNotSubmitted = definitelyNotSubmitted,
        selfPay = selfPayEstimateOctas?.let { SelfPayOffer(it, selfPayReserveOctas) },
      )
  }

private fun DecibelCommand.isFundingTransfer(): Boolean =
  this is DecibelCommand.Deposit ||
    this is DecibelCommand.Withdraw ||
    this is DecibelCommand.TransferCollateral
