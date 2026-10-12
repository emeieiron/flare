package xyz.mcxross.flare.venue.hyperliquid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.mcxross.flare.data.OwnerBackup
import xyz.mcxross.flare.data.WalletProfile
import xyz.mcxross.flare.hyperliquid.signing.EvmKeys
import xyz.mcxross.flare.hyperliquid.signing.EvmSigner
import xyz.mcxross.flare.hyperliquid.signing.FastKryptoSigner
import xyz.mcxross.flare.security.ForegroundWalletVault
import xyz.mcxross.flare.security.VaultPrompt
import xyz.mcxross.flare.security.WalletSecretSlot
import xyz.mcxross.flare.store.AccountProfile
import xyz.mcxross.flare.store.AppPreferences

/**
 * EVM keys in the platform vault: the owner as a recovery phrase or a `0x` private key, and the
 * device trading key (an approved agent) as a `0x` private key, under the vault's shared slot names
 * and identified by their EIP-55 addresses.
 */
class HyperliquidWalletRepository(
  private val vault: ForegroundWalletVault,
  private val preferences: AppPreferences,
) : HyperliquidSigningWallet {
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
    val phrase = EvmKeys.generateMnemonic()
    val address = EvmKeys.address(EvmKeys.privateKeyFromMnemonic(phrase))
    val id = "owner_$address"
    storeText(WalletSecretSlot.OWNER_MNEMONIC.forProfile(id), phrase, prompt)
    preferences.registerProfile(
      AccountProfile(id, ownerAddress = address, ownerBackupConfirmed = false, createdInApp = true)
    )
    return OwnerBackup(address, phrase.split(' '))
  }

  override suspend fun importOwner(phrase: String, prompt: VaultPrompt): String {
    val credential = HyperliquidCredentialParser.normalize(phrase)
    val address = EvmKeys.address(HyperliquidCredentialParser.ownerKey(credential))
    val saved = preferences.values.first().profiles.firstOrNull { it.ownerAddress == address }
    val id = saved?.id ?: "owner_$address"
    storeText(WalletSecretSlot.OWNER_MNEMONIC.forProfile(id), credential, prompt)
    preferences.registerProfile(
      (saved ?: AccountProfile(id)).copy(ownerAddress = address, ownerBackupConfirmed = true)
    )
    return address
  }

  override suspend fun confirmOwnerBackup() = preferences.setOwnerBackupConfirmed(true)

  override suspend fun createApiWallet(prompt: VaultPrompt): String =
    profile.first().apiWalletAddress ?: newApiWallet(prompt)

  override suspend fun newApiWallet(prompt: VaultPrompt): String {
    val key = EvmKeys.generatePrivateKey()
    val address = EvmKeys.address(key)
    storeText(slot(WalletSecretSlot.API_PRIVATE_KEY), key, prompt)
    preferences.setApiWallet(address)
    return address
  }

  /** An agent key is checked before it's stored, so a rejected key never replaces a working one. */
  override suspend fun importApiWallet(
    key: String,
    prompt: VaultPrompt,
    verify: suspend (address: String) -> Unit,
  ): String {
    val normalized =
      runCatching { EvmKeys.normalizePrivateKey(key) }
        .getOrElse { throw IllegalArgumentException("Enter the API wallet’s private key.") }
    val address = EvmKeys.address(normalized)
    verify(address)
    val saved =
      preferences.values.first().profiles.firstOrNull {
        it.ownerAddress == null && it.apiWalletAddress.sameAs(address)
      }
    val id = saved?.id ?: "api_$address"
    storeText(WalletSecretSlot.API_PRIVATE_KEY.forProfile(id), normalized, prompt)
    preferences.registerProfile(saved ?: AccountProfile(id, apiWalletAddress = address))
    preferences.setApiWallet(address)
    return address
  }

  override suspend fun discardApiWallet(prompt: VaultPrompt) {
    vault.remove(slot(WalletSecretSlot.API_PRIVATE_KEY), prompt.copy(requireFreshAuthorization = false))
    preferences.setOnboardingComplete(false)
    preferences.setApiWallet(null)
  }

  override suspend fun <T> withOwnerSigner(prompt: VaultPrompt, block: suspend (EvmSigner) -> T): T =
    withSigner(WalletSecretSlot.OWNER_MNEMONIC, prompt, HyperliquidCredentialParser::ownerKey, block)

  override suspend fun <T> withAgentSigner(prompt: VaultPrompt, block: suspend (EvmSigner) -> T): T =
    withSigner(WalletSecretSlot.API_PRIVATE_KEY, prompt, EvmKeys::normalizePrivateKey, block)

  /** Backgrounding cancels the block, so a key can never sign for an unattended app. */
  private suspend fun <T> withSigner(
    slot: WalletSecretSlot,
    prompt: VaultPrompt,
    privateKey: (String) -> String,
    block: suspend (EvmSigner) -> T,
  ): T {
    val generation = authorizationGeneration
    val signer = FastKryptoSigner.fromHex(privateKey(readText(slot, prompt)))
    val expected =
      preferences.values.first().let {
        if (slot == WalletSecretSlot.OWNER_MNEMONIC) it.ownerAddress else it.apiWalletAddress
      }
    check(expected.sameAs(signer.address)) { "The stored key doesn’t match this account" }
    return vault.whileAuthorized(generation) { block(signer) }
  }

  override suspend fun exportOwnerMnemonic(prompt: VaultPrompt): String =
    readText(WalletSecretSlot.OWNER_MNEMONIC, prompt.copy(requireFreshAuthorization = true)).also {
      HyperliquidCredentialParser.normalize(it)
    }

  override suspend fun exportApiWallet(prompt: VaultPrompt): String =
    readText(WalletSecretSlot.API_PRIVATE_KEY, prompt.copy(requireFreshAuthorization = true))
      .also(EvmKeys::normalizePrivateKey)

  /** Removing every key of a profile forgets it, and the next stored profile becomes active. */
  override suspend fun removeOwner(prompt: VaultPrompt) {
    vault.remove(slot(WalletSecretSlot.OWNER_MNEMONIC), prompt.copy(requireFreshAuthorization = true))
    preferences.setOwnerWallet(null, backupConfirmed = false)
  }

  override suspend fun removeApiWallet(prompt: VaultPrompt) {
    vault.remove(slot(WalletSecretSlot.API_PRIVATE_KEY), prompt.copy(requireFreshAuthorization = true))
    preferences.setOnboardingComplete(false)
    preferences.setApiWallet(null)
  }

  override fun lock() = vault.lock()

  override suspend fun confirmIdentity(prompt: VaultPrompt) {
    val saved = preferences.values.first()
    val signing =
      if (saved.apiWalletAddress != null) WalletSecretSlot.API_PRIVATE_KEY
      else WalletSecretSlot.OWNER_MNEMONIC
    vault.read(slot(signing), prompt.copy(requireFreshAuthorization = true)).fill(0)
  }

  private suspend fun storeText(slot: WalletSecretSlot, value: String, prompt: VaultPrompt) {
    val bytes = value.encodeToByteArray()
    try {
      vault.store(slot, bytes, prompt)
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
