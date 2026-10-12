package xyz.mcxross.flare.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.data.SetupNeed

/** Takes the person to what [SetupNeed] asks for: the receive screen, or setup where it stopped. */
val LocalFinishSetup = staticCompositionLocalOf<(SetupNeed) -> Unit> { {} }

/**
 * Why an action is unavailable, as [reason]. When it waits on setup the wallet left for later, the
 * way to finish comes with it.
 */
@Composable
fun UnavailableNotice(reason: String, need: SetupNeed?, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth()) {
    ActionNotice(reason, tone = NoticeTone.INFO)
    if (need != null && reason == need.reason) {
      val finish = LocalFinishSetup.current
      FlareButton(
        need.actionLabel,
        { finish(need) },
        Modifier.fillMaxWidth().padding(top = 12.dp),
        style = FlareButtonStyle.OUTLINE,
      )
    }
  }
}
