package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.core.toDecimalString
import xyz.mcxross.flare.data.WalletBalanceRepository
import xyz.mcxross.flare.decibel.DecibelDeployment
import xyz.mcxross.kaptos.Aptos
import xyz.mcxross.kaptos.account.AccountAsset
import xyz.mcxross.kaptos.model.AccountAddress
import xyz.mcxross.kaptos.model.AptosResult
import xyz.mcxross.kaptos.util.APTOS_COIN

class AptosWalletBalanceRepository(
  private val aptos: Aptos,
  private val deployment: DecibelDeployment,
) : WalletBalanceRepository {
  override val symbols = listOf("APT", "USDC")

  override suspend fun balance(address: String, symbol: String): String {
    val (asset, decimals) = when (symbol) {
      "APT" -> AccountAsset.coin(APTOS_COIN) to 8
      "USDC" -> AccountAsset.fungibleAsset(deployment.usdcMetadataAddress) to 6
      else -> error("Unsupported wallet asset")
    }
    return when (val result = aptos.accounts.getBalance(AccountAddress.fromString(address), asset)) {
      is AptosResult.Success -> result.value.toDecimalString(decimals)
      is AptosResult.Failure -> error("The $symbol balance couldn’t be refreshed")
    }
  }
}
