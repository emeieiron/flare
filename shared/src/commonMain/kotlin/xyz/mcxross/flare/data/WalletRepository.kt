package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import xyz.mcxross.flare.security.VaultPrompt

data class WalletProfile(
  val ownerAddress: String? = null,
  val apiWalletAddress: String? = null,
  val ownerBackupConfirmed: Boolean = false,
) {
  val apiOnly: Boolean
    get() = ownerAddress == null && apiWalletAddress != null
}

data class OwnerBackup(val address: String, val words: List<String>)

/**
 * The keys this device holds for the exchange: an owner (recovery phrase or key) and a trading key,
 * both in the platform vault and identified by their address. Signing stays inside the exchange's
 * repositories, which reach the keys through the venue's own wallet interface (Decibel's is
 * `AptosSigningWallet`).
 */
interface WalletRepository {
  /**
   * Asks the person to authenticate now (fingerprint, face, or passcode) with the key this device
   * signs with. Throws [xyz.mcxross.flare.security.WalletVaultException.Cancelled] if dismissed.
   */
  suspend fun confirmIdentity(prompt: VaultPrompt) = Unit

  /** Identifies the current foreground visit; signing must not cross two of them. */
  val authorizationGeneration: Long

  /** Fails when the app left the foreground since [generation] was read. */
  fun requireAuthorization(generation: Long)

  val profile: Flow<WalletProfile>

  suspend fun createOwner(prompt: VaultPrompt): OwnerBackup

  suspend fun importOwner(phrase: String, prompt: VaultPrompt): String

  suspend fun confirmOwnerBackup()

  suspend fun createApiWallet(prompt: VaultPrompt): String

  /**
   * Stores an imported trading key only after [verify] accepts the address it signs as, so a
   * rejected key never replaces a working one.
   */
  suspend fun importApiWallet(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (address: String) -> Unit,
  ): String

  suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String

  suspend fun exportApiWallet(prompt: VaultPrompt): String

  suspend fun removeOwner(prompt: VaultPrompt)

  suspend fun removeApiWallet(prompt: VaultPrompt)

  fun lock()
}
