package xyz.mcxross.flare.decibel.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.flare.decibel.model.MarginMode
import xyz.mcxross.flare.decibel.model.ValidatedOrder
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.TransactionSigner
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosError
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.ExternalFeePayerRequest
import xyz.mcxross.kaptos.model.TransactionPayload
import xyz.mcxross.kaptos.model.UnsignedTransaction
import xyz.mcxross.kaptos.model.UserTransactionResponse
import xyz.mcxross.kaptos.model.WaitForTransactionOptions
import xyz.mcxross.kaptos.move.MoveArgument

sealed interface DecibelCommand {
  data object CreateSubaccount : DecibelCommand

  data class Deposit(val subaccount: String, val assetMetadata: String, val amount: ULong) :
    DecibelCommand

  data class Withdraw(val subaccount: String, val assetMetadata: String, val amount: ULong) :
    DecibelCommand

  data class TransferCollateral(
    val subaccount: String,
    val assetMetadata: String,
    val destination: String,
    val amount: ULong,
  ) : DecibelCommand

  data class DelegateTrading(
    val subaccount: String,
    val delegate: String,
    val expiresAtSeconds: ULong? = null,
  ) : DecibelCommand

  data class RevokeDelegation(val subaccount: String, val delegate: String) : DecibelCommand

  data class ConfigureMarket(
    val subaccount: String,
    val market: String,
    val marginMode: MarginMode,
    val leverage: UByte,
  ) : DecibelCommand

  data class PlaceOrder(val subaccount: String, val order: ValidatedOrder) : DecibelCommand

  data class PlaceSpotOrder(val subaccount: String, val order: ValidatedOrder) : DecibelCommand

  data class CancelOrder(val subaccount: String, val market: String, val orderId: String) :
    DecibelCommand

  data class CancelSpotOrder(val subaccount: String, val market: String, val orderId: String) :
    DecibelCommand

  data class CancelPositionTpSl(val subaccount: String, val market: String, val orderId: String) :
    DecibelCommand

  data class SetPositionTpSl(
    val subaccount: String,
    val market: String,
    val takeProfitTrigger: ULong? = null,
    val takeProfitLimit: ULong? = null,
    val takeProfitSize: ULong? = null,
    val stopLossTrigger: ULong? = null,
    val stopLossLimit: ULong? = null,
    val stopLossSize: ULong? = null,
  ) : DecibelCommand
}

sealed interface TransactionState {
  data object Simulating : TransactionState

  data object AwaitingAuthorization : TransactionState

  data object Submitting : TransactionState

  data class Pending(val hash: String) : TransactionState

  data class Committed(val hash: String) : TransactionState

  data class Failed(
    val message: String,
    val hash: String? = null,
    val committed: Boolean = false,
    val selfPayEstimateOctas: ULong? = null,
    val definitelyNotSubmitted: Boolean = false,
  ) : TransactionState
}

fun interface ExternalFeePayerSubmitter {
  suspend fun submit(request: ExternalFeePayerRequest): AptosResult<String>
}

interface DecibelTradingService {
  suspend fun payload(command: DecibelCommand): AptosResult<TransactionPayload.EntryFunction>

  fun execute(
    signer: TransactionSigner,
    command: DecibelCommand,
    feePayer: TransactionSigner? = null,
    externalFeePayer: ExternalFeePayerSubmitter? = null,
    onPrepared: suspend (hash: String) -> Unit = {},
    beforeSign: suspend () -> Unit = {},
  ): Flow<TransactionState>
}

