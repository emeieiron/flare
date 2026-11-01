package xyz.mcxross.flare.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import xyz.mcxross.flare.design.FlareButton
import xyz.mcxross.flare.design.FlareButtonStyle
import xyz.mcxross.flare.design.FlareColors
import xyz.mcxross.flare.design.FlareSheet
import xyz.mcxross.flare.design.shortAddress
import xyz.mcxross.flare.store.ACCOUNT_NAME_MAX_LENGTH

@Composable
internal fun AccountNameSheet(editor: AccountNameEditor, onIntent: (SettingsIntent) -> Unit) {
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = remember { FocusRequester() }
  var focused by remember { mutableStateOf(false) }
  var value by
    remember(editor.profileId, editor.address, editor.tradingAccount) {
      mutableStateOf(TextFieldValue(editor.name, TextRange(0, editor.name.length)))
    }
  val dismiss = {
    keyboard?.hide()
    onIntent(SettingsIntent.DismissAccountName)
  }
  val save = {
    if (editor.canSave) onIntent(SettingsIntent.SaveAccountName)
  }
  FlareSheet(
    title = if (editor.tradingAccount) "Rename trading account" else "Rename wallet",
    onDismiss = dismiss,
    dismissible = !editor.saving,
  ) {
    Text(
      shortAddress(editor.address),
      color = FlareColors.TextSecondary,
      style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(24.dp))
    Text("Name", style = MaterialTheme.typography.labelMedium, color = FlareColors.TextSecondary)
    Spacer(Modifier.height(6.dp))
    val border =
      when {
        editor.error != null -> FlareColors.Negative
        focused -> FlareColors.Positive
        else -> FlareColors.BorderSubtle
      }
    BasicTextField(
      value = value,
      onValueChange = {
        val changed = it.text != value.text
        value = it
        if (changed) onIntent(SettingsIntent.ChangeAccountName(it.text))
      },
      modifier =
        Modifier.fillMaxWidth()
          .focusRequester(focus)
          .onFocusChanged { focused = it.isFocused }
          .semantics { contentDescription = "Name" }
          .background(FlareColors.Elevated, RoundedCornerShape(14.dp))
          .border(1.dp, border, RoundedCornerShape(14.dp))
          .padding(14.dp),
      enabled = !editor.saving,
      singleLine = true,
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = FlareColors.TextPrimary),
      cursorBrush = SolidColor(FlareColors.Positive),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { save() }),
    )
    Row(
      Modifier.fillMaxWidth().padding(top = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        editor.error ?: "Name on this device",
        style = MaterialTheme.typography.bodySmall,
        color = if (editor.error != null) FlareColors.Negative else FlareColors.TextSecondary,
      )
      Text(
        "${value.text.trim().length}/$ACCOUNT_NAME_MAX_LENGTH",
        style = MaterialTheme.typography.bodySmall,
        color = FlareColors.TextSecondary,
      )
    }
    if (editor.hasCustomName)
      TextButton(
        onClick = {
          value = TextFieldValue(editor.defaultName, TextRange(0, editor.defaultName.length))
          onIntent(SettingsIntent.UseDefaultAccountName)
        },
        enabled = !editor.saving && !editor.useDefault,
      ) {
        Text("Use default name", color = FlareColors.TextSecondary)
      }
    Spacer(Modifier.height(24.dp))
    FlareButton(
      "Save",
      save,
      Modifier.fillMaxWidth(),
      enabled = editor.canSave,
      working = editor.saving,
    )
    Spacer(Modifier.height(8.dp))
    FlareButton(
      "Cancel",
      dismiss,
      Modifier.fillMaxWidth(),
      style = FlareButtonStyle.OUTLINE,
      enabled = !editor.saving,
    )
  }
  DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
  LaunchedEffect(editor.profileId, editor.address, editor.tradingAccount) {
    withFrameNanos {}
    focus.requestFocus()
    keyboard?.show()
  }
}
