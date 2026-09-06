package xyz.mcxross.flare

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.flare.core.FlareRuntimeConfig

class SharedLogicIOSTest {

  @Test
  fun secureWorkerUrlMapsToSecureIosWebSocketUrl() {
    val runtime = FlareRuntimeConfig(workerBaseUrl = "https://worker.example")

    assertEquals("wss://worker.example/decibel/ws", runtime.decibelWebSocketUrl)
  }
}
