package xyz.mcxross.flare.data

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.ExternalFeePayerSubmitter
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.PendingTransactionEntity
import xyz.mcxross.flare.store.TransactionJournalDao
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.AccountAsset
import xyz.mcxross.kaptos.account.Ed25519Account
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.SimulationOptions
import xyz.mcxross.kaptos.model.UserTransactionResponse
import xyz.mcxross.kaptos.model.WaitForTransactionOptions
import xyz.mcxross.kaptos.util.APTOS_COIN

enum class TradingSigner {
  OWNER,
  API,
}

enum class FeePayment {
  SPONSORED,
  SELF_PAY,
}

data class PendingTransaction(
  val hash: String,
  val network: String,
  val operation: String,
  val state: String,
  val createdAtMs: Long,
  val updatedAtMs: Long,
)

data class ReconciliationResult(
  val finalized: Int,
  val unresolved: Int,
)

interface TradingRepository {
  val pendingTransactions: Flow<List<PendingTransaction>>

  fun execute(
    command: DecibelCommand,
    signer: TradingSigner,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<TransactionState>

  suspend fun apiWalletAptBalance(): ULong

  fun topUpApiWallet(
    amountOctas: ULong,
    prompt: VaultPrompt,
  ): Flow<TransactionState>

  suspend fun reconcilePending(): ReconciliationResult
}

class DefaultTradingRepository(
  database: xyz.mcxross.flare.store.FlareDatabase,
  private val client: DecibelClient,
  private val aptos: Aptos,
  private val wallets: WalletRepository,
  private val sessions: SessionRepository,
  private val gasSponsorship: GasSponsorshipRepository,
  private val runtime: FlareRuntimeConfig,
) : TradingRepository {
  private val journal: TransactionJournalDao = database.transactionJournalDao()
  private val submissionMutex = Mutex()

  override val pendingTransactions: Flow<List<PendingTransaction>> =
    journal.observePending().map { entries -> entries.map(PendingTransactionEntity::toDomain) }

  override fun execute(
    command: DecibelCommand,
    signer: TradingSigner,
    prompt: VaultPrompt,
    feePayment: FeePayment,
  ): Flow<TransactionState> = flow {
    val activeSession = sessions.status.value
    val now = Clock.System.now().toEpochMilliseconds()
    if (
      activeSession == null ||
        activeSession.role == SessionRole.ANONYMOUS ||
        activeSession.expiresAt - now < MINIMUM_SESSION_REMAINING_MS
    ) {
      emit(TransactionState.Failed("Unlock an authenticated wallet session before submitting"))
      return@flow
    }
    if (signer == TradingSigner.API && command.requiresOwner()) {
      emit(TransactionState.Failed("This operation requires the owner wallet"))
      return@flow
    }
    if (!submissionMutex.tryLock()) {
      emit(TransactionState.Failed("Another transaction is already being authorized"))
      return@flow
    }

    var preparedHash: String? = null
    try {
      withSigner(signer, prompt) { account ->
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
              if (feePayment == FeePayment.SPONSORED) {
                ExternalFeePayerSubmitter { request ->
                  gasSponsorship.submit(
                    request = request,
                    ownerOnly = command.requiresOwner(),
                  )
                }
              } else {
                null
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
                  operation = command.journalName(),
                  state = JournalState.PREPARED,
                  createdAtMs = now,
                  updatedAtMs = now,
                )
              )
              preparedHash = hash
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
            emit(state)
          }
      }
    } catch (cancelled: CancellationException) {
      preparedHash?.updateState(JournalState.RECONCILE_REQUIRED)
      throw cancelled
    } catch (error: Throwable) {
      preparedHash?.updateState(JournalState.RECONCILE_REQUIRED)
      emit(
        TransactionState.Failed(
          error.message ?: "Transaction authorization failed",
          hash = preparedHash,
        )
      )
    } finally {
      submissionMutex.unlock()
    }
  }

  override suspend fun apiWalletAptBalance(): ULong {
    val address =
      wallets.profile.first().apiWalletAddress ?: error("An API wallet is not configured")
    return when (
      val result =
        aptos.accounts.getBalance(
          AccountAddress.fromString(address),
          AccountAsset.coin(APTOS_COIN),
        )
    ) {
      is AptosResult.Success -> result.value
      is AptosResult.Failure -> error(result.error.toString())
    }
  }

  override fun topUpApiWallet(
    amountOctas: ULong,
    prompt: VaultPrompt,
  ): Flow<TransactionState> = flow {
    require(amountOctas > 0uL) { "APT top-up amount must be greater than zero" }
    val session = sessions.status.value
    if (session?.role != SessionRole.OWNER) {
      emit(
        TransactionState.Failed("Fresh owner authorization is required for an API-wallet top-up")
      )
      return@flow
    }
    if (!submissionMutex.tryLock()) {
      emit(TransactionState.Failed("Another transaction is already being authorized"))
      return@flow
    }

    var preparedHash: String? = null
    try {
      val recipient =
        wallets.profile.first().apiWalletAddress ?: error("An API wallet is not configured")
      wallets.withOwnerAccount(prompt.copy(requireFreshAuthorization = true)) { owner ->
        check(
          session.walletAddress?.equals(owner.accountAddress.toString(), ignoreCase = true) == true
        ) {
          "The owner session does not match the owner signing wallet"
        }
        emit(TransactionState.Simulating)
        val transaction =
          when (
            val result =
              aptos.coins.buildTransfer(
                sender = owner.accountAddress,
                recipient = AccountAddress.fromString(recipient),
                amount = amountOctas,
              )
          ) {
            is AptosResult.Success -> result.value
            is AptosResult.Failure -> {
              emit(TransactionState.Failed(result.error.toString()))
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
            emit(TransactionState.Failed(result.error.toString()))
            return@withOwnerAccount
          }
          is AptosResult.Success -> {
            if (result.value.size != 1) {
              emit(TransactionState.Failed("Expected exactly one transaction simulation result"))
              return@withOwnerAccount
            }
            val failed = result.value.firstOrNull { !it.success }
            if (failed != null) {
              emit(TransactionState.Failed(failed.vmStatus))
              return@withOwnerAccount
            }
          }
        }

        emit(TransactionState.AwaitingAuthorization)
        val authenticator =
          when (val result = aptos.transactions.sign(owner, transaction)) {
            is AptosResult.Success -> result.value
            is AptosResult.Failure -> {
              emit(TransactionState.Failed(result.error.toString()))
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
              emit(TransactionState.Failed(result.error.toString()))
              return@withOwnerAccount
            }
          }
        val now = Clock.System.now().toEpochMilliseconds()
        check(journal.find(hash) == null) { "This APT top-up is already pending reconciliation" }
        journal.upsert(
          PendingTransactionEntity(
            hash = hash,
            network = runtime.network.name,
            operation = "TOP_UP_API_WALLET",
            state = JournalState.PREPARED,
            createdAtMs = now,
            updatedAtMs = now,
          )
        )
        preparedHash = hash

        emit(TransactionState.Submitting)
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
              emit(TransactionState.Failed(result.error.toString(), hash = hash))
              return@withOwnerAccount
            }
          }
        if (!pending.hash.equals(hash, ignoreCase = true)) {
          hash.updateState(JournalState.RECONCILE_REQUIRED)
          emit(TransactionState.Failed("Aptos returned a mismatched top-up transaction hash", hash))
          return@withOwnerAccount
        }
        hash.updateState(JournalState.PENDING)
        emit(TransactionState.Pending(hash))
        when (
          val result =
            aptos.transactions.waitForTransaction(
              hash,
              WaitForTransactionOptions(checkSuccess = false),
            )
        ) {
          is AptosResult.Failure -> {
            hash.updateState(JournalState.RECONCILE_REQUIRED)
            emit(TransactionState.Failed(result.error.toString(), hash = hash))
          }
          is AptosResult.Success -> {
            val response = result.value as? UserTransactionResponse
            if (response == null || !response.hash.equals(hash, ignoreCase = true)) {
              hash.updateState(JournalState.RECONCILE_REQUIRED)
              emit(TransactionState.Failed("Unexpected top-up transaction response", hash = hash))
            } else {
              journal.remove(hash)
              if (response.success) {
                emit(TransactionState.Committed(hash))
              } else {
                emit(
                  TransactionState.Failed(
                    message = response.vmStatus,
                    hash = hash,
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
        TransactionState.Failed(error.message ?: "API-wallet top-up failed", hash = preparedHash)
      )
    } finally {
      submissionMutex.unlock()
    }
  }

  override suspend fun reconcilePending(): ReconciliationResult {
    var finalized = 0
    var unresolved = 0
    journal.pending().forEach { entry ->
      if (entry.network != runtime.network.name) return@forEach
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
            WaitForTransactionOptions(timeoutSecs = 2, checkSuccess = false),
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
    block: suspend (Ed25519Account) -> T,
  ): T =
    when (signer) {
      TradingSigner.OWNER -> wallets.withOwnerAccount(prompt, block)
      TradingSigner.API -> wallets.withApiAccount(prompt, block)
    }

  private suspend fun String.updateState(state: String) {
    val current = journal.find(this) ?: return
    journal.upsert(
      current.copy(
        state = state,
        updatedAtMs = Clock.System.now().toEpochMilliseconds(),
      )
    )
  }

  private companion object {
    const val MINIMUM_SESSION_REMAINING_MS = 60_000L
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

private fun DecibelCommand.requiresOwner(): Boolean =
  when (this) {
    DecibelCommand.CreateSubaccount,
    is DecibelCommand.Deposit,
    is DecibelCommand.Withdraw,
    is DecibelCommand.DelegateTrading,
    is DecibelCommand.RevokeDelegation -> true
    is DecibelCommand.ConfigureMarket,
    is DecibelCommand.PlaceOrder,
    is DecibelCommand.CancelOrder,
    is DecibelCommand.CancelPositionTpSl,
    is DecibelCommand.SetPositionTpSl -> false
  }

private fun DecibelCommand.journalName(): String =
  when (this) {
    DecibelCommand.CreateSubaccount -> "CREATE_SUBACCOUNT"
    is DecibelCommand.Deposit -> "DEPOSIT"
    is DecibelCommand.Withdraw -> "WITHDRAW"
    is DecibelCommand.DelegateTrading -> "DELEGATE_TRADING"
    is DecibelCommand.RevokeDelegation -> "REVOKE_DELEGATION"
    is DecibelCommand.ConfigureMarket -> "CONFIGURE_MARKET"
    is DecibelCommand.PlaceOrder -> "PLACE_ORDER"
    is DecibelCommand.CancelOrder -> "CANCEL_ORDER"
    is DecibelCommand.CancelPositionTpSl -> "CANCEL_POSITION_TP_SL"
    is DecibelCommand.SetPositionTpSl -> "SET_POSITION_TP_SL"
  }

private fun PendingTransactionEntity.toDomain(): PendingTransaction =
  PendingTransaction(
    hash = hash,
    network = network,
    operation = operation,
    state = state,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
  )
