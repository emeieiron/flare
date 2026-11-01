package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {
  @Test
  fun overlappingReadsShareSuccessButExplicitLaterReadsAreFresh() = runTest {
    val coordinator = RefreshCoordinator<String>()
    val release = CompletableDeferred<Unit>()
    var reads = 0
    launch {
      coordinator.run("a") {
        reads++
        release.await()
        true
      }
    }
    runCurrent()
    launch {
      coordinator.run("a") {
        reads++
        true
      }
    }
    runCurrent()
    assertEquals(1, reads)
    release.complete(Unit)
    runCurrent()
    assertEquals(1, reads)
    coordinator.run("a") {
      reads++
      true
    }
    assertEquals(2, reads)
  }

  @Test
  fun failureAndAccountSwitchCannotReuseAnotherResult() = runTest {
    val coordinator = RefreshCoordinator<String>()
    var reads = 0
    coordinator.run("a") {
      reads++
      false
    }
    coordinator.run("a", maxAgeMs = 2_000) {
      reads++
      true
    }
    coordinator.run("a", maxAgeMs = 2_000) {
      reads++
      true
    }
    coordinator.run("b", maxAgeMs = 2_000) {
      reads++
      true
    }
    assertEquals(3, reads)
  }

  @Test
  fun postTransactionReadCannotReuseARequestStartedBeforeTheTransaction() = runTest {
    val coordinator = RefreshCoordinator<String>()
    val release = CompletableDeferred<Unit>()
    var reads = 0
    launch {
      coordinator.run("a") {
        reads++
        release.await()
        true
      }
    }
    runCurrent()
    launch {
      coordinator.run("a", shareInFlight = false) {
        reads++
        true
      }
    }
    runCurrent()
    release.complete(Unit)
    runCurrent()
    assertEquals(2, reads)
  }

  @Test
  fun cancelledReadDoesNotBlockItsReplacement() = runTest {
    val coordinator = RefreshCoordinator<String>()
    val first = launch {
      coordinator.run("a") {
        CompletableDeferred<Unit>().await()
        true
      }
    }
    runCurrent()
    first.cancel()
    runCurrent()
    var loaded = false
    coordinator.run("a") {
      loaded = true
      true
    }
    assertEquals(true, loaded)
  }
}
