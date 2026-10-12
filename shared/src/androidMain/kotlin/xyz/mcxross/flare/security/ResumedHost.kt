package xyz.mcxross.flare.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The screen prompts are shown over: whichever attached host is resumed. A host that was paused or
 * destroyed is never handed out, so a prompt can't bind to a screen that has saved its state.
 */
internal class ResumedHost<T : Any> {
  private val resumed = MutableStateFlow<T?>(null)

  fun attach(owner: LifecycleOwner, host: T) {
    owner.lifecycle.addObserver(
      object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
          resumed.value = host
        }

        override fun onPause(owner: LifecycleOwner) {
          resumed.compareAndSet(host, null)
        }
      }
    )
  }

  /** The resumed host, waiting for one when the app is between screens or in the background. */
  suspend fun await(): T = resumed.filterNotNull().first()
}
