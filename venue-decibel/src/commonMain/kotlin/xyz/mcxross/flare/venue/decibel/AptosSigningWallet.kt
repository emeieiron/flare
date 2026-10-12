package xyz.mcxross.flare.venue.decibel

import xyz.mcxross.flare.data.WalletRepository
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.kaptos.account.Account
import xyz.mcxross.kaptos.model.AccountAddress

/**
 * Decibel's view of the device's keys: Kaptos accounts to sign Aptos transactions and Worker
 * challenges. Bound to the same instance as [WalletRepository].
 */
interface AptosSigningWallet : WalletRepository {
  /**
   * Stores an imported trading key only after [verify] accepts it, so a rejected key never replaces
   * a working one. [verify] may sign with the key, which [WalletRepository.importApiWallet] can't.
   */
  suspend fun importAptosApiWallet(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (Account) -> Unit,
  ): String

  suspend fun <T> withOwnerAccount(prompt: VaultPrompt, block: suspend (Account) -> T): T

  suspend fun <T> withApiAccount(prompt: VaultPrompt, block: suspend (Account) -> T): T
}

fun String.sameAptosAddress(other: String): Boolean =
  AccountAddress.fromString(this) == AccountAddress.fromString(other)
