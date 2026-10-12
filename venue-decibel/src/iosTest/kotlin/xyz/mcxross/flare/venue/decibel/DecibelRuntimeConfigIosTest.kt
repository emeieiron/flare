package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicIOSTest {

  @Test
  fun secureWorkerUrlMapsToSecureIosWebSocketUrl() {
    val runtime = DecibelRuntimeConfig(workerBaseUrl = "https://worker.example")

    assertEquals("wss://worker.example/decibel/ws", runtime.decibelWebSocketUrl)
  }
}
