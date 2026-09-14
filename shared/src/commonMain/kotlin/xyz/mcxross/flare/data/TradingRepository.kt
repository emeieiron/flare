package xyz.mcxross.flare.data

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.core.runSuspendCatching
import xyz.mcxross.flare.decibel.DecibelClient
import xyz.mcxross.flare.decibel.api.DecibelCommand
import xyz.mcxross.flare.decibel.api.ExternalFeePayerSubmitter
import xyz.mcxross.flare.decibel.api.TransactionState
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.flare.store.FlareDatabase
import xyz.mcxross.flare.store.PendingTransactionEntity
import xyz.mcxross.flare.store.TransactionJournalDao
import xyz.mcxross.flare.store.journalOperation
import xyz.mcxross.flare.store.operationName
import xyz.mcxross.flare.store.profileId
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.AccountAsset
import xyz.mcxross.kaptos.account.Ed25519Account
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

data class ReconciliationResult(val finalized: Int, val unresolved: Int)

interface TradingRepository {
  val pendingTransactions: Flow<List<PendingTransaction>>

  /**
   * Signs with the key the command requires: the owner key for collateral and delegation, the
   * device trading key for everything else. Callers describe the action, never the key.
   */
  fun execute(
    command: DecibelCommand,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
    onPrepared: suspend (String) -> Unit = {},
  ): Flow<TransactionState>

  suspend fun transactionStatus(reference: String): TransactionState

  suspend fun apiWalletAptBalance(): ULong

  suspend fun baseAssetBalance(accountAddress: String, symbol: String): Double = 0.0

  fun topUpApiWallet(amountOctas: ULong, prompt: VaultPrompt): Flow<TransactionState>

  suspend fun reconcilePending(): ReconciliationResult
}

