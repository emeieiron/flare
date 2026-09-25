package xyz.mcxross.flare.security

import xyz.mcxross.flare.store.LEGACY_PROFILE_ID

data class WalletSecretSlot(val storageKey: String) {
  /** Secrets of the migrated profile keep their original keys; later profiles namespace theirs. */
  fun forProfile(id: String) =
    if (id == LEGACY_PROFILE_ID) this else WalletSecretSlot("${id}_$storageKey")

  companion object {
    val OWNER_MNEMONIC = WalletSecretSlot("owner_mnemonic")
    val API_PRIVATE_KEY = WalletSecretSlot("api_private_key")
  }
}

data class VaultPrompt(
  val title: String,
  val subtitle: String,
  val cancelLabel: String = "Cancel",
  val requireFreshAuthorization: Boolean = false,
)

interface WalletVault {
  suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt)

  suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray

  suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt)

  fun lock()
}

class UnavailableWalletVault : WalletVault {
  override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) {
    throw WalletVaultException.Unavailable("Secure wallet storage is unavailable in previews")
  }

  override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray {
    throw WalletVaultException.Unavailable("Secure wallet storage is unavailable in previews")
  }

  override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) {
    throw WalletVaultException.Unavailable("Secure wallet storage is unavailable in previews")
  }

  override fun lock() = Unit
}

const val AUTHORIZATION_CANCELLED_MESSAGE = "Authorization was cancelled"

/** The person dismissed an authorization prompt: nothing was signed, so there is nothing to report. */
fun Throwable.isAuthorizationCancelled(): Boolean =
  this is WalletVaultException.Cancelled || message == AUTHORIZATION_CANCELLED_MESSAGE

sealed class WalletVaultException(message: String, cause: Throwable? = null) :
  Exception(message, cause) {
  class Cancelled : WalletVaultException(AUTHORIZATION_CANCELLED_MESSAGE)

  class Unavailable(message: String) : WalletVaultException(message)

  class Corrupted(cause: Throwable? = null) :
    WalletVaultException("Stored wallet data is corrupted", cause)
}
