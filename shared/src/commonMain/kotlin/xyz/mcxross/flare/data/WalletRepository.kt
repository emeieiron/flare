package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.WalletSecretSlot
import xyz.mcxross.flare.security.WalletVault
import xyz.mcxross.flare.store.AppPreferences
import xyz.mcxross.kaptos.account.Ed25519Account
import xyz.mcxross.kaptos.core.crypto.Aip80PrivateKey
import xyz.mcxross.kaptos.core.crypto.Ed25519PrivateKey
import xyz.mcxross.kaptos.core.crypto.MnemonicPhrase
import xyz.mcxross.kaptos.core.crypto.MnemonicWordCount

data class WalletProfile(
  val ownerAddress: String? = null,
  val apiWalletAddress: String? = null,
  val ownerBackupConfirmed: Boolean = false,
) {
  val apiOnly: Boolean
    get() = ownerAddress == null && apiWalletAddress != null
}

data class OwnerBackup(val address: String, val words: List<String>)

interface WalletRepository {
  val profile: Flow<WalletProfile>

  suspend fun createOwner(prompt: VaultPrompt): OwnerBackup

  suspend fun importOwner(phrase: String, prompt: VaultPrompt): String

  suspend fun confirmOwnerBackup()

  suspend fun createApiWallet(prompt: VaultPrompt): String

  suspend fun importApiWallet(aip80: String, prompt: VaultPrompt): String

  suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String

  suspend fun exportApiWallet(prompt: VaultPrompt): String

  suspend fun removeOwner(prompt: VaultPrompt)

  suspend fun removeApiWallet(prompt: VaultPrompt)

  fun lock()

  suspend fun <T> withOwnerAccount(prompt: VaultPrompt, block: suspend (Ed25519Account) -> T): T

  suspend fun <T> withApiAccount(prompt: VaultPrompt, block: suspend (Ed25519Account) -> T): T
}

class DefaultWalletRepository(
  private val vault: WalletVault,
  private val preferences: AppPreferences,
) : WalletRepository {
  override val profile: Flow<WalletProfile> =
    preferences.values.map {
      WalletProfile(
        ownerAddress = it.ownerAddress,
        apiWalletAddress = it.apiWalletAddress,
        ownerBackupConfirmed = it.ownerBackupConfirmed,
      )
    }

  override suspend fun createOwner(prompt: VaultPrompt): OwnerBackup {
    val mnemonic = MnemonicPhrase.generate(MnemonicWordCount.Words12)
    val phrase = mnemonic.reveal()
    val account = Ed25519Account.fromMnemonic(mnemonic)
    return try {
      storeText(WalletSecretSlot.OWNER_MNEMONIC, phrase, prompt)
      val address = account.accountAddress.toString()
      preferences.setOwnerWallet(address, backupConfirmed = false)
      OwnerBackup(address, phrase.split(' '))
    } finally {
      account.close()
    }
  }

  override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String {
    val mnemonic = MnemonicPhrase.parse(phrase)
    val account = Ed25519Account.fromMnemonic(mnemonic)
    return try {
      storeText(WalletSecretSlot.OWNER_MNEMONIC, mnemonic.reveal(), prompt)
      account.accountAddress.toString().also {
        preferences.setOwnerWallet(it, backupConfirmed = true)
      }
    } finally {
      account.close()
    }
  }

  override suspend fun confirmOwnerBackup() = preferences.setOwnerBackupConfirmed(true)

  override suspend fun createApiWallet(prompt: VaultPrompt): String {
    val privateKey = Ed25519PrivateKey.generate()
    val account = Ed25519Account(privateKey)
    return try {
      storeText(WalletSecretSlot.API_PRIVATE_KEY, privateKey.toAip80().value, prompt)
      account.accountAddress.toString().also { preferences.setApiWallet(it) }
    } finally {
      account.close()
    }
  }

  override suspend fun importApiWallet(aip80: String, prompt: VaultPrompt): String {
    val validated = Aip80PrivateKey.parse(aip80)
    val privateKey = Ed25519PrivateKey.fromAip80(validated)
    val account = Ed25519Account(privateKey)
    return try {
      storeText(WalletSecretSlot.API_PRIVATE_KEY, validated.value, prompt)
      account.accountAddress.toString().also { preferences.setApiWallet(it) }
    } finally {
      account.close()
    }
  }

  override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String =
    readText(
        WalletSecretSlot.OWNER_MNEMONIC,
        prompt.copy(requireFreshAuthorization = true),
      )
      .also(MnemonicPhrase::parse)

  override suspend fun exportApiWallet(prompt: VaultPrompt): String =
    readText(
        WalletSecretSlot.API_PRIVATE_KEY,
        prompt.copy(requireFreshAuthorization = true),
      )
      .also(Aip80PrivateKey::parse)

  override suspend fun removeOwner(prompt: VaultPrompt) {
    vault.remove(
      WalletSecretSlot.OWNER_MNEMONIC,
      prompt.copy(requireFreshAuthorization = true),
    )
    preferences.setOwnerWallet(null, backupConfirmed = false)
  }

  override suspend fun removeApiWallet(prompt: VaultPrompt) {
    vault.remove(
      WalletSecretSlot.API_PRIVATE_KEY,
      prompt.copy(requireFreshAuthorization = true),
    )
    preferences.setApiWallet(null)
  }

  override fun lock() = vault.lock()

  override suspend fun <T> withOwnerAccount(
    prompt: VaultPrompt,
    block: suspend (Ed25519Account) -> T,
  ): T {
    val phrase = readText(WalletSecretSlot.OWNER_MNEMONIC, prompt)
    val account = Ed25519Account.fromMnemonic(MnemonicPhrase.parse(phrase))
    return try {
      block(account)
    } finally {
      account.close()
    }
  }

  override suspend fun <T> withApiAccount(
    prompt: VaultPrompt,
    block: suspend (Ed25519Account) -> T,
  ): T {
    val aip80 = readText(WalletSecretSlot.API_PRIVATE_KEY, prompt)
    val account = Ed25519Account(Ed25519PrivateKey.fromAip80(aip80))
    return try {
      block(account)
    } finally {
      account.close()
    }
  }

  private suspend fun storeText(slot: WalletSecretSlot, value: String, prompt: VaultPrompt) {
    val bytes = value.encodeToByteArray()
    try {
      vault.store(slot, bytes, prompt)
    } finally {
      bytes.fill(0)
    }
  }

  private suspend fun readText(slot: WalletSecretSlot, prompt: VaultPrompt): String {
    val bytes = vault.read(slot, prompt)
    return try {
      bytes.decodeToString()
    } finally {
      bytes.fill(0)
    }
  }
}
