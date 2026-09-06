package xyz.mcxross.flare

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.mcxross.flare.core.FlareRuntimeConfig

class SharedLogicAndroidHostTest {

  @Test
  fun localWorkerConfigurationRemainsAvailableForAndroidDevelopment() {
    val runtime = FlareRuntimeConfig()

    assertEquals("http://127.0.0.1:8787/aptos/v1", runtime.aptosFullnodeUrl)
  }
}
