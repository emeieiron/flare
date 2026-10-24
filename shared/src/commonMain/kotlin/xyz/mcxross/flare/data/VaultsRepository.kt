package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.venue.model.ActionState
import xyz.mcxross.flare.venue.model.VaultCollateral
import xyz.mcxross.flare.venue.model.VaultPosition
import xyz.mcxross.flare.venue.model.VaultSummary

/**
 * The venue's vaults and the selected account's stakes in them, in the neutral models of
 * `xyz.mcxross.flare.venue.model`. Only venues with vaults bind it; where nothing is bound the
 * screens leave vaults out.
 */
interface VaultsRepository {
  suspend fun vaults(limit: Int = 50): List<VaultSummary>

  /** The selected account's stakes in vaults. */
  suspend fun vaultPositions(): List<VaultPosition>

  fun contribute(
    vaultAddress: String,
    amount: String,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<ActionState>

  fun redeem(
    vaultAddress: String,
    shares: String,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<ActionState>

  /**
   * How much of this vault's shares the account may pledge as margin and already has, or null
   * where the venue doesn't take vault shares as collateral.
   */
  suspend fun collateral(vaultAddress: String): VaultCollateral? = null

  /**
   * Pledges [shares] of the vault as margin, or releases them when [pledge] is false. Only venues
   * whose [collateral] answers offer it.
   */
  fun setCollateral(
    vaultAddress: String,
    shares: String,
    pledge: Boolean,
    prompt: VaultPrompt,
    feePayment: FeePayment = FeePayment.SPONSORED,
  ): Flow<ActionState> =
    flowOf(
      ActionState.Failed("Vault shares aren't collateral here", definitelyNotSubmitted = true)
    )
}
