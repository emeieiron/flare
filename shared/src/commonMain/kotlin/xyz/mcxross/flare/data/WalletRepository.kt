package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.WalletSecretSlot
import xyz.mcxross.flare.store.AccountProfile
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
   * Stores an imported trading key only after [verify] accepts it, so a rejected key never replaces
   * a working one.
   */
  suspend fun importApiWallet(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (Ed25519Account) -> Unit,
  ): String

  suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String

  suspend fun exportApiWallet(prompt: VaultPrompt): String

  suspend fun removeOwner(prompt: VaultPrompt)

  suspend fun removeApiWallet(prompt: VaultPrompt)

  fun lock()

  suspend fun <T> withOwnerAccount(prompt: VaultPrompt, block: suspend (Ed25519Account) -> T): T

  suspend fun <T> withApiAccount(prompt: VaultPrompt, block: suspend (Ed25519Account) -> T): T
}

class DefaultWalletRepository(
  private val vault: ForegroundWalletVault,
  private val preferences: AppPreferences,
) : WalletRepository {
  override val authorizationGeneration: Long
    get() = vault.generation

  override fun requireAuthorization(generation: Long) = vault.requireVisit(generation)

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
      val address = account.accountAddress.toString()
      val id = "owner_$address"
      storeText(WalletSecretSlot.OWNER_MNEMONIC.forProfile(id), phrase, prompt, scoped = true)
      preferences.registerProfile(AccountProfile(id, ownerAddress = address))
      preferences.setOwnerWallet(address, backupConfirmed = false)
      OwnerBackup(address, phrase.split(' '))
    } finally {
      account.close()
    }
  }

  override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String {
    val credential = WalletCredential.normalize(phrase)
    val account = ownerAccount(credential)
    return try {
      val address = account.accountAddress.toString()
      val saved = preferences.values.first().profiles.firstOrNull { it.ownerAddress == address }
      val id = saved?.id ?: "owner_$address"
      storeText(WalletSecretSlot.OWNER_MNEMONIC.forProfile(id), credential, prompt, scoped = true)
      preferences.registerProfile(saved ?: AccountProfile(id, ownerAddress = address))
      account.accountAddress.toString().also {
        preferences.setOwnerWallet(it, backupConfirmed = true)
      }
    } finally {
      account.close()
    }
  }

  override suspend fun confirmOwnerBackup() = preferences.setOwnerBackupConfirmed(true)

  override suspend fun createApiWallet(prompt: VaultPrompt): String {
    profile.first().apiWalletAddress?.let {
      return it
    }
    val privateKey = Ed25519PrivateKey.generate()
    val account = Ed25519Account(privateKey)
    return try {
      storeText(WalletSecretSlot.API_PRIVATE_KEY, privateKey.toAip80().value, prompt)
      account.accountAddress.toString().also { preferences.setApiWallet(it) }
    } finally {
      account.close()
    }
  }

  override suspend fun importApiWallet(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (Ed25519Account) -> Unit,
  ): String {
    val validated = Aip80PrivateKey.parse(WalletCredential.normalize(key))
    val privateKey = Ed25519PrivateKey.fromAip80(validated)
    val account = Ed25519Account(privateKey)
    return try {
      val address = account.accountAddress.toString()
      val saved =
        preferences.values.first().profiles.firstOrNull {
          it.ownerAddress == null && it.apiWalletAddress == address
        }
      val id = saved?.id ?: "api_$address"
      storeText(
        WalletSecretSlot.API_PRIVATE_KEY.forProfile(id),
        validated.value,
        prompt,
        scoped = true,
      )
      try {
        verify(account)
      } catch (error: Throwable) {
        if (saved == null)
          vault.remove(
            WalletSecretSlot.API_PRIVATE_KEY.forProfile(id),
            prompt.copy(requireFreshAuthorization = false),
          )
        throw error
      }
      preferences.registerProfile(saved ?: AccountProfile(id, apiWalletAddress = address))
      account.accountAddress.toString().also { preferences.setApiWallet(it) }
    } finally {
      account.close()
    }
  }

  override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String =
    readText(WalletSecretSlot.OWNER_MNEMONIC, prompt.copy(requireFreshAuthorization = true)).also {
      WalletCredential.normalize(it)
    }

  override suspend fun exportApiWallet(prompt: VaultPrompt): String =
    readText(WalletSecretSlot.API_PRIVATE_KEY, prompt.copy(requireFreshAuthorization = true))
      .also(Aip80PrivateKey::parse)

  /** Removing every key of a profile forgets it, and the next stored profile becomes active. */
  override suspend fun removeOwner(prompt: VaultPrompt) {
    vault.remove(
      slot(WalletSecretSlot.OWNER_MNEMONIC),
      prompt.copy(requireFreshAuthorization = true),
    )
    preferences.setOwnerWallet(null, backupConfirmed = false)
  }

  override suspend fun removeApiWallet(prompt: VaultPrompt) {
    vault.remove(
      slot(WalletSecretSlot.API_PRIVATE_KEY),
      prompt.copy(requireFreshAuthorization = true),
    )
    // Setup must run again before this device can trade, so record that before forgetting the key.
    preferences.setOnboardingComplete(false)
    preferences.setApiWallet(null)
  }

  override fun lock() = vault.lock()

  override suspend fun <T> withOwnerAccount(
    prompt: VaultPrompt,
    block: suspend (Ed25519Account) -> T,
  ): T = withAccount(WalletSecretSlot.OWNER_MNEMONIC, prompt, ::ownerAccount, block)

  override suspend fun <T> withApiAccount(
    prompt: VaultPrompt,
    block: suspend (Ed25519Account) -> T,
  ): T =
    withAccount(
      WalletSecretSlot.API_PRIVATE_KEY,
      prompt,
      { Ed25519Account(Ed25519PrivateKey.fromAip80(it)) },
      block,
    )

  /** Backgrounding cancels the block, so a key can never sign for an unattended app. */
  private suspend fun <T> withAccount(
    slot: WalletSecretSlot,
    prompt: VaultPrompt,
    open: (String) -> Ed25519Account,
    block: suspend (Ed25519Account) -> T,
  ): T {
    val generation = authorizationGeneration
    val account = open(readText(slot, prompt))
    return try {
      vault.whileAuthorized(generation) { block(account) }
    } finally {
      account.close()
    }
  }

  private suspend fun storeText(
    slot: WalletSecretSlot,
    value: String,
    prompt: VaultPrompt,
    scoped: Boolean = false,
  ) {
    val bytes = value.encodeToByteArray()
    try {
      vault.store(if (scoped) slot else slot(slot), bytes, prompt)
    } finally {
      bytes.fill(0)
    }
  }

  private suspend fun slot(slot: WalletSecretSlot) =
    slot.forProfile(preferences.values.first().activeProfileId)

  private suspend fun readText(slot: WalletSecretSlot, prompt: VaultPrompt): String {
    val bytes = vault.read(slot(slot), prompt)
    return try {
      bytes.decodeToString()
    } finally {
      bytes.fill(0)
    }
  }
}

private fun ownerAccount(credential: String): Ed25519Account =
  if (credential.startsWith("ed25519-priv-")) {
    Ed25519Account(Ed25519PrivateKey.fromAip80(credential))
  } else {
    Ed25519Account.fromMnemonic(MnemonicPhrase.parse(credential))
  }
