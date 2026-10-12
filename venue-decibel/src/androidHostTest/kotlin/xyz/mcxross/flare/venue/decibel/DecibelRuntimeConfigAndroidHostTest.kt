package xyz.mcxross.flare.venue.decibel

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicAndroidHostTest {

  @Test
  fun localWorkerConfigurationRemainsAvailableForAndroidDevelopment() {
    val runtime = DecibelRuntimeConfig()

    assertEquals("http://127.0.0.1:8787/aptos/v1", runtime.aptosFullnodeUrl)
  }
}
