package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicAndroidHostTest {

  @Test
  fun anUnconfiguredBuildReachesTheMainnetWorker() {
    val runtime = DecibelRuntimeConfig()

    assertEquals("https://api.mcxross.xyz/aptos/v1", runtime.aptosFullnodeUrl)
  }

  @Test
  fun localWorkerConfigurationRemainsAvailableForAndroidDevelopment() {
    // The Android Emulator routes 10.0.2.2 to the development host.
    val runtime = DecibelRuntimeConfig(workerBaseUrl = "http://10.0.2.2:8787")

    assertEquals("http://10.0.2.2:8787/aptos/v1", runtime.aptosFullnodeUrl)
    assertEquals("ws://10.0.2.2:8787/decibel/ws", runtime.decibelWebSocketUrl)
  }
}
