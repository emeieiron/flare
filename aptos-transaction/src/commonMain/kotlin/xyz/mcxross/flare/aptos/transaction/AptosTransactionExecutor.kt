package xyz.mcxross.flare.aptos.transaction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.TransactionSigner
import xyz.mcxross.kaptos.model.AptosError
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.ExternalFeePayerRequest
import xyz.mcxross.kaptos.model.TransactionPayload
import xyz.mcxross.kaptos.model.TransactionOptions
import xyz.mcxross.kaptos.model.UnsignedTransaction
import xyz.mcxross.kaptos.model.UserTransactionResponse
import xyz.mcxross.kaptos.model.WaitForTransactionOptions

sealed interface AptosTransactionState {
  data object Simulating : AptosTransactionState

  data object AwaitingAuthorization : AptosTransactionState

  data object Submitting : AptosTransactionState

  data class Pending(val hash: String) : AptosTransactionState

  data class Committed(val hash: String) : AptosTransactionState

  data class Failed(
    val message: String,
    val hash: String? = null,
    val committed: Boolean = false,
    val selfPayEstimateOctas: ULong? = null,
    val definitelyNotSubmitted: Boolean = false,
    /**
     * What the sender must hold to pay the fee itself: the network reserves the whole gas limit at the
     * gas price before running a transaction, so this is more than [selfPayEstimateOctas].
     */
    val selfPayReserveOctas: ULong? = null,
  ) : AptosTransactionState
}

fun interface ExternalFeePayerSubmitter {
  suspend fun submit(request: ExternalFeePayerRequest): AptosResult<String>
}