internal class DefaultDecibelTradingService(
  private val aptos: Aptos,
  private val deployment: DecibelDeployment,
) : DecibelTradingService {
  override suspend fun payload(
    command: DecibelCommand
  ): AptosResult<TransactionPayload.EntryFunction> {
    if (command is DecibelCommand.TransferCollateral) {
      require(command.amount > 0uL) { "Transfer amount must be positive" }
      return AptosResult.Success(
        TransactionPayload.entryFunction(
          function = "0x1::primary_fungible_store::transfer",
          typeArguments =
            listOf(xyz.mcxross.kaptos.model.TypeTag.fromString("0x1::fungible_asset::Metadata")),
          arguments =
            listOf(
              address(command.assetMetadata),
              address(command.destination),
              MoveArgument.U64(command.amount),
            ),
        )
      )
    }
    val call =
      try {
        buildCall(command)
      } catch (error: Exception) {
        return AptosResult.Failure(
          AptosError.Validation(error.message ?: "Invalid Decibel command")
        )
      }
    return aptos.transactions.entryFunctionPayload(
      function = call.function,
      arguments = call.arguments,
    )
  }

  private fun buildCall(command: DecibelCommand): EntryFunctionCall {
    val packageAddress = deployment.packageAddress
    val function: String
    val arguments: List<MoveArgument>
    when (command) {
      is DecibelCommand.TransferCollateral -> error("Transfer payload is built directly")
      DecibelCommand.CreateSubaccount -> {
        function = "$packageAddress::dex_accounts_entry::create_new_subaccount"
        arguments = emptyList()
      }
      is DecibelCommand.Deposit -> {
        require(command.amount > 0uL) { "Deposit amount must be positive" }
        function = "$packageAddress::dex_accounts_entry::deposit_to_subaccount_at"
        arguments =
          listOf(
            address(command.subaccount),
            address(command.assetMetadata),
            MoveArgument.U64(command.amount),
          )
      }
      is DecibelCommand.Withdraw -> {
        require(command.amount > 0uL) { "Withdrawal amount must be positive" }
        function = "$packageAddress::dex_accounts_entry::withdraw_from_cross_collateral"
        arguments =
          listOf(
            address(command.subaccount),
            address(command.assetMetadata),
            MoveArgument.U64(command.amount),
          )
      }
      is DecibelCommand.DelegateTrading -> {
        function = "$packageAddress::dex_accounts_entry::delegate_all_trading_to_for_subaccount"
        arguments =
          listOf(
            address(command.subaccount),
            address(command.delegate),
            option(command.expiresAtSeconds?.let(MoveArgument::U64)),
          )
      }
      is DecibelCommand.RevokeDelegation -> {
        function = "$packageAddress::dex_accounts_entry::revoke_delegation"
        arguments = listOf(address(command.subaccount), address(command.delegate))
      }
      is DecibelCommand.ConfigureMarket -> {
        require(command.leverage.toInt() in 1..100) { "Leverage must be between 1 and 100" }
        function = "$packageAddress::dex_accounts_entry::configure_user_settings_for_market"
        arguments =
          listOf(
            address(command.subaccount),
            address(command.market),
            MoveArgument.Bool(command.marginMode == MarginMode.CROSS),
            MoveArgument.U8(command.leverage),
          )
      }
      is DecibelCommand.PlaceOrder -> {
        function = "$packageAddress::dex_accounts_entry::place_order_to_subaccount"
        val order = command.order
        require(order.price > 0uL) { "Order price must be positive" }
        require(order.size > 0uL) { "Order size must be positive" }
        arguments =
          listOf(
            address(command.subaccount),
            address(order.marketAddress),
            MoveArgument.U64(order.price),
            MoveArgument.U64(order.size),
            MoveArgument.Bool(order.side == xyz.mcxross.flare.decibel.model.OrderSide.BUY),
            MoveArgument.U8(order.timeInForce.chainValue),
            MoveArgument.Bool(order.reduceOnly),
            option(order.clientOrderId?.let(MoveArgument::StringValue)),
            option(order.stopPrice?.let(MoveArgument::U64)),
            option(order.takeProfitTriggerPrice?.let(MoveArgument::U64)),
            option(order.takeProfitLimitPrice?.let(MoveArgument::U64)),
            option(order.stopLossTriggerPrice?.let(MoveArgument::U64)),
            option(order.stopLossLimitPrice?.let(MoveArgument::U64)),
            option(null),
            option(null),
          )
      }
      is DecibelCommand.PlaceSpotOrder -> {
        function = "$packageAddress::dex_accounts_spot_entry::place_spot_order_to_subaccount"
        val order = command.order
        require(order.price > 0uL) { "Order price must be positive" }
        require(order.size > 0uL) { "Order size must be positive" }
        arguments =
          listOf(
            address(command.subaccount),
            address(order.marketAddress),
            MoveArgument.U64(order.price),
            MoveArgument.U64(order.size),
            MoveArgument.Bool(order.side == xyz.mcxross.flare.decibel.model.OrderSide.BUY),
            MoveArgument.U8(order.timeInForce.chainValue),
            option(null),
            option(null),
          )
      }
      is DecibelCommand.CancelOrder -> {
        function = "$packageAddress::dex_accounts_entry::cancel_order_to_subaccount"
        arguments =
          listOf(
            address(command.subaccount),
            MoveArgument.U128(command.orderId),
            address(command.market),
          )
      }
      is DecibelCommand.CancelSpotOrder -> {
        function = "$packageAddress::dex_accounts_spot_entry::cancel_spot_order_to_subaccount"
        arguments =
          listOf(
            address(command.subaccount),
            address(command.market),
            MoveArgument.U128(command.orderId),
          )
      }
      is DecibelCommand.CancelPositionTpSl -> {
        function = "$packageAddress::dex_accounts_entry::cancel_tp_sl_order_for_position"
        arguments =
          listOf(
            address(command.subaccount),
            address(command.market),
            MoveArgument.U128(command.orderId),
          )
      }
      is DecibelCommand.SetPositionTpSl -> {
        require(command.takeProfitTrigger != null || command.stopLossTrigger != null) {
          "A take-profit or stop-loss trigger is required"
        }
        function = "$packageAddress::dex_accounts_entry::place_tp_sl_order_for_position"
        arguments =
          listOf(
            address(command.subaccount),
            address(command.market),
            option(command.takeProfitTrigger?.let(MoveArgument::U64)),
            option(command.takeProfitLimit?.let(MoveArgument::U64)),
            option(command.takeProfitSize?.let(MoveArgument::U64)),
            option(command.stopLossTrigger?.let(MoveArgument::U64)),
            option(command.stopLossLimit?.let(MoveArgument::U64)),
            option(command.stopLossSize?.let(MoveArgument::U64)),
            option(null),
            option(null),
          )
      }
    }
    return EntryFunctionCall(function, arguments)
  }

  override fun execute(
    signer: TransactionSigner,
    command: DecibelCommand,
    feePayer: TransactionSigner?,
    externalFeePayer: ExternalFeePayerSubmitter?,
    onPrepared: suspend (hash: String) -> Unit,
    beforeSign: suspend () -> Unit,
  ): Flow<TransactionState> = flow {
    if (feePayer != null && externalFeePayer != null) {
      emit(TransactionState.Failed("Choose either a local fee payer or an external Gas Station"))
      return@flow
    }
    emit(TransactionState.Simulating)
    val payload =
      when (val result = payload(command)) {
        is AptosResult.Success -> result.value
        is AptosResult.Failure -> {
          emit(TransactionState.Failed(result.error.toString()))
          return@flow
        }
      }

    val unsigned: UnsignedTransaction =
      when (
        val result =
          if (feePayer == null && externalFeePayer == null) {
            aptos.transactions.build(signer.accountAddress, payload)
          } else {
            aptos.transactions.buildFeePayer(
              sender = signer.accountAddress,
              payload = payload,
              feePayer = feePayer?.accountAddress,
            )
          }
      ) {
        is AptosResult.Success -> result.value
        is AptosResult.Failure -> {
          emit(TransactionState.Failed(result.error.toString()))
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
        emit(TransactionState.Failed(simulation.error.toString()))
        return@flow
      }
      is AptosResult.Success -> {
        if (simulation.value.size != 1) {
          emit(TransactionState.Failed("Expected exactly one transaction simulation result"))
          return@flow
        }
        val failed = simulation.value.firstOrNull { !it.success }
        if (failed != null) {
          println("SIMULATION_FAILED_VM_STATUS: ${failed.vmStatus}")
          emit(TransactionState.Failed(failed.vmStatus))
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

    emit(TransactionState.AwaitingAuthorization)
    beforeSign()
    val senderAuthenticator =
      when (val result = aptos.transactions.sign(signer, unsigned)) {
        is AptosResult.Success -> result.value
        is AptosResult.Failure -> {
          emit(TransactionState.Failed(result.error.toString()))
          return@flow
        }
      }
    val feePayerAuthenticator =
      feePayer?.let {
        when (val result = aptos.transactions.sign(it, unsigned)) {
          is AptosResult.Success -> result.value
          is AptosResult.Failure -> {
            emit(TransactionState.Failed(result.error.toString()))
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
          emit(TransactionState.Failed("External sponsorship requires a fee-payer transaction"))
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
            emit(TransactionState.Failed(result.error.toString()))
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
            emit(TransactionState.Failed(result.error.toString()))
            return@flow
          }
        }
      }
    try {
      onPrepared(preparedReference)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      emit(TransactionState.Failed(error.message ?: "Unable to journal signed transaction"))
      return@flow
    }

    beforeSign()
    emit(TransactionState.Submitting)
    val submittedHash =
      if (externalRequest != null) {
        when (val result = externalFeePayer!!.submit(externalRequest)) {
          is AptosResult.Success -> result.value
          is AptosResult.Failure -> {
            val safeForSelfPay = result.error.safeForSelfPay()
            emit(
              TransactionState.Failed(
                message = result.error.toString(),
                hash = preparedReference,
                selfPayEstimateOctas = selfPayEstimateOctas.takeIf { safeForSelfPay },
                definitelyNotSubmitted = safeForSelfPay,
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
              emit(TransactionState.Failed(result.error.toString(), hash = preparedReference))
              return@flow
            }
          }
        if (!pending.hash.equals(preparedReference, ignoreCase = true)) {
          emit(
            TransactionState.Failed(
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
        TransactionState.Failed(
          "The transaction submitter returned an invalid hash",
          hash = preparedReference,
        )
      )
      return@flow
    }
    emit(TransactionState.Pending(submittedHash))

    when (
      val committed =
        aptos.transactions.waitForTransaction(
          submittedHash,
          WaitForTransactionOptions(checkSuccess = false),
        )
    ) {
      is AptosResult.Failure ->
        emit(TransactionState.Failed(committed.error.toString(), hash = submittedHash))
      is AptosResult.Success -> {
        val response = committed.value
        if (
          response !is UserTransactionResponse ||
            !response.hash.equals(submittedHash, ignoreCase = true)
        ) {
          emit(TransactionState.Failed("Unexpected transaction confirmation", hash = submittedHash))
        } else if (!response.success) {
          emit(
            TransactionState.Failed(
              message = response.vmStatus,
              hash = response.hash,
              committed = true,
            )
          )
        } else {
          emit(TransactionState.Committed(response.hash))
        }
      }
    }
  }

  private fun address(value: String): MoveArgument.Address =
    MoveArgument.Address(AccountAddress.fromString(value))

  private fun option(value: MoveArgument?): MoveArgument.Option = MoveArgument.Option(value)

  private data class EntryFunctionCall(val function: String, val arguments: List<MoveArgument>)
}

private fun AptosError.safeForSelfPay(): Boolean =
  this is AptosError.Api &&
    errorCode in
      setOf(
        "gas_station_not_configured",
        "gas_station_credentials_rejected",
        "gas_station_http_400",
        "gas_station_http_401",
        "gas_station_http_403",
        "gas_station_http_404",
        "gas_station_http_422",
        "gas_station_http_429",
      )
