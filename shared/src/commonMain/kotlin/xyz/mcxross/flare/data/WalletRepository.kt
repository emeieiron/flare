package xyz.mcxross.flare.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.WalletSecretSlot
import xyz.mcxross.flare.security.WalletVault
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
  val authorizationGeneration: Long
    get() = 0L

  fun requireAuthorization(generation: Long) {}

  val profile: Flow<WalletProfile>

  suspend fun createOwner(prompt: VaultPrompt): OwnerBackup

  suspend fun importOwner(phrase: String, prompt: VaultPrompt): String

  suspend fun confirmOwnerBackup()

  suspend fun createApiWallet(prompt: VaultPrompt): String

  suspend fun importApiWallet(aip80: String, prompt: VaultPrompt): String

  suspend fun importVerifiedApi(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (Ed25519Account) -> Unit,
  ): String = error("Verified import unavailable")

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
  override val authorizationGeneration: Long
    get() = (vault as? xyz.mcxross.flare.security.ForegroundWalletVault)?.generation ?: 0L

  override fun requireAuthorization(generation: Long) {
    (vault as? xyz.mcxross.flare.security.ForegroundWalletVault)?.requireVisit(generation)
  }

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

  override suspend fun importApiWallet(aip80: String, prompt: VaultPrompt): String =
    importVerifiedApi(aip80, prompt) {}

  override suspend fun importVerifiedApi(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (Ed25519Account) -> Unit,
  ): String {
    val aip80 = key
    val validated = Aip80PrivateKey.parse(WalletCredential.normalize(aip80))
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

  override suspend fun removeOwner(prompt: VaultPrompt) {
    vault.remove(
      slot(WalletSecretSlot.OWNER_MNEMONIC),
      prompt.copy(requireFreshAuthorization = true),
    )
    preferences.setOwnerWallet(null, backupConfirmed = false)
    selectRemainingProfileIfEmpty()
  }

  override suspend fun removeApiWallet(prompt: VaultPrompt) {
    vault.remove(
      slot(WalletSecretSlot.API_PRIVATE_KEY),
      prompt.copy(requireFreshAuthorization = true),
    )
    preferences.setApiWallet(null)
    preferences.setOnboardingComplete(false)
    selectRemainingProfileIfEmpty()
  }

  private suspend fun selectRemainingProfileIfEmpty() {
    val saved = preferences.values.first()
    if (saved.ownerAddress == null && saved.apiWalletAddress == null) {
      val next = saved.profiles.firstOrNull()
      if (next != null) preferences.activateProfile(next.id)
      else {
        preferences.setSelectedSubaccount(null)
        preferences.setOnboardingComplete(false)
      }
    }
  }

  override fun lock() = vault.lock()

  override suspend fun <T> withOwnerAccount(
    prompt: VaultPrompt,
    block: suspend (Ed25519Account) -> T,
  ): T {
    val generation = authorizationGeneration
    val phrase = readText(WalletSecretSlot.OWNER_MNEMONIC, prompt)
    val account = ownerAccount(phrase)
    return try {
      val foreground = vault as? xyz.mcxross.flare.security.ForegroundWalletVault
      if (foreground != null) foreground.whileAuthorized(generation) { block(account) }
      else block(account)
    } finally {
      account.close()
    }
  }

  override suspend fun <T> withApiAccount(
    prompt: VaultPrompt,
    block: suspend (Ed25519Account) -> T,
  ): T {
    val generation = authorizationGeneration
    val aip80 = readText(WalletSecretSlot.API_PRIVATE_KEY, prompt)
    val account = Ed25519Account(Ed25519PrivateKey.fromAip80(aip80))
    return try {
      val foreground = vault as? xyz.mcxross.flare.security.ForegroundWalletVault
      if (foreground != null) foreground.whileAuthorized(generation) { block(account) }
      else block(account)
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
