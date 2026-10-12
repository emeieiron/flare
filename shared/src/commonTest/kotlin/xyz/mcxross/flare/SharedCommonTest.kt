package xyz.mcxross.flare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import xyz.mcxross.flare.core.DecibelRuntimeConfig
import xyz.mcxross.flare.core.FlareRuntimeConfig
import xyz.mcxross.flare.core.runSuspendCatching

class SharedCommonTest {

  @Test
  fun suspendResultPreservesCoroutineCancellation() = runTest {
    assertFailsWith<CancellationException> {
      runSuspendCatching<Unit> { throw CancellationException("test cancellation") }
    }
  }

  @Test
  fun runtimeConfigDerivesOnlyAllowlistedWorkerEndpoints() {
    val runtime = DecibelRuntimeConfig(workerBaseUrl = "https://worker.example/")

    assertEquals("https://worker.example/decibel", runtime.decibelRestUrl)
    assertEquals("wss://worker.example/decibel/ws", runtime.decibelWebSocketUrl)
    assertEquals("https://worker.example/aptos/v1", runtime.aptosFullnodeUrl)
    assertEquals(
      "http://10.0.2.2:8787/aptos/v1",
      DecibelRuntimeConfig(workerBaseUrl = "http://10.0.2.2:8787").aptosFullnodeUrl,
    )
    assertFailsWith<IllegalArgumentException> {
      DecibelRuntimeConfig(workerBaseUrl = "http://worker.example")
    }
    assertFailsWith<IllegalArgumentException> {
      DecibelRuntimeConfig(workerBaseUrl = "http://10.0.2.2.example:8787")
    }
  }

  @Test
  fun runtimeConfigKeepsTheWorkerAuthenticationOrigin() {
    assertEquals("flare://mobile", FlareRuntimeConfig().appOrigin)
    assertFailsWith<IllegalArgumentException> { FlareRuntimeConfig(appOrigin = "https://app") }
  }
}
