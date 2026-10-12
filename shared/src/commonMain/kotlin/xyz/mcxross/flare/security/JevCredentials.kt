package xyz.mcxross.flare.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.mcxross.flare.store.AppPreferences

data class JevCredentialMetadata(
  val configured: Boolean = false,
  val enabled: Boolean = false,
  val revision: Long = 0,
)

class JevCredentials(
  private val vault: ForegroundWalletVault,
  private val preferences: AppPreferences,
) {
  private val revision = MutableStateFlow(0L)
  val metadata: Flow<JevCredentialMetadata> = combine(preferences.values, revision) { config, version ->
    JevCredentialMetadata(config.jevKeyConfigured, config.useJevConfluence, version)
  }
  private val mutex = Mutex()
  private val requests = MutableStateFlow<Set<Job>>(emptySet())
  private val prompt = VaultPrompt("Jev API key", "Confirm your identity")

  suspend fun store(key: String) {
    require(validInput(key)) {
      "Enter an API key without spaces or control characters (maximum 4,096 characters)."
    }
    mutex.withLock {
      cancelRequests()
      revision.update { it + 1 }
      preferences.setUseJevConfluence(false)
      val bytes = key.encodeToByteArray()
      try {
        vault.store(WalletSecretSlot.JEV_API_KEY, bytes, prompt)
        preferences.setJevKeyConfigured(true)
      } finally {
        bytes.fill(0)
      }
    }
  }

  suspend fun remove() = mutex.withLock {
    cancelRequests()
    revision.update { it + 1 }
    vault.remove(WalletSecretSlot.JEV_API_KEY, prompt)
    preferences.setJevKeyConfigured(false)
  }

  suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
    if (!enabled) cancelRequests()
    revision.update { it + 1 }
    preferences.setUseJevConfluence(enabled)
  }

  suspend fun preload() = mutex.withLock {
    if (!metadata.first().configured || !vault.unlocked.value) return@withLock
    val generation = vault.generation
    try {
      vault.preload(WalletSecretSlot.JEV_API_KEY, prompt)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: WalletVaultException.Cancelled) {
      return@withLock
    } catch (_: WalletVaultException.Corrupted) {
      if (vault.unlocked.value && vault.generation == generation) {
        preferences.setJevKeyConfigured(false)
      }
    } catch (_: Exception) {
      // Recovery belongs to this visit; a revoked visit must not change saved enablement.
      if (vault.unlocked.value && vault.generation == generation) {
        preferences.setUseJevConfluence(false)
      }
    }
  }

  suspend fun <T> withKey(block: suspend (String) -> T): T = coroutineScope {
    val job = currentCoroutineContext().job
    val (bytes, generation) = mutex.withLock {
      check(metadata.first().enabled) { "Enable Jev confluence in Settings to continue." }
      requests.update { it + job }
      val generation = vault.generation
      try {
        val config = preferences.values.first()
        if (!vault.unlocked.value && config.profiles.isEmpty()) {
          vault.unlock(listOf(WalletSecretSlot.JEV_API_KEY), prompt)
        }
        vault.read(WalletSecretSlot.JEV_API_KEY, prompt) to generation
      } catch (_: WalletVaultException.Corrupted) {
        requests.update { it - job }
        if (vault.generation == generation) preferences.setJevKeyConfigured(false)
        throw WalletVaultException.Unavailable("Save a Jev API key in Settings to continue.")
      } catch (error: Throwable) {
        requests.update { it - job }
        throw error
      }
    }
    try {
      vault.whileAuthorized(generation) {
        currentCoroutineContext().ensureActive()
        block(bytes.decodeToString()).also { currentCoroutineContext().ensureActive() }
      }
    } finally {
      bytes.fill(0)
      requests.update { it - job }
    }
  }

  private fun cancelRequests() {
    requests.getAndUpdate { emptySet() }.forEach {
      it.cancel(CancellationException("Jev credentials changed"))
    }
  }

  companion object {
    fun validInput(key: String): Boolean =
      key.isNotEmpty() && key.length <= 4_096 && key.all { !it.isWhitespace() && !it.isISOControl() }
  }
}
