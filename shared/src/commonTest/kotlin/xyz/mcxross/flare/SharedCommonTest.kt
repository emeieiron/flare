package xyz.mcxross.flare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
  fun runtimeConfigKeepsTheWorkerAuthenticationOrigin() {
    assertEquals("flare://mobile", FlareRuntimeConfig().appOrigin)
    assertFailsWith<IllegalArgumentException> { FlareRuntimeConfig(appOrigin = "https://app") }
  }
}