class DefaultTradingRepository(
  database: FlareDatabase,
  private val client: DecibelClient,
  private val aptos: Aptos,
  private val wallets: WalletRepository,
  private val sessions: SessionRepository,
  private val gasSponsorship: GasSponsorshipRepository,
  private val runtime: FlareRuntimeConfig,
  private val preferences: AppPreferences,
) : TradingRepository {
  private val journal: TransactionJournalDao = database.transactionJournalDao()
  private val submissionMutex = Mutex()

  override val pendingTransactions: Flow<List<PendingTransaction>> =
    journal.observePending().combine(preferences.values) { entries, saved ->
      entries
        .filter { it.profileId == saved.activeProfileId }
        .map(PendingTransactionEntity::toDomain)
    }

  override fun execute(
    command: DecibelCommand,
    prompt: VaultPrompt,
    feePayment: FeePayment,
    onPrepared: suspend (String) -> Unit,
  ): Flow<TransactionState> = flow {
    if (!submissionMutex.tryLock()) {
      emit(TransactionState.Failed("Another transaction is already being authorized"))
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
                    if (feePayment == FeePayment.SPONSORED) {
                      ExternalFeePayerSubmitter { request ->
                        gasSponsorship.submit(
                          request = request,
                          ownerOnly = signer == TradingSigner.OWNER,
                        )
                      }
                    } else {
                      null
                    },
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
                    if (state is TransactionState.Failed && eligible == false)
                      state.copy(selfPayEstimateOctas = null)
                    else state
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
        TransactionState.Failed(
          error.message ?: "Transaction authorization failed",
          hash = preparedHash,
        )
      )
    } finally {
      if (command.requiredSigner() == TradingSigner.OWNER) {
        // An owner action interrupts trading; hand the session back without another prompt.
        try {
          wallets.requireAuthorization(wallets.authorizationGeneration)
          val saved = preferences.values.first()
          if (saved.apiWalletAddress != null && saved.selectedSubaccount != null) {
            sessions.ensureTrading(
              saved.selectedSubaccount,
              prompt.copy(requireFreshAuthorization = false),
            )
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

  override suspend fun transactionStatus(reference: String): TransactionState {
    val hash =
      if (reference.startsWith(SPONSOR_REFERENCE_PREFIX)) {
        when (
          val result = gasSponsorship.resolve(reference.removePrefix(SPONSOR_REFERENCE_PREFIX))
        ) {
          is AptosResult.Success -> result.value ?: return TransactionState.Pending(reference)
          is AptosResult.Failure -> return TransactionState.Pending(reference)
        }
      } else reference
    return when (
      val result =
        aptos.transactions.waitForTransaction(
          hash,
          WaitForTransactionOptions(timeoutSecs = 2, checkSuccess = false),
        )
    ) {
      is AptosResult.Failure -> TransactionState.Pending(reference)
      is AptosResult.Success -> {
        val response = result.value as? UserTransactionResponse
        if (response == null || !response.hash.equals(hash, true))
          TransactionState.Pending(reference)
        else {
          journal.remove(reference)
          if (hash != reference) journal.remove(hash)
          if (response.success) TransactionState.Committed(hash)
          else TransactionState.Failed(response.vmStatus, hash, committed = true)
        }
      }
    }
  }

  override suspend fun apiWalletAptBalance(): ULong {
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

  override suspend fun baseAssetBalance(accountAddress: String, symbol: String): Double =
    runSuspendCatching {
      val address = AccountAddress.fromString(accountAddress)
      val token = symbol.split("/").firstOrNull()?.trim() ?: symbol
      if (token.equals("APT", ignoreCase = true)) {
        when (val result = aptos.accounts.getBalance(address, AccountAsset.coin(APTOS_COIN))) {
          is AptosResult.Success -> result.value.toDouble() / 100_000_000.0
          is AptosResult.Failure -> 0.0
        }
      } else {
        0.0
      }
    }.getOrDefault(0.0)

  override fun topUpApiWallet(amountOctas: ULong, prompt: VaultPrompt): Flow<TransactionState> =
    flow {
      require(amountOctas > 0uL) { "APT top-up amount must be greater than zero" }
      val session =
        sessions.authenticateOwner(
          preferences.values.first().selectedSubaccount,
          prompt.copy(requireFreshAuthorization = true),
        )
      if (session.role != SessionRole.OWNER) {
        emit(TransactionState.Failed("Confirm your identity to send APT from your wallet"))
        return@flow
      }
      if (!submissionMutex.tryLock()) {
        emit(TransactionState.Failed("Another transaction is already being authorized"))
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
            emit(
              TransactionState.Failed("Aptos returned a mismatched top-up transaction hash", hash)
            )
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
          TransactionState.Failed(
            error.message ?: "The network-fee top-up failed",
            hash = preparedHash,
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
      current.copy(state = state, updatedAtMs = Clock.System.now().toEpochMilliseconds())
    )
  }

  private companion object {
    const val SPONSOR_REFERENCE_PREFIX = "sponsor:"
  }
}

/**
 * Sponsorship can fall back to self-payment only when the signing key can pay the fee itself.
 * Returns the amount of APT the device trading key is missing, or null when no top-up applies.
 */
suspend fun TradingRepository.apiWalletTopUpFor(
  failure: TransactionState.Failed,
  profile: WalletProfile,
): ULong? {
  val estimate = failure.selfPayEstimateOctas ?: return null
  if (profile.apiWalletAddress == null || profile.ownerAddress == null) return null
  val balance = runSuspendCatching { apiWalletAptBalance() }.getOrNull() ?: return null
  if (balance >= estimate) return null
  val buffered = if (estimate <= ULong.MAX_VALUE / 3uL) estimate * 3uL else estimate
  return maxOf(MINIMUM_TOP_UP_OCTAS, buffered)
}

private const val MINIMUM_TOP_UP_OCTAS = 1_000_000uL

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
    is DecibelCommand.RevokeDelegation -> TradingSigner.OWNER
    is DecibelCommand.ConfigureMarket,
    is DecibelCommand.PlaceOrder,
    is DecibelCommand.PlaceSpotOrder,
    is DecibelCommand.CancelOrder,
    is DecibelCommand.CancelSpotOrder,
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
    is DecibelCommand.CancelOrder -> "CANCEL_ORDER"
    is DecibelCommand.CancelSpotOrder -> "CANCEL_SPOT_ORDER"
    is DecibelCommand.CancelPositionTpSl -> "CANCEL_POSITION_TP_SL"
    is DecibelCommand.SetPositionTpSl -> "SET_POSITION_TP_SL"
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
    is DecibelCommand.CancelOrder -> subaccount
    is DecibelCommand.CancelSpotOrder -> subaccount
    is DecibelCommand.CancelPositionTpSl -> subaccount
    is DecibelCommand.SetPositionTpSl -> subaccount
  }
