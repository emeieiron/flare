package xyz.mcxross.flare.security

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class ResumedHostTest {
  private class Screen(val name: String) : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this)

    fun move(to: Lifecycle.State) {
      lifecycle.currentState = to
    }
  }

  @Test
  fun aPromptAfterTheFirstScreenIsGoneUsesTheNewOne() = runTest {
    val host = ResumedHost<String>()
    val first = Screen("first").also { host.attach(it, it.name) }
    first.move(Lifecycle.State.RESUMED)
    assertEquals("first", host.await())

    // Leaving with Back saves the first screen's state and destroys it.
    first.move(Lifecycle.State.DESTROYED)
    val second = Screen("second").also { host.attach(it, it.name) }
    second.move(Lifecycle.State.STARTED)
    val prompt = async(UnconfinedTestDispatcher(testScheduler)) { host.await() }
    assertTrue(prompt.isActive)

    second.move(Lifecycle.State.RESUMED)
    assertEquals("second", prompt.await())
  }

  @Test
  fun aPausedScreenIsNotUsed() = runTest {
    val host = ResumedHost<String>()
    val screen = Screen("only").also { host.attach(it, it.name) }
    screen.move(Lifecycle.State.RESUMED)
    screen.move(Lifecycle.State.STARTED)
    val prompt = async(UnconfinedTestDispatcher(testScheduler)) { host.await() }
    assertTrue(prompt.isActive)
    screen.move(Lifecycle.State.RESUMED)
    assertEquals("only", prompt.await())
  }
}
