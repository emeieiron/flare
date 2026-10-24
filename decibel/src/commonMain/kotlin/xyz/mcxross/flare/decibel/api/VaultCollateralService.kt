package xyz.mcxross.flare.decibel.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosError
import xyz.mcxross.kaptos.model.AptosResult

/** A vault's shares as collateral: what may be pledged, and what already is. */
data class VaultShareCollateral(
  /** The share asset, which the pledge transaction names. */
  val assetMetadata: String,
  /** Shares free to pledge, in chain units. Shares inside the vault's lockup are excluded. */
  val pledgeable: ULong,
  /** Shares already counting toward margin, in chain units. */
  val pledged: ULong,
)

/**
 * Reads the chain for a vault's share asset and the account's balances of it. Decibel exposes no
 * REST endpoint for these, so each is a Move view.
 */
interface VaultCollateralService {
  suspend fun shareCollateral(vaultAddress: String, account: String): AptosResult<VaultShareCollateral>
}

internal class DefaultVaultCollateralService(
  private val aptos: Aptos,
  private val deployment: DecibelDeployment,
) : VaultCollateralService {
  override suspend fun shareCollateral(
    vaultAddress: String,
    account: String,
  ): AptosResult<VaultShareCollateral> {
    val vault = normalize(vaultAddress) ?: return invalid("Invalid vault address")
    val owner = normalize(account) ?: return invalid("Invalid account address")

    val metadata =
      when (val result = view("vault::get_vault_share_asset_type", listOf(JsonPrimitive(vault)))) {
        is AptosResult.Success ->
          result.value.values.firstOrNull()?.let(::objectAddress)
            ?: return invalid("The vault reported no share asset")
        is AptosResult.Failure -> return result
      }

    val pledgeable =
      when (
        val result =
          view(
            "vault_share_asset::get_user_unlocked_balance",
            listOf(JsonPrimitive(metadata), JsonPrimitive(owner)),
          )
      ) {
        is AptosResult.Success -> result.value.values.firstOrNull()?.toULongOrNull() ?: 0uL
        is AptosResult.Failure -> return result
      }

    val pledged =
      when (
        val result =
          view(
            "accounts_collateral::get_cross_secondary_balance",
            listOf(JsonPrimitive(owner), JsonPrimitive(metadata)),
          )
      ) {
        is AptosResult.Success -> result.value.values.firstOrNull()?.toULongOrNull() ?: 0uL
        is AptosResult.Failure -> return result
      }

    return AptosResult.Success(VaultShareCollateral(metadata, pledgeable, pledged))
  }

  private suspend fun view(function: String, arguments: List<JsonPrimitive>) =
    aptos.views.callRaw(function = "${deployment.packageAddress}::$function", arguments = arguments)

  private fun normalize(value: String): String? =
    runCatching { AccountAddress.fromString(value.trim()).toString() }.getOrNull()

  /** A view returning an `Object<T>` answers with `{"inner": "0x…"}`. */
  private fun objectAddress(element: kotlinx.serialization.json.JsonElement): String? =
    runCatching { element.jsonPrimitive.content }.getOrNull()
      ?: runCatching {
        (element as kotlinx.serialization.json.JsonObject)["inner"]?.jsonPrimitive?.content
      }.getOrNull()

  private fun kotlinx.serialization.json.JsonElement.toULongOrNull(): ULong? =
    runCatching { jsonPrimitive.content.toULong() }.getOrNull()
      ?: runCatching { jsonPrimitive.longOrNull?.toULong() }.getOrNull()

  private fun invalid(message: String): AptosResult<VaultShareCollateral> =
    AptosResult.Failure(AptosError.Validation(message))
}