/** Builds, simulates and submits one Aptos transaction; callers own authorization and durable storage. */
class AptosTransactionExecutor(private val aptos: Aptos) {
  fun execute(
    signer: TransactionSigner,
    payload: suspend () -> AptosResult<TransactionPayload.EntryFunction>,
    feePayer: TransactionSigner? = null,
    externalFeePayer: ExternalFeePayerSubmitter? = null,
    onPrepared: suspend (hash: String) -> Unit = {},
    beforeSign: suspend () -> Unit = {},
    validateSimulation: suspend (UserTransactionResponse) -> Unit = {},
    options: TransactionOptions? = null,
  ): Flow<AptosTransactionState> = flow {
    if (feePayer != null && externalFeePayer != null) {
      emit(AptosTransactionState.Failed("Choose either a local fee payer or an external Gas Station"))
      return@flow
    }
    emit(AptosTransactionState.Simulating)
    val payload =
      when (val result = payload()) {
        is AptosResult.Success -> result.value
        is AptosResult.Failure -> {
          emit(AptosTransactionState.Failed(result.error.toString()))
          return@flow
        }
      }

    val unsigned: UnsignedTransaction =
      when (
        val result =
          if (feePayer == null && externalFeePayer == null) {
            aptos.transactions.build(signer.accountAddress, payload, options)
          } else {
            aptos.transactions.buildFeePayer(
              sender = signer.accountAddress,
              payload = payload,
              feePayer = feePayer?.accountAddress,
              options = options,
            )
          }
      ) {
        is AptosResult.Success -> result.value
        is AptosResult.Failure -> {
          emit(AptosTransactionState.Failed(result.error.toString()))
          return@flow
        }
      }

    val simulation =
      aptos.transactions.simulate(
        transaction = unsigned,
        senderPublicKey = signer.publicKey,
        feePayerPublicKey = feePayer?.publicKey,
      )
    var selfPayEstimateOctas: ULong? = null
    when (simulation) {
      is AptosResult.Failure -> {
        emit(AptosTransactionState.Failed(simulation.error.toString()))
        return@flow
      }
      is AptosResult.Success -> {
        if (simulation.value.size != 1) {
          emit(AptosTransactionState.Failed("Expected exactly one transaction simulation result"))
          return@flow
        }
        val failed = simulation.value.firstOrNull { !it.success }
        if (failed != null) {
          emit(AptosTransactionState.Failed(failed.vmStatus))
          return@flow
        }
        val estimate = simulation.value.firstOrNull()
        val gasUsed = estimate?.gasUsed?.toULongOrNull()
        val gasUnitPrice = estimate?.gasUnitPrice?.toULongOrNull()
        selfPayEstimateOctas =
          if (
            gasUsed != null &&
              gasUnitPrice != null &&
              (gasUsed == 0uL || gasUnitPrice <= ULong.MAX_VALUE / gasUsed)
          ) {
            gasUsed * gasUnitPrice
          } else {
            null
          }
      }
    }

    validateSimulation(simulation.value.single())
    emit(AptosTransactionState.AwaitingAuthorization)
    beforeSign()
    val senderAuthenticator =
      when (val result = aptos.transactions.sign(signer, unsigned)) {
        is AptosResult.Success -> result.value
        is AptosResult.Failure -> {
          emit(AptosTransactionState.Failed(result.error.toString()))
          return@flow
        }
      }
    val feePayerAuthenticator = feePayer?.let {
      when (val result = aptos.transactions.sign(it, unsigned)) {
        is AptosResult.Success -> result.value
        is AptosResult.Failure -> {
          emit(AptosTransactionState.Failed(result.error.toString()))
          return@flow
        }
      }
    }
    val preparedReference: String
    val externalRequest =
      if (externalFeePayer == null) {
        null
      } else {
        val feePayerTransaction = unsigned as? UnsignedTransaction.FeePayer
        if (feePayerTransaction == null) {
          emit(AptosTransactionState.Failed("External sponsorship requires a fee-payer transaction"))
          return@flow
        }
        when (
          val result =
            aptos.transactions.externalFeePayerRequest(
              transaction = feePayerTransaction,
              senderAuthenticator = senderAuthenticator,
            )
        ) {
          is AptosResult.Success -> result.value
          is AptosResult.Failure -> {
            emit(AptosTransactionState.Failed(result.error.toString()))
            return@flow
          }
        }
      }
    preparedReference =
      if (externalRequest != null) {
        "sponsor:${externalRequest.fingerprint}"
      } else {
        when (
          val result =
            aptos.transactions.userTransactionHash(
              transaction = unsigned,
              senderAuthenticator = senderAuthenticator,
              feePayerAuthenticator = feePayerAuthenticator,
            )
        ) {
          is AptosResult.Success -> result.value
          is AptosResult.Failure -> {
            emit(AptosTransactionState.Failed(result.error.toString()))
            return@flow
          }
        }
      }
    try {
      onPrepared(preparedReference)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      emit(AptosTransactionState.Failed(error.message ?: "Unable to journal signed transaction"))
      return@flow
    }

    beforeSign()
    emit(AptosTransactionState.Submitting)
    val submittedHash =
      if (externalRequest != null) {
        when (val result = externalFeePayer!!.submit(externalRequest)) {
          is AptosResult.Success -> result.value
          is AptosResult.Failure -> {
            val safeForSelfPay = result.error.safeForSelfPay()
            val raw = unsigned.rawTransaction
            val reserve =
              if (raw.gasUnitPrice == 0uL || raw.maxGasAmount <= ULong.MAX_VALUE / raw.gasUnitPrice) {
                raw.maxGasAmount * raw.gasUnitPrice
              } else {
                null
              }
            emit(
              AptosTransactionState.Failed(
                message = result.error.toString(),
                hash = preparedReference,
                selfPayEstimateOctas = selfPayEstimateOctas.takeIf { safeForSelfPay },
                definitelyNotSubmitted = safeForSelfPay,
                selfPayReserveOctas = reserve.takeIf { safeForSelfPay },
              )
            )
            return@flow
          }
        }
      } else {
        val pending =
          when (
            val result =
              aptos.transactions.submit(
                transaction = unsigned,
                senderAuthenticator = senderAuthenticator,
                feePayerAuthenticator = feePayerAuthenticator,
              )
          ) {
            is AptosResult.Success -> result.value
            is AptosResult.Failure -> {
              emit(AptosTransactionState.Failed(result.error.toString(), hash = preparedReference))
              return@flow
            }
          }
        if (!pending.hash.equals(preparedReference, ignoreCase = true)) {
          emit(
            AptosTransactionState.Failed(
              "Aptos returned a transaction hash that does not match the signed bytes",
              hash = preparedReference,
            )
          )
          return@flow
        }
        pending.hash
      }
    if (!submittedHash.matches(Regex("^0x[0-9a-fA-F]{64}$"))) {
      emit(
        AptosTransactionState.Failed(
          "The transaction submitter returned an invalid hash",
          hash = preparedReference,
        )
      )
      return@flow
    }
    emit(AptosTransactionState.Pending(submittedHash))

    when (
      val committed =
        aptos.transactions.waitForTransaction(
          submittedHash,
          WaitForTransactionOptions(checkSuccess = false),
        )
    ) {
      is AptosResult.Failure ->
        emit(AptosTransactionState.Failed(committed.error.toString(), hash = submittedHash))
      is AptosResult.Success -> {
        val response = committed.value
        if (
          response !is UserTransactionResponse ||
            !response.hash.equals(submittedHash, ignoreCase = true)
        ) {
          emit(AptosTransactionState.Failed("Unexpected transaction confirmation", hash = submittedHash))
        } else if (!response.success) {
          emit(
            AptosTransactionState.Failed(
              message = response.vmStatus,
              hash = response.hash,
              committed = true,
            )
          )
        } else {
          emit(AptosTransactionState.Committed(response.hash))
        }
      }
    }
  }

}
private fun AptosError.safeForSelfPay(): Boolean =
  this is AptosError.Api &&
    errorCode in
      setOf(
        "gas_station_not_configured",
        "gas_station_credentials_rejected",
        "gas_station_transaction_not_allowed",
        "gas_station_http_400",
        "gas_station_http_401",
        "gas_station_http_403",
        "gas_station_http_404",
        "gas_station_http_422",
        "gas_station_http_429",
      )
