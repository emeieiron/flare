package xyz.mcxross.flare.security

enum class WalletSecretSlot(val storageKey: String) {
  OWNER_MNEMONIC("owner_mnemonic"),
  API_PRIVATE_KEY("api_private_key"),
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

sealed class WalletVaultException(message: String, cause: Throwable? = null) :
  Exception(message, cause) {
  class Cancelled : WalletVaultException("Authorization was cancelled")

  class Unavailable(message: String) : WalletVaultException(message)

  class Corrupted(cause: Throwable? = null) :
    WalletVaultException("Stored wallet data is corrupted", cause)
}
