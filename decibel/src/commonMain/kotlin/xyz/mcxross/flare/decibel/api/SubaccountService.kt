package xyz.mcxross.flare.decibel.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosError
import xyz.mcxross.kaptos.model.AptosResult

enum class SubaccountStatus {
  ACTIVE,
  INACTIVE,
  NOT_SUBACCOUNT,
}

interface SubaccountService {
  /** What is at [address] as far as Decibel is concerned. */
  suspend fun status(address: String): AptosResult<SubaccountStatus>
}

internal class DefaultSubaccountService(
  private val aptos: Aptos,
  private val deployment: DecibelDeployment,
) : SubaccountService {
  override suspend fun status(address: String): AptosResult<SubaccountStatus> {
    val normalized =
      try {
        AccountAddress.fromString(address.trim()).toString()
      } catch (error: Exception) {
        return AptosResult.Failure(AptosError.Validation(error.message ?: "Invalid address"))
      }
    return when (
      val result =
        aptos.views.callRaw(
          function = "${deployment.packageAddress}::dex_accounts::view_is_subaccount_active",
          arguments = listOf(JsonPrimitive(normalized)),
        )
    ) {
      is AptosResult.Success ->
        when (result.value.values.firstOrNull()?.jsonPrimitive?.booleanOrNull) {
          true -> AptosResult.Success(SubaccountStatus.ACTIVE)
          false -> AptosResult.Success(SubaccountStatus.INACTIVE)
          null -> AptosResult.Failure(AptosError.Serialization("Unexpected view result"))
        }
      // The view aborts when the address holds no Subaccount object.
      is AptosResult.Failure ->
        if (result.error.isAbort()) AptosResult.Success(SubaccountStatus.NOT_SUBACCOUNT)
        else result
    }
  }
}

private fun AptosError.isAbort(): Boolean =
  this is AptosError.Api && (vmErrorCode == 4016L || message.contains("ABORTED"))
