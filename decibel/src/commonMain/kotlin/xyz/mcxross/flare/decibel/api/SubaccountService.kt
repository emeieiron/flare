package xyz.mcxross.flare.decibel.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.flare.decibel.model.Delegation
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosError
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.model.Event
import xyz.mcxross.kaptos.model.UserTransactionResponse
import xyz.mcxross.kaptos.model.WaitForTransactionOptions

enum class SubaccountStatus {
  ACTIVE,
  INACTIVE,
  NOT_SUBACCOUNT,
}

interface SubaccountService {
  /** What is at [address] as far as Decibel is concerned. */
  suspend fun status(address: String): AptosResult<SubaccountStatus>

  /** Every permission delegated on [subaccount], one row per key and permission, read on-chain. */
  suspend fun delegations(subaccount: String): AptosResult<List<Delegation>> =
    AptosResult.Failure(AptosError.Validation("Delegations are not available"))

  /** The trading account [transactionHash] opened, read from the transaction rather than an index. */
  suspend fun createdSubaccount(transactionHash: String): AptosResult<String?> =
    AptosResult.Failure(AptosError.Validation("Created accounts are not available"))

  /**
   * The most [builder] may charge [subaccount], in hundredths of a basis point, or null when the
   * account hasn't approved it. Spot keeps its own registry.
   */
  suspend fun approvedBuilderFee(
    subaccount: String,
    builder: String,
    spot: Boolean,
  ): AptosResult<Long?> =
    AptosResult.Failure(AptosError.Validation("Builder approvals are not available"))
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
        if (result.error.isAbort()) AptosResult.Success(SubaccountStatus.NOT_SUBACCOUNT) else result
    }
  }

  override suspend fun delegations(subaccount: String): AptosResult<List<Delegation>> {
    val normalized =
      try {
        AccountAddress.fromString(subaccount.trim()).toString()
      } catch (error: Exception) {
        return AptosResult.Failure(AptosError.Validation(error.message ?: "Invalid address"))
      }
    return when (
      val result =
        aptos.views.callRaw(
          function = "${deployment.packageAddress}::dex_accounts::view_delegated_permissions",
          arguments = listOf(JsonPrimitive(normalized)),
        )
    ) {
      is AptosResult.Success ->
        try {
          AptosResult.Success(parseDelegatedPermissions(result.value.values.firstOrNull()))
        } catch (error: IllegalArgumentException) {
          AptosResult.Failure(AptosError.Serialization(error.message ?: "Unexpected view result"))
        }
      is AptosResult.Failure -> result
    }
  }

  override suspend fun approvedBuilderFee(
    subaccount: String,
    builder: String,
    spot: Boolean,
  ): AptosResult<Long?> {
    val (account, builderAccount) =
      try {
        AccountAddress.fromString(subaccount.trim()).toString() to
          AccountAddress.fromString(builder.trim()).toString()
      } catch (error: Exception) {
        return AptosResult.Failure(AptosError.Validation(error.message ?: "Invalid address"))
      }
    val registry = if (spot) "spot_builder_code_registry" else "builder_code_registry"
    return when (
      val result =
        aptos.views.callRaw(
          function = "${deployment.packageAddress}::$registry::get_approved_max_fee",
          arguments = listOf(JsonPrimitive(account), JsonPrimitive(builderAccount)),
        )
    ) {
      is AptosResult.Success -> {
        val option = result.value.values.firstOrNull() as? JsonObject
        val vec = option?.get("vec")?.jsonArray
        if (vec == null) AptosResult.Failure(AptosError.Serialization("Unexpected view result"))
        else AptosResult.Success(vec.firstOrNull()?.jsonPrimitive?.contentOrNull?.toLongOrNull())
      }
      is AptosResult.Failure -> result
    }
  }

  override suspend fun createdSubaccount(transactionHash: String): AptosResult<String?> =
    when (
      val result =
        aptos.transactions.waitForTransaction(
          transactionHash,
          WaitForTransactionOptions(timeoutSecs = 10, checkSuccess = false),
        )
    ) {
      is AptosResult.Success ->
        AptosResult.Success(
          (result.value as? UserTransactionResponse)?.let {
            createdSubaccount(it.events, deployment.packageAddress)
          },
        )
      is AptosResult.Failure -> result
    }
}

/** Only Decibel's own `SubaccountCreatedEvent` names the new account. */
internal fun createdSubaccount(events: List<Event>, packageAddress: String): String? {
  val expected = AccountAddress.fromString(packageAddress).toString()
  return events.firstNotNullOfOrNull { event ->
    val parts = event.type?.split("::") ?: return@firstNotNullOfOrNull null
    val emitter = runCatching { AccountAddress.fromString(parts[0]).toString() }.getOrNull()
    val created =
      parts.size == 3 &&
        emitter == expected &&
        parts[1] == "dex_accounts" &&
        parts[2] == "SubaccountCreatedEvent"
    if (created) (event.data as? JsonObject)?.get("subaccount")?.jsonPrimitive?.contentOrNull
    else null
  }
}

internal fun parseDelegatedPermissions(value: JsonElement?): List<Delegation> {
  val delegates = value?.jsonObject?.get("entries")?.jsonArray ?: return emptyList()
  return delegates.flatMap { entry ->
    val delegate =
      entry.jsonObject["key"]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
    val perms =
      entry.jsonObject["value"]?.jsonObject?.get("perms")?.jsonObject?.get("entries")?.jsonArray
        ?: return@flatMap emptyList()
    perms.mapNotNull { perm ->
      val key = perm.jsonObject["key"] as? JsonObject ?: return@mapNotNull null
      val type = key["__variant__"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      val stored = perm.jsonObject["value"] as? JsonObject
      Delegation(
        delegate = delegate,
        expirationTimeSeconds =
          (stored?.get("expiration_time_s") ?: stored?.get("_0"))
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull(),
        permissionType = type,
        permissionMarket = key["market"]?.let(::objectAddress),
      )
    }
  }
}

private fun objectAddress(element: JsonElement): String? =
  (element as? JsonObject)?.get("inner")?.jsonPrimitive?.contentOrNull
    ?: (element as? JsonPrimitive)?.contentOrNull

private fun AptosError.isAbort(): Boolean =
  this is AptosError.Api && (vmErrorCode == 4016L || message.contains("ABORTED"))
