package xyz.mcxross.flare

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.flare.core.DecibelRuntimeConfig

class SharedLogicIOSTest {

  @Test
  fun secureWorkerUrlMapsToSecureIosWebSocketUrl() {
    val runtime = DecibelRuntimeConfig(workerBaseUrl = "https://worker.example")

    assertEquals("wss://worker.example/decibel/ws", runtime.decibelWebSocketUrl)
  }
}
