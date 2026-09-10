package xyz.mcxross.flare.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only encrypted platform storage survives a foreground visit. Never persists this cache. */
class ForegroundWalletVault(private val platform: WalletVault) : WalletVault {
  private data class Visit(
    val generation: Long = 0,
    val secrets: Map<WalletSecretSlot, ByteArray> = emptyMap(),
  )

  private val visit = MutableStateFlow(Visit())
  private val mutex = Mutex()
  private val signingJobs = MutableStateFlow<Set<Job>>(emptySet())
  private val mutableUnlocked = MutableStateFlow(false)
  val unlocked: StateFlow<Boolean> = mutableUnlocked.asStateFlow()
  val generation: Long
    get() = visit.value.generation

  fun requireVisit(expectedGeneration: Long) {
    check(mutableUnlocked.value && generation == expectedGeneration) { "Open Flare to continue" }
  }

  suspend fun <T> whileAuthorized(expectedGeneration: Long, block: suspend () -> T): T {
    val job = currentCoroutineContext().job
    signingJobs.update { it + job }
    return try {
      requireVisit(expectedGeneration)
      block()
    } finally {
      signingJobs.update { it - job }
    }
  }

  suspend fun unlock(slots: List<WalletSecretSlot>, prompt: VaultPrompt) =
    mutex.withLock {
      if (mutableUnlocked.value) return@withLock
      val start = visit.value
      val loaded = mutableMapOf<WalletSecretSlot, ByteArray>()
      try {
        slots.distinct().forEachIndexed { index, slot ->
          loaded[slot] = platform.read(slot, prompt.copy(requireFreshAuthorization = index == 0))
        }
        check(visit.compareAndSet(start, start.copy(secrets = loaded.toMap()))) {
          "Open Flare to continue"
        }
        mutableUnlocked.value = true
        if (generation != start.generation) {
          mutableUnlocked.value = false
          throw WalletVaultException.Cancelled()
        }
      } catch (error: Throwable) {
        loaded.values.forEach { it.fill(0) }
        throw error
      }
    }

  override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) =
    mutex.withLock {
      val start = visit.value
      platform.store(slot, secret, prompt)
      val copy = secret.copyOf()
      if (!visit.compareAndSet(start, start.copy(secrets = start.secrets + (slot to copy)))) {
        copy.fill(0)
        throw WalletVaultException.Cancelled()
      }
      start.secrets[slot]?.fill(0)
      mutableUnlocked.value = true
      if (generation != start.generation) mutableUnlocked.value = false
    }

  override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray =
    mutex.withLock {
      val start = visit.value
      requireVisit(start.generation)
      if (!prompt.requireFreshAuthorization) {
        return@withLock start.secrets[slot]?.copyOf()
          ?: throw WalletVaultException.Unavailable("Account credentials are unavailable")
      }
      val bytes = platform.read(slot, prompt)
      try {
        requireVisit(start.generation)
        bytes.copyOf()
      } finally {
        bytes.fill(0)
      }
    }

  override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) =
    mutex.withLock {
      val start = visit.value
      platform.remove(slot, prompt)
      if (visit.compareAndSet(start, start.copy(secrets = start.secrets - slot)))
        start.secrets[slot]?.fill(0)
      Unit
    }

  override fun lock() {
    mutableUnlocked.value = false
    while (true) {
      val previous = visit.value
      if (visit.compareAndSet(previous, Visit(previous.generation + 1))) {
        previous.secrets.values.forEach { it.fill(0) }
        break
      }
    }
    signingJobs
      .getAndUpdate { emptySet() }
      .forEach { it.cancel(CancellationException("Flare entered the background")) }
    platform.lock()
  }
}
