package xyz.mcxross.flare.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.design.ActionNotice
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.FlareTextField
import xyz.mcxross.flare.design.ProtectFromScreenCapture
import xyz.mcxross.flare.design.SwitchRow

@Composable
fun JevKeySheet(state: JevSettingsUiState, onIntent: (JevSettingsIntent) -> Unit) {
  ProtectFromScreenCapture()
  FlareSheet("Jev confluence", { onIntent(JevSettingsIntent.Dismiss) }) {
    Text(
      "Optional second opinion for strategy signals. Requests go directly from this device to Jev " +
        "with your API key and market indicators. Your wallet keys are never included. " +
        "Jev usage may incur charges on your account.",
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodyMedium,
    )
    SwitchRow(
      "Use Jev confluence",
      "Strategies also work with this turned off.",
      state.metadata.enabled,
      { onIntent(JevSettingsIntent.SetEnabled(it)) },
      enabled = state.metadata.configured && !state.busy,
    )
    Text(
      if (state.metadata.configured) "API key saved on this device" else "No API key saved",
      style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))
    FlareTextField(
      value = state.keyInput,
      onValueChange = { onIntent(JevSettingsIntent.ChangeKey(it)) },
      placeholder = if (state.metadata.configured) "Replacement API key" else "Jev API key",
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
      visualTransformation = PasswordVisualTransformation(),
    )
    Spacer(Modifier.height(16.dp))
    FlareButton(
      if (state.metadata.configured) "Replace key" else "Save key",
      { onIntent(JevSettingsIntent.Save) },
      Modifier.fillMaxWidth(),
      enabled = !state.busy && state.keyInput.isNotEmpty(),
      working = state.busy,
    )
    FlareButton(
      "Check entered key",
      { onIntent(JevSettingsIntent.Check) },
      Modifier.fillMaxWidth().padding(top = 12.dp),
      style = FlareButtonStyle.OUTLINE,
      enabled = !state.busy && state.keyInput.isNotEmpty(),
    )
    if (state.metadata.configured) {
      FlareButton(
        "Remove key",
        { onIntent(JevSettingsIntent.Remove) },
        Modifier.fillMaxWidth().padding(top = 12.dp),
        style = FlareButtonStyle.OUTLINE,
        enabled = !state.busy,
      )
    }
    state.notice?.let { ActionNotice(it, Modifier.padding(top = 16.dp)) }
  }
}
