package xyz.mcxross.flare.security

import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForegroundWalletVaultTest {
  private val owner = WalletSecretSlot.OWNER_MNEMONIC
  private val api = WalletSecretSlot.API_PRIVATE_KEY
  private val prompt = VaultPrompt("Open Flare", "Confirm your identity")

  @Test
  fun onePromptCoversAllProfilesAndDoesNotExpire() = runTest {
    val native = MemoryVault()
    val vault = ForegroundWalletVault(native)
    vault.unlock(listOf(owner, api, api.forProfile("second")), prompt)
    assertEquals(1, native.prompts.count { it.requireFreshAuthorization })
    val readsAtLaunch = native.prompts.size
    advanceTimeBy(60 * 60 * 1_000)
    repeat(3) { assertContentEquals(byteArrayOf(1, 2, 3), vault.read(api, prompt)) }
    assertEquals(readsAtLaunch, native.prompts.size)
    assertTrue(vault.unlocked.value)
  }

  @Test
  fun freshSensitiveApprovalDoesNotDestroyTradingVisit() = runTest {
    val native = MemoryVault()
    val vault = ForegroundWalletVault(native)
    vault.unlock(listOf(owner, api), prompt)
    val generation = vault.generation
    vault.read(owner, prompt.copy(requireFreshAuthorization = true)).fill(0)
    vault.read(api, prompt).fill(0)
    assertEquals(2, native.prompts.count { it.requireFreshAuthorization })
    vault.requireVisit(generation)
  }

  @Test
  fun backgroundWipesBytesAndRejectsPreviouslyAuthorizedSigning() = runTest {
    val native = MemoryVault()
    val vault = ForegroundWalletVault(native)
    vault.unlock(listOf(api), prompt)
    val generation = vault.generation
    vault.lock()
    assertFalse(vault.unlocked.value)
    assertTrue(native.returned.all { bytes -> bytes.all { it == 0.toByte() } })
    assertFailsWith<IllegalStateException> { vault.requireVisit(generation) }
    assertFailsWith<IllegalStateException> { vault.read(api, prompt) }
    vault.unlock(listOf(api), prompt)
    assertFailsWith<IllegalStateException> { vault.requireVisit(generation) }
  }

  @Test
  fun authenticationCompletingAfterBackgroundCannotReopenVault() = runTest {
    val native = MemoryVault()
    val waiting = CompletableDeferred<Unit>()
    native.wait = waiting
    val vault = ForegroundWalletVault(native)
    val opening = async { runCatching { vault.unlock(listOf(api), prompt) } }
    runCurrent()
    vault.lock()
    waiting.complete(Unit)
    assertTrue(opening.await().isFailure)
    assertFalse(vault.unlocked.value)
    assertTrue(native.returned.all { bytes -> bytes.all { it == 0.toByte() } })
  }

  @Test
  fun backgroundCancelsInFlightSigningAndRunsCleanup() = runTest {
    val vault = ForegroundWalletVault(MemoryVault())
    vault.unlock(listOf(api), prompt)
    var cleaned = false
    val signing = async {
      vault.whileAuthorized(vault.generation) {
        try {
          awaitCancellation()
        } finally {
          cleaned = true
        }
      }
    }
    runCurrent()
    vault.lock()
    assertFailsWith<CancellationException> { signing.await() }
    assertTrue(cleaned)
  }

  @Test
  fun callersCannotMutateCachedKey() = runTest {
    val vault = ForegroundWalletVault(MemoryVault())
    vault.unlock(listOf(api), prompt)
    vault.read(api, prompt).fill(0)
    assertContentEquals(byteArrayOf(1, 2, 3), vault.read(api, prompt))
  }
}

private class MemoryVault : WalletVault {
  val prompts = mutableListOf<VaultPrompt>()
  val returned = mutableListOf<ByteArray>()
  var wait: CompletableDeferred<Unit>? = null

  override suspend fun read(slot: WalletSecretSlot, prompt: VaultPrompt): ByteArray {
    prompts += prompt
    wait?.await()
    return byteArrayOf(1, 2, 3).also { returned += it }
  }

  override suspend fun store(slot: WalletSecretSlot, secret: ByteArray, prompt: VaultPrompt) {}

  override suspend fun remove(slot: WalletSecretSlot, prompt: VaultPrompt) {}

  override fun lock() {}
}
