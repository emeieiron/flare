package xyz.mcxross.flare.venue.hyperliquid

import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.hyperliquid.signing.EvmSigner
import xyz.mcxross.flare.security.VaultPrompt

/**
 * Hyperliquid's view of the device's keys: EVM signers for the owner, which signs user-signed
 * actions (approving agents and builder fees, moving funds), and for the device's agent, which signs
 * L1 actions (orders). Bound to the same instance as [WalletRepository].
 */
interface HyperliquidSigningWallet : WalletRepository {
  suspend fun <T> withOwnerSigner(prompt: VaultPrompt, block: suspend (EvmSigner) -> T): T

  suspend fun <T> withAgentSigner(prompt: VaultPrompt, block: suspend (EvmSigner) -> T): T

  /** Replaces the device's agent key with a new one, which no agent approval has ever named. */
  suspend fun newApiWallet(prompt: VaultPrompt): String

  /**
   * Forgets the device's agent key once the exchange no longer accepts it, so it's never approved
   * again: a deregistered agent's old signatures could be replayed.
   */
  suspend fun discardApiWallet(prompt: VaultPrompt)
}
