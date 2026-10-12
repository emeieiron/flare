package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecibelRuntimeConfigTest {

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
}
