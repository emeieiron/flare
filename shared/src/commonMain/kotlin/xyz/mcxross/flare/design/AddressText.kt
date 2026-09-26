package xyz.mcxross.flare.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** How many characters at each end of an address read brighter. */
private const val ADDRESS_EMPHASIS = 6

/**
 * An address in two even lines. The start and end, which people compare against another screen,
 * read brighter than the middle.
 */
fun emphasizedAddress(address: String): AnnotatedString {
  val prefix = if (address.startsWith("0x")) "0x" else ""
  val hex = address.removePrefix(prefix)
  val breakAt = (hex.length + 1) / 2
  return buildAnnotatedString {
    withStyle(SpanStyle(color = FlareColors.TextPrimary)) { append(prefix) }
    hex.forEachIndexed { index, char ->
      if (index == breakAt && hex.length > 2 * ADDRESS_EMPHASIS) append('\n')
      val bright = index < ADDRESS_EMPHASIS || index >= hex.length - ADDRESS_EMPHASIS
      withStyle(SpanStyle(color = if (bright) FlareColors.TextPrimary else FlareColors.TextTertiary)) {
        append(char)
      }
    }
  }
}

/** An Aptos account address: `0x` and up to 64 hex digits. */
fun String.isAptosAddress(): Boolean = AptosAddress.matches(trim())

private val AptosAddress = Regex("^0x[0-9a-fA-F]{1,64}$")

/**
 * An address input on the same surface as [FlareAmountField]: the label sits inside, the border only
 * appears for an error. Empty, it offers to paste; filled, to clear.
 */
@Composable
fun FlareAddressField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: String = "Address",
  placeholder: String = "0x…",
  supportingText: String? = null,
  isError: Boolean = false,
  enabled: Boolean = true,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
  val clipboard = LocalClipboardManager.current
  val focused by interactionSource.collectIsFocusedAsState()
  val labelColor by animateColorAsState(
    when {
      !enabled -> FlareColors.TextDisabled
      isError -> FlareColors.Negative
      focused -> FlareColors.TextPrimary
      else -> FlareColors.TextTertiary
    }
  )
  val shape = RoundedCornerShape(18.dp)
  val valueStyle = MaterialTheme.typography.bodyLarge
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicTextField(
      value = value,
      onValueChange = { onValueChange(it.filterNot(Char::isWhitespace)) },
      modifier = Modifier.fillMaxWidth()
        .clip(shape)
        .background(FlareColors.Surface)
        .then(if (isError) Modifier.border(1.dp, FlareColors.Negative, shape) else Modifier)
        .padding(horizontal = 16.dp, vertical = 14.dp),
      enabled = enabled,
      singleLine = false,
      maxLines = 2,
      keyboardOptions =
        KeyboardOptions(
          capitalization = KeyboardCapitalization.None,
          autoCorrectEnabled = false,
          keyboardType = KeyboardType.Ascii,
          imeAction = ImeAction.Next,
        ),
      interactionSource = interactionSource,
      textStyle = valueStyle.copy(
        color = if (enabled) FlareColors.TextPrimary else FlareColors.TextDisabled,
      ),
      cursorBrush = SolidColor(if (isError) FlareColors.Negative else FlareColors.Positive),
      decorationBox = { innerTextField ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(text = label, style = MaterialTheme.typography.labelSmall, color = labelColor)
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
              if (value.isEmpty()) {
                Text(text = placeholder, style = valueStyle, color = FlareColors.TextDisabled)
              }
              innerTextField()
            }
            if (value.isEmpty()) {
              Box(
                Modifier.padding(start = 10.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .border(1.dp, FlareColors.BorderDefault, RoundedCornerShape(8.dp))
                  .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Paste address") {
                    clipboard.getText()?.text?.let { onValueChange(it.trim()) }
                  }
                  .padding(horizontal = 10.dp, vertical = 5.dp),
              ) {
                Text("Paste", style = MaterialTheme.typography.labelMedium, color = FlareColors.Positive)
              }
            } else if (enabled) {
              Box(
                Modifier.padding(start = 10.dp)
                  .size(24.dp)
                  .clip(CircleShape)
                  .background(FlareColors.Elevated)
                  .clickable(role = Role.Button, onClickLabel = "Clear address") { onValueChange("") },
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  Icons.Outlined.Close,
                  contentDescription = null,
                  modifier = Modifier.size(14.dp),
                  tint = FlareColors.TextSecondary,
                )
              }
            }
          }
        }
      },
    )
    if (supportingText != null) {
      Text(
        text = supportingText,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) FlareColors.Negative else FlareColors.TextSecondary,
      )
    }
  }
}
