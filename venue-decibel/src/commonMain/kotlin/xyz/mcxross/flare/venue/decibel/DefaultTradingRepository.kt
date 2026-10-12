package xyz.mcxross.flare.venue.decibel

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.pow
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.data.FeePayment
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
import xyz.mcxross.flare.venue.model.OpenOrder
import xyz.mcxross.flare.venue.model.SelfPayOffer
import xyz.mcxross.flare.venue.model.TradeAction
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.Account
import xyz.mcxross.kaptos.account.AccountAsset
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.SimulationOptions
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

class DefaultTradingRepository(
  database: FlareDatabase,
  private val client: DecibelClient,
  private val aptos: Aptos,
  private val wallets: AptosSigningWallet,
  private val sessions: SessionRepository,
  private val gasSponsorship: GasSponsorshipRepository,
  private val runtime: FlareRuntimeConfig,
  private val preferences: AppPreferences,
) : DecibelTrading {
  private val journal: TransactionJournalDao = database.transactionJournalDao()
  private val submissionMutex = Mutex()

  override val pendingTransactions: Flow<List<PendingTransaction>> =
    journal
      .observePending()
      .combine(preferences.values) { entries, saved ->
        entries
          .filter { it.profileId == saved.activeProfileId }
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
          )
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
            val asExit = DecibelCommand.CancelPositionTpSl(command.subaccount, command.market, exit.orderId)
            val asOrder = DecibelCommand.CancelOrder(command.subaccount, command.market, exit.orderId)
            // Exits Decibel keeps for the position cancel as exits, ones on the book as orders. One
            // refused before it was ever sent, as in simulation, costs nothing, so the other is tried.
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
            orders.exitsFor(market, closesWithBuy = !position.isLong).filter { it.exitKind in kinds }
          )
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
    if (!submissionMutex.tryLock()) {
      emit(ActionState.Failed("Another transaction is already being authorized"))
      return@flow
    }

    var preparedHash: String? = null
    try {
      val selected = preferences.values.first()
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
        subaccount == null || selected.selectedSubaccount?.sameAptosAddress(subaccount) == true
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
          sessions.authenticateOwner(subaccount, prompt)
        }
      emitAll(
        sessions.bind(
          activeSession,
          flow {
            reconcilePending()
            check(pendingTransactions.first().none { it.operation == command.journalName() }) {
              "An earlier transaction is pending. Check Activity before trying again."
            }
            withSigner(signer, prompt.copy(requireFreshAuthorization = false)) { account ->
              check(
                activeSession.walletAddress?.equals(
                  account.accountAddress.toString(),
                  ignoreCase = true,
                ) == true
              ) {
                "The authenticated Worker session does not match the selected signing wallet"
              }
              client.trading
                .execute(
                  signer = account,
                  command = command,
                  externalFeePayer =
                    if (feePayment == FeePayment.SPONSORED) gasSponsorship.submitterFor(command)
                    else null,
                  beforeSign = {
                    wallets.requireAuthorization(generation)
                    val current = preferences.values.first()
                    check(
                      current.activeProfileId == selected.activeProfileId &&
                        current.selectedSubaccount == selected.selectedSubaccount
                    ) {
                      "The selected account changed. Review the action again."
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
                      )
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
                          ) == 1
                        ) {
                          "The pending transaction journal entry is unavailable"
                        }
                        preparedHash = state.hash
                      } else {
                        hash?.updateState(JournalState.PENDING)
                      }
                    }
                    is TransactionState.Committed -> hash?.let { journal.remove(it) }
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
                  val eligible =
                    (state as? TransactionState.Failed)?.selfPayEstimateOctas?.let { estimate ->
                      when (
                        val balance =
                          aptos.accounts.getBalance(
                            account.accountAddress,
                            AccountAsset.coin(APTOS_COIN),
                          )
                      ) {
                        is AptosResult.Success -> balance.value >= estimate
                        is AptosResult.Failure -> false
                      }
                    }
                  emit(
                    (if (state is TransactionState.Failed && eligible == false)
                        state.copy(selfPayEstimateOctas = null)
                      else state)
                      .toActionState()
                  )
                }
            }
          },
        )
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
        )
      )
    } finally {
      if (command.requiredSigner() == TradingSigner.OWNER) {
        // An owner action interrupts trading; hand the session back without another prompt.
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
        if (response == null || !response.hash.equals(hash, true))
          ActionState.Pending(reference)
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

  override suspend fun baseAssetBalance(accountAddress: String, symbol: String): Double {
    val token = symbol.substringBefore('/').trim().uppercase()
    val address = AccountAddress.fromString(accountAddress)
    val asset: AccountAsset
    val decimals: Int
    if (token == "USDC") {
      asset = AccountAsset.fungibleAsset(client.config.deployment.usdcMetadataAddress)
      decimals = 6
    } else {
      val context = client.markets.spotAssetContexts().firstOrNull {
        it.name.substringBefore('/').trim().equals(token, ignoreCase = true)
      } ?: error("Balance information for $token is unavailable")
      asset = AccountAsset.fungibleAsset(context.baseAssetAddress)
      decimals = context.baseDecimals
    }
    require(decimals in 0..18) { "Unsupported asset precision" }
    return when (val result = aptos.accounts.getBalance(address, asset)) {
      is AptosResult.Success -> result.value.toDouble() / 10.0.pow(decimals)
      is AptosResult.Failure -> error("Couldn’t update your $token balance")
    }
  }

  override fun topUpTradingKey(amount: ULong, prompt: VaultPrompt): Flow<ActionState> =
    flow {
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
            session.walletAddress?.equals(owner.accountAddress.toString(), ignoreCase = true) ==
              true
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
            )
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
              ActionState.Failed("Aptos returned a mismatched top-up transaction hash", hash)
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
                    )
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
          )
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
      current.copy(state = state, updatedAtMs = Clock.System.now().toEpochMilliseconds())
    )
  }

  private companion object {
    const val SPONSOR_REFERENCE_PREFIX = "sponsor:"
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
